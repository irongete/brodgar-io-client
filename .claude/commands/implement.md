# /implement — execute ONE task

Usage:
- `/implement` → the first unchecked task of the active feature (per `STATE.md`).
- `/implement <NNN.X>` → force a specific task.

## Common rules (non-negotiable)

- **NEVER `git push`. NEVER commit** — /implement commits nothing, not even after a fix
  round. The whole task (code, docs, addons, specs) is committed by `/end`, once the
  maintainer's in-game verification passes.
- `specs/`, `docs/`, `src/` and `addons/` all live in the project repo.
- Converse in **Spanish**; files and code in **English**.
- Java (engine) changes require `ant` rebuild + a full client restart (no hot-reload);
  only Lua addon files reload live (`:reload`).
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY:** `specs/addons/STATE.md` → the active feature → its `tasks.md`,
   `spec.md` and `plan.md`, **plus** the spec's "Context files" (and the task's own
   "extra context", if any). If `specs/addons/HANDOFF.md` exists, WARN the maintainer:
   there is a task pending `/end` — do not silently overwrite it.
2. **Implement the ONE task.** Verify: `ant hafen-client` → `BUILD SUCCESSFUL`, and the
   logic with `jshell` or the in-game `:lua` REPL where feasible.
3. **Do NOT check the task off, do NOT document, do NOT touch STATE.md** (that is /end's
   job). Write `specs/addons/HANDOFF.md` (MAX 30 lines): the task · files touched ·
   decisions made along the way · EXACTLY what to test in-game.
4. **STOP and report what to test in-game.** Then stay with the maintainer for as many
   rounds as it takes: they test, report back, you fix, they retest. Each fix updates
   `HANDOFF.md` so it always describes the CURRENT state. Still only ONE task, still no
   commit — when the maintainer is satisfied they run `/end`.
