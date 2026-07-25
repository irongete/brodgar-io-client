# V5b — The transform gizmo (Unity-style, drawn with g:draw) + `g:poly`

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the updated
> `planner` (now `gizmo.lua` + `main.lua`) into `bin/addons/`. **Headless-verified:** `gizmo.lua` loads under a real
> `Sandbox.create()` env and installs the `gizmo` global; the constructor's target-validation rejects nil / `{}` /
> pos-only targets **before** touching the `hafen` facade; `main.lua` parses. The gizmo's live behaviour (projection,
> screen-space picking, the drag) needs a GL/session, so it is **in-game DoD-verified**. **Zero `haven` core edit** —
> the one bit of new Java is a `g:poly` filled-triangle primitive added to our own `LuaGOut` draw wrapper (public
> `GOut.drawp` + public `GOut.tx`). **Java engine change ⇒ `ant` rebuild + a full client restart** before testing.
> **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§4 "Two tiers" —
> the **2D-projected** path; §4.1 snapping), decisions **D-031** (gizmo = Lua over Java primitives, shipped in the
> planner example) / **D-033** (reuse the client's placegrid) / **D-029** (client-only, safe-tier).

## What V5b is, and the one design decision

V5a shipped the Java **primitives** (`screenToWorld`, `snapPlace`/`placeGrid`, `hook.grab`) and proved them with a
drag-the-body move-mode (`:planner grab`). V5b is the **gizmo itself**: drag a ghost **by its arrows**, with an axis
constraint. Per **D-031** the gizmo behaviour is a **bundled Lua library** over those primitives — so V5b is almost
entirely Lua, shipped as [`planner/gizmo.lua`](../../../addons/planner/gizmo.lua).

**The maintainer's call on the handles.** The spec (§4) offers two ways to render the handles:

- **3D-native** — small clickable **arrow-ghost meshes** picked by the engine `ClickMap` (reusing the V2 pick). The
  original V5b plan. It needs a small arrow **resource**, and none ships in the client jar.
- **2D-projected (the documented fallback)** — draw the handles by projecting the target + world-axis tips to screen
  and hit-test in screen space. Same drag pipeline; needs **no resource**, just the draw surface.

The maintainer chose the **2D-projected** path explicitly ("triangles drawn with `g:draw`, Unity-style — no resource
hunt"). So the gizmo draws its arrows as **filled triangles on a HUD overlay**, projected from the world axes each
frame (they foreshorten with the camera, so they still read as "in the 3D world"), and picks them in screen space.
This is faithful to §4's fallback and side-steps the missing arrow resource entirely.

## The only new Java: `g:poly` (a filled triangle), zero core edit

Unity arrow-heads are **filled** triangles, and the `g` draw wrapper had only `text/atext/rect/frect/line/prect/
color` — no polygon fill. So `LuaGOut` gained one primitive:

```java
// g:poly(x1,y1, x2,y2, x3,y3, ...) — a FILLED convex polygon (>= 3 points) via a GPU triangle fan.
int npt = (a.narg() - 1) / 2;                    // arg1 = self; then x,y pairs
if(npt < 3) return NIL;
float[] data = new float[npt * 2];
for(int i = 0; i < npt; i++) {
    data[i * 2]     = (float)(d.tx.x + a.arg(2 + (i * 2)).todouble());
    data[i * 2 + 1] = (float)(d.tx.y + a.arg(3 + (i * 2)).todouble());
}
d.drawp(Model.Mode.TRIANGLE_FAN, data);
```

Both backings are **public**: `GOut.drawp(Model.Mode, float[])` (the same vertex primitive `fellipse`/`frect2` use)
and the `GOut.tx` translation field. Adding `d.tx` per vertex makes a poly line up with `g:line`/`g:frect` on the
same surface — so this is **zero `haven` edit**, purely additive to our own wrapper, and reusable by any addon (not
just the gizmo). It is intentionally **not** clipped to the widget bounds (like `GOut.fellipse`); it is meant for
full-screen overlay draw where there is nothing to clip against.

## `gizmo.lua` — the bundled library (D-031)

One file, loaded **before** `main.lua` (manifest `files` order), installing a single global `gizmo(target, opts)`
into the (per-addon, sandboxed) env — the multi-file channel, since addon files share one env and `require` is
withheld by the sandbox. It mirrors the api-reference `hafen.ghost.gizmo(target, opts)` shape, promotable to a shared
`hafen.ghost.gizmo` later with no behaviour change.

**Draw** ([`hafen.ui.overlay`](../api/ui.md)). Each frame: project the target centre + the two world-axis tips
(`target:pos()` + `hafen.player.worldToScreen`), draw a red **X** arrow and a green **Y** arrow (shaft `g:line` +
filled-triangle head `g:poly`) and a yellow **centre** square (`g:frect`). The hovered/active axis lights up. The
arrow-head is a fixed **screen** size, so it stays a grabbable target at any zoom even though the shaft length is
world-projected.

**Pick** ([`hafen.hook.input`](../api/hooks.md) `"mapview"` `"mousedown"`). On press, hit-test the cursor (mapview-
local pixels — the same space `worldToScreen` returns, so the test is exact) against the centre (free), then the
nearest axis within tolerance. On a hit → `ev:preventDefault()` (the map neither clicks nor pans, and the ghost's V2
select does not fire) → start the drag. On a **miss** we do not preventDefault, so ordinary map clicks / ghost
selection still work. A hover `mousemove` hook (never preventDefault) feeds the highlight.

**Drag** ([`hafen.hook.grab`](../api/hooks.md) + `screenToWorld` + `snapPlace`). Arming the grab on the real
mousedown makes it a true press-drag-release: the grab captures the move + the terminating up (so the **camera stays
put**), and the FIRST up IS the drop — none of the mousedown-vs-pick timing ambiguity a "click an arrow-ghost to
arm" model would have. Each move raycasts the ground, **constrains to the axis**, and snaps to the placegrid:

```lua
if axis == "x" then      local s = hafen.map.snapPlace(w.x, d.sy, fine); nx, ny = s.x, d.sy   -- X varies, Y pinned
elseif axis == "y" then  local s = hafen.map.snapPlace(d.sx, w.y, fine); nx, ny = d.sx, s.y   -- Y varies, X pinned
else                     local s = hafen.map.snapPlace(w.x, w.y, fine); nx, ny = s.x, s.y     -- centre = free
end
self.target:move(nx, ny, d.a)                                                                 -- keep facing
```

The axis constraint is clean because `MapView.placeSnap` snaps each component **independently**, so
`snapPlace(w.x, d.sy).x` is the snapped X regardless of the pinned Y (captured at grab start — the axis line through
the target). SHIFT → the fine sub-tile placegrid (**D-033**). Release → `onCommit(pos)`.

## Threading

Nothing new over V5a. The draw runs on the render thread; the input-hook and grab callbacks run on the frame thread;
`screenToWorld`'s callback runs on the render thread — **all under `synchronized(ui)`**, so every `callLua` is
serialized (the L3/V2 guarantee). `screenToWorld` is coalesced (`pending`) so at most one GPU raycast is in flight.

## Lifecycle

The gizmo's overlay + input hooks + grab are all **bridge-owned** (torn down on `:reload`/disable/relog), so the
engine layer never leaks. On top of that, `planner` **detaches** the gizmo whenever its target changes or dies —
selecting another ghost, `:planner grab` (mutually exclusive), `remove`/`clear` of the gizmo'd ghost, and
`OnEnterWorld` (relog). `gz:detach()`/`:destroy()` is idempotent and releases any active grab.

## The planner wiring (`:planner gizmo`)

`:planner gizmo` toggles the gizmo on the selected ghost; `onCommit` re-anchors the record to the grid it now sits on
(`hafen.map.gridPos`) and persists — exactly like the V5a `commitDrag`. `hello` is untouched (it stays the read-only
harness; the gizmo is the V-series example, D-031). Version → **planner v0.3.0**.

## Files changed

- **`src/io/brodgar/addon/LuaGOut.java`** — new `g:poly(...)` filled-polygon primitive (+ `import haven.render.Model`).
  Zero `haven` core edit (public `GOut.drawp` + `GOut.tx`).
- **`addons/planner/gizmo.lua`** — **new**: the bundled Lua transform-gizmo library (draw / pick / axis-locked snapped
  drag). Installs the `gizmo(target, opts)` global.
- **`addons/planner/main.lua`** (v0.3.0) — `:planner gizmo` (toggle), gizmo lifecycle (detach on select-change /
  grab / remove / clear / relog / disable), help + header + load line.
- **`addons/planner/manifest.json`** — `files` now `["gizmo.lua", "main.lua"]` (gizmo first), v0.3.0, description.

## Try it in-game (V5b DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (Java engine change — no hot-reload). Then, in the world
(`planner` is default-enabled):

1. `:planner place` a blueprint ghost, then **click** it to select (warm highlight).
2. `:planner gizmo` — red/green **arrows** + a yellow **centre** square appear on the ghost.
3. **Drag the red arrow** → the ghost slides on world-**X** only; **green** → world-**Y** only; **centre** → free.
   It **snaps to tile centres**; **hold SHIFT** for the fine sub-tile placegrid (try `:placegrid 4`/`2`/`16` first — it
   follows the setting, **D-033**). The **camera does not pan** while dragging. Release to drop (re-anchored +
   persisted → relog reloads it there).
4. `:planner gizmo` again detaches it; selecting another ghost / `:planner grab` / `:reload` mid-drag leaves no stuck
   grab, no leaked overlay.

## Deferred

- **Rotate ring** (`snapAngle`/`:placeangle`, reuse `plobagran`) + **uniform scale** (`g:scale`) → **V6**.
- **Draw-on-top + constant screen-size handles** (Unity polish; the head is already screen-sized, the shaft is not) → V6.
- **Promote to a shared `hafen.ghost.gizmo`** — a thin Java shim that runs the bundled Lua, once a second addon wants it.
- **Multi-select / group transform** — post-V6 (single-target gizmo first, per spec §10).
- **Non-fullscreen MapView** — the drawn handles assume the MapView is fullscreen at the screen origin (like the 2b
  overlays); hit-testing is unaffected (both sides are mapview-local).
