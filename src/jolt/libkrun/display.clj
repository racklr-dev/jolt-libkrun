;; jolt.libkrun.display — the native virtio-gpu display path (libkrun 2.0).
;; Binds the host-side display vtable libkrun drives on its GPU worker thread:
;; configure_scanout / alloc_frame / present_frame. alloc_frame hands libkrun a
;; writable buffer; libkrun fills it; present_frame copies it out into a
;; jolt.framebuffer frame and hands it to a sink. This is the UTM-native route
;; (no VNC server, no RFB decode, no external viewer).
;;
;; The C contract (include/libkrun_display.h):
;;   krun_display_backend_new(const void *vtable, size_t vtable_size,
;;                            KrunError **err) -> KrunDisplayBackend (void*)
;;   krun_display_backend_add_display(KrunDisplayBackend handle,
;;                                    KrunDisplayInfoBuilder display)  ; both void*
;;   krun_gpu_device_new(uint32_t virgl_flags, KrunDisplayBackend backend)
;;     -> KrunGpuDevice (void*, to device-manager-add)
;; The backend struct is 64 bytes: u64 features | void* create_userdata |
;; void* create | vtable[5] (destroy, disable_scanout, configure_scanout,
;; alloc_frame, present_frame). Callbacks return int32 (0 ok, negative
;; KRUN_DISPLAY_ERR_*). They run on the GPU worker thread and must not block
;; long; we only memcpy and swap a ratom, like the reference gtk_display.
(ns jolt.libkrun.display
  (:require [jolt.ffi :as ffi]))

;; ---- FFI surface -----------------------------------------------------------
(ffi/defcfn display-backend-new   "krun_display_backend_new"   [:pointer :size_t :pointer] :pointer)
(ffi/defcfn display-backend-add-display "krun_display_backend_add_display" [:pointer :pointer] :void)
(ffi/defcfn display-backend-destroy "krun_display_backend_destroy" [:pointer] :void)
(ffi/defcfn display-info-builder-new "krun_display_info_builder_new" [:uint32 :uint32] :pointer)
(ffi/defcfn display-info-builder-dpi "krun_display_info_builder_dpi" [:pointer :uint32] :void)
(ffi/defcfn display-info-builder-refresh-rate "krun_display_info_builder_refresh_rate" [:pointer :uint32] :void)
(ffi/defcfn display-info-builder-physical-size "krun_display_info_builder_physical_size" [:pointer :uint16 :uint16] :void)
(ffi/defcfn gpu-device-new        "krun_gpu_device_new"        [:uint32 :pointer] :pointer)
(ffi/defcfn gpu-device-shm-size  "krun_gpu_device_shm_size"  [:pointer :size_t] :void)
(ffi/defcfn gpu-device-destroy    "krun_gpu_device_destroy"    [:pointer] :void)

;; KRUN_DISPLAY_ERR_* (libkrun_display.h)
(def ^:private err-invalid-scanout -3)
(def ^:private err-invalid-param -4)

;; KRUN_DISPLAY_FEATURE_BASIC_FRAMEBUFFER
(def ^:private feature-basic-framebuffer 1)

;; KRUN_DISPLAY_FORMAT_* (32bpp UNORM) -> jolt.framebuffer format keyword.
(def ^:private format-kws
  {1   :b8g8r8a8
   2   :b8g8r8x8
   3   :a8r8g8b8
   4   :x8r8g8b8
   67  :r8g8b8a8
   68  :x8b8g8r8
   121 :a8b8g8r8
   134 :r8g8b8x8})

;; ---- the backend vtable (64 bytes) ----------------------------------------
(def ^:private backend-layout
  (ffi/layout [:struct [[:features :uint64]
                        [:create-userdata :pointer]
                        [:create :pointer]
                        [:destroy :pointer]
                        [:disable-scanout :pointer]
                        [:configure-scanout :pointer]
                        [:alloc-frame :pointer]
                        [:present-frame :pointer]]]))

;; Live state for the current boot. One display backend per process (vmm_run
;; is blocking), so a single global atom is enough; the token is written into
;; *instance by create and ignored by the rest (kept non-null for the future).
;;   {:sink     (fn [frame])   ; called on present_frame with a frame map
;;    :scanouts {id {:width :height :format :frame-size :frame-ptr}}}
(defonce ^:private active (atom nil))

(defn- create-cb* [instance _userdata _reserved]
  (let [token (ffi/alloc 8)]
    (ffi/write instance :pointer token)
    (swap! active assoc :token token :scanouts {})
    0))

(defn- destroy-cb* [_instance]
  (doseq [[_ s] (:scanouts @active)]
    (when-let [p (:frame-ptr s)] (ffi/free p)))
  (reset! active {:sink nil :scanouts {} :token nil})
  0)

(defn- disable-cb* [_instance scanout-id]
  (when-let [p (get-in @active [:scanouts scanout-id :frame-ptr])]
    (ffi/free p))
  (swap! active update :scanouts dissoc scanout-id)
  0)

(defn- configure-cb* [_instance scanout-id display-w display-h w h format]
  (swap! active assoc-in [:scanouts scanout-id]
         {:display-width display-w :display-height display-h
          :width w :height h :format format
          :frame-size (* w h 4)})
  0)

(defn- alloc-cb* [_instance scanout-id buffer buffer-size]
  (let [s (get-in @active [:scanouts scanout-id])]
    (if-not s
      err-invalid-scanout
      (let [buf (ffi/alloc (:frame-size s))]
        (ffi/write buffer :pointer buf)
        (ffi/write buffer-size :size_t (:frame-size s))
        (swap! active assoc-in [:scanouts scanout-id :frame-ptr] buf)
        ;; one buffer in flight (the reference keeps a pool of 4; a pool is
        ;; a later optimisation)
        1))))

(defn- present-cb* [_instance scanout-id _frame-id _damage]
  (let [s (get-in @active [:scanouts scanout-id])]
    (cond
      (nil? s) err-invalid-scanout
      (nil? (:frame-ptr s)) err-invalid-param
      :else
      (let [bytes (ffi/read-array (:frame-ptr s) :uint8 (:frame-size s))
            frame {:width (:width s) :height (:height s)
                   :format (get format-kws (:format s) :x8r8g8b8)
                   :stride (* (:width s) 4)
                   :bytes bytes}]
        (ffi/free (:frame-ptr s))
        (swap! active assoc-in [:scanouts scanout-id :frame-ptr] nil)
        (when-let [sink (:sink @active)]
          (sink frame))
        0))))

(defonce ^:private create-cb
  (ffi/foreign-callable create-cb* [:pointer :pointer :pointer] :int32 :collect-safe))
(defonce ^:private destroy-cb
  (ffi/foreign-callable destroy-cb* [:pointer] :int32 :collect-safe))
(defonce ^:private disable-cb
  (ffi/foreign-callable disable-cb* [:pointer :uint32] :int32 :collect-safe))
(defonce ^:private configure-cb
  (ffi/foreign-callable configure-cb* [:pointer :uint32 :uint32 :uint32 :uint32 :uint32 :uint32] :int32 :collect-safe))
(defonce ^:private alloc-cb
  (ffi/foreign-callable alloc-cb* [:pointer :uint32 :pointer :pointer] :int32 :collect-safe))
(defonce ^:private present-cb
  (ffi/foreign-callable present-cb* [:pointer :uint32 :uint32 :pointer] :int32 :collect-safe))

(defn gpu-device
  "Build a virtio-gpu device backed by these display callbacks. Returns a
  KrunGpuDevice handle to hand to krun_device_manager_add. opts:
    :width  :height   scanout size (default 1280x720)
    :virgl-flags      virglrenderer flags (default 0 = 2D-only scanout; the
                      reference gui_vm uses 835 for USE_EGL|VENUS|RENDER_SERVER|
                      THREAD_SYNC|USE_ASYNC_FENCE_CB)
    :sink             (fn [frame]) called on present_frame; must not block long
                      (copy the frame, swap a ratom, write a file)
    :refresh-rate     optional scanout refresh rate (Hz)
    :shm-mib          GPU shared-memory window in MiB (default 64; libkrun's
                      own default of 8192 overflows small VMs)"
  [{:keys [width height virgl-flags sink refresh-rate shm-mib]
    :or {width 1280 height 720 virgl-flags 0 shm-mib 64}}]
  (when-not sink
    (throw (ex-info "gpu-device needs a :sink fn" {})))
  (let [err (ffi/alloc 8)
        backend (ffi/alloc 64)
        _ (ffi/write-field backend backend-layout :features feature-basic-framebuffer)
        _ (ffi/write-field backend backend-layout :create-userdata ffi/null)
        _ (ffi/write-field backend backend-layout :create create-cb)
        _ (ffi/write-field backend backend-layout :destroy destroy-cb)
        _ (ffi/write-field backend backend-layout :disable-scanout disable-cb)
        _ (ffi/write-field backend backend-layout :configure-scanout configure-cb)
        _ (ffi/write-field backend backend-layout :alloc-frame alloc-cb)
        _ (ffi/write-field backend backend-layout :present-frame present-cb)
        _ (reset! active {:sink sink :scanouts {} :token nil})
        backend-handle (display-backend-new backend 64 err)]
    (when (ffi/null? backend-handle)
      (throw (ex-info "krun_display_backend_new failed"
                      {:error (ffi/read err :pointer)})))
    ;; one scanout display per gpu-device (hyperjay is a single-VM bench)
    (let [info (display-info-builder-new width height)
          slot (ffi/alloc 8)]
      (ffi/write slot :pointer info)
      (when refresh-rate
        (display-info-builder-refresh-rate slot refresh-rate))
      (display-backend-add-display backend-handle info))  ; info consumed here
    (let [gpu (gpu-device-new virgl-flags backend-handle)]
      (when (ffi/null? gpu)
        (throw (ex-info "krun_gpu_device_new failed" {})))
      ;; libkrun defaults the GPU shm region to 8 GiB, which overflows small
      ;; VMs; the scanout needs ~a few MiB (and virgl a modest window)
      (let [slot (ffi/alloc 8)]
        (ffi/write slot :pointer gpu)
        (gpu-device-shm-size slot (* shm-mib 1048576)))
      gpu)))
