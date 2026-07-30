# /end — close a task after the maintainer's in-game verification

Usage:
- `/end` → everything verified OK.
- `/end <note>` → OK with nuances; incorporate the note before closing.

## Common rules (non-negotiable)

- **NEVER `git push`.** Everything stays local.
- **Code/docs commits are the maintainer's** (after in-game verification). The ONE commit
  this command makes is step 6 — a **specs-only** commit (`git commit -- specs`), nothing
  else staged with it.
- `specs/` and `docs/` are both committed in the project repo.
- Converse in **Spanish**; files and code in **English**.
- Java (engine) changes require `ant` rebuild + a full client restart (no hot-reload);
  only Lua addon files reload live (`:reload`).
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY `specs/addons/HANDOFF.md`** (+ the maintainer's note). If it does not
   exist, say so and do nothing.
2. If the note describes a problem that requires code, **do NOT close**: propose fixing it
   within the same task or add a new task to the feature's `tasks.md`, and stop.
3. **Document in ONE tier only:** create/update `docs/addons/api/*.md` with the shipped
   surface (+ the `api/README.md` index and the `docs/addons/README.md` "API at a glance"
   table when a new section first ships). **The devlog does NOT exist — never create
   per-task narrative notes.** A new design decision → append the full entry to its `specs/addons/decisions/<category>.md`
   file (a brand-new category also gets its line in `DECISIONS.md`). Gotchas/learnings → append to the matching
   `specs/addons/learnings/*.md` file (see the LEARNINGS.md index; add an index line only
   for a brand-new category).
4. **Close:** check the task off in `tasks.md` · update `STATE.md` (REPLACING, max 60
   lines) · learnings appended (step 3) · if the code structure changed, update
   `specs/codebase-map.md` (and `specs/addons/API-REFERENCE.md` if the shipped surface
   differs from the contract) · delete `HANDOFF.md`.
5. **If it was the last task:** mark the feature DONE in `FEATURES.md` with its one-line
   summary, reflect it in `STATE.md`, and remind the maintainer of the final code+docs
   commit. The `NNN-` folder STAYS where it is (folders are never archived).
6. **Commit the specs changes — always the LAST step**, scoped to `specs/` only so the
   maintainer's pending code/docs changes are untouched:
   `git add specs && git commit -m "end NNN.X — <task title>" -- specs`.
   No approval needed — this is the workspace bookkeeping commit.
