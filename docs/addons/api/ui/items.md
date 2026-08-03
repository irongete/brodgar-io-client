# hafen.ui: the items inside a container

`widget:items()` is a **relation**, exactly like `:children()`: it answers with the
[`Item`](../types.md#item) snapshots inside *that* widget — your backpack, a chest, a cupboard, a study
window, an equipment grid — while the window stays visible and interactive. Nothing is hidden and nothing
is registered. Reading is ungated.

```lua
for _, it in ipairs(hafen.ui.inventory():items()) do
  hafen.log((it.name or it.res or "?") .. " x" .. (it.num or 1))
end

local cursor = hafen.ui.hand()                              -- the item on the cursor, or nil
```

## Read

| Method | Returns | Description |
|---|---|---|
| `widget:items()` | [`Item`](../types.md#item)`[]` | the items inside this widget, in the container's own order |
| `hafen.ui.hand()` | [`Item`](../types.md#item) \| nil | the item on the cursor — a snapshot, since there is no widget to walk |

- The search is **deep**, so a whole window answers for the grid inside it: `hafen.ui.node(chestId):items()`
  works whether you point at the window or at its `Inventory` child.
- Each entry's `pos` is shaped by its container — an inventory cell `{x, y}`, or, from
  `hafen.ui.equipment()`, the slot name plus a numeric `slot`. A two-slot worn item appears as two entries
  with distinct `slot` values.
- A non-container, or a stale widget, answers with an **empty array**, never `nil`. `quality` and a
  container's own `contents` are not exposed.
- There is no `find` verb: it is a one-liner over `:items()`, and it would have to pick a container for you.

To **move** an item — take, drop, transfer, use — pass its `handle` to the gated
[`hafen.act.item`](../act.md#hafenactitemitem-verb-n).

## The container lifecycle

Three subscriptions on the container itself. All chain, and passing `nil` unsubscribes.

| Method | Description |
|---|---|
| `:onItemAdded(fn)` | `fn(item)` when an item enters this container |
| `:onItemRemoved(fn)` | `fn(item)` when one leaves |
| `:onDestroy(fn)` | `fn()` once, when this widget leaves the tree |

```lua
local chest = hafen.ui("window[title=Chest]")
chest:onItemAdded(function(item) hafen.log("in:  " .. (item.name or item.res or "?")) end)
     :onItemRemoved(function(item) hafen.log("out: " .. (item.name or item.res or "?")) end)
     :onDestroy(function() hafen.log("chest closed") end)
```

**The subscription is the registration.** A container nobody subscribed to is never polled, so leaving
`:items()` alone costs nothing, and dropping the last callback takes the widget out of the poll entirely.
There is no `:watch()`/`:unwatch()` pair because there is nothing extra to say.

An item entering or leaving is a widget create or destroy rather than a server message, so these are
detected on a per-tick diff. Two consequences are worth knowing: the items **already** inside a container
fire `onItemAdded` on the first poll after you subscribe, so the state arrives as events the way
[`BuffAdded`](../events.md#character-and-status) does; and a container that is hidden still
fires them, which is why you can [hide a grid](native.md) and keep reading it. Worn equipment additionally
has the global [`EquipChanged`](../events.md#character-and-status) event, which carries the
whole new list.

## See also

- [`Item`](../types.md#item) — the snapshot shape every verb here returns
- [`hafen.act.item`](../act.md#hafenactitemitem-verb-n) — the gated verb that moves one
- [widget](widget.md) — the object `:items()` is a method on
- [replace](replace.md#watching-for-a-widget) — waiting for a container to open in the first place
- [events](../events.md#character-and-status) — `EquipChanged` and the other global lists
