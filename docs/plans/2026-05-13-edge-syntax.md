# Edge syntax Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three additive edge-syntax mechanisms (implicit-`from`, `=>` chain with vector fan, `with-attrs` defaults supplier) to reduce edge line bloat in `.drawl` sources.

**Architecture:** All three desugar into the existing edge IR `{:kind :edge :from :to :description :attrs :bidirectional?}` inside `drawl.walker`. The walker's ctx gains two keys (`:current-element`, `:wrap-attrs`). `walk-children` becomes mapcat-style so forms can return vectors of edges. Emitters, IR validation, parser — unchanged.

**Tech Stack:** Clojure / ClojureScript (`.cljc`). Cognitect test runner via `clojure -M:test`. Spec at `docs/specs/2026-05-13-edge-syntax-design.md` — re-read it before each task; it is the source of truth.

---

## File map

- **Modify**: `src/drawl/walker.cljc` — ctx threading, arity-disambiguating `walk-edge`, new `walk-form` defmethods for `'=>` and `'with-attrs`, mapcat-style `walk-children`.
- **Modify**: `test/drawl/walker_test.cljc` — add tests for each new behavior.
- **Create**: `test/fixtures/edges/01-mixed.drawl` — end-to-end fixture exercising all three forms.
- **Create**: `test/fixtures/edges/01-mixed.edn` — expected IR for the fixture.
- **Modify**: `test/drawl/compiler_test.cljc` — load fixture, assert IR equals expected.
- **Modify**: `examples/03-bank-containers.drawl`, `examples/05-macros.drawl`, `examples/06-nested-systems.drawl` — rewrite using new forms.
- **Create**: `examples/08-journey.drawl` — new example showcasing `=>`.
- **Modify**: `SPEC.org` §2.4 (relationships) — document new mechanisms.
- **Modify**: `GRAMMAR.org` Relationships section — same.

No new namespaces. No emitter changes. No IR changes.

---

## Task 1: Implicit-`from` on `(->)` and `(<->)`

**Files:**
- Modify: `src/drawl/walker.cljc:55-73,89-104`
- Test: `test/drawl/walker_test.cljc` (append)

The walker must thread the enclosing element id through ctx as `:current-element`, and `walk-edge` must disambiguate by arity of leading symbols. When `:wrap-attrs` is in ctx (added by `with-attrs` in Task 3, but the merge code lands here forward-compatibly), edge attrs merge over it.

- [ ] **Step 1.1: Add failing tests for implicit-`from`**

Append to `test/drawl/walker_test.cljc`:

```clojure
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
        clojure.lang.ExceptionInfo
        #"two endpoints"
        (c/parse "(diagram (system s) (-> s))"))))

(deftest edge-merges-wrap-attrs-from-ctx
  (testing "walk-edge picks up :wrap-attrs from ctx; edge attrs win on conflict"
    (let [edge-a (walker/walk-form '(-> a b) {:wrap-attrs {:tech "gRPC"}})
          edge-b (walker/walk-form '(-> a b :tech "REST")
                                   {:wrap-attrs {:tech "gRPC" :style :dashed}})]
      (is (= {:tech "gRPC"} (:attrs edge-a)))
      (is (= {:tech "REST" :style :dashed} (:attrs edge-b))
          "edge :tech wins, wrap :style passes through"))))
```

- [ ] **Step 1.2: Run tests to confirm they fail**

Run: `clojure -M:test -n drawl.walker-test`

Expected: 5 failures — implicit-from cases throw "edge requires a symbol id" or similar; wrap-attrs cases see empty `:attrs`.

- [ ] **Step 1.3: Modify `walk-edge` and `walk-element` in `src/drawl/walker.cljc`**

Replace `walk-element` (lines 55–73):

```clojure
(defn- walk-element
  "Shared shape for person/system/container/component forms.
  Layout: (head ID header... children...) where header is any interleaving
  of positional title/description strings (up to 2) and :attr value pairs."
  [kind form ctx]
  (let [[_ id & rest] form
        _ (when-not (symbol? id)
            (throw (ex-info (str (name kind) " requires a symbol id, got: "
                                 (pr-str id))
                            {:type :walk-error :form form}))) 
        [[title description] attrs children] (split-header rest)
        child-ctx                            (assoc ctx :current-element id)
        [child-els edges]                    (walk-children children child-ctx)]
    {:kind        kind
     :id          id
     :title       title
     :description description
     :attrs       attrs
     :children    child-els
     :edges       edges}))
```

Replace `walk-edge` (lines 89–101) and its two defmethods:

```clojure
(defn- walk-edge [bidirectional? form ctx]
  (let [args (rest form)
        [from to rest-args]
        (cond
          (and (symbol? (first args)) (symbol? (second args)))
          [(first args) (second args) (drop 2 args)]

          (and (symbol? (first args)) (:current-element ctx))
          [(:current-element ctx) (first args) (rest args)]

          :else
          (throw (ex-info "edge requires two endpoints at top level"
                          {:type :walk-error :form form})))
        [[desc _] own-attrs leftover] (split-header rest-args)]
    (when (seq leftover)
      (throw (ex-info (str "edge does not accept children, got: "
                           (pr-str leftover))
                      {:type :walk-error :form form})))
    {:kind           :edge
     :from           from
     :to             to
     :bidirectional? bidirectional?
     :description    desc
     :attrs          (merge (:wrap-attrs ctx) own-attrs)}))

(defmethod walk-form '->  [form ctx] (walk-edge false form ctx))
(defmethod walk-form '<-> [form ctx] (walk-edge true  form ctx))
```

Note the existing bidirectional `walk-edge` signature was `[bidirectional? form]` — we add `ctx` and update both defmethods to pass it.

- [ ] **Step 1.4: Run tests, confirm pass**

Run: `clojure -M:test -n drawl.walker-test`

Expected: all walker tests pass, including the 5 new ones and the existing `bidirectional-edge-walks` (which calls `walk-form` with empty ctx).

- [ ] **Step 1.5: Run the full test suite to catch regressions**

Run: `clojure -M:test`

Expected: all tests pass.

- [ ] **Step 1.6: Commit**

```bash
git add src/drawl/walker.cljc test/drawl/walker_test.cljc
git commit -m "feat(drawl): implicit-from on (->)/(<->) inside element bodies"
```

---

## Task 2: `=>` chain with vector fan

**Files:**
- Modify: `src/drawl/walker.cljc`
- Test: `test/drawl/walker_test.cljc` (append)

`=>` produces a vector of edges. To handle that, `walk-children` becomes mapcat-style. Walker dispatch gets a new defmethod for `'=>`.

- [ ] **Step 2.1: Add failing tests for `=>`**

Append to `test/drawl/walker_test.cljc`:

```clojure
;; --- => chain with vector fan -------------------------------------------

(defn- chain-edges
  "Convenience: parse a diagram containing only the chain form,
  return its relationships in order."
  [chain-src]
  (-> (c/parse (str "(diagram (container a) (container b)
                              (container c) (container d)
                              (container e) (container f)
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
        clojure.lang.ExceptionInfo
        #"chain requires at least 2 nodes"
        (c/parse "(diagram (container a) (=> a))"))))

(deftest chain-rejects-empty-fan-vector
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
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
```

- [ ] **Step 2.2: Run tests, confirm fail**

Run: `clojure -M:test -n drawl.walker-test`

Expected: 8 new failures, all with "Unknown form head: =>" (walker has no defmethod for `=>` yet).

- [ ] **Step 2.3: Refactor `walk-children` to flatten vector returns**

Replace `walk-children` in `src/drawl/walker.cljc` (around lines 46–53):

```clojure
(defn- walk-children
  "Walk each child form and split the results into [elements edges].
  A child walker may return a single node or a vector of nodes (e.g.
  =>, with-attrs); vectors are spliced. Used by every element-bearing
  head (diagram + person/system/container/component)."
  [children ctx]
  (let [walked    (mapv #(walk-form % ctx) children)
        flattened (mapcat (fn [r] (if (vector? r) r [r])) walked)
        {elements true edges false}
        (group-by #(not= :edge (:kind %)) flattened)]
    [(vec elements) (vec edges)]))
```

- [ ] **Step 2.4: Run tests, confirm previously-passing tests still pass**

Run: `clojure -M:test -n drawl.walker-test`

Expected: existing tests still pass, new `=>` tests still fail (defmethod still missing).

- [ ] **Step 2.5: Add `walk-form` defmethod for `=>`**

Append to `src/drawl/walker.cljc` (after the `<->` defmethod at end):

```clojure
(defmethod walk-form '=> [form ctx]
  (let [args (rest form)
        [chain trailer] (split-with #(or (symbol? %) (vector? %)) args)
        _ (when (< (count chain) 2)
            (throw (ex-info "chain requires at least 2 nodes"
                            {:type :walk-error :form form})))
        _ (doseq [n chain]
            (when (and (vector? n) (empty? n))
              (throw (ex-info "chain fan vector cannot be empty"
                              {:type :walk-error :form form})))
            (when (vector? n)
              (doseq [item n]
                (when-not (symbol? item)
                  (throw (ex-info "chain fan vector must contain symbols"
                                  {:type :walk-error :form form
                                   :offender item}))))))
        [[desc _] own-attrs leftover] (split-header trailer)
        _ (when (seq leftover)
            (throw (ex-info "=> trailing args must be label + attrs"
                            {:type :walk-error :form form :leftover leftover})))
        attrs   (merge (:wrap-attrs ctx) own-attrs)
        sources (fn [n] (if (vector? n) n [n]))]
    (vec
     (for [[a b] (partition 2 1 chain)
           from  (sources a)
           to    (sources b)]
       {:kind           :edge
        :from           from
        :to             to
        :bidirectional? false
        :description    desc
        :attrs          attrs}))))
```

- [ ] **Step 2.6: Run tests, confirm pass**

Run: `clojure -M:test -n drawl.walker-test`

Expected: all `=>` tests pass.

- [ ] **Step 2.7: Run the full test suite**

Run: `clojure -M:test`

Expected: all tests pass.

- [ ] **Step 2.8: Commit**

```bash
git add src/drawl/walker.cljc test/drawl/walker_test.cljc
git commit -m "feat(drawl): => chain with vector fan for edge generation"
```

---

## Task 3: `with-attrs` defaults supplier

**Files:**
- Modify: `src/drawl/walker.cljc`
- Test: `test/drawl/walker_test.cljc` (append)

`with-attrs` is a transparent ctx update — it merges its map into `:wrap-attrs` and walks its body, splicing the results. Defaults flow into every edge constructed in the subtree, including through element boundaries.

- [ ] **Step 3.1: Add failing tests for `with-attrs`**

Append to `test/drawl/walker_test.cljc`:

```clojure
;; --- with-attrs defaults supplier ---------------------------------------

(deftest with-attrs-flat-wraps-edges
  (let [edges (-> (c/parse "(diagram (container a) (container b)
                              (with-attrs {:tech \"gRPC\"} (-> a b)))")
                  :relationships)]
    (is (= 1 (count edges)))
    (is (= "gRPC" (-> edges first :attrs :tech)))))

(deftest with-attrs-edge-overrides-wrap
  (let [edges (-> (c/parse "(diagram (container a) (container b)
                              (with-attrs {:tech \"gRPC\"}
                                (-> a b :tech \"REST\")))")
                  :relationships)]
    (is (= "REST" (-> edges first :attrs :tech))
        "edge :tech wins over wrap :tech")))

(deftest with-attrs-nested-inner-wins
  (let [edges (-> (c/parse "(diagram (container a) (container b)
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
                                  (container api \"API\"
                                    (-> db \"reads\")))
                                (container db \"DB\"))")
                    :elements first :edges)]
      (is (= 1 (count edges)))
      (is (= 'api (:from (first edges))))
      (is (= "gRPC" (-> edges first :attrs :tech))))))

(deftest with-attrs-flows-into-chain
  (let [edges (-> (c/parse "(diagram (container a) (container b) (container c)
                              (with-attrs {:tech \"gRPC\"}
                                (=> a b c)))")
                  :relationships)]
    (is (= 2 (count edges)))
    (is (every? #(= "gRPC" (-> % :attrs :tech)) edges))))

(deftest with-attrs-rejects-non-map-first-arg
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"with-attrs requires a map"
        (c/parse "(diagram (container a) (container b)
                   (with-attrs :tech \"gRPC\" (-> a b)))"))))

(deftest with-attrs-empty-body-no-op
  (let [ir (c/parse "(diagram (container a)
                              (with-attrs {:tech \"gRPC\"}))")]
    (is (= [] (:relationships ir)))))
```

- [ ] **Step 3.2: Run tests, confirm fail**

Run: `clojure -M:test -n drawl.walker-test`

Expected: 7 failures, all with "Unknown form head: with-attrs".

- [ ] **Step 3.3: Add `walk-form` defmethod for `with-attrs`**

Append to `src/drawl/walker.cljc`:

```clojure
(defmethod walk-form 'with-attrs [form ctx]
  (let [[_ amap & body] form
        _ (when-not (map? amap)
            (throw (ex-info "with-attrs requires a map of defaults"
                            {:type :walk-error :form form})))
        child-ctx (update ctx :wrap-attrs #(merge % amap))
        walked    (mapv #(walk-form % child-ctx) body)]
    (vec
     (mapcat (fn [r] (if (vector? r) r [r])) walked))))
```

- [ ] **Step 3.4: Run tests, confirm pass**

Run: `clojure -M:test -n drawl.walker-test`

Expected: all `with-attrs` tests pass.

- [ ] **Step 3.5: Run the full test suite**

Run: `clojure -M:test`

Expected: all tests pass.

- [ ] **Step 3.6: Commit**

```bash
git add src/drawl/walker.cljc test/drawl/walker_test.cljc
git commit -m "feat(drawl): with-attrs defaults supplier for edge attrs"
```

---

## Task 4: End-to-end fixture

**Files:**
- Create: `test/fixtures/edges/01-mixed.drawl`
- Create: `test/fixtures/edges/01-mixed.edn`
- Modify: `test/drawl/compiler_test.cljc` (append)

A single fixture exercising all three new mechanisms (implicit-from, `=>` w/ fan, `with-attrs`) end-to-end. Confirms they compose and that the IR shape matches expectation.

- [ ] **Step 4.1: Create the fixture source**

Write to `test/fixtures/edges/01-mixed.drawl`:

```clojure
(diagram "Edge syntax fixture"
  (person customer "Customer")
  (system shop "Shop"
    (container web "Web")
    (container mobile "Mobile")
    (container api "API"
      (with-attrs {:tech "gRPC"}
        (-> auth "verify")
        (-> catalog "lookup")))
    (container auth "Auth")
    (container catalog "Catalog")
    (container db "DB" :role :database))
  (=> customer [web mobile] api db "via HTTPS" :tech "HTTPS"))
```

- [ ] **Step 4.2: Create the expected IR**

Write to `test/fixtures/edges/01-mixed.edn`:

```clojure
{:title         "Edge syntax fixture"
 :level         :container
 :relationships
 [{:kind :edge :from customer :to web    :bidirectional? false :description "via HTTPS" :attrs {:tech "HTTPS"}}
  {:kind :edge :from customer :to mobile :bidirectional? false :description "via HTTPS" :attrs {:tech "HTTPS"}}
  {:kind :edge :from web      :to api    :bidirectional? false :description "via HTTPS" :attrs {:tech "HTTPS"}}
  {:kind :edge :from mobile   :to api    :bidirectional? false :description "via HTTPS" :attrs {:tech "HTTPS"}}
  {:kind :edge :from api      :to db     :bidirectional? false :description "via HTTPS" :attrs {:tech "HTTPS"}}]}
```

Note: this asserts only `:title`, `:level`, and `:relationships` (diagram-level edges). The inner edges (from api → auth/catalog) live on the `api` container's `:edges` key and are not part of `:relationships`; they're asserted indirectly via the all-edges count in the test.

- [ ] **Step 4.3: Add failing test in `test/drawl/compiler_test.cljc`**

Append to `test/drawl/compiler_test.cljc`:

```clojure
(deftest edge-syntax-fixture
  (testing "implicit-from + => + with-attrs compose end-to-end"
    (let [src      (slurp "test/fixtures/edges/01-mixed.drawl")
          expected (clojure.edn/read-string
                     (slurp "test/fixtures/edges/01-mixed.edn"))
          actual   (c/parse src)]
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
```

Top of file: confirm `clojure.edn` is available; if not, add `[clojure.edn]` to the `:require` and re-run.

- [ ] **Step 4.4: Run test, confirm fail**

Run: `clojure -M:test -n drawl.compiler-test`

Expected: fails — likely with file-not-found if the test runner's working directory differs from project root, or assertion failure.

- [ ] **Step 4.5: Adjust paths if needed**

If the slurp fails, check that the test runner's CWD is the project root (Cognitect's runner runs from where you invoke it). Adjust paths to be relative to project root or convert to `io/resource` (note: `test/` isn't on the resource path; using project-relative `slurp` is simpler).

- [ ] **Step 4.6: Run full test suite**

Run: `clojure -M:test`

Expected: all tests pass.

- [ ] **Step 4.7: Commit**

```bash
git add test/fixtures/edges test/drawl/compiler_test.cljc
git commit -m "test(drawl): end-to-end fixture for new edge syntax"
```

---

## Task 5: Update SPEC.org and GRAMMAR.org

**Files:**
- Modify: `SPEC.org` §2.4 (Relationships) and §3 (compile pipeline note if needed)
- Modify: `GRAMMAR.org` Relationships section

Doc must match implementation. Both files are org-mode.

- [ ] **Step 5.1: Update `GRAMMAR.org` Relationships table**

Open `GRAMMAR.org`. Replace the existing Relationships table (around lines 107–117) with:

```org
* Relationships

** Two-symbol explicit form

| Form                                | Direction       |
|-------------------------------------+-----------------|
| =(-> a b)=                          | a → b           |
| =(-> a b "label")=                  | a → b, labelled |
| =(-> a b "label" :tech "HTTPS")=    | a → b, with tech |
| =(<-> a b "label")=                 | a ↔ b           |

Endpoints (~a~, ~b~) must resolve to declared element IDs. Unresolved
endpoints are a hard error.

** Implicit-=from= inside element bodies

When =(-> ...)= or =(<-> ...)= appears inside an element's body
(=person= / =system= / =container= / =component=), a single-symbol
arity means "from this element":

#+begin_src clojure
(container api "API"
  (-> db "reads")               ; from = api, implicit
  (-> stripe "charges" :tech "REST")
  (-> spa api "delivers"))      ; two symbols = explicit, still legal
#+end_src

Outside an element body, the single-symbol form is a hard error.

** =(=> a b c …)= — chain with vector fan

Each adjacent pair in the chain produces an edge. A vector at any
position is a parallel set; adjacent vectors yield the full
cross-product. A trailing string + keyword/value pairs apply to every
edge in the chain.

#+begin_src clojure
(=> a b c d)                              ; a→b, b→c, c→d
(=> a [b c] d)                            ; a→b, a→c, b→d, c→d
(=> [a b] [c d])                          ; 4 edges, cross-product
(=> customer api "via HTTPS" :tech "HTTPS")
#+end_src

Chain must have at least two nodes; fan vectors must be non-empty.
Per-edge labels are not expressible — use =(-> ...)= for those.

** =(with-attrs {…} body…)= — defaults supplier

Wraps any body. Defaults flow into every edge constructed in the
subtree, no matter how deeply nested. Edge attrs win on conflict;
inner =with-attrs= overrides outer.

#+begin_src clojure
(with-attrs {:tech "gRPC"}
  (-> api auth)
  (-> api billing)
  (=> customer [web mobile] api))
#+end_src
```

- [ ] **Step 5.2: Update `SPEC.org` §2.4 (Relationships)**

Find the Relationships subsection in `SPEC.org` and update it to mirror the GRAMMAR.org changes above, with rationale paragraphs added (cross-reference `docs/specs/2026-05-13-edge-syntax-design.md` for the design discussion).

Add a short paragraph after the Relationships subsection noting that the walker tracks `:current-element` and `:wrap-attrs` in ctx, and that `walk-children` flattens vector returns.

- [ ] **Step 5.3: Verify doc + spec agreement**

Re-read the design spec at `docs/specs/2026-05-13-edge-syntax-design.md` and confirm every rule it states is reflected in the updated GRAMMAR.org and SPEC.org. Flag any discrepancy.

- [ ] **Step 5.4: Commit**

```bash
git add SPEC.org GRAMMAR.org
git commit -m "docs(drawl): document new edge surface in SPEC and GRAMMAR"
```

---

## Task 6: Refresh examples + add journey example

**Files:**
- Modify: `examples/03-bank-containers.drawl`
- Modify: `examples/05-macros.drawl`
- Modify: `examples/06-nested-systems.drawl`
- Create: `examples/08-journey.drawl`

Living docs. Rewrite existing examples to use the new forms where they win; add one new example demonstrating `=>` end-to-end. Each rewritten file must still compile to a valid IR.

- [ ] **Step 6.1: Rewrite `examples/03-bank-containers.drawl`**

Overwrite with:

```clojure
(diagram "Internet Banking — Containers"
  (person customer "Banking Customer")
  (system bank "Internet Banking System"
    (container webapp "Web App" (-> spa "Delivers SPA"))
    (container spa    "SPA"     (-> api "API calls"))
    (container api    "API"
      (-> db "Reads/writes")
      (-> mainframe "Calls")))
  (system mainframe "Mainframe Banking")
  (-> customer webapp "Visits bigbank.com"))
```

- [ ] **Step 6.2: Rewrite `examples/05-macros.drawl`**

Overwrite with:

```clojure
(diagram "Storefront — macros + roles + external"
  (person customer "Customer")

  (system store "Storefront"
    (webapp web "Web App" (-> api "renders via"))
    (rest-api api "Public API"
      (-> db "reads/writes" :tech "JDBC")
      (<-> cache "session sync"))
    (postgres-db db "Postgres")
    (redis-cache cache "Cache"))

  (system stripe "Stripe" :external true)

  (-> customer web "browses" :tech "HTTPS")
  (-> api stripe "charges" :tech "REST" :style :dashed))
```

- [ ] **Step 6.3: Rewrite `examples/06-nested-systems.drawl`**

Overwrite with:

```clojure
(diagram "Acme platform — system landscape"
  (person customer "Customer")

  (system platform "Acme Platform"
    (system storefront "Storefront"
      (webapp web "Web" (-> api "calls"))
      (rest-api api "API" (-> db "JDBC"))
      (postgres-db db "Postgres"))
    (system fulfilment "Fulfilment"
      (rest-api orders "Orders" (-> orders-db "JDBC"))
      (postgres-db orders-db "Orders DB"))
    (-> api orders "places order"))

  (system stripe "Stripe" :external true)

  (-> customer web "browses")
  (-> api stripe "charges" :tech "REST" :style :dashed))
```

- [ ] **Step 6.4: Create `examples/08-journey.drawl`**

Write to `examples/08-journey.drawl`:

```clojure
(diagram "Order journey"
  (person customer "Customer")

  (container web    "Web")
  (container mobile "Mobile")
  (container api    "API"
    (with-attrs {:tech "gRPC"}
      (-> auth     "verify")
      (-> catalog  "lookup")
      (-> payment  "charge")
      (-> shipping "ship")))
  (container auth     "Auth Svc")
  (container catalog  "Catalog Svc")
  (container payment  "Payment Svc")
  (container shipping "Shipping Svc")
  (container db       "Postgres" :role :database)
  (container cache    "Redis"    :role :database)

  (=> customer [web mobile] api [db cache] "via HTTPS" :tech "HTTPS"))
```

- [ ] **Step 6.5: Verify every example still compiles**

Run a small REPL session or test snippet that compiles each example. If a `bin/compile-examples` script doesn't exist, run from REPL:

```bash
clojure -M -e "(require '[drawl.compiler :as c])
               (doseq [f (sort (.list (java.io.File. \"examples\")))]
                 (println f \"->\"
                          (try (let [src (slurp (str \"examples/\" f))]
                                 (c/emit (c/parse src) :dot)
                                 \"OK\")
                            (catch Exception e (.getMessage e)))))"
```

Expected: every file ends with `OK`.

- [ ] **Step 6.6: Commit**

```bash
git add examples/
git commit -m "docs(drawl): refresh examples with new edge syntax; add 08-journey"
```

---

## Self-review checklist

Run through each item, fix inline if any issue.

**Spec coverage** — every section of `docs/specs/2026-05-13-edge-syntax-design.md`:

- Implicit-from on `(->)` / `(<->)` → Task 1 ✓
- `=>` chain with vector fan → Task 2 ✓
- `with-attrs` defaults supplier → Task 3 ✓
- Walker ctx changes (`:current-element`, `:wrap-attrs`) → Task 1 + Task 3 ✓
- `walk-children` mapcat flatten → Task 2 ✓
- Walker unit tests for all error cases → Tasks 1–3 ✓
- End-to-end fixture exercising all three forms → Task 4 ✓
- SPEC.org + GRAMMAR.org updates → Task 5 ✓
- Examples refresh + new journey example → Task 6 ✓

**Placeholder scan** — no TBD / TODO / "implement later" / "add appropriate error handling" patterns. All test bodies have concrete assertions. All code-step blocks contain the actual code to paste.

**Type consistency** — IR shape used in all tests:

```
{:kind :edge :from <sym> :to <sym> :bidirectional? <bool> :description <str-or-nil> :attrs <map>}
```

This matches the existing IR (verified against `src/drawl/walker.cljc` lines 96–101 before modification). `c/parse` returns the same structure with `:elements` / `:relationships` / `:title` / `:level` keys (verified against `src/drawl/compiler.cljc`).

`walker/walk-form` is called both with maps in ctx (`{:wrap-attrs {...}}`) and via `c/parse`. Both work because the existing `c/parse` already passes a ctx with `:macros`; we only add new keys.

**Cross-platform note** — All `.cljc` files. JVM tests run via `clojure -M:test`. The CLJS portability test referenced in SPEC §7.3 is not yet wired (per CLAUDE.md). Plan covers JVM only; CLJS portability is followup.

---

## Done criteria

- All 6 tasks committed.
- `clojure -M:test` is green.
- Every file in `examples/` compiles via `drawl.compiler/compile … :dot`.
- `docs/specs/2026-05-13-edge-syntax-design.md` is unchanged (it is the spec, not an artifact to update).
- `SPEC.org` and `GRAMMAR.org` describe the new surface accurately.
