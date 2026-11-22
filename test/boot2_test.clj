;; Boot smoke: proper argv via the injected /.krun_config.json (no shell split).
(ns jolt.test.boot2
  (:require [jolt.libkrun :as krun]))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 256
             :exec ["/bin/sh" "-c" "echo new-api-ok; uname -sr"]})
