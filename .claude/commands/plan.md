# /plan — plan a feature

Usage: `/plan <feature description>`

Plan ONE feature as a new `specs/NNN-<feature>/` folder, on a branch of its own. **/plan implements
NOTHING and commits NOTHING** — it cuts the branch, writes the three files and stops for review.
They reach the repo with the feature's first `/end`.

## 0. The branch — before anything is read or written

A feature is built on `feature/<NNN>-<feature>`, cut from `master` (`CLAUDE.md`, *Branches and
releases*). Two checks, then the cut:

```bash
git rev-parse --abbrev-ref HEAD     # must be master
git status --porcelain              # must be empty
```

- **Not on `master`** — a feature still open on its branch, or anything else — **stops the command**:
  say where you are and stop. A feature branch cut from another feature's branch would carry that
  feature's half-built work, and `master` is the only place where every feature is closed.
- **A dirty tree stops it too**: the three files would be written beside work that is not theirs.
  Stashing or committing it is the maintainer's call.
- The number is the next free `NNN` in `specs/` (§2); the name is the folder's. Then:

```bash
git switch -c feature/<NNN>-<feature>
```

The branch exists from here and holds nothing until the first `/end`; everything below is written on
it, uncommitted.

## 1. Read — in this order, and nothing else

1. **`docs/addons/**` — the pages of every surface this feature touches.** For anything that
   already ships, **the shipped reference IS the contract and the only current model.** There is no
   prose summary of it anywhere; do not go looking for one.
2. **`specs/ROADMAP.md`** — the maintainer's own long-term queue, and the one file a command reads
   and never writes. Is this ground already stated there? Take what it says as input to the scope;
   the lines are struck by hand, by the maintainer, when they choose.
3. **`docs/client/`** — `ls` it, then the 1–2 subsystem pages this feature touches. That is the map
   of upstream `haven`, and it is where you start rather than in `src/`.
4. **The source**, where the feature needs engine behaviour neither tier states. `grep` `src/` for
   the seam; read the class, not the tree. Then, **if no `docs/client/` page covered what you had
   to read, `plan.md` MUST list that page under "files to create/modify"** — the reading is paid
   once, by the task that pays it, and the next feature finds it in `docs/client/`.
5. **Prior art — only if this feature revisits settled ground.**
   `grep -rln "<topic>" specs/[0-9]*/plan.md` finds the *Discarded alternatives* of the feature that
   decided it. Everything in a `NNN-` folder is history by construction: it says what was rejected
   and why, never what is in force. **What is in force is `docs/` and `src/`.**
6. **`DOCUMENTATION.md`**, if the feature writes pages — which it does.

## 2. Write `specs/NNN-<feature>/` — the next free number

**`spec.md`** (MAX 900 words):

- **What & why** · **acceptance criteria**, each verifiable in-game through the task's own suite ·
  **out of scope**.
- **Scope it so the surface it ships is WHOLE when it closes.** *Out of scope* is a **boundary,
  never a remainder**: the place where this feature's surface ends and another's begins, drawn so
  that everything inside stands on its own. A write whose undo comes later, a read that answers for
  one of its two callers, half of a pair that has to round-trip — those are not out of scope, they
  are the feature unfinished, and the close refuses them. If only half of the ground fits, draw the
  boundary where that half is itself coherent, and say in one line what the other half would be.
- **`Docs impact:`** the pages that will be written, **plus the derived impact set** — grep the
  prose names of this surface across the whole of `docs/` and write the command *and its result*.
  *This exists because a feature documents the pages it opens, while the stale sentence sits in a
  page it never opened, written in the negative, invisible to every grep aimed at the new syntax.*
- **`Context files:`** the exact files `/implement` may load, **each tagged with the tasks that need
  it** — `src/io/brodgar/addon/Manifest.java — 1, 2`. The list is the budget and the promise that it
  is enough; the tags are what keep a docs-only task from loading twelve Java files it will never
  open. An untagged line is read by every task, so tag everything that is not.

**→ STOP HERE and get the maintainer's approval.** Only then write:

**`plan.md`** (MAX 1200 words):

- **Approach** · **files to create/modify**, `docs/` pages included · **risks & gotchas**, **naming
  the classes and members you read in `src/`**, so the implementing context knows where to look
  instead of searching for the seam again.
- **`Discarded alternatives:`** one line each, **with the reason it was rejected.** *This is the
  feature's decision record and the only one. Write the reason so it still argues its case years
  later: name the thing, not a number ("a per-target listener is not the hot path a global hook
  would be").*

**`tasks.md`** (MAX 900 words) — a checklist `NNN.1`, `NNN.2`, … **One task = one session**,
self-contained and verifiable on its own. **Every acceptance criterion in `spec.md` is named by at
least one task**: a criterion no task claims is a decomposition that is not finished, and the
feature's last close is far too late to find it.

> **These three files are everything that crosses a context boundary.** `/plan` writes them; a
> fresh `/implement` reads them, builds, verifies with the maintainer and closes — one unbroken
> context, and no second pass to rescue a thin line. Write each task for a reader who has never seen
> this conversation, because they have not.

A task carries the parts the template shows, and **the suite is designed here, not there**: naming
the assertion is what proves the task was decomposed at all. **~170 words each is the working density**; the budget
holds four or five.

```markdown
- [ ] **NNN.1 — <the title, which becomes the commit subject>.** <What it changes, in the
      vocabulary of the thing itself: the names it adds, the sites it touches, what is retired
      with it.>
      *Its suite* <what it declares and drives, and why THAT proves the claim — the assertion,
      never the subject. A refusal it must raise, and the text that refusal has to name.>
      `[manual]`: <only what a program cannot observe, worded as the maintainer will read it back.>
      <!-- extra context: <a file this task alone needs, beyond the spec's tagged list> -->
```

If it does not fit the limits, do NOT stretch them: propose splitting the feature.

**A task may build a fourth file in the folder** — a census, an audit, an inventory — **when a later
task of the same feature works from it and it edits nothing else.** Name it in the task that builds
it and in the tasks that read it. It freezes with the folder, like everything else there.

## 3. Register and stop

- **Write no file but the three.** Where this feature's scope covers ground the maintainer's
  `specs/ROADMAP.md` already states, **name those lines in the report** — striking them is theirs.
- **Write nothing else** — the three files, and nothing that records state. The feature is *active*
  because its `tasks.md` has unchecked boxes, which is derived — and because its branch is the one
  checked out, which is derived too.
- **STOP for review, and do not commit.** Report the branch cut, the files written and the
  `ROADMAP.md` lines this scope covers. Stay in the review loop as long as it takes. A plan the
  review drops leaves whole: `git switch master && git branch -D feature/<NNN>-<feature>`, and the
  folder deleted by hand — untracked, it does not follow the branch. Say so rather than doing it,
  since the maintainer decides.
- **Every decision the review reaches goes back into the three files before you hand over** — the
  approach into `plan.md`, what the maintainer rejected into *Discarded alternatives* with its
  reason, the scope into `spec.md`. The conversation ends here and `/implement` reads the files and
  nothing else, so anything only said out loud was not said.
