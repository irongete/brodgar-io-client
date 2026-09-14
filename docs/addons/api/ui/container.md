# Container Events

Subscribe to item additions, removals, and container lifecycle events on inventory and chest widgets.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Listen for open chests
session:ui():on("window[title=Chest]", "Added", function(chest_window)
  hafen.log():write("Chest window opened.")

  chest_window:on("ItemAdded", function(item_entry)
    local item_name = item_entry:name() or item_entry:res() or "Item"
    hafen.log():write("Item placed into chest: " .. item_name)
  end)

  chest_window:on("ItemRemoved", function(item_entry)
    local item_name = item_entry:name() or item_entry:res() or "Item"
    hafen.log():write("Item taken from chest: " .. item_name)
  end)

  chest_window:on("Removed", function()
    hafen.log():write("Chest window closed.")
  end)
end)
```

---

## Events on Container Widgets

| Event Name | Argument | Triggered |
|---|---|---|
| `"ItemAdded"` | `item_entry` ([`Item`](items.md)) | Fired when an item enters this container (including pre-existing items upon initial subscription). |
| `"ItemRemoved"` | `item_entry` ([`Item`](items.md)) | Fired when an item is removed from this container. |
| `"Removed"` | None | Fired when this container widget leaves the UI tree (closed). |
