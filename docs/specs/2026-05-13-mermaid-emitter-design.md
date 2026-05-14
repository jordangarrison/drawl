# mermaid C4 emitter — design

Date: 2026-05-13
Status: accepted
Tracks: SPEC.org §6.2, roadmap v0.2

## Goal

Add `drawl.emit.mermaid` so `(drawl.compiler/compile src :mermaid)` returns
a mermaid C4-PlantUML source string, and wire the browser SPA to render
it via the mermaid npm package.

IR is fixed. Emitter is pure `IR -> string`. No IO, no external tools,
runs identically on JVM and CLJS.

## Output shape

Header keyword is driven by `(:level ir)`:

| Level         | Header        |
|---------------|---------------|
| `:context`    | `C4Context`   |
| `:container`  | `C4Container` |
| `:component`  | `C4Component` |

Body shape (illustrative — actual whitespace is 4-space indent inside
`{ ... }`, one form per line):

```
C4Container
    title <ir :title>

    Person(id, "Title", "Description")
    Person_Ext(id, ...)

    System(id, "Title", "Description")
    System_Ext(id, ...)
    System_Boundary(id, "Title") {
      ...children...
    }

    Container(id, "Title", "Tech", "Description")
    ContainerDb(id, ...)              // :role :database
    ContainerQueue(id, ...)           // :role :queue
    Container_Boundary(id, "Title") { ...children... }

    Component(id, "Title", "Tech", "Description")
    ComponentDb(id, ...) / ComponentQueue(id, ...)

    Rel(from, to, "Label", "Tech")
    BiRel(from, to, "Label", "Tech")
```

## Per-element rules

| Element kind                       | Output                                            |
|------------------------------------|---------------------------------------------------|
| `:person`                          | `Person(id, T, D)` (`Person_Ext` if `:external`)  |
| `:system` leaf                     | `System(id, T, D)` (`System_Ext` if `:external`)  |
| `:system` with children            | `System_Boundary(id, T) { ... }`                  |
| `:container` leaf                  | `Container(id, T, Tech, D)` (or `Db`/`Queue`)     |
| `:container` with children         | `Container_Boundary(id, T) { ... }`               |
| `:component`                       | `Component(id, T, Tech, D)` (or `Db`/`Queue`)     |

`:role` mapping:
- `:database` → `*Db` macro (`ContainerDb`, `ComponentDb`)
- `:queue`    → `*Queue` macro (`ContainerQueue`, `ComponentQueue`)
- everything else → plain macro (silently ignored)

`:role` on Person/System is silently ignored (no native variant in
C4-PlantUML, and the emitter follows SPEC §2.4 "unknown attrs are
silently passed over").

## Per-edge rules

| Form                              | Output                                |
|-----------------------------------|---------------------------------------|
| `(-> a b "label" :tech "X")`      | `Rel(a, b, "label", "X")`             |
| `(<-> a b "label" :tech "X")`     | `BiRel(a, b, "label", "X")`           |

Edge `:style` is silently ignored — mermaid C4 has no per-edge stroke
control. Documented as a known gap; users who need dashed edges should
prefer the dot backend.

Cluster-edge logic (graphviz `compound`, `ltail`, `lhead`) is **not**
implemented in this emitter. The original assumption was that mermaid
would route boundary endpoints visually; testing during implementation
proved otherwise — mermaid C4 (v10 and v11) throws
`Cannot read properties of undefined (reading 'x')` when an edge
endpoint is a `System_Boundary` or `Container_Boundary`. The emitter
mirrors dot's "first-leaf-in-declaration-order" anchor contract: when
an edge endpoint is a boundary, the emitter rewrites it to the first
leaf descendant. The boundary itself is not highlighted — the edge
just attaches to the chosen interior leaf — but the diagram renders.

## Nil/empty handling

- Missing `:title` falls back to `(name id)`.
- Missing `:description` emits `""` (empty quoted string). Per-element
  arity is fixed; mermaid renders empty descriptions as blank.
- Missing `:tech` on Container/Component emits `""` in the tech slot.
- `:tech` on Person/System: ignored (mermaid C4 Person/System have no
  tech slot).

## String escaping

- `"` -> `\"`. No other escapes (mermaid honours `\n` literally in
  labels, leave it alone).
- IDs pass through as `(name id)`. Mermaid C4-PlantUML accepts hyphens
  in identifiers, matching the dot emitter's contract.

## Edge collection

Use `drawl.ir/collect-edges` — the same single source of truth the
dot emitter uses. Top-level relationships first, then any element's
`:edges`, depth-first. Emit order in the mermaid source matches that
collection order; mermaid C4 does not depend on declaration order for
correctness but keeping it stable makes snapshots deterministic.

## SPA wiring

- Add `"mermaid": "^10.9.4"` to `package.json` dependencies. v11 was
  tried first but its split-bundle layout broke shadow-cljs npm
  resolution (`@upsetjs/venn.js` exports parsing); v10 ships a single
  prebundled ESM file and renders C4 with identical fidelity in our
  test cases.
- New `src/app/mermaid.cljs`:
  ```clojure
  (defn render
    "src -> Promise<SVGElement>"
    [src])
  ```
  Lazy `defonce`'d mermaid init (`mermaid.initialize({startOnLoad:
  false, securityLevel: 'loose', theme: 'default'})`). Uses
  `mermaid.render(id, src)` and converts the returned SVG string into
  an element via a throwaway `<div>` + `innerHTML` (not DOMParser:
  DOMParser silently strips mermaid's namespaced attributes, which
  collapses several diagram types to an empty layout). The returned
  SVG gets an inline `background:#fff` so the rendered diagram stays
  legible against drawl's dark theme — mermaid C4's palette is
  hard-coded to light-mode colours.
- Extend the `case backend` in `src/app/core.cljs`:
  - `:mermaid` -> route through `app.mermaid/render`, replace `out`
    pane's children with the resulting `<svg>`.
- `file-info` adds `:mermaid {:ext "mmd" :mime "text/vnd.mermaid"
  :summary "mermaid source"}`.
- Add `<option value="mermaid">mermaid C4</option>` to the backend
  selector in `public/index.html`.
- Cheatsheet copy gets a "mermaid C4" entry (optional polish).

## Tests

`test/drawl/emit/mermaid_test.cljc` — style mirrors
`dot_test.cljc`. String-includes assertions on compiled output. Cases:

- header per level (context/container/component)
- `Person` vs `Person_Ext`
- `System` vs `System_Ext`
- `System_Boundary` for system with children
- `Container_Boundary` for container with children
- `Container` with `:tech` in the third slot
- `ContainerDb` for `:role :database`
- `ContainerQueue` for `:role :queue`
- `Component` and `ComponentDb`
- `Rel` arity (with/without tech, with/without description)
- `BiRel` for `<->`
- `:description` nil -> `""`
- `at-level :context` strips containers and emits `C4Context`
- `:style` on edges silently ignored (regression — no extra args)
- escape rule (`"` inside a label)

Compiler test (`test/drawl/compiler_test.cljc`) gets the
`examples-compile` test extended to also compile each fixture to
`:mermaid` and assert it starts with a `C4*` header.

## Out of scope (deferred)

- Warnings for `:style`, `:role` on Person/System, `:tech` on Person/System.
- A "lint" pass that surfaces unused-attr warnings (deferred until the
  CLI lint subcommand lands in v0.3).
- Custom mermaid theme switching from the SPA (mermaid C4 uses a
  fixed light palette regardless of the global `theme:` directive).
- Per-relationship `Rel_Back` / `Rel_U` directional variants.

## Findings logged during implementation

- `clojure.string/replace` on a non-string throws "s.replace is not a
  function" in CLJS — `:tech` and similar attrs that parse as bare
  symbols must be coerced via `str` before any string op. The emitter
  does this in its `q` helper.
- mermaid 11 ships a multi-chunk bundle with `@upsetjs/venn.js` in its
  exports field; shadow-cljs's npm resolver can't follow it. Mermaid
  10's prebundled ESM works cleanly.
- The Closure compiler can't down-transpile mermaid's ES2018 features
  (named groups, RegExp `s` flag) to ES8; `shadow-cljs.edn`'s
  `:output-feature-set` is bumped to `:es2020`.
- DOMParser + `image/svg+xml` strips namespaced attributes mermaid
  emits, which silently kills C4 layout. innerHTML on a throwaway div
  is the workaround.
- Boundary-as-edge-endpoint crashes mermaid C4 layout; we replicate
  the dot first-leaf anchor instead of relying on mermaid clipping.

## File map

```
src/drawl/emit/mermaid.cljc       (new)
test/drawl/emit/mermaid_test.cljc (new)
src/drawl/compiler.cljc           (case :mermaid)
src/app/mermaid.cljs              (new)
src/app/core.cljs                 (extend backend switch, file-info)
public/index.html                 (add option)
package.json                      (mermaid dep)
test/drawl/compiler_test.cljc     (extend examples-compile)
SPEC.org                          (move mermaid emitter to "done" in §8)
GRAMMAR.org                       (note both backends in §Backend mapping)
README.org                        (add mermaid to backend list)
```
