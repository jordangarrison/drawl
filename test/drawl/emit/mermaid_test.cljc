(ns drawl.emit.mermaid-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [drawl.compiler :as c]
            [drawl.ir :as ir]))

;; ── header per level ───────────────────────────────────────────────

(deftest context-level-header
  (let [out (c/compile "(diagram (person alice))" :mermaid)]
    (is (str/starts-with? out "C4Context"))))

(deftest container-level-header
  (let [out (c/compile "(diagram (system s (container c)))" :mermaid)]
    (is (str/starts-with? out "C4Container"))))

(deftest component-level-header
  (let [out (c/compile "(diagram (system s (container c (component k))))" :mermaid)]
    (is (str/starts-with? out "C4Component"))))

(deftest at-level-strips-deeper-kinds
  (let [ir   (c/parse "(diagram (system s (container c (component k))))")
        out  (c/emit (ir/at-level :context ir) :mermaid)]
    (is (str/starts-with? out "C4Context"))
    (is (not (str/includes? out "Container(")))
    (is (not (str/includes? out "Component(")))))

;; ── title ──────────────────────────────────────────────────────────

(deftest title-line-renders
  (let [out (c/compile "(diagram \"My Diagram\" (person a))" :mermaid)]
    (is (str/includes? out "title My Diagram"))))

(deftest missing-title-renders-empty
  (let [out (c/compile "(diagram (person a))" :mermaid)]
    (is (str/includes? out "title "))))

;; ── person ─────────────────────────────────────────────────────────

(deftest person-renders
  (let [out (c/compile "(diagram (person alice \"Alice\" \"A user\"))" :mermaid)]
    (is (str/includes? out "Person(alice, \"Alice\", \"A user\")"))))

(deftest person-external-flips-suffix
  (let [out (c/compile "(diagram (person alice :external true))" :mermaid)]
    (is (str/includes? out "Person_Ext(alice"))))

(deftest person-missing-description-emits-empty
  (let [out (c/compile "(diagram (person alice \"Alice\"))" :mermaid)]
    (is (str/includes? out "Person(alice, \"Alice\", \"\")"))))

(deftest person-missing-title-falls-back-to-id
  (let [out (c/compile "(diagram (person alice))" :mermaid)]
    (is (str/includes? out "Person(alice, \"alice\", \"\")"))))

;; ── system ─────────────────────────────────────────────────────────

(deftest system-leaf-renders
  (let [out (c/compile "(diagram (system bank \"Bank\" \"Core banking\"))" :mermaid)]
    (is (str/includes? out "System(bank, \"Bank\", \"Core banking\")"))))

(deftest system-external-flips-suffix
  (let [out (c/compile "(diagram (system mainframe \"Mainframe\" :external true))" :mermaid)]
    (is (str/includes? out "System_Ext(mainframe, \"Mainframe\", \"\")"))))

(deftest system-with-children-renders-boundary
  (let [out (c/compile "(diagram (system bank \"Bank\" (container web \"Web\")))" :mermaid)]
    (is (str/includes? out "System_Boundary(bank, \"Bank\") {"))
    (is (str/includes? out "Container(web, \"Web\""))
    (is (str/includes? out "}"))))

(deftest nested-system-boundary
  (let [out (c/compile
             "(diagram
                (system outer \"Outer\"
                  (system inner \"Inner\"
                    (container c \"C\"))))" :mermaid)]
    (is (str/includes? out "System_Boundary(outer, \"Outer\") {"))
    (is (str/includes? out "System_Boundary(inner, \"Inner\") {"))))

;; ── container ──────────────────────────────────────────────────────

(deftest container-renders-with-tech
  (let [out (c/compile
             "(diagram (system s (container web \"Web\" :tech \"Phoenix\")))"
             :mermaid)]
    (is (str/includes? out "Container(web, \"Web\", \"Phoenix\", \"\")"))))

(deftest container-missing-tech-emits-empty
  (let [out (c/compile "(diagram (system s (container web \"Web\")))" :mermaid)]
    (is (str/includes? out "Container(web, \"Web\", \"\", \"\")"))))

(deftest container-role-database-renders-db
  (let [out (c/compile
             "(diagram (system s (container db \"DB\" :role :database :tech \"PG\")))"
             :mermaid)]
    (is (str/includes? out "ContainerDb(db, \"DB\", \"PG\", \"\")"))))

(deftest container-role-queue-renders-queue
  (let [out (c/compile
             "(diagram (system s (container q \"Q\" :role :queue)))" :mermaid)]
    (is (str/includes? out "ContainerQueue(q, \"Q\", \"\", \"\")"))))

(deftest container-with-children-renders-boundary
  (let [out (c/compile
             "(diagram (system s (container api \"API\" (component sign-in \"Sign-in\"))))"
             :mermaid)]
    (is (str/includes? out "Container_Boundary(api, \"API\") {"))
    (is (str/includes? out "Component(sign-in, \"Sign-in\""))))

(deftest container-external-not-flipped
  (testing "C4-PlantUML has no Container_Ext; :external is silently ignored on containers"
    (let [out (c/compile
               "(diagram (system s (container c :external true)))" :mermaid)]
      (is (str/includes? out "Container(c"))
      (is (not (str/includes? out "Container_Ext"))))))

;; ── component ──────────────────────────────────────────────────────

(deftest component-renders-with-tech
  (let [out (c/compile
             "(diagram (system s (container c (component k \"K\" :tech \"Java\"))))"
             :mermaid)]
    (is (str/includes? out "Component(k, \"K\", \"Java\", \"\")"))))

(deftest component-role-database-renders-db
  (let [out (c/compile
             "(diagram
                (system s (container c (component k :role :database :tech \"PG\"))))"
             :mermaid)]
    (is (str/includes? out "ComponentDb(k, \"k\", \"PG\", \"\")"))))

;; ── edges ──────────────────────────────────────────────────────────

(deftest rel-emits-with-label-and-tech
  (let [out (c/compile
             "(diagram (person a) (person b) (-> a b \"calls\" :tech \"HTTP\"))"
             :mermaid)]
    (is (str/includes? out "Rel(a, b, \"calls\", \"HTTP\")"))))

(deftest rel-emits-empty-label-and-tech-when-missing
  (let [out (c/compile "(diagram (person a) (person b) (-> a b))" :mermaid)]
    (is (str/includes? out "Rel(a, b, \"\", \"\")"))))

(deftest birel-for-bidirectional
  (let [out (c/compile
             "(diagram (person a) (person b) (<-> a b \"sync\"))" :mermaid)]
    (is (str/includes? out "BiRel(a, b, \"sync\", \"\")"))))

(deftest edge-style-silently-ignored
  (testing "mermaid C4 has no per-edge stroke control; :style passes through silently"
    (let [out (c/compile
               "(diagram (person a) (person b) (-> a b :style :dashed))" :mermaid)]
      (is (str/includes? out "Rel(a, b, \"\", \"\")"))
      (is (not (str/includes? out "dashed"))))))

(deftest cross-boundary-edge-anchors-to-first-leaf
  (testing "mermaid 10 C4 rejects Rel(boundary, ...); rewrite to first leaf"
    (let [out (c/compile
               "(diagram
                  (system s
                    (container src (component a))
                    (container dst (component b)))
                  (-> src dst))" :mermaid)]
      (is (str/includes? out "Rel(a, b"))
      (is (not (str/includes? out "Rel(src, dst")))
      (is (not (str/includes? out "ltail")))
      (is (not (str/includes? out "lhead"))))))

(deftest cluster-anchor-walks-deepest-first-leaf
  (let [out (c/compile
             "(diagram
                (system outer
                  (system inner
                    (container deepest))
                  (container sibling))
                (-> outer sibling))" :mermaid)]
    (is (str/includes? out "Rel(deepest, sibling"))))

(deftest cluster-anchor-follows-declaration-order
  (let [a-first (c/compile
                 "(diagram
                    (system s
                      (container parent
                        (component leaf-a)
                        (component leaf-b))
                      (container sibling))
                    (-> parent sibling))" :mermaid)
        b-first (c/compile
                 "(diagram
                    (system s
                      (container parent
                        (component leaf-b)
                        (component leaf-a))
                      (container sibling))
                    (-> parent sibling))" :mermaid)]
    (is (str/includes? a-first "Rel(leaf-a, sibling"))
    (is (str/includes? b-first "Rel(leaf-b, sibling"))))

(deftest symbol-tech-attr-coerces-to-string
  (testing "users sometimes write :tech foo (bare symbol); emitter must not throw"
    (let [out (c/compile
               "(diagram (system s (container c \"C\" :tech s3)))" :mermaid)]
      (is (str/includes? out "Container(c, \"C\", \"s3\", \"\")")))))

;; ── escaping ───────────────────────────────────────────────────────

(deftest quotes-in-labels-escape
  (let [out (c/compile "(diagram (person a \"He said \\\"hi\\\"\"))" :mermaid)]
    (is (str/includes? out "Person(a, \"He said \\\"hi\\\"\""))))

;; ── error surface ──────────────────────────────────────────────────

(deftest unresolved-endpoint-still-errors-on-mermaid
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs :default)
        #"Unresolved edge endpoint"
        (c/compile "(diagram (person a) (-> a nope))" :mermaid))))
