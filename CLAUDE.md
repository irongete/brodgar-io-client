# brodgar-io-client — Project Instructions

Customized Haven & Hearth ("Hafen") client, a fork of `dolda2000/hafen-client`. Active work: a
**World-of-Warcraft-style Lua (LuaJ) AddOn system** living in `src/io/brodgar/addon/`, on branch
**`feature/addons`**.

## Rules (obey always)

- **NEVER `git push`.** Everything stays **local**. Pushing is forbidden unless the maintainer
  explicitly asks.
- **The ONLY self-driven commit is `/end`'s**, and it lands the whole task at once — code, docs,
  addons and specs — **after** the maintainer's in-game verification. `/plan` and `/implement`
  commit nothing; outside `/end`, run `git commit` only when the maintainer explicitly says so.
- **Documentation is ONE tier:** the user-facing **API reference** in **`docs/addons/api/*.md`**
  (+ its `api/README.md` index and the top `docs/addons/README.md` "API at a glance" table when a
  new section first ships). There are no per-task narrative notes.
- **`specs/`, `docs/addons/`, `src/` and `addons/` all live in the project repo** and ride the
  same `/end` commit. A feature's specs (written by `/plan`) land with the first `/end` of that
  feature. Nothing is ever pushed. **`/archive` is a frozen backup — NEVER read it.**
- Converse in **Spanish**; write docs, specs, and code comments in **English**.
- API design: **one canonical way** per operation (no dual styles); **namespaced `hafen.*`**;
  reference-based accessors (`hafen.gob.health(ref)`).
- Core edits to `haven` stay minimal and **centralized**, tagged `// addon:`. Invasiveness is
  allowed where it clearly enables better features (decision D-011) — but prefer new code in
  `src/io/brodgar/addon/` + few one-liners.
- Java compiles at `source/target 1.8` (no `var`, `Files.readString`, switch-expressions, …) and
  runs on Java 23. **Java (engine) changes require `ant` rebuild + a full client restart** — the JVM
  does not hot-reload classes. Only Lua addon *files* reload live (`:reload`).

## Build

`ant get-luaj` (fetch deps) · `ant hafen-client` (compile) · `ant bin` (package) · `ant run` (launch).
Verify with `ant hafen-client` → `BUILD SUCCESSFUL`, then stop for the maintainer's in-game test.

## How to continue the AddOn work

**The cycle is `/plan <feature>` (design, review, no commit) → `/implement` (one task; iterate with
the maintainer until it passes in-game) → `/end` (document, close and commit everything).**
Each command states exactly what to read — read nothing else.
Key paths: `specs/addons/STATE.md` (what works + the active feature), `ROADMAP.md` (future work),
`FEATURES.md` (one line per `NNN-` feature folder), `DECISIONS.md` (→ `decisions/`),
`API-REFERENCE.md` (the `hafen.*` contract), `specs/codebase-map.md` (client-wide `file:line` map),
`LEARNINGS.md` (index of grep-able `learnings/*.md`).

**The `hello` addon (`addons/hello/`) is our standing test + regression harness.** Every task
extends it to exercise the new feature, so one login re-checks that *all* prior features still work.
It grows gradually with the project. If a feature is too big or distinct to fold in cleanly,
**propose a dedicated example addon** instead of bloating `hello`.
