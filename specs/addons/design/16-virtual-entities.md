# Virtual Entities (World Ghosts) & Transform Gizmos

> **Status:** 🟢 CLOSED — design ratified (maintainer, 2026-07-25). Decisions
> [D-029](../decisions/virtual-entities.md)…[D-033](../decisions/virtual-entities.md) all resolved; **namespace = `hafen.ghost.*`**. Ready to
> build — delivered as [011-virtual-entities](../011-virtual-entities/tasks.md) (V1..V6).
> **Spec:** AddOns · **Related:** [07-ui-and-drawing.md](07-ui-and-drawing.md) (overlays),
> [00-vision-scope.md](00-vision-scope.md) (N1/N2), [API-REFERENCE.md](../API-REFERENCE.md),
> [../../codebase-map.md](../../codebase-map.md)

The capability that lets an addon place **client-only virtual objects in the 3D world** — "ghosts":
translucent, non-interactive props rendered at arbitrary world coordinates — and (later) manipulate
them with a **Unity-style transform gizmo** (move / rotate / scale handles). The motivating use case
is **city / base planning**: lay out ghost buildings, walls and roads over the real terrain, save the
layout, and iterate — a visual blueprint the server never sees.

This is a **new capability** beyond the original goals G1–G10; it is designed here from scratch.

---

## 1. Scope fit — why this is allowed, and safe-tier

- **N1 preserved (server-authoritative).** A ghost is **client-only**: it is never sent to the
  server (no `wdgmsg`), the server never learns it exists, and it grants **no** gameplay advantage —
  it is a visualization, exactly like a HUD overlay. Committing a *real* build is still the gated
  `hafen.act.place` ([API-REFERENCE.md](../API-REFERENCE.md)); ghosts do **not** touch that path.
- **N2 preserved (MapView not replaced).** Ghosts **overlay on** the 3D view by adding render nodes
  to its scene — they do not re-skin or replace `MapView`. The vision doc already allows exactly this
  ("Addons overlay on it", [07-ui-and-drawing.md:94](specs/addons/07-ui-and-drawing.md:94)).
- **Therefore this is a SAFE-tier capability — NOT gated.** No `"permissions": ["actions"]`
  declaration, no consent dialog. It sits alongside `hafen.ui.overlay` / `hafen.ui.gobOverlay`, not
  alongside `hafen.act.*`. ([D-029](../decisions/virtual-entities.md).)

---

## 2. Mechanism — a client-only `Gob` (the `Plob` precedent)

The engine already renders a client-only, cursor-driven virtual object: the **placement preview**.
[`MapView.Plob extends Gob`](src/haven/MapView.java:1779) is our exact template:

```java
// MapView.Plob — the engine's own client-only world entity (paraphrased)
super(MapView.this.glob, Coord2d.of(getcc()));     // a Gob, no server id
setattr(new ResDrawable(this, res, sdt));           // give it a visual
...
this.slot = basic.add(this.placed);                 // add to the 3D scene
// move(Coord2d c, double a) repositions + rotates; slot.remove() despawns
```

So a ghost is:

1. `new Gob(glob, rc)` — [`Gob(Glob, Coord2d)`](src/haven/Gob.java:441); `Gob implements
   RenderTree.Node, Sprite.Owner` ([Gob.java:33](src/haven/Gob.java:33)). No id ⇒ not in `OCache`,
   invisible to every read API and to the server.
2. `gob.setattr(new ResDrawable(gob, res, sdt))` — the visual ([`ResDrawable`](src/haven/ResDrawable.java:79)).
3. add `gob.placed` to the MapView **`basic`** scene slot; keep the returned `RenderTree.Slot`.
4. reposition with [`Gob.move(Coord2d, double)`](src/haven/Gob.java) (position + facing);
   despawn with `slot.remove()`.

**Screen↔world.** Dragging on the ground needs the inverse of `worldToScreen`. The engine's
[`MapView.Maptest`](src/haven/MapView.java:1810) (`Plob.Adjust`) already raycasts the ground under a
screen pixel (`hit(Coord pc, Coord2d mc)` → world `mc`). We surface it as `hafen.map.screenToWorld`.

**Picking (which gizmo handle).** The MapView has a full pixel-perfect pick pass —
[`ClickMap`](src/haven/MapView.java:507), [`MapClick extends Clickable`](src/haven/MapView.java:911),
[`Clicklist`](src/haven/MapView.java:1155), [`ClickLocation`](src/haven/MapView.java:1409) — the same
machinery behind `hafen.act.clickGob`. A ghost made clickable (its own `Gob.GobClick`) is picked by
it — used for clickable ghosts (§3, V2) and the 3D-native gizmo handles (§4, V5).

**Drag capture.** [`UI.grabmouse(Widget)`](src/haven/UI.java:575) gives modal mouse capture; plus the
already-built `hafen.hook.input` + `ev:preventDefault()` (Phase 2c) stops the MapView from panning
mid-drag.

---

## 3. `hafen.ghost.*` — the API (handle-based)

A dedicated namespace for the whole subsystem ([D-030](../decisions/virtual-entities.md); keeps `hafen.world.*` about
scanning the *server* world). The namespace functions:

| Function | Returns | |
|---|---|---|
| `hafen.ghost.new{...}` | `Ghost` handle | create a client-only prop (opts below) |
| `hafen.ghost.list([filter])` | `Ghost[]` | this addon's live ghosts (canonical `filter`) |
| `hafen.ghost.gizmo(target, opts)` | `Gizmo` handle | attach a transform gizmo (§4) |

Interactive, bridge-owned ⇒ each ghost is a **handle** (like `Window`/`Item`, not a GobRef — a ghost
has no server id and re-resolution is meaningless). Creating one registers it in the addon's
owned-resource registry so reload/disable destroys it (P2).

```lua
local g = hafen.ghost.new{
  res   = "gfx/terobjs/arch/logcabin",  -- resource name (client or addon-relative)
  x = wx, y = wy,                        -- world coords (login-relative, like every coord)
  a     = 0,                             -- facing radians (optional, default 0)
  sdt   = nil,                           -- optional spawn-data bytes (resource variant/state)
  alpha = 0.5,                           -- translucency — the "ghost" look (optional)
  tint  = {80,160,255,180},              -- {r,g,b,a} colour overlay (optional)
  scale = 1.0,                           -- optional (V6)
  clickable = true,                      -- opt-in pick-selectable (client-only detection — safe-tier)
  onClick   = function(g, button) end,   -- fires on click; also via the GhostClicked event
}
```

Handle methods (one canonical way — flat verbs on the handle, mirroring the `Window` handle in
[07-ui-and-drawing.md](07-ui-and-drawing.md); [D-013](../decisions/architecture-api.md)):

| Method | Effect | Backing |
|---|---|---|
| `g:move(x, y [, a])` | reposition (+ optional facing) | `Gob.move(Coord2d, double)` |
| `g:rotate(a)` | set facing (radians) | `Gob.move(rc, a)` |
| `g:scale(s)` | uniform scale (V6) | a scaling `Location`/`Pipe.Op` on `placed` |
| `g:setRes(res [, sdt])` | swap the visual | `setattr(new ResDrawable(...))` |
| `g:tint(color)` / `g:alpha(a)` | recolour / translucency | a colour/alpha render state on `placed` |
| `g:show()` / `g:hide()` | add / remove the scene slot | `basic.add` / `slot.remove` |
| `g:clickable(bool)` | toggle pick-selectability (V2) | add/remove the ghost's `Clickable` |
| `g:pos()` | `{x, y, a}` | `Gob.rc` / `Gob.a` |
| `g:destroy()` | remove now (also auto on teardown) | `slot.remove()` + registry drop |

### Clickability & selection (V2) — client-only, stays SAFE-tier ([D-032](../decisions/virtual-entities.md))

Ghosts are **opt-in clickable** (`clickable=true`, or `g:clickable(true)`) so an addon can select /
drag / context-menu them — the core need for a planner. This is **pure client-side detection**: the
engine's pick pass returns the ghost, the bridge fires the addon callback, and **nothing is sent to
the server** — so it is **safe-tier**, not an "action".

- **The intercept.** A click resolves in [`MapView.Click.hit`](src/haven/MapView.java:2017), which
  ends in `wdgmsg("click", …)`. A ghost has **no server id**, so that would be a bogus server click.
  The subsystem adds a `// addon:` intercept there — **exactly where the voice feature already hooks**
  ([:2018](src/haven/MapView.java:2018)) — that, when the picked object is a client ghost, dispatches
  `GhostClicked{ghost, button}` (and the ghost's `onClick`) and **returns without `wdgmsg`**.
- **No hijacking normal clicks.** Clickability is **opt-in per ghost** (decorative ghosts stay
  non-interactive and never win a pick), and the handler chooses to **consume** the click or **pass it
  through** to the default (walk / interact with the real gob behind it). A planner typically flips its
  ghosts clickable only in an "edit mode".
- **Event:** `GhostClicked` (payload `{ghost, button, x, y}`) on the event bus, in addition to the
  per-ghost `onClick`.
- **Still safe-tier because** the only thing a click *could* do to the server is if the addon then
  called a **gated** `hafen.act.*` verb — which already requires the `"actions"` permission on its own.
  The clickability itself never contacts the server.

> **Positioning & persistence.** Ghost coords are **login-relative** like all world coords — not
> shareable/persistent as-is. To save a layout across sessions, anchor on **grid ids** via
> `hafen.map.gridPos()` and re-resolve on load (the same rule markers follow — see
> [coverage-gaps.md](../ROADMAP.md) C4, and the [[hafen-positioning]] memory). A layout is stored
> with `hafen.store` (per-char or account scope).

New `hafen.map` helpers (all safe-tier) — the raycast inverse of `worldToScreen`, plus the placement
snapping the gizmo reuses (§4.1):

| Function | Returns | Backing |
|---|---|---|
| `hafen.map.screenToWorld(sx, sy)` | `{x, y}` \| nil | `MapView.Maptest` ground raycast ([:1810](src/haven/MapView.java:1810)) |
| `hafen.map.snapPlace(x, y [, fine])` | `{x, y}` | `StdPlace.adjust` position math ([:1749](src/haven/MapView.java:1749)) + public `plobpgran` |
| `hafen.map.snapAngle(a [, fine])` | number | `StdPlace.rotate` angle math ([:1764](src/haven/MapView.java:1764)) + public `plobagran` |
| `hafen.map.placeGrid()` / `hafen.map.placeAngle()` | number | read `MapView.plobpgran` / `plobagran` ([:57-58](src/haven/MapView.java:57), **public static**) |

---

## 4. Transform gizmo — `hafen.ghost.gizmo`

A gizmo attaches move/rotate/scale handles to a target (a Ghost handle, or anything exposing
`move/rotate/scale`) and drives it with the mouse.

```lua
local gz = hafen.ghost.gizmo(target, {
  mode = "move",                 -- "move" | "rotate" | "scale" | "all"
  axes = "xy",                   -- ground plane by default (world X / Y)
  snap = 11,                     -- world-units grid snap (tile = 11), or nil for free
  onChange = function(t) end,    -- during drag: t = {x, y, a, scale}
  onCommit = function(t) end,    -- on mouse release
})
gz:setMode("rotate"); gz:detach(); gz:destroy()
```

### Design: primitives in Java, gizmo logic in Lua ([D-031](../decisions/virtual-entities.md))

The gizmo is not a monolithic Java widget. Java exposes **primitives**; the gizmo **behaviour**
(which handle is under the cursor, drag→delta math, axis constraint, snapping, apply) lives in a
**bundled Lua library** over those primitives. This keeps it addon-native and easy to iterate.
Primitives required: ghosts (§3), `hafen.map.screenToWorld` (§3), `hafen.player.worldToScreen`
(exists), `hafen.hook.input` + `preventDefault` (exists, 2c), and — for the polished tier — clickable
ghost handles + a pick event.

### Two tiers

- **3D-native handles — the primary path (V5 move, V6 polish).** Because **V2 already gives ghost
  picking**, the gizmo handles are just small **clickable arrow/ring ghosts** picked pixel-perfectly by
  the engine `ClickMap` — clicking one starts the drag. Later polished with draw-on-top (no depth
  occlusion) + **constant screen size** (rescale by camera distance each tick).
- **2D-projected handles — the fallback.** If a 3D handle mesh is awkward, handles can instead be drawn
  by projecting the target's centre + axis tips to screen (`worldToScreen`) and hit-testing in screen
  space. Same drag pipeline. Kept as a fallback, not the default, now that picking is free.

In both, the drag reads the ground position via `screenToWorld`, constrains it to the active axis, and
**snaps via the client's own placement system** (§4.1) — no bespoke snap math.

### 4.1 Snapping — reuse the client's `placegrid` / `placeangle` ([D-033](../decisions/virtual-entities.md))

Ghost move/rotate must feel **identical to placing a real building**, so they reuse the client's
existing placement snapper rather than inventing one. The engine's placement flow adjusts the ghost
([`Plob`](src/haven/MapView.java:1779)) through [`PlobAdjust`](src/haven/MapView.java:1740) /
[`StdPlace`](src/haven/MapView.java:1746), driven by **two public, persisted settings**:

| Setting (public static) | Console command | Meaning | Default |
|---|---|---|---|
| [`MapView.plobpgran`](src/haven/MapView.java:57) | `:placegrid <n>` | position sub-tile divisions (the **placegrid**) | 8 |
| [`MapView.plobagran`](src/haven/MapView.java:58) | `:placeangle <n>` | rotation divisions (the **placeangle**) | 12 |

`StdPlace` semantics we mirror exactly (`tilesz` = 11):

- **Position** ([`adjust`](src/haven/MapView.java:1749)): no SHIFT → **snap to tile centre**
  (`mc.floor(tilesz)*tilesz + tilesz/2`); SHIFT + `plobpgran>0` → **sub-tile grid**
  (`plobpgran` divisions); SHIFT + `plobpgran==0` → **free**.
- **Rotation** ([`rotate`](src/haven/MapView.java:1764)): CTRL → **45° (π/4) steps**; SHIFT →
  **fine `π/plobagran` steps**. (The auto-face-away-from-player default applies until the user rotates.)

**Reuse strategy (pick at V5).**
- **(preferred, D-009 faithful)** a tiny `// addon:` refactor extracting the `StdPlace` math into public
  static helpers `MapView.placeSnap(Coord2d, modflags)` + `placeSnapAngle(double, wheel, modflags)` that
  **both** `StdPlace` and the bridge call — identical behaviour, no drift.
- **(zero-edit fallback)** mirror the (small, pure) math in the bridge, reading the **public**
  `plobpgran`/`plobagran`. Still respects `:placegrid`/`:placeangle` live; risks drift if upstream changes.

Either way the bridge exposes `hafen.map.snapPlace/snapAngle/placeGrid/placeAngle` (§3), and the gizmo
snaps through them **by default** (`snap="place"`), honouring the same SHIFT/CTRL modifiers. `:move` /
`:rotate` stay **raw/exact** unless given `snap="place"`, so an addon can also place precisely.

---

## 5. Backings & seams (summary)

| Concern | Seam | Status |
|---|---|---|
| Client-only world entity | [`MapView.Plob extends Gob`](src/haven/MapView.java:1779) (template); [`Gob(Glob,Coord2d)`](src/haven/Gob.java:441) + [`ResDrawable`](src/haven/ResDrawable.java:79) | precedent exists |
| Add/remove in the 3D scene | `basic.add(gob.placed)` / `slot.remove()` (Plob does this) | **needs a `// addon:` MapView seam** (§6) |
| Move / rotate | [`Gob.move(Coord2d, double)`](src/haven/Gob.java) | public, native |
| Placement snapping (V5) — placegrid/placeangle | [`StdPlace`](src/haven/MapView.java:1746) math + public [`plobpgran`/`plobagran`](src/haven/MapView.java:57) (`:placegrid`/`:placeangle`) | reuse (zero-edit mirror, or a `// addon:` `placeSnap*` refactor — §4.1) |
| Scale (V6) | scaling `Location`/`Pipe.Op` on `placed` | not native to `Gob` — least-native piece |
| Tint / alpha | colour/alpha render state on `placed` | render-state compose |
| World → screen | `hafen.player.worldToScreen` | **exists** |
| Screen → world | [`MapView.Maptest`](src/haven/MapView.java:1810) → `hafen.map.screenToWorld` | small new read |
| Ghost / handle picking (V2, V5) | [`ClickMap`](src/haven/MapView.java:507)/[`Clicklist`](src/haven/MapView.java:1155)/[`GobClick`](src/haven/Gob.java:673) | exists (same as clickGob) |
| Ghost-click intercept (V2) | [`MapView.Click.hit`](src/haven/MapView.java:2017) — consume before `wdgmsg` | **`// addon:` hook, beside the voice hooks** ([:2018](src/haven/MapView.java:2018)) |
| Drag capture / no pan | [`UI.grabmouse`](src/haven/UI.java:575) + `hafen.hook.input`+`preventDefault` (2c) | exists |

---

## 6. Core edits (invasiveness ledger delta — [11-core-hooks.md](11-core-hooks.md))

Almost everything above is public or already-exposed. The subsystem needs **two small, centralized
`// addon:` MapView edits**, both mirroring things the client already does:

1. **(V1) Add/remove a client-only gob in the `basic` scene slot** — the exact operation
   `Plob.place()` does internally, but `basic` and `Gob.placed` are MapView/Gob internals (a different
   package from `io.brodgar.addon`). Options:
   - **(A)** a public `MapView.addClientGob(Gob) → RenderTree.Slot` + `removeClientGob(Slot)` pair
     (preferred — smallest, clearest, mirrors `Plob.place`).
   - **(B)** an `AddonWidgets`-style `haven`-package accessor to `basic` (the "SpeakerIcon trick", like
     1d-1) — zero MapView edit, but leakier.
2. **(V2) Ghost-click intercept in [`MapView.Click.hit`](src/haven/MapView.java:2017)** — before
   `wdgmsg("click", …)`, if the picked object is a client ghost, dispatch to the addon and return
   (consume). This is **the same choke point the voice feature already hooks**
   ([:2018-2019](src/haven/MapView.java:2018)) → a 2–3 line edit next to a precedent.

`hafen.map.screenToWorld` reuses `Maptest` and likely needs **no** edit (or a tiny accessor).
**Placement snapping (V5)** reads the **public** `plobpgran`/`plobagran` at zero edit; optionally a
third tiny `// addon:` refactor factors the `StdPlace` snap math into shared `placeSnap*` helpers so the
bridge and the client snap through the exact same code (§4.1, preferred for zero drift). Scale / tint
(V6) may add a render-state helper. Everything else is engine-package (`io.brodgar.addon`) only.

---

## 7. Threading & lifecycle

- **UI thread only (P5).** Ghost create/move/destroy and all draw/drag callbacks run on the UI
  thread; scene mutation happens where `Plob` does it. No cross-thread ghost mutation.
- **Teardown (P2).** Each ghost and gizmo is a bridge-owned handle in a per-addon list
  (`Addon.ghosts` / `Addon.gizmos`); `OnDisable` / `:reload` / relogin `teardown` destroys them
  (removes the scene slot, drops input hooks), leaking nothing — the same guarantee as windows and
  overlays ([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).
- **Reload restores nothing automatically** — an addon recreates its ghosts in `OnEnterWorld` from its
  saved layout (grid-anchored, §3). Consistent with N6 (reload rebuilds only the addon layer).

---

## 8. Build slices (delivered as [011-virtual-entities](../011-virtual-entities/tasks.md))

Sliced — each was one task, verified in-game before the next.

- **V1 — Minimal ghost (create / move / destroy).** `hafen.ghost.new{res,x,y[,a]}` + `hafen.ghost.list()`
  → handle with `:move(x,y[,a])`, `:pos()`, `:destroy()`; the MapView client-gob seam (§6 edit 1); bridge-owned list
  + teardown. **DoD:** from `:lua`, spawn a visible prop at the player, move it, destroy it; `:reload`
  removes it cleanly. *(Java engine change ⇒ `ant` rebuild + restart.)*
- **V2 — Clickable ghosts + `GhostClicked`.** `clickable=true` / `g:clickable(bool)`, the per-ghost
  `onClick`, and the `GhostClicked{ghost,button,x,y}` event — via the **`Click.hit` intercept** (§6
  edit 2, beside the voice hooks): pick returns the ghost → dispatch to the addon → **no `wdgmsg`**
  (client-only ⇒ still SAFE-tier). Opt-in per ghost + consume-or-pass-through so it never hijacks
  normal game clicks. **DoD:** click a clickable ghost → `GhostClicked` fires, no server click sent, no
  character walk/interact; a non-clickable ghost is click-through. *(Java engine change.)*
- **V3 — Look & orientation.** `:rotate(a)`, `:setRes(res[,sdt])`, `:show()/:hide()`, `alpha`/`tint`
  (the translucent ghost look). **DoD:** a rotated, translucent cabin ghost; res-swap works.
- **V4 — Layouts + `planner` example addon.** Manage a set of ghosts; save/load a **grid-anchored**
  layout via `hafen.store`; a **dedicated `planner` example addon** (place blueprints, **click-to-select**
  using V2). Keeps `hello` the read-only harness. **DoD:** place several, click to select, relog, they
  reload at the same grid position.
- **V5 — Gizmo: move (+ placegrid snapping).** `hafen.map.screenToWorld` + `snapPlace`/`placeGrid`
  (reuse the client's `StdPlace`/`plobpgran` — §4.1, [D-033](../decisions/virtual-entities.md)) + a `grab` helper + a
  **bundled Lua gizmo library** in `planner`: **move** handles (3D-native clickable arrow ghosts reusing
  the V2 pick, 2D-projected fallback), ground-plane drag that **snaps exactly like placing a building**
  (tile centre default, SHIFT → sub-tile grid), no camera pan. **DoD:** drag a selected ghost by its
  arrows; it snaps on the placegrid (and follows `:placegrid`); SHIFT gives the fine grid; camera stays.
- **V6 — Gizmo: rotate (+ placeangle) + scale + polish (stretch).** Rotate ring using `snapAngle`/
  `placeAngle` (reuse `plobagran` — 45° default, SHIFT fine) + uniform scale (`:scale(s)`); draw-on-top
  + constant screen-size handles. **DoD:** full move/rotate/scale, rotation snapping on the placeangle.

---

## 9. Decisions (all ratified — maintainer, 2026-07-25)

- **[D-029](../decisions/virtual-entities.md)** — Virtual entities are **client-only, safe-tier** (not gated): N1/N2
  preserved, no server contact, no permission.
- **[D-030](../decisions/virtual-entities.md)** — Ghosts live under a dedicated **`hafen.ghost.*`** namespace and are
  addressed by a **handle** (like `Window`), not a GobRef.
- **[D-031](../decisions/virtual-entities.md)** — The gizmo (`hafen.ghost.gizmo`) is a **Lua library over Java primitives**,
  not a monolithic Java widget; ships in the `planner` example addon.
- **[D-032](../decisions/virtual-entities.md)** — Ghosts are **opt-in clickable via client-only pick detection** (no server
  click) — still safe-tier; the click is consumed at `Click.hit` (V2). *(Maintainer decision, 2026-07-25.)*
- **[D-033](../decisions/virtual-entities.md)** — Ghost move/rotate **reuse the client's own placement snapping**
  (`StdPlace` + `plobpgran`/`plobagran`, i.e. `:placegrid`/`:placeangle`) so it feels identical to
  placing a building. *(Maintainer decision, 2026-07-25.)*

## 10. Resolved defaults & deferred items

Everything below is **decided** (the spec is closed); the deferrals are scope, not open questions.

- **Namespace** → **`hafen.ghost.*`** (dedicated), gizmo = `hafen.ghost.gizmo` ([D-030](../decisions/virtual-entities.md)).
- **Clickability routing** → **opt-in per ghost; the handler decides** consume vs pass-through (a
  planner consumes only in "edit mode") ([D-032](../decisions/virtual-entities.md)).
- **Snapping** → **reuse the client's placegrid/placeangle** (`StdPlace`), not a bespoke model
  ([D-033](../decisions/virtual-entities.md)); free/fine via SHIFT, rotation via CTRL/SHIFT — as in real placement.
- **Scale mechanism** → a `Location`/scaling `Pipe.Op` on `placed` (V6; the least-native piece).
- **Multi-select / group transform** → **deferred** to a post-V6 slice (single-target gizmo first).
- **Resource ergonomics** → v1 uses **client resources**; addon-shipped `.res` props come later (ties
  into [07-ui-and-drawing.md](07-ui-and-drawing.md) "Custom assets").
