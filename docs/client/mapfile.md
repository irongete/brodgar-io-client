# The map database: `MapFile`

> The map the player has **explored**, on disk and persistent — as opposed to `MCache`, the terrain
> streamed around them ([state.md](state.md)). The client that reads it — the coordinate bridge and the
> drawings — is [minimap.md](minimap.md).

## The file

- `haven/MapFile.java` — `MapFile(ResCache store, String filename)`: the store is a **constructor
  argument**, so a throwaway in-memory `ResCache` gives you the whole subsystem headlessly.
  `load(store, filename)` reads only the *index*: `knownsegs` and every `Marker`.
- **Who makes one.** `GameUI.addchild` builds it on the `"mapview"` placement:
  `MapFile.load(mapstore, mapfilename())`, where `mapfilename()` is `genus` plus the `mapfile/<chrid>` pref
  **only when that pref exists** — by default it does not, and the `chrmap` console command is what sets it
  — and `mapstore` is `HashDirCache.get(MapFile.mapbase)` when `haven.mapbase` names a cache identity and the
  store is the files one, else `ResCache.global` ([rescache.md](rescache.md)). So two characters on one server name the same database unless one of them has been given a name of its own.
- **How many there are: one per `(store, filename)`.** `load` memoizes on that pair (`// addon:`) — the
  pair, because `mapbase` is the other half of the identity — so every `GameUI` naming it, and every
  re-placement, gets the **same instance**, and the one lock below is therefore one lock over that
  directory. The instance is handed to both readers, `CornerMap` (`GameUI.mmap`) and `MapWnd`
  (`GameUI.mapfile`). **Nothing disposes a `MapFile`**: what a session destroys is its `MapWnd`, so the
  database stands as long as the client does, and object identity answers *which database is this* rather
  than *whose*.
- **One `ReentrantReadWriteLock`** guards everything; `checklock()` makes several methods
  *assert* the caller holds it. The **processor thread** takes the WRITE lock for segment
  saves and the index save, **across disk I/O** — so a UI-thread reader must `tryLock`, never `lock`
  (`MiniMap.resolve`, below). `defersave()` only queues; nothing writes synchronously.
  ⚠️ **A `tryLock` that fails says nothing about the data**: the read lock is held by every `Defer` grid read
  and the write lock by every save, so a caller that takes the failure for *not in the database* gets a
  different answer at every grid the file reads. Keep what was known and ask again later.
- Both caches are `BackCache` (`haven/BackCache.java`): a `load` function, a `store` function, and a
  size-bounded access-ordered `LinkedHashMap`. **`get` mutates the map** (insert + LRU touch) while
  callers hold only the *read* lock — the client's own discipline; mirror it rather than fixing it.
  - `gridinfo`, `BackCache(100)`: grid id → `GridInfo{id, seg, sc}`, one tiny file per
    grid. **The only bridge from a server grid id into the database**, and it needs no grid data.
  - `segments`, `BackCache(5)`: segment id → `Segment`. Loading one is a **synchronous** file
    read (small: just the coord → grid-id map). Only five live at once, so the same segment comes back as
    a *different object* after an eviction — never intern on its Java identity.

## Segments, grids, markers

- **A segment id is minted inside the file that holds it.** `MapFile.update` does
  `new Segment(rnd.nextLong())` for ground that joins nothing already known, off `MapFile`'s own private
  `Random`. So an id names a segment only within one `MapFile`, and two databases (`GameUI.chrmap` gives a
  character its own) are never comparable segment by segment: ask which `MapFile` first.
- `Segment` is an inner class: `id` plus a **private** `map` (coord ⇄ grid id, `HashBMap`) and
  three weak `CacheMap`s. `map` being private is why there is no "list this segment's grids": the client
  itself never enumerates — `MiniMap.redisplay` walks the coords of the rectangle it draws.
  - `grid(long id)` / `grid(Coord sc)` / `grid(int lvl, Coord gc)` (zoom
    levels; `lvl 0` delegates) all hand back an `Indir<Grid>` and all `checklock()`. `get()` on it throws
    `Loading` until `Defer` has the file (`loadgrid`) — take the `Indir` under the lock, call
    `get()` outside it, catch `RuntimeException` to nil.
  - `gridid(Coord sc)` (`// addon:`) reads `map` directly: the WHOLE coord→id map arrives with the segment
    (the `seg-%x` file is a flat pair list, ``), so an id is answerable **from memory** where
    `grid(sc)` would wait on a disk read for tiles nobody wants — the durability predicate's one door.
  - `include(Grid, sc)` is how a grid enters a segment; it also invalidates the zoom cache.
- `DataGrid`: `tilesets[]` (`TileInfo` = `Resource.Saved` + `prio`), `tiles[]` (indices **into
  this grid's own tilesets** — unrelated to `MCache`'s tile ids), `zmap[]`, `ols`, `mtime`. `gettile(c)`
  / `getfz(c)` take a within-grid coord `0..cmaps`; `render(off)` / `olrender(off, tag)`
  draw it ([minimap.md](minimap.md)). `Grid` adds the server `id`; `load` / `save`.
- `Overlay` is a per-grid boolean mask keyed by an overlay **resource**; `MCache.ResOverlay.tags()`
  says which tags it carries, several resources may share one (hence `olrender`'s composite), and `olid.get()`
  throws `Loading` — who *displays* them is in [world-3d.md](world-3d.md) (`realm` here, `prov` there).
  Markers: `Marker{seg, tc, nm}`, `PMarker` (colour, onmap), `SMarker` (oid, res, data).
  `add`/`remove`/`update` take the write lock (`update` the read lock — it mutates a field in place, not
  the collection), `defersave()`, bump `markerseq` and call `AddonManager.onMarkersChanged`
  (`// addon:`) — a marker-changed notify, marshalled onto the tick so it never
  fires from inside the DB's own lock. **A `Marker` object is loaded once and mutated in place**, so its
  Java identity IS stable, unlike a segment's or a grid's.
- `merge(dst, src, soff)` is the trap the whole anchor rule exists for: it re-bases the loser's
  grid coords **and rewrites every marker's `seg`/`tc` in place**, bumping `markerseq` and firing the same
  notify once if any marker moved. A stored segment coord does not go stale, it points somewhere else. A
  grid id is the server's and never moves.
- `update(MCache, Coord cgc)` queues the 3×3 grids around a coord; `GameUI.mapfiletick`
  (`haven/GameUI.java`) calls it whenever the player's grid or its `seq` changes — which is why the
  recorded grid under the player is current.

## Zoom grids

- `ZoomGrid` is a `DataGrid` covering `cmaps << lvl` tiles in the **same** `cmaps` array — a
  level is a scale, not a size. `fetch` loads one from disk, else `from` builds it out of
  the four grids one level down (recursively) and `save`s it. `from` resolves **no** resource: it merges
  tileset *names* and versions, picks the majority tile of each 2×2 and the min z, then `zoomols`
  downsamples the overlay masks. `Segment.grid(lvl, gc)` **requires `gc` aligned to `1<<lvl`**
  and throws `IllegalArgumentException` otherwise; its `ByZCoord` answers `null` (not `Loading`)
  once it has run and found nothing there. Level `0` goes through `grid(gc)`, which `checklock()`s —
  the caller must hold the file's lock — while a level ≥ 1 loads on a `Defer` thread under the lock
  of its own and may be asked from anywhere.
- **A zoom grid is a level of detail.** Every level is one `cmaps` array, so a cell costs the same to
  read and to mesh at any level while covering four times the ground of the one below. Fork:
  `MapView`'s view distance draws the ground it does not draw whole from them — a quadtree over segment
  grid coords, a cell splitting while one of its samples would cover more than a few pixels at its
  nearest depth — drawn as the view calls for, and built and kept beforehand as every view a turn of the
  camera about its centre gives calls for, so a turn of the camera builds nothing — each leaf one
  height-map mesh over `zmap` with the tilesets'
  `Resource.imgc` colours as its texture, one texel per sample, and a skirt hung from every edge, since
  neighbouring levels sample different heights along a shared edge. `zmap` is the **minimum** of each
  2×2, so a coarse cell sits at or under the ground it stands for. The texture is sampled nearest when
  magnified — the map window draws a zoom grid with `TexI`'s own `NEAREST` both ways, and the game's
  ground tiles (`Tileset`'s atlas) with `NEAREST` in a level, `LINEAR` between two and `Mipmapper.avg`
  levels — so a sample is a crisp square; linear magnification smears a few-pixel sample into its
  neighbours. A leaf keeps the zoom grid's `Indir` and is built again when it answers another grid that
  draws differently (the record moved, in the gotchas below), the old mesh drawn until the new one is built.
  ⚠️ Ground never recorded is not absent from a zoom grid: `from` fills a missing quarter with
  `DataGrid.nogrid`, tileset `gfx/tiles/notile` at height `0`, and it wins the majority vote like any
  tile — so a coarse cell carries unexplored samples, and their zero enters the min of a mixed block.
  ⚠️ Only a power-of-two texture has mipmaps: `Texture2D.image(level)` throws past level 0 for any other
  size, `TexL` refuses one outright, and `TexI` fills level 0 alone (`TexI(img)` pads to a power of two,
  `TexI(img, false)` keeps the size). A 100-sample texture to be mipmapped is laid in the corner of a 128
  one, with texcoords spanning `0..100/128`.
  ⚠️ `Light.PhongLight`'s defaults are not the ground's lighting: `defamb` 0.2 and `defdif` 0.8, where the
  terrain materials' `col` asks for an ambient of 128/255 and a full diffuse (`GroundTile`'s `gcol` the same;
  paving 0.8 and 0.64). A mesh standing in for ground lit with the defaults is a third darker than the ground
  beside it under a high sun, and three fifths darker at night.

## Reading a recorded grid back as a live one

A recorded `Grid` and an `MCache.Grid` hold the same picture in the same layout — a `cmaps`-sized `int[]` of
tile indices and a `float[]` of heights — so the record is rasterizable by the terrain machinery in
[terrain-raster.md](terrain-raster.md), once these four differences are paid.

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
- **The read is two steps and neither of them waits.** `Segment.gridid(sc)` answers from memory;
  `Segment.grid(id)` hands back an `Indir` — both `checklock()`, both under the `tryLock` of
  `MiniMap.resolve`'s rule, so a pass that cannot have the lock does nothing and the next one gets it. Call
  `get()` **outside** the lock and treat its `Loading` as *ask again next pass*: the caller is itself on a
  `Defer` thread and blocking one on another's task is what [boot-and-loop.md](boot-and-loop.md) warns about.
- **A read budget bounds NEW asks, and it takes a pending set to say so.** The `Indir` is cached in the
  segment, so asking again is a check on a running future and is how the answer is collected — but a coord
  still `Loading` re-consumes a slot on every pass unless the reader keeps its own set of what it has asked
  for and not yet installed, and a budget without one bounds what is *outstanding*: a square of grids then
  takes as long to ask for as to read. Clear the set whenever the offset moves, or a coord asked for through
  the old one installs somewhere it never was. Keep the budget small, for the gotcha below and not the disk.
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

- **A grid ask still in flight holds a `Defer` worker on the read lock.** `Segment.loadgrid` is
  `Defer.later(locked(..., lock.readLock()))` and `locked` takes the lock with `lock()`, never `tryLock`,
  so an `Indir` handed out and not yet collected parks a pool thread for as long as the processor
  thread's write lock lasts — a whole segment save. The caller's own `get()` throws rather than waits, so
  nothing on its side shows this; a burst of asks starves the pool every mesh build shares
  ([boot-and-loop.md](boot-and-loop.md)).
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
- **A zoom grid that is not there is a cascade to find out, and upstream forgets the answer at the next GC.**
  `ZoomGrid.fetch` misses the store and `from` probes the four cells one level down — recursively, to the
  grids — so one absent cell over unexplored ground is dozens of store misses (21 at level 3); `from`
  answers `null`, and `Segment.zcache` holds the `ByZCoord` weakly, so once the map window lets go of it a
  collection drops it and the next look repeats the probe — thousands of misses per pan on a large map. The
  fork keeps the `null` answers: `Segment.empties` (`// addon:`) holds those `ByZCoord`s strongly, which
  keeps their weak entries alive, so `include`'s own loop still relaunches them when a grid lands under the
  cell — the freshness rule is unchanged, only the lifetime — and the set is cleared whole past 65,536
  cells, which is upstream's behaviour again. While a relaunched load runs, `get()` answers the previous
  value without `Loading` (`got`), for a `null` as for a grid: the caller re-asks, as the minimap does each frame.
- **A zoom grid whose build failed answers every later `get()` with the failure.** `ZoomGrid.from` `save`s
  what it builds, and a store that will not take it (a full disk, a locked file, a SQLite error) throws
  `StreamMessage.IOError` inside the `Defer` task; `ByZCoord.get()` then rethrows it as
  `Defer.DeferredException` — a `RuntimeException`, not `Loading` — on every ask until a grid is recorded
  under the cell. Reads cannot do this (`load` turns `BinError` and `IOException` into `null`). Upstream's
  zoomed-out `MiniMap` catches `Loading` alone on that `get()`, so a caller that must survive it catches
  `RuntimeException` and reads the cell as empty.
- **An `SMarker` whose icon no resource source has kills the UI thread at draw, and only an import can
  record one.** `MiniMap.MarkerIcon.ckload` reads its `Loader` future with `Future.get`, which rethrows a
  failed load as a plain `RuntimeException` (`NoSuchResourceException`), and every caller of `icon()`
  catches `Loading` alone. The game never records such a marker; an export from another client does — that
  client's own icons (`gfx/icons/…`) travel in its `.hmap`. The fork's `Importer.hasres` (`// addon:`)
  leaves those markers out and tells the filter through `ImportFilter.skipmark` (`MapWnd.importmap` counts
  them into one `ui.msg` line). The verdict is `NoSuchResourceException` alone — every source said "not
  found" — asked **once per name per import**: the pool re-walks every source (two HTTP 404s a walk, two
  walks a `Saved.get`) on each ask for a name it has already failed, and an export repeats one icon on
  hundreds of markers. A resource that exists but fails to load now (`LoadFailedException`: a broken file,
  no network — the HTTP sources throw a non-`FileNotFound` `IOException`, which sets `found`) is imported
  like any other marker, so an offline import drops nothing.
- **Upstream's importer replaces recorded ground and merges the player's own segments.**
  `Importer.importgrid` records an imported grid at its coord whatever the segment already holds there, and
  a grid the file shares with another of the database's segments makes it `merge` the segment it is filling
  into that one — after the first shared grid, usually the player's main segment. An export taken from
  another snapshot of the world carries other grid ids for the same ground, so the player's grids are
  replaced by ids the server no longer sends: `update` then finds the live grid "oddly gone" and starts a
  segment of its own, and every reader proved against a live grid id (the remembered ground's witnesses)
  stops drawing. Each merge also re-records the whole map, one `gridinfo` store per grid, while the
  import's progress (bytes read) stands still. The fork's importer is add-only (`// addon:`,
  `Importer.fresh`): a grid is recorded only where the segment holds nothing, a grid the database already
  has keeps its data, and only a segment the import made itself is ever merged — with `merge`'s `keep`,
  which leaves a coord the destination holds alone. The database's own segments stay unmerged by an import;
  `update` still joins them as the character walks from one into another.
- **An import relaunches every live zoom grid over each grid it takes in.** `Segment.include` invalidates the
  stored zoom grids above the coord and relaunches every live `ByZCoord` over it, each relaunch a `Defer` task
  that recomputes and stores a column of zoom grids under the read lock. The minimap holds a few; the view
  distance's far ground holds thousands, so an import relaunches the top levels once per grid under them,
  the tasks pile up, and the importer's next grid waits on the write lock while the far ground rebuilds its
  textures. The fork defers it (`// addon:`, `MapFile.importer`): an include on the importing thread only
  notes the coord, and when the import ends, however it ends, `zrelaunch` relaunches each touched cell once.
  The stored zoom grids are still invalidated per grid, so what the view asks for first meanwhile is built
  fresh.
- **A copy read out of the record is never told the record moved.** `Segment.include` — the one door a grid
  enters a segment by: `update` off the live map, the importer, `merge` — refreshes `MapFile`'s own caches
  and nothing else. It empties the stored zoom grids above the coord (`ZoomGrid.inval`), relaunches every
  `ByZCoord` still alive over it and sets `Cached.loaded` on the grid's id, so an `Indir` from
  `Segment.grid` answers the new object once its reload lands — the previous one until then, never
  `Loading` (`got`) — and that identity is the whole of the signal: `MiniMap.DisplayGrid.CachedImage`
  compares `gref.get()` with the grid it drew. A copy taken out of an `Indir` is not an `Indir`. And
  `update` takes in the 3×3 grids around the character whenever the character's grid or its `seq` changes,
  the first sight of each grid in a session included, changed or not — so the ground just walked over is
  exactly the ground whose kept copy went stale. Fork: `MapFile.journal` (`// addon:`) is the ring of every
  `(segment id, grid coord)` `include` has taken in, which a reader keeping copies reads from a place of its
  own. It reads its entries **before** it takes the read lock for its reads: `include` runs under the write
  lock, so an `Indir` asked for after that answers at least as new as every entry found.
  ⚠️ **The same ground taken in twice is not the same arrays.** `Grid.from` numbers a grid's tilesets in the
  order the live tile ids first appear, so compare tiles by tileset name and version; and `savez` rounds
  every height it stores to a step of a quantum of the grid's own, within 0.01, while `include` hands the
  in-memory grid to any `Cached` it finds — so one reader gets the grid as recorded and another the grid read
  back off the disk, and their heights agree to within hundredths, never to the bit.
