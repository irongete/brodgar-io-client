# Decisions — Virtual entities (ghosts & gizmo)

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-029 — Virtual entities (world ghosts) are client-only and SAFE-tier (not gated) ✅ (maintainer, 2026-07-25)
**Decision.** Addons may place **client-only virtual objects** in the 3D world ("ghosts") via
`hafen.ghost.new` (spec [16-virtual-entities.md](../design/16-virtual-entities.md)). A ghost is a `Gob` with **no
server id**, rendered by adding its node to the MapView scene (the [`Plob`](src/haven/MapView.java:1779)
template) — it is **never** sent to the server. Therefore it is a **safe-tier** capability, alongside
`hafen.ui.overlay`/`gobOverlay`, **not** the gated actions tier: **no** `"permissions": ["actions"]`, **no**
consent dialog. Committing a real build stays the gated `hafen.act.place`.
**Rationale.** N1 (server-authoritative) is preserved because nothing reaches the server and no gameplay
advantage is conferred — a ghost is a visualization, like a HUD overlay. N2 (MapView not replaced) is
preserved because ghosts **overlay on** the scene; the vision doc already permits this. Gating a purely
visual, client-only aid would be miscategorising it (cf. the [[hafen-botting-allowed]] framing: the gate is a
user-control for *actions on your behalf*, which a ghost is not).
**See.** [16-virtual-entities.md](../design/16-virtual-entities.md), [00-vision-scope.md](../design/00-vision-scope.md) (N1/N2),
[07-ui-and-drawing.md](../design/07-ui-and-drawing.md).

### D-030 — Ghosts use a dedicated `hafen.ghost.*` namespace, addressed by a HANDLE (not a GobRef) ✅ (maintainer, 2026-07-25)
**Decision.** The whole subsystem lives under a **dedicated `hafen.ghost.*` namespace** (`hafen.ghost.new`
to create, `hafen.ghost.list` to enumerate this addon's ghosts, `hafen.ghost.gizmo` for the transform gizmo)
— keeping `hafen.world.*` about scanning the *server* world. `hafen.ghost.new{...}` returns a bridge-owned
**handle** with flat verb methods (`:move`/`:rotate`/`:scale`/`:setRes`/`:tint`/`:alpha`/`:show`/`:hide`/
`:clickable`/`:pos`/`:destroy`), exactly like the `Window` handle — **not** a GobRef. Rationale: a ghost has
no server id, so the GobRef re-resolve model
([D-012](architecture-api.md)) is meaningless; and it is addon-owned and interactive, which is precisely the case the
conventions assign to handles ("Interactive things return handles", [API-REFERENCE.md](../API-REFERENCE.md)).
Still **one canonical way** ([D-013](architecture-api.md)) — flat verbs on the handle, no OO/ref alternative.
**See.** [16-virtual-entities.md](../design/16-virtual-entities.md) §3, [07-ui-and-drawing.md](../design/07-ui-and-drawing.md).

### D-031 — The transform gizmo (`hafen.ghost.gizmo`) is a Lua library over Java primitives ✅ (maintainer, 2026-07-25)
**Decision.** The move/rotate/scale gizmo is **not** a monolithic Java widget. Java exposes
**primitives** — ghosts ([D-029](virtual-entities.md)), `hafen.map.screenToWorld` (the [`Maptest`](src/haven/MapView.java:1810)
ground raycast), the existing `hafen.player.worldToScreen`, `hafen.hook.input`+`preventDefault` (2c), a `grab`
helper, and clickable ghost handles + a pick event ([D-032](virtual-entities.md), V2) — and the gizmo **behaviour**
(handle hit-test, drag→delta math, axis constraint, snapping, apply) lives in a **bundled Lua library** shipped
in the `planner` example addon. Primary path = **3D-native clickable handles** (reusing the V2 ghost pick);
2D-projected handles are a fallback.
**Rationale.** Keeps the capability addon-native and iterable without recompiling Java, matches the project
ethos (engine exposes primitives, addons orchestrate — like `LuaGobOverlay`), and lets the hard interaction
design evolve in Lua. **Consequence:** the reusable gizmo Lua module is a first-party asset — a candidate for a
shared `lib/` once addon-to-addon reuse exists.
**See.** [16-virtual-entities.md](../design/16-virtual-entities.md) §4.

### D-032 — Ghosts are opt-in CLICKABLE via client-only pick detection (still SAFE-tier) ✅ (maintainer, 2026-07-25)
**Decision.** Ghost entities are **clickable** (`clickable=true` / `g:clickable(bool)`, opt-in per ghost),
so an addon can select / drag / context-menu them — the core need for a planner. Clickability is **client-only
detection** and stays **SAFE-tier** (reinforces [D-029](virtual-entities.md)): the engine pick pass returns the ghost,
the bridge fires `GhostClicked{ghost,button,x,y}` + the per-ghost `onClick`, and **nothing is sent to the
server**. The click is intercepted in [`MapView.Click.hit`](src/haven/MapView.java:2017) — **before** its
`wdgmsg("click", …)` — the same choke point the voice feature already hooks ([:2018](src/haven/MapView.java:2018));
if the picked object is a client ghost the intercept dispatches to the addon and returns (consume). A ghost has
no server id, so a server click for it would be bogus anyway.
**Consequences.**
- Pulls a clickable-ghost + `GhostClicked` slice **forward to V2** (was folded into the V5 "3D-native handles"
  polish). The gizmo's 3D-native handles then reuse the same pick, so pixel-perfect handle picking comes for free.
- Adds the **second** small `// addon:` MapView edit (the `Click.hit` intercept) alongside V1's add-gob seam.
- **No hijacking normal play:** clickability is opt-in (decorative ghosts never win a pick) and the handler
  chooses to consume or pass the click through to the default (walk / interact behind the ghost).
- Boundary intact: the only way a ghost click reaches the server is if the addon then calls a **gated**
  `hafen.act.*` verb, which needs the `"actions"` permission on its own. The clickability never contacts the server.
**Rationale.** Maintainer direction (2026-07-25): "quiero que los gobs ghosts sean clicables". Compatible with N1
(no server contact, no advantage) and with the safe-tier classification, since detection ≠ action.
**See.** [16-virtual-entities.md](../design/16-virtual-entities.md) §3 (Clickability), [D-029](virtual-entities.md) (safe-tier).

### D-033 — Ghost move/rotate reuse the client's placement snapping (placegrid / placeangle) ✅ (maintainer, 2026-07-25)
**Decision.** Moving and rotating a ghost — via the gizmo, or `:move`/`:rotate` with `snap="place"` —
**reuse the client's existing placement snapper** instead of a bespoke one, so it feels **identical to
placing a real building**. The engine adjusts a placement ghost ([`Plob`](src/haven/MapView.java:1779))
through [`PlobAdjust`](src/haven/MapView.java:1740) / [`StdPlace`](src/haven/MapView.java:1746), governed
by two **public, persisted** settings — [`MapView.plobpgran`](src/haven/MapView.java:57) (the
**placegrid**, `:placegrid <n>`, default 8) and [`MapView.plobagran`](src/haven/MapView.java:58) (the
**placeangle**, `:placeangle <n>`, default 12). The subsystem exposes `hafen.map.snapPlace`/`snapAngle`
(the `StdPlace` math) + `hafen.map.placeGrid`/`placeAngle` (read the settings), and the gizmo snaps
through them **by default**, honouring the same **SHIFT** (fine grid / free) and **CTRL/SHIFT**
(rotation) modifiers. `StdPlace` semantics reused verbatim: position → tile centre by default, SHIFT →
`plobpgran` sub-tile grid (or free at 0); rotation → 45° (π/4), SHIFT → `π/plobagran` fine.
**Consequences.**
- Lives in the gizmo slices — **placegrid in V5** (move), **placeangle in V6** (rotate). `:move`/
  `:rotate` stay raw/exact unless `snap="place"` is passed.
- Reading the settings is **zero core edit** (both fields are `public static`). Two implementation
  options for the math (D-009 tension): **(preferred)** a tiny `// addon:` refactor factoring
  `StdPlace`'s math into shared static `MapView.placeSnap*` helpers that both `StdPlace` and the bridge
  call (no drift); **(fallback)** mirror the small pure math in the bridge. Either respects a live
  `:placegrid`/`:placeangle`.
- The user's existing placegrid/placeangle preference automatically applies to ghosts — one setting,
  one behaviour, for both real placement and planning.
**Rationale.** Maintainer direction (2026-07-25): "al mover y rotar los ghosts quiero que funcione con
el sistema de placegrid y el otro que hay para rotaciones". Faithful reuse (D-009) over reinvention.
**See.** [16-virtual-entities.md](../design/16-virtual-entities.md) §4.1, [D-009](widgets-ui.md) (wrap-not-reimplement).

### D-127 — a thing that cannot EXIST without a place takes it on the constructor, positionally ✅ (2026-08-05)
**Decision.** `hafen.render():sprite():add(image, p)`, `:object():add(model, p)` and
`hafen.ghost():add(res, p)` take the Position as a second **required** positional argument.
`:position(p [, a])` stays as the live write.
**Rationale.** (2026-08-05, 039.8, found by the in-game round.) The first implementation followed D-119 —
build it bare, let a setter place it — reasoning that an unplaced entity sits harmlessly at world origin,
off-map. It does not: `MapView.addClientGob` is `basic.add(gob.placed)`, and `Gob.Placed.Placement`'s ctor
resolves the **tile** under the gob (`getmapstate` / `placer().getr`). At the origin there is no map data,
so the engine raises `MCache.LoadingMap` *"Waiting for map data..."* straight out of the Lua call that
built it. So the difference from D-112/D-119 is not taste: for a widget and for an overlay, *not drawn
yet* is a legal state the builder passes through; for a thing in the 3D scene there is no such state — an
entity with no place is **unbuildable**, not inert. §2.5's own rule then decides it: *a required argument
stays positional on the constructor where the thing is meaningless without it*.
**Alternatives.** Arm on the next tick like the UI builders (rejected — only moves the throw one frame,
and an entity nobody places still lands on the origin). Catch the `Loading` at create and leave it out of
the scene (rejected as the *whole* answer — it makes "I forgot the place" a silent invisible entity, which
is the failure the grammar deletes; it IS kept for the honest case below). Default to the player's
position (rejected — an invented place is a wrong place).
**Consequences.** The world-origin state is gone from the API and from the pages. Separately, a create
whose ground is **explored but not currently streamed** is a real and normal case (a saved layout
reloading as the map arrives), and there *not yet* is not *no* — D-095's model — so `addToScene` catches
`Loading`, marks the entity `pending`, and `RenderApi.armPending` retries on each addon tick. A retired
row's message names the two-argument spelling, which is asserted as text by two suites.
**See.** [D-119](architecture-api.md) (a builder is attached inert — the rule this one bounds),
[D-112](architecture-api.md), [D-114](architecture-api.md) (a creation raises where a removal is inert),
[D-095](architecture-api.md) (kick the load, answer nil), [ghosts.md](../learnings/ghosts.md).

### D-206 — a thing standing at a POINT is drawn while the GROUND under it is drawn, and the engine's own display structure is the test ✅ (044.9, 2026-08-09)
**Decision.** A client-only entity anchored to a **point** — a ghost, a sprite, an object or a standing
widget, all four on the shared core — is in the scene only while the terrain under it is drawn. It is
**hidden, not ended**: the handle, the place, the look and `:exists()` all survive, and it returns by itself
when the ground does. It is **not a policy** the addon chooses. One that is **anchored to a gob** is
untouched — its place is that gob's, so it behaves exactly like the gob and ends with it (D-102).
**Rationale.** (Raised verifying 043.2, seen again in 044.7, made its own task by maintainer directive.) A
client-only gob is in no `OCache` — the very premise D-102 rests on — so nothing removed it and
`Gob.Placed.autotick` catches the `Loading` a missing tile throws and keeps the last placement, so nothing
hid it either. It hung over the void until it left the screen, and walking back found it still there.
Ending it instead would lose an addon's placement the first time the player walked away and make
`:exists()` a function of where the camera has been; and there is nothing to opt into, because drawing over
ground that is not there is never what an addon asked for — so a switch would only be a way to keep the
bug.
**The test is `MapView`'s own per-cut map, not `MCache`'s grid set.** A grid stays loaded well past the
point where the terrain stops being *drawn* (the display list is `view = 2` cuts around the player's cut,
~75 tiles at most, while the server trims grids far later), so the obvious "is the grid loaded" test would
still leave the thing hanging over the void for about a grid's width. `MapRaster.Grid.cuts` holds a cut
**exactly while that cut's mesh is in the scene**, so the question is asked of the very structure that
draws the ground and there is no second rule to drift out of step with what the player can see — the same
shape as D-203, where the answer was already being computed and only had to be read.
**The event is that map changing**, tapped at its two mutation points, flag only, drained on the addon tick
(D-106). It fires when the player crosses a cut boundary or a grid streams in or out — never per frame, and
not at all while standing still. `MCache.gridwait`, which the task's plan guessed at, was not needed.
**Consequences.** A **third** boolean ANDed into `shows()`, beside the entity's own `:visible(b)` and the
section's (D-189) — so nothing the addon wrote is ever overwritten and `:visible()` still reads back what
it was told while the thing itself waits for its ground. What the world is doing needed a read of its own,
so the shared core gained `<entity>:drawn()` (read-only, refuses an argument): *is it in the scene right
now* — false while hidden, while the section is off, while the visual is still streaming in, and while a
free one's ground is not drawn. It says nothing about the camera; that is 044.7's `culled`. A free create
over undrawn ground now simply does not enter the scene, so it no longer starts a bounded `Resolve` chain
on a tile that may never arrive (D-127's honest case, answered better).
**See.** [D-102](architecture-api.md) (the end of a derived thing rides its source's event — the anchored
half), [D-189](architecture-api.md) (a switch is a SECOND boolean, never a write over the thing's own),
[D-203](rendering.md) (the test was already being computed), D-127 above (the create-over-unstreamed-ground case whose retry this replaces), [ghosts.md](../learnings/ghosts.md),
[world-3d.md](../../codebase/world-3d.md).

### D-207 — a thing standing at a POINT holds its DURABLE place, and the session coordinate is a cache derived from it ✅ (045.1, 2026-08-09)
**Decision.** What a client-only entity anchored to a **point** keeps is the durable form of that place —
the server's grid id plus the offset inside that grid — and its session world coordinate becomes a value
the layer **derives** from it and is free to recompute, or to have none of. Both forms already live in one
`Position` type (D-109), so nothing is invented; what changed is which of the two the entity keeps. One
anchored to a **gob** is untouched: its place is that gob's and it has nothing of its own to hold.
**Rationale.** (Found in-game verifying 044.9.) A session coordinate is re-based whenever the server drops
the map — walking into a cave or a house — and the same numbers then name different ground. An entity
holding one went on holding a number that had quietly stopped meaning anywhere, so a thing put down where
you wanted it was somewhere else after a cave and back. There is nothing to re-base *from* once the grids
are gone: the anchor is the only part of a place that survives the drop, so it has to be the part that is
kept. Keeping both and preferring the world one "while it is still valid" was rejected — two sources of
truth, and *valid* has no test.
**So the read splits, and the asymmetry IS the contract.** `<entity>:position()` answers the anchor form
for one that stands still and the gob's live point for one that follows. `:info()` therefore reads the same
grid and offset across a walk, while `:x()`/`:y()` are only ever this session's answer to where that is —
free to differ, and (once a place this session cannot locate is legal) free to be nil.
**A place with no DURABLE form is refused, at both doors that hold one.** `LuaPosition.anchorArg` replaces
`worldArg` at exactly two call sites — the `Position` branch of a `:add(what, anchor)` and
`<entity>:position(p)` — while the eight verbs that act *here and now* (`moveTo`, `place`, `select`,
`useItemOn`, `marker():add`, `snapPlace`, `worldToScreen`) keep asking for a coordinate, which is the right
question for them. Ground nobody has ever recorded has no grid id and the client cannot invent one, so that
place is refused naming why rather than accepted as an entity pinned to a number that will lie. Hard cut,
no warning tier: the silent version is the failure this decision exists to delete.
**The derivation rides an event that already exists.** `rc` is recomputed in 044.9's ground drain, one step
in front of its ground test (D-206) — the moment every cut leaves the scene is the moment the coordinate
space is re-based, so the event that says the ground moved is the event that says the numbers did. No
sweep, no per-frame poll (D-181), and no `Resolve` chain: "the player has not walked there" is not a
blocker that clears (042.12).
**`rc` becoming nullable is safe because of one invariant**: no coordinate means not grounded, so
`shows()` is false, so no scene path is ever reached with it — an entity with no place has no slot, and the
create that would have used it is the one the drain runs when the place resolves again.
**See.** D-206 above (the ground rule this extends, and the drain it rides),
D-127 above (a thing that cannot exist without a place takes it on the constructor — the refusal this
re-aims), [D-109](architecture-api.md) (a value with two forms derives, never converts — the one type this rests on),
[D-106](architecture-api.md) (a tap raises a flag, the tick does the work),
[ghosts.md](../learnings/ghosts.md), [world-reads.md](../learnings/world-reads.md).

### D-208 — a place you have not reached is a legal place to stand something, and it waits forever ✅ (045.2, 2026-08-09)
**Decision.** `:add(what, p)` and `<entity>:position(p)` accept a durable place this session **cannot
locate**. The entity is created, holds that place, reports it through `:info()`, answers `nil` for `:x()`,
is not drawn, and enters the scene by itself the moment that ground resolves. Nothing bounds the wait and
nothing reports it as a failure.
**Rationale.** Once the place is what the entity keeps (D-207), *can this place be held* and *where is it
right now* are two different questions, and only the first one has an answer that is true forever. Refusing
the second made a legal saved place — one read back out of `hafen.store` from a corner of the world the
character is not standing in — into an error at the one moment an addon would restore it, which is the
first thing anybody will do with a durable place. The honest answer is the one a Position already gives:
`p:x()` is nil, and the thing waits.
**No `Resolve` chain, and no retry cap.** 042.12's lesson is that a bounded retry chain wants one stable
blocker that clears on notify; "the player has not walked there" is not one, and may never be. The entity
sits in the free index and is re-asked on the two events below — silently, and for as long as it takes.
**The gob is still built at `:add`**, at the origin, un-moved and outside the scene. `shows()` is false
with no coordinate, so it cannot get in from there, and `attachScene` stays the ONE door into the scene
rather than four creators becoming re-runnable. Deferring the build until the place resolved would have
bought nothing observable: `:drawn()` is false either way.
**What is still refused is the other edge**, unchanged: a place with **no durable form at all** — a raw
coordinate over ground nobody has recorded. Unreached and un-holdable are different answers, and only the
first waits.
**See.** D-207 above (the place this holds), D-206 above (the ground rule that keeps it out of the scene),
D-127 above (a thing meaningless without a place takes it on the constructor — still true; what changed is
what counts as one), D-209 below (the second event it waits on),
[D-114](architecture-api.md) (a creation raises where a removal is inert),
[ghosts.md](../learnings/ghosts.md).

### D-209 — the session location's own assignment is the second event, and the equality test is the whole tap ✅ (045.2, 2026-08-09)
**Decision.** Re-deriving a free entity's coordinate rides **two** events, both mutation points the client
already has: the terrain's cut map (D-206's drain) and the assignment of `MiniMap.sessloc`. The second is
one guarded `// addon:` line raising the same flag the first does, drained on the addon tick (D-106).
**Rationale.** The anchor→world derivation runs through `sessloc`, which is re-resolved a frame or more
**after** the cuts have come back. Without the second tap, an entity whose ground returned while the player
stood still would wait for a cut change that may never come — the walk back out of a cave would leave it
out of the scene until something else happened to move.
**The guard is not an optimisation, it is the decision.** `tick` mints a fresh `Location` object every
frame, so notifying on the assignment alone would raise the flag sixty times a second and turn the drain
into exactly the per-frame poll D-181 deleted. What actually changes is the segment and the tile origin —
a handful of times an hour — so the tap compares those two and says nothing otherwise.
**And it listens to ONE minimap**, the instance the derivation reads. The map window carries a second one
ticking the same locator against the same file; letting both through would let whichever ticked first
consume the change for the other, and the memo would already match by the time the instance that matters
had been assigned. A tap keyed on a value must listen to the reader of that value, not to the type.
**Made assertable rather than asserted.** The counter behind `hafen.client():profiling():entities()`
reports how many times the drain has actually walked the list, so "this is an event, not a poll" is a line
a suite checks over an idle stretch instead of a claim in a comment.
**See.** D-208 above (what waits on it), D-206 above (the first source, and the drain both share),
[D-106](architecture-api.md) (a tap raises a flag, the tick does the work),
[D-181](architecture-api.md) (the layer does not poll what the client already announces),
[D-051](architecture-api.md) (a pull-only counter answers whether profiling is armed or not),
[minimap.md](../../codebase/minimap.md).
