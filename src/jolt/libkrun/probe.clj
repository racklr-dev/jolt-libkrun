;; Locate libkrun / libkrunfw shared libraries and /dev/kvm for
;; pre-flight checks (port of dirge's sandbox/libkrun_probe.rs + check.rs).
;; Loading itself is jolt.ffi's job (it resolves krun_* symbols already);
;; this namespace only answers "are they installed, and where?".
(ns jolt.libkrun.probe
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]))

(defn- macos?
  []
  (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(defn libkrun-leaf
  "Leaf filename of the libkrun shared library on this platform."
  []
  (if (macos?) "libkrun.dylib" "libkrun.so"))

(defn libkrunfw-leaf
  "Leaf filename of the libkrunfw shared library on this platform."
  []
  (if (macos?) "libkrunfw.dylib" "libkrunfw.so"))

(defn- brew-prefix
  ([package]
   (let [r (apply shell/sh (if package ["brew" "--prefix" package] ["brew" "--prefix"]))]
     (when (zero? (:exit r)) (str/trim (:out r)))))
  ([] (brew-prefix nil)))

(defn- lib-dirs
  "Directories to search for libkrun/libkrunfw, most-specific first."
  []
  (if (macos?)
    (let [prefixes (remove str/blank? [(brew-prefix "libkrunfw") (brew-prefix)])]
      (concat (map #(str % "/lib") prefixes)
              ["/opt/homebrew/lib" "/usr/local/lib" "/usr/lib"]))
    (distinct (concat
                ;; LD_LIBRARY_PATH first (nix/non-FHS environments)
                (remove str/blank? (str/split (or (System/getenv "LD_LIBRARY_PATH") "") #":"))
                ["/usr/local/lib" "/usr/lib" "/usr/lib64" "/lib"
                 "/usr/lib/x86_64-linux-gnu" "/lib/x86_64-linux-gnu"
                 "/usr/lib/aarch64-linux-gnu" "/lib/aarch64-linux-gnu"]))))

(defn- best-versioned
  "Given a leaf (e.g. libkrun.so) and matching files, prefer the exact
  unversioned name, then ascending ABI versions (libkrun.so.1 before .1.19.6)."
  [leaf files]
  (let [prefix (str leaf ".")
        exact (some #(when (= (.getName %) leaf) %) files)
        versioned (remove #(= (.getName %) leaf) files)
        rank (fn [f]
               (mapv #(Integer/parseInt %)
                     (str/split (subs (.getName f) (count prefix)) #"\\.")))]
    (or exact (first (sort-by rank versioned)))))

(defn- find-leaf
  [leaf]
  (let [dirs (lib-dirs)]
    (some (fn [dir]
            (let [d (File. dir)]
              (when (.isDirectory d)
                (let [files (filter #(and (.isFile %)
                                             (or (= (.getName %) leaf)
                                                 (str/starts-with? (.getName %) (str leaf "."))))
                                         (or (seq (.listFiles d)) []))]
                  (when-let [f (best-versioned leaf files)]
                    (.getAbsolutePath f))))))
          dirs)))

(defn find-libkrun
  "Absolute path of libkrun on this host, or nil if not found."
  []
  (find-leaf (libkrun-leaf)))

(defn find-libkrunfw
  "Absolute path of libkrunfw on this host, or nil if not found."
  []
  (find-leaf (libkrunfw-leaf)))

(defn kvm-available?
  "True when /dev/kvm exists and this process can read AND write it
  (write requires membership in the kvm group on most distros)."
  []
  (let [f (File. "/dev/kvm")]
    (and (.exists f) (.canRead f) (.canWrite f))))

(defn kvm-present?
  "True when /dev/kvm merely exists (regardless of permission)."
  []
  (.exists (File. "/dev/kvm")))
