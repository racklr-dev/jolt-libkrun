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
(ffi/defcfn add-net-tap    "krun_add_net_tap"   [:int32 :pointer :pointer :uint32 :uint32] :int32)

(defn- check! [what rc]
  (when-not (zero? rc) (throw (ex-info what {:rc rc}))))

(defn- add-virtiofs!
  "Add one or more virtio-fs devices. Accepts a single [tag path] pair or a
  vector of pairs [[tag path] ...]."
  [ctx virtiofs]
  (let [pairs (if (and (sequential? virtiofs) (= 2 (count virtiofs))
                       (string? (first virtiofs)))
                [virtiofs]
                virtiofs)]
    (doseq [[tag path] pairs]
      (ffi/with-c-string [t tag]
        (ffi/with-c-string [p path]
          (check! "krun_add_virtiofs" (add-virtiofs ctx t p)))))))

(defn- add-net-taps!
  "Add virtio-net devices backed by host TAPs (replaces the default TSI backend).
  taps: [{:name \"jolt0\" :mac [6 bytes]}] — mac optional."
  [ctx taps]
  (doseq [{:keys [name mac]} taps]
    (ffi/with-c-string [t name]
      (let [p (ffi/alloc 6)]
        (try
          (doseq [i (range 6)]
            (ffi/write p :uint8 (nth (or mac [0 0 0 0 0 0]) i) i))
          (check! "krun_add_net_tap" (add-net-tap ctx t p 0 0))
          (finally (ffi/free p)))))))

(defn boot!
  "Create a microVM context, apply opts, and block in the guest until it exits.
  opts: :rootfs (required), :workdir, :cpus, :memory-mib, :port-map \"HOST:22\",
  :virtiofs [tag path] or [[tag path] ...], :net-taps [{:name tap :mac [..]}],
  :exec [cmd arg...], :rlimits [str...]."
  [{:keys [rootfs workdir cpus memory-mib port-map virtiofs net-taps exec rlimits]}]
  (let [ctx (create-ctx)]
    (when (neg? ctx) (throw (ex-info "krun_create_ctx failed" {:rc ctx})))
    (check! "krun_set_vm_config" (set-vm-config ctx (or cpus 2) (or memory-mib 512)))
    (ffi/with-c-string [root rootfs]
      (check! "krun_set_root" (set-root ctx root))
      (ffi/with-c-string [wd (or workdir "/")]
        (check! "krun_set_workdir" (set-workdir ctx wd))
        (when virtiofs (add-virtiofs! ctx virtiofs))
        (when net-taps (add-net-taps! ctx net-taps))
        (when rlimits
          (ffi/with-c-string-array [rl (inc (count rlimits))] (conj (vec rlimits) nil)
            (check! "krun_set_rlimits" (set-rlimits ctx rl))))
        (when port-map
          (ffi/with-c-string-array [pm 2] [port-map nil]
            (check! "krun_set_port_map" (set-port-map ctx pm))))
        (when exec
          (ffi/with-c-string [exe (first exec)]
            (ffi/with-c-string-array [argv (count exec)] (conj (vec (rest exec)) nil)
              (ffi/with-c-string-array [env 4] ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" "HOME=/root" "HOSTNAME=microvm" nil]
                (check! "krun_set_exec" (set-exec ctx exe argv env))))))
        (check! "krun_start_enter" (start-enter ctx))))))
