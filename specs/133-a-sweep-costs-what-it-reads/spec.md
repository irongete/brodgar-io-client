# 133 — a sweep costs what it reads

## What and why

Remembered ground is read by a **sweep**. `Recall.read()` starts one on a `Defer` worker every ctick that
one is not already running, and `Recall.sweep(Base)` proves the record's offset against the live map and
then reads what the raster asked for. Three things in it are sized by the whole world rather than by what
it reads.

**It copies the live grid cache to prove an offset.** `AddonWidgets.loadedGrids(sess.glob.map)` builds a
fresh `ArrayList` of every loaded grid under `synchronized(mc.grids)` — the monitor the map and render
threads take, which `world-3d.md` already records as a cost worth avoiding per cut. What the sweep needs is
grids whose id the record also holds; `AddonWidgets.loadedGrid(map, gc)` answers one at a time, and
`RecallTerrain.tick` already asks that way, per grid, every ctick.

**Then it walks all of them.** The prove loop asks the record for `lg.gc.add(base.off)` for every live grid
— a `Coord` each — and counts checked against wrong, when what it decides is binary: one agreement proves
the offset, one disagreement condemns it. The comment beside the loop already names the reliable witness —
*"the character's own grid is recorded within a second of arriving"*.

**And it builds three maps before knowing there is anything to do.** `sweep` opens with a `HashMap` for
`ready`, one for `ids` and one for `got`, then takes a `tryLock` that may fail and reads a wanted set that
is routinely empty.

Beside them, `trim` copies a cache the same way to reconcile its LRU. That one is the recall's **own**
`MCache` — `new MCache(sess)`, not the session's — so it is bounded by `gridcap()` and its monitor is
nobody else's. It is the small half, and it is the same shape.

Measured on a client holding hours of ground, the sweep is **5.5 % of all client allocation**, most of it
the `Coord` per grid in the prove loop.

## Acceptance criteria

1. A sweep takes the live cache's monitor a bounded number of times — once per grid it names — rather than
   once to copy every grid there is.
2. It proves the base from grids it names, and the verdict is unchanged: one disagreement still condemns
   the offset, and remembered ground leaves the scene on a re-base in the frame it is found.
3. Nothing is built until the sweep holds the lock and has something to read.
4. `trim` reconciles its LRU without copying the recall's own cache.
5. Remembered ground is unchanged to look at: the same grids at the same range, and `c:recall`,
   `c:recallRange` and `c:recallGrey` do what their page says.
6. The four `recall` counters answer as they do now — `recallGridsHeld` and the cut gauges rise and fall
   with panning, and `recallGridsRead` climbs while the record is read back and stops when it catches up.
7. A sweep that cannot take the file lock, or that has nothing wanted, allocates nothing.

## Out of scope

- **`MCache.trim`/`trimall` disposing a `Grid`'s cut meshes while `MapView.MapRaster.Grid.tick` still holds
  their slots** — `ROADMAP.md` line 36. Same subsystem and a real defect, but a different one: a disposed
  mesh drawn on, where this feature is work sized by the wrong thing.
- **What the recall costs to DRAW.** `recallmaxbuild`, `recallcutcap` and the two-pass `RecallTerrain.tick`
  are a decided budget with their own section on `terrain-raster.md`; this feature is the read behind them
  and touches neither.
- **Reading fewer grids.** `maxread`, `gridcap()` and `keepsquares` say how much ground the record hands
  back and how much is held; those numbers are the feature's, not this one's. What changes is the cost of
  deciding, not the amount decided.

## Docs impact

`docs/client/terrain-raster.md` — one gotcha, on the page that already maps the recall raster: the recall
holds its **own** `MCache`, so `loadedGrids` on the *session's* cache takes the monitor the render path
wants while `loadedGrid` on the recall's own takes nobody's. That distinction is what makes one of these
two copies expensive and the other merely wasteful, and nothing states it today.

No `docs/addons` page: nothing an addon can observe changes, and criteria 5 and 6 are the promise.

Derived impact set — every page naming this surface:

```text
$ grep -rln "remembered ground\|recallRange\|recallGrey\|recallGridsHeld" docs/
docs/addons/api/client/README.md              the three verbs — unchanged, criterion 5
docs/addons/api/client/profiling/counters.md  the four counters — unchanged, criterion 6
docs/addons/api/world.md                      names remembered ground in passing
docs/client/terrain-raster.md                 the raster's own page — this feature's gotcha
```

## Context files

- `src/io/brodgar/session/Recall.java` — 1, 2 (`sweep`, `read`, `trim`, `want`, `lru`, `pinned`, `base`,
  `Base.off`, `readset`, `pending`, `maxread`, `gridcap`)
- `src/haven/AddonWidgets.java` — 1, 2 (read only: `loadedGrids`, `loadedGrid`, and the monitor each takes)
- `src/haven/MCache.java` — 1 (read only: `grids`, `Grid.gc`, `Grid.id`, `Grid.removed`, `drop`)
- `src/haven/MapView.java` — 1, 2 (`recalltick` hands the source the character's session grid coord —
  `new Coord2d(getcc()).floor(tilesz).div(cmaps)` — beside the minimap, which is what the proof is
  anchored on; `RecallTerrain.tick` as the shape that already asks per grid, and `gridvisible`, which
  is why the range is a maximum bounded by the view)
- `src/haven/MapFile.java` — 1 (read only: `Segment.gridid`, and the read lock the sweep tries)
- `docs/client/terrain-raster.md` — 1, 2 (the recall raster's page; the two-caches gotcha is written
  there)
- `docs/client/minimap.md` — 1, 2 (the base's own proof recipe, which 133.1 rewrote: which grids to
  name as witnesses, and why session grid `(0, 0)` is not one of them)
- `docs/client/world-3d.md` — 1 (read only: what taking the `grids` lock per cut already costs)
- `docs/addons/api/client/profiling/counters.md` — 2 (read only: what the four counters promise)
- `docs/addons/api/client/README.md` — 2 (read only: **Remembered ground** — `recallRange` is a
  *maximum* bounded by what fits in the view, so a suite cannot drive a gauge with it)
- `DOCUMENTATION.md` — 1
