;; Input-path smoke test: builds the virtio-input keyboard device (libkrun 2.0
;; native input), then exercises the config + event-provider vtables the same
;; way libkrun's input worker will. Requires libkrun (the :jolt/native is
;; :optional, so on a host without it skip the device build like the probe
;; test does). Run: jolt run test/jolt/libkrun_input_test.clj
(ns jolt.test.libkrun-input
  (:require [jolt.ffi :as ffi]
            [jolt.libkrun.input :as input]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- struct sizes (ABI contract with libkrun_input.h) ------------------------
(check "config struct is 80 bytes" 80
       (ffi/sizeof @#'jolt.libkrun.input/config-layout))
(check "event-provider struct is 48 bytes" 48
       (ffi/sizeof @#'jolt.libkrun.input/provider-layout))
(check "input event struct is 8 bytes" 8
       (ffi/sizeof @#'jolt.libkrun.input/event-layout))
(check "device ids struct is 8 bytes" 8
       (ffi/sizeof @#'jolt.libkrun.input/ids-layout))

;; -- device build (needs libkrun loaded) -------------------------------------
(let [dev (input/keyboard-device)]
  (check "krun_input_device_new returns a device" false (ffi/null? dev)))

;; -- event queue round-trip --------------------------------------------------
(input/send-key! 30 true)   ; KEY_A press -> EV_KEY + EV_SYN
(let [st @@#'jolt.libkrun.input/active]
  (check "two events queued (EV_KEY + EV_SYN)" 2 (count @(:queue st)))
  (check "ready eventfd is valid" true (pos? (:efd st))))

(let [next-cb (deref #'jolt.libkrun.input/next-event-cb*)
      ev-layout (deref #'jolt.libkrun.input/event-layout)]
  (ffi/with-alloc [out 8]
    (check "first event is EV_KEY KEY_A press" 1 (next-cb nil out))
    (check "first event payload" {:type 1 :code 30 :value 1} (ffi/read out ev-layout))
    (check "second event is EV_SYN" 1 (next-cb nil out))
    (check "second event payload" {:type 0 :code 0 :value 0} (ffi/read out ev-layout))
    (check "queue drained" 0 (next-cb nil out))))

;; -- config query callbacks --------------------------------------------------
(let [qname (deref #'jolt.libkrun.input/query-device-name-cb*)
      qids (deref #'jolt.libkrun.input/query-device-ids-cb*)
      qcaps (deref #'jolt.libkrun.input/query-event-caps-cb*)
      ids-layout (deref #'jolt.libkrun.input/ids-layout)]
  (ffi/with-alloc [buf 64]
    (check "device name length" 24 (qname nil buf 64))
    (check "device name" "libkrun Virtual Keyboard" (ffi/read-bytes buf 24)))
  (ffi/with-alloc [ids 8]
    (qids nil ids)
    (check "device ids" {:bustype 6 :vendor 18514 :product 1 :version 1}
           (ffi/read ids ids-layout)))
  (ffi/with-alloc [bm 128]
    (let [n (qcaps nil 1 bm 128)]   ; EV_KEY capabilities
      (check "key capability bitmap non-empty" true (pos? n))
      (check "KEY_A bit set (byte 3 bit 6)" true
             (bit-test (ffi/read bm :uint8 3) 6)))))

(println "libkrun input: device + vtables OK")
