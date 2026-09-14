# hafen.ui: User Interface Subsystem

Create custom windows, compose native control layouts, inspect the client's widget tree, and paint custom 2D graphics.

## Quick Example

```lua
-- Create a custom window with layout and button
local custom_window = hafen.ui():window()
  :title("My Control Panel")
  :position(100, 100)

local root_column = hafen.ui():column()
  :gap(6)
  :parent(custom_window)

local action_button = hafen.ui():button()
  :text("Trigger Action")
  :parent(root_column)

action_button:on("Click", function()
  hafen.log():write("Action button clicked!")
end)

custom_window:pack()
```

---

## UI Subsystems Navigation

| Subsystem | Reference Page | Description |
|---|---|---|
| **Widget Object** | **[widget.md](widget.md)** | Core `Widget` methods, dimensions, hierarchy, and visibility. |
| **Custom Windows** | **[custom.md](custom.md)** | Creating draggable windows and custom canvas surfaces. |
| **2D Drawing** | **[drawing.md](drawing.md)** | Direct 2D canvas drawing surface (`graphics:text`, `:rect`, `:frect`, `:image`). |
| **Controls** | **[controls/README.md](controls/README.md)** | Buttons, checkboxes, labels, text entries, and images. |
| **Layout Containers**| **[column.md](column.md)** | Auto-flowing vertical columns and horizontal rows (`:pack()`). |
| **Selectors** | **[selectors.md](selectors.md)** | CSS-like queries to find widgets in the client tree (`s:ui():match(...)`). |
| **Items & Inventory**| **[items.md](items.md)** | Backpack grids, inventory slots, dragging, and transferring items. |
| **Stylesheets** | **[style/README.md](style/README.md)**| Styling rules, colors, borders, and window chrome. |
