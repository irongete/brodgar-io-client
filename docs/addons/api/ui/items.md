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
| `:contents()` | [`Contents`](#what-an-item-holds) \| nil | what it holds; `nil` for an item holding nothing |
| `:container()` | [`Item`](#the-item-object) \| nil | the item it sits **inside**; `nil` for one sitting in a container widget |
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

## What an item holds

`item:contents()` answers a **`Contents`** object for an item that holds something, and `nil` for one that
holds nothing. A stack of dandelions and a creel carry real items, each with its own quality and its own
server address; a bucket carries what its tooltip states and no items at all. One object answers for both,
because the client is never told which kind it has — the difference is the server's, and a read that guessed
would answer confidently and wrongly.

| Read | Returns | Description |
|---|---|---|
| `contents:items()` | [`Item`](#the-item-object)`[]` | what is inside, as live objects; an **empty array**, never `nil`, for a container that states what it holds rather than carrying it |
| `contents:name()` | string \| nil | what the server calls this inside — the caption its own window carries; `nil` when it gave none |
| `contents:info()` | table | the [snapshot](../types.md#contents), which carries no `items` |

Reading is unprotected, and a `Contents` is **interned** like every other object here, so two reads of one
item's contents are `==`. It answers `nil` while the item's info is still resolving, never a half-built
object — so `nil` means "holds nothing" and an empty `:items()` means "an empty container".

```lua
for _, it in ipairs(hafen.ui():inventory():items()) do
  local held = it:contents()
  for _, one in ipairs(held and held:items() or {}) do
    hafen.log():write((one:name() or "?") .. " q" .. (one:quality() or 0)   -- its own quality...
                      .. " in " .. (it:name() or "?"))                      -- ...not the stack's
  end
end
```

**`item:container()` is the exact inverse.** `a:contents():items()` holds `b` if and only if `b:container()`
is `a`, and it chains: a dandelion in a stack in a creel answers the stack, and the stack answers the creel.
It is a **where** read, so like `:cell()`, `:slots()` and `:handle()` it answers `nil` on a stale item —
where it is is exactly what a departed item no longer has.

**A contained item is not in `widget:items()`.** A stack is one item there, as it is one cell on screen:
flattening it would break `:cell()` and `#items` as the count of slots used, and delete the difference between
one stack of eight and eight loose things. So a thing inside answers `:cell()` as `nil` too — it is drawn no
cell of its own — and you reach it by recursing through `:contents()`, picking your own depth. The protected
verbs reach it like any other item: `:take()` on one dandelion lifts that one, and `:take()` on the stack
lifts the whole pile in one message.

**Nothing has to be open.** The window a container pops up under the pointer is hidden rather than destroyed
when you move away, so every read here answers the same with it down. Opening it is not something an addon can
do either: the message that pins it open is the server's answer to a right-click, not anything the client
sends.

## Write (protected)

What you can do **to** an item is on the item. Each sends exactly what the matching click sends, and each
hands the Item back, so a run of verbs chains.

| Method | Key | Description |
|---|---|---|
| `item:use(mods)` | `item.use` | activate it: its default right-click action — eat, open, light, … |
| `item:take()` | `item.take` | pick it up onto the cursor, or unequip a worn item |
| `item:drop(n)` | `item.drop` | drop it on the ground |
| `item:transfer(n)` | `item.transfer` | move it to the linked container, or to your inventory |

Each needs its own [permission key](../../guides/permissions.md) declared in your manifest — or the group
`item.*`, which covers all four — and raises an error naming that key when it was not declared.

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

What an item holds is what the **server** pushed with it, and it pushes it for the containers you carry: a
stack, a creel, a bucket. An item it sent nothing for reads `nil`, and no message the client can send asks for
one — a chest standing in the world is opened, and read as the container widget it becomes.

## See also

- [`Item`](../types.md#item) — the snapshot `:info()` hands back
- [`hafen.player`](../player.md#the-hand) — the cursor: what it carries, and applying it to something
- [widget](widget.md) — the object `:items()` is a method on
- [replace](replace.md#watching-for-a-widget) — waiting for a container to open in the first place
- [events](../event.md#character-and-status) — `EquipChanged` and the other global lists
