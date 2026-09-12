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
   unverified — name what you changed, ask for another `:t<NNN>` run, and stop. The last thing
   in this context before `/end` is a log, never an edit of yours.

   A `[fail]` line, or a `[manual]` line whose answer does not match its expected result, **is a
   problem, and by default it is this task's**: fix it here, have the maintainer re-run, and the
   task does not close. A defect that lies **outside what this task claims but inside the surface
   this FEATURE ships** becomes a new task in `tasks.md` — say which of the two you chose and why.
   Either way, stop.
   A task that shipped no suite is not closed either, and neither is one whose maintainer described
   anything needing code.

3. **No page may teach a name that already throws.** `python tools/docverbs.py` and
   `python tools/retiredverbs.py` — both exit non-zero on a finding, so this is a gate and not a
   reading. Between them they resolve every documented verb against its own **receiver's**
   vocabulary, every event key against the sets the bridge actually fires, every verb a refusal
   offers as a replacement, and every collection used as an array. A grep over `Retired.NAMES`
   cannot do this: it asks only whether a name exists *somewhere*, which is why it was green while
   six pages taught code that raises. **Read what each tool says it cannot see** — a green there is
   exactly as wide as its stated blind spots and no wider. If a retired spelling survives, this
   closes only when the NEXT task in `tasks.md` is the sweep that removes it; with no such task, it
   does not close.

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

   **What the folder already decides is not open — it is closed, and it closes here.** Before
   anything is called undecided and stops this, put it against `plan.md`'s *Discarded alternatives*,
   `spec.md`'s acceptance criteria and its *out of scope*, the checked-off lines of `tasks.md`, and
   the API grammar in `CLAUDE.md`. A gap a shipped task already delivered, or a boundary the spec
   already drew, is **decided**: close over it and say nothing about it. What survives stops the
   close **quoting the sentence that came closest and saying what it leaves open**; where there is no
   such sentence to quote, nothing stands open. Holding a verified task back over a question the
   folder answers costs the maintainer a round trip to answer it out of a file this context had open,
   and this command counts that exactly as it counts closing over a real gap.

   The `NNN-` folder then stays exactly where it is and is frozen — nothing is appended, nothing is
   archived, no index is updated. It is done because no box is unchecked, which is derived.

6. **Commit — on the feature's branch, and it carries ONLY what this task wrote.** The branch is
   `feature/<NNN>-<feature>`, the one `/implement` derived; `git rev-parse --abbrev-ref HEAD` says so,
   and anything else — `master` above all — stops the close: say where you are. Then stage the
   explicit list of paths this task created or changed, derived from what you did in this context,
   never from `git status`:

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

7. **If it was the last task of the feature, the branch goes into `master` — the LAST step of all.**
   The feature closed whole in step 5, and `master` holds closed features only (`CLAUDE.md`,
   *Branches and releases*), so this is the moment it may carry this one:

   ```bash
   git switch master
   git merge --ff-only feature/<NNN>-<feature>
   git branch -d feature/<NNN>-<feature>
   ```

   **A fast-forward, or nothing.** The branch was cut from `master` and `master` does not move
   while a feature is open, so `--ff-only` succeeds; if it refuses, `master` moved underneath —
   an upstream `/merge`, a hotfix merged back — and the feature has to take that first: stop, say
   what `master` gained, and leave the branch standing. The maintainer brings it up to date
   (`/merge` from the branch does exactly that) and runs `/end` again. Never `--no-ff`, never a
   rebase, never a push: the trunk's history is the features' own commits in order, and `master`
   reaching `origin` is the maintainer's act. Report the sha `master` now stands on.
