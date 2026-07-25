# A1 — `hafen.markers` (map markers, gap subsystem A1)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **64/64 headless logic checks**
> (world↔segment coordinate round-trip incl. negative coords + a non-zero session origin [the floor-for-negatives
> gotcha]; the facade-safe marker-ref map assign/resolve/session-reset/stale; `luaColor`/`clampByte`) + LuaJ parse
> of `hello/main.lua` under the sandbox. **Zero `haven` core edit** — every backing is public. **In-game DoD
> pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) ("Gap subsystems" → `hafen.markers`),
> [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md) **A1** (the #1 addon category — auto-mapping,
> radar, custom markers) + **C4** (no global position: anchor on grid id / segment coords) + **B3** (the map is a
> client-side subsystem, not a reskinnable server widget). Positioning memory: `hafen-positioning`.

The first **gap subsystem** (post-Phase-3, build order [D-026](../../specs/addons/decisions.md)) and the audit's
**biggest gap**: read/add/remove the client's **map markers**. Markers live in the client-side on-disk map DB
([`MapFile`](../../src/haven/MapFile.java)), owned by the map window ([`MapWnd`](../../src/haven/MapWnd.java)) and
the corner minimap ([`MiniMap`](../../src/haven/MiniMap.java)) — the **same `MapFile` instance**.

## The API — `hafen.markers`

```lua
-- READ ---------------------------------------------------------------------------------------------
local all   = hafen.markers.list()              -- array of marker snapshots (see the shape below)
local pins  = hafen.markers.list("Bram")        -- string filter: name substring
local mine  = hafen.markers.list(function(m) return m.type == "player" end)   -- function filter
local near  = hafen.markers.nearest()           -- the nearest marker (by world distance from you), or nil
local nearP = hafen.markers.nearest("player")   -- nearest matching the filter (string or function)

-- WRITE (persists to the shared on-disk map DB) ---------------------------------------------------
local ref = hafen.markers.add("My spot", x, y)  -- a PLAYER marker at a WORLD position → a ref (or nil)
local ref = hafen.markers.add("My spot", x, y, { color = {r=80,g=220,b=90}, onmap = true })
hafen.markers.remove(ref)                        -- remove it by the ref add()/list() handed out → boolean

-- EVENT --------------------------------------------------------------------------------------------
hafen.events.on("MarkersChanged", function(ev) print(ev.count) end)   -- fires when the marker set changes
```

### The marker snapshot

| Field | Type | Notes |
|---|---|---|
| `id` | number | a **session-local ref** (pass to `remove`); facade-safe — no Java object crosses to Lua ([P1](../../specs/addons/01-architecture.md)) |
| `name` | string | the marker's name (`nm`) |
| `type` | `"player"` \| `"system"` | **player** = a user pin (`PMarker`); **system** = a server/quest pin (`SMarker`) |
| `seg` | string | the **segment id** (64-bit → decimal string, like grid ids — a Lua double would lose precision) |
| `tc` | `{x,y}` | the **segment tile coord** — the **persistent anchor** (survives a relog) |
| `color` | `{r,g,b,a}` | player markers only |
| `onmap` | boolean | player markers only — whether its label is drawn on the main map |
| `icon` | string | system markers only — the icon resource name |
| `x`,`y` | number | **session-local WORLD** coord (tile centre) — **present only when the marker is in your current segment** |
| `dist` | number | world distance from the player — present only with `x,y` and a live player |

> **`seg`+`tc` are the persistent identity; `x,y`/`dist` are the session convenience.** A marker in a *different*
> explored area (segment) than you has **no** `x,y`/`dist` this session (there is no valid world coord for it) — but
> it still lists with its `seg`+`tc`. This is the [C4](../../specs/addons/coverage-gaps.md) positioning reality:
> world coords are login-relative (session-local); the durable anchor is the segment tile coord. To get a
> cross-player anchor for a marker you can see, call `hafen.map.gridPos(m.x, m.y)`.

### `add(name, x, y [, opts])`

- **`x,y` are WORLD units** (matching `hafen.gob.pos`/`hafen.map.*`) — the one canonical coordinate space. They are
  converted to the persistent segment anchor **at add time** (world → `sessloc.tc + floor(world/tilesz)`), so the
  marker survives a relog even though the world coord it was placed at will not.
- Creates a **player marker** (`PMarker`). `opts.color` = `{r,g,b[,a]}` (default a gold pin); `opts.onmap` = draw
  its label on the main map (default `false`). *(There is no `icon` option — an icon belongs to a `system` marker,
  which is bound to a real game object the server owns; addons create player pins.)*
- Returns a **ref** (number) for `remove`, or `nil` if the map/session location has not streamed in yet (see below).

## Coordinates — the world↔segment bridge

Markers store a **segment id + segment tile coord** (`Marker.seg`/`tc`), *not* world units. The bridge between the
two is the **session location** — the corner minimap's live `sessloc` (`MiniMap.Location` = a `Segment` + the
**segment-tile-coord of session tile (0,0)**). This mirrors the client's own marker placement
(`MapWnd.MarkButton.FindMark.hit`):

```
world → segment :  segTc = sessloc.tc + floor(worldCoord / tilesz)          -- add()
segment → world :  world = (marker.tc - sessloc.tc) * tilesz + tilesz/2     -- snapshot x,y (tile centre)
```

Both `floor` (not truncate), so negative login-relative coords convert correctly (the same gotcha as `hafen.map`,
verified headless). `tilesz = 11` world units. The segment→world direction only applies when the marker shares the
player's segment (`marker.seg == sessloc.seg.id`).

## Threading

`MapFile.markers` is mutated on **loader threads** — the server pushes `SMarker`s via `MapWnd.markobj`, and a
**segment merge** re-keys markers (mutates `seg`/`tc` in place) — all under the file's `ReentrantReadWriteLock`. So
reads **copy the list under `file.lock.readLock()`**, then build snapshots (and run Lua filters) **outside** the
lock — the same discipline as the `OCache` gob reads (a filter can call back into Lua, and could even call
`markers.add`, so it must not run under the lock). `add`/`remove` call `MapFile.add`/`remove`, which take the write
lock themselves. All facade calls are on the UI thread except the `:lua` REPL (stdin thread) — so the small
marker-ref map is guarded (`synchronized`).

## `MarkersChanged` — poll-driven

A marker add/remove/merge is **not** a `uimsg`, so — like buffs/study/action-bar — it is detected by **polling**:
`pollMarkers()` (in `tick`, after `pollModels`) watches `MapFile.markerseq` (a `volatile int` the DB bumps on every
change) and fires **`MarkersChanged{count}`** globally when it moves. It is **primed** (not fired) the first time the
DB is seen — the *initial* set is read via `markers.list()` (the DB is fully loaded from disk before the HUD, so
there is no streaming "initial" the way vitals have) — and thereafter fires on genuine changes (a server marker
arriving, you/an addon adding or removing one, a segment merge). Markers loaded from disk do **not** bump
`markerseq` (they are added directly), so they are captured by `list()`, not by an event.

## Streaming — read on a timer, not synchronously at `OnEnterWorld`

Like the rest of the HUD/map state, the map DB **and the session location** (`sessloc`) stream in a **beat after
enter-world** (`sessloc` needs the grid-info the map builds as you load in). So `markers.list()`/`nearest()` may be
empty and `markers.add()` may return `nil` *right at* the `OnEnterWorld` handler — read/add on a short timer or off
`MarkersChanged`, exactly like `hafen.map`/`hafen.char`/vitals. (`hello` reads at `now` + `+3s`.)

## Core edits

**Zero `haven` edits.** Every backing is public: `GameUI.mapfile` (`MapWnd`) / `GameUI.mmap` (`MiniMap`),
`MapWnd.file` / `MiniMap.file` (`MapFile`), `MiniMap.sessloc` (`Location`), `MapFile.markers` / `markerseq` / `lock`
/ `add` / `remove`, `Marker.seg` / `tc` / `nm`, `PMarker.color` / `onmap`, `SMarker.res.name`, `Location.seg` / `tc`,
`Segment.id`. All new code is in `AddonManager.java`:

- **Facade** `hafen.markers` (`list`/`nearest`/`add`/`remove`).
- **Helpers** `mapfile()` (locate the DB via `GameUI.mapfile`→`mmap` fallback), `sessloc()`, `markerSnapshots()`
  (copy-under-lock), `markerSnapshot()`, `addMarker()`, `removeMarker()`, `luaColor()`/`clampByte()`, the
  facade-safe marker-ref map (`markerId`/`markerByRef`), and `pollMarkers()`.
- **Wiring** `pollMarkers()` in `tick`; the ref map + `markersPrimed` reset in `init()` (per-session).

## The `hello` harness (v0.22.0)

- **`readMarkers("now"/"+3s")`** in the enter-world read burst — logs the marker count, the first marker (name +
  `tc`), and the nearest (name + `dist`).
- **`MarkersChanged`** subscription — logs the count (first few).
- **Ctrl+Shift+M** (a fourth **"Hello"** keybind row, remappable) — a **toggle**: drops a green *"Hello marker"* at
  your position (`hafen.markers.add`), or removes it if already placed (`hafen.markers.remove`). Watch it appear on
  the map (**M**) and the corner minimap.
- **Cleanup:** `OnDisable` removes the demo marker, so the regression harness never leaves *"Hello marker"* pins on
  your persistent map. *(A normal login adds nothing — the marker is only placed when you press the hotkey.)*

## How to test in-game (DoD)

1. `ant hafen-client` (done) → `ant run`. `hello` loads at **v0.22.0**; a **"Hello"** keybind section now shows a
   fourth row, *marker* = Ctrl+Shift+M.
2. In-world, after a few seconds the `[+3s] markers=N …` line logs your current markers (a well-explored character
   usually has several **system** pins; a fresh spot may show `0`). `MarkersChanged` may log once or twice as server
   markers stream in.
3. Press **Ctrl+Shift+M** → `A1: … dropped 'Hello marker' at X,Y (ref …)`. Open the map (**M**) / glance at the
   corner minimap → a green *"Hello marker"* pin is at your position. `MarkersChanged` fires. **add ✓**
4. `:lua hafen.markers.nearest().name` → `"Hello marker"` (you are on top of it, dist ~0). `:lua
   #hafen.markers.list()` grew by one. **list/nearest ✓**
5. Press **Ctrl+Shift+M** again → the pin disappears; `MarkersChanged` fires. **remove ✓**
6. Place it again, then `:reload` (or `:addons disable hello` + `:reload`) → `OnDisable` removes the pin (teardown
   cleanup) → the map has no *"Hello marker"* left. **teardown cleanup ✓**
7. Regression: `hello`'s window, overlays, hooks, all the read logs, Ctrl+H/Ctrl+B still work.

> To see a marker **persist across a relog** (the whole point of the segment anchor), add one without the auto-cleanup
> — e.g. `:lua hafen.markers.add("keep me", hafen.gob.pos("player").x, hafen.gob.pos("player").y)` — then relog: it is
> still there (it was written to the on-disk DB). Remove it from the map's own marker list, or with
> `hafen.markers.remove`, when done.

## Deferred

- **`add` an SMarker / icon markers** — system markers are bound to a server object id (`oid`); addons create player
  pins (`PMarker`). A meaningful icon-marker API would need the gated object/action tier.
- **A `hafen.map.grid`/`gridPos` field baked into each snapshot** — derivable from `x,y` via `hafen.map.gridPos`
  when the marker is in a loaded grid; not duplicated in the snapshot (D-013, one canonical way).
- **Editing a marker** (rename / recolor / move in place) — `MapFile.update(marker)` exists; a `markers.update` verb
  can follow if wanted.
- **A map/minimap overhaul** — the map window is a **client-side** subsystem ([B3](../../specs/addons/coverage-gaps.md)),
  not a reskinnable server widget; a custom map view is its own (large) subsystem, out of scope here.
- **`nearest` across segments** — currently ranks by world distance, so cross-segment markers (no `dist`) are
  skipped; a segment-graph distance is out of scope.
