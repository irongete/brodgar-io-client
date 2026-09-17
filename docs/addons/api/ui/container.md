# hafen.ui: An Item Entering or Leaving a Container

Two keys on a container widget report what arrived and what left, through the [`:on(key, fn)`](widget.md#subscribing) every widget answers, instead of polling [`widget:items()`](items.md). Subscribing is unprotected.

```lua
local function label(item) return item:name() or item:res() or "?" end

hafen.session():current():ui():on("window[title=Chest]", "Added", function(chest)
  chest:on("ItemAdded",   function(item) hafen.log():write("in:  " .. label(item)) end)
  chest:on("ItemRemoved", function(item) hafen.log():write("out: " .. label(item)) end)
  chest:on("Removed",     function() hafen.log():write("chest closed") end)
end)
```

Two chests can be open at once, so take each as it opens rather than naming it from the root.

---

## Keys

| Key | Handler receives | Cancelable | Fires |
|---|---|---|---|
| `ItemAdded` | [`Item`](items.md#the-item-object) | No | An item enters this container. |
| `ItemRemoved` | `Item` | No | An item leaves it. |
| `Removed` | — | No | This widget leaves the tree (universal to any widget, stated here because a container closing is the usual reason to hold one). |

| Rule | Detail |
|---|---|
| The subscription is the registration | A container nobody subscribed to is watched for nothing. Dropping the last `ItemAdded`/`ItemRemoved` subscription stops the watching. |
| A widget create or destroy | An item entering or leaving is seen the moment the client puts its icon into the tree or takes it out. It is not seen on a server message. |
| Seeding | The items already inside fire `ItemAdded` while you subscribe, before `:on` returns. That is the top-level items alone, exactly what `widget:items()` answers, never what is inside them. |
| Hidden containers still fire | Which is why a [hidden grid](native.md) still reads. |
| A widget that has left the tree | Subscribing to any of these keys fires `Removed` there and then and drops every subscription on it. |
| The `ItemRemoved` payload | The same object the add reported, and it answers after it has left — [a stale item](items.md#the-item-object). |
| Worn equipment | Also has the global [`EquipChanged`](../event/bus/character.md#character-and-status) event, carrying the whole new list. |

---

## The events go deeper than `:items()`

The keys answer what entered this container, not what it draws — the one place the read and the events differ.

| Rule | Detail |
|---|---|
| Any depth | An item dropped into a stack or a creel this container holds fires here, since it did arrive in your inventory. `widget:items()` stays as shallow as ever, a stack being one cell. `item:container()` places the item a handler was handed. |
| Only the outermost thing that moved | A stack arriving with three dandelions inside fires `ItemAdded` once, for the stack. A dandelion dropped into a stack already there fires its own, the stack not having moved. |
| A contained item | Reported only when it later moves on its own. It never appears in the seeding read. |

---

## See Also

- [Items](items.md) — the `Item` object every payload is, and the container's own read.
- [Contents](contents.md) — why a stack arriving fires once.
- [Widget](widget.md#subscribing) — the door every widget key goes through.
- [Events](../event/bus/character.md#character-and-status) — `EquipChanged` and the other lists.
