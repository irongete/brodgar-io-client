# Ground detail: the flavor pass, the mesh and the cut lifecycle

> How a cut's decoration is seeded — the tufts, pebbles and flowers a tileset scatters over its tiles
> — what its ground mesh is made of — the blend layers of a terrain tile and the transition skirts
> between tile types — and how a cut's two deferred halves are built, replaced and swapped into the
> scene. Which cuts are asked for at all is [terrain-raster.md](terrain-raster.md).

## The flavor pass: where it lives

| What | Where |
|---|---|
| A tileset's flavor list | `Tileset.flavors`, `Collection<Indir<Flavor>>`. Filled by the tileset's own `set` part (`Tileset(Resource, Message)`, part `1`): `flavprob` and, per entry, a resource and a weight, each becoming a cached `Tileset.SpriteFlavor` — or replaced whole by the resource's `flavobj` layers (`Tileset.Flavor.Res`, applied in `Tileset.init`) |
| The probability | Computed in that constructor: `w / (flavprob × tw)`, with the first weight doubled by the `XXX: Bug-for-bug` block. It is `SpriteFlavor.p`, a per-tile chance, and the only number a density can scale |
| A published flavor | `Tileset.Flavor.Res.get()`: a `flavobj` resource that carries a `Tileset.Flavor.Factory` (`@Resource.PublishedCode(name = "flavor")`) makes its own `Flavor`; one that does not becomes a `SpriteFlavor` with `args[0]` as `p` |
| The pass itself | `Tileset.SpriteFlavor.flavor(Buffer, Terrain, Random)`: resolves `res` first (`this.res.get()` — a `Loading` leaves before any state is touched), seeds a `DRandom` from the cut's seed, the resource name and the tile id, then for every tile of the terrain **reseeds `ornd` per tile** and draws `ornd.nextDouble() < p`; a hit places a `Tileset.Flavor.Obj` at a random offset and angle, wearing a `ResDrawable` of the resource, into `buf` under `Tileset.flavobjmat` |
| The inputs | `Tileset.Flavor.Buffer` (the glob, the cut's `Area` in tiles, the seed, and the per-material `mats` map the pieces collect into) and `Tileset.Flavor.Terrain` (one tile id over the cut: `tiles()` lists its coords, `mask()` its bitmap, both lazy) |
| Collecting a cut | `MCache.Grid.makeflavor(Coord)`: one `Buffer` per cut, the set of tile ids in it, and for each id a `Terrain` and a `flavor(...)` call per entry of that tileset's `flavors`, `rnd` reseeded from `buf.seed`, the id and the entry index before each; `buf.finish()` runs what a flavor deferred; the result is a `Flavobjs` |
| What the scene gets | `MCache.Grid.Flavobjs`, a `RenderTree.Node`: one child node per material (`mats`), each adding every piece's `Gob.placed`, and `all`, the flat array `tick`/`gtick` walk |
| Ticking the pieces | `MCache.Grid.tick(double)` / `gtick(Render)` — from `MCache.ctick`/`gtick`, from `Glob.ctick`/`gtick` — call `Flavobjs.tick`/`gtick` on every cut's **current** `fo.cur()` of every loaded grid, and those call `Gob.ctick`/`gtick` on every piece |

**Fork.** The density is applied inside `SpriteFlavor.flavor`, on a local copy of `p`, after the
resource has resolved and before the loop: below the maximum, `p` is scaled by the percentage — unless
the resource is *ambient*, decided once per `SpriteFlavor` from its own layers and cached on it — and
at the maximum the field is used untouched, so the draw sequence is upstream's bit for bit. A published
`Flavor` of its own is not reached: it never draws against `p`.

## The blend layers: where they live

| What | Where |
|---|---|
| The tiler | `TerrainTile`, made by `TerrainTile.Factory` (`@ResName("trn")`) once per tileset resource: `base` (the tileset's own material), `var[]` (`TerrainTile.Var`: a material, a noise band `thrl`..`thrh` and a noise offset `nz`), `transset` and `draw`. `TerrainTile.RidgeTile` (its factory `trn-r`) extends it and shares all of this |
| The weights, once per mesh | `TerrainTile.Blend`, built through `MapMesh.DataID<Blend> blend` and `m.data(blend)`, so `faces` and `_faces` of one cut read the same instance. Its constructor: a noise table `lwc` per layer over the cut plus a margin of `sr` (`12`) vertices, `setbase(buf1)` choosing one layer per vertex from the variants' noise bands (the base where no band matches), then `sr` blur passes weighted by `lwc` that end in `buf1 = buf2`. `bv` is the result, post-processed (`v × 1.2 − 0.1`, clamped, `0.25 + 0.75 v` between) |
| Which layers a tile emits | `Blend.en[layer][tile]`, decided per tile from the top layer down: a layer whose four corner weights are all below `0.001` is off, one whose four are all above `0.99` is on and marks `fall`, so everything under it is off |
| The vertices | `Blend.lvfac[layer]`, a `VertFactory` per layer: the vertex colour's alpha is that layer's weight interpolated over the tile (`bv(lc, tcx, tcy)`), tangent and bitangent for the bump map, the texture coordinate on a `25/4` grid |
| Emitting the ground | `TerrainTile.faces(MapMesh, MPart)`: for every enabled layer of the tile, one `SModel` keyed on the layer's material and the tile's `draw` (`MapMesh.MLOrder(0, z)` for `var`, `VertexColor`), so a tile with `n` layers enabled is `n` models and `n` slots |
| Emitting a transition | `TerrainTile._faces(MapMesh, int, Tile, MPart)`: the same loop over enabled layers, each under the transition texture's `AlphaTex` and `MLOrder(z, layer)`, through `MapMesh.Model.get` — one model per (material, order, alpha) key per cut. Reached from `tcons`, which `trans` hands to `Tiler.MCons` |

**Fork.** The three steps that compute the weights — the noise table, `setbase` and the blur — run
only while ground blending is on. Off, `buf1[0]` is filled with `1` and the variants stay at `0`; the
post-processing and the `en` loop are untouched and then enable the base alone, opaque, on every
tile, so `faces` and `_faces` emit one model where they emitted up to `var.length + 1`. The blur would
leave 1/0 arrays as they are; skipping it and the noise is the build-time half of the saving.

## The transition pass: where it lives

| What | Where |
|---|---|
| The two passes of a build | `MapMesh.build(MCache, Random, Coord, Coord)`: pass one calls `Tiler.model` per tile (the surface), pass two calls `Tiler.lay` then `dotrans` per tile, then `calcnrm`/`postcalcnrm` over every `ConsHooks` in `data` and `consflat`. Before each tile of both passes `ns = rnd.nextLong()` is drawn and `rnd.setSeed(ns)` restores it after, so one tile's draws never move another's |
| The pass itself | `MapMesh.dotrans(MapMesh, Random, Coord, Coord)`: reads the eight neighbours' tile ids through `MCache.gettile` (one tile across the cut edge — the reason a cut at a cache's own edge throws `MCache.LoadingMap`), and from the highest id down builds a side mask `bm` and a corner mask `cm`; for every id with a mask it calls that tiler's `trans(m, rnd, ground, lc, gc, 255 − id, bm, cm)`, which lays the skirt of the higher tile over the lower |
| What a skirt is | `TerrainTile.trans`: returns unless the tile under it has a lower id, picks a side tile from `transset.btrans[bmask − 1]` and a corner tile from `transset.ctrans[cmask − 1]` with the tile's `rnd`, and lays each over the ground tile through `gt.lay(m, lc, gc, tcons(z, tile), false)` — an `MCons` whose `faces` is `_faces` above. `Tiler.CTrans` (`tcons(z, bmask, cmask)`) is the same pick for a caller that lays the ground itself |

**Fork.** `dotrans` runs under the transitions switch; `rnd.setSeed(ns)` follows on the next line,
so no other tile's randomness moves. A remembered cut and a live cut go through this one path: there
is one mesh builder, and a switch in it is one switch.

## The cut lifecycle: where it lives

| What | Where |
|---|---|
| The cut | `MCache.Grid.Cut`: `mesh` (`Deferred<MapMesh>`) and `fo` (`Deferred<Flavobjs>`), plus the overlay meshes (`ols`, `olols`, `olstamp`). One per `MCache.cutsz` square of a grid, made with the grid |
| The deferred half | `MCache.Grid.Deferred<T>`: `val` (the current value), `def` (the future in flight), `inited` (volatile). `get()` returns `val` on the unlocked fast path; otherwise, under its own monitor, schedules `rebuild()` when `!inited`, and when the future is done swaps the new value in through `update(T)` and **disposes the previous one** |
| `cur()` vs `get()` | `cur()` is `val` and nothing else: no build, no wait, `null` until the first build lands. `get()` is what schedules; it throws `MCache.LoadingMap` when there is neither a value nor a future (the grid was disposed) |
| `invalidate()` vs `rebuild()` | `rebuild()` schedules a `Defer.later` now, cancelling the previous future. `invalidate()` cancels the future and clears `inited`, so the **next `get()`** schedules — and a cut nobody asks for is never built. `Cut.invalidate()` (from `Grid.invalidate` on `Grid.fill(Message)`, a grid's arrival, and from `Grid.ivneigh` for the edge cuts of its neighbours) invalidates both halves; the `addon: (terrain loading)` comment there records the flood that eager `rebuild()` caused at login |
| The mesh half's extra | The `mesh` `Deferred`'s `update` sets `Grid.olseq = -1`, so `Grid.getolcut` re-lays every overlay over the new vertices on its next call |
| The accessors | `MCache.Grid.getcut(Coord)` → `mesh.get()`, reached through `MCache.getcut` with no lock; `MCache.Grid.getfo(Coord)` → `fo.get()`, reached through `MCache.getfo`, which takes `synchronized(grids)` |
| The swap into the scene | `MapView.MapRaster.Grid.tick`: per cut of `area`, `getcut(cc)` (the raster's own, `map.getcut` or `map.getfo`) and, when the answer is a **different object** from `cuts.get(cc).a`, a new slot is added and the old one removed. Identity is the whole test: a `Deferred` that keeps answering its old value swaps nothing |
| What stays built | A cut's value outlives the slot that drew it: `MapRaster.Grid.removed` clears the raster's `cuts`, not the `Deferred`. `MCache.cutbuilt` (fork) reads `mesh.cur() != null`, true while a rebuild is pending too |

**Fork.** A cut carries a stamp per half, set at construction from the holder's generation: one for
the flavor objects, compared in `getfo`, and one for the ground (blend and transitions), compared in
`getcut`. The accessor compares it before `get()`: on a mismatch it rewrites the stamp and calls
`invalidate()` on that half, so the `get()` that follows schedules one build, keeps answering the old
value until the build lands, and `MapRaster.Grid.tick` swaps the slot on the identity change. No lock
is added: the stamp is `volatile`, `invalidate()` synchronises on the `Deferred`, `getfo` already runs
inside `MCache.getfo`'s `synchronized(grids)`, and `getcut` runs under nothing — two threads racing
its compare at worst invalidate twice, and `rebuild()` cancels the previous future. A cut never built
has `val == null` and is built at the current setting on its first `get()`, whatever the stamp says.
The two generations are separate so a flavor slider's drag never re-meshes the ground it does not touch.

## Gotchas

- **The per-tile reseed is what makes a subset stable.** `ornd.setSeed(...)` runs per tile from the
  cut's seed, and the density draw is the *first* `nextDouble()` after it: a lower chance keeps every
  tile whose first draw was already under it, and the offset and angle draws that follow are the same
  draws. Scale anything after the first draw, or draw twice, and every piece moves.
- **A `Loading` from `Indir.get` at the top of `flavor` retries the whole cut.** `SpriteFlavor.flavor`
  is called from `makeflavor` inside the `Defer` task, and the `Loading` propagates out of `build()`:
  `Defer.Future.run` keeps it as `lastload` and parks the future (`resched`), and the next `done()`
  from `Deferred.get` re-queues the same future — the whole `build()`, every tileset of the cut, runs
  again. Nothing partial is kept, so no state may be touched before that first `get()`.
- **Ticking every flavor object every frame is the CPU half of their cost.** `MCache.Grid.tick` walks
  every built cut of every loaded grid — drawn or not, `cur()` not `get()` — and `Flavobjs.tick` calls
  `Gob.ctick` on each piece. Fewer pieces seeded is fewer gobs ticked; a piece merely not drawn would
  still be ticked.
- **Remembered ground follows a ground switch unbudgeted.** `MapView.RecallTerrain` and
  `MapView.SessionTerrain` read their cuts through the same `MCache.getcut`, so a switch re-meshes
  every drawn recall cut too — outside `MapView.recallmaxbuild`, because that budget admits an
  already-built cut for free through `MCache.cutbuilt`, which reads `mesh.cur() != null` and is still
  true while the rebuild is pending. Accepted: a switch is rare, only drawn cuts rebuild, and the old
  mesh stays on screen meanwhile.
- **`getfo` is under a monitor the render thread takes.** `MCache.getfo` wraps the grid lookup and the
  `Deferred.get` in `synchronized(grids)`; anything added on that path is paid per cut per tick while
  `grids` is contended. A compare of two ints is fine; a walk is not.

## See also

- [which ground is drawn](terrain-raster.md) — the rasters that ask for cuts, and what bounds them
- [state roots](state.md) — `MCache` and the grids these cuts belong to
- [resources](resources.md) — reading a resource's layers, which is how an ambient piece is told apart
- [the boot and the loops](boot-and-loop.md) — the `Defer` pool a cut build runs on
