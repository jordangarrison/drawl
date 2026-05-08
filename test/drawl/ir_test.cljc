(ns drawl.ir-test
  (:require [clojure.test :refer [deftest is testing]]
            [drawl.compiler :as c]
            [drawl.ir :as ir]))

(deftest infer-level-context
  (is (= :context (:level (c/parse "(diagram (person p) (system s))")))))

(deftest infer-level-container
  (is (= :container (:level (c/parse "(diagram (system s (container c))))")))))

(deftest infer-level-component
  (is (= :component
         (:level (c/parse
                   "(diagram (system s (container c (component k))))")))))

(deftest at-level-strips-deeper-kinds
  (let [full (c/parse
               "(diagram
                  (system bank
                    (container web
                      (component handler))))")
        ctx  (ir/at-level :context full)
        cont (ir/at-level :container full)]
    (is (= :context (:level ctx)))
    (let [bank (first (:elements ctx))]
      (is (= 'bank (:id bank)))
      (is (= [] (:children bank)) ":context strips containers"))
    (let [bank (first (:elements cont))
          web  (first (:children bank))]
      (is (= 'web (:id web)))
      (is (= [] (:children web)) ":container strips components"))))

(deftest at-level-drops-dangling-edges
  (let [ir   (c/parse
               "(diagram
                  (person user)
                  (system bank
                    (container web)
                    (container db))
                  (-> user web))")
        ctx  (ir/at-level :context ir)]
    (is (= 0 (count (:relationships ctx)))
        "edge user->web should drop because web (container) was stripped")))

(deftest nested-system-validates
  (testing "system inside system is allowed (system landscape view)"
    (let [ir (c/parse "(diagram (system outer (system inner (container c))))")
          outer (first (:elements ir))
          inner (first (:children outer))]
      (is (= 'outer (:id outer)))
      (is (= :system (:kind inner)))
      (is (= 'inner (:id inner)))
      (is (= 'c (-> inner :children first :id))))))

(deftest nested-container-validates
  (testing "container inside container is allowed (sub-container grouping)"
    (let [ir (c/parse "(diagram (system s (container outer (container inner (component c)))))")
          outer (-> ir :elements first :children first)
          inner (first (:children outer))]
      (is (= 'outer (:id outer)))
      (is (= :container (:kind inner)))
      (is (= 'inner (:id inner)))
      (is (= 'c (-> inner :children first :id))))))

(deftest nesting-validation-component-outside-container
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"component .* must be inside container"
        (c/parse "(diagram (system s (component k)))"))))

(deftest nesting-validation-container-outside-system
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"container .* must be inside system"
        (c/parse "(diagram (container c))"))))

(deftest multi-system-context-keeps-both-systems
  (let [ir  (c/parse
              "(diagram
                 (person user)
                 (system app (container web) (container api))
                 (system payments (container processor))
                 (-> user web)
                 (-> api processor))")
        ctx (ir/at-level :context ir)
        ids (set (map :id (:elements ctx)))]
    (is (contains? ids 'user))
    (is (contains? ids 'app))
    (is (contains? ids 'payments))
    (is (= 0 (count (:relationships ctx)))
        "edges to filtered containers should drop")))

(deftest multi-system-container-keeps-cross-system-edges
  (let [ir  (c/parse
              "(diagram
                 (system app (container web) (container api))
                 (system payments (container processor))
                 (-> api processor \"charges\"))")
        co  (ir/at-level :container ir)]
    (is (= 1 (count (:relationships co)))
        "containers survive at :container, so cross-system edge should remain")))
