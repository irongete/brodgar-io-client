# hafen.ui: the items inside a container

`widget:items()` is a **relation**, exactly like `:children()`: it answers with the
[`Item`](#the-item-object) objects inside *that* widget — your backpack, a chest, a cupboard, an
equipment grid — while the window stays visible and interactive. Nothing is hidden and nothing is
registered. Reading is unprotected.

```lua
for _, it in ipairs(hafen.ui():inventory():items()) do
  hafen.log():write((it:name() or it:res() or "?") .. " x" .. (it:quantity() or 1))
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
| `:quantity()` | number \| nil | [how many](#the-two-numbers-on-an-icon) this one item is — the number on its icon; `nil` for one showing none |
| `:progress()` | number \| nil | [the arc](#the-two-numbers-on-an-icon) painted over the icon, `0..1`; `nil` for one painting none |
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
`:quantity()` still say what it was, `:exists()` is false, and `:cell()`, `:slots()` and `:handle()` are
empty, because where it is is exactly what it no longer has.

> The verbs below take the object, never the number. A stale one raises an error and sends nothing,
> rather than moving whatever took its place.

## The two numbers on an icon

An item wears up to two numbers where you can see them: a **count** in the corner and an **arc** drawn
round the middle. Each has two sources — a field a server message writes, and the item's own tooltip —
and the client falls back from one to the other as it paints. `:quantity()` and `:progress()` fold the
same two sources in the same order, which makes one contract hold for both: **if you can see it on the
icon, the verb answers it.** An item showing neither answers `nil` to both, and so does one whose
tooltip has not resolved yet.

`:quantity()` is usually how many this one item **is**. A counted item (`42 seeds of Hemp`) is one thing
with one quality and no parts, so it holds nothing and this is the only count it has; a stack draws the
same number for what is inside it, where it agrees with `#item:contents():items()`.

**What the count counts is the item's own business, and it is not always a quantity.** The client has one
way to put a number on an icon, and the code shipped with an item decides what to put there: gildable gear
draws how many gildings it carries, `0` included. So the verb answers what is drawn — read it beside
`:res()` when you need to know what you are counting, rather than assuming every number is an amount.

`:progress()` is a **fraction with no units**. The client paints a wedge and can say how far round it
went, never how many of how many, so the verb names the arc rather than a magnitude — what it measures
is the server's business, and an item may paint one for a craft in flight as readily as for wear.

> `item:progress()` **reads a number**. The [progress bar](controls/display.md#progress-bar) that
> `hafen.ui():progress()` builds is a control you put on the screen; the two share a name and nothing else.

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
| `contents:text()` | string \| nil | the line the tooltip states about what is inside; `nil` for a container carrying items |
| `contents:quality()` | number \| nil | the **content's** own quality, which is not `item:quality()`; `nil` when none is stated |
| `contents:level()` | table \| nil | the fill meter's `{cur, max}`; `nil` for a container that draws none |
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

### A liquid container: a stated line, a fill, and no items

A bucket, a jug, a barrel holds something and carries no items, so the other three reads answer instead:

```lua
local b = hafen.ui():inventory():items()[1]        -- a jug holding water
local c = b:contents()

c:text()                   --> "4.55 l of Water"   the line its tooltip states
c:quality()                --> the water's quality, and b:quality() is still the jug's
c:level()                  --> { cur = 455, max = 500 }   the fill meter, in its own scale
c:items()                  --> { }                 it states what it holds; it does not carry it
```

`c:level()` is how you ask how full something is, and the two counts are the ones behind the bar drawn on
the item's icon — the client itself paints only the fraction of them, so this is the one place they read as
numbers. **They are the meter's own scale and not the units the line states**: divide one by the other and
compare fractions, rather than reading `cur` as the number in front of the `l`. A stack answers `nil` to
`:text()` and `:level()`, and a container that states what it holds answers an empty `:items()`, so the two
insides are told apart by asking rather than by knowing which you hold.

**The substance is never named to the client.** What arrives is that rendered line, a quality and a fill:
there is no water type behind them to ask for instead, so `c:text()` is the whole of what can be said about
what is in there. Match on the line if you must, knowing it is a display string carrying the amount as well
as the name.

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

### The events go deeper than `:items()`

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
