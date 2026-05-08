# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) and other AI coding agents when working with code in this repository. `CLAUDE.md` is a symlink to this file for Claude Code's discovery convention.

> Note: this guide stays in markdown for tool-discovery (Claude Code / Codex agents look for `CLAUDE.md` / `AGENTS.md` by name). The canonical design document `SPEC.org` is in org-mode.

## Project status

drawl is **pre-implementation**. The repo currently contains:

- `SPEC.org` — the authoritative design document (org-mode). Read it before any non-trivial work.
- `flake.nix` — real dev shell: `jdk21`, `clojure`, `babashka`, `nodejs_22`, `clj-kondo`, `graphviz` (`dot`), `mermaid-cli` (`mmdc`).
- `.envrc` (`use flake`) — direnv hook.

There is **no source code yet**. Do not assume `src/`, `deps.edn`, `bb.edn`, `shadow-cljs.edn`, or `package.json` exist — they are described in `SPEC.org` §4.1 as the planned layout but not on disk. Verify with `ls` before referencing them.

## What drawl is

A Lisp surface for declaring C4-aligned diagrams, compiled by walking forms into an IR and emitting backend output (graphviz dot, mermaid C4). Single codebase ships three targets:

1. **Browser SPA** (shadow-cljs + UIx + CodeMirror 6) — live editor, sub-150ms keystroke-to-SVG.
2. **Babashka CLI** — `drawl compile|render|lint|watch`, distributed via `bbin`.
3. **JVM library** — `drawl.compiler/{compile,parse,emit,at-level,validate}` consumed via `deps.edn`.

Source files use `.cljc` so the parser/walker/IR/emitters run identically on JVM and CLJS. The browser and CLI/JVM targets share `drawl.*` core code; only `app/*.cljs` (UI) and `cli/main.clj` (Babashka entry) are platform-specific.

## Architecture (per SPEC.org §3–§4)

The compile pipeline is a strict three-stage funnel — keep these boundaries clean when adding code:

```
source string
  → parser     (clojure.edn/read-string on JVM/bb, cljs.reader/read-string in browser)
  → walker     (multimethod on form head; produces IR)
  → IR         (single nested map: {:title :level :elements :relationships})
  → emitter    (drawl.emit.dot or drawl.emit.mermaid; pure IR → string)
```

Key invariants from the spec:

- **Implicit `do` wrapping**: full source is wrapped in `(do ...)` so multiple top-level forms are legal, but exactly one `(diagram ...)` form must remain after macro expansion.
- **Flat global namespace**: every `person`/`system`/`container`/`component` ID lives in one table. Duplicates are a hard error; unresolved edge endpoints are a hard error. Qualified `system/component` syntax is reserved, not implemented.
- **Attribute parsing rule** (§2.2): positional args first, then keyword-value pairs greedily until the first non-keyword form, then everything else is children. Single-pass, no backtracking. The walker must follow this exactly or nesting breaks.
- **Level inference is computed, not declared**: deepest `:kind` wins (`:component` > `:container` > `:context`). One source can be filtered to lower levels via `(drawl.ir/at-level :context ir)` — preserve this so a single file serves multiple zoom levels.
- **Walker uses multimethod dispatch on head symbol** (`defmulti walk-form`). Built-in macros (`phoenix-app`, `postgres-db`, `redis-cache`, `rest-api`) are additional `defmethod`s that re-dispatch to canonical forms. User-defined `(defmacro ...)` is **not** supported in v1 — it would require self-hosted CLJS (~1MB bundle hit). Adding a new shorthand = adding a `walk-form` defmethod to core.
- **Errors as `ex-info`** with `ex-data` carrying `:type` (`:parse-error` / `:walk-error` / `:emit-error`), `:position`, `:message`. Don't throw bare exceptions.
- **Reader portability matters**: `clojure.edn/read-string` and `cljs.reader/read-string` are nearly but not bug-for-bug identical (watch tagged literals, namespaced keywords). The portability test suite (§7.3) loads the same fixture through both runtimes and asserts identical IR — wire it into CI before it bit-rots.

## Backend mapping cheat-sheet

When adding emitter logic, the canonical mappings are in SPEC.org §6. Quick reference:

- mermaid C4 header per level: `C4Context` / `C4Container` / `C4Component`. `:external` flips `Foo` → `Foo_Ext`. Systems with children become `System_Boundary`.
- dot: roles map to shapes (`:database` → `cylinder`); boundaries become `subgraph cluster_N`. Edge `:style` passes through to dot `style=`.
- Unknown attributes pass through the walker and are silently ignored by emitters (warning optional). This is intentional — keeps the surface open for user-introduced attrs.

## Commands

Enter the dev shell:

```bash
nix develop          # enter dev shell
direnv allow         # auto-load on cd (one-time)
```

Planned per SPEC.org §5 — none of these work yet (no `deps.edn` / `bb.edn` / `shadow-cljs.edn` on disk):

```bash
# Browser SPA
npx shadow-cljs watch app           # dev build + hot reload
npx shadow-cljs release app         # production build

# Tests
clojure -M:test                     # JVM walker/emitter tests
npx shadow-cljs compile node-test   # CLJS portability tests

# CLI (after bb.edn lands)
bb drawl compile --input FILE --backend mermaid
bb drawl lint    --input FILE
bb drawl watch   --input FILE --output FILE
```

When implementing, prefer running a single test namespace over the whole suite: `clojure -M:test -n drawl.walker-test` (Cognitect test runner) or whatever runner ends up wired in `deps.edn`.

## Non-goals (do not drift into these)

From SPEC.org §1: not replacing Structurizr, not implementing general graph layout (delegated to graphviz/mermaid), no C4 L4 (code-level) diagrams in v1, no rich text in labels, no runtime user macros.

## Conventions

- Conventional commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`).
- Never force push.
- File extension is `.drawl`. CLI command is `drawl`.
- When in doubt about syntax or semantics, the spec is the source of truth — update `SPEC.org` in the same commit if behavior diverges intentionally.
