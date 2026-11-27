;; Input-path smoke test: builds the virtio-input keyboard AND touchscreen
;; devices (libkrun 2.0 native input), then exercises the config + event-
;; provider vtables the same way libkrun's input worker will. Requires libkrun
;; (the :jolt/native is :optional, so on a host without it skip the device
;; build like the probe test does). Run: jolt run test/jolt/libkrun_input_test.clj
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

(defn mint-instance
  "Simulate libkrun's input worker: run the provider create callback with the
  device's eventfd as userdata (what the builder passed) and answer the
  instance pointer the worker would receive."
  [efd]
  (let [create-cb (deref #'jolt.libkrun.input/provider-create-cb*)]
    (ffi/with-alloc [instance-ptr 8]
      (ffi/with-alloc [ud 4]
        (ffi/write ud :int efd)
        (check "provider create returns 0" 0 (create-cb instance-ptr ud ffi/null))
        (ffi/read instance-ptr :pointer)))))

;; -- struct sizes (ABI contract with libkrun_input.h) ------------------------
(check "config struct is 80 bytes" 80
       (ffi/sizeof @#'jolt.libkrun.input/config-layout))
(check "event-provider struct is 48 bytes" 48
       (ffi/sizeof @#'jolt.libkrun.input/provider-layout))
(check "input event struct is 8 bytes" 8
       (ffi/sizeof @#'jolt.libkrun.input/event-layout))
(check "device ids struct is 8 bytes" 8
       (ffi/sizeof @#'jolt.libkrun.input/ids-layout))
(check "absinfo struct is 20 bytes" 20
       (ffi/sizeof @#'jolt.libkrun.input/abs-info-layout))

;; -- device build (needs libkrun loaded) -------------------------------------
(let [dev (input/keyboard-device)]
  (check "keyboard: krun_input_device_new returns a device" false (ffi/null? dev)))
(let [dev (input/touchscreen-device 1280 720)]
  (check "touchscreen: krun_input_device_new returns a device" false (ffi/null? dev)))

;; -- keyboard event queue round-trip -----------------------------------------
(input/send-key! 30 true)   ; KEY_A press -> EV_KEY + EV_SYN
(let [st @@#'jolt.libkrun.input/keyboard]
  (check "keyboard: two events queued (EV_KEY + EV_SYN)" 2 (count @(:queue st)))
  (check "keyboard: ready eventfd is valid" true (pos? (:efd st))))

(let [st @@#'jolt.libkrun.input/keyboard
      instance (mint-instance (:efd st))
      next-cb (deref #'jolt.libkrun.input/next-event-cb*)
      ev-layout (deref #'jolt.libkrun.input/event-layout)]
  (check "keyboard: provider create minted an instance" false (ffi/null? instance))
  (ffi/with-alloc [out 8]
    (check "first event is EV_KEY KEY_A press" 1 (next-cb instance out))
    (check "first event payload" {:type 1 :code 30 :value 1} (ffi/read out ev-layout))
    (check "second event is EV_SYN" 1 (next-cb instance out))
    (check "second event payload" {:type 0 :code 0 :value 0} (ffi/read out ev-layout))
    (check "queue drained" 0 (next-cb instance out))))

;; -- touchscreen event queue round-trip --------------------------------------
;; BTN_TOUCH press + ABS_X + ABS_Y + SYN_REPORT, the non-MT pointer sequence.
(input/send-touch-event! {:type 1 :code 0x14a :value 1})
(input/send-touch-event! {:type 3 :code 0 :value 640})
(input/send-touch-event! {:type 3 :code 1 :value 360})
(input/send-touch-event! {:type 0 :code 0 :value 0})
(let [st @@#'jolt.libkrun.input/touchscreen
      instance (mint-instance (:efd st))
      next-cb (deref #'jolt.libkrun.input/next-event-cb*)
      ev-layout (deref #'jolt.libkrun.input/event-layout)]
  (check "touchscreen: four events queued" 4 (count @(:queue st)))
  (check "touchscreen: ready eventfd is valid" true (pos? (:efd st)))
  (ffi/with-alloc [out 8]
    (check "t1 BTN_TOUCH press" {:type 1 :code 0x14a :value 1}
           (do (check "t1 delivered" 1 (next-cb instance out))
               (ffi/read out ev-layout)))
    (check "t2 ABS_X" {:type 3 :code 0 :value 640}
           (do (check "t2 delivered" 1 (next-cb instance out))
               (ffi/read out ev-layout)))
    (check "t3 ABS_Y" {:type 3 :code 1 :value 360}
           (do (check "t3 delivered" 1 (next-cb instance out))
               (ffi/read out ev-layout)))
    (check "t4 SYN_REPORT" {:type 0 :code 0 :value 0}
           (do (check "t4 delivered" 1 (next-cb instance out))
               (ffi/read out ev-layout)))
    (check "touchscreen queue drained" 0 (next-cb instance out))))

;; -- config query callbacks -----------------------------------------------------
(let [qname (deref #'jolt.libkrun.input/query-device-name-cb*)
      qids (deref #'jolt.libkrun.input/query-device-ids-cb*)
      qcaps (deref #'jolt.libkrun.input/query-event-caps-cb*)
      ids-layout (deref #'jolt.libkrun.input/ids-layout)]
  (ffi/with-alloc [buf 64]
    (check "keyboard device name length" 24 (qname nil buf 64))
    (check "keyboard device name" "libkrun Virtual Keyboard" (ffi/read-bytes buf 24)))
  (ffi/with-alloc [ids 8]
    (qids nil ids)
    (check "keyboard device ids" {:bustype 6 :vendor 18514 :product 1 :version 1}
           (ffi/read ids ids-layout)))
  (ffi/with-alloc [bm 128]
    (let [n (qcaps nil 1 bm 128)]   ; EV_KEY capabilities
      (check "key capability bitmap non-empty" true (pos? n))
      (check "KEY_A bit set (byte 3 bit 6)" true
             (bit-test (ffi/read bm :uint8 3) 6)))))

(let [qname (deref #'jolt.libkrun.input/touch-query-device-name-cb*)
      qids (deref #'jolt.libkrun.input/touch-query-device-ids-cb*)
      qcaps (deref #'jolt.libkrun.input/touch-query-event-caps-cb*)
      qabs (deref #'jolt.libkrun.input/touch-query-abs-info-cb*)
      qprops (deref #'jolt.libkrun.input/touch-query-properties-cb*)
      ids-layout (deref #'jolt.libkrun.input/ids-layout)
      abs-layout (deref #'jolt.libkrun.input/abs-info-layout)]
  (ffi/with-alloc [buf 64]
    (check "touchscreen device name length" 19 (qname nil buf 64))
    (check "touchscreen device name" "libkrun Touchscreen" (ffi/read-bytes buf 19)))
  (ffi/with-alloc [ids 8]
    (qids nil ids)
    (check "touchscreen device ids" {:bustype 6 :vendor 18514 :product 3 :version 1}
           (ffi/read ids ids-layout)))
  (ffi/with-alloc [bm 128]
    (check "touch EV_KEY bitmap non-empty (BTN_TOUCH)" true (pos? (qcaps nil 1 bm 128)))
    (check "touch EV_ABS bitmap non-empty (ABS_X/ABS_Y)" true (pos? (qcaps nil 3 bm 128))))
  ;; query_abs_info reads the frame size from the config instance
  (ffi/with-alloc [inst 8]
    (ffi/write inst :uint32 1280)
    (ffi/write inst :uint32 720 4)
    (ffi/with-alloc [info 20]
      (qabs inst 0 info)
      (check "touch ABS_X absinfo" {:min 0 :max 1280 :fuzz 0 :flat 0 :res 0}
             (ffi/read info abs-layout))
      (qabs inst 1 info)
      (check "touch ABS_Y absinfo" {:min 0 :max 720 :fuzz 0 :flat 0 :res 0}
             (ffi/read info abs-layout))))
  (ffi/with-alloc [bm 128]
    (let [n (qprops nil bm 128)]
      (check "touch properties bitmap non-empty (INPUT_PROP_DIRECT)" true (pos? n))
      (check "INPUT_PROP_DIRECT bit set" true (bit-test (ffi/read bm :uint8 0) 1)))))

(println "libkrun input: keyboard + touchscreen devices + vtables OK")
