# 070 — plan

## Approach

Two tasks. The first corrects `Sessions.groundz` and gives the session layer the counter group both
tasks read; the second closes the render-thread race and writes the one line of the engine map that
would have made it visible. Neither adds a `hafen.*` verb.

The shape of the whole feature is set by one problem: **all three defects live in code no addon can
call.** `Sessions` is not an API surface, `groundz` is reached only from a camera callback, and a data
race is not observable on demand. So before deciding what to fix, decide what will *prove* it — and
that decision is the counter group.

### The observation channel: `p:session()`

`ProfHandle` already exposes pull-only counter groups by subsystem — `:memory()`, `:net()`,
`:loader()`, `:render()` — and its own comment records that some of them are written off the UI
thread and may be a frame or a packet stale, *"both by design"*. A session group belongs beside them:
how often the layer failed to answer a ground query, and how often a cache was rebuilt somewhere it
should not have been, are ordinary diagnostics for a client holding several sessions, not scaffolding
for a test.

Two counters, and each is exactly one acceptance criterion turned into a number a suite reads:

| Counter | Meaning |
|---|---|
| `groundAnswered` / `groundMissed` | `Sessions.groundz` found a session with that ground, or fell through to its `dflt` |
| `placedRebuiltOffTick` | `placed()` rebuilt its cache on a thread that is not the UI tick — **must stay 0** |

`placedRebuiltOffTick` is the honest form of the assertion. It does not claim the race is gone; it
claims the render thread no longer reaches the code that races, which is the thing the fix actually
does and the thing a program can check.

### D1 — `groundz` through `placed()`

`Sessions.groundz(Coord2d anchorpos, double dflt)` iterates `members` and reads `Member.offset()`
raw. Both halves are wrong and `Sessions.placed()` already fixes both: it includes the main session
(with `mainoff`, maintained by `tickmainoffset`) and it applies `isanchor ? Coord2d.of(0, 0) :
offset` when it builds each `Placed`. The fix is to iterate `placed()` and use `Placed.offset` and
`Placed.glob.map`, skipping the anchor's own entry — the caller only reaches `groundz` *because* the
anchor's map threw `Loading`.

`Loading` must go on being caught per session, not once around the loop: one session lacking that
ground is the normal case and must not stop the next from answering.

### D2 — the cache, and who may build it

The three fields are `Sessions.placedcache`, `Sessions.mainguifor` and `Sessions.mainguicache`, all
plain statics. The render thread arrives through `MapView.checkmapclick` → `Sessions.offsetfor(MCache)`
→ `Sessions.placed()`, inside the callback of `out.pget(...)` / `clmaplist.get(...)`.

**The render thread must not rebuild.** It reads the published cache, and answers `null` when there is
none — which `offsetfor` already returns for a map it does not recognise, so its contract does not
change and `checkmapclick` already handles it (`if(off != null)`). Publication is `volatile` on
`placedcache`; `buildplaced()` stays UI-thread-only and `mainguiof()` with it.

The reason it is not the other way round: `buildplaced()` runs `Widget.findchild`, a recursive walk of
a widget tree another thread is mutating, and the only lock direction this layer permits is anchor
then member (`Sessions.flushsay`'s comment states it, and `docs/client/multi-session.md` records it as
load-bearing). A render callback taking a UI monitor is a new deadlock class, not a fix.

The cost is real and belongs in the code: a click resolved in the first frames after `invalidate()`
may find no cache and lose its offset. `invalidate()` runs at the top of `Sessions.tick()` and again
in `Sessions.anchor()`, and `placed()` is rebuilt within the same tick, so the window is sub-frame and
only ever costs a merged-ground click its translation — never a wrong one, because `null` means *not a
merged cut* and the anchor's own frame is the fallback.

### D3 — the comment

`RemoteUI.init` says per-session addon state *"is F6's problem; until then the anchor owns the
engine"*. `Sessions.tickrebind` is tagged `(F6)` and does it. The guard
(`if(!Sessions.ismember(sess))`) is correct and untouched; the sentence about F6 is replaced by what
the engine does — a member never binds it, and the anchor rebinds it on every switch through
`tickrebind`.

## Files to create and modify

| File | What |
|---|---|
| `src/io/brodgar/session/Sessions.java` | `groundz` through `placed()`; `volatile` on `placedcache`; `placed()` gains the read-only path for a non-tick caller; the two counter sources |
| `src/io/brodgar/addon/ProfHandle.java` | the `p:session()` group, beside `:net()` and `:render()` |
| `src/haven/RemoteUI.java` | the comment in `init` |
| `docs/addons/api/client/profiling/counters.md` | the session counters — one table section, in the page's existing shape |
| `docs/client/world-3d.md` | the **Click dispatch** row: the consequence of the asynchronous pick |
| `addons/070-session-layer-fixes.1/`, `.2/` | the two suites |

`docs/client/world-3d.md` is the right home and the only one: `MapView.Hittest`, `checkmapclick` and
the readback are upstream `haven`, which is what that subtree maps. Nothing about `Sessions`,
`placed()` or `groundz` goes there — `DOCUMENTATION.md` §12.3, and `ROADMAP.md` already carries
`multi-session.md` for exactly that violation.

## Risks and gotchas

- **`groundz` is reached from one place only**: `MapView.RTSCam.camcc`, in its `catch(Loading e)`
  branch. It answers at all only with the `rts` camera installed **and** the view panned over ground
  the anchor has never loaded. A suite that does not set both up is asserting against a code path
  that never runs.
- **`hafen.world():height(p)` does not go through `groundz`.** It reads `MCache.getcz` on the
  anchor's own map (`WorldApi`), so it answers `nil` exactly where `groundz` matters. That makes it a
  good *precondition* check and a useless *outcome* check.
- **`hafen.world():screenToWorld(sx, sy, fn)` does go through the racing path**: `WorldApi` →
  `MapView.Maptest` → `checkmapclick` → `Sessions.offsetfor` → `placed()`, resolved on the render
  thread's callback. This is what makes D2 provable from Lua at all, and `docs/addons/api/world.md`
  already documents the verb as asynchronous, so nothing about its contract changes.
- **`Member.tickoffset` returns early when `mine == anchor`**, leaving `Member.offset` at its previous
  value. Any new reader of that field inherits D1's second half; read `Placed.offset` instead.
- **`Sessions.invalidate()` is called from two places** — the top of `tick()` and inside `anchor()`.
  A cache that may only be built on the tick must answer something sane between them.
- **A clean build is required to trust a compile here.** `ant hafen-client` is incremental and hides
  a symbol that moved; `rm -rf build/classes` first.

## Discarded alternatives

- **`volatile` on the three fields and nothing else** — publication is the smaller half. It leaves
  `Widget.findchild` walking a live widget tree from a render callback, which is the part that can
  actually observe a torn tree rather than a stale reference.
- **Let the render thread take the UI monitor and rebuild safely** — a render callback holding a UI
  monitor inverts the one lock direction this layer permits, and the layer's whole threading argument
  rests on that direction being the only one anything takes.
- **Give `groundz` a fourth branch for the main session, leaving the `members` loop** — the special
  case for "the main session" is precisely what `Placed` exists to delete; `Placed`'s own docstring
  records that every lookup carrying one broke the day a member could hold the screen.
- **Prove `groundz` by eye alone** — the outcome is a camera altitude, and an eye does not distinguish
  a correct height from a plausible stale one. That is what the counters buy.
- **A `:session groundz` console verb as the observation channel** — a suite cannot run a console
  command from Lua, so it would report to the maintainer and prove nothing a program reads.
- **Write the race into `docs/client/multi-session.md`** — `io.brodgar`, refused by §12.3, and that
  page is already filed on `ROADMAP.md` for the same reason.
- **A third task for the `RemoteUI` comment** — a task whose suite has nothing to assert is a task
  that cannot be verified; it rides with D1, which is the other half of "the record says what the
  code does".
- **Fixing `ROADMAP.md`'s `multi-session.md` line here** — it is a page-shape decision (split, or a
  written exception) that outlives these three defects and would double the feature.
