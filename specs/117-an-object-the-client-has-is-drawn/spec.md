# 117 — an object the client has is an object it draws

## What & why

`Gob.attr` is a plain `HashMap`, and its only safety is an invariant nothing states: it is mutated
and walked under the gob's own monitor. Upstream honours it because every writer is a delta apply
and `OCache.GobInfo.apply` wraps those. Our layer added writers that do not — `SpeakerIcon.sweep`,
on the UI thread, every frame, over every player gob; `GobScale.apply`; `LuaGobOverlay.ensure` and
`prune`. The last two do take a monitor, but the attrib's own, and `prune` names the UI monitor,
which serialises Lua verbs against each other and nothing at all against a Loader thread.

Confirmed from a live client. The Loader thread was inside `Gob.added(RenderTree.Slot)` walking
`attr.values()` while the UI thread called `setattr`: `ConcurrentModificationException`. It came out
of `slot.add(ob.placed)` in `MapView.Gobs.addgob`, whose only catch is `RenderTree.SlotRemoved`, so
the gob stayed in `adding`, never reached `current`, and the `Loader.Future` — deferred through the
overload that does not capture exceptions — recorded it and rethrew, killing the thread. A `Loading`
would have been parked and re-run; a `RuntimeException` is not, and the view's own `Gobs` has no
reconcile. The object stayed in the `OCache`, unhidden, and out of the render tree until the whole
set was rebuilt. What the player saw: their own character invisible while everything else drew, for
as long as they stayed in that segment.

Two things are wrong and both close here — an attrib write can be made without the gob's monitor,
and one failed add costs an object for ever. Now rather than later, because voice is about to move
under `session:voice()` and be drawn by an addon through `gob:overlay()`: that turns these writes
from ours into anybody's, on any gob, at whatever rate an addon chooses.

## Acceptance criteria

Each is read back by the task's own suite, in game.

1. **No attrib write costs an object its place in the scene.** With `gob:scale` and
   `gob:overlay():add`/`:remove` driven at once from the step and from a `Draw` handler, over every
   gob in view for a bounded window, every gob drawn before the window is drawn after it.
2. **Every write lands.** After that load, `gob:scale()` and the overlay's own key list answer what
   was last written to them, for every gob the run touched.
3. **Exactly once, and none missing.** Sampled under the same load, each gob's attached overlay
   paints once per frame — not zero, which is a stranded object, and not twice, which is a
   double-add; objects arriving mid-run included.
4. **The client keeps drawing.** Frames advance throughout the window: taking the gob's monitor
   around slot surgery neither deadlocks nor stalls the frame.

## Out of scope

- **`session:voice()`, and retiring `SpeakerIcon`** — its own feature. What happens here is only that
  `sweep`'s write stops being able to break; it is not redesigned, and its reflective walk of `attr`
  from the render pass becomes safe by the same change that makes every other unguarded read safe.
- **Proving the repair in the merged multi-session view.** The reconcile is written on `Gobs`, so
  `SessionGobs` inherits the rule and its own long-standing gap closes with it — a member's copy
  re-added from a stale snapshot is evicted by nothing today. Verifying that needs two logins on two
  accounts, which no suite here can stand up alone; it is a multi-session task and belongs beside the
  other work on that layer.

## Docs impact

Pages this feature writes:

- **`docs/client/state.md`** — the attrib-map row gains the monitor rule and who walks the map from
  which thread; a row for what a dropped render add costs and what recovers it. 76 lines today,
  ceiling 150.
- **`docs/addons/api/threading.md`** — one line under *What runs beside you*: a write on a gob is
  safe from either group of the table, which is what the API's own `gob:scale` and `gob:overlay()`
  need said now that both are reachable from a `Draw` handler and from the step at once.

Derived impact set — `grep -rn "needs guarding\|half-written\|Loader thread\|synchronized(gob)\|gob's own monitor" docs/`:

- `docs/addons/api/threading.md` (*needs guarding*, *half-written*) — both are about the addon's own
  Lua tables and stay true; this is the page the one new line goes on.
- `docs/client/state.md`, `docs/client/resources.md` (*synchronized(gob)*) — `resources.md` already
  states the delta path's monitor and stays true. `state.md` is edited.
- `docs/client/README.md`, `boot-and-loop.md`, `gameui-windows.md`, `glossary.md`, `multi-session.md`,
  `network.md`, `services.md`, `widgets.md` (*Loader thread*) — every one is about the loader as a
  place work runs, none about the attrib map. No change.

No page in `docs/addons/api/` states a threading rule for `gob:scale` or `gob:overlay()`, so nothing
there is contradicted; what is added is a guarantee that was silently untrue.

## Context files

- `src/haven/Gob.java` — 1, 2
- `src/haven/MapView.java` — 2
- `src/haven/OCache.java` — 1, 2
- `src/haven/Loader.java` — 2
- `src/haven/SpeakerIcon.java` — 1
- `src/io/brodgar/addon/GobScale.java` — 1
- `src/io/brodgar/addon/LuaGobOverlay.java` — 1, 2
- `docs/client/state.md` — 1, 2
- `docs/addons/api/threading.md` — 1
- `docs/addons/api/gob.md` — 1, 2
- `docs/addons/api/overlay.md` — 1, 2
- `DOCUMENTATION.md` — 1, 2
