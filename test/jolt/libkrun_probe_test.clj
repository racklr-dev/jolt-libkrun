;; Probe smoke test: loads the full jolt.libkrun FFI surface (with the
;; :jolt/native lib marked :optional, this compiles even where libkrun is not
;; installed), then reports what the probe finds. On a host with libkrun, both
;; libraries resolve; on CI (no libkrun) it must still load and skip cleanly.
;; The macOS branches (leaf names, Hypervisor.framework, versioned
;; libkrunfw.N.dylib runtime leaf) are pinned by the pure tests at the bottom,
;; which run on every OS so a Linux gate still catches a macOS regression.
;; Run: jolt run test/jolt/libkrun_probe_test.clj
(ns jolt.test.libkrun-probe
  (:require [clojure.string :as str]
            [jolt.libkrun :as krun]
            [jolt.libkrun.probe :as probe]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- FFI surface + host probe -----------------------------------------------
(assert (ifn? krun/boot!) "jolt.libkrun must define boot!")
(println :ffi-surface-loaded true)

(let [lk (probe/find-libkrun)
      lkfw (probe/find-libkrunfw)]
  (if-not lk
    (println (str "skip host probe: libkrun not found (CI runner, os " (name (probe/host-os)) ")"))
    (do
      (assert (.startsWith lk "/") "find-libkrun must be absolute")
      (assert (.startsWith lkfw "/") "find-libkrunfw must be absolute")
      ;; the hardware backend assertion is OS-specific: /dev/kvm only on Linux,
      ;; Hypervisor.framework only on macOS.
      (when (= (probe/host-os) :linux)
        (assert (probe/kvm-present?) "expected /dev/kvm on a libkrun host"))
      (println :libkrun lk :libkrunfw lkfw
               :kvm-ok (probe/kvm-available?)
               :hypervisor-ok (probe/hypervisor-available?))
      (println "libkrun probe: libs found + backend checked OK"))))

;; -- pure tests: the macOS rules a Linux runner must still pin -------------
(check "host-os returns a keyword" true (keyword? (probe/host-os)))

(check "libkrun leaf: linux is libkrun.so" "libkrun.so" (probe/libkrun-leaf :linux))
(check "libkrun leaf: macos is libkrun.dylib" "libkrun.dylib" (probe/libkrun-leaf :macos))
(check "libkrunfw link leaf: linux is libkrunfw.so" "libkrunfw.so" (probe/libkrunfw-link-leaf :linux))
(check "libkrunfw link leaf: macos is libkrunfw.dylib" "libkrunfw.dylib" (probe/libkrunfw-link-leaf :macos))

(check "kvm-present? is Linux-only" false (probe/kvm-present? :macos))
(check "kvm-present? is Linux-only (windows)" false (probe/kvm-present? :windows))
(check "kvm-available? is Linux-only" false (probe/kvm-available? :macos))
(check "hypervisor-available? is macOS-only" false (probe/hypervisor-available? :linux))

;; The load-bearing macOS rule (dirge-jbhz): the runtime dlopen wants
;; libkrunfw.5.dylib, not the unversioned libkrunfw.dylib symlink. The
;; versioned leaf must win, and an unversioned-only dir must answer nil, never
;; a false present.
(let [d (str (System/getProperty "java.io.tmpdir") "/jolt-probe-" (System/nanoTime))
      mk (fn [n] (.mkdirs (java.io.File. d)) (spit (str d "/" n) ""))]
  (mk "libkrunfw.3.dylib")
  (mk "libkrunfw.5.dylib")
  (mk "libkrunfw.dylib")   ; the link-time symlink the loader never consults
  (check "macOS libkrunfw picks the highest ABI version"
         (str d "/libkrunfw.5.dylib")
         (probe/find-libkrunfw-in :macos [d]))
  ;; remove the versioned files; the unversioned symlink alone must NOT pass
  (.delete (java.io.File. d "libkrunfw.3.dylib"))
  (.delete (java.io.File. d "libkrunfw.5.dylib"))
  (check "macOS libkrunfw ignores the unversioned symlink alone"
         nil
         (probe/find-libkrunfw-in :macos [d])))

;; Linux versioned rule still works through the same seam.
(let [d (str (System/getProperty "java.io.tmpdir") "/jolt-probe-" (System/nanoTime))
      mk (fn [n] (.mkdirs (java.io.File. d)) (spit (str d "/" n) ""))]
  (mk "libkrunfw.so.1.19.6")
  (check "linux libkrunfw parses a multi-dot versioned .so"
         (str d "/libkrunfw.so.1.19.6")
         (probe/find-libkrunfw-in :linux [d])))

(println "ALL PROBE TESTS PASSED")
