# Custom UI

Addons can render custom user interfaces in two ways:
1. **Direct 2D Canvas Drawing**: Paint text, shapes, and custom textures on a blank window or HUD overlay.
2. **Native Control Panels**: Compose windows using the client's built-in buttons, labels, checkboxes, and layout columns.

All UI components created by your addon are automatically cleaned up when your addon reloads or unloads.

---

## 1. Creating a Window with Custom 2D Drawing

Create a draggable window and handle its `Draw` event to paint custom 2D elements:

```lua
local window_handle = nil

hafen.event():on("SessionEnteredWorld", function(session)
  window_handle = hafen.ui():window()
    :title("Player Radar")
    :size(200, 60)
    :position(100, 100)

  window_handle:on("Draw", function(draw_event)
    local graphics = draw_event:g()
    local width = draw_event:w()
    local height = draw_event:h()

    -- Draw dark background panel
    graphics:color(20, 20, 20, 200)
    graphics:frect(0, 0, width, height)

    -- Draw border
    graphics:color(100, 100, 100)
    graphics:rect(0, 0, width, height)

    -- Draw status text
    graphics:color(255, 215, 0)
    graphics:text("Radar Active", 10, 10)

    local player_count = session:world():gob():count("gfx/borka/body")
    graphics:color(200, 200, 200)
    graphics:text("Players nearby: " .. player_count, 10, 30)
  end)

  window_handle:on("Close", function()
    hafen.log():write("Radar window closed by user.")
  end)
end)
```

### Canvas Drawing Methods (`graphics`)

| Method | Parameters | Description |
|---|---|---|
| `graphics:color(r, g, b, [a])` | `number, number, number, [number]` | Set drawing color (values `0..255`). |
| `graphics:text(text, x, y)` | `string, number, number` | Render cached text at widget-local coordinates. |
| `graphics:line(x1, y1, x2, y2)` | `number, number, number, number` | Draw a 1-pixel line. |
| `graphics:rect(x, y, w, h)` | `number, number, number, number` | Draw an unfilled rectangle outline. |
| `graphics:frect(x, y, w, h)` | `number, number, number, number` | Draw a filled solid rectangle. |
| `graphics:image(asset_handle, x, y)`| `userdata, number, number` | Render an image loaded via `hafen.asset`. |

---

## 2. Composing Native Controls (Columns & Rows)

To create a window with standard game buttons, checkboxes, and text entries, use layout containers ([`column`](../api/ui/column.md) and [`row`](../api/ui/column.md)):

```lua
local options_window = nil

hafen.event():on("SessionEnteredWorld", function(session)
  options_window = hafen.ui():window()
    :title("Harvest Helper")
    :position(120, 120)

  -- Root vertical column inside the window
  local root_column = hafen.ui():column()
    :gap(6)
    :parent(options_window)
    :position(10, 10)

  -- Header Label
  hafen.ui():label()
    :text("Automated Gathering Settings")
    :parent(root_column)

  -- Horizontal row with an icon and a checkbox
  local check_row = hafen.ui():row()
    :gap(4)
    :parent(root_column)

  local ripe_only_checkbox = hafen.ui():check()
    :text("Only harvest fully ripe crops")
    :parent(check_row)

  ripe_only_checkbox:on("Changed", function(is_checked)
    hafen.log():write("Setting changed: ripe_only = " .. tostring(is_checked))
  end)

  -- Action Button
  local scan_button = hafen.ui():button()
    :text("Scan Field Now")
    :size(140, 24)
    :parent(root_column)

  scan_button:on("Pressed", function()
    local crops = session:world():gob():count("terobjs/plants")
    hafen.log():write("Found crops: " .. crops)
  end)

  -- Automatically size the window to wrap around its contents
  options_window:pack()
end)
```

## 3. Window Visibility and Toggling

```lua
-- Toggle window on keybinding or button click
function toggle_my_window()
  if not options_window then return end
  local is_visible = options_window:visible()
  options_window:visible(not is_visible)
end
```
