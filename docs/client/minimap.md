# The minimap: the live and recorded bridge, and how a grid becomes a picture

> `MiniMap` is the widget that reads the map database ([mapfile.md](mapfile.md)) and draws it — the corner
> minimap and, through `MapWnd`, the big map window. Split out of `mapfile.md`. Line numbers are
> indicative; the class + member name is the stable anchor.

## The coordinate bridge

- `haven/MiniMap.java` `file`, `` `sessloc`, `` `curloc`. `GameUI.mmap` is the corner minimap and
  `GameUI.mapfile` the big window; **both hold the same `MapFile`**.
- `Location(seg, tc)` (``): `sessloc.tc` is the **segment tile coord of session tile (0,0)**, so
  segment tile = session tile + `sessloc.tc`. That single addition is the whole coordinate bridge.
- `resolve(Locator)` (``) is the rule to copy: **`tryLock` on the read lock, else throw `Loading`** —
  never wait for a lock a disk write may be holding. `SessionLocator` (``) derives `sessloc` from any
  live `MCache.Grid` whose `gridinfo` is known; `MapLocator` (``) and `SpecLocator` (``) are the
  other two. `tick` (``) re-resolves `sessloc` every frame and swallows `Loading`.
- **That assignment (``) is the coordinate space's own mutation point**, and carries the one
  `// addon:` seam in this file (`` → `AddonManager.sessionRebased(this, sessloc)`): every
  session world coordinate derived from a durable place goes through `sessloc`, so when it
  moves, all of them have. Two things make the seam correct rather than a poll. **It is guarded on
  `(seg.id, tc)`** — `resolve` mints a fresh `Location` object every frame, so identity says nothing and
  only those two values changing is news. And **it is filtered to `GameUI.mmap`**, the one instance
  `MapApi.sessloc()` reads: the map window's `MiniMap` ticks the same locator against the same file, and
  accepting either would let whichever ticked first consume the change for the other.
- **`sessloc` goes STALE, never null.** `tick`'s `catch(Loading){}` keeps the previous value, and
  `SessionLocator.locate` throws `Loading("No mapped grids found.")` while the new area's grids are not yet
  in `gridinfo` — so for a window after the server drops the map (a cave, a house) `sessloc` still names
  the segment you left. Anything deriving a coordinate in that window gets an old-frame answer, not a
  refusal; compare the segment rather than testing for null.

## Drawing a grid

- `DisplayGrid` (``) is one square of the display: `seg`, `sc` (the coord **at its level**), `mapext`,
  and a `gref` the caller built as `seg.grid(lvl, sc.mul(1 << lvl))`. `redisplay` (``) is the walk —
  the client never enumerates a segment, it iterates `dgext`, the rectangle it is about to paint.
- `CachedImage` (``) is the whole async pattern: it holds the `DataGrid` it last rendered, and when
  `gref.get()` hands back a *different* one it cancels the old `Defer.Future` and starts a new one.
  `get()` calls `next.get()` inside `catch(Loading){}` — so **a caller polls and gets `null` until the
  render lands**. Nothing is ever rendered on the UI thread, and the `TexI` is built off-thread too (its
  GL upload is lazy and synchronized).
- Two renderers, and the split is by level (`img()`, ``):
  - **level 0** → `MapFile.View(seg)` + `MapSource.drawmap(view, Area.sized(sc.mul(cmaps), cmaps))`, with
    the 3×3 grids around `sc` added under the read lock (`haven/MapFile.java`, `addgrid` ``,
    `fin()` ``). The neighbours are there for **tile transitions**: `drawmap` blends across the grid
    border, so a grid rendered alone carries a seam the minimap does not have.
  - **level ≥ 1** → the `ZoomGrid`'s own `DataGrid.render(sc.mul(cmaps))`.
- `olimg(tag)` (``) is the same `CachedImage` over `DataGrid.olrender(off, tag)` — the overlay mask in
  the overlay's own colour, one cache per tag in a `synchronized` map.
- Both renderers produce a **`cmaps`-sized image at every level** (`PUtils.imgraster(cmaps)` /
  `TexI.mkbuf(a.sz())` where the area is `cmaps`): 100×100 pixels, one per `2^lvl` tiles.

## Gotchas

- **The corner minimap is drawn UNDER its own frame.** `GameUI` does `mmap = blpanel.add(new CornerMap(…),
  minimapc)` and then `mmap.lower()`, which makes it the panel's **first** child; `Widget.draw` walks
  `child`→`next`, so the `gfx/hud/blframe` `Img` added before it paints **over** the map. The stock frame's
  centre is transparent, which is what makes the two read as one surround. Anything opaque drawn in that
  `Img`'s box hides the map without moving it: the hit walk runs the other way, `Img.hit` is `false` on
  that one, and `Img.checkhit` samples the resource's own alpha rather than whatever was painted — so the
  click still reaches the map beneath.
- **`MiniMap` cannot be classloaded headless** — its static `Resource.loadtex` fields need GL. An
  `Unsafe.allocateInstance` + reflect-set `file`/`sessloc` is enough for anything that only reads those.
- `MapSource.drawmap` resolves a `Tileset` layer per tile index and an `Image` layer under it, and its
  transition pass wraps `m.tiler(t)` in `catch(RuntimeException)` — a broken tiler is silently skipped,
  a missing tileset is not (`View.tileset` throws `NoSuchLayerException`).
- `DataGrid.render`/`olrender` **resolve resources**, so they throw `Loading`; on `Defer` that is a
  reschedule, on the UI thread it would be an escape into user code.
- `CachedImage` never disposes the `Tex` it replaces — the client leans on the `DisplayGrid` itself being
  dropped. Anything that caches these outside `MiniMap` owns the disposal.
