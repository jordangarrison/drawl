(ns cli.smoke-test
  "Spawns `bb -m cli.main ...` against every fixture in examples/.
  Skipped automatically when bb is not on PATH."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- bb-available? []
  (try (zero? (:exit (sh/sh "bb" "--version"))) (catch Exception _ false)))

(defn- example-files []
  (->> (file-seq (io/file "examples"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".drawl"))
       sort))

(deftest bb-cli-compile-every-example-to-dot
  (if-not (bb-available?)
    (println "skipping bb smoke test: bb not on PATH")
    (doseq [^java.io.File f (example-files)]
      (testing (.getName f)
        (let [{:keys [exit out err]}
              (sh/sh "bb" "drawl" "compile" "-i" (.getAbsolutePath f) "-b" "dot")]
          (is (zero? exit) (str "non-zero exit for " (.getName f) ": " err))
          (is (str/starts-with? out "digraph G {")
              (str "unexpected dot output for " (.getName f))))))))

(deftest bb-cli-lint-every-example
  (if-not (bb-available?)
    (println "skipping bb smoke test: bb not on PATH")
    (doseq [^java.io.File f (example-files)]
      (testing (.getName f)
        (let [{:keys [exit err]} (sh/sh "bb" "drawl" "lint" "-i" (.getAbsolutePath f))]
          (is (zero? exit) (str "lint failed for " (.getName f) ": " err)))))))
