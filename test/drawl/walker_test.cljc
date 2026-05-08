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

(deftest bidirectional-edge-walks
  (let [edge (walker/walk-form '(<-> a b "sync") {})]
    (is (true? (:bidirectional? edge)))
    (is (= 'a (:from edge)))
    (is (= 'b (:to edge)))
    (is (= "sync" (:description edge)))))

;; Built-in macros (SPEC §2.6). drawl.macros must be loaded so the walk-form
;; defmethods register; in production drawl.compiler does this require.

(require '[drawl.macros])

(defn- walk-container-from-macro [src]
  (-> (parse-one src)
      (walker/walk-form {})
      :elements first :children first))

(deftest phoenix-app-expands-to-container
  (let [el (walk-container-from-macro
             "(diagram (system bank (phoenix-app web \"Web App\")))")]
    (is (= :container (:kind el)))
    (is (= 'web (:id el)))
    (is (= "Web App" (:title el)))
    (is (= "Phoenix" (-> el :attrs :tech)))))

(deftest postgres-db-injects-role-and-tech
  (let [el (walk-container-from-macro
             "(diagram (system bank (postgres-db db \"DB\")))")]
    (is (= :database (-> el :attrs :role)))
    (is (= "Postgres" (-> el :attrs :tech)))
    (is (= "DB" (:title el)))))

(deftest redis-cache-injects-role
  (let [el (walk-container-from-macro
             "(diagram (system bank (redis-cache rc)))")]
    (is (= :cache (-> el :attrs :role)))
    (is (= "Redis" (-> el :attrs :tech)))))

(deftest rest-api-injects-tech
  (let [el (walk-container-from-macro
             "(diagram (system bank (rest-api api \"API\")))")]
    (is (= "REST API" (-> el :attrs :tech)))))

(deftest user-attrs-override-macro-attrs
  (let [el (walk-container-from-macro
             "(diagram (system bank (phoenix-app web :tech \"Custom\")))")]
    (is (= "Custom" (-> el :attrs :tech))
        "user :tech wins because it appears later in the canonical form")))

(deftest macro-children-walk-as-components
  (let [el (walk-container-from-macro
             "(diagram (system bank (phoenix-app web (component foo))))")]
    (is (= 1 (count (:children el))))
    (is (= :component (-> el :children first :kind)))))
