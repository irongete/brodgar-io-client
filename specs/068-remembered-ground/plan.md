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

**3. The wash — a shader state on the raster's slot.** The wash takes the *colour* out, and no layer
laid over the ground can do that: alpha blending interpolates every fragment the same fraction
toward one colour, so a translucent white sheet leaves remembered ground pale and still green,
brown and blue. Desaturating is an operation on a fragment's own three channels against each other,
and the fragment shader is the only place it can be written.

A shader program here is compiled from the **composed `Pipe`** of the slot being drawn rather than
per `Material`, so every `State` in that composition contributes its `ShaderMacro` — which is what
lets one state installed at this subtree's root reach ground whose colour comes from a tileset's own
material. `io.brodgar.rts.Greyscale` is that state: a `State.Slot`, a `Uniform` holding how far
toward grey, and `FragColor.fragcol(prog.fctx).mod(fn, 1000)` mixing the fragment's Rec. 709 luma
back over its rgb. `haven.ColorMask` and `render.BaseColor` are the same mechanism.

What it costs is one program, compiled on the first frame that needs it — no second mesh, no second
draw, no second pass, and the amount living in the uniform rather than the macro means changing it
recompiles nothing either: `Slot.ostate` pushes a new instance and the next frame reads it.

**4. The command — `:recall`** in `MapView.cmdmap`, beside `:cam`: bare prints the counters (grids
read from disk, cuts live, requests sent), `off`/`on` toggles the raster, `wash <a>` sets the alpha
so the look is dialled in-game instead of through a rebuild.

## Files to create/modify

- `src/io/brodgar/rts/Recall.java` — new: the recalled cache, the bridge, the disk read, the remap,
  the budget, the counters.
- `src/haven/MCache.java` — `settileset`.
- `src/haven/AddonWidgets.java` — `putgrid`.
- `src/io/brodgar/rts/Greyscale.java` — new: the desaturating render state.
- `src/haven/MapView.java` — `RecallTerrain`, the wash on its slot, install/remove, `:recall`.
- `docs/client/mapfile.md` — a recorded grid read back into a live-shaped one.
- `docs/client/world-3d.md` — the drawn-ground row gains the second raster; the ground-overlays
  section gains both ways of recolouring ground, the sheet and the shader state, and which each can
  do.
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
- **A translucent sheet over the ground** — the client's own ground-overlay path (an `OverlayInfo`
  of our own plus an `MCache.RectOverlay` covering everything, drawn through `MCache.getolcut` as
  `MapMesh.makeol`'s conforming sheet). It follows the relief exactly and it ships already, but it
  can only tint: blending moves every fragment the same fraction toward one colour, so the ground
  reads as hazy rather than as remembered. It also costs what the shader does not — two full
  tile-laying passes per cut (`makeol` and the `makeolol` nobody draws) and a second mesh in the
  scene.
- **`ZoomGrid` levels as far LOD** — `ZoomGrid.from` takes the majority tile and the *minimum* z of
  each 2×2, so far ground reads stepped and sunken. A wash over wrong relief is worse than no ground.
- **A cut budget derived from the frustum alone** — the camera can frame more ground than this client
  was ever built to draw. The cap is a stated number and the cuts beyond it are simply not drawn.
