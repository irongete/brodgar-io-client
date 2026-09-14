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
| `:remember(key)` | `string` | `self` | Automatically remembers and restores the user's dragged position across game sessions under `key`. |
