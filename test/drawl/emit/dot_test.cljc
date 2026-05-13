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

;; --- cluster-edge rendering (compound=true + ltail/lhead) ---------------
;; When an edge endpoint is an element that has children, that element
;; renders as a graphviz `subgraph cluster_<id>` rather than a node. The
;; emitter must (a) declare `compound=true` and (b) redirect such edges
;; to a real interior node + an `ltail`/`lhead` cluster reference, or
;; graphviz auto-creates a phantom empty node outside the cluster.

(deftest compound-true-declared
  (let [out (c/compile
             "(diagram (system bank (container web) (container api)))"
             :dot)]
    (is (str/includes? out "compound=true;"))))

(deftest edge-from-cluster-uses-ltail-and-anchor
  (testing "(-> parent sibling) where parent has children: anchor=first leaf, ltail=cluster_parent"
    (let [out (c/compile
               "(diagram
                  (system s
                    (container parent
                      (component child-a)
                      (component child-b))
                    (container sibling))
                  (-> parent sibling \"calls\"))"
               :dot)]
      (is (str/includes? out "\"child-a\" -> \"sibling\"")
          "from anchor is parent's first leaf descendant (child-a)")
      (is (re-find #"\"child-a\" -> \"sibling\" \[[^\]]*ltail=\"cluster_parent\"" out)
          "ltail must reference the parent cluster")
      (is (not (re-find #"\"parent\" -> \"sibling\"" out))
          "no edge directly from cluster name (would create phantom node)"))))

(deftest edge-to-cluster-uses-lhead-and-anchor
  (let [out (c/compile
             "(diagram
                (system s
                  (container sibling)
                  (container parent
                    (component child)))
                (-> sibling parent \"calls\"))"
             :dot)]
    (is (re-find #"\"sibling\" -> \"child\" \[[^\]]*lhead=\"cluster_parent\"" out))
    (is (not (re-find #"\"sibling\" -> \"parent\"" out)))))

(deftest edge-between-two-clusters-uses-both
  (let [out (c/compile
             "(diagram
                (system s
                  (container src
                    (component src-leaf))
                  (container dst
                    (component dst-leaf)))
                (-> src dst))"
             :dot)]
    (is (re-find #"\"src-leaf\" -> \"dst-leaf\" \[[^\]]*ltail=\"cluster_src\"[^\]]*lhead=\"cluster_dst\""
                 out))))

(deftest leaf-to-leaf-edge-unaffected
  (testing "regression: edges between leaf elements get no ltail/lhead"
    (let [out (c/compile
               "(diagram (person a) (system b) (-> a b))" :dot)]
      (is (str/includes? out "\"a\" -> \"b\";"))
      (is (not (re-find #"\"a\" -> \"b\" \[" out))))))

(deftest cluster-anchor-walks-to-deepest-first-leaf
  (testing "nested clusters: anchor is the first leaf, even through several levels"
    (let [out (c/compile
               "(diagram
                  (system outer
                    (system inner
                      (container deepest))
                    (container sibling))
                  (-> outer sibling))"
               :dot)]
      (is (str/includes? out "\"deepest\" -> \"sibling\"")
          "anchor is the first leaf at the bottom of outer's first-child chain"))))
