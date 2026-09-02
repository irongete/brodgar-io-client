# hafen.ui: the Widget object

A **Widget** is one piece of the UI — a window, a button, a container, a label, or something you built
yourself. It is the one type `hafen.ui` hands back, and every door below returns it.

```lua
local inv = hafen.session():current():ui():inventory()
if inv then hafen.log():write(inv:type() .. " holds " .. inv:items():count() .. " items") end
```

## Getting a Widget

| Expression | Returns |
|---|---|
| `hafen.ui():window()` / `hafen.ui():widget()` | a surface you [painted](custom.md) — owned, and in [the layer](custom.md#your-windows-live-in-the-layer) rather than in the client's tree |
| one of the [control builders](controls/README.md#builders) (`:button()`, `:label()`, `:image()`, …) | a [control](controls/README.md) you built — owned, and drawn by the client |
| `s:ui():match(selector)` | the **one** widget matching a [selector](selectors.md) in that character's tree, or `nil` — [two or more raises](selectors.md#one-or-all-of-them) |
| `s:ui():matchAll(selector)` | **every** match, in tree order — an empty array, never `nil` |
| `w:match(selector)` / `w:matchAll(selector)` | the same two, searched inside **one widget's** subtree — see [searching inside one widget](#searching-inside-one-widget) |
| `s:ui():root()` | the top of that character's tree; walk down to any window it has open |
| `s:ui():node(id)` | the widget for a **server widget id** in that character's tree, or `nil` if it does not resolve |
| `s:ui():inventory()` | that character's main backpack grid, a container like any other |
| `s:ui():equipment()` | that character's worn-equipment grid |
| [`s:player():hand()`](../player.md#the-hand) | that character's cursor, and the [`Item`](items.md#the-item-object) on it |
| `hafen.ui():hit(x, y)` | the **deepest** widget under a root-coord point — see [hit-testing](selectors.md#hit-testing) |
| `hafen.ui():tipAt(x, y)` | the widget whose **tooltip** the client would show at that point, or `nil` — see [tooltips](#tooltips-and-focus) |
| `hafen.ui():mouse()` | the pointer — not a Widget, see [the mouse](mouse.md) |

`s` is a [Session](../session.md): `hafen.session():current()` for the character on screen,
`hafen.session():get(user)` for any other. **A widget the client put up belongs to one character**, and a
session nobody is looking at keeps its whole tree, so its windows stay findable and readable from another
character. What you built is in none of those trees: hold the handle the builder gave you. The rows still
on `hafen.ui()` ask about the **screen**, and there is one.

A Widget is opaque, facade-safe userdata: no raw widget crosses into Lua and one cannot be forged. It is
**interned per addon**, so two lookups of the same live widget are the *same* Lua value:

```lua
local m, s = hafen.ui():mouse(), hafen.session():current()
hafen.ui():hit(m:x(), m:y()) == hafen.ui():hit(m:x(), m:y())   -- true
s:ui():inventory() == s:ui():node(invId)                     -- true: one widget, one object
```

`==` **is** the identity test, so there is no `:same()`. You can key a table by a Widget, stash one across
frames and compare it next frame. Holding one does not keep the widget or its dead subtree alive: it is a
view of engine state, not an owned resource, and there is nothing to tear down.

**Staleness.** A widget that leaves the tree — window closed, server destroy, relog — is *stale*: every
read answers `nil` or empty, every client-side write is a silent no-op that still chains, and `:exists()`,
the one read that always answers, is `false`. Ask it about the widget in your hand, on the line you ask it.

**A Widget you pass as an argument takes the same rule.** `w:parent(p)`, `w:draggable(h)`, `w:resizable(h)`,
`w:replace(view)` and a rule's [`anchor{ to = w }`](style/geometry.md#anchor) each take one, and each
**stops** when the widget you name has left the tree: nothing is placed, armed or installed, the call
chains, and the read beside it answers `nil`. No guard of yours could cover that — a widget an event handed
you may be destroyed on the client's own step, and no `:exists()` sits inside that instant — so the surface
answers it rather than the caller. A value that is **not** a Widget is a spelling mistake and raises, naming
what a Widget is.

A stale **receiver** raises wherever there is no honest `nil` to hand back:

- [`w:send(msg, ...)`](#send-a-message-protected) — no tree to deliver into, and nothing was sent
- [`w:on(key, fn)`](#subscribing) — no events left to check the key against, so its refusal names the
  missing tree rather than the key
- [`w:match(sel)` and `w:matchAll(sel)`](#searching-inside-one-widget) — no subtree to search, where an
  empty answer would read as "no match"
- [`w:overlay():add(key)`](overlay.md#over-one-widget) — nothing left to draw over

## Read

Every method below answers on every widget, owned or not, and none of them throws.

| Method | Returns | Description |
|---|---|---|
| `:type()` | string | class name, e.g. `"Inventory"`, `"Label"`; for an anonymous subclass, the nearest named superclass |
| `:role()` | string \| nil | what it **is** in the [selector vocabulary](selectors.md#roles), or `nil` when nothing classifies it |
| `:res()` | string \| nil | its [resource name](selectors.md#what-carries-a-res), e.g. `"gfx/invobjs/torch"`; `nil` for most widgets |
| `:picture()` | string \| nil | the resource name of the **picture it shows**, e.g. `"gfx/hud/wnd/lg/cbtnu"` on a window's close button; `nil` where it holds none — [a different read from `:res()`](selectors.md#the-picture-is-a-different-read) |
| `:id()` | int \| nil | server widget id, or `nil` when the widget is not server-bound |
| `:session()` | [Session](../session.md) \| nil | the character whose **tree** it stands in; `nil` for one in your own layer, which belongs to nobody |
| `:events()` | array | the [event keys](#subscribing) this widget answers, which is what `:on(key, fn)` refuses anything outside |
| `:name()` | string \| nil | the name the addon that **built** it gave it, as `<addon>/<name>`; `nil` for every widget nobody named — [naming your own](custom.md#naming-and-dressing-your-own-surfaces) |
| `:stock()` | table \| nil | what a widget **you built** declared its own look to be, or `nil` — [the same page](custom.md#naming-and-dressing-your-own-surfaces) |
| `:owned()` | boolean | whether **your** addon built it — see [owned vs borrowed](#owned-vs-borrowed) |
| `:is(sel)` | boolean | whether **this** widget matches that [selector](selectors.md) — the predicate, where [`:match(sel)`](#searching-inside-one-widget) searches below it |
| `:children()` | [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) | child Widgets in tree order; empty for a leaf. `:list()` is the array, and a child has no key, so there is no `:get` |
| `:parent()` | Widget \| nil | the enclosing widget, or `nil` at the root |
| `:position()` | `{x=, y=}` | position within the parent, in widget-local [design pixels](pixels.md) — [`:position(x, y)` moves it](native.md) |
| `:size()` | `{w=, h=}` | size, in [design pixels](pixels.md); for a window its **outer** box. `.x` on one [raises](../shapes.md#the-anonymous-shapes) |
| `:visible()` | boolean | whether it is visible — [`:visible(b)` writes it](native.md) |
| `:draggable()` | Widget \| nil | the handle **your** addon armed for the user to drag it by, or `nil` — [`:draggable(h)` arms it](native.md#letting-the-user-drag-it-unprotected) |
| `:resizable()` | Widget \| nil | the handle **your** addon armed for the user to resize it by, or `nil` — [`:resizable(h)` arms it](native.md#letting-the-user-resize-it-unprotected) |
| `:remember()` | string \| nil | the name **your** addon keeps its place and box under, or `nil` — [`:remember(name)` keeps them](native.md#remembering-where-the-user-put-it-unprotected) |
| `:text()` | string \| nil | best-effort text for text-bearing widgets (Label, Button, CheckBox, Window, TextEntry), else `nil` — `:text(s)` writes it, on [a control you built](controls/README.md#setters) or [one of the client's](edit.md#what-a-window-says) |
| `:tooltip()` | string \| nil | the line that appears when the pointer rests on it, or `nil` — [`:tooltip(s)` writes it on a control you built](#tooltips-and-focus) |
| `:focused()` | boolean | whether a keystroke would reach this widget — see [focus](#tooltips-and-focus) |
| `:image()` | table \| nil | the faces of a [control](controls/interactive.md#a-caption-or-a-picture) that shows pictures, as `{up=, down=, hover=}`, else `nil` |
| `:value()` | varies \| nil | what a [control](controls/README.md#setters) holds — the client's own included, a checkbox's boolean through a text field's string — or `nil` where it holds nothing; `:value(v)` writes it on [one you built](controls/README.md#setters) and, protected, on [one of the client's](edit.md#driving-one-protected) |
| `:source()` | string \| userdata \| nil | the picture a [picture control](controls/display.md#picture) shows, or `nil` before one is set — [`:source(h)` writes it](controls/display.md#picture) |
| `:rows()` | array \| nil | the row source a [radio](controls/interactive.md#radio) or a [listbox, dropdown, menu, grid or table](lists.md) takes, or `nil` where a control has no rows — [`:rows(t)` writes it](lists.md#rows-listbox-dropdown-menu) |
| `:range()` | `{min=, max=}` \| nil | the value bounds of a [slider or scrollbar](controls/interactive.md#slider), or `nil` where a control has none — [`:range(min, max)` writes it](controls/interactive.md#slider) |
| `:rowHeight()` | int \| nil | the height of a row in a [listbox, dropdown, menu or table](lists.md), in [design pixels](pixels.md), or `nil` where a control has no rows — [`:rowHeight(n)` writes it](lists.md) |
| `:cellSize()` | `{w=, h=}` \| nil | the cell box of a [grid](lists.md#grid), in [design pixels](pixels.md), or `nil` where a control has no cells — [`:cellSize(w, h)` writes it](lists.md#grid) |
| `:columns()` | array \| nil | the column descriptors of a [table](lists.md#table), or `nil` where a control has no columns — [`:columns(t)` writes it](lists.md#table) |
| `:item()` | [`Item`](items.md#the-item-object) \| nil | the item an **icon** draws — `nil` on everything else, where [`:items()`](items.md) is the container's own read |
| `:items()` | [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of [`Item`](items.md#the-item-object) | the items inside it — see [items](items.md) |
| `:exists()` | boolean | whether it is still in the tree |
| `:info()` | table \| nil | the snapshot escape hatch `{type, role, res, id, pos, size, visible, text, owned}`; absent values are unset, and the whole thing is `nil` once stale |
| `:walk(fn)` | self | depth-first visit — `fn(widget, depth)`; **return `false` to prune** that subtree |
| `:hit(coord)` | Widget \| nil | the deepest widget under a `{x=, y=}` **root-coord** point within this subtree |
| `:rootPos()` | `{x=, y=}` \| nil | its top-left in **root coords**; with `:size()` that is the rectangle outlining it |
| `:replacement()` | Widget \| nil | the view **you** put in place of this widget's window, or `nil` — see [replace](replace.md) |
| `:chrome()` | table \| nil | on a **window**, where its decoration drew its [ornaments](style/chrome.md#ornaments) — `{caption = {x=, y=}, plate = {x=, y=, w=, h=, styled=}, sizer = {x=, y=}, close = {x=, y=, w=, h=}}`, each present only once it has been drawn; `nil` on anything else |
| `:overlay()` | collection | what **your** addon [draws over this widget](overlay.md#over-one-widget) — a keyed painter or label, clipped to its box, that dies with it |
| `:style()` | table \| nil | the style this widget [resolves to](style/README.md#the-cascade), or `nil` when nothing names it |
| `:rule()` | Rule | **your own** [level of the cascade](style/README.md#restyle-one-widget) on this widget: its properties are setters, `:info()` reads them back and `:release()` gives them back |

Reading the tree is unprotected client-side data.

```lua
-- dump the client's full nested tree from the :lua console
hafen.session():current():ui():root():walk(function(n, d)
  hafen.log():write(string.rep("  ", d) .. n:type()
    .. (n:role() and (" [" .. n:role() .. "]") or "")
    .. (n:id()   and (" #" .. n:id())          or "")
    .. (n:text() and (" '" .. n:text() .. "'") or ""))
end)
```

**`:id()` is what makes a widget *bound*.** A widget the server placed has one; one your addon built does
not, and a message from it would be dropped — the difference [`:send`](#send-a-message-protected)
below turns on, so you send from the nearest server-bound ancestor rather than from the button itself.

## Searching inside one widget

| Method | Returns | Description |
|---|---|---|
| `:match(selector)` | Widget \| nil | the **one** match inside this widget's subtree, itself included, or `nil` |
| `:matchAll(selector)` | array | **every** match inside it, in tree order — an empty array, never `nil` |

These are [`s:ui():match` and `:matchAll`](selectors.md#one-or-all-of-them) with a narrower scope, and they
answer the same way. Their whole contract — the strict `find`, the absence case, what the scope decides and
the refusal on a widget that has left the tree — is stated once, under
[inside one widget](selectors.md#inside-one-widget).

## Subscribing

**Any** widget — one you built, one you found by [selector](selectors.md), one an event handed you —
answers `:on(key, fn)` for the keys below. This is what makes the widget half of `hafen.ui`
reachable at all: input on a widget you found by selector goes through the same door as input on one you
built.

```lua
local sub = hafen.session():current():ui()
  :match("window[title=Cupboard] inventory"):on("MouseDown", function(ev)
  if ev:button() == 3 then ev:preventDefault() end    -- right-click disabled on this cupboard only
end)
sub:off()
```

| Key | `ev` answers | Cancelable | Fires |
|---|---|---|---|
| `MouseDown` | `:x()` `:y()` `:button()` `:preventDefault()` | yes | a mouse button is pressed over it |
| `MouseUp` | `:x()` `:y()` `:button()` `:preventDefault()` | yes | a mouse button is released over it |
| `MouseMove` | `:x()` `:y()` `:preventDefault()` | yes | the mouse moves over it |
| `Wheel` | `:x()` `:y()` `:amount()` `:preventDefault()` | yes | the wheel turns over it |
| `Removed` | — | no | it leaves the tree |
| `Dragged` | `:x()` `:y()` | no | the user finished [dragging it](native.md#knowing-when-one-was-dragged) by a handle you armed |
| `Resized` | `:x()` `:y()` | no | the user finished [resizing it](native.md#knowing-when-one-was-resized) by a handle you armed |

`ev:x()`/`:y()` are widget-local [design pixels](pixels.md); `ev:button()` is 1 for left and 3 for right, present on
`MouseDown` and `MouseUp` only; `ev:amount()` is the wheel delta. `ev:preventDefault()` stops the input
reaching the widget's own handling and any child under it — there is no separate propagation verb, and no
handler's return value is ever read. **Two handlers fire independently**: either one calling
`preventDefault` cancels, and both still run.

On a window you [painted](custom.md), widget-local means the **content**: the corner `Draw` paints from, with
the chrome left to the client — see [where a press lands](custom.md#where-a-press-lands). On one of the
client's own windows it means that window's own box, caption included — a press in its title bar arrives
with a small `ev:y()`, and cancelling that press is what stops the client dragging the window.

A [control](controls/README.md) answers all of these as well — it is a Widget first — plus the capability
keys of the thing it is, and a surface you [paint](custom.md) answers four more on top. A capability key
belongs to **a control**, not to a control you built: `Pressed` answers on one of the client's own buttons,
`Changed` on one of its checkboxes or [lists](lists.md) and `Submitted` on one of its text entries the same
way, and there they are **cancelable**, because there the client has an action of its own underneath your
handler — see [edit](edit.md). The exception is the one family that writes its value before it reports it:
a borrowed [slider or scrollbar](controls/interactive.md#slider)'s `Changed` cannot be cancelled, and
`ev:preventDefault()` raises there rather than doing nothing.
`:on(key, fn)` on a key a widget does not have throws, naming the ones it does:

```lua
label:on("Pressed", fn)
-- a Label has no event 'Pressed' — it has: MouseDown, MouseUp, MouseMove, Wheel, Destroy, Dragged, Resized
```

Subscribing on a **native** widget is released the same way as anywhere else — on `:reload` or disable, or
for one widget and everything under it with [`w:revert()`](edit.md#taking-the-whole-edit-back) — even
though the widget itself survives: the listener is yours, not the widget's, so nothing is left behind in
client state you do not own.

## Owned vs borrowed

A widget is **owned** if *your* addon created it — a surface you [painted](custom.md) or a
[control](controls/README.md) you built — and **borrowed** otherwise: a native client widget, or another
addon's. Reads answer on both; `:info().owned` tells you which you are holding, so you can ask rather than
provoke the error.

| Method | Owned | Borrowed |
|---|---|---|
| `:position(x, y)` | move, and chain | **works** — [it is a layer, and it restores](native.md) |
| `:size(w, h)` | resize the content, chrome repacks around it, and chain | **works**, same |
| `:size(w)` | set the width and keep the height a [control](controls/README.md#sizing)'s own art gives it | **error** — the client's widget has no art of yours to ask |
| `:pack()` | size it to what is inside it — a window's chrome or a bare widget alike — and chain | **works on a window** — [it refits, as a level that restores](edit.md#your-own-controls-inside-one-of-the-clients-windows); a control refuses |
| `:parent(w)` | choose what it hangs under while it is being built — [one of the client's own windows included](edit.md#your-own-controls-inside-one-of-the-clients-windows) | **works** — [take it into a surface of yours, and `nil` gives it back](native.md#taking-one-into-a-surface-of-your-own-unprotected) |
| `:destroy()` | remove it and everything in it, and chain | **error**, same reason |
| `:revert()` | give back everything your addon holds on it and on what is inside it | **works**, same — [the one undo for a whole edit](edit.md#taking-the-whole-edit-back) |
| `:text(s)` | write the caption of a [control](controls/README.md) you built | **works** — [a level over what it says, and it restores](edit.md#what-a-window-says) |
| `:title(s)` | write the caption of a window you built | **works**, same — a window's caption is this verb wherever it came from |
| `:tooltip(s)` | write the line that appears when the pointer rests on it | **error** — those are the client's own words about its own button |
| `:image(up, down [, hover])` | give a [control](controls/interactive.md#a-caption-or-a-picture) you are building its pictures | **error**, same reason |
| `:value(v)` | write what a [control](controls/README.md#setters) holds | **works, and it is the one PROTECTED write here** — [driving the client's own control](edit.md#driving-one-protected) is what the user would have done, and the server sees it |
| `:source(h)` | give a [picture control](controls/display.md#picture) its content | **error**, same reason |
| `:rows(t)` | give a [radio](controls/interactive.md#radio) or a [listbox, dropdown, menu, grid or table](lists.md#rows-listbox-dropdown-menu) its rows | **error**, same reason |
| `:range(min, max)` | set the bounds of a [slider or scrollbar](controls/interactive.md#slider) you built | **error**, same reason |
| `:rowHeight(n)` | set a [listbox, dropdown, menu or table](lists.md)'s row height while it is being built | **error**, same reason |
| `:cellSize(w, h)` | set a [grid](lists.md#grid)'s cell box while it is being built | **error**, same reason |
| `:columns(t)` | name a [table](lists.md#table)'s columns while it is being built | **error**, same reason |
| `:visible(b)` | show or hide it, and chain | **works** — [see hiding](native.md#hiding-a-native-widget-carries-a-restore), except on a [radial menu](../flowermenu.md#drawn-or-not-unprotected), where both writes refuse naming `s:flowermenu():visible(b)` and the read still answers |
| `:draggable(h)` | hand the move to the user, by a handle they press | **works** — [and what a drag writes is your position level](native.md#letting-the-user-drag-it-unprotected) |
| `:resizable(h)` | hand the box to the user, by a handle they press | **works** — [and what a resize writes is your size level](native.md#letting-the-user-resize-it-unprotected) |
| `:remember(name)` | keep its place and box under a name of yours | **works**, same — [and it puts them back on the call](native.md#remembering-where-the-user-put-it-unprotected) |
| `:replace(view)` | **error** — a window you created is not one to stand in for | **works** — [put your own window in its place](replace.md) |
| `:rule()` | restyle it and its subtree through your own level | **works**, same |

One of these writes is protected, and it is the one that is not client-side state: `:value(v)` on a
**borrowed** control drives it as the user would, so the server sees it, and it needs the `widget.value`
[key](../../guides/permissions.md) — like [`:send`](#send-a-message-protected) below. Every other write
here changes only your own client, and every one of them restores.

**Arity is the verb.** `w:position()` reads, `w:position(x, y)` writes and `w:position(nil)` drops your write;
`w:size()` (`{w=, h=}`), `w:visible()`, `w:draggable()`, `w:resizable()` and `w:remember()` are the same shape
— with `w:size(w)` as the arity a [control](controls/README.md#sizing)'s own art earns it — and so is every
setter on `w:rule()`. That is why there is no `:move()`, no `:show()` and no `:hide()`: a value belongs in the
argument, not the verb's name. It is also why nothing stands beside
[`w:remember(name)`](native.md#remembering-where-the-user-put-it-unprotected) to *apply* what it kept — the
only moment that would be correct is the moment you name it, which is a step rather than a verb.

**Replacement is the one place where the read has a name of its own.** `w:replace(view)` is an *act* and
the thing standing in is a *replacement*, so the two do not share a spelling: `w:replacement()` reads,
`w:replace(view)` installs and `w:replace(nil)` undoes.

Provenance comes from the tree, not from how you obtained the object: a surface your addon built reads as
owned through every handle to it, and one of the client's own reads as borrowed however deep inside your own
window you built it. Addon B looking at addon A's window holds a *borrowed* widget, which is the correct
answer.

**A widget's place is on the screen, not in the world.** `:position()` and `:rootPos()` answer in
[design pixels](pixels.md) and hand back a plain `{x=, y=}` table, never a [Position](../position.md). The
verb is the same word because the question is the same one — *where is this thing, in the space it lives in*
— and the object says which space, so [`s:player():move`](../player.md#write-protected) refuses a widget's
coordinates instead of walking a character somewhere that merely has the same two numbers. It holds out in
the world too: the [**panel**](../virtual/widgets.md) standing there is a second object, answering to `panel`,
whose place is a Position — so a typo on either is answered in the vocabulary of the one in hand.

## Send a message (protected)

### `widget:send(msg, ...)`

Send an arbitrary widget message, for what the typed verbs do not cover — the message a client-side button
would have sent, from the widget the server knows. `msg` must be a **string**: a number is refused rather
than coerced, because a message name leaving the client is not a thing to guess at. Trailing arguments
marshal the way an [`action`](../event/streams.md#intercepting-an-outbound-action) `ev:args()` is read: a
`{x=, y=}` table becomes a coordinate, and numbers, strings and booleans pass through. Returns the Widget.
It needs the `widget.send` [permission key](../../guides/permissions.md) declared in your manifest; without
it the call raises an error naming that key. It is the widest key in the catalogue — anything the typed
verbs can send, this can send too — so declare it only where none of them fit.

```lua
hafen.session():current():ui():match("@MapView"):send("click", {x = 0, y = 0}, {x = 0, y = 0}, 1, 0)
```

**Bound widgets only.** One your addon built has no server id, so there is nobody to deliver to and the
client would drop the message without a word; `send` refuses it instead, naming that, and [`:id()`](#read)
answers the same question first. It **raises on a stale widget** too, where every other write here is a
silent no-op. The receiver **is** the target, so there is no address argument and no vocabulary of names:
the map view and the HUD are ordinary [selectors](selectors.md), as above.

## Tooltips and focus

`w:tooltip()` is the line that appears when the pointer rests on a widget. It answers on **any** widget,
the client's own included, and never throws: a plain string as it was given, the text of one of the
client's own keybound tips (the shortcut it appends is the keymap's, not the text's), or `nil` where there
is none. `w:tooltip(s)` writes it on a control you built — like `:text(s)` — and `""` clears it.

**Which widget's tooltip the client would actually *show* at a point is a different question**, because a
tooltip is inherited from whatever ancestor carries one, so it is not always the widget under the pointer:

```lua
local m = hafen.ui():mouse()
local from = hafen.ui():tipAt(m:x(), m:y())        -- who speaks for this point, or nil
if from then hafen.log():write(from:tooltip()) end
```

`hafen.ui():hit(x, y)` answers *what is under the point*; `hafen.ui():tipAt(x, y)` answers *who would speak
for it*. Both resolve the way the client itself does, [panels standing in the 3D world](../virtual/widgets.md)
included, so a tooltip over a standing widget is that widget's and not the map's behind it.

`w:focused()` asks whether a keystroke would reach a widget. The client resolves the keyboard down a chain
of controllers from the root, so being focused is a property of a **path** rather than of one widget: it is
true for the text entry you are typing into, and true for the window around it, because the key passes
through on the way. It is read-only — focus follows the click, and a verb that stole it would be a second
way to do what clicking already does.

## See also

- [the pixel](pixels.md) — the unit every coordinate and size here is measured in
- [the mouse](mouse.md) — the pointer, what is under it, and the grab that makes a drag yours
- [controls](controls/README.md) — the client's own controls, built and owned by your addon
- [lists](lists.md) — the row-source controls, a scrolling listbox among them
- [custom](custom.md) — a surface you paint, and its four extra subscription keys
- [selectors](selectors.md) — how to name the widget you want in the first place
- [native](native.md) — what moving, hiding and handing over a borrowed widget actually does
- [edit](edit.md) — taking over what one of the client's own controls does
- [replace](replace.md) — standing your own window in place of a native one
- [items](items.md) — `:items()` and the container subscriptions
- [overlays](overlay.md) — `:overlay()`, and the same vocabulary over the screen and a game object
- [style](style/README.md) — `:rule()`, `:style()` and the cascade they sit in
