# 070 — the session layer's own defects

## What and why

`io.brodgar.session` holds several game sessions open and draws one of them — the **anchor**. Three
things in it are wrong, all found by reading the class rather than by a report from the game. None of
them is an addon-facing surface, and none needs a design decision: this feature is three corrections
and one line of the engine map, and it is deliberately first in a longer sequence because everything
that follows edits the same class.

**No `hafen.*` verb is added, retired or re-spelled.** The one addon-facing addition is a **counter
group**, `p:session()`, beside the `:memory()`, `:net()`, `:loader()` and `:render()` groups that
already exist — because two of the three defects are otherwise provable only by eye, and a defect
whose proof is a judgement of how a camera looks is a defect that closes on a guess.

### The three defects

**D1 — `Sessions.groundz` asks the wrong sessions, in the wrong frame.**

`groundz(Coord2d anchorpos, double dflt)` answers "how high is the ground at this place in the
anchor's coordinates", by asking whichever session actually has that ground. It gets two things wrong
at once:

- It iterates `members` only. **The main session is not a member**, so it is never asked — even
  though `Sessions.mainoff` exists, maintained by `tickmainoffset`, for exactly this purpose. With a
  member holding the screen, ground that only the main session has loaded is unreachable.
- It is the **only** site that reads `Member.offset()` raw, without the `isanchor ? zero : offset`
  correction `buildplaced` applies. `Member.tickoffset` returns early when `mine == anchor`, so that
  field keeps a stale value once a member becomes the anchor. The result is the wrong session's map
  queried at the wrong translation.

The single caller is `MapView.RTSCam.camcc`, in its `catch(Loading e)` branch: a free camera panned
over ground the anchor has never loaded. The comment there states the intent the defect breaks —
*"ask the session that does have that ground, and failing that keep the last height rather than the
whole view"*. Today it keeps the last height in cases where it should have found one.

The fix is to route through `Sessions.placed()`, which already answers both halves. This is the one
lookup that was never converted when `Placed` was introduced, and `Placed`'s own docstring warns
against exactly this: *"Up to F4 'the anchor's own character' and 'the main character' were the same
thing and every lookup could special-case it."*

**D2 — the cache behind `placed()` is reached from the render thread and is not published safely.**

`Sessions.placedcache`, `Sessions.mainguifor` and `Sessions.mainguicache` are plain statics with no
`volatile` and no lock. They are written on the UI thread by `buildplaced()` and `mainguiof()`.

They are also read **and written** from the render thread: `MapView.checkmapclick` calls
`Sessions.offsetfor(cut.map)` inside the callback of `out.pget(...)` / `clmaplist.get(...)`, which are
asynchronous GPU readbacks. `docs/client/world-3d.md` already records that the pick is *"ASYNCHRONOUS
(`env.submit`, resolved on the render thread's callback)"*. That path runs
`offsetfor` → `placed()` → `buildplaced()` → `mainguiof()` → `findgui()` → `Widget.findchild`, a
recursive walk of the widget tree, without the UI monitor.

By the Java memory model this is a data race. It has **not** been observed at runtime, and confirming
it is part of the work rather than an assumption to build on. What makes it worth fixing regardless is
the contrast: the rest of the class is scrupulous about threads — `CopyOnWriteArrayList` for
`members`, `volatile` on every field of `Member`, `flushsay` queued with its deadlock reasoning
written beside it. These three fields are the only gap.

Marking them `volatile` is not the whole answer. The decision the fix has to make is whether the
render thread may **rebuild** the cache — running `findchild` off the UI thread — or must take
whatever is there, and what it answers when there is nothing.

**D3 — `RemoteUI.init`'s comment describes a future that already happened.**

It reads *"Per-session addon state is F6's problem; until then the anchor owns the engine."*
`Sessions.tickrebind` is tagged `(F6)` and is implemented: on every anchor change it calls
`AddonManager.init(u)` and `AddonManager.attach(mv)`, and reports what that cost. The guard itself
(`if(!Sessions.ismember(sess))`) is correct and is not touched — what is corrected is what the
comment claims about the engine.

## Acceptance criteria

1. `groundz` answers over the **main session's** ground as well as a member's.
2. `groundz` measures against the frame each session is actually in, so a member that holds the
   screen contributes its own map at zero offset rather than at a stale translation.
3. The `placed()` cache is safe to reach from the render thread, and **a counter proves the render
   thread never rebuilds it** — readable through the existing profiling counters, so the assertion is
   a number a suite reads rather than a claim about the code.
4. `hafen.world():screenToWorld` driven hard while the tick mutates the cache answers a coherent
   ground Position every time, and never `nil` for a pixel over drawn ground.
5. `RemoteUI.init`'s comment states what the engine does today.
6. `docs/client/world-3d.md` states the **consequence** of the asynchronous pick — that anything the
   callback reaches is touched off the UI thread — and not only that it is asynchronous. That
   omission is what made D2 invisible.

## Out of scope

- Every other step of the multi-session sequence: `hafen.session()`, parameterising
  `AddonManager.ui`/`.view`, the per-session caches, the engine ceasing to reload on a switch, and
  the API cut. This feature adds no addon-facing surface at all.
- **`ROADMAP.md`'s line on `docs/client/multi-session.md` mapping `io.brodgar` (filed: 066) stays
  standing.** `groundz`, `placed()` and `Sessions` are `io.brodgar`, so by `DOCUMENTATION.md` §12.3
  none of D1 or D2 is written to that page — this feature neither worsens the violation nor takes on
  the split.
- The `Fonts` subsystem.
- The uncommitted camera work in the tree (`adoptcam`, the RTS mode without a camera, `applymute`,
  `order`→`orderunit`). Verified not to touch `groundz`, `placedcache`, `mainguifor` or
  `mainguicache`, so it does not collide — but it must be committed before any task here is
  implemented, or that context's own `/end` cannot tell its work from this one's.

## Docs impact

**Written**: `docs/client/world-3d.md` — the threading consequence of the asynchronous pick, on the
**Click dispatch** row that already names it. Upstream `haven`, so the page is the right home.

**Not written, and deliberately**: nothing under `docs/addons/**`. No `hafen.*` verb changes
behaviour. `hafen.world():screenToWorld` is *used* by the suite and its contract is unchanged;
`hafen.world():height(p)` reads the anchor's own `MCache.getcz` and never went through `groundz`.

**Derived set:**

```
grep -rniE "ground height|terrain height|pick pass|asynchronous|render thread" docs/
```

**20 hits.** Read in full; the ones this feature touches or must leave exactly as they are:

| Page | What it says | Verdict |
|---|---|---|
| `client/world-3d.md` | "**The pick is ASYNCHRONOUS** … resolved on the render thread's callback, so it cannot serve anything that must answer inside the event" | **Revised.** True, and incomplete in the way that hid D2: it names what the pick cannot *serve*, never what the callback may *touch* |
| `client/world-3d.md` | the Pick pass row (`ClickMap`, `MapClick`, `Clicklist`, `ClickLocation`, `Gob.GobClick`) | unchanged |
| `addons/api/world.md` | `screenToWorld` "reads the true terrain point from the GPU, the same pass the client's own building placement uses; asynchronous" | unchanged — the contract is what the suite leans on, and it is already correct |
| `addons/api/world.md` | `hafen.world():height(p)` — "terrain height there" | unchanged — the anchor's own map, unrelated to `groundz` |
| `client/multi-session.md` | four rows naming the pick pass and `checkmapclick` | **Discharged unrevised**: `io.brodgar`, §12.3, and ROADMAP already carries the page |
| `client/render-gl.md` | "replayed by `BufferBGL` on the render thread" | unchanged — corroborates, states nothing false |
| `addons/api/{actionbar,http,menugrid,ui/drawing}.md`, `client/README.md` | "asynchronous" about unrelated subjects | discharged, no overlap |
| `addons/api/vr/ghosts.md`, `addons/api/client/profiling/README.md`, `client/boot-and-loop.md` | "pick pass" / "render thread" in passing | discharged, no overlap |

## Context files

- `src/io/brodgar/session/Sessions.java` — 1, 2
- `src/io/brodgar/addon/ProfHandle.java` — 1, 2 (the `p:session()` group both tasks write a counter into)
- `src/haven/MapView.java` — 1, 2
- `src/haven/RemoteUI.java` — 3
- `src/io/brodgar/addon/WorldApi.java` — 2
- `docs/client/world-3d.md` — 2
- `docs/addons/api/world.md` — 1, 2
- `docs/addons/api/client/profiling/counters.md` — 2
- `DOCUMENTATION.md` — 2
