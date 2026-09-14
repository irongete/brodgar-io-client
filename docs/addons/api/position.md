# Position: World Coordinates

Represents an immutable 3D coordinate point in the game world.

## Quick Example

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  local current_position = player_gob:position()
  hafen.log():write(string.format("Player at X=%.2f, Y=%.2f, Z=%.2f", 
    current_position:x(), current_position:y(), current_position:z()
  ))

  -- Create an offset position 10 tiles east
  local target_position = current_position:offset(10, 0)
  local distance_between = current_position:distance(target_position)
  hafen.log():write("Offset distance: " .. distance_between)
end
```

---

## Methods on `Position`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:x()` | None | `number` | X world coordinate. |
| `:y()` | None | `number` | Y world coordinate. |
| `:z()` | None | `number` | Z world coordinate (elevation). |
| `:distance(other_position)` | `Position` | `number` | Calculates euclidean 2D distance to another coordinate. |
| `:offset(dx, dy)` | `number, number` | `Position` | Returns a new `Position` offset by `dx` and `dy` tiles. |
| `:grid()` | None | `{gridId, tileX, tileY}` | Returns the server map grid ID and local tile coordinates. |
| `:info()` | None | `table` | Plain table snapshot `{ x, y, z, gridId }`. |
