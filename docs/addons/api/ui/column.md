# hafen.ui: Layout Columns & Rows

Auto-flowing vertical columns and horizontal rows for building responsive control panels.

## Quick Example

```lua
local parent_window = hafen.ui():window():title("Settings Panel")

-- Root vertical column
local root_column = hafen.ui():column()
  :gap(8)
  :parent(parent_window)

-- Horizontal row with an icon and text label
local header_row = hafen.ui():row()
  :gap(4)
  :parent(root_column)

hafen.ui():label():text("Configuration Options"):parent(header_row)

-- Add checkboxes into the column
hafen.ui():check():text("Auto-harvest crops"):parent(root_column)
hafen.ui():check():text("Sound notifications"):parent(root_column)

-- Automatically size the window around its contents
parent_window:pack()
```

---

## Container Methods

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.ui():column()` | None | `Widget` | Creates a vertical column container. Children stack top-to-bottom. |
| `hafen.ui():row()` | None | `Widget` | Creates a horizontal row container. Children lay out left-to-right. |
| `:gap(pixels)` | `number` | `self` | Sets spacing in pixels between consecutive child widgets. |
| `:parent(parent_widget)`| `Widget` | `self` | Attaches this container into a parent widget or window. |
| `:pack()` | None | `self` | Re-measures all children and resizes the container to fit them tightly. |
