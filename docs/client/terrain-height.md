# Terrain height: where a height comes from and who reads it

> The height chain — from the `z` array a grid arrives with, through the one tile-corner read every
> drawn height goes through, to the mesh, the surfaces the tilers hand out, the placers every object
> stands on, the camera and the record — and the ridge model that turns a height step into a cliff, or
> into a wall. Which cuts are drawn at all is
> [terrain-raster.md](terrain-raster.md); what a cut's mesh is made of is
> [ground-detail.md](ground-detail.md).

## The height chain: where it lives

| What | Where |
|---|---|
| The streamed heights | `MCache.Grid.z`, one `float` per tile corner of the grid, filled by `Grid.fill` from the map message. `Grid.getz(Coord)` indexes it and `Grid.getfz(Coord)` is that read under the `MapSource` name. Both stay raw whatever is drawn |
| The record | `MapFile.Grid.from(MCache, MCache.Grid)` copies `cg.z[i]` into the record's `zmap` (from `MapFile.update(MCache, Collection<Grid>)`), and the zoom pass takes `minz` over the record's own `getfz`. `MapFile.View` is a `MapSource` over the record, and what the minimap's cliff lines read through `Ridges.brokenp(MapSource, Coord)` |
| The DRAWN tile-corner read (fork) | `MCache.getfz(Coord tc)`: `getrealfz(tc)` first, then the plane when the flat-terrain switch is on. Every drawn height below goes through it, which is what makes one seam sufficient |
| The STREAMED tile-corner read (fork) | `MCache.getrealfz(Coord tc)`: `getgridt(tc)` then `Grid.getz` — what `getfz` answered before the fork. Read by the addon API's `world:height` and by the cliff detection in `Ridges` |
| The interpolated reads | `MCache.getcz(double, double)` interpolates the four corners of the tile a point is in, each through `getfz`; the `Coord2d`/`float`/`Coord` overloads wrap it and `getzp(Coord2d)` is the same number as a `Coord3f`. `getrealcz(double, double)` (fork) is `getcz` over `getrealfz`. `MCache.zsurf` is a `ZSurface` whose `getz(Coord)` is `getfz` |
| The surface interface | `MCache.ZSurface`: `getz(Coord)` is the corner, `getz(Coord2d)` the default bilinear over four corners, `getnorm`/`getnormt` the normal from those same four. `MCache.getz(SurfaceID, …)`, `getzp(SurfaceID, …)` and `getnorm(SurfaceID, …)` reach the grid, take the tile's cut through `Grid.getcut` and ask `MapMesh.getsurf(id, tiler)` |
| The surface a cut hands out | `MapMesh.getsurf(SurfaceID, Tiler)`, cached per `(id, tiler)` on the mesh, answers `Tiler.getsurf(MapMesh, SurfaceID)`: a `Tiler.MapZSurface`, whose `getz(Coord)` is `m.map.getfz(tc)` at call time — the surface reads the cache, not the mesh's vertices |
| The water's bottom | `WaterTile.getsurf` answers a `WaterTile.BottomSurface` for any surface under `SurfaceID.trn`: `super.getz(tc)` minus `BottomData.depth` at that tile, so what stands in water stands on the ground's read less the depth. `WaterTile.drawstate` reads `getcz` for the fog line and `WaterTile.slopes` reads `getfz` differences for the flow shading |
| The mesh's vertices | `MapMesh.MapSurface`: one `Vertex` per tile corner of the cut, its `z` from `map.getfz(ul.add(x, y))` at build time. Rebuilt lazily through the ground stamp `MCache.Grid.getcut` compares ([ground-detail.md](ground-detail.md#the-cut-lifecycle-where-it-lives)) |
| The placers | `Gob.Placer.getc(rc, a)` is where an object stands. `MCache.mapplace`, the default, is a `Gob.DefaultPlace` over `SurfaceID.map`, reading `map.getzp(surf, rc)` every call. `Drawable.placer` builds the others from the resource's `place` prop: `surface` (a `DefaultPlace`), `incline` (`Gob.InclinePlace`), `base` (`Gob.BasePlace`, the lowest corner of the obstacle outline), `line` (`Gob.LinePlace`) and `plane` (`Gob.PlanePlace`, the plane through the outline's points). `Gob.getc` → `getrc` → `placer().getc`; `Gob.Placed.autotick` recomposes the placement from it every tick |
| The camera | `MapView.getcc` is the player's `Gob.getc`, or `getzp(cc)` without one. The free camera (`MapView.RTSCam`) reads `getzp` under the point it looks at and, when that throws `Loading`, `Sessions.groundz`, which asks every other placed session's `map.getzp` for the same ground |
| The ground decals | `MapMesh.groundmod` reads `getcz` at the decal's centre; `CSprite.addpart` reads `getcz` per part |
| The served flavor terrain | `Tileset.Flavor.Terrain` is a `MapSource` over a `MapSource` (the `MCache.Grid`), what a served `Tileset.Flavor.Factory` sees. Fork: its `getfz` answers the plane when the ground is drawn flat, so flavor code that reads heights — `Ridges.brokenp(trn, tc)` for a cliff-edge piece — agrees with the mesh |
| Who else is a `MapSource` | `MCache` itself, `MCache.Grid` and `MapFile.View`. Served code handed the cache reads the drawn height; handed a grid or the record, the streamed one |

**Fork.** The flat-terrain switch (`perf-flatterrain`, [prefs-and-options.md](prefs-and-options.md))
forks the chain at `MCache.getfz`: the grid is reached exactly as before, then the plane is answered.
`getrealfz` and `getrealcz` are the streamed reads beside it. A write bumps the ground generation, so
every drawn cut re-meshes lazily, and tells every session's `MCache` its heights moved
(`MCache.heightschanged`), so a placer that caches on `MCache.chseq` re-reads the ground on its next
tick.

## The ridge model: where it lives

| What | Where |
|---|---|
| A cliff tile | `TerrainTile.RidgeTile` (factory `trn-r`): a `TerrainTile` implementing `Ridges.RidgeTile`, whose `breakz()` is the tileset's `rthres` (default `20`), with `rcons`, a `Ridges.TexCons` over the tileset's `rmat` material and its `texh` |
| The per-cut model | `Ridges`, one per `MapMesh` through `MapMesh.DataID Ridges.id` (`m.data(Ridges.id)`), a `ConsHooks`. Its constructor runs `breaks()` once over the cut and allocates `edges`, `edgec`, `gnd` and `ridge` |
| Which edges are broken | `Ridges.breaks()`: for every tile corner of the cut plus one, the north edge (`eo(c, 0)`) and the west edge (`eo(c, 3)`) are broken when the height difference exceeds `breakz` (plus `EPSILON`) of **both** tiles beside the edge — `bz` is `+∞` for a tile that is no `RidgeTile` — so a broken edge always lies between two ridge tiles and both call `model`. `breaks(Coord tc)` answers a tile's four edges in the order N, E, S, W |
| Which two edges a tile owns | `eo(tc, e)` indexes a cut-wide edge array: `eo(tc, 1)` is `eo(tc + (1, 0), 3)` and `eo(tc, 2)` is `eo(tc + (0, 1), 0)`, so a tile's east and south edges are its neighbours' west and north, and an edge on the cut's east or south border belongs to the next cut's tile `(0, y)` or `(x, 0)`. `makeedge` folds `e == 1` and `e == 2` onto the neighbour the same way |
| The five shapes | `Ridges.model(Coord tc)`: no broken edge answers `false`; one (`isend`) is `modelcap`; two opposite is `modelstraight`; two adjacent (`isdiag`) is `modeldiag1`; all four with one diagonal under the threshold (`isdiag2`) is `modeldiag2`; anything else is `modelcomplex`, whose `ArrayIndexOutOfBoundsException`/`NegativeArraySizeException` is caught and issued as the `ridge crash` `Warning`. Each stores the split ground in `gnd[ts.o(tc)]` and the cliff faces in `ridge[ts.o(tc)]` and answers `true` |
| The columns | `makeedge(tc, e)` builds one column of `Vertex` up a broken edge, `segh` (`8`) units per segment, jittered by the cut's `grnd` and bent by `cfac` toward the low side (`edgelc(tc, e)`: whether corner `e` is lower than corner `e + 1`); `ensureedge` caches it in `edges[eo]` and its two column ends as **copies** in `edgec[eo]` |
| The cliff faces | `connect(tc, l, r)`: two columns bottom-to-top become one `RPart`, faces added to the `MapSurface` through `mkfaces`; it fills `rcx` (0 for `l`, 1 for `r`), `rcy` (0..1 up the column), `rn`, `rh` (the column heights), `ledge` and `uedge`. `RPart(RPart...)` merges parts through `mapvertices`/`mapridges`; `MPart(MPart...)` needs at least one part |
| What the faces wear | `Ridges.TexCons.faces(MapMesh, MPart)`: the tile's `rmat` material, texture coordinates `(rcx, rcy × tiles)` with the tiles counted from `rh` over `texh`, tangent and bitangent for the bump map. `testcons` colours by `rcx`/`rcy` instead |
| Laying | `RidgeTile.model` calls `super.model` when `Ridges.model` answers `false` (plain ground). `RidgeTile.lay(m, lc, gc, cons, cover)` lays `gnd` through `laygnd` or falls back to `super.lay`, and lays the ridge under a cover's `cons` only when `laygnd` answered; `RidgeTile.lay(m, rnd, lc, gc)` calls `super.lay` then `layridge(lc, rcons)` unconditionally — `layridge` answers `false` on a `null` entry |
| After the build | `Ridges.clean()` (from `ConsHooks`) keeps only `edgeo`, the column ends' jitter per edge, and drops `edges`/`edgec`; `edgeoff(MCache, tc, edge, hi)` reads it for served code; `getrdesc(tc)` answers a tile's `RPart` or `null` |
| The minimap's cliff lines | `Ridges.brokenp(MapSource, Coord)`: a tile with any edge over the smallest `breakz` around it, read off the `MapSource` it is handed — the record, for the minimap |
| The cliff's lip | A served flavor, `gfx/tiles/flavor/ridge-edge` (`RidgeEdge`, referenced by the `ridges/edge-*` resources a tileset lists): in the cut's flavor pass it takes each tile's `RPart` through `Ridges.getrdesc` off `MCache.Grid.getcut`, follows its `uedge` rows and builds a tube along them, offset by `trn.map.getfz(area.ul)` — the drawn read — under a `GridObj` placed by `mapplace`. It is a flavor object, so it lives in `Cut.fo`, not in the mesh |

**Fork.** `hz(Coord)` is `m.map.getrealfz` and is every height `breaks`, `edgelc`, `makeedge`, `tczs`
and `isdiag2` read, so cliffs are found where the streamed heights put them whatever the ground draws;
with the switch off it equals `getfz`. `Ridges.flat` reads the switch once per build. Flat, the cliff
keeps upstream's shape and height and stands on the plane: `makeedge` puts a column's base at `0`
instead of its low corner (the column's jitter, bend and segments are upstream's, its height the real
drop), `tczs` answers the complex tile's corners relative to their lowest so its centre column stands
on the plane too, the five shapes skip their split ground part (`gnd` stays `null`, `model` answers
`false`, so `RidgeTile.model` lays plain ground and `laygnd` falls through), and every ridge part goes
through `wall(tc, l, r)`: upstream's `connect` part plus, flat, the same two columns handed the other
way round from `back(column)` copies — one back copy per column, kept in `backs` and dropped by
`clean` — because the scene culls back faces and the plane has no low side for the wall to face.
`layridge` draws it in the tileset's cliff texture, tiled by the real drop. The back part's `ledge`
and `uedge` are emptied, so the lip is drawn once. The switch bumps the flavor stamp as well as the
ground stamp, and `Cut.fo`'s build calls `Deferred.current()` on the cut's mesh first — `get()`, but
throwing the pending rebuild's `NotDoneException` while one is in flight, so the flavor pass
reschedules behind the mesh it reads instead of reading the one about to be replaced.

## Gotchas

- **`MCache.getfz` is on the `Loading` path, so the flattened read reaches the grid first.** The comment
  above `MCache.getgridt` says it: `gettile`, `getfz`, `getcz` and `getzp` all reach the grid through
  `getgrid`, which on a miss puts a map request on the wire and throws `MCache.LoadingMap`, and
  `MapView.draw` turns a camera `Loading` into the black *Waiting for map* screen. A `0.0` answered
  without reaching the grid would stop requesting the ground the camera needs. `tileheld`/`groundheld`
  are the quiet lookups for a reader that must not ask.
- **The record must never read the drawn height.** `MapFile.Grid.from` copies `MCache.Grid.z`; the zoom
  pass and `MapFile.View` read the record's own `getfz`. Flatten `Grid.getz` and the map file records a
  flat world and the minimap loses its cliff lines.
- **The drawn surface is the mesh's `ZSurface`, which reads `getfz` at call time.** `MapZSurface.getz`
  is `m.map.getfz(tc)`, not the vertex the mesh was built from — so the surface an object is placed on
  flattens the instant the switch flips, before the cut re-meshes. Reading `getcz` where the surface is
  meant is a different height wherever a tiler overrides `getsurf` (the water's bottom).
- **Objects move to the plane a tick before their cut re-meshes.** `Gob.Placed.autotick` recomposes
  the placement every tick from `getc`; the mesh follows cut by cut through the ground stamp. For the
  seconds a cut takes, an object stands on the plane under a hill still drawn — or on a hill drawn under
  a plane already there.
- **Three placers cache their height on `MCache.chseq`.** `Gob.BasePlace`, `LinePlace` and `PlanePlace`
  keep their `z` until `chseq` moves, the position moves or the angle moves, and only `mapdata2` — a
  grid's arrival — bumps `chseq`. A height that moves without a grid arriving is invisible to a
  stationary building until `MCache.heightschanged` is called.
- **A wall's back face is built from copies, never the front column's own vertices.** `MapSurface`
  averages a vertex's normal over the faces it is in; one vertex in both faces of a wall would average
  to nothing. `ensureedge` copies its column ends into `edgec` for the same reason. Positions coincide,
  so there is no crack.
- **The lip is built from the mesh the flavor pass finds, and `Deferred.get` hands out the old one
  while a rebuild is pending.** `Cut.invalidate` at a grid's arrival and the lazy stamps in `getcut`/
  `getfo` both queue the two builds together; at load the flavor's `getcut` blocks on the first mesh,
  but on a re-mesh it reads the old `RPart` and the lip floats where the old ridge top was, one offset
  per cut. `Deferred.current()` in `fo.build` is what orders them; anything else that reads a cut's
  mesh from a deferred build wants the same call.
- **A complex tile's wall is per column, not per tile.** An edge column is shared by the two tiles
  beside it and stands at `0` from its own low corner; the centre column stands at `0` from the tile's
  lowest corner. Where an edge's low corner is above the tile's lowest, the wall between them slants
  by the difference. Every other shape connects edge columns only and is exact.
- **A wall on a cut border is the owning cut's, from heights that may sit in the next grid.** `breaks`
  reads one corner past the cut on every side, so a border edge's wall is built by the cut whose tile
  owns it from `getrealfz` reads that may miss and throw `Loading` — the same `Loading` the ridge
  model always risked, caught by the cut's `Deferred` and retried.
- **`MCache` implements `MapSource`.** A helper written over `MapSource` and handed the cache reads the
  drawn height; handed `MCache.Grid` or `MapFile.View`, the streamed one. Say which one a caller holds.

## See also

- [ground detail](ground-detail.md) — the mesh a cut is made of, and the stamp a height change re-meshes through
- [which ground is drawn](terrain-raster.md) — the rasters that ask for cuts
- [state roots](state.md) — `MCache` and its grids
- [the map database](mapfile.md) — the record the streamed heights are copied into
- [the 3D world](world-3d.md) — the placers and the scene an object stands in
- [preferences and the Options window](prefs-and-options.md) — the `perf-*` prefs
