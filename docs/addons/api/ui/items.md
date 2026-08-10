# hafen.ui: the items inside a container

`widget:items()` is a **relation**, exactly like `:children()`: it answers with the
[`Item`](#the-item-object) objects inside *that* widget — your backpack, a chest, a cupboard, an
equipment grid — while the window stays visible and interactive. Nothing is hidden and nothing is
registered. Reading is unprotected.

```lua
for _, it in ipairs(hafen.ui():inventory():items()) do
  hafen.log():write((it:name() or it:res() or "?") .. " x" .. (it:num() or 1))
end

local h = hafen.player():hand()                -- the cursor, or nil while it is empty
local cursor = h and h:item()                  -- the item on it
```

## Read

| Method | Returns | Description |
|---|---|---|
| `widget:items()` | [`Item`](#the-item-object)`[]` | the items inside this widget, in the container's own order |
| [`hafen.player():hand():item()`](../player.md#the-hand) | [`Item`](#the-item-object) \| nil | the item on the cursor |

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
| `:handle()` | number \| nil | its server widget id, the number it is addressed by on the wire; `nil` once it is gone |
| `:exists()` | boolean | is this still a live item |
| `:info()` | table | the [snapshot](../types.md#item) — every read above in one table |

An item is **interned**, so `==` is the identity test and a stashed one keeps answering. It is keyed on
the item itself and never on `:handle()`, because the server re-uses that number: a reference built on
it would stop naming this item and start naming its replacement, silently. So an item that moves, is
eaten or is consumed does not become something else — it goes **stale**: `:res()`, `:name()` and
`:num()` still say what it was, `:exists()` is false, and `:cell()`, `:slots()` and `:handle()` are
empty, because where it is is exactly what it no longer has.

> The verbs below take the object, never the number. A stale one raises an error and sends nothing,
> rather than moving whatever took its place.

## Write (protected: `actions`)

What you can do **to** an item is on the item. Each sends exactly what the matching click sends, and each
hands the Item back, so a run of verbs chains.

| Method | Description |
|---|---|
| `item:use(mods)` | activate it: its default right-click action — eat, open, light, … |
| `item:take()` | pick it up onto the cursor, or unequip a worn item |
| `item:drop(n)` | drop it on the ground |
| `item:transfer(n)` | move it to the linked container, or to your inventory |

```lua
local first = hafen.ui():inventory():items()[1]
if first then first:take() end
```

`n` is how many of a stack to move; it is optional and defaults to `-1`, meaning all of it. `mods` is
optional and defaults to `0`: Shift = 1, Ctrl = 2, Alt = 4, added together. `take` takes **no arguments**
at all, and an argument to it raises.

**Only `use` carries modifiers, and that is the wire rather than a style.** `take`, `drop` and `transfer`
have no modifier field in them: on a real click the modifier keys select the *count* — shift transfers
one, ctrl drops one — so `n` states that directly and is the whole of it. Do not look for a `mods` beside
it.

All four raise on a **stale** item, and send nothing: an item that moved, was used or was consumed is not
the item that took its place. Re-read the container and retry.

Applying what you are carrying **onto** an item is the cursor's verb, not the item's:
[`hafen.player():hand():use(item)`](../player.md#the-hand). On an arbitrary item that gesture would name
whatever happens to be on the cursor rather than the receiver, which is why it lives on the hand.

## The container lifecycle

Two keys on the container itself, through the same [`:on(key, fn)`](widget.md#subscribing) every widget
answers — plus `Destroy`, universal to any widget, worth re-stating here because a container closing is
usually the reason to hold one.

| Key | handler receives | Fires |
|---|---|---|
| `ItemAdded` | [`Item`](#the-item-object) | an item enters this container |
| `ItemRemoved` | [`Item`](#the-item-object) | one leaves |
| `Destroy` | — | this widget leaves the tree |

Two chests can be open at once, so take each one as it opens rather than naming it from the root:

```lua
local function label(item) return item:name() or item:res() or "?" end

hafen.ui():on("window[title=Chest]", "appear", function(chest)
  chest:on("ItemAdded",   function(item) hafen.log():write("in:  " .. label(item)) end)
  chest:on("ItemRemoved", function(item) hafen.log():write("out: " .. label(item)) end)
  chest:on("Destroy",     function() hafen.log():write("chest closed") end)
end)
```

**The subscription is the registration.** A container nobody subscribed to is watched for nothing, so
leaving `:items()` alone costs nothing, and dropping the last subscription on `ItemAdded`/`ItemRemoved`
stops the watching. There is no separate watch/unwatch pair because there is nothing extra to say.

An item entering or leaving is a widget create or destroy rather than a server message, so both are seen
at the moment the client puts that widget into the tree or takes it out. Three consequences are worth
knowing: the items **already** inside a container fire `ItemAdded` while you subscribe, before `:on`
returns, so the state arrives as events the way [`BuffAdded`](../event.md#character-and-status) does; a
container that is hidden still fires them, which is why you can [hide a grid](native.md) and keep reading
it; and subscribing to any of the three on a widget that has already left the tree fires `Destroy` there
and then, and drops every subscription on it. The item handed to `ItemRemoved` is the same object the add
reported, so it is worth keeping — it answers after it has left. Worn equipment additionally has the
global [`EquipChanged`](../event.md#character-and-status) event, which carries the whole new list.

## Where the item reads end

What a container holds *as an item* — a cupboard picked up with its contents inside — is not readable:
the client only knows what is in a container while its own window is open, so the answer would be a
guess. Open it and read that widget.

## See also

- [`Item`](../types.md#item) — the snapshot `:info()` hands back
- [`hafen.player`](../player.md#the-hand) — the cursor: what it carries, and applying it to something
- [widget](widget.md) — the object `:items()` is a method on
- [replace](replace.md#watching-for-a-widget) — waiting for a container to open in the first place
- [events](../event.md#character-and-status) — `EquipChanged` and the other global lists
