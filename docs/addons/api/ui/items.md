# hafen.ui: Inventory Items & Icons

Inspect inventory items, worn equipment, container grids, item counts, qualities, and perform item manipulation.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local backpack_inventory = session:ui():inventory()
if backpack_inventory then
  for _, item_entry in ipairs(backpack_inventory:items():list()) do
    local item_name = item_entry:name() or item_entry:res() or "Unknown"
    local quantity = item_entry:quantity() or 1
    local quality_score = item_entry:quality()
    hafen.log():write(string.format(
      "Item: %s (x%d, Quality: %s)",
      item_name, quantity, quality_score and tostring(math.floor(quality_score)) or "N/A"
    ))
  end
end
```

---

## Methods on `Widget` for Items

| Method | Returns | Description |
|---|---|---|
| `widget:items()` | `ItemCollection` | Returns all items drawn inside this container widget or window. |
| `widget:item()` | `Item \| nil` | If this widget is a single item icon, returns the `Item` it represents. |
| `session:player():hand():item()` | `Item \| nil` | The item currently held on the mouse cursor. |

---

## Methods on `Item`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Display name of the item. |
| `:res()` | `string \| nil` | Stable engine resource path (e.g. `"gfx/invobjs/torch"`). |
| `:quantity()` | `number \| nil` | Stack count, or `nil` if not stackable. |
| `:quality()` | `number \| nil` | Item quality rating (or `nil`). |
| `:cell()` | `{x, y} \| nil` | 1-based inventory grid coordinates. |
| `:durability()` | `{cur, max} \| nil` | Durability hit points. |
| `:slots()` | `string[]` | Worn equipment slots (if equipped). |
| `:contents()` | `Contents \| nil` | Nested items or fluid volumes held within this item. See [Item Contents](contents.md). |
| `:exists()` | `boolean` | `true` if the item is still present in the container. |
| `:info()` | `table` | Plain table snapshot. |

---

## Protected Item Actions

Manipulating items requires the corresponding `item.*` permissions in `manifest.json`:

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `item:take()` | None | `item.take` | Picks the item up onto the mouse cursor. |
| `item:drop([count])` | `[number]` | `item.drop` | Drops the item (or `count` from stack) onto the ground. |
| `item:transfer(target_container)`| `Widget` | `item.transfer` | Moves the item into another container. |
| `item:use()` | None | `item.use` | Activates or consumes the item. |

---

## See Also

- [Item Contents](contents.md) — Inspecting fluid volumes, stacks, and nested container items.
- [Container Events](container.md) — Subscribing to items entering or leaving containers.
- [Permissions](../../guides/permissions.md) — Required `item.*` permissions.

