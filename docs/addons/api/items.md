# hafen.items — inventory & equipment

Read the player's items as [`Item`](types.md#item) snapshots. To act on an item (take, drop, use, …),
pass its `handle` to the gated [`hafen.act.item`](actions.md#hafenactitem).

| Function | Returns | Description |
|---|---|---|
| `hafen.items.inventory()` | [`Item`](types.md#item)`[]` | the main inventory; each item's `pos` is its `{x,y}` grid cell |
| `hafen.items.equipment()` | [`Item`](types.md#item)`[]` | worn equipment; each item carries a `slot` index and a slot-name `pos` |
| `hafen.items.hand()` | [`Item`](types.md#item) \| nil | the item on the cursor, or nil |
| `hafen.items.find(nameOrRes)` | [`Item`](types.md#item)`[]` | inventory items whose `name` or `res` contains the string |

```lua
for _, it in ipairs(hafen.items.inventory()) do
  hafen.log((it.name or it.res or "?") .. " x" .. (it.num or 1))
end

local coins = hafen.items.find("coin")
```

Subscribe to [`EquipChanged`](events.md#character--status-widget-tree-backed) to react to
equipping/removing gear. A two-slot worn item appears as two entries with distinct `slot` values.
Item `quality` and container `contents` are not exposed.
