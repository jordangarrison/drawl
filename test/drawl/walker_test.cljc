(ns drawl.walker-test
  (:require [clojure.test :refer [deftest is testing]]
            [drawl.walker :as walker]
            [drawl.parser :as parser]))

(defn- parse-one [src]
  (->> (parser/parse-forms src)
       (filter #(and (seq? %) (= 'diagram (first %))))
       first))

(deftest split-attrs-basic
  (is (= [{:tech "Phoenix"} '(:foo)]
         (walker/split-attrs '(:tech "Phoenix" :foo))))
  (is (= [{} '(child)]
         (walker/split-attrs '(child))))
  (is (= [{:a 1 :b 2} '()]
         (walker/split-attrs '(:a 1 :b 2)))))

(deftest diagram-with-system
  (let [ir (walker/walk-form (parse-one "(diagram \"T\" (system foo \"Foo\"))") {})]
    (is (= "T" (:title ir)))
    (is (= 1 (count (:elements ir))))
    (is (= [] (:relationships ir)))
    (let [el (first (:elements ir))]
      (is (= :system (:kind el)))
      (is (= 'foo (:id el)))
      (is (= "Foo" (:title el))))))

(deftest unknown-form-throws
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"Unknown form head"
        (walker/walk-form '(nope foo) {}))))

(deftest non-list-form-throws
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"Expected a list form"
        (walker/walk-form 42 {}))))
