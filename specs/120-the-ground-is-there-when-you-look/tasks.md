# 120 — The ground is there when you look — tasks

- [x] **120.1 — The system has a surface: three settings and four numbers.** `MapView.recallon` and
      `washamt` stop being per-view fields. `recallon`, `recallrange` (1..8 grids, default 2) and
      `recallgrey` become statics with like-named prefs written in one statement, the
      `MapView.invcamx` shape; `Recall.radius` and `RecallTerrain`'s own `r` read the range instead
      of a constant. The wash becomes a boolean — the slot's `ostate` carries one cached `Greyscale`
      or `null`. `io.brodgar.ui.ClientPanel` gains a **Remembered ground** section (checkbox,
      `HSlider`, checkbox) beside its profiling one, and `ClientOptions` gains `recall()`,
      `recallRange()` and `recallGrey()` on the `InterfaceOptions.posGran` shape.
      `ProfHandle.render()` gains `recallGridsHeld`, `recallGridsRead`, `recallCutsDrawn` and
      `recallCutsWanted`, readable with profiling disarmed like `overlayMeshes`; `:recall` loses
      `off`/`on`/`wash <a>` and reports the same four.
      *Its suite* round-trips all three options and asserts each reads back what it wrote, then
      `pcall`s three refusals — an explicit `nil`, a non-number range, and a range outside 1..8 —
      asserting the last names its bounds and that a refused write leaves the old value standing. It
      reads all four counters with profiling **disarmed** and asserts each answers a number, because
      every later task's proof rests on that.
      `[manual]`: open Options ▸ Game ▸ Client — expect a **Remembered ground** section with the
      three controls, and its checkbox moving when the suite's Lua write runs.

- [x] **120.2 — The read side asks once, and asks for what is wanted.** `Recall` gains a `pending`
      set of grid coords asked for and not yet installed, so `maxread` bounds **new** asks rather
      than outstanding ones — today a grid still `Loading` re-consumes a slot on every sweep. The
      0.25 s `period` goes: `sweeping` already serialises, so one sweep runs per ctick. What is read
      is the wanted grid set `RecallTerrain` hands over plus a one-grid margin, not a square, and
      nothing is read while the raster is out of the scene. The `tryLock` on
      `MapFile.lock.readLock()` stays and `maxread` stays bounded, because an ask is a `Defer` task
      that parks on that lock across a segment save. `pending` clears with `proven`/`mustrelease`.
      *Its suite* installs the `rts` camera, turns the system on at a known range, then polls
      `recallGridsRead` every tick and asserts it settles within a bounded window — the read side's
      whole claim as a number, scored over what the run reached. It then writes `recall(false)` and
      asserts the count stops moving at all, which is what "nothing is read while it is out of the
      scene" means.
      `[manual]`: run `:recall` after walking a while — expect **requests sent 0**.
      `[manual]`: walk into a house or a cave with the camera panned away — expect the ground to go
      and come back in the right place, never drawn in the wrong one.

- [x] **120.3 — The build side is a concurrency target, not a per-tick quota.** `RecallTerrain.tick`
      runs every ctick instead of every 0.2 s, and `recallmaxbuild` becomes an in-flight target sized
      from `Defer`'s own `max(2, availableProcessors() - 1)` less a stated reserve, so throughput is
      `cap / build-latency` rather than `cap / period`. What makes 20 Hz affordable is a **per-grid**
      pre-reject: one `boxvisible` on each grid's world box and one `AddonWidgets.loadedGrid`, so
      `cutvisible` runs over the cuts of surviving grids alone instead of eight `clipxf` for each of
      400. `MapRaster` gains a hook saying whether a raster's cuts are live ground, false for
      `RecallTerrain`.
      *Its suite* polls `recallCutsDrawn` against `recallCutsWanted` and asserts the two meet within
      a bounded window, which is acceptance criterion 1 read back as a number rather than judged by
      eye. It asserts `recallCutsDrawn` never exceeds the cap it read. The `groundChanged` half
      changes what no Lua call can observe, so it is verified by reading the site: `Grid.tick` taps
      it at both mutation points, `MapView.grounddrawn` reads `terrain.main.cuts` alone, and at 20 Hz
      that tap drains the free-entity placement pass every tick for ground no entity can stand on.
      `[manual]`: pan onto remembered ground — expect it grey, with no objects and no grass, and the
      seam with live ground showing no step and no shimmer.
      <!-- extra context: src/io/brodgar/addon/VirtualApi.java — groundDirty and what drains it -->

- [x] **120.4 — What is built is kept, and what is dropped is dropped safely.** `Recall.tick`'s
      `map.trim(square)` every ctick goes. The keep set becomes an LRU over grids by last wanted,
      trimmed to `gridcap`, and a grid the raster holds a cut of is **never** trimmed whatever the
      LRU says — `RecallTerrain.main.cuts` is the authority, which is the ROADMAP defect (`MCache.trim`
      disposing cut meshes while `MapRaster.Grid.tick` still holds their slots) kept out of this
      source's reach. `gridcap` and `recallcutcap` are stated constants scaled from the range, and
      both are reported by `:recall` and by the counters.
      *Its suite* reads `recallGridsHeld`, writes a smaller `recallRange` and asserts the count falls
      to the new cap within a bounded window, then writes a larger one and asserts it rises again —
      the budget read back as a number in both directions. It asserts across every reading it takes
      that neither count ever exceeds its cap.
      `[manual]`: `:cam rts`, pan two screens off and straight back — expect the ground already
      there on the return, with nothing growing into place a second time.
      `[manual]`: pan far in one direction for a minute, then run `:recall` — report grids held and
      cuts drawn against their caps, so the shipped constants are set from a number.

- [x] **120.5 — The pages.** Rewrites `docs/client/terrain-raster.md`'s two rows and its trim
      gotcha: the wanted set is the raster's and is grid-shaped, the schedule is a concurrency target
      at ctick rate, the budgets are the LRU and the cut cap, and the settings replace the console
      arguments. Rewrites the read-back row in `docs/client/mapfile.md` for the `pending`/`tryLock`
      pair and what now bounds a read. Adds the three rows and their section to
      `docs/addons/api/client/README.md`'s `client()`, and the four counters to the `render()` table
      in `docs/addons/api/client/profiling/counters.md`, saying which are gauges and which
      cumulative.
      *Its suite* runs the `client()` section's own example and the counters page's `render()`
      example verbatim, asserting each prints what its page says — a page whose example does not run
      is the defect this catches.
      Then the `DOCUMENTATION.md` §11 checks over every page touched: links and anchors resolved,
      `wc -l` against the ceilings, headings, the change-note greps, every `hafen.*` symbol present
      in `src/`, and the spec's impact set discharged row by row.

- [ ] **120.6 — Nothing is wanted while nothing is drawn.** `recalltick`'s out-of-scene branch tells the
      read side `recall.want(null)` and drops the raster, but never ticks it — so `RecallTerrain.nwanted`
      keeps the value it held when it left, and with `recall(false)`, or under any camera but `rts`, an
      addon reads 0 cuts drawn against a cuts wanted that never falls. The counters' own page calls the
      three gauges what is held, drawn and wanted **right now**, and 120.2 already made the read side say
      nothing is wanted; the drawn side's half of that word was left behind. The branch zeroes the
      raster's own counts as it drops it, the way `RecallTerrain.tick` already does with no centre.
      *Its suite* reads `recallCutsWanted` above zero with the raster in the scene, writes
      `recall(false)`, and asserts both counts read zero within a bounded window and stay there while
      the range is written under them — then switches back on and asserts wanted rises again, which is
      the gauge proved in both directions rather than only on the way down.
