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
| `hafen.map.worldToTile(x, y)` | `{x, y}` | world → tile coord (floors) |
| `hafen.map.tileToWorld(tx, ty)` | `{x, y}` | tile coord → world (its upper-left corner) |
| `hafen.map.tileToGrid(tx, ty)` | `{x, y}` | tile coord → grid coord |

```lua
local t = hafen.map.tile(p.x, p.y)
if t then hafen.log("standing on " .. (t.name or t.id)) end

local gp = hafen.map.gridPos()          -- player's shareable position
-- gp.gridId is a stable 64-bit id (a string); gp.x, gp.y are the 0..1099 within-grid offset
```

> **Grid ids are strings.** A grid id is a 64-bit number and Lua numbers are doubles, so it is
> returned as an exact decimal **string** — the only value safe to store and compare across sessions.
>
> **There is no global position.** Raw world coordinates (`hafen.gob.pos`) are session-local — they
> reset each login and aren't comparable across players. Use `hafen.map.gridPos()` for any position you
> save or share. See [conventions](conventions.md#coordinates).
