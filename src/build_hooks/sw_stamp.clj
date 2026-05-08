(ns build-hooks.sw-stamp
  "Shadow-cljs build hook: stamp the service worker's CACHE_VERSION
  with the current package version + a content hash of the freshly
  compiled main.js. Yields keys like

    drawl-v0.0.1-alpha-a3f9c1

  so installed clients invalidate their shell automatically whenever
  the bundle changes — no manual CACHE_VERSION bump required.

  In dev (`shadow-cljs watch`) we use a static suffix, since the SW
  is intentionally disabled on localhost (see app.core/register-
  service-worker!) and recomputing a hash on every save is waste."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.file Files]))

(def ^:private tmpl-path  "public/sw.js.tmpl")
(def ^:private out-path   "public/sw.js")
(def ^:private bundle     "public/js/main.js")
(def ^:private placeholder "__CACHE_VERSION__")

(defn- read-package-version []
  (or (-> "package.json" slurp (json/read-str) (get "version"))
      "0.0.0"))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- file-hash6 [path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [bytes (Files/readAllBytes (.toPath f))
            md    (MessageDigest/getInstance "SHA-256")]
        (subs (hex (.digest md bytes)) 0 6)))))

(defn- suffix-for [mode]
  (case mode
    :release (or (file-hash6 bundle)
                 (throw (ex-info "sw-stamp: main.js missing at :flush"
                                 {:path bundle})))
    "dev"))

(defn- cache-version [pkg-version suffix]
  (str "drawl-v" pkg-version "-alpha-" suffix))

(defn stamp
  {:shadow.build/stage :flush}
  [build-state]
  (let [tmpl (io/file tmpl-path)]
    (when (.exists tmpl)
      (let [mode (:shadow.build/mode build-state)
            cv   (cache-version (read-package-version) (suffix-for mode))
            body (-> (slurp tmpl) (str/replace placeholder cv))]
        (spit out-path body)
        (println (str "[sw-stamp] CACHE_VERSION=" cv)))))
  build-state)
