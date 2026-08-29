# Which ground is drawn: the terrain rasters

> `MapView`'s terrain display lists — the one bolted to the player, the ones over another cache, and what
> bounds each. What is drawn *on* that ground is the ground-overlay half of [world-3d.md](world-3d.md).

| What | Where |
|---|---|
| **Which ground is DRAWN — the terrain display list** | `MapView.MapRaster` (private inner) → the public `Terrain` and its `Grid main`/`flavobjs`. `MapRaster.tick` — the base default, which `Terrain` takes and every other raster overrides — sets `area = Area(cc - view, cc + view + 1)` around the player's CUT (`getcc().floor(tilesz).div(MCache.cutsz)`, `view = 2`, `cutsz = 25×25` tiles) ⇒ the drawn terrain is only **~50–75 tiles** across from you; `Grid.tick` then adds/removes one scene slot per cut, keyed in `Grid.cuts`, which therefore holds a cut **exactly while that cut's mesh is in the scene**. ⚠️ That is a GRID smaller than `MCache.grids`: map data is dropped only when the server says so (`invalblob` type 1 → `MCache.trim`, which has **no caller inside the client**), so "the grid is loaded" is true well past the visible edge — test `cuts` when the claim is about what the player SEES. Fork: `MapView.grounddrawn(Coord2d)` (`// addon:`) + a `groundChanged()` tap at `Grid.tick`'s two mutation points, which is how a client-only gob stops drawing over the void. That tap is gated on `MapRaster.liveground()`, true by default and overridable: `grounddrawn` reads `terrain.main.cuts` **alone**, so a raster whose cuts are not that ground wakes the addon layer's placement pass for a set it never reads |
| **A SECOND source of ground in one scene** | Fork: `MapView.RecallTerrain extends MapRaster` over a second `MCache` filled from the map database ([mapfile.md](mapfile.md)) instead of from the wire — the same pattern as `MapView.SessionTerrain`, which rasterizes another *session's* `MCache` into this scene. What separates such a raster from `Terrain`: its `area` is centred on **the camera** (`RTSCam.center()`) rather than on `getcc()`, and is sized in whole GRIDS, because a grid is the unit its source reads. It **decides what that source reads** — `RecallTerrain.tick` builds the wanted grid set and `MapView.recalltick` hands it over to the source — so the one owner of that decision is the one that knows where the camera is pointing and what of that is on screen. What it draws is `MapView.recallrange` grids and what the source fills is one grid wider: `MapMesh.dotrans` reads a tile across the cut edge and the corner heights need the same tile, so a cut at the fill's own edge throws `MCache.LoadingMap` and never completes. It draws only grids the source is already holding (`AddonWidgets.loadedGrid`), never through a `getcut` that would ask for one. It yields every cut inside `terrain.area`, because both sources hold the same ground there and one mesh on its twin is z-fighting, not a merge — the same rule `SessionTerrain` obeys against the anchor. It culls with `cutvisible` unconditionally rather than on `ui.gprefs.cullterrain`, because its slot carries `ShadowMap.maskshadow` anyway, which is the one thing that option buys an invisible cut. And it answers `liveground()` false. Installed in `basic` with the `rts` camera and removed with it, with `MapView.recallon`, and whenever the source cannot vouch for the offset its ground was read through. What tells it apart from live ground is a **shader state on its slot** — a desaturation compiled into every tileset material below it (recipe in [world-3d.md](world-3d.md#recolouring-ground-a-sheet-over-it-or-a-shader-under-it)), which is one program and no second mesh; the state is one cached instance pushed through `Slot.ostate`, or `null`, so the switch rebuilds nothing — not the program, not the slot tree, not a cut |
| **Where such a raster's settings live** | Fork: `MapView.recallon`, `MapView.recallrange` and `MapView.recallgrey` — three `static` fields with like-named prefs, each written live-and-persisted in **one statement** the way `MapView.invcamx` is, so neither half can move without the other. They are `static` because the settings are the **client's**: every session up draws its own remembered ground out of its own record, and a switch the user flips once has to move all of them. `MapView.recallrange` is the DRAWN reach in grids and the source is filled one grid wider for `dotrans`'s margin, so the raster's own `r` is the field and the fill's radius is the field plus one; both are read per tick, which is what makes a range change apply with nothing to rebuild and nothing to tell. `MapView.recallrangemin`/`recallrangemax` are the bounds, stated once and read by the panel's `HSlider`, the field's own clamp and the Lua refusal. `:recall` in `cmdmap` takes **no argument** and is a report: `MapView.recallgridsheld`/`recallgridsread`/`recallcutsdrawn`/`recallcutswanted` (`// addon:`) are the four numbers it prints, and the same four an addon reads back |
| **Bounding what such a raster DRAWS** | Fork: a camera-centred `area` is as large as the view is wide — 5×5 grids at `cutn = 4×4`, the default `MapView.recallrange`, is **400 cuts** where `Terrain` keeps 25 — and `MapMesh.build` is two passes over 625 tiles plus `dotrans`'s eight neighbour reads each, then a slot compile and a VBO upload, on the `Defer` threads every loading thing in the client shares. `MapRaster` has no budget of its own, and `Grid.tick` calls `getcut` for every cut of `area` in **iteration order**, so the bound goes in `skipcut`. `RecallTerrain.tick` decides it in two passes, and the first is per GRID: `MapView.gridvisible(gc)`, the grid's own box pushed out one cut on every side, and `AddonWidgets.loadedGrid`. Only the cuts of a grid that survives both are frustum-tested at all, so the default range costs a couple of dozen box tests instead of `cutvisible`'s eight `clipxf` for each of 400 cuts — which is what lets the whole tick run **every ctick**, with no clock of its own. `gridvisible` is `cutvisible`'s box a cut wider, so a grid it refuses holds no cut the second pass would have kept and the two cannot disagree about the edge. The survivors are sorted by distance from the camera's own cut and admitted until `recallcutcap()`, a stated share of the drawn square rather than one number for every range, so the cap means the same fraction of what was asked for wherever the dial stands. `skipcut` is then a set membership, and `Grid.tick` removes the slot of everything the budget or the view left out. ⚠️ A cap derived from the frustum instead is not a cap: the camera can frame more ground than this client was built to draw |
| **Bounding what it BUILDS, which is a different budget** | Fork: `MapView.recallmaxbuild` is a **concurrency target** and not a quota per tick — `Math.max(2, Defer.maxthreads - recallbuildreserve)`, a stated share of the pool that does the building ([boot-and-loop.md](boot-and-loop.md)). It is charged only for a cut that is in neither `Grid.cuts` nor `MCache.cutbuilt` (`// addon:`), so what it counts is what has been started and has not yet arrived, and a cut already built is admitted for free: `continue` past the budget, never `break`, or a run of unbuilt cuts stops the walk before it reaches the built ones behind them. Paired with a tick on the frame rather than on a period, throughput is that target over one build's own **latency** instead of over a clock — a finished build is replaced within a frame, where a fifth of a second left a five-millisecond mesh's slot in the budget idle for the rest of it |

**Gotcha — a raster over a cache the client must not send for cannot use `getcut` alone.** `MCache.getcut`
ends in `getgrid`, which on a miss calls `request(gc)` and throws `LoadingMap` — so a `MapRaster` walking an
area wider than its source has filled queues every absent grid in it. On the live cache that is the point; on
one filled from disk it fills a queue nothing may ever send, and buries the request count that is the only
evidence such a source is behaving. Ask the cache what it holds first (`AddonWidgets.loadedGrid`, `// addon:`)
and skip the cut when it holds nothing. That is also what drops a cut whose grid has just been disposed:
`MapRaster.Grid.tick` removes the slot of any cut its `skipcut` starts refusing, so the stale mesh leaves the
scene at the next tick instead of being drawn after its `dispose()`.

**Gotcha — what such a source keeps is an LRU, and the raster's own `cuts` is what it may never drop.**
`MCache.trim` keeps a rectangle, and a rectangle cannot say *keep what was built*: one grid of travel puts a
whole rank of grids outside the square, so panning one way and back re-reads and re-meshes every one of them.
`MCache.drop(Collection<Coord>)` (`// addon:`) disposes exactly the grids it is handed instead, and it names
what **goes** rather than what stays — the keep set is decided on the UI thread while grids arrive from a
`Defer` thread, and naming what stays would dispose one read off the disk before anything could draw it.
⚠️ Order the two sides the right way round: an `MCache.Grid` disposes its cut meshes with itself and
`MapRaster.Grid` holds a scene slot per cut it has drawn, so **the raster's cut map is the authority over the
budget** — a grid under one of its entries is kept whatever the order says, or its slot goes on drawing a
disposed mesh with nothing to notice. `trimall` has no next tick to notice either: it disposes every `Grid` at
once, and a raster removed in the same breath never ticks again, so **the slot comes out first and the dispose
follows**.

**Gotcha — `skipcut` runs OUTSIDE `Grid.tick`'s `Loading` guard, and nothing above it catches one
either.** `Grid.tick` wraps only the `getcut` half in `try`/`catch(Loading)`; the `skipcut` test sits ahead
of that block, so a `Loading` out of it leaves `tick` altogether. There is no net under it: `MapView.oltick`
ticks its overlay rasters in a loop *after* its own `catch`, and `MapView.tick` calls `oltick` inside
`synchronized(glob.map)` with no guard of its own — so the throw takes the whole frame. A `skipcut` that
consults the cache (`MCache.getgrid`, `Indir.get`) must therefore **swallow `Loading` and answer
conservatively**: yield nothing, and let the `getcut` that follows throw where it is caught.

**Gotcha — a cut's MESH outlives the slot that drew it, so "not in `Grid.cuts`" never means "needs
building".** `MapRaster.Grid.removed` clears `cuts` when the raster leaves the tree, and `Grid.tick` drops
the slot of every cut `skipcut` refuses — neither touches `MCache.Grid.Cut.mesh`, a `Deferred` that holds
its built value until the whole `Grid` is disposed. So a raster that came out of the scene and went back in
finds every cut it had built still built, and `Deferred.get` hands it back with no `Defer` task at all;
`MCache.numcuts` counts exactly those (`Cut.mesh.cur() != null`) and `MCache.cutbuilt` (`// addon:`) asks it
of one cut, as a plain lookup that neither builds nor requests. ⚠️ A budget that reads absence from `cuts` as
"being built" therefore throttles work nobody is doing: it is the right test for what is IN FLIGHT only
while a dropped cut also loses its mesh, and a source that keeps its grids breaks that coupling — admit an
already-built cut for free and charge the budget for the rest.

## See also

- [the 3D world](world-3d.md) — the scene these rasters put their cuts into, and the ground overlays over them
- [the camera](camera.md) — what a camera-centred `area` is centred on, and the frustum that is not a cap
- [the map database](mapfile.md) — the recorded map a second cache is filled from
- [the boot and the loops](boot-and-loop.md) — the `Defer` pool a build budget is a share of
- [several sessions at once](multi-session.md) — `SessionTerrain`, the same pattern over another session
