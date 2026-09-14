# Virtual 3D Models (glTF)

Load and render custom 3D glTF/GLB models shipped with your addon.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  -- Load glTF model from addon assets
  local custom_model_asset = hafen.asset():load("assets/models/statue.glb")

  local world_object = hafen.virtual():object():add(custom_model_asset, player_position)
  world_object:scale(1.2)
end
```

---

## Methods on `VirtualObject`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:position(position)` | `Position` | `self` | Sets anchor location in the world. |
| `:rotate(radians)` | `number` | `self` | Sets model yaw rotation. |
| `:scale(factor)` | `number` | `self` | Sets visual scale factor. |
| `:tint(color_table)` | `{r, g, b, [a]}` | `self` | Tints the 3D model surface. |
| `:remove()` | None | None | Removes this model from the scene. |
