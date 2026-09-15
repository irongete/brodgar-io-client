# Position: World Coordinates

Represents an immutable 2D coordinate point with a persistent grid anchor in the game world.

## Quick Example

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  local current_position = player_gob:position()
  hafen.log():write(string.format("Player at X=%.2f, Y=%.2f", 
    current_position:x(), current_position:y()
  ))

  -- Create an offset position 10 tiles east
  local target_position = current_position:offset(110, 0)
  local distance_between = current_position:distance(target_position)
  hafen.log():write("Offset distance: " .. distance_between)
end
```

---

## Methods on `Position`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:x()` | None | `number \| nil` | World X coordinate in the current session. `nil` if grid is not loaded. |
| `:y()` | None | `number \| nil` | World Y coordinate in the current session. `nil` if grid is not loaded. |
| `:distance(other_position)` | `Position` | `number \| nil` | Calculates Euclidean distance to `other_position` in world units. |
| `:offset(dx, dy)` | `number, number` | `Position` | Returns a new `Position` translated by `(dx, dy)` world units. |
| `:tileCoord()` | None | `{x, y} \| nil` | Local tile coordinates `(0..99, 0..99)` inside the anchor grid. |
| `:durable()` | None | `boolean` | `true` if position carries a persistent server grid ID. |
| `:info()` | None | `table` | Plain table snapshot `{ gridId, x, y }`. |
