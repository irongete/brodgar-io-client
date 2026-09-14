# hafen.ui: Screen HUD Overlays

Create full-screen, click-through 2D rendering overlay planes over the 3D game world and HUD.

## Quick Example

```lua
-- Create a persistent full-screen HUD overlay
local hud_overlay = hafen.ui():overlay()

hud_overlay:on("Draw", function(draw_event)
  local graphics = draw_event:graphics()
  local screen_width = draw_event:width()
  local screen_height = draw_event:height()

  -- Draw a subtle compass indicator at the top center of the screen
  graphics:color(255, 255, 255, 180)
  graphics:text("N", math.floor(screen_width / 2), 20)
end)
```

---

## Behavior

* **Click-Through**: Overlay planes do not block mouse clicks or drag events; all interactions pass through to the game world or windows beneath.
* **Viewport Resizing**: The overlay automatically resizes to match the client window viewport on every frame.
* **Cleanup**: Overlays are destroyed automatically when your addon is reloaded or disabled.
