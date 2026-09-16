# hafen.ui: Selectors

A selector is a CSS-shaped string naming a piece of the client's UI; `session:ui():match` and `:matchAll` are the lookups, and the same string is the key of a [stylesheet](style/keys.md) rule.

```lua
local session = hafen.session():current()
session:ui():match("window[title=Cupboard]")                 -- the one match, or nil
session:ui():matchAll("inventory")                           -- every open container, in tree order
session:ui():match("window[title=Cupboard] inventory")       -- the grid inside that window
session:ui():match("window[title=Foo] button[text=Close]")   -- one exact widget, in one string
session:ui():match("@Equipory")                              -- by class
session:ui():matchAll("*[res*=gfx/hud/meter]")               -- by resource name, as a substring
session:ui():root()                                          -- no selector: the whole tree
```

A lookup searches one character's tree, so it is addressed at that character's [session](../session.md): `hafen.session():current()` for the one on screen, `hafen.session():get(user)` for any other, whose tree answers the same searches whether or not it is drawn. Windows your addon builds stand in the addon layer and match no selector; hold [the builder's](custom.md) handle.

---

## Lookups

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:ui():match(selector)` | [`Widget`](widget.md) `\| nil` | Unprotected | The one match in that character's tree. No match is `nil`; two or more raise, saying how many matched and naming `matchAll(selector)[i]`. |
| `session:ui():matchAll(selector)` | `Widget[]` | Unprotected | Every match, in tree order; empty, never `nil`. |
| `session:ui():root()` | `Widget \| nil` | Unprotected | The top of that character's tree. |
| `widget:match(selector)`, `widget:matchAll(selector)` | as above | Unprotected | The same search over `widget`'s own subtree, `widget` included — [below](#inside-one-widget). |

### One, or all of them

`:match` is for a selector that identifies one widget: a chain down to an exact button, a unique caption, a class only one widget has. A selector naming several today may name one on your screen — "the Cupboard window" is one widget until a second cupboard opens — so a lookup that picked whichever came first would act on the wrong one; the raise is the answer instead. `:matchAll` is for a question with several answers.

### Inside one widget

`widget:match(sel)` and `widget:matchAll(sel)` run the same grammar with the same strictness over one widget's subtree. The scope decides the candidates; an ancestor step may still name a widget above it, as in CSS. Inside an [`Added` callback](replace.md#watching-for-a-widget) it is the right lookup: the whole tree has no single answer to `window[title=Cupboard] inventory` while two cupboards are open, and the widget you were handed does.

```lua
session:ui():on("window[title=Cupboard]", "Added", function(cupboard)
  local grid = cupboard:match("inventory")       -- this cupboard's grid, whatever else is open
end)
```

Both refuse on a widget that has left the tree: "nothing matched" and "the thing searched is gone" are different answers. Ask [`:exists()`](widget.md#read-methods) first when unsure.

---

## The grammar

A selector is one or more steps separated by spaces; a step is a role or `*`, refiners, or both, in any order, each refiner at most once. Refiners alone are a step (`@Equipory`, `[res*=gfx/hud]`).

| Part | Matches |
|---|---|
| `*` | Any widget, including one no role classifies. |
| A role | What the widget is — [roles](#roles). |
| `@Class` | Its class name, the string `:type()` reports: the nearest named class, so `@Window` matches a plain `Window` and not a `CharWnd`; use the `window` role for any window. |
| `[title=…]` | A window's own caption; valid only in a step whose role is `window`. |
| `[text=…]` | The words a widget displays: a button's caption, a label, a checkbox's label, an entry's contents. Refused on a `window` step, and never matches a window under `*`. |
| `[res=…]` | Its resource name, `:res()` — [what carries one](#what-carries-a-res). |
| `[name=…]` | What the addon that built it calls it, as `<addon>/<name>` — [below](#the-one-refiner-an-addon-owns). |

| Operator | Matches when the value |
|---|---|
| `=` | Is exactly this. |
| `*=` | Contains this. |
| `^=` | Starts with this. |
| `$=` | Ends with this. |

| Rule | Detail |
|---|---|
| The space | The descendant combinator: `window[title=Cupboard] inventory` is the grid anywhere below the Cupboard window. It is the only part that looks upward; every refiner tests its own step. There is no `>`. |
| Where `[title=]` goes | The client wraps bare widgets in titled windows, so the grid is `window[title=Cupboard] inventory`. `inventory[title=Cupboard]` is refused and the error hands back that spelling; under CSS it would parse and never match. |
| Captions | Match the client's own English whatever it displays: a [catalogue](../locale.md) lands at the render, so `[title=Inventory]` matches a window drawn in another language. |
| Errors | Anything else raises naming the offending part, listing every valid role for a bad one; the same error comes back for an invalid [sheet key](style/keys.md). |

### The one refiner an addon owns

Every other part describes what a widget happens to be; `[name=…]` is what an addon called a widget it built, through [`:name(word)`](custom.md#naming-and-dressing-your-own-surfaces). Without it every bare surface reports `@AddonWidget` and nothing tells one addon's bar from another's meter.

```lua
local bar = hafen.ui():widget():parent(hud):name("bar")      -- in the addon, once
```

```json
"[name=actionbars/bar]":   { "bg": {"color": [9, 13, 22, 214]} },
"[name^=actionbars/slot]": { "bg": {"asset": "themes/cyberpunk/slot.png"} }
```

| Rule | Detail |
|---|---|
| The addon's id is written in front | `:name("bar")` is `[name=actionbars/bar]`; two addons naming a bar cannot collide. |
| Weight 16 | Above `[res=]`'s 8: the only part an author chose. `[name^=…]` and `[name=…]` weigh the same and a JSON theme has no key order, so an exception is a chain, which sums: `[name=actionbars/bar] [name=actionbars/slot7]` is 32. |
| Operators | Every one, so `slot1`…`slot12` are dressed as a group by `^=` and one of them by `=`. Naming twelve surfaces the same is legal and buys nothing the prefix does not. |
| Written once | A name is identity, not state; the states a surface enters ride [inside a rule's value](style/chrome.md#a-face-per-state). |
| Owned only | Naming a client widget is refused, naming [`widget:rule()`](style/README.md#restyle-one-widget). A name nobody answers to matches nothing and is not an error. |

---

## Roles

`:role()` answers what a widget is, or `nil`. The names are the stylesheet's [site keys](style/keys.md): every site key is a role, and `item`, `column` and `row` classify a widget and name no site. `hafen.ui():role()` is the collection of every role the client publishes, each answering `:name()` and `:selector()` — the same string for a role that matches a widget, `nil` for a site role.

| Role | Matches |
|---|---|
| `window` | `Window` and every subclass, the `Hidewnd` around the inventory included. |
| `inventory` | `Inventory` and `Equipory`: every open container. |
| `button` | `Button`, `IButton`. |
| `label` | `Label`. |
| `textentry` | `TextEntry`. |
| `chat` | `ChatUI` and its channels. |
| `menu` | `MenuGrid`, `FlowerMenu`. |
| `item` | The icon one item is drawn as, wherever: a container's cell, an equipment slot, the cursor's item, a recipe's input or output slot, an icon a resource ships its own widget for. Exactly the widgets [`widget:item()`](widget.md#read-methods) answers on. |
| `column`, `row` | A [column or row](column.md) an addon built. |

| Rule | Detail |
|---|---|
| Site roles | `window.title`, `window.frame`, `panel`, `heading`, `tooltip`, `inventory.slot`, `world.nick`, `world.speech`, the parts of a control (`checkbox`, `checkbox.mark`, `slider`, `slider.knob`, `scrollbar`, `scrollbar.knob`, `meter`), the chat kinds (`chat.mine`, `chat.private`, `chat.system`, …) and the HUD's furniture (`hud.belt`, `hud.menu.left`, `menu.slot`, `minimap.frame`, …) name a place the client draws, not a widget it places. `session:ui():match("checkbox")` is accepted and answers nothing; `role:selector()` is `nil` for exactly these. |
| No role | Most widgets: layout containers, scroll ports, images, a bare surface of yours. Reach them with `*`, `@Class`, `[res=]`, `[name=]`, or a chain on their window. |

---

## What carries a res

`[res=]` is the stable key: a resource name never changes with the client's language. Only some widgets have one — a container's cell (`gfx/invobjs/…`, its item's), meters (`gfx/hud/meter/hp`), a building site's material boxes (`@ISBox`, the material each counts), and widgets whose code ships in a resource (`ui/rchan`, `ui/vlg`). Most windows carry none: `[res=]` for items and meters, `[title=]` for windows; [`widget:res()`](widget.md#read-methods) tells you.

| Rule | Detail |
|---|---|
| A path wants `*=` | `[res*=gfx/hud/meter]` catches every meter; `[res=gfx/hud/meter]` matches nothing. |
| Depictions | An icon drawing a [depiction](items.md#a-depiction-that-is-not-an-item) carries the resource its own code came from, or none; refine those on the item, where [`widget:item():res()`](items.md#the-item-object) answers for every icon. |
| A `[res=]` rule lands within about twenty frames | A widget's resource arrives with no event, so a rule that did not match is re-asked for a bounded run of frames and then settles; a resource resolving later never starts matching for that widget. Installing the sheet again, or any edit to it, re-opens every answer. |

### The picture is a different read

`:res()` names the resource a widget's code came from; most of the client's chrome is a Java class showing a piece of the game's art, and [`widget:picture()`](widget.md#read-methods) names that art where `:res()` is `nil`. `:picture()` has no selector key.

```lua
local sample_window = hafen.ui():window():title("Sample")
local close_button = sample_window:match("@IButton")   -- the close box the client put in the chrome
close_button:picture()                                 -- "gfx/hud/wnd/lg/cbtnu"
close_button:res()                                     -- nil
```

`:picture()` answers on a widget that holds a picture — a [picture control](controls/display.md#picture), a picture button, a picture checkbox — and `nil` on one that composes or paints its art (an inventory square, a meter's bar) and for a picture out of your own [asset](../asset/README.md) file. Two widgets sharing one picture name one resource. Any argument raises.

---

## Hit-testing

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():hit(x, y)` | `Widget \| nil` | Unprotected | The deepest widget under a root design-pixel point. |
| [`hafen.ui():mouse():over()`](mouse.md) | `Widget \| nil` | Unprotected | The same, at the pointer. |

Both mirror the engine's pointer dispatch — children topmost-first, invisible widgets skipped, scroll offsets followed, non-rectangular hit areas honoured — so they return the widget a click would hit; a position-plus-size rectangle test is wrong inside scrolled lists. Walk [`:parent()`](widget.md#read-methods) up for the stack; `:rootPos()` and `:size()` give the outline.

```lua
local last                                             -- the leaf the stack was last built for
hafen.event():on("Update", function(delta)
  local leaf = hafen.ui():mouse():over()
  if leaf == last then return end                      -- hover unchanged: no walk, no rebuild
  last = leaf
  local stack, node = {}, leaf
  while node do stack[#stack + 1] = node; node = node:parent() end
end)
```

Widgets are interned, so the guard is a plain `==` and covers `nil == nil`. Hovering a window's frame resolves to its decoration (`@DefaultDeco`, role `nil`), a child widget of its own; the window is one level up, or `window[title=…]`.

## Hold the result

Every lookup walks its whole scope and parses the selector on every call, so hold the widget the lookup answered, not the string: widgets are interned, holding one is free, and `:exists()` says whether it is still there. There is no cache underneath.

## The inspector

The `widgetstack` addon reports the hovered widget's role, class, own `[title=]` or `[text=]`, `[res=]` and anchor (the nearest captioned window, as the first step of a chain), then every selector it can build from those parts and resolve back to the widget, most specific first, with how many widgets each matches. Chains come first, since they name one widget; `[res*=…]` on a path's last segment and the `^=` form of a value before its first digit are offered where `=` would not hold. The bottom line is ready for `:lua`: `hafen.session():current():ui():match("…")` when it matches this widget alone, `…:matchAll("…")[i]` otherwise. Under it, every [read](widget.md#read-methods) with something to say prints a line; a control's own configuration (`:range()`, `:rows()`, `:rowHeight()`, `:cellSize()`, `:columns()`, `:source()`, `:image()`) is the builder's and says nothing on a client control. Click a row or run `:selector` to log the line; the `freeze` hotkey holds the stack.

---

## See Also

- [Widget](widget.md) — what you read off the widget a selector found.
- [Style keys](style/keys.md) — the same selector as a rule's key.
- [Replace](replace.md#watching-for-a-widget) — waiting for a widget instead of polling.
- [Native](native.md) — moving, hiding and handing over what you named.
- [References](../references.md#selector-naming-a-piece-of-the-ui) — selectors among the other reference kinds.
