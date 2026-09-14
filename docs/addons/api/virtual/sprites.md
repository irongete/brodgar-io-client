# Virtual 2D Sprites

Render 2D billboard images anchored in the 3D world that face the camera automatically.

## Quick Example

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  -- Load custom icon texture
  local quest_icon = hafen.asset():load("assets/icons/quest.png")

  -- Anchor sprite above the player's head (follows entity)
  local world_sprite = hafen.virtual():sprite():add(quest_icon, player_gob)
  world_sprite:scale(0.8)
    :offset(0, 20) -- 20 units above entity base
end
```

---

## Methods on `VirtualSprite`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:scale(factor)` | `number` | `self` | Scales sprite visual dimensions. |
| `:offset(dx, dz)` | `number, number` | `self` | Offsets sprite from anchor point in world units. |
| `:tint(color_table)` | `{r, g, b, [a]}` | `self` | Applies color tint wash. |
| `:remove()` | None | None | Removes sprite from the world. |
