;; Boot smoke for the libkrun 2.0 API: run a command via kernel cmdline.
;; Run: LD_LIBRARY_PATH=/tmp/libkrun/target/release jolt run test/boot2_test.clj
(ns jolt.test.boot2
  (:require [jolt.libkrun :as krun]))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 256
             :exec ["/bin/sh" "-c" "echo new-api-ok; uname -sr; sleep 1"]})
