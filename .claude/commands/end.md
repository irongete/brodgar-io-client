# /end — close a task after the maintainer's verification

Usage:
- `/end <area>` → everything verified OK in that area.
- `/end <area> <note>` → OK with nuances; incorporate the note before closing.

The area is **always** stated.

## Area resolution (ALWAYS step 0)

**The area is never assumed. There is no "current area" state anywhere** — several features may
be in flight in different areas at once, so every invocation names its own.

1. Take `<area>` from the command's first token when it matches a folder under `specs/`
   (ignoring `_`-prefixed ones); drop it from the note.
2. **If the area is missing, ambiguous, or matches no folder, STOP and ask the maintainer**
   which area this is, listing the ones that currently have a `HANDOFF.md` pending. Never guess
   and never fall back to a default — this command COMMITS, and the wrong area stages the wrong
   paths and closes the wrong task.
3. **Read `specs/<area>/AREA.md`.** It declares the docs tier and commit paths used below.
   Everything area-specific comes from there — this command never assumes them.
4. **Open your reply with `[área: <area>]`** so the maintainer always sees which area is in play.

## Common rules (non-negotiable)

- **NEVER `git push`.** Everything stays local.
- **This command makes THE task's commit** (step 7): code, docs, specs and the area's own
  trees in ONE commit. Running `/end` IS the approval — the maintainer only runs it after
  verifying, so do not ask for permission again.
- `specs/`, `docs/`, `src/` and the area's own trees all live in the project repo.
- Converse in **Spanish**; files and code in **English**.
- Respect the area's rebuild/restart rules as stated in `AREA.md`.
- **Read NOTHING outside what this command lists**, unless a task/spec lists it or the
  maintainer names it explicitly. **`/archive` is NEVER read.**

## Procedure

1. **Read ONLY `specs/<area>/AREA.md` and `specs/<area>/HANDOFF.md`** (+ the maintainer's
   note). If HANDOFF.md does not exist, say so and do nothing.
2. If the note describes a problem that requires code, **do NOT close**: propose fixing it
   within the same task or add a new task to the feature's `tasks.md`, and stop.
3. **Document in ONE tier only — exactly the docs tier `AREA.md` declares**, including the index
   / overview files it names when a new section first ships. **Never create per-task narrative
   notes; no devlog exists.** A new design decision → append the full entry to its
   `specs/<area>/decisions/<category>.md` file (a brand-new category also gets its line in
   `specs/<area>/DECISIONS.md`). Gotchas/learnings → append to the matching
   `specs/<area>/learnings/*.md` file (see that area's LEARNINGS.md index; add an index line
   only for a brand-new category).
4. **Pay the coverage toll.** If `HANDOFF.md` lists **uncovered source**, write or extend the
   matching `specs/codebase/<subsystem>.md` (max 70 lines: `file:line` anchors + the gotchas
   that cost you time) and add its one-line row to `specs/codebase-map.md` if the file is new.
   A task that read uncovered `haven` code and left no subsystem file is NOT closed.
5. **Close:** check the task off in `tasks.md` · update `specs/<area>/STATE.md` (REPLACING,
   max 60 lines) · learnings appended (step 3) · coverage paid (step 4) · if the code
   structure changed, update the affected `specs/codebase/<subsystem>.md` ·
   delete `specs/<area>/HANDOFF.md`.
6. **If it was the last task:** mark the feature DONE in `specs/<area>/FEATURES.md` with its
   one-line summary and reflect it in `specs/<area>/STATE.md`. The `NNN-` folder STAYS where
   it is (folders are never archived).
7. **Commit the whole task — always the LAST step.** First run `git status --short` and
   report anything that is NOT part of this task; never sweep a stray file into the commit.
   Then stage the area's **commit paths** as declared in `AREA.md` — `-A` so new files are
   included — and make ONE commit:
   `git add -A <commit paths from AREA.md> && git commit -m "NNN.X: <task title>"`
   Add any other path the task genuinely touched (e.g. `build.xml`). This lands the code, the
   docs and the specs together — including the feature's spec/plan/tasks if this is its first
   `/end`. No approval needed, and never push.
