# drawl

*A Lisp for diagrams. C4-aligned, browser-native, CLI-friendly.*

---

## 0. About the name

`drawl` reads two ways. As a casual user reads it, it's the slow, considered cadence you'd give to careful work — diagrams take their time. For Lispers, it's DRAW with the L of *Lisp* sliding in on the shared letter, with a faint nod to Lisp's etymological origin as a "lithp" — the speech-impediment joke from the language's earliest days.

Optional backronym, for when the project ever needs one: **D**iagrams **R**endered **A**s **W**alked **L**ists — a literal description of what the compiler does (parse forms, walk the IR, emit diagrams).

File extension is `.drawl`. CLI command is `drawl`. Tagline: *"Diagrams, with a lisp."*

---

## 1. Goals & Non-Goals

### Goals

- A minimal Lisp surface for declaring diagrams. Forms nest, edges reference elements by name, attributes are keyword-value pairs.
- C4 model alignment as a first-class concern: `person`, `system`, `container`, `component` are built-in element types.
- Multi-target compilation from a single codebase: browser SPA, Babashka CLI, JVM library.
- Pluggable backends. Render to mermaid C4 or graphviz dot today; PlantUML, Excalidraw, or Structurizr DSL later if useful.
- Live editing in the browser, sub-150ms from keystroke to rendered SVG.

### Non-Goals

- Replacing Structurizr for serious enterprise C4 authoring. drawl is a faster, more hackable alternative for personal and small-team use.
- General-purpose graph layout. Layout is delegated to backends (graphviz, mermaid).
- Code-level (C4 L4) diagrams. Out of scope for v1.
- Rich text formatting in labels. Plain strings only.

---

## 2. Language Reference

### 2.1 Lexical Structure

Source is parsed by Clojure's reader — `clojure.edn/read-string` on JVM/Babashka, `cljs.reader/read-string` in the browser. The full source is wrapped in an implicit `(do ...)` form so multiple top-level forms (e.g. macro definitions plus a diagram) are legal.

| Token kind | Examples | Notes |
|------------|----------|-------|
| Symbol | `web`, `customer`, `order-service` | Element names, edge endpoints |
| String | `"Web Application"` | Titles, descriptions, label text |
| Keyword | `:tech`, `:database`, `:external` | Attribute keys, enum values |
| Number | `42`, `0.5` | Used in some attribute values |
| List | `(component web :tech "Phoenix")` | Forms |
| Comment | `; comment to EOL` | Standard Lisp line comments |

### 2.2 Form Grammar

```
form        := (head head-args... attr-pairs... children...)
head        := symbol
head-args   := positional arguments specific to the head
attr-pairs  := keyword value, repeated zero or more times
children    := nested forms
```

**Attribute parsing rule**: positional arguments come first, keyword-value pairs come immediately after and are parsed greedily until the first non-keyword form, and everything from that point is treated as children. Single-pass, no backtracking.

### 2.3 Forms

#### `diagram`

```lisp
(diagram TITLE? :attrs... ELEMENTS...)
```

The top-level form. Required. Exactly one per source after macro expansion.

- `TITLE` (optional string): displayed as the diagram title.
- Common attributes: `:notation` (`:c4` default, `:plain`).
- `ELEMENTS`: any number of `person`, `system`, edges at the top level, or macro-expanded forms.

#### `person`

```lisp
(person ID TITLE? DESCRIPTION? :attrs...)
```

A C4 actor — renders as a stick figure or person icon depending on backend.

- `ID` (symbol, required): used to reference this person in edges.
- `TITLE` (optional string): display name.
- `DESCRIPTION` (optional string): role description, second positional string.
- Attributes: `:external` (boolean).

#### `system`

```lisp
(system ID TITLE? DESCRIPTION? :attrs... CHILDREN...)
```

A C4 software system. Renders as a leaf in context views, or as a boundary containing containers in container views.

- `ID` (symbol, required).
- `TITLE`, `DESCRIPTION` (optional strings).
- Attributes: `:external` (boolean).
- `CHILDREN`: zero or more `container` forms or edges scoped to this system.

#### `container`

```lisp
(container ID TITLE? DESCRIPTION? :attrs... CHILDREN...)
```

A C4 container. Renders as a leaf in container views, or as a boundary in component views.

- `ID` (symbol, required).
- `TITLE`, `DESCRIPTION` (optional strings).
- Attributes: `:tech` (string), `:role` (`:database`, `:queue`, `:browser`, `:lambda`, `:cache`), `:external` (boolean).
- `CHILDREN`: zero or more `component` forms or edges scoped to this container.

#### `component`

```lisp
(component ID TITLE? DESCRIPTION? :attrs...)
```

A C4 component. Always a leaf.

- `ID` (symbol, required).
- `TITLE`, `DESCRIPTION` (optional strings).
- Attributes: `:tech`, `:role`, `:external`.

#### `->` (edge)

```lisp
(-> FROM TO DESCRIPTION? :attrs...)
```

A directed relationship.

- `FROM`, `TO` (symbols, required): names of any defined element.
- `DESCRIPTION` (optional string): label on the edge.
- Attributes: `:tech` (string, often shown as a smaller label), `:style` (`:solid` default, `:dashed`, `:dotted`), `:label` (alternate to positional description).

#### `<->` (bidirectional edge)

```lisp
(<-> A B DESCRIPTION? :attrs...)
```

Convenience for two-way relationships. Renders as the backend's bidirectional edge primitive (`Rel_Bi` in mermaid C4, `dir=both` in dot).

### 2.4 Attribute Reference

| Attribute | Applies to | Type | Notes |
|-----------|-----------|------|-------|
| `:tech` | container, component, edge | string | Technology label |
| `:role` | container, component | keyword | `:database`, `:queue`, `:cache`, `:browser`, `:lambda` |
| `:external` | person, system, container | boolean | Renders as out-of-scope |
| `:description` | any element | string | Alternative to positional description |
| `:style` | edge | keyword | `:solid` (default), `:dashed`, `:dotted` |
| `:label` | edge | string | Alternative to positional description |
| `:notation` | diagram | keyword | `:c4` (default), `:plain` |

Unknown attributes are accepted by the parser and walker, and ignored by the emitter (with optional warning). This lets user macros introduce custom attributes without changing the core.

### 2.5 References & Name Resolution

- Bare symbols in `from`/`to` positions of edges are references.
- References resolve via a flat global namespace. Every `person`/`system`/`container`/`component` defines a name in one table.
- Defining the same name twice is a hard error.
- Referring to an undefined name is a hard error.
- Containers and systems ARE referenceable. An edge between two systems renders as a boundary-to-boundary edge — handled per backend (mermaid C4 supports natively, graphviz uses cluster `lhead`/`ltail`).
- No lexical scoping. Qualified references (e.g. `system/component`) are reserved syntax for future use, not implemented in v1.

### 2.6 Built-in Macros

Shorthand macros expand to canonical forms during walking. v1 ships:

| Macro | Expands to |
|-------|-----------|
| `(phoenix-app NAME OPTS...)` | `(container NAME :tech "Phoenix" OPTS...)` |
| `(postgres-db NAME OPTS...)` | `(container NAME :role :database :tech "Postgres" OPTS...)` |
| `(redis-cache NAME OPTS...)` | `(container NAME :role :cache :tech "Redis" OPTS...)` |
| `(rest-api NAME OPTS...)` | `(container NAME :tech "REST API" OPTS...)` |

User-defined `(defmacro ...)` forms in source are NOT supported in v1. They would require self-hosted CLJS (~1MB bundle hit). Built-in shorthand can be extended by adding additional `walk-form` defmethods to the drawl core itself.

### 2.7 Example

```lisp
(diagram "Internet Banking — Containers"
  (person customer "Personal Banking Customer")
  
  (system bank "Internet Banking System"
    (container webapp "Web App" :tech "Spring MVC")
    (container spa "SPA" :tech "Angular")
    (container mobile "Mobile App" :tech "Xamarin")
    (container api "API" :tech "Spring MVC")
    (postgres-db db "Database"))
  
  (system mainframe "Mainframe Banking" :external true)
  (system email "E-mail System" :external true)
  
  (-> customer webapp "Visits bigbank.com using" :tech "HTTPS")
  (-> webapp spa "Delivers to the browser")
  (-> spa api "API calls" :tech "JSON/HTTPS")
  (-> mobile api "API calls" :tech "JSON/HTTPS")
  (-> api db "Reads/writes" :tech "JDBC")
  (-> api mainframe "Calls" :tech "XML/HTTPS")
  (-> api email "Sends mail"))
```

---

## 3. Semantic Model

### 3.1 Intermediate Representation

The walker produces a single map:

```clojure
{:title         string?
 :level         #{:context :container :component}
 :attrs         map?
 :elements      [Element]
 :relationships [Edge]}

Element :=
  {:kind        #{:person :system :container :component}
   :id          symbol?
   :title       string?
   :description string?
   :attrs       map?
   :children    [Element]    ; only for :system, :container
   :edges       [Edge]}      ; edges scoped to this element

Edge :=
  {:kind            :edge
   :from            symbol?
   :to              symbol?
   :bidirectional?  boolean?
   :description     string?
   :attrs           map?}
```

Every element has the same envelope; varying only in `:kind`. Containment lives in `:children`, which lets one recursive walker handle systems-with-containers and containers-with-components uniformly.

### 3.2 Level Inference

Level is computed in one tree walk by taking the deepest `:kind` encountered:

- Any `:component` element → `:level :component`
- Else any `:container` element → `:level :container`
- Else → `:level :context`

The level controls backend rendering: which mermaid C4 header to emit (`C4Context` / `C4Container` / `C4Component`), which boundary types are valid.

The same source can be filtered to lower-level views via `(drawl.ir/at-level :context ir)`, which strips containers and components and leaves only systems and people. This means one source can serve multiple zoom levels.

### 3.3 Validation

The walker enforces:

- Exactly one top-level `(diagram ...)` form (after macro expansion).
- All form heads are valid: `person`, `system`, `container`, `component`, `->`, `<->`, or a registered macro.
- Element nesting is sound: components only inside containers, containers only inside systems, systems only inside diagrams.
- All edge endpoints resolve.
- All required positional arguments are present.

Errors are returned as `ex-info` exceptions with `ex-data` carrying `:type` (`:parse-error` / `:walk-error` / `:emit-error`), `:position` (line/col when available), and `:message`.

---

## 4. Architecture

### 4.1 Module Layout

```
src/
  drawl/
    parser.cljc        ; thin wrapper around read-string
    walker.cljc        ; forms → IR (multimethod dispatch)
    ir.cljc            ; level inference, filtering
    macros.cljc        ; built-in shorthand registrations
    emit/
      dot.cljc         ; IR → graphviz dot
      mermaid.cljc     ; IR → mermaid C4
    compiler.cljc      ; orchestrates parser → walker → emitter
  app/
    core.cljs          ; UIx mount, state atom
    editor.cljs        ; CodeMirror 6 wrapper
    render.cljs        ; mermaid.js / viz.js render
    layout.cljs        ; titlebar, panes, controls
    examples.cljs      ; preset diagrams
  cli/
    main.clj           ; bb entry: arg parsing, IO
test/
  drawl/
    walker_test.cljc
    emit/
      dot_test.cljc
      mermaid_test.cljc
    portability_test.cljc
public/
  index.html
  styles.css
shadow-cljs.edn
deps.edn
bb.edn
```

### 4.2 Public API (`drawl.compiler`)

```clojure
(compile source backend)
;; source: string
;; backend: :dot or :mermaid
;; returns: string (the compiled output)
;; throws: ex-info on parse/walk/emit errors

(parse source)
;; source: string
;; returns: IR map
;; throws: ex-info on parse/walk errors

(emit ir backend)
;; ir: IR map
;; backend: :dot or :mermaid
;; returns: string

(at-level level ir)
;; level: :context, :container, :component
;; ir: IR map
;; returns: filtered IR

(validate source)
;; source: string
;; returns: nil if valid, seq of error maps otherwise
```

### 4.3 Walker Implementation Sketch

Multimethod dispatch on head symbol — keeps the walker open for extension:

```clojure
(defmulti walk-form (fn [form _ctx] (first form)))

(defmethod walk-form 'diagram [[_ title & rest] ctx]
  (let [[attrs children] (split-attrs rest)]
    (assoc ctx
      :title (when (string? title) title)
      :attrs attrs
      :elements (mapv #(walk-form % ctx) children))))

(defmethod walk-form 'system    [form ctx] (walk-element :system form ctx))
(defmethod walk-form 'container [form ctx] (walk-element :container form ctx))
(defmethod walk-form 'component [form ctx] (walk-element :component form ctx))
(defmethod walk-form 'person    [form ctx] (walk-element :person form ctx))

(defmethod walk-form '-> [[_ from to & rest] ctx]
  (let [[desc rest'] (if (string? (first rest))
                       [(first rest) (next rest)]
                       [nil rest])
        [attrs _]    (split-attrs rest')]
    {:kind :edge :from from :to to :description desc :attrs attrs}))
```

Built-in macros are additional defmethods that re-dispatch:

```clojure
(defmethod walk-form 'phoenix-app [[_ name & opts] ctx]
  (walk-form (concat ['container name :tech "Phoenix"] opts) ctx))
```

### 4.4 Reader Compatibility

`clojure.edn/read-string` (JVM) and `cljs.reader/read-string` (browser) are nearly but not bug-for-bug identical. Specifically watch for tagged literals and namespaced keywords. The portability test suite (§7.3) loads the same fixture file through both runtimes and asserts identical IR output.

---

## 5. Targets

### 5.1 Browser SPA

- Built with shadow-cljs.
- UIx for React layer.
- CodeMirror 6 with `@nextjournal/clojure-mode` for the editor (paren matching, rainbow parens, slurp/barf).
- mermaid.js 10+ for C4 rendering, viz.js for dot rendering.
- Single UIx atom for state: `{:source :backend :level :compiled :error}`.
- Compile-on-change with 150ms debounce.
- `localStorage` for buffer persistence between sessions.
- URL hash with `lz-string` for share links: `/#z=<compressed-source>`.

Layout: split pane, source on left, render on right. Titlebar with backend toggle (mermaid C4 / dot), level selector (auto / context / container / component), share button. Footer shows parse/render status and any errors inline.

### 5.2 Babashka CLI

`bb.edn` declares the CLI tasks. Distributed via:

```
bbin install io.github.jordangarrison/drawl
```

Subcommands:

| Command | Description |
|---------|------------|
| `drawl compile --input FILE [--backend mermaid\|dot] [--output FILE]` | Compile to backend output |
| `drawl render --input FILE --output FILE.svg [--backend mermaid\|dot]` | Compile and render to SVG (requires `mmdc` or `dot` on PATH) |
| `drawl lint --input FILE` | Validate without compiling |
| `drawl watch --input FILE [--output FILE]` | Recompile on file change |

Cold start ~30-50ms via Babashka. v0.4+ may add a GraalVM native-image build for sub-millisecond startup if it ever matters.

### 5.3 JVM Library

Consumed via `deps.edn`:

```clojure
{:deps {io.github.jordangarrison/drawl {:git/sha "..."}}}
```

```clojure
(require '[drawl.compiler :as drawl])
(drawl/compile "(diagram ...)" :mermaid)
```

Use cases: server-side diagram generation in larger apps, embedding in Clojure-based static site generators, integrating with Clojure-native tooling.

---

## 6. Backends

### 6.1 graphviz dot

For "boxes and arrows" diagrams without C4 constraints. Useful for low-level component-level diagrams that don't need C4 styling.

| Element | dot output |
|---------|-----------|
| `:person` | `shape=oval` |
| `:system` (leaf) | `shape=box, style=rounded` |
| `:container` | `shape=box, style="rounded,filled"` |
| `:component` | `shape=box` |
| `:role :database` | `shape=cylinder` |
| boundary | `subgraph cluster_N` |

Edge `:style` maps directly to dot `style=` attribute.

### 6.2 mermaid C4

For C4-conformant diagrams. Mermaid handles styling, layout, and notation natively per the C4 spec.

Top-level header per level: `C4Context`, `C4Container`, `C4Component`.

| Form | mermaid C4 output |
|------|------------------|
| `(person id ...)` | `Person(id, ...)` or `Person_Ext(...)` if `:external` |
| `(system id ...)` leaf | `System(...)` or `System_Ext(...)` |
| `(system id ...)` with children | `System_Boundary(...) { ... }` |
| `(container id ...)` | `Container(...)` |
| `(container id ... :role :database)` | `ContainerDb(...)` |
| `(container id ... :role :queue)` | `ContainerQueue(...)` |
| `(component id ...)` | `Component(...)` |
| `(-> a b "desc" :tech "X")` | `Rel(a, b, "desc", "X")` |
| `(<-> a b ...)` | `Rel_Bi(a, b, ...)` |

---

## 7. Testing

### 7.1 Walker Tests

For each form, assert IR shape and presence of expected fields. Cover edge cases: missing required fields, duplicate names, unresolved refs, level inference at all three levels, attribute parsing boundary (the moment children start).

### 7.2 Emitter Tests

Snapshot tests. Given a fixture IR, assert the emitted string matches expected output. Maintain a small library of canonical examples — the C4 "Internet Banking" model is a good baseline because it exercises every element kind and both internal and external systems.

### 7.3 Portability Tests

The same `.cljc` fixture should produce identical IR when run through Clojure (JVM) and ClojureScript (via shadow-cljs `:node-test` build). Catches reader divergences early. Wire into CI so it runs on every push.

### 7.4 End-to-End

- Browser SPA: Playwright tests confirming the editor + render pipeline. Type a known good diagram, assert SVG appears in the render pane.
- CLI: shell-based — `bb drawl compile fixture.drawl | diff - expected.txt`.

---

## 8. Roadmap

### v0.1 — Core compiler and browser MVP

- Parser, walker, IR, dot emitter.
- Browser SPA with editor and dot rendering.
- localStorage persistence, URL share links.
- Built-in shorthand macros: `phoenix-app`, `postgres-db`, `redis-cache`, `rest-api`.

### v0.2 — C4 mapping

- Add `person`, `container` element types to walker.
- Implement mermaid C4 emitter.
- Backend toggle in browser UI.
- Level inference and `at-level` filtering.

### v0.3 — CLI via Babashka

- `cli/main.clj` with `compile`, `lint`, `watch` subcommands.
- Distribute via `bbin`.
- README with installation and usage.

### v0.4 — JVM library polish

- Publish to Clojars.
- Document public API.
- `drawl render` subcommand (shells to mermaid-cli or dot).

### Deferred

- User-defined macros at runtime (requires self-hosted CLJS, ~1MB bundle).
- GraalVM native-image CLI for sub-ms startup.
- Tree-sitter grammar for cross-editor highlighting.
- Additional backends: PlantUML, Excalidraw, Structurizr DSL.
- Multi-view publishing: same source generating context, container, and component views in one render pass.
- Org-babel `ob-drawl` package for Emacs.

---

## 9. Open Questions

A few decisions left to make before v0.1 starts:

- **UIx vs Reagent.** Either works. UIx is the more modern feel; Reagent has more documentation. No wrong answer.
- **Examples library.** Ship a small set (Internet Banking, three-tier web app, microservices, Phoenix LiveView app) so the editor opens to something interesting rather than a blank page.
- **Error rendering.** When a parse error happens mid-keystroke, do you keep showing the last good render, or clear it? Recommend the former — less flicker.
