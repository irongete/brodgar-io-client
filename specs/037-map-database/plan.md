# 037-map-database — Plan

## Approach

**The split is a move, not a rewrite.** `WorldApi.installMap` already holds the live half; it stays
and grows the name `hafen.world`. The recorded half is new code in `MapApi.java` plus four entity
classes. Every seam is public — `GameUI.mapfile.file` / `.mmap.file`, `MapFile.segments`,
`gridinfo`, `knownsegs`, `Segment.grid(sc)` / `grid(lvl, gc)`, `DataGrid.gettile/getfz/ols/render/
olrender`, `MiniMap.sessloc`, `MapView.visol/enol/disol`, `MapWnd.overlays` — so **zero core edits
are expected**, with one named exception below.

**One load model, chosen once and applied everywhere: kick the load, answer nil.** The map DB lives
on disk and resolves through `Defer`/`Indir`, so every grid read has the property `fromGridPos`
already has. A callback per read (`screenToWorld`'s shape) is rejected: a minimap panel walks a
dozen grids per frame and would become a callback tree. So a read starts the load and answers nil
until it lands — the caller re-reads next tick, exactly as `planner` already retries an anchor.

**Entities intern on the ENGINE'S id, not on Java identity.** Unlike a Buff or a Meter, a `Segment`
lives in a `BackCache(5)` and a `Grid` in a weak `CacheMap` — the same segment comes back as a
different object after eviction. So `LuaSegment` is keyed by segment id and `LuaMapGrid` by grid id
(D-063: the key is what the engine publishes), and a `WeakHashMap` on the Java object would be a
bug that only shows up after the cache turns over.

**You ask the map for an AREA, never for a list.** `Segment.map` (coord → grid id) is private, so a
segment's grids cannot be enumerated. This is not a gap to work around: `MiniMap` never enumerates
either — it walks the grid coords of the area it is drawing. So `seg:grid(sc)` and `seg:grids(area)`
are the whole addressing surface, which is also what a minimap panel actually wants.

**Reads copy under `MapFile.lock`'s read lock and snapshot outside it** (the discipline the marker
code already uses — a merge mutates markers in place on a loader thread under the write lock).
`Segment.grid(Coord)` calls `checklock()` and *requires* the read lock held, so it is always wrapped.
Resolving a resource (a tileset, an overlay id) may throw `Loading` and must never happen inside the
lock.

**Markers move under `map.markers` unchanged** and gain `marker:anchor()`. Its two halves differ and
the cheap one covers the common case: a marker in the **current** segment converts through `sessloc`
arithmetic to world `x, y` and then through `gridPos` — synchronous, no reverse lookup, which is the
rule `gap-subsystems.md` already recorded. A marker in **another** segment has no world coord this
session, so it goes `tc / cmaps` → `seg.grid(sc)` → the loaded grid's id — asynchronous, nil until
the grid arrives.

**Icons drive the client's own model**, as `radar` does today: `GobIcon.Settings` (`GameUI.iconconf`),
`set.show/notify = v` + `conf.dsave()`, re-shaped from two filter-mutators into an entity
(`icons(res)`, `:show(v)`/`:notify(v)`, arity as the verb, D-056).

**Images render on `Defer`, never on the frame.** `DataGrid.render(off)` builds a `BufferedImage`
from the tileset textures; `MiniMap` wraps that in a `TexI` off-thread and so do we, reusing 028's
image handle. Bounded cache keyed by (grid id, level, overlay tag), disposed on teardown — an
undisposed `TexI` per grid is a GL leak by a new door.

## Files to create / modify

- `src/io/brodgar/addon/MapApi.java` — **new**: `hafen.map` (segments, grids, overlays, images) and
  the marker + icon code moved out of `WorldApi`
- `src/io/brodgar/addon/LuaSegment.java`, `LuaMapGrid.java`, `LuaMarker.java`, `LuaIconCat.java` —
  **new**: the entities, interned per addon on the engine's id
- `src/io/brodgar/addon/WorldApi.java` — keeps the live half, renamed into `hafen.world`; the
  markers/radar halves and their poll leave
- `src/io/brodgar/addon/AddonManager.java` — the marker poll and `resetMarkers` follow the code;
  install order for the new namespace
- `src/io/brodgar/addon/LuaImage.java` — gains a non-asset origin (a rendered grid, not a file)
- `docs/addons/api/map.md` — rewritten as the map DB; `world.md` — gains the live half;
  `markers.md`, `radar.md` — **deleted**, each leaving one obituary line (032's pattern);
  `api/README.md`, `docs/addons/README.md`, `conventions.md#coordinates` — the tables and the anchor
- `specs/design/23-map-database.md` — **new**: the standing design for the area's map half
- `specs/codebase/mapfile.md` — **new** (coverage paid by this feature); `specs/codebase-map.md` and
  `specs/codebase/state.md` — one line each pointing at it
- `addons/037-map-database.1` … `.5/` — the five suites; a new example addon for the close
- `addons/planner/` — ported to `hafen.world.gridPos`; `addons/hello/` — edited **only** if the cut
  breaks it (it is frozen; a break is the one licence to touch it)

## Risks & gotchas

- **`MapView.enol`/`disol` are REFERENCE-COUNTED** (`oltags` is a multiset) and the client's own
  checkbox holds a count, as does the server's `flashol`. An unbalanced addon write leaves an
  overlay on forever — so the write is owned and released on teardown/`:reload`, D-069's shape.
- **The two overlay vocabularies are NOT the same.** The live world uses `cplot` / `vlg` / **`prov`**
  (`MapView`), the recorded map uses **`realm`** for provinces (`MapWnd.overlays`). Same feature to a
  player, two tags in the engine; the docs must say so or every reader gets it wrong once.
- **`MiniMap` cannot be classloaded headless** — its static `Resource.loadtex` fields need GL — so
  anything through `sessloc` is an in-game-only check. `new MapFile(null, "")` does no I/O and the
  pure coordinate arithmetic *is* dry-runnable; split it so the pre-check can reach it
  (`learnings/gap-subsystems.md`, `testing-tooling.md`).
- **`Loading` is everywhere on this path** — `Indir.get()`, `olid.get()`, a tileset resource — and it
  is a `RuntimeException` that must never escape into Lua. Guard every read to nil.
- **`markerseq` does not bump for disk-loaded markers**, so the poll stays primed rather than fired
  on the first sighting (existing behaviour, kept).
- **64-bit ids are decimal strings** — segment id and grid id both, never Lua numbers (>2⁵³).
- **The one core edit that may prove necessary**: exposing `Segment.map`'s key set, if the area walk
  turns out to be too slow for a panel. Take it only on measurement, tagged `// addon:`.

## Discarded alternatives

- **A callback per grid read** (`screenToWorld`'s shape) — a minimap panel becomes a callback tree.
- **Interning grids/segments on Java identity** — the caches evict and rebuild; stale after turnover.
- **One `hafen.map` holding live terrain and the DB** — the exact confusion this feature removes.
- **Keeping the name `radar`** — the engine has no radar, it has `GobIcon.Settings` (D-061).
- **`{seg, tc}` as the saved anchor** — client-random segment ids plus the merge re-base (see spec).
- **Exposing `Segment.map` up front** — the area walk is the client's own idiom; earn the edit first.
- **A Lua reimplementation of the map window** — that is `widget:replace` (032), not this feature.
