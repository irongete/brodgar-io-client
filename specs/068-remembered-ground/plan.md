# 068 — Remembered ground — plan

## Approach

Four pieces, and three of them are the client's own machinery pointed at a second source.

**1. The recalled source — a second `MCache`, filled from disk.** `io.brodgar.rts.Recall` holds an
`MCache` built on the live `Session` (so `sess.glob` and the resource pool work) whose grids are
filled from `MapFile` instead of from `mapdata2`. Nothing ticks it and nothing calls `sendreqs()` on
it, so `request()`'s bookkeeping never reaches the wire — that is what makes "no traffic" structural
rather than a promise.

The bridge: `MiniMap.sessloc.tc` is the segment tile coord of session tile `(0,0)` and is
grid-aligned (`SessionLocator` derives it from a live grid's `GridInfo`), so segment grid coord =
session `gc` + `sessloc.tc.div(cmaps)`. Take `Segment.gridid(sc)` under a `tryLock` (the whole
coord→id map is in memory), then `Segment.grid(id)`'s `Indir` and `get()` it off the UI thread.

Two seams, both `// addon:`-tagged:

- `MCache.settileset(int, Indir<Resource>)`, package-private — `cktileid(id); sets[id] = res`, the
  two lines `filltiles3` runs. `sets` and `cktileid` are private to `MCache`, which is why this one
  cannot live in `AddonWidgets`.
- `AddonWidgets.putgrid(MCache, Coord gc, long id, int[] tiles, float[] z)` — mints a `Grid`, fills
  `tiles`/`z`/`id`/`seq`, gives it empty `ols`/`ol`, installs it under `synchronized(mc.grids)`,
  beside the existing `loadedGrid`.

Tile ids are the recalled cache's own, never the live map's: a recorded `TileInfo.res` is a
`Resource.Saved`, the same `Indir<Resource>` shape `sets[]` holds, so the remap is one
`HashMap<name+ver, Integer>` and `settileset`.

**2. The raster — `MapView.RecallTerrain extends MapRaster`**, beside `FleetTerrain` and built the
same way: `super(recall.map)`, one `Grid<MapMesh>` over `map.getcut(cc)`. Two differences. Its
`area` is centred on `RTSCam.center()` instead of `getcc()`. Its `skipcut` yields every cut the live
`Terrain` already holds (`terrain.main.cuts`) and everything `cutvisible` rejects — culling
unconditionally, not on the `cullterrain` setting, because the one reason that is a setting (culled
ground stops casting into the shadow map) cannot apply to ground the shadow box never reaches. Added
to `basic` when the RTS camera is installed, removed with it; `MapView.camname()` is the test.

**3. The wash — the client's own overlay path.** `MCache.OverlayInfo` is a plain interface
(`tags`, `mat`, `omat`); `ResOverlay` is only its resource-backed implementation. `MapView.Overlay`
adds `Grid.getolcut(id, cc)` under `id.mat()`, and that cut is `MapMesh.makeol`'s conforming sheet
ordered by `MapMesh.OLOrder` — a translucent layer that follows the relief and does not fight the
ground. So the wash is one `OverlayInfo` of our own, whose `mat()` copies `MapView.gridmat`'s state
set (`BaseColor(255, 255, 255, a)`, `States.maskdepth`, `OLOrder`) with the alpha turned up, plus one
`MCache.RectOverlay` registered in the recalled cache covering its whole area — which is what makes
`MCache.getol` hand back a full mask and `makeol` emit every face. The ground underneath keeps its
real tilesets, so the tile still reads through the wash. That is the whole of "partial".

**4. The command — `:recall`** in `MapView.cmdmap`, beside `:cam`: bare prints the counters (grids
read from disk, cuts live, requests sent), `off`/`on` toggles the raster, `wash <a>` sets the alpha
so the look is dialled in-game instead of through a rebuild.

## Files to create/modify

- `src/io/brodgar/rts/Recall.java` — new: the recalled cache, the bridge, the disk read, the remap,
  the budget, the counters.
- `src/haven/MCache.java` — `settileset`.
- `src/haven/AddonWidgets.java` — `putgrid`.
- `src/haven/MapView.java` — `RecallTerrain`, the wash `OverlayInfo`, install/remove, `:recall`.
- `docs/client/mapfile.md` — a recorded grid read back into a live-shaped one.
- `docs/client/world-3d.md` — the drawn-ground row gains the second raster; the conforming-sheet
  recipe (`OverlayInfo` needs no resource) goes in the ground-overlays section.
- `docs/addons/api/world.md`, `docs/addons/api/vr/README.md`, `docs/addons/api/vr/widgets.md` — the
  impact set.

## Risks and gotchas

- **`ShadowMap.ShadowList.add` mirrors every lit `Rendered` slot** into a second draw list, and skips
  only a slot carrying `ShadowMap.maskshadow`. Recalled cuts take that op, or each is drawn twice for
  a shadow box (750 units around `smapcc`, which follows the player) they can never reach.
- **Nothing is frustum-culled by the engine.** `cutvisible`/`boxvisible` are the fork's own test and
  they are conservative per-plane; use them, do not invent a radius.
- **The build burst is the only real cost.** `MapMesh.build` is two passes over 625 tiles plus
  `dotrans`'s eight neighbour reads each, on `Defer` threads, then a slot compile and a VBO upload.
  Cap new cuts per tick, nearest to the camera centre first; `Loading` already carries the rest.
- **`dotrans` reads across the cut edge**, so fill a one-grid margin and let the edge cut throw
  `Loading` until the neighbour lands.
- **`MapFile`'s processor thread holds the WRITE lock across disk I/O**: `tryLock` the read lock,
  never `lock` — `MiniMap.resolve`'s rule.
- **`Loading` is a `RuntimeException` everywhere on this path**, and a tileset loaded from the res
  cache can carry illegal references — `MapSource.drawmap` catches plain `RuntimeException` for
  exactly that. Catch both, per grid.
- **`sessloc` goes stale, never null**, and `MCache.trimall` (`invalblob` type 2 — a cave, a house)
  re-bases the session mid-play. Re-derive the offset each tick and drop every recalled grid when
  `sessloc.seg` or `tc` moves.
- **`MapFile.merge` re-bases a segment's coords**: a stored segment coord does not go stale, it
  points somewhere else. And `segments` is a `BackCache(5)`, so the same segment returns as a
  different object after eviction — never intern on Java identity.
- **`MCache.Grid.getol` walks `ols.length` unguarded** — a recalled grid needs empty arrays, not
  null.

## Discarded alternatives

- **Widening the live `Terrain`'s area to the camera** — every cut outside the streamed set calls
  `MCache.getgrid`, which requests it from the server; that is the one thing this must not do, and
  the request gives up after five tries leaving the raster in permanent `Loading`.
- **Injecting recalled grids into the live `MCache`** — that cache is the server's model of what the
  character can see, and every terrain verb, the click path and the durable-place resolver read it
  as though the server had sent what is in it.
- **Teaching `MapMesh` to build from a `MapSource`** — it reads `getcz`, `getcut` and `getol`, none
  of which are on that interface: four classes changed to avoid constructing one object.
- **An opaque wash** — cheaper, and it hides the tile the wash exists to keep recognisable.
- **Desaturating through lighting or a shader** — a tileset brings its own material, so there is no
  state above them a subtree can set to wash them out; a sheet washes from above with machinery that
  already ships.
- **`ZoomGrid` levels as far LOD** — `ZoomGrid.from` takes the majority tile and the *minimum* z of
  each 2×2, so far ground reads stepped and sunken. A wash over wrong relief is worse than no ground.
- **A cut budget derived from the frustum alone** — the camera can frame more ground than this client
  was ever built to draw. The cap is a stated number and the cuts beyond it are simply not drawn.
