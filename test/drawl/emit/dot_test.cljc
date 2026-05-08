(ns drawl.emit.dot-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [drawl.compiler :as c]))

;; Note: dot ids and cluster names are quoted to support hyphens and other
;; non-alphanumeric characters in drawl symbols. Tests assert quoted forms.

(deftest person-renders-oval
  (let [out (c/compile "(diagram (person alice \"Alice\"))" :dot)]
    (is (str/includes? out "\"alice\" [shape=\"oval\""))
    (is (str/includes? out "label=\"Alice\""))))

(deftest edge-renders-as-arrow
  (let [out (c/compile "(diagram (person a) (system b) (-> a b \"visits\"))" :dot)]
    (is (str/includes? out "\"a\" -> \"b\" ["))
    (is (str/includes? out "label=\"visits\""))))

(deftest edge-with-no-description
  (let [out (c/compile "(diagram (person a) (system b) (-> a b))" :dot)]
    (is (str/includes? out "\"a\" -> \"b\";"))))

(deftest unresolved-endpoint-errors
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"Unresolved edge endpoint"
        (c/compile "(diagram (person a) (-> a nope))" :dot))))

(deftest duplicate-id-errors
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"Duplicate id"
        (c/compile "(diagram (person a) (system a))" :dot))))

(deftest container-leaf-renders-filled-box
  (let [out (c/compile "(diagram (system s (container db \"DB\")))" :dot)]
    (is (str/includes? out "\"db\" [shape=\"box\" style=\"rounded,filled\""))))

(deftest system-with-children-renders-cluster
  (let [out (c/compile
             "(diagram (system bank \"Bank\" (container web \"Web\") (container api \"API\")))"
             :dot)]
    (is (str/includes? out "subgraph \"cluster_bank\" {"))
    (is (str/includes? out "label=\"Bank\""))
    (is (str/includes? out "\"web\" ["))
    (is (str/includes? out "\"api\" ["))
    (is (str/includes? out "}"))))

(deftest scoped-edges-emit-once
  (let [out (c/compile
             "(diagram
                (system bank
                  (container web)
                  (container api)
                  (-> web api \"calls\")))"
             :dot)
        arrows (count (re-seq #"\"web\" -> \"api\"" out))]
    (is (= 1 arrows) "edge inside system children should appear exactly once")))

(deftest hyphenated-id-emits-quoted
  (testing "drawl symbols with hyphens (e.g. sign-in) must be quoted in dot"
    (let [out (c/compile
               "(diagram
                  (system s (container c (component sign-in)
                                         (component repo)
                                         (-> sign-in repo))))"
               :dot)]
      (is (str/includes? out "\"sign-in\" ["))
      (is (str/includes? out "\"sign-in\" -> \"repo\";")))))

(def multi-system-src
  "(diagram \"Two systems\"
     (person user \"User\")
     (system app \"App\"
       (container web \"Web\")
       (container api \"API\"))
     (system payments \"Payments\"
       (container processor \"Processor\"))
     (-> user web \"uses\")
     (-> api processor \"charges\"))")

(deftest multi-system-renders-two-clusters
  (let [out (c/compile multi-system-src :dot)]
    (is (str/includes? out "subgraph \"cluster_app\" {"))
    (is (str/includes? out "subgraph \"cluster_payments\" {"))
    (is (str/includes? out "\"user\" [shape=\"oval\""))))

(deftest multi-system-cross-system-edge
  (let [out (c/compile multi-system-src :dot)]
    (is (str/includes? out "\"api\" -> \"processor\" [label=\"charges\"]"))
    (is (str/includes? out "\"user\" -> \"web\" [label=\"uses\"]"))))

;; Slice 5: role / external / tech / edge style / bidirectional.

(deftest role-database-renders-cylinder
  (let [out (c/compile
             "(diagram (system s (container db :role :database)))" :dot)]
    (is (str/includes? out "\"db\" [shape=\"cylinder\""))
    (is (not (re-find #"\"db\" \[[^\]]*style=\"" out))
        "cylinder drops the rounded,filled default style")))

(deftest external-leaf-element-gets-dashed-style
  (let [out (c/compile
             "(diagram (system mainframe :external true))" :dot)]
    (is (str/includes? out "\"mainframe\" [shape=\"box\" style=\"rounded,dashed\""))))

(deftest external-cluster-gets-dashed-style
  (let [out (c/compile
             "(diagram (system bank :external true (container web)))" :dot)]
    (is (str/includes? out "subgraph \"cluster_bank\" {"))
    (is (re-find #"label=\"bank\" style=\"rounded,dashed\";"
                 out)
        "the cluster header line itself must carry the dashed style")))

(deftest tech-attribute-renders-as-sub-label
  (let [out (c/compile
             "(diagram (system bank (container web \"Web\" :tech \"Phoenix\")))"
             :dot)]
    (is (str/includes? out "label=\"Web\\n[Phoenix]\""))))

(deftest macro-postgres-db-emits-cylinder-with-tech
  (let [out (c/compile "(diagram (system bank (postgres-db db \"DB\")))" :dot)]
    (is (str/includes? out "\"db\" [shape=\"cylinder\""))
    (is (str/includes? out "label=\"DB\\n[Postgres]\""))))

(deftest edge-style-passes-through
  (let [out (c/compile
             "(diagram (person a) (person b) (-> a b :style :dashed))" :dot)]
    (is (str/includes? out "\"a\" -> \"b\" [style=\"dashed\"]"))))

(deftest edge-tech-renders-in-label
  (let [out (c/compile
             "(diagram (person a) (person b) (-> a b \"calls\" :tech \"HTTP\"))"
             :dot)]
    (is (str/includes? out "label=\"calls\\n[HTTP]\""))))

(deftest edge-tech-only-renders-bracketed
  (let [out (c/compile
             "(diagram (person a) (person b) (-> a b :tech \"HTTP\"))" :dot)]
    (is (str/includes? out "label=\"[HTTP]\""))))

(deftest bidirectional-edge-emits-dir-both
  (let [out (c/compile
             "(diagram (person a) (person b) (<-> a b \"sync\"))" :dot)]
    (is (str/includes? out "\"a\" -> \"b\" [label=\"sync\" dir=\"both\"]"))))

(deftest multi-system-leaf-mixed-with-boundary
  (testing "one system with children (cluster) + another system as leaf"
    (let [out (c/compile
               "(diagram
                  (system bank \"Bank\"
                    (container webapp))
                  (system mainframe \"Mainframe\")
                  (-> webapp mainframe \"calls\"))"
               :dot)]
      (is (str/includes? out "subgraph \"cluster_bank\" {"))
      (is (str/includes? out "\"mainframe\" [shape=\"box\" style=\"rounded\""))
      (is (str/includes? out "\"webapp\" -> \"mainframe\"")))))
