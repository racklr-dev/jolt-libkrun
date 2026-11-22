;; jolt.libkrun — libkrun 2.0 bindings (the new builder/device C API).
;; The old krun_create_ctx/krun_set_* API is removed on libkrun main, so this
;; library targets the new opaque-handle API:
;;   vmm_builder_new -> vcpus/ram_mib -> payload_load_krunfw
;;   -> payload_append_cmdline "init=/init.krun"
;;   -> fs_device_new (/dev/root + mounts) -> fs_overlay (init + config)
;;   -> net_device_new_tap -> device_manager_add -> builder_devices
;;   -> builder_build -> vmm_run
;; KrunStr/KrunBytes are {ptr,len} structs passed BY VALUE; a by-value argument
;; is a caller-owned pointer to the struct bytes.
(ns jolt.libkrun
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(def ^:private kstr-layout (ffi/layout [:struct [[:data :pointer] [:len :size_t]]]))
(def ^:private kbytes-layout (ffi/layout [:struct [[:data :pointer] [:len :size_t]]]))
(def ^:private kstr-desc [:by-value [:struct [[:data :pointer] [:len :size_t]]]])
(def ^:private kbytes-desc [:by-value [:struct [[:data :pointer] [:len :size_t]]]])

(defmacro ^:private defkrun [name c-sym argtypes rettype & opts]
  `(ffi/defcfn ~name ~c-sym
     ~(mapv (fn [t] (get {:kstr kstr-desc :kbytes kbytes-desc} t t)) argtypes)
     ~rettype ~@opts))

(defn- cstr! [s]
  (let [b (.getBytes s "UTF-8")
        p (ffi/alloc (inc (alength b)))]
    (dotimes [i (alength b)] (ffi/write p :uint8 (aget b i) i))
    (ffi/write p :uint8 0 (alength b))
    p))

(defn- kstr! [s]
  (let [p (ffi/alloc 16)]
    (ffi/write-field p kstr-layout :data (cstr! s))
    (ffi/write-field p kstr-layout :len (alength (.getBytes s "UTF-8")))
    p))

(defn- kbytes! [bs]
  (let [n (alength bs)
        p (ffi/alloc 16)
        bp (ffi/alloc n)]
    (dotimes [i n] (ffi/write bp :uint8 (aget bs i) i))
    (ffi/write-field p kbytes-layout :data bp)
    (ffi/write-field p kbytes-layout :len n)
    p))

(defkrun vmm-builder-new        "krun_vmm_builder_new"        [] :pointer)
(defkrun vmm-builder-vcpus      "krun_vmm_builder_vcpus"      [:pointer :uint8 :pointer] :uint64)
(defkrun vmm-builder-ram-mib    "krun_vmm_builder_ram_mib"    [:pointer :uint32 :pointer] :uint64)
(defkrun payload-load-krunfw    "krun_payload_load_krunfw"    [:pointer] :pointer)
(defkrun vmm-builder-payload    "krun_vmm_builder_payload"    [:pointer :pointer] :void)
(defkrun fs-device-new          "krun_fs_device_new"          [:kstr :kstr :pointer] :pointer)
(defkrun net-device-tap         "krun_net_device_new_tap"     [:kstr :kstr :kbytes :uint32 :pointer] :pointer)
(defkrun mmio-device-manager-new "krun_mmio_device_manager_new" [] :pointer)
(defkrun device-manager-add     "krun_device_manager_add"     [:pointer :pointer] :void)
(defkrun vmm-builder-devices    "krun_vmm_builder_devices"    [:pointer :pointer] :void)
(defkrun payload-append-cmdline "krun_payload_append_cmdline" [:pointer :kstr] :void)
(defkrun vmm-builder-build      "krun_vmm_builder_build"      [:pointer :pointer] :pointer)
(defkrun vmm-run                "krun_vmm_run"                [:pointer] :void :blocking)
(defkrun result-name-cstr       "krun_result_name_cstr"       [:uint64] :string)
(defkrun error-code             "krun_error_code"             [:pointer] :uint32)
(defkrun vmm-builder-serial-console "krun_vmm_builder_add_serial_console" [:pointer :int :int :pointer] :uint64)
(defkrun console-device-builder "krun_console_device_builder" [] :pointer)
(defkrun console-builder-add-default "krun_console_builder_add_default_console" [:pointer :int :int :int :pointer] :uint64)
(defkrun console-builder-build  "krun_console_builder_build"  [:pointer :pointer] :pointer)
(defkrun init-log               "krun_init_log"               [:int :uint32 :uint32 :uint32 :pointer] :uint64)
(defkrun fs-overlay-new         "krun_fs_overlay_new"         [] :pointer)
(defkrun fs-overlay-add-file    "krun_fs_overlay_add_file"    [:pointer :kstr :kbytes :uint32 :bool :pointer] :uint64)
(defkrun fs-device-set-overlay  "krun_fs_device_set_overlay"  [:pointer :pointer] :void)

(defn- check-result! [what r]
  (when-not (zero? r)
    (throw (ex-info (str what " failed") {:rc r :result (result-name-cstr r)})))
  r)

(defn- ptr-or-throw! [what p]
  (when (ffi/null? p)
    (throw (ex-info (str what " returned null") {})))
  p)

(defn- read-bytes [path]
  (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get path (into-array String []))))

(defn- config-json [exec env cwd]
  (json/write-str {"process" {"args" (vec exec)
                              "env" (vec (or env []))
                              "cwd" (or cwd "/")}}))

(defn boot!
  "Boot a microVM with the libkrun 2.0 API and block until the guest exits
  (vmm_run exits this process with the guest's code).

  opts:
    :rootfs        (required) host dir mounted as the guest root (tag /dev/root)
    :exec          [cmd arg...] run as the workload (written into the injected
                   /.krun_config.json as OCI args)
    :env           [K=V ...] guest env
    :cwd           guest workdir (default /)
    :init-binary   path to libkrun's init binary (libkrun_init.so), injected at
                   /init.krun; default /tmp/libkrun/target/release/libkrun_init.so.0.1.0
    :cpus :memory-mib
    :mounts        [[tag host-path] ...] extra virtio-fs devices
    :net-taps      [{:id :tap :mac}] virtio-net devices (TAP backend)"
  [{:keys [rootfs cpus memory-mib mounts net-taps exec env cwd init-binary]}]
  (let [err (ffi/alloc 8)
        _ (init-log 2 4 0 0 err)  ; stderr, Debug — guest console logs here
        slot (ffi/alloc 8)
        _ (ffi/write slot :pointer (vmm-builder-new))
        _ (check-result! "krun_vmm_builder_vcpus" (vmm-builder-vcpus slot (or cpus 2) err))
        _ (check-result! "krun_vmm_builder_ram_mib" (vmm-builder-ram-mib slot (or memory-mib 512) err))
        payload (ptr-or-throw! "krun_payload_load_krunfw" (payload-load-krunfw err))
        init-bin (or init-binary "/tmp/libkrun/target/release/krun-init")
        _ (payload-append-cmdline payload (kstr! "init=/init.krun"))
        _ (vmm-builder-payload slot payload)
        root-dev (ptr-or-throw! "krun_fs_device_new(root)"
                                (fs-device-new (kstr! "/dev/root") (kstr! rootfs) err))
        ;; inject the init binary + workload config as overlay files
        overlay (fs-overlay-new)
        _ (check-result! "krun_fs_overlay_add_file(/init.krun)"
                         (fs-overlay-add-file overlay (kstr! "/init.krun")
                                               (kbytes! (read-bytes init-bin)) 0755 true err))
        _ (when (seq exec)
            (let [cfg (.getBytes (config-json exec env cwd) "UTF-8")]
              (check-result! "krun_fs_overlay_add_file(/.krun_config.json)"
                             (fs-overlay-add-file overlay (kstr! "/.krun_config.json")
                                                   (kbytes! cfg) 0644 true err))))
        _ (fs-device-set-overlay root-dev overlay)
        mount-devs (for [[tag host] mounts]
                     (ptr-or-throw! (str "krun_fs_device_new(" tag ")")
                                    (fs-device-new (kstr! tag) (kstr! host) err)))
        net-devs (for [{:keys [id tap mac]} net-taps]
                   (ptr-or-throw! (str "krun_net_device_new_tap(" tap ")")
                                  (net-device-tap (kstr! id) (kstr! tap)
                                                  (kbytes! (or mac (byte-array 6))) 0 err)))
        mgr (mmio-device-manager-new)
        console-builder (console-device-builder)
        _ (check-result! "krun_console_builder_add_default_console"
                          (console-builder-add-default console-builder 0 1 2 err))
        console-dev (ptr-or-throw! "krun_console_builder_build" (console-builder-build console-builder err))
        _ (doseq [d (concat [root-dev] mount-devs net-devs [console-dev])] (device-manager-add mgr d))
        _ (vmm-builder-devices slot mgr)
        vmm (vmm-builder-build slot err)
        _ (when (ffi/null? vmm)
            (let [eh (ffi/read err :pointer)]
              (throw (ex-info "krun_vmm_builder_build failed"
                              {:error-code (when-not (ffi/null? eh) (error-code eh))}))))]
    (vmm-run vmm)))
