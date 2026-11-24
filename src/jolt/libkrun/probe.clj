;; Locate libkrun / libkrunfw shared libraries and the virtualization backend
;; (/dev/kvm on Linux, Hypervisor.framework on macOS) for pre-flight checks
;; (port of dirge's sandbox/libkrun_probe.rs + check.rs).
;; Loading itself is jolt.ffi's job (it resolves krun_* symbols already);
;; this namespace only answers "are they installed, and where?".
(ns jolt.libkrun.probe
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]))

(defn host-os
  "Keyword for the running host: :macos, :windows, or :linux."
  []
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/starts-with? n "mac") :macos
      (str/starts-with? n "windows") :windows
      :else :linux)))

(defn libkrun-leaf
  "Leaf filename of the libkrun shared library on the given :os (default host).
  Unversioned on both platforms: unlike libkrunfw, the name that matters is
  unversioned at both link and runtime."
  ([] (libkrun-leaf (host-os)))
  ([os] (if (= os :macos) "libkrun.dylib" "libkrun.so")))

(defn libkrunfw-link-leaf
  "The unversioned libkrunfw name a LINKER resolves through. On macOS the
  runtime dlopen asks for the VERSIONED leaf instead (libkrunfw.5.dylib): the
  unversioned libkrunfw.dylib is a symlink the loader never consults, so
  finding only it reports present while the dlopen still fails (dirge-jbhz)."
  ([] (libkrunfw-link-leaf (host-os)))
  ([os] (if (= os :macos) "libkrunfw.dylib" "libkrunfw.so")))

(def ^:private mac-libkrunfw-runtime-pattern
  #"libkrunfw\.(\d+)\.dylib")

(defn- mac-libkrunfw-version
  "ABI major of a versioned libkrunfw.N.dylib file, or nil when it is not one."
  [f]
  (when-let [[_ v] (re-matches mac-libkrunfw-runtime-pattern (.getName f))]
    (Integer/parseInt v)))

(defn- brew-prefix
  ([package]
   (let [r (apply shell/sh (if package ["brew" "--prefix" package] ["brew" "--prefix"]))]
     (when (zero? (:exit r)) (str/trim (:out r)))))
  ([] (brew-prefix nil)))

(defn- lib-dirs
  "Directories to search for libkrun/libkrunfw, most-specific first."
  ([] (lib-dirs (host-os)))
  ([os]
   (if (= os :macos)
     (let [prefixes (remove str/blank? [(brew-prefix "libkrunfw") (brew-prefix)])]
       (concat (map #(str % "/lib") prefixes)
               ["/opt/homebrew/lib" "/usr/local/lib" "/usr/lib"]))
     (distinct (concat
                 ;; LD_LIBRARY_PATH first (nix/non-FHS environments)
                 (remove str/blank? (str/split (or (System/getenv "LD_LIBRARY_PATH") "") #":"))
                 ["/usr/local/lib" "/usr/lib" "/usr/lib64" "/lib"
                  "/usr/lib/x86_64-linux-gnu" "/lib/x86_64-linux-gnu"
                  "/usr/lib/aarch64-linux-gnu" "/lib/aarch64-linux-gnu"])))))

(defn- best-versioned
  "Given a leaf (e.g. libkrun.so) and matching files, prefer the exact
  unversioned name, then ascending ABI versions (libkrun.so.1 before .1.19.6)."
  [leaf files]
  (let [prefix (str leaf ".")
        exact (some #(when (= (.getName %) leaf) %) files)
        versioned (remove #(= (.getName %) leaf) files)
        rank (fn [f]
               (mapv #(Integer/parseInt %)
                     (str/split (subs (.getName f) (count prefix)) #"\.")))]
    (or exact (first (sort-by rank versioned)))))

(defn- find-leaf-in
  [dirs leaf]
  (some (fn [dir]
          (let [d (File. dir)]
            (when (.isDirectory d)
              (let [files (filter #(and (.isFile %)
                                           (or (= (.getName %) leaf)
                                               (str/starts-with? (.getName %) (str leaf "."))))
                                       (or (seq (.listFiles d)) []))]
                (when-let [f (best-versioned leaf files)]
                  (.getAbsolutePath f))))))
        dirs))

(defn- find-leaf
  [leaf]
  (find-leaf-in (lib-dirs) leaf))

(defn find-libkrun
  "Absolute path of libkrun on this host, or nil if not found."
  []
  (find-leaf (libkrun-leaf)))

(defn find-libkrunfw-in
  "Absolute path of the libkrunfw shared library the runtime dlopens, searched
  in `dirs`, or nil. On macOS that is the VERSIONED leaf libkrunfw.N.dylib
  (highest ABI), never the unversioned link-time symlink; elsewhere the usual
  exact-then-versioned libkrunfw.so rule. Public as a test seam: the macOS rule
  is exercised on a Linux runner through this fn."
  [os dirs]
  (if (= os :macos)
    (some (fn [dir]
            (let [d (File. dir)]
              (when (.isDirectory d)
                (when-let [f (->> (or (seq (.listFiles d)) [])
                                  (filter #(and (.isFile %) (mac-libkrunfw-version %)))
                                  (sort-by mac-libkrunfw-version)
                                  last)]
                  (.getAbsolutePath f)))))
          dirs)
    (find-leaf-in dirs (libkrunfw-link-leaf os))))

(defn find-libkrunfw
  "Absolute path of the libkrunfw shared library on this host, or nil. On
  macOS this is the versioned runtime leaf (see find-libkrunfw-in)."
  ([] (find-libkrunfw (host-os)))
  ([os] (find-libkrunfw-in os (lib-dirs os))))

(defn hypervisor-available?
  "macOS: Hypervisor.framework is usable (sysctl kern.hv_support = 1). False
  elsewhere — Linux uses /dev/kvm, and libkrun has no Windows backend."
  ([] (hypervisor-available? (host-os)))
  ([os]
   (and (= os :macos)
        (= "1" (-> (shell/sh "sysctl" "-n" "kern.hv_support") :out str/trim)))))

(defn kvm-available?
  "Linux: /dev/kvm exists and this process can read AND write it (write needs
  kvm-group membership on most distros). False on non-Linux hosts."
  ([] (kvm-available? (host-os)))
  ([os]
   (and (= os :linux)
        (let [f (File. "/dev/kvm")]
          (and (.exists f) (.canRead f) (.canWrite f))))))

(defn kvm-present?
  "Linux: /dev/kvm merely exists, regardless of permission. False on non-Linux."
  ([] (kvm-present? (host-os)))
  ([os] (and (= os :linux) (.exists (File. "/dev/kvm")))))
