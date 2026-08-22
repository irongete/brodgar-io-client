# The map database: `MapFile`

> The map the player has **explored**, on disk and persistent — as opposed to `MCache`, the terrain
> streamed around them ([state.md](state.md)). The client that reads it — the coordinate bridge and the
> drawings — is [minimap.md](minimap.md).

## The file

- `haven/MapFile.java` — `MapFile(ResCache store, String filename)`: the store is a **constructor
  argument**, so a throwaway in-memory `ResCache` gives you the whole subsystem headlessly.
  `load(store, filename)` (``) reads only the *index*: `knownsegs` (``) and every `Marker` (``).
- **Who makes one.** `GameUI.addchild` builds it on the `"mapview"` placement:
  `MapFile.load(mapstore, mapfilename())`, where `mapfilename()` is `genus` plus the `mapfile/<chrid>` pref
  **only when that pref exists** — by default it does not, and the `chrmap` console command is what sets it
  — and `mapstore` is `ResCache.global` unless `MapFile.mapbase` names a directory. So two characters on one
  server name the same directory unless one of them has been given a name of its own.
- **How many there are: one per `(store, filename)`.** `load` memoizes on that pair (`// addon:`) — the
  pair, because `mapbase` is the other half of the identity — so every `GameUI` naming it, and every
  re-placement, gets the **same instance**, and the one lock below is therefore one lock over that
  directory. The instance is handed to both readers, `CornerMap` (`GameUI.mmap`) and `MapWnd`
  (`GameUI.mapfile`). **Nothing disposes a `MapFile`**: what a session destroys is its `MapWnd`, so the
  database stands as long as the client does, and object identity answers *which database is this* rather
  than *whose*.
- **One `ReentrantReadWriteLock`** (``) guards everything; `checklock()` (``) makes several methods
  *assert* the caller holds it. The **processor thread** (``–``) takes the WRITE lock for segment
  saves and the index save, **across disk I/O** — so a UI-thread reader must `tryLock`, never `lock`
  (`MiniMap.resolve`, below). `defersave()` (``) only queues; nothing writes synchronously.
- Both caches are `BackCache` (`haven/BackCache.java`): a `load` function, a `store` function, and a
  size-bounded access-ordered `LinkedHashMap`. **`get` mutates the map** (insert + LRU touch) while
  callers hold only the *read* lock — the client's own discipline; mirror it rather than fixing it.
  - `gridinfo` (``), `BackCache(100)`: grid id → `GridInfo{id, seg, sc}` (``), one tiny file per
    grid. **The only bridge from a server grid id into the database**, and it needs no grid data.
  - `segments` (``), `BackCache(5)`: segment id → `Segment`. Loading one is a **synchronous** file
    read (small: just the coord → grid-id map). Only five live at once, so the same segment comes back as
    a *different object* after an eviction — never intern on its Java identity.

## Segments, grids, markers

- `Segment` (``) is an inner class: `id` plus a **private** `map` (coord ⇄ grid id, `HashBMap`) and
  three weak `CacheMap`s. `map` being private is why there is no "list this segment's grids": the client
  itself never enumerates — `MiniMap.redisplay` walks the coords of the rectangle it draws.
  - `grid(long id)` (``) / `grid(Coord sc)` (``) / `grid(int lvl, Coord gc)` (``, zoom
    levels; `lvl 0` delegates) all hand back an `Indir<Grid>` and all `checklock()`. `get()` on it throws
    `Loading` until `Defer` has the file (`loadgrid`, ``) — take the `Indir` under the lock, call
    `get()` outside it, catch `RuntimeException` to nil.
  - `gridid(Coord sc)` (`// addon:`) reads `map` directly: the WHOLE coord→id map arrives with the segment
    (the `seg-%x` file is a flat pair list, ``), so an id is answerable **from memory** where
    `grid(sc)` would wait on a disk read for tiles nobody wants — the durability predicate's one door.
  - `include(Grid, sc)` (``) is how a grid enters a segment; it also invalidates the zoom cache.
- `DataGrid` (``): `tilesets[]` (`TileInfo` = `Resource.Saved` + `prio`), `tiles[]` (indices **into
  this grid's own tilesets** — unrelated to `MCache`'s tile ids), `zmap[]`, `ols`, `mtime`. `gettile(c)`
  / `getfz(c)` take a within-grid coord `0..cmaps`; `render(off)` (``) / `olrender(off, tag)` (``)
  draw it ([minimap.md](minimap.md)). `Grid` (``) adds the server `id`; `load` (``) / `save` (``).
- `Overlay` (``) is a per-grid boolean mask keyed by an overlay **resource**; `MCache.ResOverlay.tags()`
  says which tags it carries, several resources may share one (hence `olrender`'s composite), and `olid.get()`
  throws `Loading` — who *displays* them is in [world-3d.md](world-3d.md) (`realm` here, `prov` there).
  Markers (``–``): `Marker{seg, tc, nm}`, `PMarker` (colour, onmap), `SMarker` (oid, res, data).
  `add`/`remove`/`update` take the write lock (`update` the read lock — it mutates a field in place, not
  the collection), `defersave()`, bump `markerseq` (``) and call `AddonManager.onMarkersChanged`
  (`// addon:`) — a marker-changed notify, marshalled onto the tick so it never
  fires from inside the DB's own lock. **A `Marker` object is loaded once and mutated in place**, so its
  Java identity IS stable, unlike a segment's or a grid's.
- `merge(dst, src, soff)` (``) is the trap the whole anchor rule exists for: it re-bases the loser's
  grid coords **and rewrites every marker's `seg`/`tc` in place**, bumping `markerseq` and firing the same
  notify once if any marker moved. A stored segment coord does not go stale, it points somewhere else. A
  grid id is the server's and never moves.
- `update(MCache, Coord cgc)` (``) queues the 3×3 grids around a coord; `GameUI.mapfilesave`
  (`haven/GameUI.java`) calls it whenever the player's grid or its `seq` changes — which is why the
  recorded grid under the player is current.

## Zoom grids

- `ZoomGrid` (``) is a `DataGrid` covering `cmaps << lvl` tiles in the **same** `cmaps` array — a
  level is a scale, not a size. `fetch` (``) loads one from disk, else `from` (``) builds it out of
  the four grids one level down (recursively) and `save`s it. `from` resolves **no** resource: it merges
  tileset *names* and versions, picks the majority tile of each 2×2 and the min z, then `zoomols` (``)
  downsamples the overlay masks. `Segment.grid(lvl, gc)` (``) **requires `gc` aligned to `1<<lvl`**
  and throws `IllegalArgumentException` otherwise; its `ByZCoord` (``) answers `null` (not `Loading`)
  once it has run and found nothing there.

## Reading a recorded grid back as a live one

A recorded `Grid` and an `MCache.Grid` hold the same picture in the same layout — a `cmaps`-sized `int[]` of
tile indices and a `float[]` of heights — so the record is rasterizable by the terrain machinery in
[world-3d.md](world-3d.md), once these four differences are paid.

| The record | The live cache | What has to happen |
|---|---|---|
| `DataGrid.tiles`, indices into **this grid's own** `tilesets` | `MCache.Grid.tiles`, this **session's** tile ids, indices into `MCache.sets` | remap per grid, through a name→id map the reader keeps for itself |
| `TileInfo.res`, a `Resource.Saved` | `MCache.sets[id]`, an `Indir<Resource>` | the same shape already; `MCache.settileset` (`// addon:`) is the two lines `filltiles3` runs with no wire message around them |
| `DataGrid.zmap`, `Grid.id` | `MCache.Grid.z`, `.id` | copy. The id seeds each `Cut`'s `Random`, so a place meshes identically every time it is built |
| `DataGrid.ols`, a `Collection<Overlay>` keyed by resource | `MCache.Grid.ols`/`.ol`, parallel arrays | not the same shape at all — and `MCache.Grid.getol` walks `ols.length` unguarded, so a grid filled from the record needs **empty arrays, never null** |

- **A tile id is per-session and per-cache.** The server assigns it (`MCache.Grid.filltiles3`) and it is an
  index into `sets`, which is why the record stores names and versions instead. It is also the transition
  priority: `MapMesh.dotrans` loops from the highest neighbouring id down and hands `255 - i` to `Tiler.trans`
  as the layer order. A reader minting ids of its own therefore gets correct ground with a transition order of
  its own, and there is no way to recover the server's — see the `prio` gotcha below.
- **The read is two steps and neither of them waits.** `Segment.gridid(sc)` answers from memory; `Segment.grid(id)`
  hands back an `Indir` — both `checklock()`, both under the `tryLock` of `MiniMap.resolve`'s rule. Call `get()`
  **outside** the lock and treat its `Loading` as *ask again next pass*: the caller is itself on a `Defer` thread
  and blocking one on another's task is what [boot-and-loop.md](boot-and-loop.md) warns about.
- **The offset is `sessloc`, and it can check itself.** Segment grid coord = session grid coord +
  `sessloc.tc / cmaps`, and `sessloc.tc` is grid-aligned because `SessionLocator` derives it from a live grid's
  `GridInfo`. `sessloc` goes stale rather than null ([minimap.md](minimap.md)), so a reader proves the offset
  before trusting it: a live `MCache.Grid`'s `id` must equal `Segment.gridid` at its translated coord. A grid id
  is the server's and is the same number in every frame, which is what makes that comparison an answer.
- **A grid arriving does not need its neighbours invalidated, and invalidating them is expensive.**
  `MCache.Grid.Cut.invalidate` goes through `Deferred.rebuild`, which schedules a build whether or not that cut
  was ever built — so `ivneigh` around each arrival meshes the edge cuts of eight grids nobody asked to draw,
  and `MapMesh.dotrans` reads one tile across the grid border, which is a `getgrid` miss and therefore a
  `request`. `Grid.fill` can afford it because the ground it fills is being drawn anyway. It is also
  unnecessary: that same cross-border read means an edge cut whose neighbour grid is absent throws
  `MCache.LoadingMap`, and `Defer.Future.run` catches `Loading` into `resched` instead of completing — so a cut
  only ever finishes with every grid it read present. Fill a margin beyond what is drawn; do not invalidate.

## Gotchas

- **`Loading` is everywhere on this path** (`Indir.get`, a tileset resource, `olid.get`) and it is a
  `RuntimeException`: catch broadly at the API boundary or it escapes into user code.
- A grid id and a segment id are **64-bit**; expose them as decimal strings, never Lua numbers.
- **`TileInfo.prio` is 0 on every grid recorded off the live map.** `update`'s first loop passes `prios[i]` into
  each `TileInfo`; the loop that *fills* `prios` runs after it, and the array is never read again. So the
  ordering the field exists to keep is thrown away at the one place it is written — which flattens
  `DataGrid.render`'s tile-border pass (it fires on `prio` strictly greater than the centre's) and leaves
  `View.fin`'s topological tile sort ordering by the grid's own array order. `ZoomGrid.from` mints its own from
  the merged name index, so a zoom level's prios are not 0 and are not the server's either.
- `markerseq` does **not** bump for markers loaded from disk at startup, so the initial load never calls
  `onMarkersChanged`. Every call into it is therefore already a **real** change — `MapApi.fireMarkerChanged`
  fires on the first call too (it primes `lastMarkerSeq` and fires in the same call), unlike the old poll's
  prime-then-skip: there is no "first tick after login" to distinguish from a real one anymore.
- `new MapFile(null, "")` does no I/O, but a read NPEs *inside a `Defer` task* and surfaces wrapped rather
  than clean — give a headless probe a real (in-memory) store.
