# hafen.ui: the items the client draws

**An item is a thing the client draws, and the icon drawing it is where you find it.** That covers the
cell in your backpack and the one on the cursor, and it covers a crafting recipe's input and output slots
and the food icon on a constipation row just as well: `widget:item()` answers on every one of them, and
they are all the same [`Item`](#the-item-object) object. `widget:items()` is the relation over the widget
*around* them, exactly like `:children()` — everything that widget draws, while the window stays visible
and interactive, so a crafting window answers for its recipe's slots the way your backpack answers for its
cells. Nothing is hidden and nothing is registered. Reading is unprotected.

```lua
local s = hafen.session():current()              -- the character on screen
for _, it in ipairs(s:ui():inventory():items():list()) do
  hafen.log():write((it:name() or it:res() or "?") .. " x" .. (it:quantity() or 1))
end

local h = s:player():hand()                      -- the cursor, or nil while it is empty
local cursor = h and h:item()                    -- the item on it
```

A container the client put up belongs to one character, so it is reached through that character's
[session](../session.md): two characters carry two backpacks, and the one nobody is looking at answers
just as well as the one on screen.

Some of what the client draws is **not** a thing the server put anywhere — a recipe slot names an
ingredient, it does not hold one. Those read exactly like an item and answer nothing about where they
are; [what a depiction cannot do](#a-depiction-that-is-not-an-item) is the whole of the difference.

## Read

| Method | Returns | Description |
|---|---|---|
| `widget:items()` | collection of [`Item`](#the-item-object) | everything this widget draws, in the order it draws them — `:list()` is the array |
| [`s:player():hand():item()`](../player.md#the-hand) | [`Item`](#the-item-object) \| nil | the item on the cursor |
| [`widget:item()`](widget.md#read) | [`Item`](#the-item-object) \| nil | the item **one icon** draws; `nil` on any other widget |

- **An icon is any widget that draws one item**: a container's cell, the cursor, a crafting recipe's input
  or output slot, the food icon on a constipation row, and a listing a resource ships its own widget for.
  `widget:item()` answers on all of them and `nil` on everything else, so the test for "is this an item
  icon" is the read itself — and the [`item` role](selectors.md#roles) is that same test, which is why
  `s:ui():matchAll("item")` and `s:ui():on("item", "Added", fn)` reach exactly the icons `:item()` answers
  on, and no others.
- The search is **deep**, so a whole window answers for what is inside it: `s:ui():node(chestId):items()`
  works whether you point at the window or at its `Inventory` child, and the crafting window lists its
  recipe's input and output slots the same way.
- **Each thing appears once.** The equipment window draws a worn item in every slot it fills, and
  `item:slots()` names them all — so a two-slot piece of gear is one entry, not two. The window does not
  publish a display name for every one of its places; a slot that has none is listed by its own identifier
  instead, so a worn item always names where it is and an empty `:slots()` means exactly *not worn*.
- **Quality inputs and tools are not items.** The crafting window prints those as bare pictures rather
  than icons, so nothing draws one and `:items()` lists none of them: what the recipe *asks* for is
  [`s:craft()`](../craft.md#a-spec), and the input and output slots are what it *draws*.
- **The cursor belongs to a character**, so it is read on the [session](../session.md) that names one: `s`
  above is `hafen.session():current()`, and one you are not looking at can perfectly well be carrying
  something.
- A widget that draws nothing, or a stale one, answers with an **empty array**, never `nil`.
- There is no `find` verb: it is a one-liner over `:items()`, and it would have to pick a widget for you.

## The Item object

| Method | Returns | Description |
|---|---|---|
| `:res()` | string \| nil | resource name — the item's stable identity |
| `:name()` | string \| nil | display name, once the item's tooltip has resolved |
| `:quantity()` | number \| nil | [how many](#the-two-numbers-on-an-icon) this one item is — the number on its icon; `nil` for one showing none |
| `:progress()` | number \| nil | [the arc](#the-two-numbers-on-an-icon) painted over the icon, `0..1`; `nil` for one painting none |
| `:durability()` | table \| nil | [the two counts](#durability-the-counts-a-wear-row-prints) its wear row prints, `{cur, max}`; `nil` for one printing none |
| `:quality()` | number \| nil | the quality the tooltip shows; `nil` for an item that has none |
| `:contents()` | [`Contents`](contents.md) \| nil | what it holds; `nil` for an item holding nothing |
| `:container()` | [`Item`](#the-item-object) \| nil | the item it sits **inside**; `nil` for one sitting in a container widget |
| `:cell()` | table \| nil | the **1-based** `{x, y}` grid cell it sits **in** — a place, where a grid control's [`:cellSize()`](widget.md#read) is a size |
| `:slots()` | string[] | the equipment slots it fills, by name; empty for anything not worn |
| `:handle()` | number \| nil | its server widget id, the number it is addressed by on the wire; `nil` once it is gone |
| `:exists()` | boolean | is the icon drawing this still in the tree |
| `:info()` | table | the [snapshot](../types/items.md#item) — every read above in one table |
| `:on("Changed", fn)` | a [subscription](../event/README.md#subscribe) | [what this item says about itself resolved, or was revised](#an-item-arrives-before-it-can-be-described) |

An item is **interned**, so `==` is the identity test and a stashed one keeps answering. Two reads of one
icon are the same object, and so are the icon's `:item()` and the `:items()` entry of the widget around it. It is
keyed on the thing drawn and never on `:handle()`, because the server re-uses that number: a reference
built on it would stop naming this item and start naming its replacement, silently.

**An item you keep goes stale rather than becoming something else.** One that moves, is eaten or is consumed
still says what it was — `:res()`, `:name()` and `:quantity()` go on answering — while `:exists()` turns
false and `:cell()`, `:slots()` and `:handle()` go empty, because where it is is exactly what it no longer
has. A depiction goes stale the same way when the widget drawing it leaves: close the crafting window and
its slots still name their ingredients, and `:exists()` is false. That is what makes an item worth keeping
past its own end — the [`ItemRemoved`](container.md) payload is a stale item, and it is the only
thing that can tell you what left.

> The verbs below take the object, never the number. A stale one raises an error and sends nothing,
> rather than moving whatever took its place.

## A depiction that is not an item

A **depiction** is something the client paints from a resource with nothing on the wire behind it: a recipe
slot, a price on a listing, an icon a piece of resource code puts up. It is an `Item` and reads like one —
the same `:res()`, `:name()`, `:quality()`, `:durability()` and `:info()`, off the same tooltip — because
the client describes both through one mechanism. What it has no answer for is **where it is** and **what to
do to it**, and each of those says so on its own:

| Read | On a depiction | Why |
|---|---|---|
| `:cell()` | `nil` | it is drawn where its icon is, and that is not a cell in a container |
| `:slots()` | empty | nothing is wearing it |
| `:handle()` | `nil` | the server has no widget for it, so there is no id to name |
| `:container()` | `nil` | nothing is holding it |
| `:contents()` | `nil` | the server sends an inside with an item it put somewhere, and it put this nowhere |
| `:exists()` | its icon | true while the widget drawing it is in the tree, false the moment that widget goes |

The four [protected verbs](#write-protected) **refuse** it, naming why and sending nothing: a depiction is
drawn, not held, so there is no message to send and nothing to retry. Guard on `:handle()` when you want to
know which kind you have before you act.

```lua
local function label(icon)
  local it = icon:item()
  if it == nil then return nil end
  local name = it:name() or it:res() or "?"
  if it:handle() == nil then return name .. " (drawn, not held)" end
  return name .. " #" .. it:handle()
end
```

The two numbers on the icon read from the item's own tooltip alone here — a depiction has no server message
writing a count or an arc, so `:quantity()` and `:progress()` answer what the tooltip publishes and `nil`
otherwise, which is still exactly what the icon draws.

## An item arrives before it can be described

**An item is on screen before the client knows what it is.** The server sends the icon first and the item's
tooltip after it, and the code that reads a quality, a wear row or a contents block out of that tooltip
*ships inside a resource* which may still be loading when the tooltip lands. Until both have happened,
`:name()`, `:quality()`, `:durability()`, `:contents()` and the two icon numbers all answer `nil` — for an
item that will answer perfectly well a moment later.

`item:on("Changed", fn)` is the moment it can be described. It fires when the tooltip resolves, and again
whenever the server revises it — a bucket you drink from, gear that wears, a stack you add to.

```lua
local face = hafen.font():get("serif"):derive():size(11)

hafen.session():current():ui():on("item", "Added", function(icon)   -- every item icon, wherever it is drawn
  local item = icon:item()
  if item == nil then return end

  local ov = icon:overlay():add("q")
  ov:anchor(0.5, 1):offset(0, -1):color{255, 230, 140}:font(face)

  local function show()
    local q = item:quality()
    if q then ov:text(tostring(math.floor(q + 0.5))) end
  end
  show()                                              -- if it is already known, now
  item:on("Changed", show)                            -- ...and if it is not, the moment it is
end)
```

- **One key, because the wire has one.** The server resends a whole tooltip rather than the field that
  changed, so a key per field would be a promise nothing can keep. Read what you need in the handler.
- **The payload is the item**, the same object you subscribed on, so one handler can serve several items.
- **It fires on the [step](../threading.md) after the answer becomes true** — the first frame after the
  client builds that item's description, which is the draw of its icon or your own read. An item nobody
  has drawn yet is described the moment something asks; reading it *is* asking.
- **A stale item never fires.** What it was is all it will ever say. Subscribing to one is legal and inert,
  so a handler that outlives its item needs no guard.
- **The subscription is per item**, and it goes when the item does. Ending one early is `sub:off()`.

> A **changed tooltip** is this event. An item **arriving** or **leaving** is the container's
> [`ItemAdded`/`ItemRemoved`](container.md) — where it is is not what it is.

## The two numbers on an icon

An item wears up to two numbers where you can see them: a **count** in the corner and an **arc** drawn
round the middle. Each has two sources — a field a server message writes, and the item's own tooltip —
and the client falls back from one to the other as it paints. `:quantity()` and `:progress()` fold the
same two sources in the same order, which makes one contract hold for both: **if you can see it on the
icon, the verb answers it.** An item showing neither answers `nil` to both, and so does one whose
tooltip has not resolved yet.

`:quantity()` is usually how many this one item **is**. A counted item (`42 seeds of Hemp`) is one thing
with one quality and no parts, so it holds nothing and this is the only count it has; a stack draws the
same number for what is inside it, where it agrees with `item:contents():items():count()`.

**What the count counts is the item's own business, and it is not always a quantity.** The client has one
way to put a number on an icon, and the code shipped with an item decides what to put there: gildable gear
draws how many gildings it carries, `0` included. So the verb answers what is drawn — read it beside
`:res()` when you need to know what you are counting, rather than assuming every number is an amount.

`:progress()` is a **fraction with no units**. The client paints a wedge and can say how far round it
went, never how many of how many, so the verb names the arc rather than a magnitude — what it measures
is the server's business, and an item may paint one for a craft in flight as readily as for wear.

> `item:progress()` **reads a number**. The [progress bar](controls/display.md#progress-bar) that
> `hafen.ui():progress()` builds is a control you put on the screen; the two share a name and nothing else.

## Durability: the counts a wear row prints

`item:durability()` answers `{cur, max}` for an item whose tooltip prints a wear row, and `nil` for one
that prints none — a piece of gear that never wears, and any item whose tooltip has not resolved yet. The
pair is handed over exactly as the row states it, `cur` first, and the row is drawn in red once `cur`
reaches `max`.

```lua
for _, it in ipairs(hafen.session():current():ui():equipment():items():list()) do
  local d = it:durability()
  if d then
    hafen.log():write((it:name() or "?") .. ": worn " .. d.cur .. " of " .. d.max)
  end
end
```

**Durability is not the arc, and neither read is a view of the other.** `:progress()` is a fraction with no
units — the client paints a wedge and can say how far round it went, never how many of how many — while
these are two absolute counts, and only absolute counts let you work out what is left. An item may answer
both, one, or neither, so ask for the one you want and guard it on its own.

**What a count measures is the server's business.** The client renders the pair and colours it; it is never
told what one point of wear costs or what happens when the two meet. Compare the numbers against
themselves, on the same item, rather than across two kinds of gear.

## Write (protected)

A write goes out **once per frame at most**; a second in the same frame raises. The client sends only
shapes a player could compose, and what the server does with more than that is the server's.

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
local first = hafen.session():current():ui():inventory():items():list()[1]
if first then first:take() end
```

`n` is how many of a stack to move; it is optional and defaults to `-1`, meaning all of it. `mods` is
optional and defaults to `0`: Shift = 1, Ctrl = 2, Alt = 4, added together. Optional is not unchecked: a
value that is not a number raises naming the verb and the parameter, and one that merely scans as a number
is [still a string](../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number).
`take` takes **no arguments** at all, and an argument to it raises.

**Only `use` carries modifiers, and that is the wire rather than a style.** `take`, `drop` and `transfer`
have no modifier field in them: on a real click the modifier keys select the *count* — shift transfers
one, ctrl drops one — so `n` states that directly and is the whole of it. Do not look for a `mods` beside
it.

All four raise on a **stale** item, and send nothing: an item that moved, was used or was consumed is not
the item that took its place. Re-read the container and retry.

Applying what you are carrying **onto** an item is the cursor's verb, not the item's:
[`s:player():hand():use(item)`](../player.md#the-hand). On an arbitrary item that gesture would name
whatever happens to be on the cursor rather than the receiver, which is why it lives on the hand.

## See also

- [what an item holds](contents.md) — `item:contents()`, and the `Contents` object it answers with
- [`Item`](../types/items.md#item) — the snapshot `:info()` hands back
- [`session:player`](../player.md#the-hand) — the cursor: what it carries, and applying it to something
- [widget](widget.md) — the object `:items()` is a method on
- [replace](replace.md#watching-for-a-widget) — waiting for a container to open in the first place
- [events](../event/bus/character.md#character-and-status) — `EquipChanged` and the other global lists
