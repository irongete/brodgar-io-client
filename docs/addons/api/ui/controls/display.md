# Display UI Controls

Non-interactive visual controls for rendering text labels, icons, images, and progress bars.

## Controls Reference

### Labels (`hafen.ui():label()`)
Displays static or dynamic text strings.

```lua
local info_label = hafen.ui():label()
  :text("Character Coordinates: (120, -45)")
  :parent(container_widget)

-- Update text dynamically
info_label:text("Updated: (125, -40)")
```

---

### Images (`hafen.ui():image()`)
Renders an engine resource icon or custom addon texture.

```lua
-- Display game resource icon
local resource_icon = hafen.ui():image()
  :source("gfx/hud/chr/farming")
  :size(24, 24)
  :parent(container_widget)

-- Display custom asset loaded via hafen.asset
local custom_texture = hafen.asset():load("assets/icons/star.png")
local custom_icon = hafen.ui():image()
  :source(custom_texture)
  :size(32, 32)
  :parent(container_widget)
```

---

### Progress Bars (`hafen.ui():progress()`)
Horizontal progress meter bar.

```lua
local progress_bar = hafen.ui():progress()
  :size(160, 16)
  :value(0.65) -- 65% progress (0.0..1.0)
  :parent(container_widget)
```
