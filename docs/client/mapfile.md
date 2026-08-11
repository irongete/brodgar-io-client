# The map database: `MapFile`

> The map the player has **explored**, on disk and persistent — as opposed to `MCache`, the terrain
> streamed around them ([state.md](state.md)). The client that reads it — the coordinate bridge and the
> drawings — is [minimap.md](minimap.md).

## The file

- `haven/MapFile.java` — `MapFile(ResCache store, String filename)`: the store is a **constructor
  argument**, so a throwaway in-memory `ResCache` gives you the whole subsystem headlessly.
  `load(store, filename)` (``) reads only the *index*: `knownsegs` (``) and every `Marker` (``).
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

## Gotchas

- **`Loading` is everywhere on this path** (`Indir.get`, a tileset resource, `olid.get`) and it is a
  `RuntimeException`: catch broadly at the API boundary or it escapes into user code.
- A grid id and a segment id are **64-bit**; expose them as decimal strings, never Lua numbers.
- `markerseq` does **not** bump for markers loaded from disk at startup, so the initial load never calls
  `onMarkersChanged`. Every call into it is therefore already a **real** change — `MapApi.fireMarkersChanged`
  fires on the first call too (it primes `lastMarkerSeq` and fires in the same call), unlike the old poll's
  prime-then-skip: there is no "first tick after login" to distinguish from a real one anymore.
- `new MapFile(null, "")` does no I/O, but a read NPEs *inside a `Defer` task* and surfaces wrapped rather
  than clean — give a headless probe a real (in-memory) store.
