# Edge syntax — design

**Date:** 2026-05-13
**Status:** Proposed
**Replaces:** none (additive to current SPEC §2.4 relationships)

## Problem

The current edge surface (SPEC.org §2.4, GRAMMAR.org §Relationships) requires one form per edge:

```clojure
(-> spa api "API calls")
(-> api db  "Reads/writes")
(-> api auth     "verify"  :tech "gRPC")
(-> api catalog  "lookup"  :tech "gRPC")
(-> api payment  "charge"  :tech "gRPC")
(-> api shipping "ship"    :tech "gRPC")
;; …
```

This produces line bloat in three recurring shapes:

1. **Chains / pipelines** — `a → b → c → d`, one form per hop.
2. **Fan-out / fan-in** — one source talking to many sinks (or vice versa), one form per edge with mostly-duplicate label/attrs.
3. **Shared transport attrs** — every edge under a section uses the same `:tech` / `:style`, repeated verbatim.

The bloat is worst in chatty microservice topologies and customer-journey diagrams. Real-world `.drawl` files spend more lines on relationships than on element declarations.

## Goals

- Significantly reduce edge line count on chain-heavy, fan-heavy, and shared-attr diagrams. Realistic wins: ~30–40% on label-rich diagrams (every edge needs a description) and up to ~80% on label-free, fan-heavy ones where one `=>` form replaces N `(->)` forms.
- Keep the existing IR shape (`{:from :to :description :attrs :bidirectional?}`) untouched so emitters require zero changes.
- Add no new file extensions, runtime dependencies, or emitter targets.
- Preserve `(->)` and `(<->)` two-symbol semantics as today; new affordances are additive.

## Non-goals

- Not redesigning element nesting. The `:in parent-id` flat-declaration idea was considered and rejected for v0.2 (see §Rejected alternatives).
- Not adding edge IR fields. Description and attrs are already enough.
- Not introducing a separate `:calls` element attr (considered and cut — see §Rejected alternatives).

## Design

Three additions:

1. **Implicit-`from` on `(->)` and `(<->)` inside element bodies** — defaulting rule, not a new form.
2. **`=>` chain with vector-fan** — new head; edge-set generator.
3. **`with-attrs`** — defaults supplier; lexically scopes attr defaults onto every edge in its subtree.

All three desugar to the existing edge IR. No IR change.

### 1. Implicit-`from` on `(->)` and `(<->)`

When an edge form appears inside an element body (`person` / `system` / `container` / `component`), the walker tracks the enclosing element id in ctx (`:current-element`). Disambiguation by arity of leading symbols:

| Form shape | Behavior |
|---|---|
| `(-> a b ...)` — 2 leading symbols | Explicit. `from=a`, `to=b`. (Current behavior, unchanged.) |
| `(-> a ...)` — 1 leading symbol, inside element body | Implicit. `from = :current-element`, `to = a`. |
| `(-> a ...)` — 1 leading symbol, at diagram body | Walk-error: edge requires two endpoints at top level. |
| `(-> ...)` — 0 leading symbols | Walk-error. |

The walker peeks at the 2nd arg's type. If it is a symbol, treat as explicit (current `walk-edge` path). Otherwise, if `:current-element` is set in ctx, treat as implicit-from. `<->` follows the same rule.

```clojure
(container api "API" :tech "Phoenix"
  (-> db "reads")                 ; api → db
  (-> stripe "charges" :tech "REST") ; api → stripe
  (-> spa api "delivers"))        ; spa → api (explicit)
```

### 2. `=>` — chain with vector-fan

Single new head. Header rule: leading args that are symbols or vectors of symbols form the chain; remaining args (string + kw/val pairs) follow split-header as the shared description + shared attrs.

```clojure
(=> a b c d)                                 ; a→b, b→c, c→d
(=> a [b c] d)                               ; a→b, a→c, b→d, c→d
(=> [a b] [c d])                             ; cross-product: 4 edges
(=> customer api "via HTTPS" :tech "HTTPS")  ; shared label + attrs
```

Rules:

- Chain must have ≥2 nodes.
- A vector contributes all its items as the set at that position. Adjacent positions form a full cross-product of edges (source-set × target-set).
- Shared label/attrs apply to **every** edge produced.
- Per-edge labels are not expressible; use multiple `(->)` instead.
- `=>` is always explicit. Implicit-from is **not** extended to `=>` (see §Rejected alternatives).

### 3. `with-attrs` — defaults supplier

```clojure
(with-attrs {:tech "gRPC"}
  (-> api auth)
  (-> api billing)
  (=> customer [web mobile] api))
```

Walker dispatch on `'with-attrs`. First arg must be a map literal. The walker updates `ctx :wrap-attrs` by merging the supplied map over any existing wrap-attrs from an enclosing `with-attrs`, then walks the body. Every edge constructed in the subtree merges the active `:wrap-attrs` UNDER its own attrs (edge attrs win on conflict).

Properties:

- **Transparent**: `with-attrs` adds nothing to ctx other than `:wrap-attrs`. It does not restrict what its body may contain. Elements, edges, macros — anything legal in the enclosing scope is legal here.
- **Lexical**: defaults flow into edges no matter how deeply nested, including across element boundaries.
- **Nestable**: inner `with-attrs` merges over outer; inner wins on key conflict.
- **Defaults only**: edge attrs override wrap-attrs. `with-attrs` is not an override mechanism.

Co-locating with edges inside an element body works as expected — `with-attrs` does not clear `:current-element`:

```clojure
(container api "API" :tech "Phoenix"
  (with-attrs {:tech "gRPC"}
    (-> auth     "verify")    ; api → auth, :tech "gRPC"
    (-> catalog  "lookup")
    (-> payment  "charge")))
```

The container's own `:tech "Phoenix"` is on the container, not on the edges — different data; no collision.

## Walker / IR mapping

IR shape is unchanged. Walker changes:

### `walk-form` may return a vector

Today every form returns a single IR node. With `=>` (N edges) and `with-attrs` (M edges), one form produces many edges. `walk-children` becomes mapcat-style: a vector return value is spliced into the result list.

### `ctx` grows two keys

- `:current-element` — symbol id of the nearest enclosing element. Set in `walk-element` immediately before descending into children.
- `:wrap-attrs` — map of default attrs from enclosing `with-attrs`. Read by every edge-constructing path (`walk-edge`, `walk-chain`).

### `walk-edge` (modified)

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
        [[desc _] own-attrs leftover] (split-header rest-args)
        attrs (merge (:wrap-attrs ctx) own-attrs)]
    (when (seq leftover)
      (throw (ex-info "edge does not accept children"
                      {:type :walk-error :form form :leftover leftover})))
    {:kind           :edge
     :from           from
     :to             to
     :bidirectional? bidirectional?
     :description    desc
     :attrs          attrs}))
```

### `walk-element` (modified)

Pass `:current-element id` in ctx when descending into children:

```clojure
(let [child-ctx (assoc ctx :current-element id)
      [child-els edges] (walk-children children child-ctx)]
  ...)
```

`:calls` is **not** introduced. Element attrs are pure data; the walker does not expand any of them into edges.

### `walk-chain` (new — defmethod on `'=>`)

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
                              {:type :walk-error :form form}))))
        [[desc _] own-attrs leftover] (split-header trailer)
        _ (when (seq leftover)
            (throw (ex-info "=> trailing args must be label + attrs"
                            {:type :walk-error :form form :leftover leftover})))
        attrs (merge (:wrap-attrs ctx) own-attrs)
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

### `walk-with-attrs` (new — defmethod on `'with-attrs`)

```clojure
(defmethod walk-form 'with-attrs [form ctx]
  (let [[_ amap & body] form
        _ (when-not (map? amap)
            (throw (ex-info "with-attrs requires a map of defaults"
                            {:type :walk-error :form form})))
        child-ctx (update ctx :wrap-attrs #(merge % amap))
        walked    (mapv #(walk-form % child-ctx) body)]
    ;; flatten — a child may be a single node (edge or element) or a vector
    ;; of nodes (chain expansion or nested with-attrs).
    (vec
     (mapcat (fn [r] (if (vector? r) r [r])) walked))))
```

### `walk-children` (modified)

```clojure
(defn- walk-children [children ctx]
  (let [walked    (mapv #(walk-form % ctx) children)
        flattened (mapcat (fn [r] (if (vector? r) r [r])) walked)
        {elements true edges false}
        (group-by #(not= :edge (:kind %)) flattened)]
    [(vec elements) (vec edges)]))
```

## Examples

### `examples/03-bank-containers.drawl`

Before (16 lines):

```clojure
(diagram "Internet Banking — Containers"
  (person customer "Banking Customer")
  (system bank "Internet Banking System"
    (container webapp "Web App")
    (container spa "SPA")
    (container api "API")
    (container db "Database")
    (-> spa api "API calls")
    (-> api db "Reads/writes"))
  (system mainframe "Mainframe Banking")
  (-> customer webapp "Visits bigbank.com")
  (-> webapp spa "Delivers SPA")
  (-> api mainframe "Calls"))
```

After (10 lines):

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

~37% line reduction. The win comes from (a) co-locating each edge with its `from` element via implicit-from and (b) dropping the separate relationships section.

### Chatty microservices — labeled edges, shared tech

Before (14 lines):

```clojure
(diagram "Order Service"
  (container api "Orders API")
  (container auth "Auth Svc")
  (container catalog "Catalog Svc")
  (container payment "Payment Svc")
  (container shipping "Shipping Svc")
  (container notify "Notify Svc")
  (container audit "Audit Svc")
  (-> api auth     "verify"  :tech "gRPC")
  (-> api catalog  "lookup"  :tech "gRPC")
  (-> api payment  "charge"  :tech "gRPC")
  (-> api shipping "ship"    :tech "gRPC")
  (-> api notify   "notify"  :tech "gRPC")
  (-> api audit    "log"     :tech "gRPC"))
```

After (14 lines, same count but ~25% fewer characters per edge line, with the shared `:tech` factored once):

```clojure
(diagram "Order Service"
  (container api "Orders API"
    (with-attrs {:tech "gRPC"}
      (-> auth     "verify")
      (-> catalog  "lookup")
      (-> payment  "charge")
      (-> shipping "ship")
      (-> notify   "notify")
      (-> audit    "log")))
  (container auth     "Auth Svc")
  (container catalog  "Catalog Svc")
  (container payment  "Payment Svc")
  (container shipping "Shipping Svc")
  (container notify   "Notify Svc")
  (container audit    "Audit Svc"))
```

Same line count because the `with-attrs` wrapper costs the line you save on the implicit `from`. The real wins are (a) one place to change `:tech` and (b) edges visibly belong to `api`. If shared attrs grow (e.g. `:tech "gRPC" :style :dashed :timeout 5000`), the savings compound — the wrapper line carries N attrs at no extra per-edge cost.

### Chatty microservices — no per-edge labels

When the diagram doesn't need per-edge descriptions, `=>` collapses the fan into one form:

```clojure
(diagram "Order Service"
  (container api "Orders API")
  (container auth     "Auth Svc")
  (container catalog  "Catalog Svc")
  (container payment  "Payment Svc")
  (container shipping "Shipping Svc")
  (container notify   "Notify Svc")
  (container audit    "Audit Svc")
  (with-attrs {:tech "gRPC"}
    (=> api [auth catalog payment shipping notify audit])))
```

10 lines. Six edges in one chain form. This is the case where `=>` shines.

### Journey-style with `=>`

```clojure
(diagram "Order flow"
  (person customer "Customer")
  (container web "Web")
  (container mobile "Mobile")
  (container api "API")
  (container db "Postgres" :role :database)
  (container cache "Redis" :role :database)
  (=> customer [web mobile] api [db cache]))
```

One line, 6 edges: customer→web, customer→mobile, web→api, mobile→api, api→db, api→cache.

## Errors

### `(->)` / `(<->)`

| Case | Behavior |
|---|---|
| Diagram body, 1 leading symbol | walk-error: `"edge requires two endpoints at top level"` |
| Diagram body, 0 symbols | walk-error |
| Self-loop (`(-> a a)`) | Allowed |
| Unknown endpoint id | walk-error from `drawl.ir/validate` (existing) |
| 2 strings in header | First is description, second ignored (mirrors current behavior) |

### `=>`

| Case | Behavior |
|---|---|
| `(=> a)` | walk-error: `"chain requires at least 2 nodes"` |
| `(=> a [] b)` | walk-error: `"chain fan vector cannot be empty"` |
| Non-symbol in fan vector | walk-error |
| Leftover args after trailer | walk-error: `"=> trailing args must be label + attrs"` |
| Implicit-from inside element body | Not supported. `=>` always explicit. |

### `with-attrs`

| Case | Behavior |
|---|---|
| First arg not a map | walk-error: `"with-attrs requires a map of defaults"` |
| Empty body | Accept, no-op |
| Body contains element | Allowed; transparent to nested elements |
| Body contains non-edge non-element form with unknown head | walk-error (delegates to default `walk-form` handling) |
| Nested `with-attrs` | Inner merges over outer |
| Edge attr vs wrap-attr conflict | Edge attr wins (wrap-attrs are defaults) |

### Macros

Built-in (`webapp`, `postgres-db`, …) and user `defmacro` expansions are walked recursively. `:current-element` and `:wrap-attrs` apply based on ctx at the time the expanded edge is walked, not where the macro was defined. No special handling.

### IR / emitters

No changes. Validation (`drawl.ir/validate`), dot emitter, excalidraw emitter unchanged. New surfaces desugar before emit sees them.

## Test plan

### Walker unit tests (`test/drawl/walker_test.cljc`)

**Implicit-from**
- Element body, 1 symbol → edge from parent
- Element body, 2 symbols → explicit edge
- Element body, `<->` 1 symbol → bidirectional implicit
- Diagram body, 1 symbol → walk-error
- Diagram body, 0 symbols → walk-error

**`=>`**
- 2 nodes → 1 edge
- 3 nodes → 2 edges
- Trailing label + attr → applied to every edge
- `(=> a [b c] d)` → 4 edges
- `(=> [a b] [c d])` → 4 cross-product edges
- `(=> a)` → walk-error
- `(=> a [] b)` → walk-error
- Non-symbol in fan vector → walk-error

**`with-attrs`**
- Flat `(with-attrs {} (-> a b))` → wrap-attrs merged
- Edge attr override (edge attr wins)
- Nested `with-attrs` (inner overrides outer)
- Inside element body, applies to implicit-from edges
- Around an element, applies to edges nested inside it
- Around `=>`, applies to every produced edge
- Non-map first arg → walk-error

### End-to-end fixture

Add `test/fixtures/edges/` with:

- One representative `.drawl` exercising all three new forms.
- Expected IR as an `.edn` next to it.
- Loaded through JVM (`clojure.edn`) and CLJS (`cljs.reader`) per SPEC §7.3, asserting identical IR.

### Emitter snapshot tests

Existing emitter tests stay green unchanged. Add one new fixture-driven test per emitter (dot, excalidraw) proving the new surfaces produce the same output as the explicit-only equivalent.

### Examples refresh

Rewrite `examples/03-bank-containers.drawl`, `examples/05-macros.drawl`, and `examples/06-nested-systems.drawl` using the new forms in the same commit. Serves as living docs and as a sanity check that the surface actually saves lines in real diagrams. Add one new example (e.g. `examples/08-journey.drawl`) showcasing `=>` end-to-end.

## Documentation

Update `SPEC.org` §2 (relationships) and `GRAMMAR.org` §Relationships in the same commit as the implementation. The canonical spec must agree with the implementation at all times.

## Rejected alternatives

### `:calls` element attribute

Considered: `(container api "API" :calls [db [cache "hot" :tech "Redis"]])` as a terse outgoing-edges attribute.

Rejected because it introduces:
1. A keyword attribute that the walker special-cases into edges (the element form does double duty: declare AND generate edges).
2. A nested mini-grammar for `:calls` entries (`[target label? attrs*]`), distinct from the main form-header rule.
3. A second surface for "edge from this element" — duplicating body `(->)` with implicit-from.

Body `(->)` with implicit-from reuses the existing form, the existing parser, and the existing header rule, costing only an argument-defaulting rule. Fewer concepts, same expressiveness.

### `:in parent-id` flat element declaration

Considered: declare elements at diagram level with `:in bank-id` instead of lexical nesting.

Rejected for v0.2. Containment is the most fundamental structural relationship in C4, and Lisp's lexical nesting already encodes it cleanly. Adding a second containment mechanism doubles the mental model. Revisit only if a real diagram makes nested declarations painful in practice.

### Implicit-from on `=>`

Considered: inside `(container api ...)`, `(=> auth catalog)` could mean `api → auth → catalog`.

Rejected. `=>` already requires ≥2 nodes, so arity-disambiguation does not apply. Adding "prepend parent if inside element body" would be a fourth distinct rule for one form. `=>` stays always-explicit. If you want to chain from the enclosing element, write `(=> api auth catalog)`.

### Replacing `(->)` rather than extending it

Considered: drop `(->)` in favor of a single new edges form.

Rejected. `(->)` is already in every example file, in the README, on drawl.jordangarrison.dev, and in user mental models. Breaking it earns nothing. The extension is additive: every existing `.drawl` continues to compile unchanged.

### `via` instead of `with-attrs`

Considered as a shorter, more evocative name (transport metaphor for `:tech`).

Rejected. `with-attrs` is honest: it supplies attribute defaults. `via` overcommits to the transport reading and confuses cases where the defaults are not transport-related (e.g. `:style :dashed`).

## Future work

- `(<=>)` bidirectional chain — only if real diagrams want it. Skipped here because the chain → cross-product semantics work out cleanly for one direction and double the surface to add the other.
- Re-evaluate `:in parent-id` and `:calls` after a few months of using the lean core. The skip is intentional, not permanent.
