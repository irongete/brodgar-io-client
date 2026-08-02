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
   protocol, design rules and commit paths. Everything area-specific comes from there — this
   command never assumes them.
4. **Read the area's test protocol file** (`AREA.md`'s *Test protocol* — for `addons`,
   `specs/addons/TESTING.md`). It defines what this task must ship as tests and the exact format
   its results are reported in. Skip only if the area declares `none`.
5. **Open your reply with `[area: <area>]`** so the maintainer always sees which area is in play.

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

1. **Read ONLY:** `specs/<area>/AREA.md`, the test protocol file it names, and
   `specs/<area>/STATE.md` → the active feature →
   its `tasks.md`, `spec.md` and `plan.md`, **plus** the spec's "Context files" (and the
   task's own "extra context", if any). If `specs/<area>/HANDOFF.md` exists, WARN the
   maintainer: there is a task pending `/end` — do not silently overwrite it.
2. **Implement the ONE task.** Verify with the area's build check (it must pass), and
   pre-check the logic however `AREA.md` says is feasible.
3. **Ship the task's tests exactly as the test protocol prescribes** — they are part of the
   task, not an extra. **Automate everything the API can assert**; only what a program cannot
   do is left as a `[manual]` line, and each of those states the steps and the expected result.
4. **Do NOT check the task off, do NOT document, do NOT touch STATE.md** (that is /end's
   job). Write `specs/<area>/HANDOFF.md` (MAX 30 lines): the task · files touched ·
   decisions made along the way · **how to run the task's tests and paste the results back** ·
   any `[manual]` line that needs a human · **"uncovered
   source"** — any `haven` file you had to read that no `specs/codebase/<subsystem>.md`
   covers, and which subsystem file should absorb it (`/end` writes it).
5. **STOP and report how to run the tests.** Then stay with the maintainer for as many rounds
   as it takes: they run it and paste the log back, you read every `[fail]` and every answered
   `[manual]` line, fix, they re-run. Each fix updates `HANDOFF.md` so it
   always describes the CURRENT state. Still only ONE task, still no commit — when the
   maintainer is satisfied they run `/end`.
