;; Exit-code smoke: the guest exits 5, vmm_run exits this process with 5.
(ns jolt.test.boot3
  (:require [jolt.libkrun :as krun]))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 256
             :exec ["/bin/sh" "-c" "echo exit-code-test; exit 5"]})
