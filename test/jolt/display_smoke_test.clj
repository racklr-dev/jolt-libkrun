;; Display smoke: boot alpine with a virtio-gpu device and a PPM-writing
;; sink. Proves the native path end-to-end: guest -> virtio-gpu ->
;; alloc_frame -> present_frame -> sink. krun_vmm_run exits this process with
;; the guest's code, so the sink records and writes PPMs DURING boot; verify
;; afterwards via the exit code (0) and the PPM files under /tmp/display-smoke.
;; Run: jolt run test/jolt/display_smoke_test.clj
(ns jolt.test.display-smoke
  (:require [jolt.libkrun :as krun]))

(def out-dir "/tmp/display-smoke")
(.mkdirs (java.io.File. out-dir))

;; channel byte offsets (r g b) per 32bpp format keyword emitted by
;; jolt.libkrun.display (the same orders as jolt.framebuffer/formats)
(def fmt-offsets
  {:b8g8r8a8 [2 1 0] :b8g8r8x8 [2 1 0]
   :a8r8g8b8 [1 2 3] :x8r8g8b8 [1 2 3]
   :r8g8b8a8 [0 1 2] :r8g8b8x8 [0 1 2]
   :x8b8g8r8 [3 2 1] :a8b8g8r8 [3 2 1]})

(defn nonzero-bytes [bs]
  (loop [i 0 acc 0]
    (if (>= i (alength bs))
      acc
      (recur (inc i) (if (zero? (aget bs i)) acc (inc acc))))))

(defn write-ppm [{:keys [width height format stride bytes]} n]
  (let [[ro go bo] (get fmt-offsets format [1 2 3])
        hdr (.getBytes (str "P6\n" width " " height "\n255\n") "US-ASCII")
        out (byte-array (+ (alength hdr) (* 3 width height)))]
    (System/arraycopy hdr 0 out 0 (alength hdr))
    (dotimes [row height]
      (dotimes [col width]
        (let [o (+ (* row stride) (* col 4))
              i (+ (alength hdr) (* 3 (+ (* row width) col)))]
          (aset out i (aget bytes (+ o ro)))
          (aset out (+ i 1) (aget bytes (+ o go)))
          (aset out (+ i 2) (aget bytes (+ o bo))))))
    (let [p (str out-dir "/frame-" n ".ppm")]
      (java.nio.file.Files/write (java.nio.file.Paths/get p (into-array String []))
                                 out (into-array java.nio.file.OpenOption []))
      p)))

(def state (atom {:count 0 :first-ok true}))

(defn sink [frame]
  (let [n (get (swap! state update :count inc) :count)
        bs (:bytes frame)
        nonzero (nonzero-bytes bs)]
    (when (= n 1)
      (println :FIRST-FRAME (select-keys frame [:width :height :format :stride])
               :bytes (alength bs) :nonzero-bytes nonzero)
      (when (or (not= (:width frame) 640) (not= (:height frame) 480)
                (zero? nonzero))
        (swap! state assoc :first-ok false)))
    (when (or (= n 1) (zero? (mod n 25)))
      (println :PPM n (write-ppm frame n)))
    (when (zero? (mod n 50))
      (println :present-frame n (:width frame) "x" (:height frame)
               :format (:format frame) :nonzero-bytes nonzero))))

(krun/boot! {:rootfs "/tmp/nettap-cache/alpine_3.20/base"
             :cpus 1 :memory-mib 1024
             :display {:width 640 :height 480 :virgl-flags 1 :sink sink}
             :exec ["/bin/sh" "-c" "echo display-smoke-ok; drm-modeset /dev/dri/card0 640 480; exit 0"]})

;; unreachable: krun_vmm_run exits this process with the guest's code
