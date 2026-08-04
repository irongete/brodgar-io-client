# 037-map-database — Tasks

- [x] 037.1 — **The restructure.** `hafen.world` absorbs all thirteen of today's `hafen.map`
      functions unchanged; `hafen.map` empties out; `hafen.markers` → `hafen.map.markers` (same
      surface) and `hafen.radar` → `hafen.map.icons`, re-shaped as an entity (`icons(filter)`,
      `icons(res)`, `:res/:name/:show(v)/:notify(v)/:info`, arity as the verb). Ports `planner`.
      No new capability — this task exists so the other four land in the right home.
      Suite must prove: the thirteen names read nil on `map` and answer identically on `world`
      (one comparison per function, at the player's own position); `hafen.markers`/`hafen.radar`
      are nil; a marker added through `map.markers` is found by `list` and removed by ref; an icon
      category's `show`/`notify` round-trip and are **put back** (it is the user's configuration).

- [x] 037.2 — **Segments and grids.** `map.segment()` (the player's) / `map.segment(id)` /
      `map.segments()`; `seg:id/:grid(sc)/:grids(area)/:markers(filter)/:info`; `grid:id/:pos/:sc/
      :tile(c)/:height(c)/:mtime/:segment/:info`. The anchor bridge both ways, and `marker:anchor()`
      (sync in the current segment, async elsewhere — plan.md).
      Suite must prove: a segment id is an exact decimal string and equals the `seg` a marker in it
      publishes; **the recorded grid and the live terrain name the same tile** for the tile the
      player stands on (the cross-check no other task can make); a grid not yet loaded answers nil
      and the next call answers — without ever throwing or blocking; `marker:anchor()` round-trips
      to the tile the marker reports. `[manual]`: relog, the same stored anchor still resolves.

- [x] 037.3 — **Overlays: claims, village claims, provinces.** `grid:overlay(tag)` → which tiles the
      mask covers, nil for a tag the grid does not carry; and the client's own display toggles read
      and write through `hafen.map`, **owned and released** like a hidden window (the ref-counted
      `MapView.enol`/`disol`, plan.md).
      Suite must prove: a mask for a tag the grid carries, nil for one it does not; the live toggle
      round-trips (read false → set true → read true → release → read false) and a **teardown
      releases it exactly once** — the assertion the ref count makes necessary; the two vocabularies
      (`prov` live vs `realm` recorded) each reach their own side. `[manual]`: the claim overlay
      appears in the world and the provinces on the map, then both go back as they were.
      <!-- extra context: `src/haven/MapView.java` (enol/disol/visol, oltags) -->

- [ ] 037.4 — **The imagery.** `grid:image(lvl)` and `grid:overlayImage(tag)` — a drawable handle,
      rendered on `Defer`, bounded cache, disposed on teardown.
      Suite must prove: the first call answers nil and a later one answers (never blocks, never
      throws); the handle's size is what the level implies; drawing it costs **0 draw callbacks** of
      ours in `profiling():addons()`; a disposed handle is inert rather than an error; the cache
      hands back the same handle for the same (grid, level). `[manual]`: drawn in an addon window it
      is the minimap for that spot, and the zoomed level is the same ground at a coarser scale.

- [ ] 037.5 — **The close.** `design/23-map-database.md`, `specs/codebase/mapfile.md`, the docs
      sweep (`map.md` rewritten, `world.md` grown, `markers.md`/`radar.md` deleted to one obituary
      line each, the two README tables, `conventions.md#coordinates` carrying the anchor rule and
      **why a segment id is never stored or sent**), the link/anchor checker over `docs/addons/`,
      and the example addon: a live minimap panel built from the DB alone — the player's segment,
      the grids around, its markers on top.
      Suite must prove: the example's own cost reads 0 draw and widget callbacks of ours while the
      panel is up; the docs' claims about the anchor hold as assertions, not prose. `[manual]`: the
      panel tracks the player and looks like the corner minimap.
