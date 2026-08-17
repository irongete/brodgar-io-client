# 071 — plan

## Approach

Three tasks, in the only order that keeps the tree green between them: **hand the ownership over
first, delete the duplicate second, re-say the vocabulary third.** The reverse order — merging the
read layer before the ownership moves — builds a uniformity that the handoff then rebuilds.

`070.2` established the rule this feature works under and must not break: **only `UILoop.th` may
build the `placed()` cache**, `republish()` runs at the end of `Sessions.tick()`, and every other
thread is answered from `Sessions.unpublished` with `placedRebuiltOffTick` counting any ask that
should not have happened. Every task here rewrites code inside that rule, never around it.

### 071.1 — the handoff

`Client.Main.run` is an endless `while(true)` cycling `Bootstrap` → `RemoteUI` → `Bootstrap`, each
step through `UILoop.newui(fun)`, which **replaces `UILoop.ui` and destroys the previous UI**. That
slot is the whole asymmetry: a session living in it is owned by the client's own runner chain rather
than by `Sessions`.

After this task the slot holds the **login** UI and never a game session. When `Main`'s chain reaches
a `RemoteUI`, `Sessions` adopts it — the same construction `Member.start` already performs through
`UILoop.bgui`, which replaces nothing and destroys nothing, on its own `HackThread`. `Main` then
returns to `Bootstrap` and waits, exactly as it does after a logout today.

`UILoop.drawn()` stops falling back to a game session: it answers whichever session holds the screen,
and `UILoop.ui` — the login screen — when there is none. `UILoop.run`'s second tick call,
`Sessions.tickbg(main)`, is deleted with the concept it served; `Sessions.tick()` covers everyone.
`Sessions.reclaim()` loses its `an == mu` early return and hands a dead anchor's screen to another
live session, or to the login slot when it was the last.

**The `ui`/`drawui` split stays, and its stated reason survives intact**: `UILoop`'s javadoc says two
fields exist because *"destroying the runner's UI must never be able to destroy a session it does not
own"*. That is still true and is now stronger — the runner's UI is the login screen, which owns no
session at all, so `newui`'s destroy can never reach one.

### 071.2 — delete what the handoff made dead

With every session a `Member`, the second copy is unreferenced rather than merely redundant. Delete:
`mainoff`, `mainoffanchor`, `mainoffglob`, `mainofftry`, `tickmainoffset()`, `mainguiof()`,
`mainguifor`, `mainguicache`. `Sessions.dormant(Glob)` loses its `mainui()` branch and keeps its
loop. `buildplaced()` loses its main-session branch and becomes one loop over `members`, so
`Placed.member` is never null and `Placed.user` is always `Member.user` — the literal `"main"` is
gone from the class.

`Sessions.mainui()` survives only if something still needs the login UI by name; if nothing does, it
goes with the rest.

### 071.3 — the vocabulary, and the pages

`:session anchor` takes an account name and drops the `main` keyword. `:session drop <account>`
reaches any session; dropping the last leaves the client on the login screen rather than drawing
nothing. `Sessions.add` refuses an account already live, which after 071.1 means checking one list
rather than one list plus a slot. `Sessions.next()` cycles a flat list with no distinguished first
stop, and `Control.take(null)` — which meant "the main one" — is re-spelled or retired.

`docs/client/multi-session.md` is revised in place; `docs/client/boot-and-loop.md` gains the new
shape of the runner state machine, which is upstream `haven` and belongs there.

### The observation channel

`hafen.client():profiling():session()` exists from `070` with `groundAnswered`, `groundMissed` and
`placedRebuiltOffTick`. This feature adds **`live`** — how many sessions the client currently holds —
which is what makes every acceptance criterion about the *set* readable from Lua rather than by eye.

`live` is a gauge, not a cumulative total, so `counters.md`'s sentence that `session()`'s counters are
*"cumulative since the client started"* is corrected in the task that adds it. The pattern the suites
use throughout: the maintainer performs the console gesture, the suite reads the number that gesture
should have moved.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `src/haven/Client.java` | 1, 3 | `Main.run` hands its `RemoteUI` to `Sessions`; the `:session` console verbs |
| `src/haven/UILoop.java` | 1 | `drawn()` no longer falls back to a game session; `tickbg` call deleted |
| `src/io/brodgar/session/Sessions.java` | 1, 2, 3 | adoption, `reclaim`, `tickbg` deleted; the duplicate machinery deleted; `add`/`drop`/`next` |
| `src/io/brodgar/session/Control.java` | 3 | `take(null)`'s meaning |
| `src/io/brodgar/session/SessionWnd.java` | 3 | the switcher's rows and its "main" button |
| `src/io/brodgar/addon/ProfHandle.java` | 1 | `live` in the `session()` group |
| `docs/addons/api/client/profiling/counters.md` | 1, 3 | `live`, and the "cumulative" sentence |
| `docs/client/boot-and-loop.md` | 1 | the runner state machine's new shape — 86 lines, room under the 150 ceiling |
| `docs/client/multi-session.md` | 3 | revised in place; **174 lines today, over its own ceiling**, and this feature deletes rows |
| `addons/071-no-main-session.1/`, `.2/`, `.3/` | | the suites |

## Risks and gotchas

- **`Client.run`'s outer loop ends the client.** `run(UI.Runner task)` is
  `while(task != null) task = task.run(newui(task))` with `finally { newui(null); }` — when it falls
  out, the client exits. `Main.run` never returns today, and must go on never returning after the
  handoff, or dropping a session closes the program.
- **`newui` destroys the previous UI and waits on `uilock`** for a frame that has it locked. The
  handoff must not route a session's UI through `newui`; `bgui` is the constructor that exists for
  exactly this and takes no lock.
- **`UI`'s constructor runs `Runner.init`**, which is where `RemoteUI.init` binds `ui.sess` and asks
  `Sessions.ismember(sess)`. A session must be registered **before** its `UI` is built, as
  `Sessions.add` already does and documents — otherwise the first widgets arrive while the answer is
  still wrong.
- **`Sessions.anchor(Member)` leaves the cache unpublished until the next tick**, on purpose
  (`070.2`). Anything reading `placed()` between an anchor switch and the next frame is answered
  "nothing", off-tick. Do not add a caller in that window.
- **`GameUI.onscreen` is `ui == Sessions.anchor()`** and gates three window-geometry writes. With no
  main session it must still be true for exactly one session and false for the login screen.
- **`Bootstrap.gettoken` and `savedusers()`** name accounts the login screen saved. The adopted
  session arrived through the login screen rather than a token, so its `Member.user` has to be taken
  from the authenticated account (`Session.User`), not invented — criterion 2 rests on it.
- **A clean build is required.** `ant hafen-client` is incremental and hides a symbol that moved
  between files; `rm -rf build/classes` first.

## Discarded alternatives

- **Splitting `docs/client/multi-session.md` per `DOCUMENTATION.md` §12.3, as this feature's spec
  recommended before the page sizes were read** — the natural destination for its merged-scene and
  pick-pass rows is `world-3d.md`, which is **151 lines against a 150 ceiling**. Moving content into
  a page that is already over its own limit relocates the violation instead of ending it. The page is
  revised in place, `ROADMAP.md`'s line stays standing, and this feature aims at the half it *can*
  close: `multi-session.md` is 174 lines and the asymmetry being deleted takes rows with it.
- **Merging the read layer first and moving ownership afterwards** — `placed()` would be made uniform
  over a main session that is still owned by the runner chain, which means a `Member` with no thread
  and a `drop()` that cannot work: a fake uniformity that the handoff then rebuilds.
- **Keeping `UILoop.ui` as a game session and registering it in `members` as well** — two owners for
  one UI, which is the precise ambiguity the `ui`/`drawui` split was introduced to prevent.
- **Enforcing "the client cannot close its last session" as a rule in `drop`** — there is nothing to
  enforce. Closing the last session returns you to the login screen, which is what logging out
  already does, so the rule is a consequence of the design rather than a guard bolted onto it.
- **Claiming the login dialog for a second account here** — it falls out of the handoff almost for
  free, and it is a new way to *create* a session rather than a statement about sessions being equal.
  A feature that grows a door while it is moving a wall cannot say which of the two broke the build.
- **Proving `Placed.user` from Lua** — nothing reads `Placed` until `hafen.session()` exists, so the
  proof is the maintainer reading `:session list` and pasting it back. Inventing a counter that
  encodes a string would be a test surface pretending to be a diagnostic.
