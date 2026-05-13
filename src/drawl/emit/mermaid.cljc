(ns drawl.emit.mermaid
  "IR -> mermaid C4-PlantUML source (SPEC §6.2).

  Header per level: C4Context / C4Container / C4Component.

  Element kinds:
    :person     -> Person(id, T, D)         (Person_Ext if :external)
    :system     -> System(id, T, D)         (System_Ext if :external)
    :system  w/ children -> System_Boundary(id, T) { ... }
    :container  -> Container(id, T, Tech, D)
                   ContainerDb if :role :database
                   ContainerQueue if :role :queue
    :container w/ children -> Container_Boundary(id, T) { ... }
    :component  -> Component(id, T, Tech, D)
                   ComponentDb / ComponentQueue per :role

  Edges:
    (->)  -> Rel(from, to, label, tech)
    (<->) -> BiRel(from, to, label, tech)

  Cluster-edge anchoring: mermaid 10 C4 throws when an edge endpoint
  is itself a `System_Boundary` or `Container_Boundary` (\"reading 'x'\"
  during layout). We mirror the dot emitter's contract — for each
  cluster, pick the first leaf descendant in source order as the
  anchor, and rewrite the edge endpoint to that anchor. Users control
  which leaf gets the visible end of the arrow by ordering the
  children. There is no mermaid equivalent of dot's `ltail`/`lhead` so
  the boundary itself is not visually highlighted — the edge simply
  attaches to the chosen interior leaf.

  Unknown attributes (e.g. :style on edges, :role on Person/System,
  :tech on Person/System) are silently ignored per SPEC §2.4."
  (:require [clojure.string :as str]
            [drawl.ir :as ir]))

;; ── strings ────────────────────────────────────────────────────────

(defn- esc
  "Escape `\"` in a string literal. Mermaid C4 strings are double-quoted
  the same as dot — backslashes pass through unchanged."
  [s]
  (str/replace (or s "") "\"" "\\\""))

(defn- q
  "Quote a value for a mermaid C4 arg slot. Coerces non-string values
  (keywords, symbols, numbers) to their string form first — drawl
  attrs like `:tech s3` parse as a bare symbol, so an emitter that
  insists on strings here will throw at render time."
  [v]
  (str "\"" (esc (cond
                   (nil? v)    ""
                   (string? v) v
                   :else       (str v))) "\""))

;; ── element name + level ───────────────────────────────────────────

(def ^:private level->header
  {:context   "C4Context"
   :container "C4Container"
   :component "C4Component"})

(def ^:private role->suffix
  {:database "Db"
   :queue    "Queue"})

(defn- macro-name
  "C4-PlantUML macro for a leaf element. `:role` flips the suffix on
  Container/Component only; Person/System ignore :role (no _Db/_Queue
  variants exist in C4-PlantUML)."
  [{:keys [kind attrs]}]
  (let [base (case kind
               :person    "Person"
               :system    "System"
               :container "Container"
               :component "Component")
        ext? (:external attrs)
        suff (when (#{:container :component} kind)
               (role->suffix (:role attrs)))]
    (cond
      ;; Container/Component never take the _Ext flip in C4-PlantUML.
      (and ext? (#{:person :system} kind)) (str base "_Ext")
      suff                                  (str base suff)
      :else                                 base)))

;; ── element lines ──────────────────────────────────────────────────

(defn- title-of [el]
  (or (:title el) (name (:id el))))

(defn- leaf-line
  "One line of mermaid C4 for a leaf element. Per-kind arity:
     Person/System         -> 3 args (id, title, description)
     Container/Component   -> 4 args (id, title, tech, description)"
  [indent {:keys [id kind attrs description] :as el}]
  (let [macro (macro-name el)
        head  (str indent macro "(" (name id) ", " (q (title-of el)))
        tail  (case kind
                (:person :system)
                (str ", " (q description) ")")
                (:container :component)
                (str ", " (q (:tech attrs)) ", " (q description) ")"))]
    (str head tail)))

(defn- boundary-line
  "Open-line for a parent element. Uses mermaid's generic
  `Boundary(id, label, type)` form rather than `System_Boundary` /
  `Container_Boundary` — the dedicated macros render the boundary
  with an `[ENTERPRISE]` stereotype label regardless of kind (a
  long-standing mermaid C4 quirk). Passing the type explicitly via
  the generic form makes the stereotype say `[system]` /
  `[container]`, which is what users actually mean."
  [indent {:keys [id kind] :as el}]
  (let [boundary-type (case kind :system "system" :container "container")]
    (str indent "Boundary(" (name id) ", "
         (q (title-of el)) ", " (q boundary-type) ") {")))

(defn- element-lines
  "Lines for an element. Leaf -> one line. Parent -> open boundary,
  recurse children, close. Indent grows 2 spaces per nesting level."
  [indent {:keys [children] :as el}]
  (if (seq children)
    (concat [(boundary-line indent el)]
            (mapcat #(element-lines (str indent "  ") %) children)
            [(str indent "}")])
    [(leaf-line indent el)]))

;; ── edges ──────────────────────────────────────────────────────────

(defn- first-leaf-id
  "Walk down the first-child chain to the first leaf and return its id.
  Mirrors the contract of `drawl.emit.dot/first-leaf-id`: declaration
  order picks the anchor — put the representative child first if you
  care which interior node carries the visible end of the arrow."
  [{:keys [id children]}]
  (if (seq children)
    (recur (first children))
    id))

(defn- index-clusters
  "Walk the element tree, returning `{cluster-id anchor-leaf-id}` for
  every element that has children (renders as a *_Boundary*). Edges
  whose endpoints are boundary ids must be rewritten to point at the
  anchor — mermaid 10 C4 fails layout when an edge endpoint is a
  boundary."
  [elements]
  (letfn [(walk [acc el]
            (let [acc (if (seq (:children el))
                        (assoc acc (:id el) (first-leaf-id el))
                        acc)]
              (reduce walk acc (:children el))))]
    (reduce walk {} elements)))

(defn- edge-line
  "Rel or BiRel line. Arity is always 4: from, to, label, tech.
  Both label and tech default to empty string so the arity stays
  fixed; mermaid renders empty fields as blank. Boundary endpoints
  are rewritten to the first-leaf anchor."
  [indent clusters {:keys [from to bidirectional? description attrs]}]
  (let [m    (if bidirectional? "BiRel" "Rel")
        from (get clusters from from)
        to   (get clusters to   to)
        tech (:tech attrs)]
    (str indent m "(" (name from) ", " (name to) ", "
         (q description) ", " (q tech) ")")))

;; ── emit ───────────────────────────────────────────────────────────

(defn emit
  "IR -> mermaid C4 string."
  [{:keys [title level elements] :as ir}]
  (let [hdr      (or (level->header level) "C4Context")
        clusters (index-clusters elements)
        body  (concat
                [hdr
                 (str "    title " (or title ""))]
                (mapcat #(element-lines "    " %) elements)
                (map   #(edge-line     "    " clusters %) (ir/collect-edges ir)))]
    (str/join "\n" body)))
