# Subsystem — the minimap: the live ⇄ recorded bridge, and how a grid becomes a picture

> `MiniMap` is the widget that reads the map database ([mapfile.md](mapfile.md)) and draws it — the corner
> minimap and, through `MapWnd`, the big map window. Split out of `mapfile.md` at 037.4. Line numbers are
> indicative; the class + member name is the stable anchor.

## The coordinate bridge

- `haven/MiniMap.java:50` `file`, `:53` `sessloc`, `:52` `curloc`. `GameUI.mmap` is the corner minimap and
  `GameUI.mapfile` the big window; **both hold the same `MapFile`**.
- `Location(seg, tc)` (`:83`): `sessloc.tc` is the **segment tile coord of session tile (0,0)**, so
  segment tile = session tile + `sessloc.tc`. That single addition is the whole coordinate bridge.
- `resolve(Locator)` (`:347`) is the rule to copy: **`tryLock` on the read lock, else throw `Loading`** —
  never wait for a lock a disk write may be holding. `SessionLocator` (`:102`) derives `sessloc` from any
  live `MCache.Grid` whose `gridinfo` is known; `MapLocator` (`:142`) and `SpecLocator` (`:162`) are the
  other two. `tick` (`:373`) re-resolves `sessloc` every frame and swallows `Loading`.

## Drawing a grid

- `DisplayGrid` (`:597`) is one square of the display: `seg`, `sc` (the coord **at its level**), `mapext`,
  and a `gref` the caller built as `seg.grid(lvl, sc.mul(1 << lvl))`. `redisplay` (`:723`) is the walk —
  the client never enumerates a segment, it iterates `dgext`, the rectangle it is about to paint.
- `CachedImage` (`:617`) is the whole async pattern: it holds the `DataGrid` it last rendered, and when
  `gref.get()` hands back a *different* one it cancels the old `Defer.Future` and starts a new one.
  `get()` calls `next.get()` inside `catch(Loading){}` — so **a caller polls and gets `null` until the
  render lands**. Nothing is ever rendered on the UI thread, and the `TexI` is built off-thread too (its
  GL upload is lazy and synchronized).
- Two renderers, and the split is by level (`img()`, `:645`):
  - **level 0** → `MapFile.View(seg)` + `MapSource.drawmap(view, Area.sized(sc.mul(cmaps), cmaps))`, with
    the 3×3 grids around `sc` added under the read lock (`haven/MapFile.java:1336`, `addgrid` `:1355`,
    `fin()` `:1382`). The neighbours are there for **tile transitions**: `drawmap` blends across the grid
    border, so a grid rendered alone carries a seam the minimap does not have.
  - **level ≥ 1** → the `ZoomGrid`'s own `DataGrid.render(sc.mul(cmaps))`.
- `olimg(tag)` (`:673`) is the same `CachedImage` over `DataGrid.olrender(off, tag)` — the overlay mask in
  the overlay's own colour, one cache per tag in a `synchronized` map.
- Both renderers produce a **`cmaps`-sized image at every level** (`PUtils.imgraster(cmaps)` /
  `TexI.mkbuf(a.sz())` where the area is `cmaps`): 100×100 pixels, one per `2^lvl` tiles.

## Gotchas

- **`MiniMap` cannot be classloaded headless** — its static `Resource.loadtex` fields need GL. An
  `Unsafe.allocateInstance` + reflect-set `file`/`sessloc` is enough for anything that only reads those.
- `MapSource.drawmap` resolves a `Tileset` layer per tile index and an `Image` layer under it, and its
  transition pass wraps `m.tiler(t)` in `catch(RuntimeException)` — a broken tiler is silently skipped,
  a missing tileset is not (`View.tileset` throws `NoSuchLayerException`).
- `DataGrid.render`/`olrender` **resolve resources**, so they throw `Loading`; on `Defer` that is a
  reschedule, on the UI thread it would be an escape into user code.
- `CachedImage` never disposes the `Tex` it replaces — the client leans on the `DisplayGrid` itself being
  dropped. Anything that caches these outside `MiniMap` owns the disposal (D-098).
