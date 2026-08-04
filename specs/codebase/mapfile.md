# Subsystem — the map database (`MapFile`) and the minimap that reads it

> The map the player has **explored**, on disk and persistent — as opposed to `MCache`, the terrain
> streamed around them ([state.md](state.md)). Line numbers are indicative; the class + member name is
> the stable anchor.

## The file

- `haven/MapFile.java:41` — `MapFile(ResCache store, String filename)`: the store is a **constructor
  argument**, so a throwaway in-memory `ResCache` gives you the whole subsystem headlessly.
  `load(store, filename)` (`:90`) reads only the *index*: `knownsegs` (`:46`) and every `Marker` (`:47`).
- **One `ReentrantReadWriteLock`** (`:49`) guards everything; `checklock()` (`:57`) makes several methods
  *assert* the caller holds it. The **processor thread** (`:219`–`:275`) takes the WRITE lock for segment
  saves and the index save, **across disk I/O** — so a UI-thread reader must `tryLock`, never `lock`
  (`MiniMap.resolve`, below). `defersave()` (`:135`) only queues; nothing writes synchronously.
- Both caches are `BackCache` (`haven/BackCache.java`): a `load` function, a `store` function, and a
  size-bounded access-ordered `LinkedHashMap`. **`get` mutates the map** (insert + LRU touch) while
  callers hold only the *read* lock — the client's own discipline; mirror it rather than fixing it.
  - `gridinfo` (`:151`), `BackCache(100)`: grid id → `GridInfo{id, seg, sc}` (`:142`), one tiny file per
    grid. **The only bridge from a server grid id into the database**, and it needs no grid data.
  - `segments` (`:1467`), `BackCache(5)`: segment id → `Segment`. Loading one is a **synchronous** file
    read (small: just the coord → grid-id map). Only five live at once, so the same segment comes back as
    a *different object* after an eviction — never intern on its Java identity.

## Segments, grids, markers

- `Segment` (`:1181`) is an inner class: `id` plus a **private** `map` (coord ⇄ grid id, `HashBMap`) and
  three weak `CacheMap`s. `map` being private is why there is no "list this segment's grids": the client
  itself never enumerates — `MiniMap.redisplay` walks the coords of the rectangle it draws.
  - `grid(long id)` (`:1229`) / `grid(Coord sc)` (`:1278`) / `grid(int lvl, Coord gc)` (`:1289`, zoom
    levels; `lvl 0` delegates) all hand back an `Indir<Grid>` and all `checklock()`. `get()` on it throws
    `Loading` until `Defer` has the file (`loadgrid`, `:1219`) — take the `Indir` under the lock, call
    `get()` outside it, catch `RuntimeException` to nil.
  - `include(Grid, sc)` (`:1325`) is how a grid enters a segment; it also invalidates the zoom cache.
- `DataGrid` (`:473`): `tilesets[]` (`TileInfo` = `Resource.Saved` + `prio`), `tiles[]` (indices **into
  this grid's own tilesets** — unrelated to `MCache`'s tile ids), `zmap[]`, `ols`, `mtime`. `gettile(c)`
  / `getfz(c)` take a within-grid coord `0..cmaps`. `render(off)` (`:516`) and `olrender(off, tag)`
  (`:566`) build a `BufferedImage` and **resolve tileset resources**, so they can throw `Loading`
  (037.4's business). `Grid` (`:729`) adds the server `id`; `Grid.load` (`:857`) / `save` (`:834`).
- `Overlay` (`:459`) is a per-grid boolean mask keyed by an overlay **resource**; `MCache.ResOverlay.tags()`
  says which tags it carries, several resources may share one (hence `olrender`'s composite), and `olid.get()`
  throws `Loading` — who *displays* them is in [world-3d.md](world-3d.md) (`realm` here, `prov` there).
  Markers (`:277`–`:437`): `Marker{seg, tc, nm}`, `PMarker` (colour, onmap), `SMarker` (oid, res, data).
  `add`/`remove` take the write lock, `defersave()` and bump `markerseq` (`:48`) — the only change
  signal there is. **A `Marker` object is loaded once and mutated in place**, so its Java identity IS
  stable, unlike a segment's or a grid's.
- `merge(dst, src, soff)` (`:1525`) is the trap the whole anchor rule exists for: it re-bases the loser's
  grid coords **and rewrites every marker's `seg`/`tc` in place**. A stored segment coord does not go
  stale, it points somewhere else. A grid id is the server's and never moves (D-096).
- `update(MCache, Coord cgc)` (`:2029`) queues the 3×3 grids around a coord; `GameUI.mapfilesave`
  (`haven/GameUI.java:1315`) calls it whenever the player's grid or its `seq` changes — which is why the
  recorded grid under the player is current.

## MiniMap — the live ⇄ recorded bridge

- `haven/MiniMap.java:50` `file`, `:53` `sessloc`, `:52` `curloc`. `GameUI.mmap` is the corner minimap and
  `GameUI.mapfile` the big window; **both hold the same `MapFile`**.
- `Location(seg, tc)` (`:83`): `sessloc.tc` is the **segment tile coord of session tile (0,0)**, so
  segment tile = session tile + `sessloc.tc`. That single addition is the whole coordinate bridge.
- `resolve(Locator)` (`:347`) is the rule to copy: **`tryLock` on the read lock, else throw `Loading`** —
  never wait for a lock a disk write may be holding. `SessionLocator` (`:102`) derives `sessloc` from any
  live `MCache.Grid` whose `gridinfo` is known; `MapLocator` (`:142`) and `SpecLocator` (`:162`) are the
  other two. `tick` (`:373`) re-resolves `sessloc` every frame and swallows `Loading`.
- `DisplayGrid` (`:597`) with `img()` (`:645`) and `olimg(tag)` (`:673`) is how the client turns a grid
  into a `Tex`: `Defer.later(() -> new TexI(grid.render(...)))`, cached per grid — the shape 037.4 reuses.

## Gotchas

- **`Loading` is everywhere on this path** (`Indir.get`, a tileset resource, `olid.get`) and it is a
  `RuntimeException`: catch broadly at the API boundary or it escapes into user code.
- A grid id and a segment id are **64-bit**; expose them as decimal strings, never Lua numbers.
- `markerseq` does **not** bump for markers loaded from disk at startup — prime the poll, do not fire it.
- `new MapFile(null, "")` does no I/O, but a read through it NPEs *inside a `Defer` task* and surfaces
  wrapped rather than clean — give a headless probe a real (in-memory) store instead.
