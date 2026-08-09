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
