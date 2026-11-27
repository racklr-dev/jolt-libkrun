;; jolt.libkrun.input — the native virtio-input keyboard path (libkrun 2.0).
;; Binds the host-side config + event-provider vtables libkrun drives on its
;; input worker thread. The event provider owns an eventfd (EFD_SEMAPHORE |
;; EFD_NONBLOCK) and a thread-safe FIFO: any host thread pushes a Linux input
;; event with send-event!/send-key!, the enqueue writes one semaphore unit to
;; the eventfd, libkrun's epoll wakes, next_event drains the fd then pops the
;; event — the exact protocol of the reference gui_vm's pollable_channel.
;;
;; C contract (include/libkrun_input.h + include/libkrun.h):
;;   krun_input_device_new(const void *config, size_t config_size,
;;                         const void *event_provider, size_t provider_size,
;;                         KrunError **err) -> KrunInputDevice (void*)
;;   krun_input_device_destroy(KrunInputDevice)
;;   krun_input_device_new_from_fd(int input_fd, KrunError **err)
;; Both structs are COPIED by krun_input_device_new; the function pointers in
;; them (our foreign-callables) must stay live for the device's lifetime.
;; config is 80 bytes: u64 features | ptr create_userdata | ptr create |
;; vtable[7] destroy/query_device_name/query_serial_name/query_device_ids/
;; query_event_capabilities/query_abs_info/query_properties.
;; event_provider is 48 bytes: u64 features | ptr create_userdata | ptr create |
;; vtable[3] destroy/get_ready_efd/next_event. create is left NULL — we hold
;; one global state per process (vmm_run is blocking, so one device at a time).
(ns jolt.libkrun.input
  (:require [jolt.ffi :as ffi]))

;; ---- libc (eventfd/read/write resolve from the process's own libc) --------
(ffi/defcfn c-eventfd "eventfd" [:uint :int] :int)
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t)
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t)
(ffi/defcfn c-close "close" [:int] :int)

;; ---- krun input device surface --------------------------------------------
(ffi/defcfn input-device-new "krun_input_device_new"
  [:pointer :size_t :pointer :size_t :pointer] :pointer)
(ffi/defcfn input-device-new-from-fd "krun_input_device_new_from_fd"
  [:int :pointer] :pointer)
(ffi/defcfn input-device-destroy "krun_input_device_destroy" [:pointer] :void)

;; ---- constants ------------------------------------------------------------
(def ^:private feature-query 1)          ; KRUN_INPUT_CONFIG_FEATURE_QUERY
(def ^:private feature-queue 1)          ; KRUN_INPUT_EVENT_PROVIDER_FEATURE_QUEUE
(def ^:private efd-semaphore 1)
(def ^:private efd-nonblock 2048)        ; O_NONBLOCK

;; Linux input (linux/input.h + input-event-codes.h)
(def ^:const ev-syn 0)
(def ^:const ev-key 1)
(def ^:const syn-report 0)
(def ^:const bus-virtual 6)              ; BUS_VIRTUAL. The reference's own
                                          ; input_constants.rs says 0x00 — a
                                          ; typo; 0x06 is the kernel's value.
(def ^:private vendor-rh 0x4852)         ; "RH" little-endian, as upstream
(def ^:private keyboard-name "libkrun Virtual Keyboard")
(def ^:private keyboard-serial "KRUN-KBD")

;; Key codes the virtual keyboard reports (input-event-codes.h KEY_*), copied
;; from the reference gui_vm's SUPPORTED_KEYBOARD_KEYS.
(def ^:private supported-keys
  [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28
   29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53
   54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70 71 72 73 74 75 76 77 78
   79 80 81 82 83 87 88 96 97 98 99 100 102 103 104 105 106 107 108 109 110
   111 113 114 115 116 119 139 158 172 210])

;; ---- layouts --------------------------------------------------------------
(def ^:private event-layout
  (ffi/layout [:struct [[:type :uint16] [:code :uint16] [:value :uint32]]]))
(def ^:private ids-layout
  (ffi/layout [:struct [[:bustype :uint16] [:vendor :uint16]
                        [:product :uint16] [:version :uint16]]]))
(def ^:private config-layout
  (ffi/layout [:struct [[:features :uint64]
                        [:create-userdata :pointer]
                        [:create :pointer]
                        [:destroy :pointer]
                        [:query-device-name :pointer]
                        [:query-serial-name :pointer]
                        [:query-device-ids :pointer]
                        [:query-event-caps :pointer]
                        [:query-abs-info :pointer]
                        [:query-properties :pointer]]]))
(def ^:private provider-layout
  (ffi/layout [:struct [[:features :uint64]
                        [:create-userdata :pointer]
                        [:create :pointer]
                        [:destroy :pointer]
                        [:get-ready-efd :pointer]
                        [:next-event :pointer]]]))

;; ---- state ----------------------------------------------------------------
;; {:efd int :queue atom-of-vector} — one device per process (vmm_run is
;; blocking); the queue atom is the FIFO the host thread writes and libkrun's
;; input worker thread reads.
(defonce ^:private active (atom {:efd nil :queue nil}))

;; ---- helpers --------------------------------------------------------------
(defn- write-name! [dst max-len s]
  (let [n (min (count s) (long max-len))]
    (dotimes [i n]
      (ffi/write dst :uint8 (int (nth s i)) i))
    (long n)))

(defn- write-bitmap!
  "Upstream write_bitmap: set bit (idx%8) of byte (idx/8) for each code;
  return highest byte index + 1 (so 1 even for an empty set)."
  [buf buf-len codes]
  (loop [codes codes max-byte 0]
    (if (empty? codes)
      (inc max-byte)
      (let [idx (first codes)
            byte-pos (quot idx 8)]
        (when (< byte-pos buf-len)
          (ffi/write buf :uint8
                     (bit-or (ffi/read buf :uint8 byte-pos)
                             (bit-shift-left 1 (mod idx 8)))
                     byte-pos))
        (recur (rest codes)
               (if (< byte-pos buf-len) (max max-byte byte-pos) max-byte))))))

(defn- q-pop! [queue]
  (loop []
    (let [v @queue]
      (if (empty? v)
        nil
        (let [e (first v)]
          (if (compare-and-set! queue v (subvec v 1))
            e
            (recur)))))))

;; ---- config + event-provider callbacks ------------------------------------
(defn- config-destroy-cb* [_instance] 0)

(defn- provider-destroy-cb* [_instance]
  (when-let [efd (:efd @active)] (c-close efd))
  (reset! active {:efd nil :queue nil})
  0)

(defn- query-device-name-cb* [_instance dst max-len]
  (write-name! dst max-len keyboard-name))

(defn- query-serial-name-cb* [_instance dst max-len]
  (write-name! dst max-len keyboard-serial))

(defn- query-device-ids-cb* [_instance ids-ptr]
  (ffi/write ids-ptr ids-layout {:bustype bus-virtual :vendor vendor-rh
                                 :product 1 :version 1})
  0)

(defn- query-event-caps-cb* [_instance event-type bitmap len]
  (case (long event-type)
    0 (write-bitmap! bitmap len supported-keys)   ; EV_SYN (upstream quirk)
    1 (write-bitmap! bitmap len supported-keys)   ; EV_KEY
    0))                                            ; EV_REP & others: none

(defn- query-abs-info-cb* [_instance _abs-axis _abs-info] 0)

(defn- query-properties-cb* [_instance bitmap len]
  (write-bitmap! bitmap len []))   ; no INPUT_PROP_* bits; upstream returns 1

(defn- get-ready-efd-cb* [_instance]
  (if-let [efd (:efd @active)] efd -1))

(defn- next-event-cb* [_instance out]
  (if-let [{:keys [efd queue]} @active]
    (ffi/with-alloc [rbuf 8]
      (if (neg? (c-read efd rbuf 8))
        0
        (if-let [ev (q-pop! queue)]
          (do (ffi/write out event-layout ev) 1)
          0)))
    0))

(defonce ^:private config-destroy-cb
  (ffi/foreign-callable config-destroy-cb* [:pointer] :int32 :collect-safe))
(defonce ^:private provider-destroy-cb
  (ffi/foreign-callable provider-destroy-cb* [:pointer] :int32 :collect-safe))
(defonce ^:private query-device-name-cb
  (ffi/foreign-callable query-device-name-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private query-serial-name-cb
  (ffi/foreign-callable query-serial-name-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private query-device-ids-cb
  (ffi/foreign-callable query-device-ids-cb* [:pointer :pointer] :int32 :collect-safe))
(defonce ^:private query-event-caps-cb
  (ffi/foreign-callable query-event-caps-cb* [:pointer :uint8 :pointer :size_t] :int32 :collect-safe))
(defonce ^:private query-abs-info-cb
  (ffi/foreign-callable query-abs-info-cb* [:pointer :uint8 :pointer] :int32 :collect-safe))
(defonce ^:private query-properties-cb
  (ffi/foreign-callable query-properties-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private get-ready-efd-cb
  (ffi/foreign-callable get-ready-efd-cb* [:pointer] :int :collect-safe))
(defonce ^:private next-event-cb
  (ffi/foreign-callable next-event-cb* [:pointer :pointer] :int32 :collect-safe))

;; ---- device builder -------------------------------------------------------
(defn keyboard-device
  "Build a virtio-input keyboard device (libkrun 2.0 native input path).
  Returns a KrunInputDevice handle to hand to krun_device_manager_add. The
  device reports a BUS_VIRTUAL keyboard named 'libkrun Virtual Keyboard';
  feed it with send-key!/send-event! from any host thread."
  []
  (let [err (ffi/alloc 8)
        config (ffi/alloc 80)
        provider (ffi/alloc 48)
        _ (reset! active {:efd (c-eventfd 0 (bit-or efd-semaphore efd-nonblock))
                          :queue (atom [])})
        _ (ffi/write config config-layout
                     {:features feature-query
                      :create-userdata ffi/null
                      :create ffi/null
                      :destroy config-destroy-cb
                      :query-device-name query-device-name-cb
                      :query-serial-name query-serial-name-cb
                      :query-device-ids query-device-ids-cb
                      :query-event-caps query-event-caps-cb
                      :query-abs-info query-abs-info-cb
                      :query-properties query-properties-cb})
        _ (ffi/write provider provider-layout
                     {:features feature-queue
                      :create-userdata ffi/null
                      :create ffi/null
                      :destroy provider-destroy-cb
                      :get-ready-efd get-ready-efd-cb
                      :next-event next-event-cb})
        dev (input-device-new config 80 provider 48 err)]
    (when (ffi/null? dev)
      (throw (ex-info "krun_input_device_new failed"
                      {:error (ffi/read err :pointer)})))
    dev))

;; ---- host-side event injection --------------------------------------------
(defn send-event!
  "Enqueue one Linux input event ({:type :code :value}) for the current
  device and signal its eventfd so libkrun's input worker wakes and drains it.
  Any host thread may call this."
  [{:keys [type code value]}]
  (let [{:keys [efd queue]} @active]
    (when (and efd queue)
      (swap! queue conj {:type type :code code :value value})
      (ffi/with-alloc [wbuf 8]
        (ffi/write wbuf :uint64 1)
        (c-write efd wbuf 8)))))

(defn send-key!
  "Enqueue an EV_KEY press (pressed? -> value 1) or release (value 0), plus
  the trailing EV_SYN/SYN_REPORT — the reference gui_vm key-handler shape."
  [code pressed?]
  (send-event! {:type ev-key :code code :value (if pressed? 1 0)})
  (send-event! {:type ev-syn :code syn-report :value 0}))
