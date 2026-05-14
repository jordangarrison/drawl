# mermaid C4 emitter — implementation plan

Date: 2026-05-13
Spec: [`docs/specs/2026-05-13-mermaid-emitter-design.md`](../specs/2026-05-13-mermaid-emitter-design.md)

## Branch

`feat/mermaid-emitter` — single feature branch, PR back to `main`.

## Order of work (TDD, REPL-driven via clj-nrepl-eval)

Each step lands a green test before the next starts. Run a single
namespace via the running nREPL when iterating; full `clojure -M:test`
at the boundary of each step.

### 1. Scaffold emitter + smoke test

- Create `src/drawl/emit/mermaid.cljc` with:
  - ns + `:require` for `clojure.string` and `drawl.ir`
  - `(defn emit [ir] ...)` returning `"C4Context\n"` (placeholder)
- Create `test/drawl/emit/mermaid_test.cljc` with one failing test:
  starts-with `C4Context` on `(diagram (person a))`.
- Wire `:mermaid` into `drawl.compiler/emit`'s `case`.
- nREPL: `(c/compile "(diagram (person a))" :mermaid)` -> no throw.

### 2. Header per level

- Test: at-level `:container` -> `C4Container`; component-deep -> `C4Component`.
- Implement `header` fn on `(:level ir)`.

### 3. Person + Person_Ext

- Tests:
  - `(person alice "Alice" "User")` -> `Person(alice, "Alice", "User")`
  - `:external true` -> `Person_Ext(...)`
- Implement quoting + `:external` suffix flip.

### 4. System leaf + System_Ext + System_Boundary

- Tests:
  - leaf system -> `System(id, ...)`
  - `:external true` leaf -> `System_Ext(...)`
  - system with children -> `System_Boundary(id, "Title") {` then recursed body then `}`
- Implement `element-lines` that branches on `(seq children)`.

### 5. Container + ContainerDb + ContainerQueue + Container_Boundary

- Tests:
  - leaf container -> `Container(id, "T", "Tech", "")`
  - `:role :database` -> `ContainerDb`
  - `:role :queue`    -> `ContainerQueue`
  - container with children -> `Container_Boundary(id, "T") { ... }`
- Refactor: `kind-macro` derives macro name from `(:kind el)` + `:role`.

### 6. Component + ComponentDb + ComponentQueue

- Same shape as Container minus the boundary variant.

### 7. Title + description + tech defaults

- Tests:
  - missing title -> falls back to `(name id)`
  - missing description -> `""` in output
  - missing tech on Container/Component -> `""` in output
  - `:tech` on Person/System -> ignored (no extra arg)

### 8. Edges

- Tests:
  - `(-> a b "label" :tech "X")` -> `Rel(a, b, "label", "X")`
  - `(<-> a b "label")` -> `BiRel(a, b, "label", "")`
  - description nil -> `""` in 3rd slot, tech `""` in 4th
  - cross-boundary edge -> plain `Rel(...)` (no ltail/lhead)
- Use `drawl.ir/collect-edges`.

### 9. Escaping

- Test: a title containing `"` -> escaped to `\"`.
- Implement `esc` helper (same shape as dot emitter).

### 10. Examples compile

- Extend `examples-compile` in `test/drawl/compiler_test.cljc` with a
  parallel `doseq` that asserts each fixture compiles to `:mermaid` and
  the output starts with a `C4` header.

### 11. SPA wiring

- `package.json`: add `"mermaid": "^11"`. Run `npm install`.
- New `src/app/mermaid.cljs`:
  - `(defonce instance ...)` — initializes `mermaid` once
    (`startOnLoad: false`, theme `'default'`)
  - `(defn render [src] ... -> Promise<SVGElement>)`
- Edit `src/app/core.cljs`:
  - Add `"mermaid" :mermaid` to `backend-keyword`
  - Add `:mermaid {:ext "mmd" :mime "text/vnd.mermaid" :summary "mermaid source"}` to `file-info`
  - In `render!`'s `case backend`, add a `:mermaid` branch that calls
    `app.mermaid/render` and replaces `out-el` children with the SVG.
- Edit `public/index.html`: add `<option value="mermaid">mermaid C4</option>` to the backend select.

### 12. Browser visual check

- Start shadow-cljs (`npx shadow-cljs watch app`).
- Load each example in the dropdown (or paste in source).
- Switch backend to mermaid. Confirm SVG renders, no console errors.

### 13. Doc updates

- `SPEC.org` §8: move "Implement mermaid C4 emitter" / "Backend toggle
  in browser UI" from v0.2 roadmap to "delivered" alongside dot.
- `GRAMMAR.org` "Backend mapping" section: confirm both `dot` and
  `mermaid C4` are live (the current text already references mermaid;
  ensure no "planned" hedging remains).
- `README.org`: extend backend list and Status to mention mermaid is in.

### 14. PR

- Conventional commit: `feat(drawl): mermaid C4 emitter`.
- Use `@.github/PULL_REQUEST_TEMPLATE.md` if present.
- Test plan in PR body: enumerated `mermaid_test` cases + visual check.

## Risk / rollback

- `mermaid` npm dep is ~1MB; release build remains under viable size
  (current bundle ~600KB w/ viz.js). If bundle blows past acceptable,
  switch to dynamic import in `src/app/mermaid.cljs`
  (`shadow.lazy/loadable`). Decision deferred until measured.
- mermaid C4 syntax is a documented subset — if a corner case breaks
  rendering in the SPA, fall back to printing the source and let the
  user copy it into the mermaid live editor. Already the behaviour for
  the excalidraw backend.
