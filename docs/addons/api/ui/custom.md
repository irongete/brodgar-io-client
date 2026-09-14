# Custom Windows & Canvas Surfaces

Create draggable windows, borderless panels, and full-screen HUD overlays.

## Quick Example

```lua
-- Create a draggable window
local custom_window = hafen.ui():window()
  :title("Resource Radar")
  :size(220, 80)
  :position(100, 100)

-- Paint custom graphics on Draw
custom_window:on("Draw", function(draw_event)
  local graphics = draw_event:graphics()
  local width = draw_event:width()
  local height = draw_event:height()

  graphics:color(30, 30, 30, 220)
  graphics:frect(0, 0, width, height)

  graphics:color(255, 220, 120)
  graphics:text("Status: Active", 10, 10)
end)

custom_window:on("Close", function()
  hafen.log():write("Custom window was dismissed.")
end)
```

---

## Builder Methods on `hafen.ui()`

| Method | Returns | Description |
|---|---|---|
| `hafen.ui():window()` | `Widget` | Creates a standard framed, titled, draggable window. |
| `hafen.ui():widget()` | `Widget` | Creates a borderless, chromeless canvas widget. |
| `hafen.ui():overlay()`| `Widget` | Creates a full-screen, click-through HUD overlay plane. |

---

## Window Configuration Setters

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:title(window_title)`| `string` | `self` | Sets the window title bar caption. |
| `:size(w, h)` | `number, number` | `self` | Sets width and height in design pixels. |
| `:position(x, y)` | `number, number` | `self` | Sets top-left coordinates on screen. |
| `:pack()` | None | `self` | Auto-sizes the window to wrap tightly around child contents. |
| `:visible(b)` | `boolean` | `self` | Toggles window visibility. |
| `:destroy()` | None | None | Closes and permanently removes the window. |
