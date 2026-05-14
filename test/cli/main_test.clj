(ns cli.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cli.main :as main]))

(defn- tmp-file
  "Write contents to a fresh temp file and return its absolute path."
  ^String [suffix contents]
  (let [f (java.io.File/createTempFile "drawl-cli-" suffix)]
    (.deleteOnExit f)
    (spit f contents)
    (.getAbsolutePath f)))

(def ^:private hello-src
  "(diagram \"Hello\" (system foo \"Foo\"))")

(deftest compile-stdout-default-backend
  (testing "compile with --input and no --output writes dot to stdout, exits 0"
    (let [in   (tmp-file ".drawl" hello-src)
          out  (java.io.StringWriter.)
          err  (java.io.StringWriter.)
          code (binding [*out* out *err* err]
                 (main/-main "compile" "--input" in))]
      (is (= 0 code))
      (is (str/starts-with? (str out) "digraph G {"))
      (is (str/includes? (str out) "label=\"Hello\""))
      (is (= "" (str err))))))
