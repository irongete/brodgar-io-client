# hafen.ui: Built-in UI Controls

Overview of the native UI widgets provided by the engine for building custom windows and forms.

## Control Categories

| Category | Reference Page | Included Widgets |
|---|---|---|
| **Interactive Controls** | **[interactive.md](interactive.md)** | `button()`, `check()`, `entry()`, `slider()`, `listbox()`, `scroll()`. |
| **Display Controls** | **[display.md](display.md)** | `label()`, `image()`, `progress()`. |

---

## Quick Example

```lua
local form_window = hafen.ui():window():title("Settings Form")
local root_column = hafen.ui():column():gap(6):parent(form_window)

-- Add text label and text entry
hafen.ui():label():text("Enter Target Tag:"):parent(root_column)
local tag_entry = hafen.ui():entry():size(160):parent(root_column)

-- Add checkbox
local alert_check = hafen.ui():check():text("Enable notifications"):parent(root_column)

-- Add submit button
local submit_button = hafen.ui():button():text("Save Settings"):parent(root_column)
submit_button:on("Pressed", function()
  hafen.log():write("Saved tag: " .. (tag_entry:text() or ""))
end)

form_window:pack()
```
