# 120 — The ground is there when you look — plan

## Approach

**One owner decides what is wanted.** `MapView.RecallTerrain.tick` computes the wanted **grid** set
once per ctick — the range square around `RTSCam.center()`, each grid's own world box put to
`MapView.boxvisible` — and hands it to `Recall`. `Recall` reads exactly that set plus a one-grid
margin (`MapMesh.dotrans` reads a tile across the cut edge, and the corner heights need the same
tile) and keeps by LRU. Nothing is read while the raster is out of the scene. This is what kills the
per-cut frustum test — eight `clipxf` for each of 400 cuts, which is why 5 Hz was the only
affordable rate — and it stops reading grids no camera can see.

**Each throttle becomes a concurrency target.**

- *Read.* `Recall` keeps a `pending` set of grid coords asked for and not yet installed, so `maxread`
  bounds **new** asks; today a grid still `Loading` re-consumes a slot on every sweep, which is what
  makes the cap eight *in flight*. The 0.25 s `period` goes — `sweeping` already serialises, so one
  sweep runs per ctick. The `MapFile.lock.readLock().tryLock()` stays, never `lock()`, because
  `MapFile`'s processor thread holds the write lock across a segment save; and `maxread` stays
  bounded for that same reason, since an ask is a `Defer` task that parks on that lock.
- *Build.* `recallmaxbuild` becomes an in-flight target sized from the `Defer` pool less a stated
  reserve (`Defer.maxthreads` is `max(2, availableProcessors() - 1)`), and the raster ticks every
  ctick, so a finished build is replaced within 50 ms rather than 200. Throughput stops being
  `cap / period` and becomes `cap / build-latency`.

**What is built is kept.** `Recall.tick`'s `map.trim(square)` every ctick goes. The keep set is an
LRU over grids by last wanted, trimmed to `gridcap`, and **a grid the raster holds a cut of is never
trimmed** whatever the LRU says — which is the ROADMAP defect (`MCache.trim` disposing a `Grid`'s
cut meshes while `MapRaster.Grid.tick` still holds their slots) kept out of reach here rather than
fixed in the live path. Disposal stays grid-granular; see *Discarded alternatives*.

**The settings are the client's.** `MapView.recallon` and `washamt` stop being instance fields:
three statics with three like-named prefs, written in one statement, the `MapView.invcamx` shape.
The wash stops being an amount — `recallgrey` is a boolean, and the slot's `ostate` carries the one
cached `io.brodgar.session.Greyscale` or `null`, which is legal (`RenderTree.TreeSlot.ostate`) and
compiles no fragment mod at all. `:recall` loses `off`/`on`/`wash <a>` and becomes a report.
`io.brodgar.ui.ClientPanel` gains the section beside its profiling checkbox;
`io.brodgar.addon.ClientOptions` gains three rows on the `InterfaceOptions.posGran` shape
(`OptionsMethod.num`, then a bounds refusal naming the bounds).

**The budgets are dials, and they ship with a number the maintainer chose.** `:recall` reports grids
held against `gridcap` and cuts drawn against `recallcutcap`; the shipped constants are what a long
pan says they should be, the way 068.3 shipped the wash amount rather than guessing it.

## Files to create/modify

- `src/io/brodgar/session/Recall.java` — the wanted-set door, `pending`, the LRU keep, the counters
- `src/haven/MapView.java` — the three statics and their prefs; `RecallTerrain` (per-grid pre-reject,
  in-flight target, ctick rate, the `groundChanged` opt-out); `recalltick`'s order; `MapRaster` gains
  the hook that says whether a raster's cuts are live ground; `:recall` reduced to a report
- `src/io/brodgar/ui/ClientPanel.java` — the **Remembered ground** section
- `src/io/brodgar/addon/ClientOptions.java` — `recall()`, `recallRange()`, `recallGrey()`
- `docs/client/terrain-raster.md` — both rows and the trim gotcha: the schedule, the budgets, the
  settings, and what the wanted set is now centred and bounded by
- `docs/client/mapfile.md` — the read-back row: what bounds the read, and the `pending`/`tryLock` pair
- `docs/addons/api/client/README.md` — three rows in `client()`'s table and a section under it

## Risks & gotchas

1. **`MapRaster.Grid.tick` taps `AddonManager.groundChanged()` at both of its mutation points**, and
   that sets `VirtualApi.groundDirty`, drained on the addon tick to re-place every free
   `hafen.virtual()` entity. `MapView.grounddrawn` reads `terrain.main.cuts` **alone**, so every
   recall cut that comes or goes is a false wake — harmless at 5 Hz, a drain every tick at 20.
   `RecallTerrain` must opt out, which is the one change this makes to `MapRaster` itself.
2. **`skipcut` runs outside `Grid.tick`'s `catch(Loading)`** and nothing above it catches one either
   (`docs/client/terrain-raster.md`). The pre-reject consults `AddonWidgets.loadedGrid`, which takes
   `MCache.grids` and cannot throw; it must stay that way.
3. **`Defer.Future.get` throws `NotDoneException` rather than blocking**, so a 20 Hz raster tick
   never waits on a build. What it costs is `Grid.tick`'s walk of `area`, which is exactly why the
   pre-reject is per grid and not per cut.
4. **`boxvisible` rejects only when all eight corners fall outside one plane**, so a grid-sized box
   answers *maybe visible* generously and never *invisible* when it is visible. Sound as a
   pre-reject, and it is the same test `cutvisible` already wraps.
5. **The read budget is what bounds how many `Defer` workers can park on `MapFile.lock`** during a
   segment save. Raised past the pool it starves the mesh builds behind it in the one shared queue.
6. **The wanted set crosses a thread boundary** — written by the raster on the UI thread, read by the
   sweep on a `Defer` thread — so it is replaced whole, the way `Recall.Base` already is.
7. **A grid still `pending` when the base moves must not install behind `trimall`.** `proven` and
   `mustrelease` already gate drawing; `pending` is cleared with them.

## Discarded alternatives

- **Skipping `MapMesh.dotrans` for remembered ground** — the transition blending the wash desaturates
  away is most of the per-cut cost. Rejected: the cut needs the neighbouring tile for its corner
  heights anyway, so the fill margin stays either way, and the schedule alone reaches the target
  without a second mesh path that has to be kept true to the first.
- **A dedicated reader thread calling `MapFile.Grid.load` directly** — it takes the reads off the
  shared `Defer` queue, so a segment save cannot park the pool. Rejected: it decodes a second time
  what the minimap already pays for through `Segment.grid`'s weak `Cached`, to buy a stall that lasts
  one save.
- **A per-cut mesh budget**, finer than the grid, dropping what is farthest rather than sixteen
  neighbours at once. Rejected: `MCache.Grid.Cut.dispose` leaves its `Deferred` `inited` with a null
  `val`, so a cut disposed on its own answers `LoadingMap` for ever and never rebuilds. Making one
  rebuildable means a `reset()` on `Deferred` — an upstream seam bought for the ~2 ms disk read that
  is all a kept grid saves over a re-read one.
- **Keeping the wash as an amount beside the checkbox** — rejected: two spellings of one setting, and
  an off state indistinguishable from `wash 0`.
- **Reading a square around the player while the raster is out of the scene**, to pre-warm the first
  pan — rejected: disk work for a feature that is switched off, and with the schedule fixed the first
  pan pays a few hundred milliseconds for itself.
- **Deriving the drawn cap from the frustum** — rejected in 068 and still rejected: `FreeCam.dist`
  has no ceiling, so the frustum is not a cap.
