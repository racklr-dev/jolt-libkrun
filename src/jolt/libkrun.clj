;; libkrun microVM bindings: the krun_* C API (github.com/containers/libkrun).
;; Translated from dirge/src/bin/dirge-microvm-runner.rs.
(ns jolt.libkrun
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn set-log-level "krun_set_log_level" [:uint32] :int32)
(ffi/defcfn create-ctx     "krun_create_ctx"    [] :int32)
(ffi/defcfn set-vm-config  "krun_set_vm_config" [:int32 :uint8 :uint32] :int32)
(ffi/defcfn set-rlimits    "krun_set_rlimits"   [:int32 :pointer] :int32)
(ffi/defcfn set-root       "krun_set_root"      [:int32 :pointer] :int32)
(ffi/defcfn set-workdir    "krun_set_workdir"   [:int32 :pointer] :int32)
(ffi/defcfn add-virtiofs   "krun_add_virtiofs"  [:int32 :pointer :pointer] :int32)
(ffi/defcfn set-port-map   "krun_set_port_map"  [:int32 :pointer] :int32)
(ffi/defcfn set-exec       "krun_set_exec"      [:int32 :pointer :pointer :pointer] :int32)
(ffi/defcfn start-enter    "krun_start_enter"   [:int32] :int32 :blocking)

(defn- check! [what rc]
  (when-not (zero? rc) (throw (ex-info what {:rc rc}))))

(defn boot!
  "Create a microVM context, apply opts, and block in the guest until it exits.
  opts: :rootfs (required), :workdir, :cpus, :memory-mib, :port-map \"HOST:22\",
  :virtiofs [tag path], :exec [cmd arg...], :rlimits [str...]."
  [{:keys [rootfs workdir cpus memory-mib port-map virtiofs exec rlimits]}]
  (let [ctx (create-ctx)]
    (when (neg? ctx) (throw (ex-info "krun_create_ctx failed" {:rc ctx})))
    (check! "krun_set_vm_config" (set-vm-config ctx (or cpus 2) (or memory-mib 512)))
    (ffi/with-c-string [root rootfs]
      (check! "krun_set_root" (set-root ctx root))
      (ffi/with-c-string [wd (or workdir "/")]
        (check! "krun_set_workdir" (set-workdir ctx wd))
        (when virtiofs
          (let [[tag path] virtiofs]
            (ffi/with-c-string [t tag]
              (ffi/with-c-string [p path]
                (check! "krun_add_virtiofs" (add-virtiofs ctx t p))))))
        (when rlimits
          (ffi/with-c-string-array [rl (inc (count rlimits))] (conj (vec rlimits) nil)
            (check! "krun_set_rlimits" (set-rlimits ctx rl))))
        (when port-map
          (ffi/with-c-string-array [pm 2] [port-map nil]
            (check! "krun_set_port_map" (set-port-map ctx pm))))
        (when exec
          (ffi/with-c-string [exe (first exec)]
            (ffi/with-c-string-array [argv (count exec)] (conj (vec (rest exec)) nil)
              (ffi/with-c-string-array [env 4] ["PATH=/usr/bin:/bin" "HOME=/root" "HOSTNAME=microvm" nil]
                (check! "krun_set_exec" (set-exec ctx exe argv env))))))
        (check! "krun_start_enter" (start-enter ctx))))))
