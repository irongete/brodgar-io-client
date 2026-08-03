# 037-map-database — Spec

## What & why

The API has never touched the **map database**. `hafen.map` reads `MCache` — the live terrain
streamed around the player, `nil` off-stream, gone at logout — while the map the player *explores*
is `MapFile`: segments, grids, zoom levels, per-grid overlay masks and the minimap drawings, on disk
and persistent. One door reaches it today (`hafen.markers`) and it does not say so.

This feature opens `MapFile` and puts the whole area on the axis that was always there:
**`hafen.world` is the LIVE world** (gobs, terrain, the session coordinate spaces, screen→ground,
placement snapping) and **`hafen.map` is the RECORDED map** (segments, grids, zoom levels, images,
overlays, and — as relations rather than namespaces of their own — markers and minimap icons).
`hafen.markers` and `hafen.radar` are hard cuts: a marker lives in the map file (D-066), and the
engine has no "radar", it has `GobIcon.Settings` (D-061). New design doc: `design/23-map-database.md`.

**The anchor rule the feature ships:** a saved or shared position is `{gridId, x, y}` — the grid id
comes from the server (`MCache.Grid.fill`, layer `"m"`), so it is the same number for every player
and no merge ever moves it. A **segment id is client-local bookkeeping**: `MapFile` mints it with
`rnd.nextLong()`, and a merge re-bases the loser's grids and rewrites its markers in place. Segment
+ tile coord is therefore a *view* — read it, never store it, never send it.

## Acceptance criteria

- [ ] All thirteen of today's `hafen.map` functions read **nil** and answer on `hafen.world` with
      identical results; `hafen.markers` and `hafen.radar` read nil.
- [ ] `hafen.map.markers` and `hafen.map.icons` carry the surfaces those two had, icons as an
      entity (`icons(filter)` collection, `icons(res)` one category, `:show(v)`/`:notify(v)` arity
      as the verb), and a category's flag round-trips: set it, read it back, set it back.
- [ ] `hafen.map.segment()` is the segment the player is in and `hafen.map.segments()` every known
      one; a segment id is an exact decimal **string**, and `seg:id()` matches the `seg` field a
      marker in that segment publishes.
- [ ] A recorded grid answers `:id/:pos/:tile(c)/:height(c)/:mtime` and **agrees with the live
      terrain where both answer**: for the tile the player stands on, the grid's tile name equals
      `hafen.world.tile(x, y)`'s. A grid not yet loaded from disk answers nil and loads for the
      next call (never blocks, never throws).
- [ ] An anchor from `hafen.world.gridPos()` resolves through the map DB to that grid's segment and
      segment tile coord, and the same anchor still resolves after a relog (`[manual]`).
- [ ] A marker converts to a grid anchor — `marker:anchor()` — so a marker can be saved or shared;
      it is asynchronous where the grid is not loaded, and answers nil rather than blocking. Its
      round trip lands back on the same tile the marker reports.
- [ ] `grid:overlay(tag)` answers which tiles a `cplot` / `vlg` / `realm` overlay covers, nil for a
      tag the grid does not carry; and the client's three display toggles (personal claims, village
      claims, provinces) read and write through `hafen.map`, each confirmed on screen (`[manual]`).
- [ ] A grid's minimap drawing is a handle an addon draws with `g:image`, at a zoom level and at the
      size that level implies, rendered asynchronously: the first call answers nil, the image lands
      without a frame hitch, and in an addon window it is the minimap for that spot (`[manual]`).
- [ ] The example addon draws a live minimap panel from the map DB — the player's segment, a few
      grids around, its markers on top — from data alone, and its cost reads **0 draw callbacks**
      of ours in `hafen.client:profiling():addons()`.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **`Polity`** (the village/realm authority window) — a widget, not the map; stays on the ROADMAP.
- **Writing the map DB**: no grid/segment creation, no merges, no export/import (`ExportFilter`,
  `ImportFilter`, the two `MapWnd` windows). Markers keep the add/remove they already have.
- **Replacing the map window or the corner minimap** — that is `widget:replace` (032) if ever.
- **The 3D-world overlay geometry** (`MCache.getolcut`): this feature reads the recorded masks and
  drives the client's own toggles, it does not draw in the world.
- **Segment merging as an event**, and keeping a stored segment coord valid across one — the anchor
  rule removes the need: nothing an addon stores is expressed in segment space.
- **Sharing markers between players** — `marker:anchor()` ships the coordinate that makes it
  possible; the transport (`hafen.http`, a file) is the addon's business, not this feature's.

## Context files

- `design/23-map-database.md` — written by this feature's first task (does not exist yet)
- `src/haven/MapFile.java` — the whole subject: `segments`, `gridinfo`, `knownsegs`, `Segment`,
  `DataGrid`/`Grid`/`ZoomGrid`, `Overlay`, `merge`
- `src/haven/MiniMap.java` — `Location`/`SessionLocator` (the live↔recorded bridge), `DisplayGrid`
  (`img()`, `olimg(tag)`) — how the client itself gets a drawing out of a grid
- `src/haven/MapWnd.java` — `toggleol("realm")`, the marker types; `src/haven/GameUI.java` —
  `toggleol("cplot"/"vlg")`, `iconconf`
- `src/haven/MCache.java` — `ResOverlay.tags()`, and the live half `hafen.world` keeps
- `src/io/brodgar/addon/WorldApi.java` — where all four namespaces live today
- `docs/addons/api/map.md`, `world.md`, `markers.md`, `radar.md` — the surfaces being cut and moved
- `028-asset-loader/` — the image-handle shape (`LuaImage`, interning, `:dispose`) a grid drawing
  reuses; `030-ui-selectors/` — D-066/D-056 as applied to a relation and a callable namespace
