# /end — close a task after the maintainer's verification

Usage: `/end` → the maintainer has verified the task, and it closes here.

**This command makes THE task's commit** — code, docs, specs and addons in ONE. Running `/end` IS
the approval: the maintainer only runs it after verifying, so do not ask again. Never `git push`.

## Procedure

1. **`/end` closes a task THIS context implemented.** Everything it needs is already here: the
   `spec.md` and `tasks.md` `/implement` read, the files it wrote, the suite log the maintainer
   pasted. **If this context holds no `/implement` run, STOP and say so** — the commit stages the
   paths this task wrote, and this context is the only thing that knows which those are; `git
   status` cannot tell them from anything else in the tree. Read nothing new.

2. **Read the pasted test log, and check that it is the current one.** The log must be **later than
   your last edit**: if you changed a file after the log the maintainer pasted, this task is
   unverified — name what you changed, ask for another `:t<NNN>-<X>` run, and stop. The last thing
   in this context before `/end` is a log, never an edit of yours.

   A `[fail]` line, or a `[manual]` line whose answer does not match its expected result, **is a
   problem, and by default it is this task's**: fix it here, have the maintainer re-run, and the
   task does not close. A defect that lies **outside what this task claims but inside the surface
   this FEATURE ships** becomes a new task in `tasks.md` — say which of the two you chose and why.
   Either way, stop.
   A task that shipped no suite is not closed either, and neither is one whose maintainer described
   anything needing code.

3. **No page may teach a name that already throws.** Derive the refusal table from the engine
   (`Retired.NAMES`/`KEYS`) and grep `docs/`. If a retired spelling survives, this closes only when
   the NEXT task in `tasks.md` is the sweep that removes it; with no such task, it does not close.

   The same for the map: if the task read upstream `haven` that no `docs/client/` page covered and
   left no page behind, **it is not closed**. Check it for line numbers and for anything about
   `io.brodgar` — both are refused there.

4. **Close:**
   - Check the task off in `tasks.md`.
   - **Leave `Context files:` describing the tree as it now is.** Where this task moved a file,
     split one, or added one a later task needs, correct the list in `spec.md`. The next task opens
     what the list names, so a path that stopped being true costs it a search.
   - **Archive the suite in the state it shipped in**: diff its `manifest.json` against what
     `/implement` wrote — a `[manual]` that had the maintainer add or misspell a key must have put
     it back, or the archived proof no longer loads. Then move `addons/<NNN>-<feature>.<X>/` into
     `specs/<NNN>-<feature>/addons/`, and delete `bin/addons/<NNN>-<feature>.<X>/`.

5. **If it was the last task of the feature**, it closes on two counts, and both are checked here.

   - **Every acceptance criterion in `spec.md` is claimed by a task, and every claiming task is
     checked off.** A criterion no task claims stops the close.
   - **Nothing this feature knows about its own surface is left open.** Every gap and every defect
     found in what this feature ships — by a suite, by the maintainer, or by reading — is either a
     task that is checked off, or a line in `plan.md`'s *Discarded alternatives* naming it and
     saying why it stands. **An undecided one stops the close**: take it to the maintainer, and it
     becomes a task or a line before this runs again. *A feature ships whole; what it leaves undone
     about itself is the premise of the next one, and a queue that only grows.*

   The `NNN-` folder then stays exactly where it is and is frozen — nothing is appended, nothing is
   archived, no index is updated. It is done because no box is unchecked, which is derived.

6. **Commit — always the LAST step, and it carries ONLY what this task wrote.** Stage the explicit
   list of paths this task created or changed, derived from what you did in this context, never from
   `git status`:

   ```bash
   git add <path> <path> … && git commit
   ```

   Every path the task genuinely touched goes in, wherever it lives — `src/`, `docs/`, `addons/`,
   `specs/`, `build.xml`. **Everything else in the tree is left exactly as it is: never staged,
   never reported, never asked about.** Other work in flight is not this task's business, and a
   commit carrying a file the task did not write is what this rule exists to stop. Subject line
   `NNN.X: <task title>`; the body says **what shipped, and where it went differently from the
   plan** — written from what you did in this context, not from a summary of it. End with the
   `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` trailer.

   **`specs/ROADMAP.md` is the maintainer's own file: never add a line to it, never strike one.**
   Where this task's work bears on what it states, say so in the report and leave the file alone.

   This lands the code, the docs and the specs together — including the feature's spec/plan/tasks if
   this is its first `/end`. No approval needed, and never push.
