# The pointer and the ground: pixel, world, pick, click

> Turning a screen pixel into a place and back, what a click resolves to, and where the client's own
> dispatch can be intercepted. The scene those coordinates belong to is [world-3d.md](world-3d.md);
> which ground is drawn under them is [terrain-raster.md](terrain-raster.md).

## The two conversions, and the space they both speak

| What | Where |
|---|---|
| Screen ↔ world | screen → world (ground raycast) `MapView.Maptest` (`Plob.Adjust.hit(Coord pc, Coord2d mc)`); world → screen `MapView.screenxf`, or per NODE `Homo3D.obj2clip(objc, state)` → `HomoCoord4f.toview(area)` — object space through that slot's own chain (placement, facing, `obstate` scale). **Both speak MAP-VIEW-LOCAL DEVICE pixels**: `screenxf` ends in `toview(Area.sized(this.sz))`, and `Maptest(Coord pc)` hands `pc` to `checkmapclick` in that same box — so either side is a `Widget.rootpos()` from a root coordinate and a `UI.scale` from a design one, and the two corrections compose in opposite orders on the two sides. What comes *back* out of `Maptest.hit` is a `Coord2d` in **world units already** — `OCache.posres` is not in this path at all. ⚠️ `screenxf(Coord2d)` projects at the **player's** z, not the terrain height under the argument: it fills in `getcc().z` (`MapView.getcc` → `player().getc()`), so for a point on a slope it answers where that point would be *at the player's altitude*, and a screen→world raycast back does not return to it. ⚠️ `toview` does the projective divide unguarded: **check `w > 0` first**, or a point behind the eye answers with a plausible number on the wrong side. The `state` `Pipe` exists only inside a pass, so the way to ask is a `PView.Render2D` — which may draw nothing and exist purely to be handed it (fork: `SurfaceDrawable`). ⚠️ **Nothing is culled anywhere in this path**: `ScreenList.draw` walks every registered slot every frame and no gob is tested against a frustum — the GPU clips. So off-screen is not free, and a feature that needs it computes it itself. `cur` is a set of **slots**, not of nodes, so the same `Render2D` standing in the tree twice is drawn **twice a frame** and one that is not in it at all is drawn none — which makes a per-frame count of a gob-anchored one an exact read of whether that gob is in the scene |
| **Placement snapping — placegrid/placeangle** | `PlobAdjust` / `StdPlace` (position, rotation); **public** `plobpgran`/`plobagran`; `:placegrid`/`:placeangle` cmds. The position half is the static `MapView.placeSnap(Coord2d, modflags)`, which `StdPlace.adjust` calls (`// addon:` seam, so a gizmo snaps identically to a building). ⚠️ **The modifier goes the way round you do not expect**: with **no** SHIFT it is the coarse snap — `mc.floor(tilesz).mul(tilesz).add(tilesz.div(2))`, the **tile centre**, so a snapped world coordinate always sits at `tilesz/2` inside its tile; SHIFT picks the *finer* `plobpgran` sub-tile grid, and SHIFT with `plobpgran == 0` is free placement, returning `mc` untouched. Nothing here touches `OCache.posres` — it is world units in and world units out |

## The pick pass, and what a click reaches

| What | Where |
|---|---|
| Pick pass (clickable entities, drag handles) | `ClickMap`, `MapClick extends Clickable`, `Clicklist`, `ClickLocation`, `Gob.GobClick` |
| **Click dispatch ← intercept point** | `MapView.Hittest` resolves pick → `Click.hit` ends in `wdgmsg("click", …)`; the voice feature also hooks here (-2019). ⚠️ **The pick is ASYNCHRONOUS, on a thread of its own**: `env.submit` queues the readback, and the completion is run from `GLEnvironment.callbacks` by `GLEnvironment.cbthread` — the `"Render-query callback thread"`, started on demand and exiting after 5 idle seconds, which is neither the UI thread nor the render thread. So the pick cannot serve anything that must answer inside the event (a press that takes a grab, for one), and — the half that is easy to miss — **whatever the callback body touches, it touches off the UI thread, while the frame mutates it underneath**: `checkmapclick` resolves its cut and its coordinate there, so any state it reaches has to be *published* for that thread rather than merely reachable from it. Fork: four `// addon:` hooks at the top of `mousedown`/`mouseup`/`mousemove`/`mousewheel` (+) run a synchronous test first and fall through when it misses |

## See also

- [the 3D world](world-3d.md) — the scene these coordinates address, and the client-only gob in it
- [which ground is drawn](terrain-raster.md) — the raster a `Maptest` finds a cut in
- [the camera](camera.md) — the projection both conversions go through
- [widget input](widget-input.md) — how the event reaches `MapView` in the first place
