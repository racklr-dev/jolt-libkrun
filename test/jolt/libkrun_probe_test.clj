;; Probe smoke test: on a host with libkrun installed, both libraries resolve
;; and /dev/kvm is at least present. Run: jolt run test/jolt/libkrun_probe_test.clj
(ns jolt.test.libkrun-probe
  (:require [jolt.libkrun.probe :as probe]))

(let [lk (probe/find-libkrun)
      lkfw (probe/find-libkrunfw)]
  (when-not lk
    (println "skip: libkrun not found") (System/exit 0))
  (assert (.startsWith lk "/") "find-libkrun must be absolute")
  (assert (.startsWith lkfw "/") "find-libkrunfw must be absolute")
  (assert (probe/kvm-present?) "expected /dev/kvm on a libkrun host")
  (println :libkrun lk :libkrunfw lkfw :kvm-ok (probe/kvm-available?))
  (println "libkrun probe: libs found + /dev/kvm present OK"))
