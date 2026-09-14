# session:world():placing(): Cursor Placement Ghost

Inspect blueprint construction ghosts currently active on the player's cursor before committing placement.

## Quick Example

```lua
local session = hafen.session():current()
local active_placement = session and session:world():placing()

if active_placement and active_placement:exists() then
  local resource_name = active_placement:name() or "Resolving..."
  local current_position = active_placement:position()
  local facing_angle = active_placement:facing() or 0

  hafen.log():write(string.format(
    "Placing %s at (%.1f, %.1f), heading: %.2f rad",
    resource_name, current_position:x(), current_position:y(), facing_angle
  ))
end
```

---

## Methods on `Placing`

| Method | Returns | Description |
|---|---|---|
| `:exists()` | `boolean` | `true` if a placement ghost is currently active on the cursor. |
| `:name()` | `string \| nil` | Resource path of the building or object being placed. |
| `:position()` | `Position \| nil` | Current world position of the ghost preview. |
| `:facing()` | `number \| nil` | Rotation angle in radians (adjusted via mouse scroll wheel). |
| `:hitbox()` | `Position[][] \| nil` | Array of bounding polygon rings outlining the object's footprint. |
| `:info()` | `table \| nil` | Snapshot table `{ name, position, facing, exists }`. |

> To actually commit or cancel placement, use `session:world():place(...)` (requires `world.place` permission).
