# brodgar-io-client — Project Instructions

Customized Haven & Hearth ("Hafen") client, a fork of `dolda2000/hafen-client`. Active work: a
**World-of-Warcraft-style Lua (LuaJ) AddOn system** living in `src/io/brodgar/addon/`, on branch
**`feature/addons`**.

## Rules (obey always)

- **NEVER `git push`.** Everything stays **local**. Pushing is forbidden unless the maintainer
  explicitly asks.
- **The ONLY self-driven commit is `/end`'s**, and it lands the whole task at once — code, docs,
  specs and the area's own trees — **after** the maintainer's verification. `/plan` and
  `/implement` commit nothing; outside `/end`, run `git commit` only when the maintainer
  explicitly says so.
- **Documentation is ONE tier**, whichever the working area's `AREA.md` declares (for `addons`:
  the user-facing **API reference** in **`docs/addons/api/*.md`**, + its `api/README.md` index and
  the top `docs/addons/README.md` "API at a glance" table when a new section first ships). There
  are no per-task narrative notes.
- **`specs/`, `docs/`, `src/` and the area's own trees all live in the project repo** and ride the
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

## How to continue the work

**The cycle is `/plan <feature>` (design, review, no commit) → `/implement` (one task; iterate with
the maintainer until it passes verification) → `/end` (document, close and commit everything).**
Each command states exactly what to read — read nothing else.

**Work is organized in AREAS**, one folder per area under `specs/` (today: `specs/addons/`).
Each area owns a `specs/<area>/AREA.md` manifest — docs tier, build check, verification, test
harness, design rules, commit paths — and everything area-specific comes from there.
**The area is always stated explicitly** — `/plan <area> <desc>`, `/implement <area> [NNN.X]`,
`/end <area> [note]` — and each command echoes `[área: <name>]` first. There is no "current
area" state: several features can be in flight in different areas at once, so nothing is ever
assumed. If a command cannot tell which area it is, it stops and asks. A new area is scaffolded
from `specs/_area-template/`.

Shared across areas: **`specs/codebase-map.md`** — an INDEX only, one line per
`specs/codebase/<subsystem>.md`. Open the 1–2 subsystems your feature touches, never the tree.
Reading `haven` source that no subsystem file covers is allowed, but then `/end` writes or
extends that file: coverage is paid for once, not re-derived every task.

Key paths inside an area: `STATE.md` (what works + the active feature), `ROADMAP.md` (future
work), `FEATURES.md` (one line per `NNN-` feature folder), `DECISIONS.md` (→ `decisions/`),
`LEARNINGS.md` (index of grep-able `learnings/*.md`). The area's public contract is its docs
tier — for `addons`, `docs/addons/api/` is the `hafen.*` contract; there is no second copy.

**Area `addons` — the `hello` addon (`addons/hello/`) is its standing test + regression harness.** Every task
extends it to exercise the new feature, so one login re-checks that *all* prior features still work.
It grows gradually with the project. If a feature is too big or distinct to fold in cleanly,
**propose a dedicated example addon** instead of bloating `hello`.
