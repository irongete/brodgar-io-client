# /merge — sync upstream `haven` into our work

Usage: `/merge` → survey, then the two hops. `/merge --survey` → the survey and nothing else, no
branch touched, no merge started.

Upstream is `dolda2000/hafen-client`, fetched as the remote `upstream`. `/merge` brings it forward in
two hops — upstream → the mainline → the branch you are standing on — and its whole job is that our
client still does everything it did before, with upstream's new work underneath.

## The rules that do not bend

Everything else on this page is the reasoning behind these. Read them once here, and again if you
find yourself about to take a shortcut in the middle of a hop.

1. **Never `git push`.** Nothing here leaves the machine, which is also why every step is reversible.
2. **Never type a branch name.** All three are derived in §0 and reported before anything is merged.
   A name typed from memory is how a sync lands somewhere nobody asked for.
3. **Stop at §3 for the maintainer's approval** before the first merge.
4. **Nothing commits before the build is green** (§8). `--no-commit` on both hops is not optional,
   whether or not a conflict was raised.
5. **No `--ours`, `--theirs`, `-X ours`, or "accept incoming"** on any file that carries both sides.
   That is how a hook disappears silently, and silent disappearance is what this command exists to
   prevent.
6. **Never leave `MERGE_HEAD` in the tree at the end of a turn.** A half-merged repository blocks
   every other command, and the next context cannot know what was already resolved.
7. **Only the merge is committed** — the resolution of the conflicts it raised, and nothing else. No
   checked boxes, no `spec.md`, no file in flight.
8. **Never rebase.** A work branch here is hundreds of commits long and already published to
   `origin`; a rebase rewrites all of it and replays every conflict once per commit. The fork has
   always taken upstream by merge, and it keeps taking it by merge.

Scratch files go in `/tmp`, never in the repo — a stray `up.txt` trips §1's own cleanliness gate and
can end up inside the merge commit.

## 0. Preflight, then derive the three names

Fetch first, because two of the three names do not exist until the fetch has run.

```bash
git remote get-url upstream          # must exist, and must be dolda2000/hafen-client
git --version                        # must be >= 2.38 for merge-tree --write-tree (§3)
git fetch upstream --prune && git fetch origin --prune && git remote set-head upstream --auto
```

If the `upstream` remote is missing, stop and say so — do not add it yourself. If git is older than
2.38, `merge-tree --write-tree` does not exist, and the survey that the maintainer's approval rests
on cannot be produced; stop and report that instead of merging blind.

Then the names:

```bash
git rev-parse --abbrev-ref HEAD                      # $BR   — the working branch
git symbolic-ref --short refs/remotes/origin/HEAD    # $MAIN — as origin/<name>; drop the remote
git symbolic-ref --short refs/remotes/upstream/HEAD  # $UP   — upstream's default branch
```

- `$BR` — where you are standing. The target of hop 2, and where `/merge` must end.
- `$MAIN` — the fork's own default branch, where upstream's work lands first.
- `$UP` — upstream's default branch. `set-head --auto` above is what populates it; that write is
  local and reversible.

Either symbolic ref may be missing on a fresh clone. Ask the maintainer which branch it is — never
guess, and never fall back to a name this page could have spelled.

From here on this page writes `$BR`, `$MAIN` and `$UP` for those three. **Substitute the literal
names you derived**: a shell variable does not survive from one command to the next, and a half-set
variable makes `git switch ""` do something you did not intend.

Last, confirm the local mainline is not behind its remote:

```bash
git rev-parse $MAIN origin/$MAIN
```

Two different shas means hop 1 would merge onto a stale base and hop 2 would carry the staleness into
the working branch. If `$MAIN` is strictly behind, fast-forwarding it is safe and you may say so and
do it; if the two have diverged, stop — that is the maintainer's to untangle.

## 1. Gates, escape hatch, baseline

```bash
git status --porcelain                           # any output at all
git symbolic-ref -q HEAD                         # empty means detached
git merge-base --is-ancestor $MAIN HEAD
```

- **A detached HEAD stops everything, survey included.** There is no `$BR` to derive, and a merge
  there commits onto nothing.
- **A dirty tree stops the merge**, from §4 onward. A merge over one mixes the maintainer's in-flight
  work — a task `/implement` has open, an edit of their own — into a conflict resolution, and nothing
  afterwards can tell the two apart. Report exactly what is dirty and stop. Stashing or committing it
  is the maintainer's call, never yours. `--survey` reads only, so it may proceed over a dirty tree;
  say in the report that it did.
- **A working branch that does not descend from `$MAIN` stops the merge.** That ancestry is what
  makes hop 2 a sync of new upstream work rather than a first merge of two histories. A branch cut
  straight from `upstream` needs a plan, not this command. Under `--survey`, report it at the top of
  the findings and continue, so the maintainer sees the size of what they are dealing with.

Record the escape hatch, and report it — it is the answer to "what if this goes wrong":

```bash
git rev-parse $MAIN HEAD
```

Mid-merge, `git merge --abort` puts the tree back untouched. After a merge commit,
`git reset --hard <the matching sha above>` does. Nothing is pushed, so nothing is beyond either.

Then take the hook census baselines, **now, before anything is merged** — one per hop, because each
hop has its own starting point and comparing hop 1 against `$BR`'s numbers invents losses that are
not there. Taken against the refs rather than the working tree, so they hold even mid-merge:

```bash
git grep -c "io\.brodgar" $MAIN -- 'src/haven/*.java' | cut -d: -f2- | sort > /tmp/hooks-main-before.txt
git grep -c "// addon:"   $MAIN -- 'src/haven/*.java' | cut -d: -f2- | sort > /tmp/addon-main-before.txt
git grep -c "io\.brodgar" $BR   -- 'src/haven/*.java' | cut -d: -f2- | sort > /tmp/hooks-br-before.txt
git grep -c "// addon:"   $BR   -- 'src/haven/*.java' | cut -d: -f2- | sort > /tmp/addon-br-before.txt
```

(git's pathspec wildcards cross `/`, so `src/haven/*.java` is the whole tree, not one directory.)

Two patterns, because they catch different losses. The first is every point in the engine that
reaches into our code, and it needs no convention to hold. The second is `CLAUDE.md`'s tag, which
also covers edits that add an anchor without calling anything — **if the branch you are on marks its
core edits some other way, take the marker from the branch**, by reading one of the files §3 lists.

## 2. What upstream brings

```bash
git log --format='%ad  %s' --date=short $MAIN..$UP
git diff --stat $MAIN...$UP
git diff --name-only $MAIN...$UP | sed 's#/[^/]*$##' | sort | uniq -c | sort -rn | head -20
git log --diff-filter=DR --name-status $MAIN..$UP
```

No commits → upstream has nothing new; say so and stop.

Otherwise write the **upstream summary**, and write it from those four outputs rather than from a
sense of what upstream tends to do:

- **Volume**: commit count, date span, files changed, lines in and out.
- **Where the work is**: the directory histogram, turned into named subsystems. Three or four
  groupings is the right size — "resource loading and caching", "the map renderer", "gob/sprite
  handling" — each with roughly how many commits and files sit under it.
- **What actually changed in each**, one or two lines per grouping, read off the commit subjects.
  Distinguish new capability from refactoring: a refactor that moves symbols between files is
  quieter in the log and far more dangerous to us than a new feature (§7).
- **Deletions and renames**, listed individually. They are few, and each one is a place where
  something of ours may have been standing.

This summary is written whether or not anything collides, and it goes in the report both under
`--survey` and at the end of a full run. It is the maintainer's answer to "what did I just take".

## 3. Survey the collision set — then STOP for approval

A conflict happens where upstream changed a file we also changed. That set is computable before a
single merge runs, and it is the whole of what the maintainer is being asked to approve.

```bash
git diff --name-only $MAIN...$UP   | sort > /tmp/up.txt
git diff --name-only $MAIN...HEAD  | sort > /tmp/ours.txt
comm -12 /tmp/up.txt /tmp/ours.txt
```

The first list is what upstream touched, the second what this branch touched, `comm -12` where they
meet. Then let git resolve it for real, **without touching the working tree** — `merge-tree` prints
the conflicts a merge would raise and changes nothing:

```bash
git merge-tree --write-tree --name-only $MAIN $UP
git merge-tree --write-tree --name-only HEAD $UP
```

The first is hop 1 exactly. The second is hop 2 approximated — against `$UP` directly, because the
`$MAIN` that hop 2 will actually merge does not exist yet, and previewing against today's `$MAIN`
reports nothing at all (it is already an ancestor). Close enough to size the job, and re-run the real
one — `git merge-tree --write-tree --name-only HEAD $MAIN` — between the hops, reporting the
difference if the set grew.

### The impact map — what this lands on, in our own code

The collision set above is only the half git can see. The other half is §7's: upstream changes a
signature in `src/haven/`, our caller sits in a file upstream never touched, and the merge is clean
right up until the compiler disagrees. Both halves are estimated here, before approval.

First, derive our trees — never recite them:

```bash
git diff --name-only $MAIN...$BR | sed 's#/[^/]*$##' | sort -u    # minus src/haven and root files
```

**Direct — engine files that carry our hooks and that upstream churned.** §1 already listed every
`src/haven` file our code reaches into; intersect it with what upstream touched:

```bash
git diff --name-only $MAIN...$UP -- 'src/haven/*.java' | sort > /tmp/up-haven.txt
cut -d: -f1 /tmp/hooks-br-before.txt | sort -u > /tmp/hooked-files.txt
cut -d: -f1 /tmp/addon-br-before.txt | sort -u >> /tmp/hooked-files.txt
sort -u /tmp/hooked-files.txt -o /tmp/hooked-files.txt
comm -12 /tmp/up-haven.txt /tmp/hooked-files.txt
```

Every file here is a hook sitting in churned ground. These are the ones §8's census is most likely to
find missing, and the ones to name in the in-game checklist.

**Indirect — our code that references what upstream changed.** Crude by necessity, and deliberately
wide: a false positive costs a sentence, a false negative costs a silent breakage.

```bash
git diff --name-only $MAIN...$UP -- 'src/haven/*.java' \
  | sed 's#.*/##; s#\.java$##' | sort -u > /tmp/up-classes.txt
grep -rlFf /tmp/up-classes.txt <our trees> --include=*.java | sort
```

Then sharpen it, because the raw list will be dominated by ambient vocabulary — every file mentions
`Coord` or `Widget`. What matters is whether upstream changed a *member*, not merely a file:

```bash
git diff $MAIN...$UP -- 'src/haven/*.java' | grep -E '^[-+].*(public|protected|static).*\(' | sort -u
```

Cross those changed declarations against our trees by name. A class of ours that only *mentions* a
churned class is background noise; one that calls a method whose signature moved is a compile break
waiting for §8, and should be named now so the maintainer is not surprised by it later.

Report the map as **our subsystem ← what upstream did to it**, in our vocabulary, not upstream's: the
maintainer knows their own tree by what it does, not by which `haven` class it happens to extend.

### Then report, grouped and in plain words

- **The upstream summary from §2**, in full.
- **The impact map** — direct hooks in churned files, then indirect references, each as a named
  subsystem of ours with the upstream change that reaches it. Say plainly which entries are
  speculative: the indirect half is a grep, not a compiler.
- **How many files collide, and which subsystems they are** — named from the paths, not from a guess
  about what the branch contains.
- **The three that are never routine**, found here rather than in the middle of the merge: a file
  upstream deleted or renamed that we edit, a `build.xml` change, and any collision under
  `src/haven/res/` (adopted resource code — see §6).
- **Our subsystems upstream does not touch at all.** Short and worth saying — it is how the
  maintainer knows what they do not have to re-check.
- **The verdict**: routine, or "this one needs the maintainer beside it".

**→ STOP HERE and get the maintainer's approval before the first merge.** `--survey` stops here for
good.

## 4. Hop 1 — `$UP` → `$MAIN`

```bash
git switch $MAIN && git -c merge.conflictstyle=zdiff3 merge --no-commit --no-ff $UP
```

`zdiff3` puts the merge base in every conflict block. That is what tells you which side actually
changed the line — without it you are guessing which half is ours.

What `$MAIN` carries of its own is read, not assumed: `git log --oneline $UP..$MAIN` and
`git diff --name-only $UP...$MAIN` say it exactly. Whatever it is, a conflict here is one of those
edits and is resolved by §6 like any other. Verify (§8) against the `$MAIN` baselines, commit, then
hop 2.

If `$BR` is `$MAIN`, the command ends here — there is no hop 2, and the report is §9 with the second
hop struck.

## 5. Hop 2 — `$MAIN` → `$BR`

```bash
git switch $BR && git -c merge.conflictstyle=zdiff3 merge --no-commit --no-ff $MAIN
```

**Switch back by the name §0 recorded**, never by `-`, and never to a branch you assumed. This hop is
the one that matters: it is where the working branch's own work lives, and
`git diff --name-only $MAIN...$BR` is the list of exactly what that is.

Taking upstream through `$MAIN` rather than straight from `$UP` is deliberate, and it is what makes
the command work from any branch: whatever `$MAIN` carries of its own is reconciled once, in hop 1,
on a branch where it is the only thing in the file, and every work branch then merges the same
`$MAIN` — the same resolution, not one guess per branch.

`/merge` syncs the branch you are on and no other. Its siblings stay behind until they are themselves
stood on; that is the maintainer's timing, not yours.

## 6. How a conflict is resolved

**A conflict is read, not picked** (rule 5). The resolution rule is one sentence: upstream owns the
engine's behaviour, we own the hook. Take upstream's version of the method in full — their refactor,
their renamed local, their new argument — and then re-apply our edit on top of it, at the point that
still means what it meant. If upstream moved the call our hook rode on, the hook moves with it. If
upstream deleted it outright, the hook is re-homed, not dropped, and where it cannot be, that is a
finding for the report (§9), never a quiet deletion.

By owner:

- **`src/haven/` — shared.** Core edits of ours live there under `CLAUDE.md`'s rule: minimal,
  centralized, and tagged `// addon:`. That tag is the contract — it marks the line that must
  survive, and §8 counts it. Keep it on the line it moves to.
- **Everything the working branch adds on top of `$MAIN` — ours alone.** Derive the trees, never
  recite them: `git diff --name-only $MAIN...$BR | sed 's#/[^/]*$##' | sort -u`, minus `src/haven`
  and minus the shared root files the last bullet names. Upstream cannot touch what is left and it
  cannot conflict. If one does, stop and report it — a path collided by accident, and guessing there
  is worse than asking.
- **New files from upstream — theirs alone.** Taken as they come, no reading needed.
- **`src/haven/res/` — adopted resource code, `@FromResource` and version-pinned.** If upstream now
  ships a class we adopted, compare the pinned version and keep the newer, then say in the report
  which one won and what the version was. Never merge two copies of published resource code line by
  line.
- **`build.xml`, `.gitignore`, `README.md` — shared, and quietly load-bearing.** Ours carry the
  dependency classpath and the packaging the client is actually run from. Take upstream's targets,
  re-apply ours, and read the result end to end: a build file that merges cleanly and drops a
  `<pathelement>` compiles nothing.

Resolve every file before building. `git diff --name-only --diff-filter=U` must come back empty, and
a grep for conflict markers over `src/` must find nothing. To start one file over:
`git checkout --conflict=merge -- <path>`.

If a merge has to be abandoned and retried, `git config rerere.enabled true` makes git replay the
resolutions already made — worth asking the maintainer for once, before a large hop.

## 7. What a clean merge still breaks

**The dangerous conflicts are the ones git does not raise.** Upstream changes a method signature in
`src/haven/`; our caller sits in `src/io/brodgar/`, a file upstream never touched, so the merge is
clean and the code no longer compiles — or worse, still compiles and means something else. Git merges
text; it does not know our two trees are one program.

Only the build and the client find these. That is why §8 is not a formality, and why "the merge had
no conflicts" is never a report on its own.

## 8. Verify — before the commit of each hop, in this order

1. **A true compile, from scratch.** An incremental build hides a symbol that moved between files,
   and upstream moving symbols between files is the whole of what a sync is:

   ```bash
   rm -rf build/classes && ant hafen-client
   ```

   `BUILD SUCCESSFUL`, or this merge does not land. Java is still source/target 1.8.

2. **The hook census**, against §1's baseline for *this* hop — `/tmp/*-main-before.txt` for hop 1,
   `/tmp/*-br-before.txt` for hop 2. Taken on the working tree, mid-merge:

   ```bash
   grep -rc "io\.brodgar" src/haven --include=*.java | grep -v ':0$' | sort > /tmp/hooks-after.txt
   grep -rc "// addon:"   src/haven --include=*.java | grep -v ':0$' | sort > /tmp/addon-after.txt
   diff /tmp/hooks-<hop>-before.txt /tmp/hooks-after.txt
   diff /tmp/addon-<hop>-before.txt /tmp/addon-after.txt
   ```

   Every file whose count dropped is either a hook you deliberately re-homed — say where it went — or
   a hook the merge ate. There is no third case, and **a count you cannot explain stops the commit.**

3. **Our side is untouched where it should be.** Against §1's pre-merge sha for this hop, over the
   trees §6 derived as ours alone:

   ```bash
   git diff --stat <pre-merge-sha> HEAD -- <those trees>
   ```

   Empty is the expected answer. A tree that `$MAIN` also carries may legitimately move, because hop
   1 can carry it — `git diff --name-only $UP...$MAIN` already named those. Anything else appearing
   here is a resolution you owe an explanation for.

4. **The map, where upstream moved the ground under it.** A `docs/client/` page that now states
   something false about `src/` is corrected here, in this merge — the merge is the task that found
   it, `src/` always wins, and the page is written to `DOCUMENTATION.md` §12. Only pages whose named
   classes or members this merge actually changed, and **this is the only file outside the conflict
   set that `/merge` may write.** Anything larger is a finding for §9.

5. **Then, and only then, commit.** Write the message to a file first — a bare `git commit` opens an
   editor that has no one sitting at it:

   ```bash
   git commit -F /tmp/merge-msg
   ```

   Subject `Merge upstream dolda2000/hafen-client into $MAIN` for hop 1, `Merge branch '$MAIN' into
   $BR` for hop 2 — with the real names in it. The body lists every conflicted file and how it was
   resolved, one line each, plus every re-homed hook. End with a
   `Co-Authored-By: <the model actually running this> <noreply@anthropic.com>` trailer; if you cannot
   name it, `Claude <noreply@anthropic.com>`.

If the build cannot be made green, `git merge --abort` and report (rule 6).

## 9. Report, and hand the client over

The build passing means it compiles, not that it works. In-game verification is the maintainer's, as
everywhere else. Report:

- **Which branch you ended on**, and that it is the one §0 recorded. `/merge` never leaves the
  maintainer standing somewhere they did not start.
- **What came in** — §2's upstream summary, unchanged. The merge does not alter what upstream wrote,
  so this is the same text the survey produced; repeat it rather than compress it to a number, so the
  report stands on its own without the survey beside it.
- **What it landed on, in our code** — §3's impact map, now resolved from estimate to fact. Every
  entry gets an outcome:
  - *collided and was resolved* — with how, one line;
  - *predicted indirect impact, confirmed* — the compile broke or the census dropped, and what you
    did about it;
  - *predicted indirect impact, cleared* — it compiled untouched, so the reference was ambient after
    all;
  - *unpredicted* — it did not appear in the survey and surfaced in §8. **List these separately and
    prominently.** They are the map's misses, they are what the maintainer's in-game pass exists to
    catch, and a merge that produced several is a signal the survey's heuristics need widening.
  - Close with the subsystems of ours that came through with nothing touched.
- **Every conflict and its resolution**, one line each; every re-homed hook, with where it went and
  why that point still means what it meant.
- **What to look at in-game.** `ant run`, then — named specifically — the surfaces whose upstream
  churn landed on our hooks, plus whatever the working branch's own work rides on. The maintainer
  cannot re-run every archived suite, so name the two or three things this merge actually put at risk
  and say what "still right" looks like for each.
- **That the siblings are still behind**, if any exist — they take the same `$MAIN` when the
  maintainer stands on them.
- **Findings, routed as `CLAUDE.md` routes them.** A trap upstream just introduced is a gotcha on
  that subsystem's `docs/client/` page — written here. A hook that can no longer be re-homed, or a
  surface of ours upstream has now made redundant, is one sentence for the maintainer to queue;
  `/merge` does not open a feature and never writes `specs/ROADMAP.md`.

Then stop. A merge that needs a follow-up fix in our own code is a task, and tasks go through `/plan`
and `/implement`.