# In-World Virtual Widgets

Render floating interactive or display UI widgets projected into 3D world coordinates.

## Quick Example

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  -- Create a floating mini health bar widget
  local health_gauge = hafen.ui():gauge():size(80, 10):value(0.75)

  -- Project widget directly into 3D space above the player
  local world_widget = hafen.virtual():widget():add(health_gauge, player_gob)
  world_widget:offset(0, 18)
end
```

---

## Methods on `VirtualWidget`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:offset(dx, dz)` | `number, number` | `self` | Offsets widget from its anchor point in world units. |
| `:scale(factor)` | `number` | `self` | Scales the projected widget size. |
| `:remove()` | None | None | Removes this widget projection from the 3D scene. |

---

## Protected Actions

Simulating player clicks on native widgets standing in the 3D world requires the `virtual.click` permission:

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `hafen.virtual():click(widget, [button])` | `Widget, [number]` | `virtual.click` | Simulates clicking an in-world interactive widget. |
