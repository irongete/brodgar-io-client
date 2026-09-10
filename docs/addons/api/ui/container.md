# hafen.ui: an item entering or leaving a container

Two keys on the container itself say what arrived and what left, through the same
[`:on(key, fn)`](widget.md#subscribing) every widget answers. Reach for them instead of polling
[`widget:items()`](items.md): the read answers what a container draws right now, and these answer the
moment it changed. Subscribing is unprotected.

`Removed` is beside them, universal to any widget and worth stating here because a container closing is
usually the reason to hold one.

| Key | handler receives | Fires |
|---|---|---|
| `ItemAdded` | [`Item`](items.md#the-item-object) | an item enters this container |
| `ItemRemoved` | [`Item`](items.md#the-item-object) | one leaves |
| `Removed` | — | this widget leaves the tree |

Two chests can be open at once, so take each one as it opens rather than naming it from the root:

```lua
local function label(item) return item:name() or item:res() or "?" end

hafen.session():current():ui():on("window[title=Chest]", "Added", function(chest)
  chest:on("ItemAdded",   function(item) hafen.log():write("in:  " .. label(item)) end)
  chest:on("ItemRemoved", function(item) hafen.log():write("out: " .. label(item)) end)
  chest:on("Removed",     function() hafen.log():write("chest closed") end)
end)
```

**The subscription is the registration.** A container nobody subscribed to is watched for nothing, so
leaving `:items()` alone costs nothing, and dropping the last subscription on `ItemAdded`/`ItemRemoved`
stops the watching. There is no separate watch/unwatch pair because there is nothing extra to say.

An item entering or leaving is a widget create or destroy rather than a server message, so both are seen
at the moment the client puts that widget into the tree or takes it out. Three consequences are worth
knowing: the items **already** inside a container fire `ItemAdded` while you subscribe, before `:on`
returns, so the state arrives as events the way [`BuffAdded`](../event/bus/character.md#character-and-status)
does; a container that is hidden still fires them, which is why you can [hide a grid](native.md) and keep
reading it; and subscribing to any of the three on a widget that has already left the tree fires `Removed`
there and then, and drops every subscription on it. The item handed to `ItemRemoved` is the same object the
add reported, so it is worth keeping — it answers after it has left. Worn equipment additionally has the
global [`EquipChanged`](../event/bus/character.md#character-and-status) event, which carries the whole new
list.

## The events go deeper than `:items()`

`ItemAdded`/`ItemRemoved` answer **what entered this container**, not what it draws — the one place the
read and the events part company. An item dropped into a stack, or a creel, that this container holds
fires here too, **at any depth**, because it did arrive in your inventory; `widget:items()` on that same
widget stays exactly as shallow as ever, since a stack is still one cell on screen. `item:container()` is
what a handler uses to place the item it was handed — the stack a contained item just entered, or the
creel a stack just arrived in.

**Only the outermost thing that moved is reported.** A stack *arriving* with three dandelions already
inside it fires `ItemAdded` **once**, for the stack — never once per dandelion — and the same on the way
out. A dandelion dropped into a stack that was already there fires its own `ItemAdded`, because the stack
itself did not move.

**Subscribing seeds with exactly what `widget:items()` answers right now** — the top-level items alone,
never what is inside them — and a contained item is reported only when it later moves on its own; it
never appears in that read.

## See also

- [items](items.md) — the `Item` object every payload here is, and the container's own read
- [what an item holds](contents.md) — why a stack arriving fires once and not once per thing inside it
- [widget](widget.md#subscribing) — the one door every widget key goes through
- [events](../event/bus/character.md#character-and-status) — `EquipChanged` and the other global lists
