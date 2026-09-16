# hafen.ui: Native Widget Manipulation

Adjust the placement, visibility, draggable handles, and persistent window coordinates of native game windows.

## Quick Example

```lua
local session = hafen.session():current()
local inventory_window = session and session:ui():match("window[title=Inventory]")

if inventory_window then
  -- Move inventory window to design coordinates (100, 200)
  inventory_window:position(100, 200)

  -- Temporarily hide window
  inventory_window:visible(false)
end
```

---

## Native Placement & Visibility Methods

All layout and visibility methods on widgets are **unprotected**:

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:position(x, y)` | `number, number` | `self` | Relocates widget to pixel coordinate `(x, y)` relative to its parent. |
| `:position()` | None | `{x, y}` | Reads current local position. |
| `:size(w, h)` | `number, number` | `self` | Overrides the widget dimensions. |
| `:size()` | None | `{w, h}` | Reads current dimensions in design pixels. |
| `:visible(is_visible)` | `boolean` | `self` | Toggles whether the widget is drawn on screen. |
| `:visible()` | None | `boolean` | Returns current visibility state. |
| `:remember(name)` | `string` | `self` | Saves under `name` where the widget stands and, on a window of yours that is not packed or on a widget whose size you set, its content box; puts both back at once and on every later session of the same character. `nil` forgets the record. |
| `:resizable(handle)` | `Widget \| nil` | `self` | The widget of yours the user drags to resize this one; `nil` drops it. On a window of your own, `true` switches on the client's corner grip instead. |
| `:draggable(handle)` | `Widget \| nil` | `self` | The widget of yours the user drags to move this one; `nil` drops it. |
