# /implement — execute ONE task

Usage:
- `/implement <area>` → the first unchecked task of that area's active feature.
- `/implement <area> <NNN.X>` → force a specific task in that area.

The area is **always** stated.

## Area resolution (ALWAYS step 0)

**The area is never assumed. There is no "current area" state anywhere** — several features may
be in flight in different areas at once, so every invocation names its own.

1. Take `<area>` from the command's first token when it matches a folder under `specs/`
   (ignoring `_`-prefixed ones). A `NNN.X` token is a task, never an area.
2. **If the area is missing, ambiguous, or matches no folder, STOP and ask the maintainer**
   which area this is, listing the existing ones. Never guess and never fall back to a default
   — implementing the wrong area's task wastes a whole session.
3. **Read `specs/<area>/AREA.md`.** It declares the build check, verification procedure, test
   harness, design rules and commit paths. Everything area-specific comes from there — this
   command never assumes them.
4. **Open your reply with `[area: <area>]`** so the maintainer always sees which area is in play.

## Common rules (non-negotiable)

- **NEVER `git push`. NEVER commit** — /implement commits nothing, not even after a fix
  round. The whole task (code, docs, specs and the area's own trees) is committed by `/end`,
  once the maintainer's verification passes.
- `specs/`, `docs/`, `src/` and the area's own trees all live in the project repo.
- **Everything in English** — the conversation, the files, the code and its comments.
- Respect the area's rebuild/restart rules as stated in `AREA.md`.
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY:** `specs/<area>/AREA.md` and `specs/<area>/STATE.md` → the active feature →
   its `tasks.md`, `spec.md` and `plan.md`, **plus** the spec's "Context files" (and the
   task's own "extra context", if any). If `specs/<area>/HANDOFF.md` exists, WARN the
   maintainer: there is a task pending `/end` — do not silently overwrite it.
2. **Implement the ONE task.** Verify with the area's build check (it must pass), and
   pre-check the logic however `AREA.md` says is feasible.
3. **Do NOT check the task off, do NOT document, do NOT touch STATE.md** (that is /end's
   job). Write `specs/<area>/HANDOFF.md` (MAX 30 lines): the task · files touched ·
   decisions made along the way · EXACTLY what the maintainer must test · **"uncovered
   source"** — any `haven` file you had to read that no `specs/codebase/<subsystem>.md`
   covers, and which subsystem file should absorb it (`/end` writes it).
4. **STOP and report what to test.** Then stay with the maintainer for as many rounds as it
   takes: they test, report back, you fix, they retest. Each fix updates `HANDOFF.md` so it
   always describes the CURRENT state. Still only ONE task, still no commit — when the
   maintainer is satisfied they run `/end`.
