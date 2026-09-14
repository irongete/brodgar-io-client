# hafen.ui: Listbox Controls

Create scrollable list selection widgets displaying rows of selectable items.

## Quick Example

```lua
local parent_window = hafen.ui():window():title("Select Target"):size(200, 160)

local target_listbox = hafen.ui():listbox()
  :size(180, 120)
  :position(10, 10)
  :parent(parent_window)

-- Populate rows
target_listbox:rows({ "Apple Tree", "Wild Boar", "Iron Boulder", "Player Cabin" })

-- React to selection
target_listbox:on("Selected", function(selected_index, selected_value)
  hafen.log():write(string.format("Selected row %d: %s", selected_index, tostring(selected_value)))
end)
```

---

## Methods on `ListBox`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.ui():listbox()` | None | `ListBox` | Creates a new scrollable listbox widget. |
| `:rows(row_array)` | `string[]` | `self` | Populates the listbox with an array of text options. |
| `:selected()` | None | `number \| nil` | 1-based index of the currently selected row. |
| `:selected(index)` | `number` | `self` | Programmatically selects row `index`. |
| `:clear()` | None | `self` | Clears all rows from the listbox. |
