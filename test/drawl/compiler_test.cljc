(ns drawl.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [drawl.compiler :as c])
  #?(:clj (:import (java.io File))))

(deftest hello-end-to-end
  (let [out (c/compile "(diagram \"Hello\" (system foo \"Foo system\"))" :dot)]
    (is (str/starts-with? out "digraph G {"))
    (is (str/includes? out "label=\"Hello\""))
    (is (str/includes? out "\"foo\" ["))
    (is (str/includes? out "label=\"Foo system\""))))

(deftest validate-good-source
  (is (nil? (c/validate "(diagram \"x\" (system a))"))))

(deftest unknown-backend-throws
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
               (c/compile "(diagram (system a))" :nope))))

#?(:clj
   (deftest examples-compile
     (testing "Every fixture in examples/ compiles to dot."
       (doseq [^File f (->> (file-seq (File. "examples"))
                            (filter #(str/ends-with? (.getName ^File %) ".drawl"))
                            sort)]
         (testing (.getName f)
           (let [src (slurp f)
                 out (c/compile src :dot)]
             (is (str/starts-with? out "digraph G {"))))))))
