# Virtual Prop Ghosts

Spawn translucent client-side copies of native game 3D props (buildings, trees, boulders) in the world.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  local target_position = player_position:offset(5, 0)

  -- Spawn a preview ghost of a log cabin
  local cabin_ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", target_position)
  cabin_ghost:tint({ 80, 180, 255, 120 })
    :rotate(1.57) -- radians
end
```

---

## Methods on `VirtualGhost`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:position(new_position)`| `Position` | `self` | Repositions the ghost in the world. |
| `:rotate(angle_radians)` | `number` | `self` | Rotates the ghost heading. |
| `:scale(factor)` | `number` | `self` | Adjusts 3D scale multiplier. |
| `:tint(color_table)` | `{r, g, b, [a]}` | `self` | Applies color wash tint. |
| `:remove()` | None | None | Despawns and removes this ghost. |
