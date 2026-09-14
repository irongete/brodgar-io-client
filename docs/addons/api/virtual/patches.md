# Virtual Ground Patches

Render flat, colored 2D polygon overlays that conform seamlessly to the 3D terrain surface.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  -- Define polygon ring of world positions (e.g. 5x5 tile square)
  local polygon_ring = {
    player_position:offset(-2.5, -2.5),
    player_position:offset(2.5, -2.5),
    player_position:offset(2.5, 2.5),
    player_position:offset(-2.5, 2.5)
  }

  local ground_patch = hafen.virtual():patch():add(polygon_ring, player_position)
  ground_patch:tint({ 255, 50, 50, 90 }) -- Translucent red highlight
end
```

---

## Methods on `VirtualPatch`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:tint(color_table)` | `{r, g, b, [a]}` | `self` | Sets fill color of the ground patch. |
| `:position(position)` | `Position` | `self` | Moves the anchor point of the patch. |
| `:remove()` | None | None | Removes the ground patch. |
