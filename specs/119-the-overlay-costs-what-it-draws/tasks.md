# 119 — tasks

- [x] **119.1 — The overlay work has a number.** Two cumulative counters incremented where the work is
      actually done, in `MCache.Grid.getolcut`: one when `MapMesh.makeol` builds a cut's overlay mesh, one
      when `makeolol` builds its outline. They are read as `overlayMeshes` and `overlayOutlines` in
      `ProfHandle`'s `render()` table, beside `gobsHeld`, which is cumulative in the same way — and like
      the rest of `counters()` they are readable with profiling **disarmed**, since they count work the
      client does whether or not anyone is watching.
      *Its suite* reads both, lays one patch of its own, waits a bounded number of ticks for its ground to
      be cut, and asserts both **rose**; then asserts they never fall across a second reading, which is
      what cumulative means. It asserts a read with profiling disarmed answers a number rather than nil,
      because that is the property the whole feature's proof rests on.
      `[manual]`: none — every claim here is a number the suite reads back.

- [x] **119.2 — Registering one overlay re-cuts one overlay.** Replaces the single `MCache.olseq` for the
      three bumps that concern one id — `add`, `remove`, `RectOverlay.update` — with a sequence per
      `OverlayInfo` in a concurrent map, plus a `Cut.olstamp` per cached entry that `getolcut` compares.
      `remove` enqueues its id for a drop that **`MCache.ctick` drains** across every loaded grid, on the
      thread that builds cuts, because after removal nothing asks `getolcut` for that id again and its
      meshes would never be disposed. The `mapdata2` grid fill and `Grid`'s `olseq = -1` on a rebuilt cut
      mesh keep the grid-wide flush: those two do mean everything.
      *Its suite* lays five patches, waits for `overlayMeshes` to settle, lays a sixth and records the
      delta; then lays twenty more, settles, lays one more and records that delta. It asserts the **two
      deltas match** — the cost of laying one is what one costs, whatever is already down. It then removes
      a patch and asserts `overlayMeshes` does not rise on the next ticks, since taking one up builds
      nothing. It re-asserts that a patch it laid still answers `:exists()`, `:drawn()` and takes a
      `:tint`, because the one thing this must not change is the surface. `RectOverlay.update` has no
      Lua caller to drive, so its half is verified by reading that it bumps the same per-id sequence.
      `[manual]`: with `simple-gob-hider` on, walk into a wood you have not loaded this session — expect
      no hitch as objects appear, none that grows the more of them are already marked, and the marks
      drawn normally on the ground that streamed in while you walked.

- [ ] **119.3 — Nobody pays for an outline nobody draws.** `getolcut` builds `makeolol(id)` only when
      `id.omat() != null`, which is the same condition `MapView.Overlay.added` already uses to decide
      whether the `outl` grid is in the tree at all — so an overlay without an outline material stops
      paying a full tile-laying pass per cut for a mesh that was never reachable. `getololcut` then
      answers `null` there, which `TreeSlot` already treats as a legal absent child. And `MCache.getols`
      collects into a `LinkedHashSet` rather than an `ArrayList` it re-scans with `contains` per
      candidate, once per frame from `MapView.oltick`.
      *Its suite* lays a patch — whose `omat()` is null — and asserts `overlayOutlines` **did not move**
      while `overlayMeshes` did, which is the whole claim in one comparison. It re-reads both after
      several ticks to show the outline is not built late either. The `getols` half changes what no Lua
      call can observe, so it is verified by reading the site: a `LinkedHashSet` answers the same
      overlays in insertion order, which is the order the `ArrayList` gave.
      `[manual]`: turn on Display personal claims where you hold one — expect it drawn exactly as before,
      since a claim's overlay does carry an outline material and must still get one.

- [ ] **119.4 — A mark costs the cuts it covers.** `MapView.Overlay` overrides `MapRaster.skipcut`, so its
      raster asks `getolcut` only for cuts the overlay's mask can reach rather than for every cut of the
      drawn area. `MCache` answers that with `olreaches(id, area)`: true wherever a grid in the area records
      that id — recorded masks cannot be tested cheaply and must answer *maybe* — and otherwise true only
      where some `LocalOverlay` with that id does not `filter` the area out. The area tested is the cut's own
      tiles with a one-tile margin, which is what `makeolol` reads. It is the second half of *a patch costs
      its own cuts*: 119.2 stopped one overlay paying for another's, and this stops one paying for ground its
      own mask never touches — 25 cuts per mark where a mark covers two, and 25 × N `getolcut` calls every
      frame with N marks down, each taking `synchronized(grids)`.
      *Its suite* lays one patch under the character, settles, and asserts the delta is **below** the cuts
      the ring's own bounding box spans with its margin, which it computes from the ring it passed. It then
      lays one whose ring is far outside the drawn terrain and asserts `overlayMeshes` **did not move at
      all**. It re-asserts `:exists()`, `:drawn()` and a `:tint` on the near one, because a mark skipped
      where it should be drawn is the one way this goes wrong.
      `[manual]`: with `simple-gob-hider` on, walk into a wood with dozens marked — expect no hitch at all,
      and every mark drawn where its object stands.

- [ ] **119.5 — The pages.** Rewrites both gotchas in `docs/client/world-3d.md`: *registering a
      `LocalOverlay` is a re-cut of the whole grid* becomes a re-cut of that overlay's own cuts, and the
      advice built on it loses its reason; *the sheet's two hidden costs* loses both halves and keeps what
      is still true, the fixed-enormous-rectangle rule and `makeol` answering `null` on an empty mask.
      Adds `overlayMeshes` and `overlayOutlines` to the `render()` table in
      `docs/addons/api/client/profiling/counters.md`. Discharges `docs/addons/api/virtual/patches.md`,
      whose cost line understates today and becomes exactly true with nothing rewritten.
      *Its suite* runs the counters page's own `render()` example verbatim and asserts it prints the two
      new keys, since a page whose example does not run is the defect this catches.
      Then the `DOCUMENTATION.md` §11 checks over every page touched: links and anchors resolved across
      newlines, `wc -l` against the ceilings, headings, the change-note wording greps, every `hafen.*`
      symbol present in `src/`, and the spec's impact set discharged row by row.
