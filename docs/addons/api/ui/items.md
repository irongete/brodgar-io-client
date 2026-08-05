# hafen.ui: the items inside a container

`widget:items()` is a **relation**, exactly like `:children()`: it answers with the
[`Item`](#the-item-object) objects inside *that* widget — your backpack, a chest, a cupboard, an
equipment grid — while the window stays visible and interactive. Nothing is hidden and nothing is
registered. Reading is ungated.

```lua
for _, it in ipairs(hafen.ui():inventory():items()) do
  hafen.log():write((it:name() or it:res() or "?") .. " x" .. (it:num() or 1))
end

local cursor = hafen.ui():hand()                              -- the item on the cursor, or nil
```

## Read

| Method | Returns | Description |
|---|---|---|
| `widget:items()` | [`Item`](#the-item-object)`[]` | the items inside this widget, in the container's own order |
| `hafen.ui():hand()` | [`Item`](#the-item-object) \| nil | the item on the cursor |

- The search is **deep**, so a whole window answers for the grid inside it: `hafen.ui():node(chestId):items()`
  works whether you point at the window or at its `Inventory` child.
- **Each item appears once.** The equipment window draws a worn item in every slot it fills, and
  `item:slots()` names them all — so a two-slot piece of gear is one entry, not two. The window does not
  publish a display name for every one of its places; a slot that has none is listed by its own identifier
  instead, so a worn item always names where it is and an empty `:slots()` means exactly *not worn*.
- A non-container, or a stale widget, answers with an **empty array**, never `nil`.
- There is no `find` verb: it is a one-liner over `:items()`, and it would have to pick a container for you.

## The Item object

| Method | Returns | Description |
|---|---|---|
| `:res()` | string \| nil | resource name — the item's stable identity |
| `:name()` | string \| nil | display name, once the item's tooltip has resolved |
| `:num()` | number \| nil | stack count; `nil` for something that is not a stack |
| `:wear()` | number \| nil | 0..100 wear or progress; `nil` when the item carries no meter |
| `:quality()` | number \| nil | the quality the tooltip shows; `nil` for an item that has none |
| `:cell()` | table \| nil | the `{x, y}` grid cell it sits in, for an item in a container that has cells |
| `:slots()` | string[] | the equipment slots it fills, by name; empty for anything not worn |
| `:handle()` | number \| nil | its server widget id, for [`hafen.act():raw`](../act.md); `nil` once it is gone |
| `:exists()` | boolean | is this still a live item |
| `:info()` | table | the [snapshot](../types.md#item) — every read above in one table |

An item is **interned**, so `==` is the identity test and a stashed one keeps answering. It is keyed on
the item itself and never on `:handle()`, because the server re-uses that number: a reference built on
it would stop naming this item and start naming its replacement, silently. So an item that moves, is
eaten or is consumed does not become something else — it goes **stale**: `:res()`, `:name()` and
`:num()` still say what it was, `:exists()` is false, and `:cell()`, `:slots()` and `:handle()` are
empty, because where it is is exactly what it no longer has.

> The gated [`hafen.act():item`](../act.md#hafenactitemitem-verb-n) takes the object. A stale one raises
> an error and sends nothing, rather than moving whatever took its place.

## The container lifecycle

Three subscriptions on the container itself. All chain, and passing `nil` unsubscribes.

| Method | Description |
|---|---|
| `:onItemAdded(fn)` | `fn(item)` when an item enters this container |
| `:onItemRemoved(fn)` | `fn(item)` when one leaves |
| `:onDestroy(fn)` | `fn()` once, when this widget leaves the tree |

```lua
local chest = hafen.ui():find("window[title=Chest]")
local function label(item) return item:name() or item:res() or "?" end
chest:onItemAdded(function(item)   hafen.log():write("in:  " .. label(item)) end)
     :onItemRemoved(function(item) hafen.log():write("out: " .. label(item)) end)
     :onDestroy(function() hafen.log():write("chest closed") end)
```

**The subscription is the registration.** A container nobody subscribed to is never polled, so leaving
`:items()` alone costs nothing, and dropping the last callback takes the widget out of the poll entirely.
There is no `:watch()`/`:unwatch()` pair because there is nothing extra to say.

An item entering or leaving is a widget create or destroy rather than a server message, so these are
detected on a per-tick diff. Two consequences are worth knowing: the items **already** inside a container
fire `onItemAdded` on the first poll after you subscribe, so the state arrives as events the way
[`BuffAdded`](../event.md#character-and-status) does; and a container that is hidden still
fires them, which is why you can [hide a grid](native.md) and keep reading it. The item handed to
`:onItemRemoved` is the same object the add reported, so it is worth keeping — it answers after it has
left. Worn equipment additionally has the global
[`EquipChanged`](../event.md#character-and-status) event, which carries the whole new list.

## Where the item reads end

What a container holds *as an item* — a cupboard picked up with its contents inside — is not readable:
the client only knows what is in a container while its own window is open, so the answer would be a
guess. Open it and read that widget.

## See also

- [`Item`](../types.md#item) — the snapshot `:info()` hands back
- [`hafen.act():item`](../act.md#hafenactitemitem-verb-n) — the gated verb that moves one
- [widget](widget.md) — the object `:items()` is a method on
- [replace](replace.md#watching-for-a-widget) — waiting for a container to open in the first place
- [events](../event.md#character-and-status) — `EquipChanged` and the other global lists
