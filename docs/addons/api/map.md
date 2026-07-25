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

```lua
local t = hafen.map.tile(p.x, p.y)
if t then hafen.log("standing on " .. (t.name or t.id)) end

local gp = hafen.map.gridPos()          -- player's shareable position
-- gp.gridId is a stable 64-bit id (a string); gp.x, gp.y are the 0..1099 within-grid offset
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
