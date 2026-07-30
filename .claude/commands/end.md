# /end — close a task after the maintainer's in-game verification

Usage:
- `/end` → everything verified OK.
- `/end <note>` → OK with nuances; incorporate the note before closing.

## Common rules (non-negotiable)

- **NEVER `git push`.** Everything stays local.
- **This command makes THE task's commit** (step 6): code, docs, addons and specs in ONE
  commit. Running `/end` IS the approval — the maintainer only runs it after verifying
  in-game, so do not ask for permission again.
- `specs/`, `docs/`, `src/` and `addons/` all live in the project repo.
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
   summary and reflect it in `STATE.md`. The `NNN-` folder STAYS where it is (folders are
   never archived).
6. **Commit the whole task — always the LAST step.** First run `git status --short` and
   report anything that is NOT part of this task; never sweep a stray file into the
   commit. Then stage the task's paths — normally all four, `-A` so new files are included
   — and make ONE commit:
   `git add -A src docs addons specs && git commit -m "NNN.X: <task title>"`
   Add any other path the task genuinely touched (e.g. `build.xml`). This lands the code,
   the docs, the addon changes and the specs together — including the feature's spec/plan/
   tasks if this is its first `/end`. No approval needed, and never push.
