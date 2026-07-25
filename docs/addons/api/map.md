# hafen.map — terrain & coordinates

Read the terrain and convert between coordinate spaces. Positional arguments are **world units**
(the same space [`hafen.gob.pos`](gob.md) returns). Terrain reads return `nil` when the map for that
spot hasn't loaded yet.

| Function | Returns | Description |
|---|---|---|
| `hafen.map.tile(x, y)` | [`Tile`](types.md#tile) \| nil | tileset id + resource name at a world point |
| `hafen.map.height(x, y)` | number \| nil | terrain height at a world point |
| `hafen.map.grid(x, y)` | `{id, gc}` \| nil | the map grid at a point: `id` = stable global grid id (string), `gc` = session-local grid coord `{x,y}` |
| `hafen.map.gridPos([x, y])` | `{gridId, x, y}` \| nil | the **shareable/persistent** position; no args = the player |
| `hafen.map.fromGridPos(anchor)` | `{x, y}` \| nil | the **inverse** of `gridPos`: a saved anchor → a world coord in *this* session; `nil` if that grid isn't loaded |
| `hafen.map.worldToTile(x, y)` | `{x, y}` | world → tile coord (floors) |
| `hafen.map.tileToWorld(tx, ty)` | `{x, y}` | tile coord → world (its upper-left corner) |
| `hafen.map.tileToGrid(tx, ty)` | `{x, y}` | tile coord → grid coord |
| `hafen.map.screenToWorld(sx, sy, fn)` | — (calls `fn`) | raycast the ground under a screen pixel; **async** (see below) |
| `hafen.map.snapPlace(x, y [, fine])` | `{x, y}` | snap a world coord to the client's placement grid |
| `hafen.map.placeGrid()` | number | the current `:placegrid` setting (sub-tile divisions; 0 = free) |
| `hafen.map.snapAngle(a [, fine])` | number | snap a facing (radians) to the client's placement angle: 45° default, `:placeangle` grid with `fine` |
| `hafen.map.placeAngle()` | number | the current `:placeangle` setting (the fine rotation divisions) |

```lua
local t = hafen.map.tile(p.x, p.y)
if t then hafen.log("standing on " .. (t.name or t.id)) end

local gp = hafen.map.gridPos()          -- player's shareable position
-- gp.gridId is a stable 64-bit id (a string); gp.x, gp.y are the 0..1099 within-grid offset
```

### Screen ↔ world & placement snapping (V5)

The inverse of [`hafen.player.worldToScreen`](player.md) plus the client's own placement snapper — the primitives a
[ghost](ghost.md) gizmo (or any drag-on-the-ground tool) is built from.

**`screenToWorld(sx, sy, fn)` is asynchronous.** It reads the *true* terrain point from the GPU (the same pass the
client uses to place a building), so the answer can't be returned inline — it arrives a frame later through `fn`:

```lua
hafen.map.screenToWorld(sx, sy, function(w)
  if w then hafen.log(("ground under cursor: %.1f, %.1f"):format(w.x, w.y)) end
  -- w is nil if the pixel hit no terrain (sky / off-map)
end)
```

`(sx, sy)` are game-window pixels — the same space `worldToScreen` returns (for the standard fullscreen map view,
screen pixels). During a drag, feed it the cursor coords from [`hafen.hook.grab`](hooks.md#hafenhookgrab) and coalesce
(issue the next raycast only after the previous `fn` fired) so at most one is in flight per frame.

**`snapPlace(x, y [, fine])`** snaps a world coord *exactly* like placing a building, honouring the live `:placegrid`
setting: no `fine` → the tile centre; `fine = true` → the sub-tile placegrid (`placeGrid()` divisions, or free when
that is 0). This is the same snapper the engine's own placement uses, so a ghost dropped through it lands where a real
building would.

```lua
local s = hafen.map.snapPlace(w.x, w.y, mods.shift)   -- SHIFT = the fine grid, like real placement
ghost:move(s.x, s.y)
```

**`snapAngle(a [, fine])`** (V6) is the rotation counterpart — it snaps a facing angle (radians) to the client's
placement-angle grid: no `fine` → **45°** steps; `fine = true` → the finer `:placeangle` grid (`placeAngle()`
divisions). It honours the live `:placeangle` just as `snapPlace` honours `:placegrid`, so a ghost rotate feels
identical to rotating a real building. The result is normalized to `(-π, π]`.

```lua
local a = hafen.map.snapAngle(math.atan2(w.y - c.y, w.x - c.x), mods.shift)  -- point the ghost at the cursor, snapped
ghost:rotate(a)
```

### Saving a world position across sessions

Raw world coords reset each login, so persist a position as a **grid anchor** (`gridPos`) and re-resolve it with
`fromGridPos` on load. `fromGridPos` takes the exact table `gridPos` returns, so the round-trip needs no reshaping:

```lua
-- save (e.g. into hafen.store): a stable anchor, not raw x,y
hafen.store.spot.anchor = hafen.map.gridPos(wx, wy)   -- {gridId, x, y}

-- load (next session): back to a world coord in THIS session
local a = hafen.store.spot.anchor
local w = a and hafen.map.fromGridPos(a)              -- {x, y}, or nil if that grid hasn't streamed in yet
if w then hafen.ghost.new{ res = "…", x = w.x, y = w.y } end
```

`fromGridPos` returns `nil` until the anchored grid is loaded (it lives near where it was saved), so re-resolve at
`OnEnterWorld` and retry for a few seconds as the map streams in. The [`planner`](../../../addons/planner) example
addon does exactly this for a whole layout of [ghosts](ghost.md).

> **Grid ids are strings.** A grid id is a 64-bit number and Lua numbers are doubles, so it is
> returned as an exact decimal **string** — the only value safe to store and compare across sessions.
>
> **There is no global position.** Raw world coordinates (`hafen.gob.pos`) are session-local — they
> reset each login and aren't comparable across players. Use `hafen.map.gridPos()` for any position you
> save or share. See [conventions](conventions.md#coordinates).
