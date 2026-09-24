# hafen.ui: The Widget Object

A `Widget` is one piece of the UI: a window, a button, a container, a label, or a surface you built. It is the one type every `hafen.ui` door returns.

```lua
local inventory = hafen.session():current():ui():inventory()
if inventory then
  hafen.log():write(inventory:type() .. " holds " .. inventory:items():count() .. " items")
end
```

---

## Getting a Widget

| Expression | Returns | Permission |
|---|---|---|
| `hafen.ui():window()`, `hafen.ui():widget()` | A surface you [paint](custom.md): owned, in [the addon layer](custom.md#your-windows-live-in-the-layer). | Unprotected |
| A [control builder](controls/README.md#builders) (`:button()`, `:label()`, `:image()`, …) | A [control](controls/README.md) you built: owned, drawn by the client. | Unprotected |
| `session:ui():match(selector)` | The one widget matching a [selector](selectors.md) in that character's tree, or `nil`. [two or more raise](selectors.md#one-or-all-of-them). | Unprotected |
| `session:ui():matchAll(selector)` | Every match, in tree order. An empty array, never `nil`. | Unprotected |
| `widget:match(selector)`, `widget:matchAll(selector)` | The same two, scoped to one widget's subtree — [searching inside one widget](#searching-inside-one-widget). | Unprotected |
| `session:ui():root()` | The top of that character's tree. | Unprotected |
| `session:ui():node(id)` | The widget for a server widget id in that character's tree, or `nil`. | Unprotected |
| `session:ui():inventory()` | That character's main backpack grid. | Unprotected |
| `session:ui():equipment()` | That character's worn-equipment grid. | Unprotected |
| [`session:player():hand()`](../player.md#the-hand) | That character's cursor and the [`Item`](items.md#the-item-object) on it. | Unprotected |
| `hafen.ui():hit(x, y)` | The deepest widget under a root-coordinate point — [hit-testing](selectors.md#hit-testing). | Unprotected |
| `hafen.ui():tipAt(x, y)` | The widget whose tooltip the client shows at that point, or `nil` — [tooltips](#tooltips-and-focus). | Unprotected |
| `hafen.ui():mouse()` | The pointer, which is not a Widget — [mouse](mouse.md). | Unprotected |

`session` is a [Session](../session.md): `hafen.session():current()` for the character on screen, `hafen.session():get(user)` for any other. A widget the client put up belongs to one character. A session nobody is looking at keeps its whole tree, so its windows stay findable from another character, and a [mirror](mirror.md) shows any widget of it. What you built is in no character's tree: hold the handle the builder gave you.

### Identity

A Widget is opaque userdata, interned per addon. Two lookups of one live widget are the same Lua value. `==` is the identity test, a Widget works as a table key, and a handle kept across frames compares next frame. Holding a handle keeps nothing alive.

```lua
local mouse, session = hafen.ui():mouse(), hafen.session():current()
assert(hafen.ui():hit(mouse:x(), mouse:y()) == hafen.ui():hit(mouse:x(), mouse:y()))   -- one object
assert(session:ui():inventory() == session:ui():node(session:ui():inventory():id()))   -- one widget, one object
```

### Staleness

A widget that left the tree (window closed, server destroy, relog) is stale. Every read answers `nil` or empty. Every client-side write is a no-op that still chains. `:exists()` is `false`.

| Case | Behaviour |
|---|---|
| A stale Widget passed as an argument (`widget:parent(p)`, `:draggable(h)`, `:resizable(h)`, `:replace(view)`, a rule's [`anchor{ to = w }`](style/geometry.md#anchor)) | Nothing is placed, armed or installed. The call chains. The matching read answers `nil`. |
| A value that is not a Widget passed where one is expected | Raises, naming what a Widget is. |
| A stale receiver on `widget:send(msg, ...)` | Raises: there is no tree to deliver into. |
| A stale receiver on `widget:on(key, fn)` | Raises, naming the missing tree. |
| A stale receiver on `widget:match(sel)`, `widget:matchAll(sel)` | Raises: an empty answer would read as "no match". |
| A stale receiver on `widget:overlay():add(key)` | Raises: nothing is left to draw over. |

---

## Read Methods

Every method answers on every widget, owned or borrowed, and none throws.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:type()` | `string` | Unprotected | Class name (`"Inventory"`, `"Label"`). For an anonymous subclass, the nearest named superclass. |
| `:role()` | `string \| nil` | Unprotected | What it is in the [selector vocabulary](selectors.md#roles). `nil` when nothing classifies it. |
| `:res()` | `string \| nil` | Unprotected | Its [resource name](selectors.md#what-carries-a-res), e.g. `"gfx/invobjs/torch"`. On a building site's material box (`@ISBox`) the material it counts. `nil` for most widgets. |
| `:picture()` | `string \| nil` | Unprotected | The resource name of the picture it shows, e.g. `"gfx/hud/wnd/lg/cbtnu"` on a window's close button. `nil` where it shows none. [A different read from `:res()`](selectors.md#the-picture-is-a-different-read). |
| `:id()` | `number \| nil` | Unprotected | Server widget id. `nil` when the widget is not server-bound. |
| `:session()` | [`Session`](../session.md) `\| nil` | Unprotected | The character whose tree it stands in. `nil` for one in the addon layer. |
| `:events()` | `string[]` | Unprotected | The [event keys](#subscribing) this widget answers. |
| `:name()` | `string \| nil` | Unprotected | The name the addon that built it gave it, as `<addon>/<name>` — [naming your own](custom.md#naming-and-dressing-your-own-surfaces). |
| `:stock()` | `table \| nil` | Unprotected | What a widget you built declared its own look to be — [the same page](custom.md#naming-and-dressing-your-own-surfaces). |
| `:owned()` | `boolean` | Unprotected | Whether your addon built it — [owned vs borrowed](writes.md#owned-vs-borrowed). |
| `:is(selector)` | `boolean` | Unprotected | Whether this widget matches the [selector](selectors.md). |
| `:children()` | [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) | Unprotected | Child Widgets in tree order. Empty for a leaf. `:list()` is the array. A child has no key, so there is no `:get`. |
| `:parent()` | `Widget \| nil` | Unprotected | The enclosing widget. `nil` at the root, and `nil` inside the [Kin window](../kin.md#the-row-is-one-way). |
| `:position()` | `{x=, y=}` | Unprotected | Position within the parent, in [design pixels](pixels.md). [`:position(x, y)` moves it](native.md). |
| `:size()` | `{w=, h=}` | Unprotected | The box `:size(w, h)` writes, in [design pixels](pixels.md): a window's content area, any other widget's whole box. The frame's own box is `:chrome().frame`. `.x` on a size [raises](../shapes.md#the-anonymous-shapes). |
| `:visible()` | `boolean` | Unprotected | Whether it is drawn. [`:visible(b)` writes it](native.md). |
| `:enabled()` | `boolean` | Unprotected | Its own input flag ([`:enabled(b)`](writes.md#enabled-and-disabled)). `true` when built, `true` on every client widget. `true` on a child of a disabled column, where the column reads `false`. |
| `:draggable()` | `Widget \| nil` | Unprotected | The handle your addon armed for the user to drag it by — [`:draggable(h)`](native.md#letting-the-user-drag-it-unprotected). |
| `:resizable()` | `boolean \| Widget \| nil` | Unprotected | `true` while the client's grip is on a window of yours, else the handle your addon armed, else `nil` — [`:resizable(h)`](native.md#letting-the-user-resize-it-unprotected). |
| `:remember()` | `string \| nil` | Unprotected | The name your addon keeps its place and box under — [`:remember(name)`](native.md#remembering-where-the-user-put-it-unprotected). |
| `:text()` | `string \| nil` | Unprotected | Best-effort text of a text-bearing widget (Label, Button, CheckBox, Window, TextEntry, an `@ISBox`'s `have/total` figure). `:text(s)` writes it on [a control you built](controls/README.md#setters) or [one of the client's](edit.md#what-a-window-says). |
| `:tooltip()` | `string \| nil` | Unprotected | The line shown when the pointer rests on it — [tooltips](#tooltips-and-focus). |
| `:focused()` | `boolean` | Unprotected | Whether a keystroke reaches this widget — [focus](#tooltips-and-focus). |
| `:image()` | `table \| nil` | Unprotected | The faces of a [control](controls/interactive.md#a-caption-or-a-picture) that shows pictures, `{up=, down=, hover=}`. `nil` otherwise. |
| `:value()` | `any \| nil` | Unprotected | What a [control](controls/README.md#setters) holds, the client's own included. `nil` where it holds nothing. `:value(v)` writes it on [one you built](controls/README.md#setters) and, protected, on [one of the client's](edit.md#driving-one-protected). |
| `:source()` | `string \| userdata \| nil` | Unprotected | The picture a [picture control](controls/display.md#picture) shows. `nil` before one is set. |
| `:rows()` | `table \| nil` | Unprotected | The row source of a [radio](controls/interactive.md#radio) or a [row-source control](lists.md). On one of the client's lists, [every row it holds](lists.md#reading-one). `nil` where a control has no rows. |
| `:range()` | `{min=, max=} \| nil` | Unprotected | The bounds of a [slider or scrollbar](controls/interactive.md#slider). |
| `:gap()` | `number \| nil` | Unprotected | The room between the children of a [column or row](column.md), in design pixels. `nil` elsewhere. |
| `:rowHeight()` | `number \| nil` | Unprotected | The row height of a [listbox, dropdown, menu or table](lists.md), in design pixels. |
| `:cellSize()` | `{w=, h=} \| nil` | Unprotected | The cell box of a [grid](lists.md#grid), in design pixels. |
| `:columns()` | `table \| nil` | Unprotected | The column descriptors of a [table](lists.md#table). |
| `:item()` | [`Item`](items.md#the-item-object) `\| nil` | Unprotected | The item an icon draws — a container's cell, the cursor, a recipe slot, a listing a resource paints. `nil` on every other widget. |
| `:group()` | `number \| nil` | Unprotected | The group a list row draws its name in, `0..254`. A kin roster row answers the kin's own group ([`kin:group()`](../kin.md)). A village's or realm's member row answers that polity's group for them, also for a member the roster does not know (`???`). `nil` on every other widget and on a polity with no groups. |
| `:row()` | [`Row`](lists.md#reading-one) `\| nil` | Unprotected | On a row widget of one of the client's lists: the row it draws. `nil` on every other widget. |
| `:search()` | `string \| nil` | Unprotected | What one of the client's search lists is filtered by. `nil` while no search runs, and on every other widget. [`:search(text)` filters it](lists.md#searching-one). |
| `:items()` | [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of [`Item`](items.md#the-item-object) | Unprotected | The items inside it — [items](items.md). |
| `:exists()` | `boolean` | Unprotected | Whether it is still in the tree. |
| `:info()` | `table \| nil` | Unprotected | Snapshot `{type, role, res, id, pos, size, visible, enabled, text, owned}`. Absent values are unset keys. `nil` once stale. |
| `:walk(fn)` | `self` | Unprotected | Depth-first visit, `fn(widget, depth)`. Return `false` to prune that subtree. |
| `:hit(coord)` | `Widget \| nil` | Unprotected | The deepest widget under a `{x=, y=}` root-coordinate point within this subtree. |
| `:rootPos()` | `{x=, y=} \| nil` | Unprotected | Its top-left in root coordinates. With `:size()` (a window: `:chrome().frame`) it gives the rectangle outlining it. |
| `:replacement()` | `Widget \| nil` | Unprotected | The view you put in place of this widget's window — [replace](replace.md). |
| `:chrome()` | `table \| nil` | Unprotected | On a window wearing the client's decoration: `frame = {w=, h=}` (the outer box) and `content = {x=, y=, w=, h=}` (the content area, from the frame's top-left), always. `caption = {x=, y=}`, `plate = {x=, y=, w=, h=, styled=}`, `sizer = {x=, y=}`, `close = {x=, y=, w=, h=}` once each [ornament](style/chrome.md#ornaments) has drawn. `nil` on anything else. |
| `:overlay()` | collection | Unprotected | What your addon [draws over this widget](overlay.md#over-one-widget): keyed painters and labels, clipped to its box, ending with it. |
| `:style()` | `table \| nil` | Unprotected | The style this widget [resolves to](style/README.md#the-cascade). `nil` when nothing names it. |
| `:rule()` | `Rule` | Unprotected | Your own [level of the cascade](style/README.md#restyle-one-widget) on this widget: setters, `:info()`, `:release()`. |

Reading the tree is unprotected client data. `:id()` is what makes a widget bound: a server-placed widget has one, a widget you built does not, and only a bound widget can [`:send`](#send-a-message-protected).

```lua
hafen.session():current():ui():root():walk(function(node, depth)
  hafen.log():write(string.rep("  ", depth) .. node:type()
    .. (node:role() and (" [" .. node:role() .. "]") or "")
    .. (node:id()   and (" #" .. node:id())          or "")
    .. (node:text() and (" '" .. node:text() .. "'") or ""))
end)
```

---

## Searching inside one widget

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:match(selector)` | `Widget \| nil` | Unprotected | The one match inside this widget's subtree, itself included. `nil` for none. Two or more raise. |
| `:matchAll(selector)` | `Widget[]` | Unprotected | Every match inside it, in tree order. Empty, never `nil`. |

The scope decides the candidates. The selector is matched against the whole tree, so an ancestor step may name a widget above the scope. The contract is stated once, under [inside one widget](selectors.md#inside-one-widget).

---

## Subscribing

Any widget — built, found by selector, or handed to you by an event — answers `:on(key, fn)`. The call returns a subscription, ended with `:off()`, and never sits mid-chain.

```lua
local subscription = hafen.session():current():ui()
  :match("window[title=Cupboard] inventory"):on("MouseDown", function(event)
  if event:button() == 3 then event:preventDefault() end    -- right-click disabled on this cupboard only
end)
subscription:off()
```

| Key | `event` answers | Cancelable | Fires |
|---|---|---|---|
| `MouseDown` | `:x()` `:y()` `:button()` `:preventDefault()` | Yes | A mouse button is pressed over it. |
| `MouseUp` | `:x()` `:y()` `:button()` `:preventDefault()` | Yes | A mouse button is released over it. |
| `MouseMove` | `:x()` `:y()` `:preventDefault()` | Yes | The pointer moves over it. |
| `Wheel` | `:x()` `:y()` `:amount()` `:preventDefault()` | Yes | The wheel turns over it. |
| `Removed` | — | No | It leaves the tree on its own. See below for descendants. |
| `Dragged` | `:x()` `:y()` | No | The user finished [dragging it](native.md#knowing-when-one-was-dragged) by a handle you armed. |
| `Resized` | `:w()` `:h()` | No | The user finished [resizing it](native.md#knowing-when-one-was-resized) by a handle you armed or by the [client's grip](custom.md#letting-the-user-resize-a-window-of-yours). |

| Rule | Detail |
|---|---|
| Coordinates | `event:x()`/`:y()` are widget-local [design pixels](pixels.md). On a window you painted, local means the content area ([where a press lands](custom.md#where-a-press-lands)). On a client window, its own box, caption included. |
| Button and wheel | `event:button()` is 1 (left) or 3 (right), on `MouseDown`/`MouseUp` only. `event:amount()` is the wheel delta. |
| Cancelling | `event:preventDefault()` stops the input reaching the widget's own handling and any child under it. No handler's return value is read. Two handlers fire independently. Either cancelling cancels, both still run. |
| Controls | A control answers these keys plus its own capability keys (`Pressed`, `Changed`, `Submitted`, `Selected`, `Cell`) — [controls](controls/README.md). On a client control the capability key is cancelable ([edit](edit.md)), except a borrowed slider's or scrollbar's `Changed`, where `preventDefault()` raises: the value has already moved. A client search list also answers [`Search`](lists.md#searching-one), once per row as it searches. |
| Surfaces | A surface you paint answers `Draw`, `Update`, `Drop` and `Close` on top — [custom](custom.md#subscribing). |
| Unknown key | Raises, listing the keys this widget answers: `a Label has no event 'Pressed' — it has: MouseDown, MouseUp, MouseMove, Wheel, Removed, Dragged, Resized`. |
| `Removed` scope | Fires for the widget that was destroyed, not for what was inside it: a control that dies with its window fires nothing. Subscribe on the window. `widget:exists()` answers for any widget at any moment. |
| Release | A subscription on a client widget ends on `:reload`, on disable, or with [`widget:revert()`](edit.md#taking-the-whole-edit-back) on the widget or an ancestor. The client's widget survives untouched. |

---

## Owned vs borrowed

A widget is owned when your addon created it: a [surface](custom.md), a [column](column.md), a [control](controls/README.md). It is borrowed otherwise: a client widget or another addon's. Every read answers on both. Which writes answer on which is one table on [writes](writes.md#owned-vs-borrowed). [disabling a widget](writes.md#enabled-and-disabled) is the one write with no borrowed half.

---

## Send a message (protected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:send(msg, ...)` | `self` | `widget.send` | Sends a widget message from a server-bound widget. `msg` is a string (a number is refused). Trailing arguments marshal as an [`action`](../event/streams.md#intercepting-an-outbound-action)'s `event:args()` reads them: `{x=, y=}` becomes a coordinate. Numbers, strings and booleans pass through. |

```lua
hafen.session():current():ui():match("@MapView"):send("click", {x = 0, y = 0}, {x = 0, y = 0}, 1, 0)
```

| Case | Result |
|---|---|
| Without `widget.send` in the manifest | Raises, naming the key. The gate runs before the arguments are read. |
| A widget your addon built (no server id) | Refused, naming that. `:id()` answers the same question beforehand. Send from the nearest bound ancestor instead. |
| A stale widget | Raises. Nothing is sent. |

The receiver is the target: there is no address argument. `widget.send` is the widest of the keys that reach the game server, any message the client itself could send. Only [`console.run`](../console.md#run-a-line-protected) is wider. Declare it only where no typed verb covers the message.

---

## Tooltips and focus

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:tooltip()` | `string \| nil` | Unprotected | The line shown when the pointer rests on it. A plain string as given, or the text of a client keybound tip (the shortcut appended is the keymap's), or `nil`. Answers on any widget. Never throws. |
| `widget:tooltip(text)` | `self` | Unprotected | Writes it on a control you built. `""` clears it. Markup follows [`g:text`](drawing.md#text): plain text takes the stock path. `$col[r,g,b]{…}`, `$b`, `$i`, `$u`, `$size`, `$font` render as the client's own button tips do (wrapped, keybinding appended). Malformed markup is shown literally. `:tooltip()` reads the string as written. |
| `hafen.ui():tipAt(x, y)` | `Widget \| nil` | Unprotected | The widget whose tooltip the client shows at a root-coordinate point. A tooltip is inherited from the nearest ancestor carrying one, so this is not always `hafen.ui():hit(x, y)`. Both resolve [panels standing in the 3D world](../virtual/widgets.md). |
| `widget:focused()` | `boolean` | Unprotected | Whether a keystroke reaches this widget. Focus is a path from the root: `true` for the text entry being typed into and for the window around it. Read-only. An argument is refused. |

```lua
local mouse = hafen.ui():mouse()
local speaker = hafen.ui():tipAt(mouse:x(), mouse:y())
if speaker then hafen.log():write(speaker:tooltip()) end
```

---

## See Also

- [Pixels](pixels.md) — the unit of every coordinate and size.
- [Mouse](mouse.md) — the pointer, what is under it, and the grab.
- [Writes](writes.md) — which writes answer on an owned and on a borrowed widget.
- [Controls](controls/README.md), [Lists](lists.md) — the client's controls, built by your addon.
- [Custom](custom.md) — a surface you paint and its four extra keys.
- [Selectors](selectors.md) — naming a widget.
- [Native](native.md), [Edit](edit.md), [Replace](replace.md) — changing the client's widgets.
- [Items](items.md), [Overlay](overlay.md), [Style](style/README.md) — `:items()`, `:overlay()`, `:rule()` and `:style()`.
