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

(deftest compile-missing-input-exits-1
  (let [missing (str (java.io.File/createTempFile "drawl-cli-missing-" ".drawl") "-does-not-exist")
        err     (java.io.StringWriter.)
        code    (binding [*err* err] (main/-main "compile" "-i" missing))]
    (is (= 1 code))
    (is (str/includes? (str err) "io-error"))))

(deftest compile-output-file-mermaid
  (testing "compile with --output and --backend mermaid writes to file"
    (let [in       (tmp-file ".drawl" hello-src)
          out-file (java.io.File/createTempFile "drawl-cli-out" ".mmd")
          _        (.deleteOnExit out-file)
          out      (java.io.StringWriter.)
          err      (java.io.StringWriter.)
          code     (binding [*out* out *err* err]
                     (main/-main "compile"
                                 "--input"   in
                                 "--output"  (.getAbsolutePath out-file)
                                 "--backend" "mermaid"))]
      (is (= 0 code))
      (is (= "" (str out)) "should be silent on stdout when --output is set")
      (is (re-find #"^C4(Context|Container|Component)" (slurp out-file))))))

(deftest compile-unknown-backend-exits-1
  (let [in   (tmp-file ".drawl" hello-src)
        err  (java.io.StringWriter.)
        code (binding [*err* err] (main/-main "compile" "-i" in "-b" "fred"))]
    (is (= 1 code))
    (is (str/includes? (str err) "Unknown backend: fred"))))

(deftest compile-parse-error-exits-1
  (let [in   (tmp-file ".drawl" "(diagram (system a) (system a))") ; duplicate id
        err  (java.io.StringWriter.)
        code (binding [*err* err] (main/-main "compile" "-i" in)) ]
    (is (= 1 code))
    (is (str/includes? (str err) "walk-error"))))

(deftest lint-good-source-silent
  (let [in   (tmp-file ".drawl" hello-src)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)
        code (binding [*out* out *err* err] (main/-main "lint" "-i" in))]
    (is (= 0 code))
    (is (= "" (str out)))
    (is (= "" (str err)))))

(deftest lint-bad-source-reports-errors
  ;; duplicate id triggers a walk-error from drawl.compiler/validate
  (let [in   (tmp-file ".drawl" "(diagram (system a) (system a))")
        err  (java.io.StringWriter.)
        code (binding [*err* err] (main/-main "lint" "-i" in))]
    (is (= 1 code))
    (is (str/includes? (str err) ":walk-error"))))
