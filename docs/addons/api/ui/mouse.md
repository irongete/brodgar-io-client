# hafen.ui: Mouse Pointer

Query mouse pointer coordinates, button states, and hover hit-testing.

## Quick Example

```lua
local mouse_pointer = hafen.ui():mouse()

local screen_x = mouse_pointer:x()
local screen_y = mouse_pointer:y()
hafen.log():write(string.format("Cursor at (%d, %d)", screen_x, screen_y))

-- Deepest widget currently under the cursor
local hovered_widget = hafen.ui():hit(screen_x, screen_y)
if hovered_widget then
  hafen.log():write("Hovering over widget type: " .. hovered_widget:type())
end
```

---

## Methods on `Mouse` (`hafen.ui():mouse()`)

| Method | Returns | Description |
|---|---|---|
| `:x()` | `number` | Pointer X coordinate in design pixels. |
| `:y()` | `number` | Pointer Y coordinate in design pixels. |
| `:position()` | `{x, y}` | Pointer coordinates as a point table. |
| `:button(btn_index)` | `boolean` | `true` if mouse button `btn_index` (`1` left, `2` right, `3` middle) is currently pressed down. |

---

## Hit-Testing Methods on `hafen.ui()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.ui():hit(x, y)` | `number, number` | `Widget \| nil` | The deepest visible widget located at screen coordinates `(x, y)`. |
| `hafen.ui():tipAt(x, y)`| `number, number` | `Widget \| nil` | The widget whose tooltip would be displayed at `(x, y)`. |
