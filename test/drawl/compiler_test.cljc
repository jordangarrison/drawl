(ns drawl.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
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
     (testing "Every fixture in examples/ compiles to dot and mermaid."
       (doseq [^File f (->> (file-seq (File. "examples"))
                            (filter #(str/ends-with? (.getName ^File %) ".drawl"))
                            sort)]
         (testing (.getName f)
           (let [src      (slurp f)
                 dot-out  (c/compile src :dot)
                 mmd-out  (c/compile src :mermaid)]
             (is (str/starts-with? dot-out "digraph G {"))
             (is (re-find #"^C4(Context|Container|Component)" mmd-out))))))))

(def ^:private edge-syntax-fixture-src
  "(diagram \"Edge syntax fixture\"
    (person customer \"Customer\")
    (system shop \"Shop\"
      (container web \"Web\")
      (container mobile \"Mobile\")
      (container api \"API\"
        (with-attrs {:tech \"gRPC\"}
          (-> auth \"verify\")
          (-> catalog \"lookup\")))
      (container auth \"Auth\")
      (container catalog \"Catalog\")
      (container db \"DB\" :role :database))
    (=> customer [web mobile] api db \"via HTTPS\" :tech \"HTTPS\"))")

(def ^:private edge-syntax-fixture-expected
  "{:title         \"Edge syntax fixture\"
    :level         :container
    :relationships
    [{:kind :edge :from customer :to web    :bidirectional? false :description \"via HTTPS\" :attrs {:tech \"HTTPS\"}}
     {:kind :edge :from customer :to mobile :bidirectional? false :description \"via HTTPS\" :attrs {:tech \"HTTPS\"}}
     {:kind :edge :from web      :to api    :bidirectional? false :description \"via HTTPS\" :attrs {:tech \"HTTPS\"}}
     {:kind :edge :from mobile   :to api    :bidirectional? false :description \"via HTTPS\" :attrs {:tech \"HTTPS\"}}
     {:kind :edge :from api      :to db     :bidirectional? false :description \"via HTTPS\" :attrs {:tech \"HTTPS\"}}]}")

(deftest edge-syntax-fixture
  (testing "implicit-from + => + with-attrs compose end-to-end (CLJS-portable)"
    (let [expected (edn/read-string edge-syntax-fixture-expected)
          actual   (c/parse edge-syntax-fixture-src)]
      (is (= (:title expected) (:title actual)))
      (is (= (:level expected) (:level actual)))
      (is (= (:relationships expected)
             (mapv #(select-keys % [:kind :from :to :bidirectional?
                                    :description :attrs])
                   (:relationships actual))))
      (testing "api container has 2 inner edges with :tech gRPC"
        (let [api (->> actual :elements
                       (filter #(= 'shop (:id %)))
                       first :children
                       (filter #(= 'api (:id %)))
                       first)]
          (is (= 2 (count (:edges api))))
          (is (every? #(= 'api (:from %)) (:edges api)))
          (is (every? #(= "gRPC" (-> % :attrs :tech)) (:edges api))))))))
