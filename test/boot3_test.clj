;; Boot smoke via /.krun_config.json (OCI process args/env/cwd) — proper argv
;; and exit code. Expects to EXIT 5 (the guest's code) and print cfg-ok + uname.
(ns jolt.test.boot3
  (:require [jolt.libkrun :as krun]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]))

(def rootfs "/tmp/alpine-test")
(shell/sh "rm" "-rf" rootfs)
(shell/sh "cp" "-a" "/tmp/nettap-cache/alpine_3.20/base" rootfs)
(spit (str rootfs "/.krun_config.json")
      (json/write-str {"process" {"args" ["/bin/sh" "-c" "echo cfg-ok; uname -sr; exit 5"]
                                   "env" []
                                   "cwd" "/"}}))

(krun/boot! {:rootfs rootfs :cpus 1 :memory-mib 256})
