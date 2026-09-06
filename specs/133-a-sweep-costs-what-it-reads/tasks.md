# 133 — tasks

> **The surface publishes its own instrument.** `p:render()` carries the four `recall` keys —
> `recallGridsHeld` and the two cut gauges rise and fall with panning, `recallGridsRead` is cumulative and
> means something as a delta — and `counters.md` calls them what the reach costs. Both suites assert
> against those rather than against a stopwatch.
>
> **`allocPerFrame` only advances while the client draws its own `Mem:` line**, so it is absent until that
> has happened once and frozen whenever it is not — and a frozen counter compares *flat* for the wrong
> reason. Take it only when it MOVED across the window, and otherwise read the same quantity out of
> `heapUsed` frame by frame with the negative steps dropped, naming on the line which of the two the run
> used.

- [x] **133.1 — the sweep proves the base from grids it names.**
      The prove loop stops walking `AddonWidgets.loadedGrids(sess.glob.map)` — a fresh `ArrayList` of every
      loaded grid, built under the monitor the map and render threads take — and asks instead about a
      bounded set of coords the sweep already names: the base's own neighbourhood and what `readset` asked
      for, through `AddonWidgets.loadedGrid(map, gc)`, one monitor take per ask, the way `RecallTerrain.tick`
      already asks per grid every ctick. How many is a named constant beside `maxread` and `keepsquares`.
      The verdict keeps both halves — `checked == 0` fails, `wrong > 0` condemns — and `nstale`, `proven`
      and `mustrelease` are written on the same conditions, because a stale offset is a whole-map property:
      under one, every live grid disagrees, so a sample decides it as the walk did.
      *Its suite* reads `p:render()` across a bounded stretch with remembered ground on and asserts the four
      `recall` keys behave as their page says — the gauges rise and fall with `c:recallRange`, and
      `recallGridsRead` climbs while the record is read back and stops once it has caught up. It brackets
      allocation with recall on against recall off and asserts the difference is flat, which is what the
      per-grid `Coord` in the walk made it not. It `pcall`s `c:recallRange(0)` and asserts the refusal names
      the bounds the page states.
      `[manual]`: cross into ground you have explored before — expect: it is drawn, out to the range
      `c:recallRange()` reports.

- [x] **133.2 — the sweep builds nothing until it has something to read.**
      `ready`, `ids` and `got` move to their first use, behind the `tryLock` and behind the proof: `got` and
      `ids` inside the read loop, `ready` after it. A sweep that cannot take the file lock, that fails the
      proof, or that finds `readset` empty — the ordinary case with the camera standing still — then
      allocates nothing at all. And `trim` stops reconciling its LRU against the recall's own cache: the LRU
      records at the three doors this class owns — `install` (**after** its `trim(1)`, so the entry does not
      count against the room made for it), `trim`'s own `map.drop(drop)`, and the `map.trimall()` on a
      re-base — so there is no copy and no `HashSet` per trim.
      *Its suite* stands still with recall on and brackets allocation over a bounded stretch against the
      same stretch with recall off, asserting it is flat: a camera that does not move asks for nothing, and
      a sweep that allocates in that state is exactly what this task removes. It then pans until
      `recallGridsHeld` stops climbing and asserts it settles at the cap `c:recallRange()` implies and falls
      back on panning away — the LRU's own claim, and what fails first if the bookkeeping at the three doors
      drifts from what the cache holds.
      `[manual]`: pan across remembered ground and back — expect: no seam or flicker where a grid was
      dropped and read again.
