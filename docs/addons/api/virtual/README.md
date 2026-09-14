# hafen.virtual: In-World Visual Entities

Render custom 3D visual objects, ghosts, ground patches, sprites, and UI widgets directly anchored inside the 3D game world.

All virtual entities are client-side only, require no permissions, and are automatically destroyed when your addon is reloaded or disabled.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  -- Spawn a translucent ghost of a game building prop
  local cabin_ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", player_position)
  cabin_ghost:tint({ 60, 140, 255, 100 })

  -- Spawn a custom 2D sprite image anchored in the 3D world
  local custom_icon = hafen.asset():load("assets/icons/star.png")
  local world_sprite = hafen.virtual():sprite():add(custom_icon, player_position)
end
```

---

## Virtual Entity Collections

| Collection | Reference Page | Description |
|---|---|---|
| `hafen.virtual():ghost()` | **[ghosts.md](ghosts.md)** | Translucent previews of existing game resource 3D props. |
| `hafen.virtual():sprite()`| **[sprites.md](sprites.md)** | Custom 2D image billboards facing the camera. |
| `hafen.virtual():object()`| **[models.md](models.md)** | Custom 3D glTF models loaded from addon assets. |
| `hafen.virtual():patch()` | **[patches.md](patches.md)**, **[pieces.md](pieces.md)** | Flat colored polygon patches conforming to the terrain. |
| `hafen.virtual():widget()`| **[widgets.md](widgets.md)** | Floating interactive UI windows rendered in the 3D world. |

---

## Common Methods on Virtual Entities

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:position(position)` | `Position` | `self` | Moves entity anchor point in world coordinates. |
| `:scale(factor)` | `number` | `self` | Scales 3D visual size. |
| `:tint(color_table)` | `{r, g, b, [a]}` | `self` | Applies color tinting to the virtual entity. |
| `:remove()` | None | None | Removes this virtual entity from the world. |
