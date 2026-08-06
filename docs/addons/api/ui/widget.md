# hafen.ui: the Widget object

A **Widget** is one piece of the UI — a window, a button, a container, a label, or something you built
yourself. It is the one type `hafen.ui` hands back, and every door below returns it.

```lua
local inv = hafen.ui():inventory()
if inv then hafen.log():write(inv:type() .. " holds " .. #inv:items() .. " items") end
```

## Getting a Widget

| Expression | Returns |
|---|---|
| `hafen.ui():window()` / `hafen.ui():widget()` | a surface you [painted](custom.md) — owned |
| one of the [control builders](controls/README.md#builders) (`:button()`, `:label()`, `:image()`, …) | a [control](controls/README.md) you built — owned, and drawn by the client |
| `hafen.ui():find(selector)` | the **first** widget matching a [selector](selectors.md), in tree order, or `nil` |
| `hafen.ui():all(selector)` | **every** match, in tree order — an empty array, never `nil` |
| `hafen.ui():root()` | the top of the whole client tree; walk down to any open window |
| `hafen.ui():node(id)` | the widget for a **server widget id**, or `nil` if it does not resolve |
| `hafen.ui():at(x, y)` | the **deepest** widget under a root-coord point — see [hit-testing](selectors.md#hit-testing) |
| `hafen.ui():mouse()` | the pointer — not a Widget, see [the mouse](#the-mouse) below |
| `hafen.ui():inventory()` | your main backpack grid, a container like any other |
| `hafen.ui():equipment()` | your worn-equipment grid |
| `hafen.ui():hand()` | the [`Item`](items.md#the-item-object) on the cursor, or `nil` |

A Widget is opaque, facade-safe userdata: no raw widget crosses into Lua and one cannot be forged. It is
**interned per addon**, so two lookups of the same live widget are the *same* Lua value:

```lua
hafen.ui():at(m.x, m.y) == hafen.ui():at(m.x, m.y)     -- true
hafen.ui():inventory() == hafen.ui():node(invId)       -- true: one widget, one object
```

`==` **is** the identity test, so there is no `:same()`. You can key a table by a Widget, stash one across
frames and compare it next frame. Holding one does not keep the widget or its dead subtree alive: it is a
view of engine state, not an owned resource, and there is nothing to tear down.

**Staleness.** A widget that leaves the tree — window closed, server destroy, relog — is *stale*: every
read answers `nil` or empty, every write is a silent no-op that still chains, and `:exists()`, the one read
that always answers, is `false`. A stashed object is therefore always safe to call; guard on `:exists()`
only when "is it still there?" is the question you are actually asking.

## Read

Every method below answers on every widget, owned or not, and none of them throws.

| Method | Returns | Description |
|---|---|---|
| `:type()` | string | class name, e.g. `"Inventory"`, `"Label"`; for an anonymous subclass, the nearest named superclass |
| `:role()` | string \| nil | what it **is** in the [selector vocabulary](selectors.md#roles), or `nil` when nothing classifies it |
| `:res()` | string \| nil | its [resource name](selectors.md#what-carries-a-res), e.g. `"gfx/invobjs/torch"`; `nil` for most widgets |
| `:id()` | int \| nil | server widget id, or `nil` when the widget is not server-bound |
| `:children()` | array | child Widgets in tree order; empty for a leaf |
| `:parent()` | Widget \| nil | the enclosing widget, or `nil` at the root |
| `:position()` | `{x=, y=}` | position within the parent, in widget-local px — [`:position(x, y)` moves it](native.md) |
| `:size()` | `{x=, y=}` | size; for a window its **outer** box |
| `:visible()` | boolean | whether it is visible — [`:visible(b)` writes it](native.md) |
| `:text()` | string \| nil | best-effort text for text-bearing widgets (Label, Button, Window, TextEntry), else `nil` — [`:text(s)` writes it on a control you built](controls/README.md#setters) |
| `:image()` | table \| nil | the faces of a [control](controls/interactive.md#a-caption-or-a-picture) that shows pictures, as `{up=, down=, hover=}`, else `nil` |
| `:value()` | varies \| nil | what a [control](controls/README.md#setters) holds, or `nil` where it holds nothing — [`:value(v)` writes it](controls/README.md#setters) |
| `:source()` | string \| userdata \| nil | the picture a [picture control](controls/display.md#picture) shows, or `nil` before one is set — [`:source(h)` writes it](controls/display.md#picture) |
| `:rows()` | array \| nil | the row source a [radio](controls/interactive.md#radio) or a [list, dropdown, menu, grid or table](lists.md) takes, or `nil` where a control has no rows — [`:rows(t)` writes it](lists.md#rows-list-dropdown-menu) |
| `:range()` | `{min=, max=}` \| nil | the value bounds of a [slider or scrollbar](controls/interactive.md#slider), or `nil` where a control has none — [`:range(min, max)` writes it](controls/interactive.md#slider) |
| `:rowHeight()` | int \| nil | the height of a row in a [list, dropdown, menu or table](lists.md), in pixels, or `nil` where a control has no rows — [`:rowHeight(n)` writes it](lists.md) |
| `:cell()` | `{w=, h=}` \| nil | the cell box of a [grid](lists.md#grid), in pixels, or `nil` where a control has no cells — [`:cell(w, h)` writes it](lists.md#grid) |
| `:columns()` | array \| nil | the column descriptors of a [table](lists.md#table), or `nil` where a control has no columns — [`:columns(t)` writes it](lists.md#table) |
| `:items()` | [`Item`](items.md#the-item-object)`[]` | the items inside it — see [items](items.md) |
| `:exists()` | boolean | whether it is still in the tree |
| `:info()` | table \| nil | the snapshot escape hatch `{type, role, res, id, pos, size, visible, text, owned}`; absent values are unset, and the whole thing is `nil` once stale |
| `:walk(fn)` | self | depth-first visit — `fn(widget, depth)`; **return `false` to prune** that subtree |
| `:at(coord)` | Widget \| nil | the deepest widget under a `{x=, y=}` **root-coord** point within this subtree |
| `:rootPos()` | `{x=, y=}` \| nil | its top-left in **root coords**; with `:size()` that is the rectangle to outline it |
| `:replacement()` | Widget \| nil | the view **you** put in place of this widget's window, or `nil` — see [replace](replace.md) |
| `:style()` | table \| nil | the style this widget [resolves to](style/README.md#the-cascade), or `nil` when nothing names it |
| `:rule()` | Rule | **your own** [level of the cascade](style/README.md#restyle-one-widget) on this widget: its properties are setters, `:info()` reads them back and `:remove()` drops them |

Reading the tree is ungated client-side data.

```lua
-- dump the client's full nested tree from the :lua console
hafen.ui():root():walk(function(n, d)
  hafen.log():write(string.rep("  ", d) .. n:type()
    .. (n:role() and (" [" .. n:role() .. "]") or "")
    .. (n:id()   and (" #" .. n:id())          or "")
    .. (n:text() and (" '" .. n:text() .. "'") or ""))
end)
```

**`:id()` is the pivot for acting.** To *act*, read a **server-bound** widget's `:id()` and pass it to the
gated [`hafen.act():raw(id, msg, …)`](../act.md) with the message a client-side button would have sent. A
message from an unbound widget is dropped, so you never target the button itself but its nearest
server-bound ancestor.

## Subscribing

**Any** widget — one you built, one you found by [selector](selectors.md), one an event handed you —
answers `:on(key, fn)` for the five keys below. This is what makes the widget half of `hafen.ui`
reachable at all: a native widget found by selector had no input surface before this.

```lua
local sub = hafen.ui():find("inventory[title=Cupboard]"):on("MouseDown", function(ev)
  if ev:button() == 3 then ev:preventDefault() end     -- right-click disabled on this cupboard only
end)
sub:off()
```

| Key | `ev` answers | Cancelable | Fires |
|---|---|---|---|
| `MouseDown` | `:x()` `:y()` `:button()` `:preventDefault()` | yes | a mouse button is pressed over it |
| `MouseUp` | `:x()` `:y()` `:button()` `:preventDefault()` | yes | a mouse button is released over it |
| `MouseMove` | `:x()` `:y()` `:preventDefault()` | yes | the mouse moves over it |
| `Wheel` | `:x()` `:y()` `:amount()` `:preventDefault()` | yes | the wheel turns over it |
| `Destroy` | — | no | it leaves the tree |

`ev:x()`/`:y()` are widget-local pixels; `ev:button()` is 1 for left and 3 for right, present on
`MouseDown` and `MouseUp` only; `ev:amount()` is the wheel delta. `ev:preventDefault()` stops the input
reaching the widget's own handling and any child under it — there is no separate propagation verb, and no
handler's return value is ever read. **Two handlers fire independently**: either one calling
`preventDefault` cancels, and both still run.

A [control](controls/README.md) answers these five as well — it is a Widget first — plus its own
capability keys, and a surface you [paint](custom.md) answers four more on top. `:on(key, fn)` on a key
a widget does not have throws, naming the ones it does:

```lua
label:on("Pressed", fn)
-- a Label has no event 'Pressed' — it has: MouseDown, MouseUp, MouseMove, Wheel, Destroy
```

Subscribing on a **native** widget is released the same way as anywhere else — on `:reload` or disable —
even though the widget itself survives: the listener is yours, not the widget's, so nothing is left
behind in client state you do not own.

## Owned vs borrowed

A widget is **owned** if *your* addon created it — a surface you [painted](custom.md) or a
[control](controls/README.md) you built — and **borrowed** otherwise: a native client widget, or another
addon's. Reads answer on both; `:info().owned` tells you which you are holding, so you can ask rather than
provoke the error.

| Method | Owned | Borrowed |
|---|---|---|
| `:position(x, y)` | move, and chain | **works** — [it is a layer, and it restores](native.md) |
| `:size(w, h)` | resize the content, chrome repacks around it, and chain | **works**, same |
| `:pack()` | shrink the chrome to fit its content (a no-op on a bare widget), and chain | **error** — that is not yours to do |
| `:destroy()` | remove it and everything in it | **error**, same reason |
| `:text(s)` | write the caption of a [control](controls/README.md) you built | **error** — that caption is the client's |
| `:image(up, down [, hover])` | give a [control](controls/interactive.md#a-caption-or-a-picture) you are building its pictures | **error**, same reason |
| `:value(v)` | write what a [control](controls/README.md#setters) holds | **error**, same reason |
| `:source(h)` | give a [picture control](controls/display.md#picture) its content | **error**, same reason |
| `:rows(t)` | give a [radio](controls/interactive.md#radio) or a [list, dropdown, menu, grid or table](lists.md#rows-list-dropdown-menu) its rows | **error**, same reason |
| `:range(min, max)` | set the bounds of a [slider or scrollbar](controls/interactive.md#slider) you built | **error**, same reason |
| `:rowHeight(n)` | set a [list, dropdown, menu or table](lists.md)'s row height while it is being built | **error**, same reason |
| `:cell(w, h)` | set a [grid](lists.md#grid)'s cell box while it is being built | **error**, same reason |
| `:columns(t)` | name a [table](lists.md#table)'s columns while it is being built | **error**, same reason |
| `:visible(b)` | show or hide it, and chain | **works** — [see hiding](native.md#hiding-a-native-widget-carries-a-restore) |
| `:replace(view)` | **error** — a window you created is not one to stand in for | **works** — [put your own window in its place](replace.md) |
| `:rule()` | restyle it and its subtree through your own level | **works**, same |

None of these writes is gated: they are client-side state, and every one of them restores.

**Arity is the verb.** `w:position()` reads, `w:position(x, y)` writes and `w:position(nil)` drops your
write; `w:size()` and `w:visible()` are the same shape, and so is every setter on `w:rule()`. That is why
there is no
`:move()`, no `:show()` and no `:hide()`: a value belongs in the argument, not in the verb's name.

**Replacement is the one place where the read has a name of its own.** `w:replace(view)` is an *act* and
the thing standing in is a *replacement*, so the two do not share a spelling: `w:replacement()` reads,
`w:replace(view)` installs and `w:replace(nil)` undoes.

Provenance comes from the tree, not from how you obtained the object: find your own window with
`hafen.ui():at(x, y)` and you get the very same value `hafen.ui():window()` returned, writes and all. Addon B
looking at addon A's window holds a *borrowed* widget, which is the correct answer.

**A widget's place is on the screen, not in the world.** `:position()` and `:rootPos()` answer in pixels
and hand back a plain `{x=, y=}` table, never a [Position](../world.md#the-position-type). The verb is the
same word because the question is the same one — *where is this thing, in the space it lives in* — and the
object you ask says which space that is, so a spatial verb like `hafen.act():moveTo` refuses a widget's
coordinates instead of walking you somewhere that merely has the same two numbers.

## The mouse

`hafen.ui():mouse()` is the pointer, not a Widget — the section's one thing **is** the object, the same
shape [`hafen.player()`](../player.md) has:

```lua
local m = hafen.ui():mouse()
m:x()  m:y()                  -- where the cursor is, in root coords
m:over()                      -- the deepest Widget under it, or nil
m:shift() m:ctrl() m:alt()    -- the live modifier keys
```

| Verb | Returns |
|---|---|
| `m:x()` / `m:y()` | the cursor position, in root coords |
| `m:over()` | the deepest [Widget](#read) under the cursor, or `nil` |
| `m:shift()` / `m:ctrl()` / `m:alt()` | whether that modifier key is down, right now |
| `m:grab()` | take the pointer — see below |

`hafen.ui():at(x, y)` still answers for an arbitrary point; `m:over()` is exactly `hafen.ui():at(m:x(),
m:y())`, kept as one call for the case every addon reaches for. Reading the mouse is ungated client-side
data.

### The grab

A modal press-drag-release capture: while it is held the map view neither pans nor clicks, so a drag
leaves the camera put. It is what a [ghost](../ghost.md#the-transform-gizmo) gizmo is built on.

```lua
local g = hafen.ui():mouse():grab()   -- bare: from here the pointer is yours

g:on("Move", function(ev) end)        -- ev:x() ev:y() ev:shift() ev:ctrl() ev:alt()
g:on("Up",   function(ev) end)        -- …plus ev:button(); fires once and auto-releases

g:release()                           -- hand it back early
```

`:grab()` takes no arguments and hands back an emitter with the same `:on(key, fn)`/`sub:off()` shape as
everything else, closed to `Move` and `Up`. The instant you take it: every move reaches you wherever the
cursor goes, even off-window; the map stops panning; clicks stop reaching the game; and no other widget
sees the pointer. `g:release()` ends it early, and `Up` ends it automatically. A grab still open when your
addon reloads is released by teardown.

```lua
local g = hafen.ui():mouse():grab()

g:on("Move", function(ev)
  hafen.world():screenToWorld(ev:x(), ev:y(), function(w)
    if w then ghost:move(hafen.world():snapPlace(w.x, w.y, ev:shift()).x, w.y) end
  end)
end)

g:on("Up", function(ev) hafen.log():write("dropped with button " .. ev:button()) end)
```

Pair it with [`hafen.world():screenToWorld`](../world.md#screen-to-world-and-placement-snapping) and
`snapPlace` to drag something along the ground, exactly as before.

## See also

- [controls](controls/README.md) — the client's own controls, built and owned by your addon
- [lists](lists.md) — the row-source controls, a scrolling list among them
- [custom](custom.md) — a surface you paint, and its four extra subscription keys
- [selectors](selectors.md) — how to name the widget you want in the first place
- [native](native.md) — what moving and hiding a borrowed widget actually does
- [replace](replace.md) — standing your own window in place of a native one
- [items](items.md) — `:items()` and the container subscriptions
- [style](style/README.md) — `:rule()`, `:style()` and the cascade they sit in
- [events](../event.md) — everything that is not a widget or the pointer
