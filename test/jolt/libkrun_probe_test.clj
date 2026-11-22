;; Probe smoke test: loads the full jolt.libkrun FFI surface (with the
;; :jolt/native lib marked :optional, this compiles even where libkrun is not
;; installed), then reports what the probe finds. On a host with libkrun, both
;; libraries resolve and /dev/kvm is present. On CI (no libkrun) it must still
;; load and skip cleanly. Run: jolt run test/jolt/libkrun_probe_test.clj
(ns jolt.test.libkrun-probe
  (:require [jolt.libkrun :as krun]
            [jolt.libkrun.probe :as probe]))

(assert (ifn? krun/boot!) "jolt.libkrun must define boot!")
(println :ffi-surface-loaded true)

(let [lk (probe/find-libkrun)
      lkfw (probe/find-libkrunfw)]
  (when-not lk
    (println "skip: libkrun not found (CI runner)")
    (System/exit 0))
  (assert (.startsWith lk "/") "find-libkrun must be absolute")
  (assert (.startsWith lkfw "/") "find-libkrunfw must be absolute")
  (assert (probe/kvm-present?) "expected /dev/kvm on a libkrun host")
  (println :libkrun lk :libkrunfw lkfw :kvm-ok (probe/kvm-available?))
  (println "libkrun probe: libs found + /dev/kvm present OK"))