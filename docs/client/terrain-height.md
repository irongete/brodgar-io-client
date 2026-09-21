# Terrain height: where a height comes from and who reads it

> The height chain — from the `z` array a grid arrives with, through the one tile-corner read every
> drawn height goes through, to the mesh, the surfaces the tilers hand out, the placers every object
> stands on, the camera and the record. Which cuts are drawn at all is
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
- **`MCache` implements `MapSource`.** A helper written over `MapSource` and handed the cache reads the
  drawn height; handed `MCache.Grid` or `MapFile.View`, the streamed one. Say which one a caller holds.

## See also

- [ground detail](ground-detail.md) — the mesh a cut is made of, and the stamp a height change re-meshes through
- [which ground is drawn](terrain-raster.md) — the rasters that ask for cuts
- [state roots](state.md) — `MCache` and its grids
- [the map database](mapfile.md) — the record the streamed heights are copied into
- [the 3D world](world-3d.md) — the placers and the scene an object stands in
- [preferences and the Options window](prefs-and-options.md) — the `perf-*` prefs
