# V6 — Gizmo: rotate (+ placeangle) + scale + polish

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the updated
> `planner` into `bin/addons/`. **Headless-verified:** the `snapPlaceAngle` math (45° coarse + `π/plobagran` fine,
> normalized to `(-π,π]`) checked in isolation; `gizmo.lua` loads under a real `Sandbox.create()` env and installs
> the `gizmo` global, its target-validation still rejects nil / `{}` / pos-only **before** touching `hafen`, the
> `atan2`-from-`math.atan` helper is quadrant-correct in the sandbox, and `main.lua` parses (14 checks). The gizmo's
> live behaviour (projection, screen picking, the drag, the scaled render) needs a GL/session, so it is **in-game
> DoD-verified**. **Zero `haven` core edit** — the two Java pieces are additive: `hafen.map.snapAngle`/`placeAngle`
> on the bridge (reading the **public** `MapView.plobagran`) and a scaling `Location` on our own `GhostGob`.
> **Java engine change ⇒ `ant` rebuild + a full client restart** before testing. **In-game DoD verified ✅**
> (rotate ring snaps on the `:placeangle`, the scale box grows/shrinks the ghost, move still snaps, and a relog
> restores position + facing + scale).
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§4 the gizmo,
> §4.1 snapping, §10 "scale = a scaling `Location` on `placed`"), decisions **D-031** (gizmo = Lua over Java
> primitives) / **D-033** (reuse the client's placegrid/placeangle) / **D-029** (client-only, safe-tier).

## What V6 is

V5b shipped the **move** gizmo (drag a ghost by its axis arrows). V6 finishes the transform: **rotate** (a ring that
snaps on the `:placeangle`), **uniform scale** (`g:scale`, "the least-native piece" per the spec), and the **polish**
(draw-on-top + constant screen-size handles). Per **D-031** the gizmo behaviour stays a **bundled Lua library**
([`planner/gizmo.lua`](../../../addons/planner/gizmo.lua)); V6 adds only two small, additive Java primitives.

## Java piece 1 — `hafen.map.snapAngle` / `placeAngle` (rotation snap), zero core edit

The move gizmo snaps position through `hafen.map.snapPlace` (V5a), which the maintainer factored out of the engine's
`StdPlace.adjust` into a shared static `MapView.placeSnap` (verbatim, zero drift). Rotation is **not** symmetric:
the engine's `StdPlace.rotate` is a **mouse-wheel** handler — it *increments* the facing (`plob.a + …`), so there is
no absolute-angle expression to extract. A rotate **ring** needs an **absolute** snap (map a dragged angle → the
nearest grid angle). So the bridge grows a small pure helper (the §4.1 **zero-edit mirror**, reading the **public**
`MapView.plobagran` so it still honours `:placeangle`):

```java
// AddonManager.snapPlaceAngle — the ABSOLUTE analog of StdPlace.rotate (which is wheel-relative).
private static double snapPlaceAngle(double a, boolean fine) {
    double step = fine ? (Math.PI / MapView.plobagran) : (Math.PI / 4);   // fine = :placeangle grid; coarse = 45°
    if(step <= 0) return Utils.cangle(a);
    return Utils.cangle(Math.round(a / step) * step);                     // snap + normalize to (-π, π]
}
```

exposed as `hafen.map.snapAngle(a [, fine])` (→ radians) and `hafen.map.placeAngle()` (→ `MapView.plobagran`, the
fine divisions), mirroring `snapPlace`/`placeGrid`. The **coarse** default is a fixed **45° (π/4)** — matching the
`CTRL` branch of the wheel rotate; **`fine`** = `π/plobagran`, matching the `SHIFT` branch. Note `AddonManager`
already had an unrelated `placeAngle(double)` (the 4d MapView "place"-verb angle *encoding* to server units) — the
new `hafen.map.placeAngle` is a distinct Lua facade entry that just reads `plobagran`, no Java method clash.

## Java piece 2 — `g:scale(s)` on the ghost (a scaling `Location`), zero core edit

The api-reference/spec back scale with "a scaling `Location`/`Pipe.Op` on `placed`". `GhostGob` already overrides the
`protected Gob.obstate(Pipe)` hook (V2 added the `GobClick`, V3 the tint/alpha states). V6 preps one more state:

```java
float sc = this.scale;                      // volatile, snapshotted once
if(sc != 1f)
    buf.prep(Location.scale(sc));           // uniform in-place scale
```

**Why this scales in place (T·R·S).** `obstate` runs on the gob's **child** render slot — *below* the `Placed` slot
that applies the world translate (`"gobx"`) + facing rotation (`"gob"`). `Location` composes multiplicatively down
the tree (`Transform.fin(p) = p.mul(xf)`), so the model matrix becomes **T·R·S**: the scale is applied first, around
the gob's **local origin** (its feet), then rotated, then translated — an in-place resize that keeps position and
facing. The engine's pick surface (also prepped in `obstate`) inherits the same matrix, so a scaled ghost has a
scaled click target — consistent.

**The one caveat (documented, not a bug).** A sprite that resets its own location via `Location.goback("gobx")` —
e.g. `resutil.CSprite` — drops everything applied after the translate, i.e. **both** the facing rotation and this
scale. Such resources therefore already ignore ghost *rotation*; they ignore scale for the same reason. Building /
terobj props (the planner's use case, and the ones proven to rotate in V4/V5) keep both. Since `GobState.equals`
ignores `obstate`'s output, a live scale change is applied by the existing **remove-and-re-add** of the scene slot
(`refreshGhostScene`, shared with clickable/alpha/tint) so `obstate` re-runs and reads the new field.

The Lua surface: `LuaGhost.scale` (desired state, mirrored onto the `GhostGob` like alpha/tint, applied at deferred-
create publish too), a `new{scale=…}` option, the `g:scale(s)` handle verb, and **`g:pos()` now returns
`{x,y,a,scale}`** — the ghost's full client-side transform, so a layout (or the gizmo) can read scale back. Clamped
to `0.01..100` (never 0/negative, which would collapse/invert the mesh).

## `gizmo.lua` — rotate + scale + modes (the bulk of V6)

Still one file over the primitives (D-031), still the **2D-projected** path (draw + hit-test in screen space, no
resource). New handles and a `mode` (`"move"`|`"rotate"`|`"scale"`|`"all"`, default `"all"`; `gz:setMode(m)`
switches live, `gz:mode()` reads it):

- **Rotate — a cyan ring.** Drawn as a fixed-**screen-radius** polyline circle around the projected centre; a hit is
  within a few px of the ring line. The drag reads the ground under the cursor (`screenToWorld`, world-space so it is
  camera-accurate like the move drag), computes the world angle from the pivot to the cursor, and snaps it with
  `hafen.map.snapAngle` (SHIFT = the fine `:placeangle`). It is **relative**: an offset captured on the first raycast
  (`d.rotOffset = a₀ − rawAngle`) anchors the swept angle to the grab point, so grabbing the ring does not snap the
  ghost to face the cursor — it rotates **by how far you sweep**. Applied via `target:rotate(a)` (falling back to
  `target:move(x,y,a)` for a target that only exposes `:move`).
- **Scale — a magenta box** above the ring. Pure **screen-distance** math (no raycast): grab captures the start scale
  + the cursor's screen distance from the centre; each move sets `scale = startScale · (curDist / startDist)`, clamped.
  Drag out → grow, in → shrink. Gated on the target actually exposing `:scale`.
- **`atan2` without `math.atan2`.** The world angle needs `atan2`, which some Lua 5.1 stdlibs / LuaJ builds omit. So
  the module builds it from the always-present `math.atan` (1-arg) + quadrant fixes — headless-verified correct in a
  real sandbox env, so no dependency on an optional stdlib function.

The **polish** falls out of the 2D-projected design: the overlay draws on a HUD layer **on top** of the 3D scene (no
depth occlusion — the "draw-on-top" requirement, for free), and every grab target (arrow heads, centre square, ring,
scale box) is a **constant screen size** at any zoom — only the axis **shafts** are world-projected (intentionally,
to anchor the arrows in the world). No camera-distance rescaling was needed (that was the 3D-native path's problem).

Hit-test priority when `mode="all"`: centre (free move) → scale box → nearest axis arrow → rotate ring (outermost),
each gated by the mode. Draw order puts the ring behind, then the scale box, then the move arrows/centre on top.

## `planner` wiring (`:planner gizmo [mode]`, `:planner scale`)

- `:planner gizmo` (no arg) toggles the gizmo (mode `"all"`); `:planner gizmo move|rotate|scale|all` (re)attaches
  focused on that group (or `setMode` on the live one). The gizmo's **`onCommit`** now syncs the record's **facing
  AND scale** from `p = {x,y,a,scale}` (not just re-anchoring position) and persists — so a gizmo rotate/scale
  survives a relog.
- **Scale rides the grid-anchored layout.** Each record gains a `scale` field (default 1), stored in the per-char
  `hafen.store` JSON next to `a`/`anchor`, applied on (re)spawn (`new{scale=…}`) and re-resolved with the position —
  so a relog restores **position + facing + scale**.
- `:planner scale <s>` sets the selected ghost's scale directly (the non-interactive way, like `:planner rotate`).
- `hello` is untouched (read-only harness; the gizmo is the V-series example, D-031). Version → **planner v0.4.0**.

## Threading & lifecycle

Nothing new. Draw on the render thread; input-hook + grab callbacks on the frame thread; `screenToWorld`'s callback
on the render thread — **all under `synchronized(ui)`**, so every `callLua` is serialized (the L3/V2 guarantee). The
scale drag is synchronous screen math (no raycast); the move/rotate raycasts are coalesced (`pending`, one in flight).
The gizmo's overlay/hooks/grab are bridge-owned (torn down on `:reload`/disable/relog); the planner also detaches on
select-change / grab / remove / clear / relog. A live scale change re-adds the scene slot (`refreshGhostScene`),
identical to the V3 clickable/alpha/tint path — no new lifecycle hazard.

## Files changed

- **`src/io/brodgar/addon/GhostGob.java`** — new `volatile float scale` field + `Location.scale` prep in `obstate`
  (+ `import haven.render.Location`); the T·R·S rationale in the class javadoc. Zero `haven` core edit.
- **`src/io/brodgar/addon/LuaGhost.java`** — new `float scale` desired-state field; javadoc.
- **`src/io/brodgar/addon/AddonManager.java`** — `hafen.map.snapAngle`/`placeAngle` facade + `snapPlaceAngle` helper;
  ghost `scale` parse in `newGhost` + publish in the deferred create; `g:scale` handle verb; `scale` in `g:pos()`;
  `setGhostScale` + `luaScale`/`clampScale`. Zero `haven` core edit.
- **`addons/planner/gizmo.lua`** — rotate ring + scale box + `mode` (`move`/`rotate`/`scale`/`all`) + `setMode`/`mode`,
  the rotate (relative, `snapAngle`) and scale (screen-distance) drags, a pure `atan2`. Move path unchanged.
- **`addons/planner/main.lua`** (v0.4.0) — record `scale` (persisted + restored), `:planner gizmo [mode]`, `:planner
  scale <s>`, gizmo `onCommit` syncs facing+scale, help/header/load line.
- **`addons/planner/manifest.json`** — v0.4.0 + description.

## Try it in-game (V6 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (Java engine change — no hot-reload). Then, in the
world (`planner` is default-enabled):

1. `:planner place` a blueprint ghost, then **click** it to select.
2. `:planner gizmo` — the arrows (move) + a cyan **ring** (rotate) + a magenta **box** (scale) appear on the ghost.
3. **Drag the ring** → the ghost rotates, snapping to **45°** steps; **hold SHIFT** for the finer `:placeangle` grid
   (try `:placeangle 8`/`24` first — it follows the setting, **D-033**). The camera does not pan.
4. **Drag the box out/in** → the ghost grows / shrinks (uniform). Or `:planner scale 1.5` / `:planner scale 0.5`.
5. Move still works (drag the red/green **arrows** or the centre; snaps on the `:placegrid`, SHIFT = fine).
6. `:planner gizmo rotate|scale|move` focuses one handle group; `:planner gizmo` (bare) detaches.
7. **Relog** → the ghost reloads at the same grid spot **with its facing and scale**. `:reload` mid-drag leaves no
   stuck grab / leaked overlay.

## Deferred

- **Promote to a shared `hafen.ghost.gizmo`** — a thin Java shim running the bundled Lua, once a second addon wants it.
- **Multi-select / group transform** — post-V6 (single-target gizmo first, per spec §10).
- **Non-uniform / per-axis scale**, and scale **snapping** (V6 scale is uniform + continuous; move/rotate snap).
- **`CSprite`-style resources ignore scale/rotation** (they `goback("gobx")`) — inherent to those resource types.
- **Non-fullscreen MapView** — the drawn handles assume a fullscreen MapView at the screen origin (like the 2b
  overlays); hit-testing is unaffected (both sides are mapview-local).
