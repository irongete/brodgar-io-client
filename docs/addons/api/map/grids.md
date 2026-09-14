# hafen.map():grid(): Map Grids

Inspect server world grids, grid boundaries, tile coordinates, and cached map segments.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  local map_grid = hafen.map():grid():at(player_position)
  if map_grid then
    hafen.log():write("Standing inside grid ID: " .. map_grid:id())
  end
end
```

---

## Methods on `hafen.map():grid()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:at(position)` | `Position` | `MapGrid \| nil` | Returns the grid containing the given world position. |
| `:get(grid_id)` | `string` | `MapGrid \| nil` | Finds a grid by its unique server grid ID. |

---

## Methods on `MapGrid`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `string` | Unique alphanumeric grid identifier. |
| `:tile(tile_x, tile_y)`| `GridTile \| nil` | Reads terrain tile properties at local grid coordinates (`0..99`). |
| `:origin()` | `Position` | Top-left world coordinate anchor of this grid. |
