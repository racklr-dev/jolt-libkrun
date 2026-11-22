;; Smoke test: create a libkrun context and set a VM config.
;; Run on a machine with libkrun + /dev/kvm:
;;   jolt run test/jolt/libkrun_smoke_test.clj
(ns jolt.test.libkrun-smoke
  (:require [jolt.libkrun :as krun]))

(if-not (.exists (java.io.File. "/dev/kvm"))
  (println "skip: /dev/kvm not present")
  (let [c (krun/create-ctx)]
    (when (neg? c) (throw (ex-info "krun_create_ctx failed" {:rc c})))
    (when-not (zero? (krun/set-vm-config c 2 512))
      (throw (ex-info "krun_set_vm_config failed" {:rc c})))
    (println "libkrun smoke: create-ctx + set-vm-config ok")))
