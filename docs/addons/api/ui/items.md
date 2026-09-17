# hafen.ui: The Items the Client Draws

An item is a thing the client draws, found through the icon drawing it: `widget:item()` on the icon, `widget:items()` on the widget around it. A backpack's cells, the cursor, a recipe's slots and the food icon on a constipation row all hand the same [`Item`](#the-item-object) object.

```lua
local session = hafen.session():current()
for _, item in ipairs(session:ui():inventory():items():list()) do
  hafen.log():write((item:name() or item:res() or "?") .. " x" .. (item:quantity() or 1))
end

local hand = session:player():hand()                  -- the cursor, or nil while it is empty
local carried = hand and hand:item()
```

A container belongs to one character and is reached through that character's [session](../session.md). A session nobody is looking at answers as well as the one on screen. Reading is unprotected.

---

## Read Methods

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:items()` | collection of [`Item`](#the-item-object) | Unprotected | Everything the widget draws, in draw order, searched deep. A window answers for the grid inside it, a crafting window for its recipe's input and output slots. `:list()` is the array. Empty, never `nil`, for a widget drawing nothing or a stale one. |
| `widget:item()` | `Item \| nil` | Unprotected | The item one icon draws. The icon is a container's cell, the cursor, a recipe slot or a constipation row's food icon. Or it is a listing a resource ships its own widget for. `nil` on every other widget. The [`item` role](selectors.md#roles) is the same test. |
| [`session:player():hand():item()`](../player.md#the-hand) | `Item \| nil` | Unprotected | The item on that character's cursor. |

| Rule | Detail |
|---|---|
| Each thing appears once | The equipment window draws a worn item in every slot it fills. `item:slots()` names them all, so a two-slot piece of gear is one entry. A slot with no display name is listed by its identifier. An empty `:slots()` means not worn. |
| Quality inputs and tools are not items | The crafting window prints them as bare pictures, so `:items()` lists none. What a recipe asks for is [`s:craft()`](../craft.md#a-spec). |
| No `find` | It is a one-liner over `:items()`. |

---

## The Item object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `item:res()` | `string \| nil` | Unprotected | Resource name, the item's stable identity. |
| `item:name()` | `string \| nil` | Unprotected | Display name, once the tooltip has resolved. |
| `item:quantity()` | `number \| nil` | Unprotected | [The count on the icon](#the-numbers-on-an-icon). `nil` for one showing none. |
| `item:progress()` | `number \| nil` | Unprotected | [The arc over the icon](#the-numbers-on-an-icon), `0..1`. `nil` for one painting none. |
| `item:durability()` | `{cur, max} \| nil` | Unprotected | [The two counts its wear row prints](#durability-the-counts-a-wear-row-prints). |
| `item:quality()` | `number \| nil` | Unprotected | The quality the tooltip shows. |
| `item:contents()` | [`Contents`](contents.md) `\| nil` | Unprotected | What it holds. `nil` for an item holding nothing. |
| `item:container()` | `Item \| nil` | Unprotected | The item it sits inside. `nil` for one in a container widget. |
| `item:cell()` | `{x, y} \| nil` | Unprotected | The 1-based grid cell it sits in — a place, where a grid control's [`:cellSize()`](widget.md#read-methods) is a size. |
| `item:slots()` | `string[]` | Unprotected | The equipment slots it fills, by name. Empty for anything not worn. |
| `item:handle()` | `number \| nil` | Unprotected | Its server widget id, the number it is addressed by on the wire. `nil` once gone. |
| `item:exists()` | `boolean` | Unprotected | Whether the icon drawing it is still in the tree. |
| `item:info()` | `table` | Unprotected | The [snapshot](../types/items.md#item): every read above in one table. |
| `item:on("Changed", fn)` | [`Sub`](../event/README.md#subscribe) | Unprotected | [What the item says about itself resolved or was revised](#an-item-arrives-before-it-can-be-described). |

| Rule | Detail |
|---|---|
| Interned | `==` is identity. Two reads of one icon, and the icon's `:item()` and the container's `:items()` entry, are one object. Keyed on the thing drawn, never on `:handle()`, which the server reuses. |
| Stale, not something else | An item that moved, was eaten or consumed still answers `:res()`, `:name()` and `:quantity()`, while `:exists()` is `false` and `:cell()`, `:slots()` and `:handle()` are empty. The [`ItemRemoved`](container.md) payload is a stale item, and the only thing that says what left. |

### A depiction that is not an item

A depiction is painted from a resource with nothing on the wire behind it. That is a recipe slot, a price on a listing, an icon resource code puts up. It is an `Item` reading `:res()`, `:name()`, `:quality()`, `:durability()` and `:info()` off the same tooltip mechanism, and it answers nothing about where it is.

| Read | On a depiction |
|---|---|
| `:cell()`, `:handle()`, `:container()`, `:contents()` | `nil`: drawn where its icon is, no server widget, nothing holding it, nothing inside. |
| `:slots()` | Empty. |
| `:exists()` | Its icon's: `true` while the widget drawing it is in the tree. |
| `:quantity()`, `:progress()` | What the tooltip publishes, else `nil`. |
| The [protected verbs](#write-protected) | Refused, naming why. Nothing is sent. Guard on `:handle()`. |

```lua
local function label(icon)
  local item = icon:item()
  if item == nil then return nil end
  local name = item:name() or item:res() or "?"
  if item:handle() == nil then return name .. " (drawn, not held)" end
  return name .. " #" .. item:handle()
end
```

---

## An item arrives before it can be described

The server sends the icon first and the tooltip after. The code that reads a quality, a wear row or a contents block out of it ships in a resource that may still be loading. Until both landed, `:name()`, `:quality()`, `:durability()`, `:contents()`, `:quantity()` and `:progress()` answer `nil`. `item:on("Changed", fn)` fires when the tooltip resolves, and again whenever the server revises it.

```lua
local face = hafen.font():get("serif"):derive():size(11)

hafen.session():current():ui():on("item", "Added", function(icon)     -- every item icon, wherever drawn
  local item = icon:item()
  if item == nil then return end
  local quality_label = icon:overlay():add("q"):anchor(0.5, 1):offset(0, -1):color{255, 230, 140}:font(face)
  local function show()
    local quality = item:quality()
    if quality then quality_label:text(tostring(math.floor(quality + 0.5))) end
  end
  show()                                              -- if already known, now
  item:on("Changed", show)                            -- and the moment it is
end)
```

| Rule | Detail |
|---|---|
| One key | The server resends the whole tooltip, not the field that changed. Read what you need in the handler. |
| The payload is the item | The same object you subscribed on, so one handler serves several items. |
| On the [step](../threading.md) | The first frame after the client builds the description, which the draw of its icon or your own read causes. |
| A stale item never fires | Subscribing to one is legal and inert. The subscription is per item and ends with it, or with `sub:off()`. |
| A recipe slot is described on arrival | The client works out what a slot names while building the icon. It arrives with `:name()` and `:quality()` answering, and nothing fires behind them. Read first, subscribe second. |
| A change of font is not a change of item | Restyling re-renders every tooltip. Nothing fires. |
| Arriving and leaving | Are the container's [`ItemAdded`/`ItemRemoved`](container.md), not this key. |

---

## The numbers on an icon

An icon carries up to a count in the corner and an arc round the middle. Each has two sources, a server-written field and the item's own tooltip, folded in the same order by `:quantity()` and `:progress()`. If it is on the icon, the verb answers it. An item showing neither, or with an unresolved tooltip, answers `nil` to both.

| Read | Meaning |
|---|---|
| `:quantity()` | Usually how many this one item is: a counted item (`42 seeds of Hemp`) holds nothing and this is its only count. A stack draws what is inside it, agreeing with `item:contents():items():count()`. The item's own code decides what the number is — gildable gear draws its gilding count, `0` included — so read it beside `:res()`. |
| `:progress()` | A fraction with no units. The client paints a wedge and can say how far round it went, for a craft in flight as readily as for wear. Not the [progress bar](controls/display.md#progress-bar) control, which shares the name only. |

## Durability: the counts a wear row prints

`item:durability()` answers `{cur, max}` as the wear row states them, `cur` first, drawn in red once `cur` reaches `max`. `nil` for gear that never wears and for an unresolved tooltip. It is not the arc: `:progress()` is a unitless fraction, these are two absolute counts, and an item may answer both, one or neither. What one point of wear costs is the server's. Compare the numbers against themselves on one item.

```lua
for _, item in ipairs(hafen.session():current():ui():equipment():items():list()) do
  local durability = item:durability()
  if durability then
    hafen.log():write((item:name() or "?") .. ": worn " .. durability.cur .. " of " .. durability.max)
  end
end
```

---

## Write (protected)

Each verb sends exactly what the matching click sends and hands the Item back, so a run of verbs chains. Each needs its key declared in your manifest, or the group `item.*`, and raises naming the key otherwise.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `item:use([mods])` | `self` | `item.use` | Its default right-click action: eat, open, light. `mods` defaults to `0`: Shift `1`, Ctrl `2`, Alt `4`, added. |
| `item:take()` | `self` | `item.take` | Picks it up onto the cursor, or unequips a worn item. Takes no arguments. One raises. |
| `item:drop([n])` | `self` | `item.drop` | Drops it on the ground. |
| `item:transfer([n])` | `self` | `item.transfer` | Moves it to the linked container, or to your inventory. |

```lua
local first = hafen.session():current():ui():inventory():items():list()[1]
if first then first:take() end
```

| Rule | Detail |
|---|---|
| `n` | How many of a stack to move. Defaults to `-1`, all of it. Only `use` carries modifiers: on a real click the modifier selects the count, which `n` states directly. |
| Types | A value that is not a number raises naming the verb and parameter. A numeric string is [still a string](../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). |
| A stale item | Every protected verb raises and sends nothing. Re-read the container. A depiction is refused the same way. |
| Applying the cursor onto an item | The hand's verb, [`s:player():hand():use(item)`](../player.md#the-hand). |

---

## See Also

- [Contents](contents.md) — `item:contents()` and the `Contents` object.
- [`Item`](../types/items.md#item) — the snapshot `:info()` hands back.
- [`session:player`](../player.md#the-hand) — the cursor: what it carries, and applying it.
- [Widget](widget.md) — the object `:items()` is a method on.
- [Replace](replace.md#watching-for-a-widget) — waiting for a container to open.
- [Events](../event/bus/character.md#character-and-status) — `EquipChanged` and the other lists.
