;; jolt.libkrun.input — the native virtio-input path (libkrun 2.0).
;; Binds the host-side config + event-provider vtables libkrun drives on its
;; input worker thread, and exposes two virtual devices: a keyboard and an
;; absolute touchscreen (the mouse).
;;
;; Each device owns an eventfd (EFD_SEMAPHORE | EFD_NONBLOCK) and a
;; thread-safe FIFO. The create callback (the C ABI's per-device constructor,
;; libkrun_input.h krun_input_create_fn) allocates a native instance holding
;; the eventfd and hands it back through *instance; every subsequent callback
;; dereferences it. Any host thread pushes a Linux input event with
;; send-event!/send-key!/send-touch-event!, the enqueue writes one semaphore
;; unit to the eventfd, libkrun's epoll wakes, next_event drains the fd then
;; pops the event — the exact protocol of the reference gui_vm's
;; pollable_channel.
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
;; vtable[3] destroy/get_ready_efd/next_event.
;;
;; The config create runs on the boot thread (Input::new at attach); the event
;; provider create runs on libkrun's input worker thread during vmm_run. Both
;; are handled by role-tagged userdata pointers, so one process can attach a
;; keyboard AND a touchscreen at once (vmm_run is blocking — at most one of
;; each, keyed by role).
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
(def ^:const ev-abs 3)
(def ^:const syn-report 0)
(def ^:const bus-virtual 6)              ; BUS_VIRTUAL. The reference's own
                                          ; input_constants.rs says 0x00 — a
                                          ; typo; 0x06 is the kernel's value.
(def ^:private vendor-rh 0x4852)         ; "RH" little-endian, as upstream
(def ^:private keyboard-name "libkrun Virtual Keyboard")
(def ^:private keyboard-serial "KRUN-KBD")
(def ^:private touchscreen-name "libkrun Touchscreen")
(def ^:private touchscreen-serial "KRUN-TOUCH")
(def ^:private touchscreen-product 3)    ; reference TOUCHSCREEN_PRODUCT_ID 0x0003

;; touchscreen axes/buttons (input-event-codes.h)
(def ^:const btn-touch 0x14a)            ; BTN_TOUCH (330)
(def ^:const abs-x 0)                    ; ABS_X
(def ^:const abs-y 1)                    ; ABS_Y
(def ^:private input-prop-direct 1)      ; INPUT_PROP_DIRECT (bit 1)

;; device roles for the event-provider create callback
(def ^:private role-keyboard 1)
(def ^:private role-touchscreen 2)

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
(def ^:private abs-info-layout
  (ffi/layout [:struct [[:min :uint32] [:max :uint32] [:fuzz :uint32]
                        [:flat :uint32] [:res :uint32]]]))
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
;; Per-role state the host thread writes and the input worker reads: the eventfd
;; plus the queue atom. The eventfd + queue are created at DEVICE BUILD time (so
;; a host thread can enqueue immediately), and the provider create callback only
;; aliases them into the per-worker instance — the same shape as the reference
;; gui_vm, which builds the pollable_channel before the device and hands its
;; receiver to create as userdata. queues maps efd -> {:queue atom} so
;; next_event can find the queue from an instance pointer (which holds only the
;; efd — a Clojure atom cannot live in native memory). One keyboard + one
;; touchscreen per process (vmm_run is blocking).
(defonce ^:private queues (atom {}))
(defonce ^:private keyboard (atom {:efd nil :queue nil}))
(defonce ^:private touchscreen (atom {:efd nil :queue nil}))

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

(defn- provider-state!
  "Create the eventfd + queue for a device and register it under `role`, then
  answer a native pointer holding the eventfd — the provider create_userdata
  the worker's create callback reads back. Host threads can enqueue as soon as
  this returns, before libkrun's worker has attached the device."
  [role]
  (let [efd (c-eventfd 0 (bit-or efd-semaphore efd-nonblock))
        queue (atom [])
        ud (ffi/alloc 4)]
    (ffi/write ud :int efd)
    (swap! queues assoc efd {:queue queue})
    (if (= role role-touchscreen)
      (reset! touchscreen {:efd efd :queue queue})
      (reset! keyboard {:efd efd :queue queue}))
    ud))

;; ---- config callbacks (shared create/destroy + per-device query) ----------
(defn- config-create-cb* [instance-ptr userdata _reserved]
  ;; userdata is NULL for the keyboard (its query callbacks need no state) and
  ;; an 8-byte {width height} struct for the touchscreen (query_abs_info reads
  ;; it). Copy it into a fresh instance the destroy callback frees.
  (if (ffi/null? userdata)
    (ffi/write instance-ptr :pointer ffi/null)
    (let [st (ffi/alloc 8)]
      (ffi/write st :uint32 (ffi/read userdata :uint32))
      (ffi/write st :uint32 (ffi/read userdata :uint32 4) 4)
      (ffi/write instance-ptr :pointer st)))
  0)

(defn- config-destroy-cb* [instance]
  (when-not (ffi/null? instance) (ffi/free instance))
  0)

;; keyboard query callbacks (instance is NULL)
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

;; touchscreen query callbacks (instance holds {width height})
(defn- touch-query-device-name-cb* [_instance dst max-len]
  (write-name! dst max-len touchscreen-name))

(defn- touch-query-serial-name-cb* [_instance dst max-len]
  (write-name! dst max-len touchscreen-serial))

(defn- touch-query-device-ids-cb* [_instance ids-ptr]
  (ffi/write ids-ptr ids-layout {:bustype bus-virtual :vendor vendor-rh
                                 :product touchscreen-product :version 1})
  0)

(defn- touch-query-event-caps-cb* [_instance event-type bitmap len]
  (case (long event-type)
    1 (write-bitmap! bitmap len [btn-touch])      ; EV_KEY: BTN_TOUCH
    3 (write-bitmap! bitmap len [abs-x abs-y])    ; EV_ABS: ABS_X, ABS_Y
    0))                                            ; others: none

(defn- touch-query-abs-info-cb* [instance abs-axis abs-info]
  (when-not (ffi/null? instance)
    (let [w (ffi/read instance :uint32)
          h (ffi/read instance :uint32 4)]
      (case (long abs-axis)
        0 (ffi/write abs-info abs-info-layout {:min 0 :max w :fuzz 0 :flat 0 :res 0})
        1 (ffi/write abs-info abs-info-layout {:min 0 :max h :fuzz 0 :flat 0 :res 0})
        nil)))
  0)

(defn- touch-query-properties-cb* [_instance bitmap len]
  (write-bitmap! bitmap len [input-prop-direct]))

;; ---- event-provider callbacks (shared; instance holds the eventfd) --------
(defn- provider-create-cb* [instance-ptr userdata _reserved]
  ;; userdata is the eventfd (created at device build); mint a native instance
  ;; holding it for the worker. No new eventfd here — the queue already exists
  ;; and host threads may already be writing it.
  (if (ffi/null? userdata)
    (ffi/write instance-ptr :pointer ffi/null)
    (let [efd (ffi/read userdata :int)
          st (ffi/alloc 8)]
      (ffi/write st :int efd)
      (ffi/write instance-ptr :pointer st)))
  0)

(defn- provider-destroy-cb* [instance]
  (when-not (ffi/null? instance)
    (let [efd (ffi/read instance :int)]
      (c-close efd)
      (swap! queues dissoc efd)
      (when (= efd (:efd @keyboard)) (reset! keyboard {:efd nil :queue nil}))
      (when (= efd (:efd @touchscreen)) (reset! touchscreen {:efd nil :queue nil})))
    (ffi/free instance))
  0)

(defn- get-ready-efd-cb* [instance]
  (if (ffi/null? instance) -1 (ffi/read instance :int)))

(defn- next-event-cb* [instance out]
  (if (ffi/null? instance)
    0
    (let [efd (ffi/read instance :int)
          queue (:queue (get @queues efd))]
      (ffi/with-alloc [rbuf 8]
        (if (neg? (c-read efd rbuf 8))
          0
          (if-let [ev (q-pop! queue)]
            (do (ffi/write out event-layout ev) 1)
            0))))))

;; ---- foreign-callables (must stay live for the device's lifetime) ---------
(defonce ^:private config-create-cb
  (ffi/foreign-callable config-create-cb* [:pointer :pointer :pointer] :int32 :collect-safe))
(defonce ^:private config-destroy-cb
  (ffi/foreign-callable config-destroy-cb* [:pointer] :int32 :collect-safe))
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
(defonce ^:private touch-query-device-name-cb
  (ffi/foreign-callable touch-query-device-name-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private touch-query-serial-name-cb
  (ffi/foreign-callable touch-query-serial-name-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private touch-query-device-ids-cb
  (ffi/foreign-callable touch-query-device-ids-cb* [:pointer :pointer] :int32 :collect-safe))
(defonce ^:private touch-query-event-caps-cb
  (ffi/foreign-callable touch-query-event-caps-cb* [:pointer :uint8 :pointer :size_t] :int32 :collect-safe))
(defonce ^:private touch-query-abs-info-cb
  (ffi/foreign-callable touch-query-abs-info-cb* [:pointer :uint8 :pointer] :int32 :collect-safe))
(defonce ^:private touch-query-properties-cb
  (ffi/foreign-callable touch-query-properties-cb* [:pointer :pointer :size_t] :int32 :collect-safe))
(defonce ^:private provider-create-cb
  (ffi/foreign-callable provider-create-cb* [:pointer :pointer :pointer] :int32 :collect-safe))
(defonce ^:private provider-destroy-cb
  (ffi/foreign-callable provider-destroy-cb* [:pointer] :int32 :collect-safe))
(defonce ^:private get-ready-efd-cb
  (ffi/foreign-callable get-ready-efd-cb* [:pointer] :int :collect-safe))
(defonce ^:private next-event-cb
  (ffi/foreign-callable next-event-cb* [:pointer :pointer] :int32 :collect-safe))

;; ---- device builders -------------------------------------------------------
(defn- make-device [config-vtable provider-userdata config-userdata]
  (let [err (ffi/alloc 8)
        config (ffi/alloc 80)
        provider (ffi/alloc 48)
        _ (ffi/write config config-layout
                     {:features feature-query
                      :create-userdata (or config-userdata ffi/null)
                      :create config-create-cb
                      :destroy config-destroy-cb
                      :query-device-name (:query-device-name config-vtable)
                      :query-serial-name (:query-serial-name config-vtable)
                      :query-device-ids (:query-device-ids config-vtable)
                      :query-event-caps (:query-event-caps config-vtable)
                      :query-abs-info (:query-abs-info config-vtable)
                      :query-properties (:query-properties config-vtable)})
        _ (ffi/write provider provider-layout
                     {:features feature-queue
                      :create-userdata provider-userdata
                      :create provider-create-cb
                      :destroy provider-destroy-cb
                      :get-ready-efd get-ready-efd-cb
                      :next-event next-event-cb})
        dev (input-device-new config 80 provider 48 err)]
    (when (ffi/null? dev)
      (throw (ex-info "krun_input_device_new failed"
                      {:error (ffi/read err :pointer)})))
    dev))

(defn keyboard-device
  "Build a virtio-input keyboard device (libkrun 2.0 native input path).
  Returns a KrunInputDevice handle to hand to krun_device_manager_add. The
  device reports a BUS_VIRTUAL keyboard named 'libkrun Virtual Keyboard';
  feed it with send-key!/send-event! from any host thread."
  []
  (make-device {:query-device-name query-device-name-cb
                :query-serial-name query-serial-name-cb
                :query-device-ids query-device-ids-cb
                :query-event-caps query-event-caps-cb
                :query-abs-info query-abs-info-cb
                :query-properties query-properties-cb}
               (provider-state! role-keyboard)
               nil))

(defn touchscreen-device
  "Build a virtio-input absolute touchscreen device (libkrun 2.0 native input
  path) sized to a width x height frame. Returns a KrunInputDevice handle to
  hand to krun_device_manager_add. The device reports a BUS_VIRTUAL touchscreen
  named 'libkrun Touchscreen' with INPUT_PROP_DIRECT; feed it with
  send-touch-event! (BTN_TOUCH + ABS_X/ABS_Y + SYN_REPORT) from any host thread."
  [width height]
  (let [ud (ffi/alloc 8)]
    (ffi/write ud :uint32 (long width))
    (ffi/write ud :uint32 (long height) 4)
    (make-device {:query-device-name touch-query-device-name-cb
                  :query-serial-name touch-query-serial-name-cb
                  :query-device-ids touch-query-device-ids-cb
                  :query-event-caps touch-query-event-caps-cb
                  :query-abs-info touch-query-abs-info-cb
                  :query-properties touch-query-properties-cb}
                 (provider-state! role-touchscreen)
                 ud)))

;; ---- host-side event injection --------------------------------------------
(defn- enqueue! [state ev]
  (let [{:keys [efd queue]} @state]
    (when (and efd queue)
      (swap! queue conj ev)
      (ffi/with-alloc [wbuf 8]
        (ffi/write wbuf :uint64 1)
        (c-write efd wbuf 8))
      true)))

(defn send-event!
  "Enqueue one Linux input event ({:type :code :value}) for the KEYBOARD
  device and signal its eventfd so libkrun's input worker wakes and drains it.
  Any host thread may call this."
  [ev]
  (enqueue! keyboard ev))

(defn send-key!
  "Enqueue an EV_KEY press (pressed? -> value 1) or release (value 0), plus
  the trailing EV_SYN/SYN_REPORT — the reference gui_vm key-handler shape."
  [code pressed?]
  (send-event! {:type ev-key :code code :value (if pressed? 1 0)})
  (send-event! {:type ev-syn :code syn-report :value 0}))

(defn send-touch-event!
  "Enqueue one Linux input event ({:type :code :value}) for the TOUCHSCREEN
  device. The caller composes the BTN_TOUCH + ABS_X/ABS_Y + SYN_REPORT
  sequence; this just delivers a raw event. Any host thread may call this."
  [ev]
  (enqueue! touchscreen ev))
