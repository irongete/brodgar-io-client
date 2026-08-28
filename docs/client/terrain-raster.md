# Which ground is drawn: the terrain rasters

> `MapView`'s terrain display lists — the one bolted to the player, the ones over another cache, and what
> bounds each. What is drawn *on* that ground is the ground-overlay half of [world-3d.md](world-3d.md).

| What | Where |
|---|---|
| **Which ground is DRAWN — the terrain display list** | `MapView.MapRaster` (private inner) → the public `Terrain` and its `Grid main`/`flavobjs`. `MapRaster.tick` — the base default, which `Terrain` takes and every other raster overrides — sets `area = Area(cc - view, cc + view + 1)` around the player's CUT (`getcc().floor(tilesz).div(MCache.cutsz)`, `view = 2`, `cutsz = 25×25` tiles) ⇒ the drawn terrain is only **~50–75 tiles** across from you; `Grid.tick` then adds/removes one scene slot per cut, keyed in `Grid.cuts`, which therefore holds a cut **exactly while that cut's mesh is in the scene**. ⚠️ That is a GRID smaller than `MCache.grids`: map data is dropped only when the server says so (`invalblob` type 1 → `MCache.trim`, which has **no caller inside the client**), so "the grid is loaded" is true well past the visible edge — test `cuts` when the claim is about what the player SEES. Fork: `MapView.grounddrawn(Coord2d)` (`// addon:`) + a `groundChanged()` tap at `Grid.tick`'s two mutation points, which is how a client-only gob stops drawing over the void |
| **A SECOND source of ground in one scene** | Fork: `MapView.RecallTerrain extends MapRaster` over a second `MCache` filled from the map database ([mapfile.md](mapfile.md)) instead of from the wire — the same pattern as `MapView.SessionTerrain`, which rasterizes another *session's* `MCache` into this scene. Four things separate such a raster from `Terrain`. Its `area` is centred on **the camera** (`RTSCam.center()`) rather than on `getcc()`. It is bounded by what its source has actually filled, **less one grid**: `MapMesh.dotrans` reads a tile across the cut edge, so a cut at the fill's own edge throws `MCache.LoadingMap` and never completes. Its `skipcut` yields every cut inside `terrain.area`, because both sources hold the same ground there and one mesh on its twin is z-fighting, not a merge — the same rule `SessionTerrain` obeys against the anchor. And it culls with `cutvisible` unconditionally rather than on `ui.gprefs.cullterrain`, because its slot carries `ShadowMap.maskshadow` anyway, which is the one thing that option buys an invisible cut. Installed in `basic` with the `rts` camera and removed with it; `:recall off`/`on` in `cmdmap` takes it out of the tree and puts it back. What tells it apart from live ground is a **shader state on its slot** — a desaturation compiled into every tileset material below it (recipe in [world-3d.md](world-3d.md#recolouring-ground-a-sheet-over-it-or-a-shader-under-it)), which is one program and no second mesh; `:recall wash <a>` pushes a new amount through `Slot.ostate` and rebuilds nothing |
| **Bounding a raster the camera aims, rather than the player** | Fork: a camera-centred `area` is as large as the view is wide — 5×5 grids at `cutn = 4` is **400 cuts** where `Terrain` keeps 25 — and `MapMesh.build` is two passes over 625 tiles plus `dotrans`'s eight neighbour reads each, then a slot compile and a VBO upload, on the `Defer` threads every loading thing in the client shares. `MapRaster` has no budget of its own, and `Grid.tick` calls `getcut` for every cut of `area` in **iteration order**, so the bound goes in `skipcut`: `RecallTerrain.tick` builds the wanted set once, sorts it by distance from the camera's own cut, and admits cuts until a stated cap — plus at most a few whose mesh is not yet in `Grid.cuts`, which bounds what is **in flight** and not merely what is begun, since a cut still building has no entry there. `skipcut` is then a set membership, and `Grid.tick` removes the slot of everything the budget or the view left out. ⚠️ A cap derived from the frustum instead is not a cap: the camera can frame more ground than this client was built to draw |

**Gotcha — a raster over a cache the client must not send for cannot use `getcut` alone.** `MCache.getcut`
ends in `getgrid`, which on a miss calls `request(gc)` and throws `LoadingMap` — so a `MapRaster` walking an
area wider than its source has filled queues every absent grid in it. On the live cache that is the point; on
one filled from disk it fills a queue nothing may ever send, and buries the request count that is the only
evidence such a source is behaving. Ask the cache what it holds first (`AddonWidgets.loadedGrid`, `// addon:`)
and skip the cut when it holds nothing. That is also what drops a cut whose grid `MCache.trim` has just
disposed: `MapRaster.Grid.tick` removes the slot of any cut its `skipcut` starts refusing, so the stale mesh
leaves the scene at the next tick instead of being drawn after its `dispose()`. ⚠️ **`trimall` has no such
next tick** — it disposes every `Grid` at once, and a raster removed in the same breath never ticks again to
notice, so **the slot comes out first and the dispose follows**; and an incremental `trim`'s kept rectangle
stays concentric with the drawn `area` and a grid wider, so no pan trims a grid still in `Grid.cuts`.

**Gotcha — `skipcut` runs OUTSIDE `Grid.tick`'s `Loading` guard, and nothing above it catches one
either.** `Grid.tick` wraps only the `getcut` half in `try`/`catch(Loading)`; the `skipcut` test sits ahead
of that block, so a `Loading` out of it leaves `tick` altogether. There is no net under it: `MapView.oltick`
ticks its overlay rasters in a loop *after* its own `catch`, and `MapView.tick` calls `oltick` inside
`synchronized(glob.map)` with no guard of its own — so the throw takes the whole frame. A `skipcut` that
consults the cache (`MCache.getgrid`, `Indir.get`) must therefore **swallow `Loading` and answer
conservatively**: yield nothing, and let the `getcut` that follows throw where it is caught.

## See also

- [the 3D world](world-3d.md) — the scene these rasters put their cuts into, and the ground overlays over them
- [the camera](camera.md) — what a camera-centred `area` is centred on, and the frustum that is not a cap
- [the map database](mapfile.md) — the recorded map a second cache is filled from
- [several sessions at once](multi-session.md) — `SessionTerrain`, the same pattern over another session
