(ns drawl.walker-test
  (:require [clojure.test :refer [deftest is testing]]
            [drawl.walker :as walker]
            [drawl.parser :as parser]
            [drawl.compiler :as c]))

(defn- parse-one [src]
  (->> (parser/parse-forms src)
       (filter #(and (seq? %) (= 'diagram (first %))))
       first))

(deftest split-header-positional-then-attrs
  (let [[strs attrs children] (walker/split-header '("Title" "Desc" :tech "X"))]
    (is (= ["Title" "Desc"] strs))
    (is (= {:tech "X"} attrs))
    (is (= '() children))))

(deftest split-header-attrs-then-positional
  (testing "the relaxed rule lets attrs precede positional strings"
    (let [[strs attrs children] (walker/split-header '(:tech "X" "Title"))]
      (is (= ["Title" nil] strs))
      (is (= {:tech "X"} attrs))
      (is (= '() children)))))

(deftest split-header-stops-at-list
  (let [[strs attrs children] (walker/split-header '("T" :a 1 (child)))]
    (is (= ["T" nil] strs))
    (is (= {:a 1} attrs))
    (is (= '((child)) children))))

(deftest split-header-last-write-wins
  (let [[_ attrs _] (walker/split-header '(:tech "first" :tech "second"))]
    (is (= {:tech "second"} attrs))))

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

;; Built-in macros (SPEC §2.6) live in the per-compile :macros registry that
;; drawl.compiler seeds with drawl.macros/builtins. Tests go through the
;; full pipeline so the registry is populated.

(defn- walk-container-from-macro [src]
  (-> (c/parse src) :elements first :children first))

(deftest webapp-expands-to-container
  (let [el (walk-container-from-macro
             "(diagram (system bank (webapp web \"Web App\")))")]
    (is (= :container (:kind el)))
    (is (= 'web (:id el)))
    (is (= "Web App" (:title el)))
    (is (= "Web" (-> el :attrs :tech)))))

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
             "(diagram (system bank (webapp web :tech \"Custom\")))")]
    (is (= "Custom" (-> el :attrs :tech))
        "user :tech wins because it appears later in the canonical form")))

(deftest macro-children-walk-as-components
  (let [el (walk-container-from-macro
             "(diagram (system bank (webapp web (component foo))))")]
    (is (= 1 (count (:children el))))
    (is (= :component (-> el :children first :kind)))))

;; User-defined macros via top-level (defmacro ...) forms.

(deftest user-defmacro-expands-and-renders
  (let [src "(defmacro mongo-db [name & opts]
               (container name :role :database :tech \"MongoDB\" opts))
             (diagram (system shop (mongo-db users \"Users\")))"
        el  (-> (c/parse src) :elements first :children first)]
    (is (= :container (:kind el)))
    (is (= 'users (:id el)))
    (is (= "Users" (:title el)))
    (is (= :database (-> el :attrs :role)))
    (is (= "MongoDB" (-> el :attrs :tech)))))

(deftest user-defmacro-overrides-builtin-with-warning
  (let [src "(defmacro postgres-db [name & opts]
               (container name :tech \"YugabyteDB\" opts))
             (diagram (system s (postgres-db db \"DB\")))"
        warn-out (with-out-str
                   (binding [*err* *out*]
                     (let [el (-> (c/parse src) :elements first :children first)]
                       (is (= "YugabyteDB" (-> el :attrs :tech))
                           "user macro replaces the built-in template")
                       (is (nil? (-> el :attrs :role))
                           "user template did not include :role"))))]
    (is (re-find #"redefining macro `postgres-db`" warn-out))))

(deftest user-macro-supports-nested-expansion
  (let [src "(defmacro store [name & body]
               (system name :external false body))
             (diagram (store acme (webapp web)))"
        el  (-> (c/parse src) :elements first)]
    (is (= :system (:kind el)))
    (is (= 'acme (:id el)))
    (is (= 1 (count (:children el))))
    (is (= 'web (-> el :children first :id)))
    (is (= "Web" (-> el :children first :attrs :tech))
        "the inner webapp call still expanded via the registry")))

(deftest user-macro-no-rest-param
  (let [src "(defmacro fixed [n]
               (container n :tech \"Fixed\"))
             (diagram (system s (fixed c)))"
        el  (-> (c/parse src) :elements first :children first)]
    (is (= :container (:kind el)))
    (is (= 'c (:id el)))
    (is (= "Fixed" (-> el :attrs :tech)))))

(deftest defmacro-malformed-throws
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"defmacro requires a symbol name"
        (c/parse "(defmacro \"oops\" [x] (container x))
                  (diagram (system s))"))))

;; --- Implicit-from on (-> ...) inside element body ----------------------

(deftest implicit-from-inside-container
  (testing "single symbol after -> uses parent element as from"
    (let [ir   (c/parse "(diagram (system bank
                                    (container api \"API\"
                                      (-> db \"reads\"))
                                    (container db \"DB\")))")
          edge (-> ir :elements first :children first :edges first)]
      (is (= 'api (:from edge)))
      (is (= 'db  (:to edge)))
      (is (= "reads" (:description edge))))))

(deftest implicit-from-bidirectional
  (let [ir   (c/parse "(diagram (system s
                                  (container api \"API\"
                                    (<-> peer))
                                  (container peer \"Peer\")))")
        edge (-> ir :elements first :children first :edges first)]
    (is (true? (:bidirectional? edge)))
    (is (= 'api  (:from edge)))
    (is (= 'peer (:to edge)))))

(deftest explicit-edge-inside-container-still-works
  (testing "two leading symbols = explicit, even inside element body"
    (let [ir   (c/parse "(diagram (system s
                                    (container spa \"SPA\")
                                    (container api \"API\"
                                      (-> spa api \"delivers\"))))")
          edge (-> ir :elements first :children second :edges first)]
      (is (= 'spa (:from edge)))
      (is (= 'api (:to edge))))))

(deftest implicit-from-rejected-at-diagram-body
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"two endpoints"
        (c/parse "(diagram (system s) (-> s))"))))

(deftest implicit-from-with-label-and-attrs
  (testing "header parser still works for implicit-from form"
    (let [edge (-> (c/parse "(diagram (system s
                                        (container api \"API\"
                                          (-> db \"reads\" :tech \"JDBC\"))
                                        (container db \"DB\")))")
                   :elements first :children first :edges first)]
      (is (= 'api (:from edge)))
      (is (= 'db  (:to edge)))
      (is (= "reads" (:description edge)))
      (is (= "JDBC" (-> edge :attrs :tech))))))

(deftest implicit-from-self-loop
  (testing "self-loop via implicit-from is allowed (matches explicit (-> a a))"
    (let [edge (-> (c/parse "(diagram (system s
                                        (container queue \"Queue\"
                                          (-> queue \"retries\"))))")
                   :elements first :children first :edges first)]
      (is (= 'queue (:from edge)))
      (is (= 'queue (:to edge)))
      (is (= "retries" (:description edge))))))

(deftest edge-merges-wrap-attrs-from-ctx
  (testing "walk-edge picks up :wrap-attrs from ctx; edge attrs win on conflict"
    (let [edge-a (walker/walk-form '(-> a b) {:wrap-attrs {:tech "gRPC"}})
          edge-b (walker/walk-form '(-> a b :tech "REST")
                                   {:wrap-attrs {:tech "gRPC" :style :dashed}})]
      (is (= {:tech "gRPC"} (:attrs edge-a)))
      (is (= {:tech "REST" :style :dashed} (:attrs edge-b))
          "edge :tech wins, wrap :style passes through"))))

;; --- => chain with vector fan -------------------------------------------

(defn- chain-edges
  "Convenience: parse a diagram containing only the chain form,
  return its relationships in order. Containers are wrapped in a system
  to satisfy validate-nesting (containers must live inside a system)."
  [chain-src]
  (-> (c/parse (str "(diagram (system root
                                (container a) (container b)
                                (container c) (container d)
                                (container e) (container f))
                              " chain-src ")"))
      :relationships))

(deftest chain-two-nodes
  (let [edges (chain-edges "(=> a b)")]
    (is (= 1 (count edges)))
    (is (= 'a (-> edges first :from)))
    (is (= 'b (-> edges first :to)))))

(deftest chain-three-nodes-yields-two-edges
  (let [edges (chain-edges "(=> a b c)")]
    (is (= 2 (count edges)))
    (is (= [['a 'b] ['b 'c]]
           (map (juxt :from :to) edges)))))

(deftest chain-with-label-and-attrs-applies-to-every-edge
  (let [edges (chain-edges "(=> a b c \"hop\" :tech \"gRPC\")")]
    (is (= 2 (count edges)))
    (is (every? #(= "hop" (:description %)) edges))
    (is (every? #(= "gRPC" (-> % :attrs :tech)) edges))))

(deftest chain-fan-out-from-scalar-to-vector
  (let [edges (chain-edges "(=> a [b c] d)")]
    (is (= 4 (count edges)))
    (is (= #{['a 'b] ['a 'c] ['b 'd] ['c 'd]}
           (set (map (juxt :from :to) edges))))))

(deftest chain-vector-to-vector-cross-product
  (let [edges (chain-edges "(=> [a b] [c d])")]
    (is (= 4 (count edges)))
    (is (= #{['a 'c] ['a 'd] ['b 'c] ['b 'd]}
           (set (map (juxt :from :to) edges))))))

(deftest chain-rejects-single-node
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"chain requires at least 2 nodes"
        (c/parse "(diagram (container a) (=> a))"))))

(deftest chain-rejects-empty-fan-vector
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"chain fan vector cannot be empty"
        (c/parse "(diagram (container a) (container b) (=> a [] b))"))))

(deftest chain-merges-wrap-attrs
  (testing ":wrap-attrs from ctx flows into every chain edge"
    (let [edges (walker/walk-form '(=> a b c :tech "REST")
                                  {:wrap-attrs {:tech "gRPC" :style :dashed}})]
      (is (vector? edges))
      (is (= 2 (count edges)))
      (is (every? #(= "REST"   (-> % :attrs :tech))  edges))
      (is (every? #(= :dashed  (-> % :attrs :style)) edges)))))

(deftest chain-self-loop-allowed
  (testing "(=> a a) produces a self-loop edge — consistent with (-> a a)"
    (let [edges (chain-edges "(=> a a)")]
      (is (= 1 (count edges)))
      (is (= 'a (-> edges first :from)))
      (is (= 'a (-> edges first :to))))))

(deftest chain-rejects-trailing-children
  (testing "(=> a b \"label\" :tech \"X\" (foo)) — extra form after trailer is rejected"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs :default)
          #"trailing args must be label \+ attrs"
          (c/parse "(diagram (system root (container a) (container b))
                     (=> a b \"label\" :tech \"X\" (foo)))")))))

(deftest chain-rejects-zero-args
  (testing "(=>) with no args fails the >= 2 nodes check"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs :default)
          #"chain requires at least 2 nodes"
          (c/parse "(diagram (=>))")))))

;; --- with-attrs defaults supplier ---------------------------------------

(deftest with-attrs-flat-wraps-edges
  (let [edges (-> (c/parse "(diagram (system root (container a) (container b))
                              (with-attrs {:tech \"gRPC\"} (-> a b)))")
                  :relationships)]
    (is (= 1 (count edges)))
    (is (= "gRPC" (-> edges first :attrs :tech)))))

(deftest with-attrs-edge-overrides-wrap
  (let [edges (-> (c/parse "(diagram (system root (container a) (container b))
                              (with-attrs {:tech \"gRPC\"}
                                (-> a b :tech \"REST\")))")
                  :relationships)]
    (is (= "REST" (-> edges first :attrs :tech))
        "edge :tech wins over wrap :tech")))

(deftest with-attrs-nested-inner-wins
  (let [edges (-> (c/parse "(diagram (system root (container a) (container b))
                              (with-attrs {:tech \"gRPC\" :style :solid}
                                (with-attrs {:tech \"REST\"}
                                  (-> a b))))")
                  :relationships)]
    (is (= "REST"  (-> edges first :attrs :tech)))
    (is (= :solid  (-> edges first :attrs :style))
        "outer :style passes through, inner :tech overrides")))

(deftest with-attrs-flows-through-element
  (testing "wrap-attrs reach edges inside an element nested in with-attrs body"
    (let [edges (-> (c/parse "(diagram
                                (with-attrs {:tech \"gRPC\"}
                                  (system root
                                    (container api \"API\"
                                      (-> db \"reads\"))
                                    (container db \"DB\")))
                                )")
                    :elements first :children first :edges)]
      (is (= 1 (count edges)))
      (is (= 'api (:from (first edges))))
      (is (= "gRPC" (-> edges first :attrs :tech))))))

(deftest with-attrs-flows-into-chain
  (let [edges (-> (c/parse "(diagram (system root (container a) (container b) (container c))
                              (with-attrs {:tech \"gRPC\"}
                                (=> a b c)))")
                  :relationships)]
    (is (= 2 (count edges)))
    (is (every? #(= "gRPC" (-> % :attrs :tech)) edges))))

(deftest with-attrs-rejects-non-map-first-arg
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"with-attrs requires a map"
        (c/parse "(diagram (system root (container a) (container b))
                   (with-attrs :tech \"gRPC\" (-> a b)))"))))

(deftest with-attrs-empty-body-no-op
  (let [ir (c/parse "(diagram (system root (container a))
                              (with-attrs {:tech \"gRPC\"}))")]
    (is (= [] (:relationships ir)))))
