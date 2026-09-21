# 161 — Flat terrain: plan

## Approach

One read flattened, two reads made explicitly real, one wall builder.

- **The seam is `MCache.getfz(Coord tc)`**, the tile-corner height every drawn thing already reads:
  `MapMesh.MapSurface`'s vertices, `Tiler.MapZSurface` (the height surface every tiler but water
  hands out, and the one `WaterTile.BottomSurface` subtracts its depth from), `MCache.getcz` and
  through it `getzp`, `MCache.zsurf`, and so the placers (`Gob.DefaultPlace`, `Gob.PlanePlace` →
  `getzp(surf, rc)`), the camera (`MapView.getcc`, `MapView`'s free-camera height and
  `Sessions.groundz`), the ground decals (`MapMesh.groundmod`), the water fog line
  (`WaterTile.drawstate`), the addon projections (`WorldApi` `worldToScreen`, `PatchClick`) and the
  remembered and other-session ground, which are other `MCache`s going through the same method.
  It becomes: reach the grid exactly as today (`getgridt`, so the map request and `Loading`
  protocol do not move), then answer `0.0` when `Performance.flatTerrain` is on. The real read
  moves to **`MCache.getrealfz(Coord)`**, with **`getrealcz(double, double)`** interpolating over it.
- **Two reads go real on purpose.** `WorldApi`'s `world:height` answers `getrealcz`: the API says
  what the server streamed. `Ridges` detects cliffs through a private `hz(Coord)` that reads
  `getrealfz`: where a cliff is comes from the real heights.
- **One read joins the drawn side.** `Tileset.Flavor.Terrain.getfz` — the terrain a served flavor
  factory sees — answers the drawn height, so flavor code that reads heights agrees with the mesh.
- **The record is untouched by construction**: `MapFile` reads `MCache.Grid.z` and `Grid.getfz`,
  which stay raw; `hafen.map`'s `grid:height` reads the record.
- **Cliffs become standing walls.** With the switch on, `Ridges.model` builds no ground parts (the
  tile is laid as plain ground) and, for the broken edges the tile owns, a wall one tile high on
  the edge line, two-sided, through the existing `connect` so it carries the ridge texture
  coordinates `TexCons` expects; `RidgeTile.lay` lays it through `layridge` as it lays every ridge.
- **The switch** is a thirteenth setting of `Performance`: field, setter bumping
  `groundGeneration` (the mesh stamp 160.4 put on every cut), a box on `PerformancePanel`, a verb
  on `PerformanceOptions`, the `performance()` docs — nothing new in kind.

## The option

| Panel section · control | API verb on `performance` | Preference key | `Performance` member | Type · default | Decided at | A change shows |
|---|---|---|---|---|---|---|
| **Ground** · box *Flat terrain* (after *Tile transitions*) | `flatTerrain()` / `flatTerrain(flag)` | `perf-flatterrain` | `boolean flatTerrain`, setter `flatTerrain(boolean)` bumps `groundGeneration` | switch · `false` | `MCache.getfz` (every drawn height), `Ridges.model` (walls instead of slopes) | the drawn cuts re-mesh lazily; objects, camera and decals move on the next tick |

## Every height read, after the change

| Reader | Reads | Flat |
|---|---|---|
| `MapMesh.MapSurface` (mesh vertices) | `MCache.getfz` | plane |
| `Tiler.MapZSurface.getz` → `MCache.getz(SurfaceID, …)`, `getzp(surf, …)` | `MCache.getfz` | plane; water bottom = plane − depth |
| `MCache.getcz`, `getzp(pc)`, `zsurf` | `MCache.getfz` | plane |
| `Gob.DefaultPlace.getc`, `Gob.PlanePlace.recalc` | `getzp(surf, rc)` | on the plane |
| `MapView.getcc`, free-camera height, `Sessions.groundz` | `getzp` / `Gob.getc` | on the plane |
| `MapMesh.groundmod`, `CSprite.addpart`, `WaterTile.drawstate` | `getcz` | plane |
| `WorldApi` `worldToScreen`, `PatchClick` | `getzp` | plane (they project what is drawn) |
| `WaterTile` bank slopes (`slopes`) | `getfz` | zero: still water (`iter` guards its division) |
| `Tileset.Flavor.Terrain.getfz` (served flavor code) | `MapSource.getfz` → flattened here | plane |
| `Ridges` detection (`breaks`, `edgelc`, `isdiag2`, `tczs`, `makeedge`) | `hz` → `getrealfz` | real: cliffs found where they are |
| `Ridges.brokenp(MapSource, tc)`, `MapSource` for the minimap | the record | real |
| `WorldApi` `world:height` | `getrealcz` | real |
| `MapFile` recording (`Grid.z`, `Grid.getfz`), `LuaMapGrid` `grid:height` | the record | real |

## Files to create / modify

**161.1 — the switch and the seam**
- `src/io/brodgar/perf/Performance.java` — `flatTerrain` field, setter, in the class javadoc's list
- `src/io/brodgar/ui/PerformancePanel.java` — the box, its tooltip
- `src/io/brodgar/addon/PerformanceOptions.java` — the verb
- `src/haven/MCache.java` — `getfz` flattened (`// addon: 161.1`), `getrealfz`, `getrealcz`
- `src/haven/Tileset.java` — `Flavor.Terrain.getfz` flattened (`// addon: 161.1`)
- `src/io/brodgar/addon/WorldApi.java` — `height` reads `getrealcz`
- `docs/addons/api/client/README.md` — the row, the rule, the first sentence, the *Covers* row,
  every count of the panel's verbs
- `docs/addons/api/world.md` — the `:height` rule
- create `docs/client/terrain-height.md` (the height chain); `docs/client/README.md` — its row;
  `docs/client/state.md` — the *Map / terrain* row; `docs/client/prefs-and-options.md` — the
  Performance row; `docs/client/ground-detail.md` — a *See also* line

**161.2 — the standing walls**
- `src/haven/resutil/Ridges.java` — `hz`, `FLATWALL`, `flatwalls`, the branch in `model`
  (`// addon: 161.2`)
- `docs/client/terrain-height.md` — the ridge-model section

Each task ships its suite at `addons/161-flat-terrain.N/`.

## Risks & gotchas — the classes and members read

- **`MCache.getfz` is on the `Loading` path.** The comment above `MCache.getgridt` states that
  `gettile`, `getfz`, `getcz` and `getzp` all reach the grid through it, and that a miss puts a
  map request on the wire; `MapView.draw` turns a camera `Loading` into the black *Waiting for
  map* screen. The flattened read therefore calls `getrealfz` **first** and flattens its result: a
  `0.0` answered without reaching the grid would stop requesting ground the camera needs.
- **`MCache.Grid.getz`/`getfz` stay raw.** `MapFile.update` copies `cg.z[i]` into the record and
  its zoom pass reads `getfz` off a grid; `MapSource` (the interface `Grid` and the record's grids
  implement) is what `Ridges.brokenp` reads for the minimap's cliff lines. None of those may
  flatten, or the map file would record a flat world.
- **`Tileset.Flavor.Terrain` implements `MapSource` over a `MapSource`** (`grid.getfz(tc.add(toff))`):
  it is the terrain served flavor factories see, and `Ridges.brokenp(trn, tc)` from such code reads
  it. Flattened, no cliff-edge flavor is seeded on a flat world — right, there is no cliff top.
- **Every public member of `haven` is an ABI** ([published-code.md](../../docs/client/published-code.md)):
  `getfz`, `getcz`, `getzp`, `MapSource.getfz` keep their signatures and their `Loading`
  behaviour; the two real reads are additions. `Ridges.edgeoff`, `getrdesc` and `brokenp` are
  public statics served code may call: `edgeoff` reads `edgeo`, which `clean()` fills from `edgec`
  entries that stay `null` in flat mode (the loop skips them, so zeros, no NPE); `getrdesc` answers
  the wall part or `null`.
- **The drawn surface is the mesh's `ZSurface`, not `getcz`** (118's gotcha). Here that is what
  makes the seam sufficient: `MapMesh.getsurf(id, tiler)` → `Tiler.getsurf` → `MapZSurface.getz`
  → `m.map.getfz` at call time, so the surface flattens the instant the switch flips, before the
  mesh rebuilds. Only `WaterTile` overrides `getsurf`, and its `BottomSurface` subtracts `depth`
  from `super.getz`.
- **What is on screen rebuilds, not only what loads later.** `MapView.MapRaster.Grid.tick` calls
  the raster's `getcut(cc)` for every cut of its `area` on every tick (`Terrain`, `RecallTerrain`,
  `SessionTerrain` and the overlay grids all route it to `MCache.getcut`), and `MCache.Grid.getcut`
  compares `Cut.groundstamp` against `Performance.groundGeneration()` on each call — so the tick
  after the write, every cut being drawn is invalidated and its rebuild scheduled; `Deferred.get`
  keeps answering the old mesh until the build lands and `Grid.tick` swaps the slot on `cur.a !=
  cut`. The switch therefore reaches the ground in view first and needs no walk, no relogin and no
  new grid. 160.4's own suite is the proof of that path.
- **Objects move before the ground does.** `Gob.Placed.autotick` recomposes the placement every
  tick from `getc()`; the mesh follows cut by cut through the `groundGeneration` stamp
  (`MCache.Grid.getcut` → `Deferred.invalidate` → `MapRaster.Grid.tick`'s identity swap). For the
  seconds a cut takes, an object stands on the plane under a hill still drawn. Stated on the page.
- **`Ridges.breaks` marks an edge broken only when the difference exceeds `breakz` of both
  tiles beside it** (`bz` is `+∞` for a non-`RidgeTile`), so every broken edge lies between two
  ridge tiles and both call `Ridges.model`. A tile emits walls for its edges `0` (north, corners
  `tccs[0]`→`tccs[1]`) and `3` (west, `tccs[3]`→`tccs[0]`) only: `eo(tc, 1)` is `eo(tc+(1,0), 3)`
  and `eo(tc, 2)` is `eo(tc+(0,1), 0)`, so each edge is emitted once, and an edge on the cut's east
  or south border is the next cut's tile `(0, y)`/`(x, 0)` edge, emitted there.
- **Wall vertices are copies, not the ground's.** `modelstraight` and its siblings build the ground
  from `ms.fortile` vertices and the walls from `edgec`, which `ensureedge` fills with
  `ms.new Vertex(ret[0])` copies at the same positions: `MapSurface` normals are averaged over the
  faces a vertex is in, and a ground vertex shared with a wall would tilt the ground's shading
  along the cliff line. The flat wall's bottoms are `ms.new Vertex(ms.fortile(corner))` copies for
  the same reason; positions coincide, so there is no crack.
- **`connect(tc, l, r)` is the wall builder already there**: it wants two columns bottom-to-top,
  computes `rcx` (0 for `l`, 1 for `r`), `rcy` (0..1 up the column), `rn`, `rh` (the column
  heights) and the `ledge`/`uedge` rows `TexCons` and `mapridges` read, and adds the faces to the
  surface through `mkfaces`. Its winding faces one way; the back face is the same two columns
  handed the other way round with fresh copies. `new RPart(RPart...)` merges parts, as
  `modeldiag2` and `modelcomplex` do; `MPart(MPart...)` needs at least one part, so a tile with
  no owned broken edge stores `null`.
- **`RidgeTile.model` / `lay`**: `model` calls `super.model` when `Ridges.model` answers `false` —
  which the flat branch does, after storing the wall — so the tile is plain ground; `lay(m, rnd,
  lc, gc)` calls `layridge(lc, rcons)` unconditionally, so the wall is drawn; the cover overload
  reaches `layridge` only when `laygnd` answered, so a transition skirt is never laid over a wall.
- **`Ridges.model` is per cut and `breaks` per cut too**: a wall on a cut border is built by the
  cut that owns the edge, from real heights that may sit in the neighbouring grid — the same
  `Loading` the ridge model already risks, caught by the cut's `Deferred` and retried.
- **The 150-line ceiling**: `ground-detail.md` is at 110 and `world-3d.md` at 144, which is why
  the height chain and the ridge model get a page of their own.
- **`tools/docverbs.py`**: `performance` is already a mapped receiver (160.1); the new verb needs
  nothing there. The `performance()` section counts its verbs as *twelve* in places — each
  becomes *thirteen* or stops counting.

## Discarded alternatives

- **Flattening at each consumer** (the mesh vertices, the placers, the camera, the water, the
  decals, each on its own) — a missed one is an object floating over a flat hill or a click landing
  on a slope that is not drawn; the accessor is the one place they all already read.
- **Flattening the grid's own read** (`Grid.getz`) — the map file would record a flat world, and
  the minimap would lose its cliff lines.
- **Keeping the ridge model and clamping its heights** (the ground parts split at the cliff, the
  walls from zero to a fixed height) — the split ground would ramp from the wall's top down to the
  tile's far corners; a plane with a wall standing on it is what the switch promises.
- **A wall whose height follows the real drop** — a cliff of sixty units would hide what the
  switch exists to show; one tile marks the line and hides nothing.
- **One-sided walls facing the low side, as the real cliff does** — flat, there is no low side;
  the wall is a line seen from wherever the camera is.
- **A separate panel or a Camera-panel switch** — the machinery (statics, panel, handle, the mesh
  stamp) is the Performance panel's; a second home for one switch is a second store to keep true.
- **Answering the drawn height from `world:height`** — the API's contract is the streamed world;
  an addon measuring a slope must not read a flat one because the player flipped a picture.
- **Flattening by distance, or only the ground the camera is not on** — a second mesh path kept
  true to the first, and a picture nobody asked for.
