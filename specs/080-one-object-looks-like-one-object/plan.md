# 080 — plan

## Approach

One task. The write and the undo are two halves of one claim — *a visual write on a gob applies to the
object* — and splitting them would leave the tree in a state where a scale reaches every session and
the undo reaches one, or the reverse. Either half alone is a worse defect than the one being closed,
because it is the same asymmetry pointing the other way.

### What a gob's visual state is

`079.3` settled that a gob is one object: `LuaGob` is keyed on the id, gob ids are the server's, and
every read answers the same whichever session computes it. A `Gob`, though, is per-`OCache` — session
A's copy of tree #123 and session B's are two Java objects, each placed against its own session's map.

So *one object* and *one `Gob`* are different things, and the two visual writes are where that gap
still shows. **The object is what an addon addresses; the copies are what the engine paints.** A write
addressed at the object reaches every copy; an undo of that write reaches every copy.

### The write

`LuaGob`'s `scale` verb resolves one `Gob` through `gob(self, "scale")` and calls
`GobScale.apply(g, owner, k)`. `gob:overlay()` reaches `LuaGobOverlay.on(g)` the same way. Both grow a
walk: resolve that id in **every live session that holds it** and apply there.

`gob:sessions()` — which `079.3` added — is the read that answers who holds it, and it is the natural
source for the walk. It is a live read of the `OCache`s rather than a kept set, which is exactly what
this needs: a session that joined since the addon last touched the gob is included without anything
having been notified.

**A session that does not hold the gob is skipped, not an error.** That is the same *"a state, not a
fault"* the offset machinery already reports and a Position already documents.

### The undo

`UiApi.teardownGobOverlays` and `teardownGobScales` stop reading `screen()` and
`AddonManager.allGobs()`. Each walks every live session: that session's `UI`, that session's
`OCache` through `AddonManager.allGobs(String user)` — **which already exists** — and that session's
own revert.

Then `AddonManager.allGobs()`'s argumentless form has no callers, and **it is deleted**. That is the
discipline `072.3` and `078.4` used and the reason it worked: an ambient accessor left standing is an
invitation to re-introduce the defect, and deleting it hands the watch to the compiler.

### The monitors, one at a time

Today each method takes `synchronized(u)` on one `UI`, and the javadoc says why: teardown *"may run
off the UI thread (session bind) while `ctick` rebuilds the state"*. That reason survives per session.

What must not survive is the shape. `docs/client/multi-session.md` records that **the tick never holds
two UI monitors at once**, and that anchor-then-member is the only direction anything may take. So the
walk is: take one session's monitor, do that session's work, release, move on. **Never nested, and
never a monitor held across another session's walk.** The `u == null` fallback each method has today
becomes per session — a session whose `UI` has gone is skipped, not a reason to run unguarded.

## Files to create and modify

| File | What |
|---|---|
| `src/io/brodgar/addon/LuaGob.java` | `scale` applies across the sessions holding the gob |
| `src/io/brodgar/addon/LuaGobOverlay.java` | `on`/attach reaches every copy, `removeOwner`/`prune` likewise |
| `src/io/brodgar/addon/UiApi.java` | both teardowns walk every live session, one monitor at a time |
| `src/io/brodgar/addon/AddonManager.java` | `allGobs()`'s argumentless form deleted; `allGobs(String)` is the only one |
| `src/io/brodgar/addon/GobScale.java` | `apply`/`revert` are per `Gob` and stay that way — the walk is above them |
| `docs/addons/api/gob.md` | the scale verb, and that the undo covers every session |
| `docs/addons/api/overlay.md` | the same for overlays |
| `specs/ROADMAP.md` | the one line this feature closes |

## Risks and gotchas

- **Two monitors at once is the one way this becomes worse than the defect.** A cosmetic bug traded
  for a deadlock is a bad trade. One session's monitor, one session's walk, release, next.
- **The write and the undo must agree on the same walk.** If `scale` reaches every session and the
  revert reaches those that still hold the gob, a session that dropped the gob in between leaves
  nothing behind — the copy went with it. That is correct and worth a comment, because it looks like a
  leak and is not.
- **`GobScale` records who wrote a size** — its javadoc says a gob scaled by a *different* addon is
  untouched, and a gob has one size, so teardown reverts only what this addon last set. Widening the
  walk must not widen that: per session, still only this addon's.
- **The gob may be gone in one session and present in another.** `LuaGob`'s reads already answer
  `nil` for a gone gob without throwing, and the write already *"takes the write and does nothing with
  it"*. The walk keeps that: a session where it is gone is skipped silently.
- **The teardown runs when the addon is already stopped**, which is what makes its suite awkward and
  why the task's proof is two-phase. It also means the walk cannot call back into Lua.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` first, and especially before believing
  the deletion of `allGobs()`, whose point is that a caller left behind stops compiling.

## Discarded alternatives

- **Fixing only the teardown, as the `ROADMAP` line described** — it would leave the write landing on
  one session's copy, so a gob would still look different depending on which character is looking, and
  the undo would be reverting in sessions where nothing was ever applied. The line named the half that
  was noticed, not the whole defect.
- **Fixing only the write** — then an addon paints every copy and the engine cleans one, which is the
  same defect with more of it to clean up.
- **Keeping a set of the gobs an addon has scaled, so teardown need not walk** — a second copy of what
  `GobScale` already records on each `Gob`, and the one that disagrees is the one nothing reads. The
  walk is at `:reload` and disable, which is a rare moment; the record would be maintained forever to
  save it.
- **Holding every session's monitor for one atomic sweep** — it would make the sweep a single instant
  and break the one rule the session layer's threading argument rests on. Nothing here needs
  atomicity: a scale reverted a frame apart from another is invisible.
- **Leaving `allGobs()` argumentless for convenience** — it is the accessor that caused this, and
  every ambient accessor this sequence deleted was deleted for the same reason: while it exists,
  somebody writes the next caller.
- **Making `gob:scale()` protected instead, since the undo is what justified it being free** — the
  undo is fixable and a permission is not removable once a page has taught it. The guarantee was the
  right design; it was simply not finished.
