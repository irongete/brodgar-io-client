# hafen.ui — windows, overlays, and the widget entity

Draw your own client-side UI, paint over the HUD and the 3D world, observe and replace the client's own
server widgets, and [walk any widget's tree](#the-widget-object). Everything here is client-side and
bridge-owned — it is torn down automatically on reload/disable. (Client-side UI cannot send actions to
the server; that is [`hafen.act`](actions.md).)

**There is one type.** A window you create, a native window you find, the deepest widget under the
cursor and the container an [`appear` subscription](#watching-for-a-widget) hands your callback are all
the **same** [Widget object](#the-widget-object). What you create and what you find are not different
things.

**And one way to name one.** `hafen.ui` is *callable*: `hafen.ui("window[title=Cupboard]")` is the first
matching widget, `hafen.ui.all("inventory")` is every one — see [selectors](#selectors--naming-a-widget).
The same selector is the key of a [**stylesheet**](#the-stylesheet--restyling-the-client), the one way to
restyle the client's own surfaces.

## Custom windows & widgets

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.window(opts)` | [Widget](#the-widget-object) | a draggable, titled window wrapping your content |
| `hafen.ui.widget(opts)` | [Widget](#the-widget-object) | a bare content rectangle (no chrome) |

`opts` (all optional):

| Key | Type | Meaning |
|---|---|---|
| `size` | `{w, h}` | initial size |
| `pos` | `{x, y}` | initial position |
| `parent` | `"root"` \| `"gameui"` | where to attach (default `"root"`) |
| `title` | string | window title (windows only) |
| `font` | [`FontHandle`](fonts.md) | default font for this widget's `g:text`/`g:atext` draws (F2; not the title bar) |
| `onDraw` | `fn(g, w, h)` | draw the content — see [the `g` wrapper](#the-g-draw-wrapper) |
| `onTick` | `fn(dt)` | per-frame update; `dt` = seconds |
| `onClick` | `fn(x, y, button, mods)` | mouse press; return truthy to consume it |
| `onMouseUp` | `fn(x, y, button, mods)` | mouse release |
| `onMouseMove` | `fn(x, y, mods)` | mouse move over the widget |
| `onWheel` | `fn(x, y, amount, mods)` | mouse wheel |
| `onDrop` | `fn(x, y, drop)` | something was dropped on the widget; return truthy to consume it |
| `onClose` | `fn()` | window close button (windows only) |

- **`mods`** is the trailing `{shift, ctrl, alt}` boolean table (from `ui.modflags()`) on every mouse
  callback — the modifier state **at press time** (e.g. branch Shift+drag vs. a plain click). Additive: a
  handler that ignores the extra argument is unaffected. Same shape as [`hafen.hook.grab`](hooks.md)'s `mods`.
- **`onDrop`** makes the widget a **drop target** for the client's own drag gesture: drag a menu-grid action
  onto it and `onDrop(x, y, drop)` fires with widget-local pixels and a neutral descriptor
  `drop = { kind = "pagina", res = "<resource name>" }`. `res` is a plain resource name (ungated); draw its
  icon with [`g:resource`](#the-g-draw-wrapper), persist it via [`hafen.store`](store.md). `res` is present
  only for resource-based actions — an id-only action carries `kind` alone (usable in-session, not reliably
  persistable). *Firing* the dropped action is not part of `onDrop` (that needs the deferred menu-ability
  primitive).

```lua
local win = hafen.ui.window({
  title = "Clock", size = {160, 40}, pos = {50, 50},
  onDraw = function(g, w, h)
    g:color(255, 255, 0)
    g:text(string.format("%.0f", hafen.time.clock() or 0), 6, 12)
  end,
})
win:pos(320, 200)                                  -- the same object you would get from hafen.ui.at()
```

## The Widget object

The one entity `hafen.ui` hands back. Every door below returns it, and `:info()` answers on all of them:

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.window(opts)` / `widget(opts)` | Widget | one you **created** (owned) — see above |
| `hafen.ui(selector)` | Widget \| nil | the **first** widget matching a [selector](#selectors--naming-a-widget), in tree order |
| `hafen.ui.all(selector)` | Widget[] | **every** match, in tree order — an empty array, never nil |
| `hafen.ui()` | Widget \| nil | the top of the whole client tree — walk **down** to any open window |
| `hafen.ui.node(id)` | Widget \| nil | the widget for a **server widget id** (another widget's `:id()`); nil if it doesn't resolve |
| `hafen.ui.at(x, y)` | Widget \| nil | the **deepest** widget under a root-coord point — exactly what a click would hit ([see below](#hit-testing--what-is-under-the-cursor-the-wow-framestack-enabler)) |
| `hafen.ui.mouse()` | `{x=,y=}` \| nil | the cursor in **root coords** (not a widget) |
| `hafen.ui.inventory()` | Widget \| nil | your main backpack grid — a container like any other |
| `hafen.ui.equipment()` | Widget \| nil | your worn-equipment grid |
| `hafen.ui.hand()` | [`Item`](types.md#item) \| nil | the item on the cursor — a **snapshot**, not a widget (there is no widget to walk) |

It is opaque, **facade-safe** userdata (no raw widget crosses into Lua) and cannot be forged. It is
**interned per addon**: two lookups of the same live widget are the *same* Lua value, so

```lua
hafen.ui.at(m.x, m.y) == hafen.ui.at(m.x, m.y)     -- true
hafen.ui.inventory() == hafen.ui.node(invId)       -- true — one widget, one object
```

`==` **is** the identity test — that is why there is no `:same()`. You can key a table by a Widget, stash
one across frames and compare it next frame. Holding one does not keep the widget (or its dead subtree)
alive: it is a view of engine state, not an owned resource, and there is nothing to tear down.

**Staleness.** A widget that leaves the tree (window closed, server destroy, relog) is *stale*: every read
answers `nil`/empty, every write is a silent no-op that still chains, and `:exists()` — the one read that
always answers — is `false`. A stashed object is therefore always safe to call; guard on `:exists()` only
when "is it still there?" is the question you are actually asking.

### Selectors — naming a widget

A **selector is a string**, and `hafen.ui` itself is the lookup. The grammar is deliberately tiny and
CSS-shaped, and there are **no descendant selectors** (`window[title=X] button` is not a thing):

```lua
hafen.ui("window[title=Cupboard]")     -- the first match, or nil
hafen.ui.all("inventory")              -- every open container, in tree order
hafen.ui("inventory[title=Cupboard]")  -- the grid inside that window
hafen.ui("@Equipory")                  -- by widget class
hafen.ui("[res=gfx/hud/meter/hp]")     -- by resource name
hafen.ui()                             -- no argument: the root of the whole tree
```

| Part | Meaning |
|---|---|
| `*` | any widget — including one no role classifies |
| a **role** | what the widget *is* (see the table below) |
| `@Class` | its class name, the same string `:type()` reports |
| `[title=…]` | the caption of the **nearest enclosing window** — **exact** match |
| `[res=…]` | a **substring** of its resource name (`:res()`) |

A selector is a role (or `*`) followed by any of the refiners, in any order, each at most once —
`inventory@Inventory[title=Cupboard]`. Anything else errors, naming the offending part and, for a bad
role, listing every valid one.

#### Roles

`:role()` answers what a widget is, or **nil** when nothing classifies it. The names are the same
vocabulary as the stylesheet's [site keys](fonts.md#site-keys) — deliberately, so there is one set of
names, not two.

| Role | Matches |
|---|---|
| `window` | `Window` and every subclass (including the `Hidewnd` the client wraps the inventory in) |
| `inventory` | `Inventory` and `Equipory` — *every* open container, not just yours |
| `button` | `Button`, `IButton` |
| `label` | `Label` |
| `textentry` | `TextEntry` |
| `chat` | `ChatUI` and its channels |
| `menu` | `MenuGrid`, `FlowerMenu` |

**Five site-key names classify no widget** — `window.title`, `heading`, `tooltip`, `world.nick`,
`world.speech`. They name a *render site*, not a widget: a window's caption is drawn by the window's
decoration, a tooltip is painted rather than placed, and the world sites live over the 3D view. They stay
valid selectors (the vocabulary is shared with the sheet, and coverage can grow) but they match nothing.

Most widgets have **no** role — layout containers, scroll ports, images, item icons. On a live HUD, 174 of
625 widgets classified. That is the rule working, not a gap: an unrecognised widget answers `nil` rather
than being guessed into the nearest role. Reach those with `*`, `@Class` or `[res=]`.

#### Two rules that are easy to get wrong

- **`[title=]` is the *enclosing window's* caption, not the widget's own text.** The client wraps bare
  widgets in titled windows — the inventory grid itself has no caption — so `inventory[title=Cupboard]`
  matches the **grid inside** the Cupboard window, and `window[title=Cupboard]` matches the window. Both
  work; that is the point.
- **`@Class` is the class name, not a base class.** `@Window` matches a plain `Window`, not a `CharWnd`.
  Use the `window` role for "any window". Hafen builds most widgets as anonymous subclasses, and both
  `@Class` and `:type()` report the nearest **named** class, so this is the name you actually see.

#### What carries a `res`

`[res=]` is the *stable* key — a resource name never changes with the client's language, where a caption
can. But only some widgets have one: **items** (`gfx/invobjs/…`), **meters** (`gfx/hud/meter/hp`), and
widgets whose code ships inside a resource (`ui/rchan`, `ui/vlg`). **Windows do not** — the client's
windows are plain Java classes with no resource behind them. So in practice: `[res=]` for items and
meters, `[title=]` for windows. `w:res()` tells you what a widget actually carries.

#### Don't guess — the inspector tells you

Nobody guesses a widget's role. The bundled **`widgetstack`** addon answers it by hovering: its bottom panel
reports the hovered widget's **role** (or an honest `nil`), its **class**, its `[title=]` and its `[res=]`,
and then **every selector that actually matches it**, most specific first, with how many widgets each one
matches and where this one falls among them. The bottom line is ready to paste into `:lua` — it is
`hafen.ui("…")` when this widget is the **first** match and `hafen.ui.all("…")[i]` when it is not, because
`hafen.ui(sel)` means *first match*. Every offered selector is resolved before it is offered, so it always
hands back the widget you were pointing at.

Click a row (or run `:selector`) to log the line — chat-log text is selectable, which is how it reaches your
editor. Freeze the stack first (`widgetstack`'s `freeze` hotkey), or moving the mouse to the window
re-hovers.

One thing the inspector makes obvious: **hovering a window's frame does not give you the window.** The
chrome — border, title bar, close button — is the window's *decoration*, a child widget of its own, so the
hover resolves to that (`@DefaultDeco`, role `nil`) and not to the `Window`. `[title=]` still resolves
through it, so the deco is addressable; but for the window itself, click one level up in the stack, or just
write `window[title=…]`.

#### Hold the result — do not re-select every frame

`hafen.ui.all("*")` walks the whole tree: about **0.08 ms for 625 widgets**. Once per event, or once when
the hover changes, that is nothing; sixty times a second it is a real slice of your frame budget. Because
widgets are **interned**, holding the result costs nothing and the objects stay `==`-comparable — so
select once, keep it, and use `:exists()` when you need to know it is still there.

### Reads — they answer on every widget

| Method | Returns | Description |
|---|---|---|
| `:type()` | string | class name, e.g. `"Inventory"`, `"Label"`, `"Button"` (for an anonymous subclass — common in Hafen — the nearest named superclass) |
| `:role()` | string \| nil | what it **is** in the [selector vocabulary](#roles) — `"window"`, `"inventory"`, … — or nil when nothing classifies it |
| `:res()` | string \| nil | its [resource name](#what-carries-a-res), e.g. `"gfx/invobjs/torch"`; nil for most widgets |
| `:id()` | int \| nil | server widget id, or **nil if the widget is not server-bound** (client-only) |
| `:children()` | array | child Widgets in tree order (empty for a leaf) |
| `:parent()` | Widget \| nil | the enclosing widget, or nil at the root |
| `:pos()` | `{x=,y=}` | position within the parent (widget-local px) |
| `:size()` | `{x=,y=}` | size |
| `:visible()` | boolean | is it visible? |
| `:text()` | string \| nil | best-effort text for text-bearing widgets (Label/Button/Window/TextEntry), else nil |
| `:items()` | [`Item`](types.md#item)`[]` | the items inside it — [see below](#items-inside-a-container) |
| `:exists()` | boolean | is it still in the tree? |
| `:info()` | table \| nil | the snapshot escape hatch `{type, role, res, id, pos, size, visible, text, owned}` (absent values are simply unset); nil once stale |
| `:walk(fn)` | (self) | depth-first visit — `fn(widget, depth)`; **return `false` to prune** that subtree |
| `:at(coord)` | Widget \| nil | the deepest widget under a `{x=,y=}` **root-coord** point **within this subtree** |
| `:rootpos()` | `{x=,y=}` \| nil | its top-left in **root coords** (with `:size()` = a rectangle to outline it) |
| `:setFont(h)` | (self) | restyle **this widget and its whole subtree** with a [font handle](fonts.md#restyle-one-widget--widgetsetfonth) — its siblings keep their font |
| `:resetFont()` | (self) | drop **your** per-instance override on this widget (it falls back to the site rule, then the `*` rule) |

**`:id()` is the pivot for acting.** Reading the tree is ungated client-side data. To *act*, read a
**server-bound** widget's `:id()` and pass it to the gated [`hafen.act.raw(id, msg, …)`](actions.md) with
the message a client-only button would have sent (learned from the upstream widget class) — a `wdgmsg`
from an unbound (client-only, no `:id()`) widget is dropped, so you never target the button itself, but
its nearest server-bound ancestor.

```lua
-- dump the client's full nested tree from the :lua REPL
hafen.ui():walk(function(n, d)
  hafen.log(string.rep("  ", d) .. n:type()
    .. (n:role() and (" [" .. n:role() .. "]")   or "")
    .. (n:id()   and (" #" .. n:id())            or "")
    .. (n:text() and (" '" .. n:text() .. "'")   or ""))
end)
```

### Owned vs borrowed — which writes answer

A widget is **owned** if *your* addon created it with `hafen.ui.window{}`/`hafen.ui.widget{}`, and
**borrowed** otherwise (a native client widget, or another addon's). Reads answer on both. `info().owned`
tells you which you are holding — ask, rather than provoking the error.

| Method | Owned | Borrowed |
|---|---|---|
| `:pos(x, y)` | move + chain | **error** — moving a native widget is layout, a later feature |
| `:size(w, h)` | resize the content (a window's chrome repacks around it) + chain | **error**, same reason |
| `:pack()` | shrink the chrome to fit its content (no-op for a bare widget) + chain | **error** — that is not yours to do |
| `:destroy()` | remove it and everything in it | **error**, same reason |
| `:hide()` / `:show()` | toggle visibility + chain | **works** — [see below](#hiding-a-native-widget-carries-a-restore) |
| `:replace(view)` | **error** — a window you created is not one to stand in for | **works** — [put your own window in its place](#replacing-a-native-window) |

**Arity is the verb** on geometry — `w:pos()` reads, `w:pos(x, y)` writes; `w:size()` reads,
`w:size(w, h)` writes — and on replacement: `w:replace()` reads, `w:replace(view)` installs,
`w:replace(nil)` undoes. That is the same shape as [client options](client.md), and it is why there is no
`:move()`.

Provenance is derived from the tree, not from how you obtained the object: find your own window with
`hafen.ui.at(x, y)` and you get the very same value `hafen.ui.window{}` returned, writes and all. Addon B
looking at addon A's window holds a *borrowed* widget — which is the correct answer.

### Hiding a native widget carries a restore

`w:hide()` is the one write that answers on a widget you do not own, and it is the important line on this
page: **hiding a native widget records the restore.** Disabling your addon or `:reload`ing it gives the
widget back under **one rule — it ends up as the user was seeing it**: visible exactly when whatever you
put in its place was on screen. Hide something and put nothing there, and it stays hidden on teardown —
the user was not seeing it, and the toggle you get back (below) is what opens it again. A relog correctly
skips the restore entirely (that session's widgets are gone). `w:show()` gives it back yourself and drops
the record.

**One widget, one owner.** A native widget another addon has already hidden is not yours to hide:
`w:hide()` refuses with an error naming the addon that holds it. Its toggle can only drive one thing, so
two owners would leave the menu tick lying about both. [`replace`](#replacing-a-native-window) meets the
same rule from the other side, and *logs* it rather than throwing (it runs on the client's own placement
path): that one replacement is skipped, naming the addon that got there first.

A hidden server widget stays fully **live**: still bound to its id, still receiving updates, still filling
with items. That is why you can hide a grid and keep reading it.

#### Hiding a native window takes its toggle

If what you hid is one of the windows the client itself can open — the inventory, equipment, the character
sheet, kin, options, the map, the action search — **you also own its toggle**. The client's key and its
menu button both stop reopening it, and the menu button's tick goes off:

```lua
hafen.ui.inventory():parent():hide()   -- the Hidewnd around the grid: Tab no longer brings it back
hafen.ui.inventory():hide()            -- the grid alone: the window is still the client's, Tab still opens it
```

**What you own is what you point at.** The toggle belongs to the *window*, so hiding a widget inside one
(the inventory grid, a button) leaves that window — and its key — exactly as stock.

Without this, hiding was not authoritative. The keybinding fires the menu button's own click, and both land
in one place inside the client that flips the window straight back on — so an addon that hid the stock
inventory got it back on the next Tab, sitting on top of its replacement.

The toggle is **swallowed** while nothing stands in for the window: pressing the key does nothing, and the
tick tells the truth about what is on screen. Giving the widget back gives the toggle back with it — the
same restore as above, so `w:show()`, disabling your addon and `:reload` all hand the key to the client
again. That is also the escape hatch for a `w:hide()` typed into the `:lua` console: `:reload`, not a relog.

**With [`w:replace(view)`](#replacing-a-native-window), the toggle drives your view instead.** Tab and the
menu button open and close the window *you* built, and the menu tick reads your view's own visibility — so
it cannot drift out of sync with what is on screen. Nothing to wire: the verb is the only place that knows
both halves (the window it hides, and the view you handed it), so it binds them itself.

**There is no verb for this** — nothing to register, nothing to release. Ownership follows the hide, and it
is per window: hiding the inventory leaves equipment, the character sheet, kin, options and the map
behaving exactly as stock.

**There is no `hafen.ui.adopt`.** It existed only to get a readable handle on a native widget, and it
charged you a hidden window for the privilege. Reading no longer costs anything: `hafen.ui(selector)` (or
`node(id)`, `at()`, `inventory()`) hands you the same entity with **nothing hidden**, and hiding is the
separate, explicit act it always should have been. Putting your own window in a native one's place is
[`w:replace(view)`](#replacing-a-native-window).

## Items inside a container

`widget:items()` is a **relation**, exactly like `:children()`: it answers with the
[`Item`](types.md#item) snapshots inside *that* widget — your backpack, a chest, a cupboard, a study
window, an equipment grid — while the window stays **visible and interactive**. Nothing is hidden and
nothing is registered.

```lua
for _, it in ipairs(hafen.ui.inventory():items()) do
  hafen.log((it.name or it.res or "?") .. " x" .. (it.num or 1))
end

local cursor = hafen.ui.hand()                              -- the item on the cursor, or nil
```

- The search is **deep**, so a whole window answers for the grid inside it — `hafen.ui.node(chestId):items()`
  works whether you point at the window or at its `Inventory` child.
- Each entry's `pos` is shaped by its container: an inventory cell `{x, y}`, or — from
  `hafen.ui.equipment()` — the slot name, plus a numeric `slot`. A two-slot worn item appears as two
  entries with distinct `slot` values.
- A non-container (or a stale widget) answers with an empty array. `quality` and container `contents` are
  not exposed.
- Reading is ungated. To **move** an item (take, drop, transfer, use), pass its `handle` to the gated
  [`hafen.act.item`](actions.md#hafenactitem).
- There is no `find` verb — it is a one-liner over `:items()`, and it would have to pick a container for
  you.

### The container lifecycle

Three subscriptions on the container itself. All chain; pass `nil` to unsubscribe.

| Method | Description |
|---|---|
| `:onItemAdded(fn)` | `fn(item)` when an item enters this container |
| `:onItemRemoved(fn)` | `fn(item)` when one leaves |
| `:onDestroy(fn)` | `fn()` once, when this widget leaves the tree |

```lua
local chest = hafen.ui("window[title=Chest]")
chest:onItemAdded(function(item) hafen.log("in:  " .. (item.name or item.res or "?")) end)
     :onItemRemoved(function(item) hafen.log("out: " .. (item.name or item.res or "?")) end)
     :onDestroy(function() hafen.log("chest closed") end)
```

**The subscription is the registration.** A container nobody subscribed to is never polled, so leaving
`:items()` alone costs nothing; drop the last callback and the widget leaves the poll entirely. There is
no `:watch()`/`:unwatch()` verb because there is nothing extra to say.

Item add/remove is a widget create/destroy rather than a server message, so these are detected on a
per-tick diff. Two consequences worth knowing: the items **already** inside a container fire `onItemAdded`
on the first poll after you subscribe (the state arrives as events, like
[`BuffAdded`](events.md#character--status-widget-tree-backed)), and a container that is hidden still fires
them. Worn equipment additionally has the global [`EquipChanged`](events.md#character--status-widget-tree-backed)
event, which carries the whole new list.

## Overlays

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.overlay(fn)` | [`{ :remove() }`](#overlay--observer-handles) | paint `fn(g, w, h)` on top of the HUD each frame (`w`,`h` = screen size) |
| `hafen.ui.gobOverlay(filter, fn)` | [`{ :remove() }`](#overlay--observer-handles) | paint `fn(g, gob, sx, sy)` over each matching gob |

For `gobOverlay`, `filter` is the canonical [filter](conventions.md#the-filter-argument) — a name
substring, or a function receiving the [Gob](gob.md) — `gob` is that live Gob object, and `sx, sy` is
its projected screen point (just above the head).

```lua
hafen.ui.gobOverlay(function(gob) return gob:isplayer() end, function(g, gob, sx, sy)
  g:color(0, 255, 0)
  local label = (gob == hafen.player():gob()) and "you" or "player"
  g:atext(label, sx, sy, 0.5, 1)      -- centred just above the head
end)
```

## Observing & replacing the client's own UI

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.on(selector, event, fn)` | [`{ :remove() }`](#overlay--observer-handles) | `fn(widget)` when a widget matching a [selector](#selectors--naming-a-widget) appears or disappears |
| `widget:replace(view)` | Widget (chains) | put your own window in place of the native one around it — a [verb on the widget](#replacing-a-native-window), not a namespace function |

### Watching for a widget

`hafen.ui.on(selector, event, fn)` is how you wait for a part of the client's UI — named with the same
[selector](#selectors--naming-a-widget) a lookup uses, and handed back as the same interned
[Widget](#the-widget-object), so `==` and a Lua table keyed by it work across both events. `event` is one
of two strings, and a subscription carries exactly one (subscribe twice to watch both):

| Event | Fires when |
|---|---|
| `"appear"` | a matching widget is placed into the tree — **or is already in it when you subscribe** |
| `"disappear"` | a widget that had matched is destroyed |

```lua
hafen.ui.on("window[title=Cupboard]", "appear", function(w)
  hafen.log(("cupboard open: %d item(s)"):format(#w:items()))
end)
```

Three things worth knowing:

- **`appear` covers what is already open.** Registering scans the live tree once, so an addon reloaded
  with a window open still sees it — you never have to handle "was it there before me?" yourself.
- **Neither event is about visibility.** They track the *tree*: a window the client merely hides (the
  inventory's Tab toggle) never left, so it fires neither.
- **At `disappear`, treat the widget as a key, not as something to read.** It fires when the widget stops
  being *real* (the server destroyed it), which is not when it stops being *drawn* — a window plays a
  fade-out on close, so it lingers in the tree, readable, for the length of that animation. Match it
  against what you kept at `appear`; keep the data you need from there.

A `[title=]`/`[res=]` selector still fires exactly once for a window whose caption arrives a tick after
the window itself — such a candidate is re-checked for a short while rather than dropped.

### Replacing a native window

Replacing is a **verb on the widget**, and arity is the verb:

| Call | Does |
|---|---|
| `w:replace()` | reads the view standing in for this window, or `nil` |
| `w:replace(view)` | hides the native window and puts `view` in its place — chains |
| `w:replace(nil)` | undoes it there and then: the window comes back, the view is destroyed — chains |

**It hides the *enclosing* window, not the widget you point at.** That one line is why the verb exists.
Point it at the inventory **grid** and the whole stock window goes — frame, caption and all — because a
frame left standing around a hole is not a replacement. This is exactly where it differs from
[`w:hide()`](#hiding-a-native-widget-carries-a-restore), which hides precisely what you point at and
nothing more. Two operations, two rules; pick by what you want left on screen.

**Waiting is not part of it.** [`hafen.ui.on(sel, "appear", fn)`](#watching-for-a-widget) already waits for
anything and already fires for what is **already open**, so the whole pattern is those two together:

```lua
hafen.ui.on("inventory[title=Inventory]", "appear", function(inv)
  inv:replace(hafen.ui.window({
    title = "Bags", size = {200, 120},
    onDraw = function(g) g:text(#inv:items() .. " items", 6, 6) end,
  }))
end)
```

`inv` stays an ordinary [Widget object](#the-widget-object) throughout: the widget you replaced is
**hidden, not destroyed**, so it is still bound to its server id, still filling with items, and
`inv:items()`, `inv:onItemAdded(…)` and every other verb keep answering while your view is up. That is
"wrap, don't reimplement" — you draw, the client keeps doing the work.

**The client's own toggle comes with the window.** Hiding it means you [own
it](#hiding-a-native-window-takes-its-toggle), so Tab (or the menu button, or whichever key that window
uses) opens and closes **your view**, and the menu tick reads your view's visibility rather than the
hidden window's.

**The view's fate follows the substitution.** When the replacement ends, the view is destroyed — by
`w:replace(nil)`, by `:reload`/disabling your addon, or by the server destroying the window (close a
replaced chest and your view goes with it). A stand-in that no longer stands for anything is an orphan
window over a container that is gone, so it is not left behind for you to clean up. Every ending also
leaves the stock window **as the user was seeing it**: your view was open ⇒ the stock window is open;
nothing was on screen ⇒ it stays closed.

**One window, one view.** Installing a *different* view ends the previous substitution (and destroys that
view); installing the same one again is a no-op. Four things are refused outright, each naming what to do
instead: a view your addon did not create, a widget with **no enclosing window** (there is nothing to stand
in for), one of your *own* windows, and a window another addon already holds.

> `hafen.ui.replace` — the old namespace function, which named a window by the server's own widget-creation
> vocabulary rather than by a [selector](#selectors--naming-a-widget) — is **gone**, and reads as plain
> `nil`. It was the last place naming a window a different way, and the only thing that could bind a view to
> a hidden native window. Both of its halves are ordinary API now: `hafen.ui.on` waits, `w:replace` replaces.

**Limits.** A widget's Java state is otherwise read-only — mutating it desyncs from the server. `:text()`
is best-effort over a known type set (unknown → nil, never throws). The whole client tree is reachable via
`hafen.ui()`/`:parent()` (all client-side data — actions stay separately gated); which child is a price vs. a
spacer is upstream-defined knowledge your Lua adapter supplies. Restyling native widgets beyond
`:setFont`/`:resetFont`, and moving them, are later features.

### Hit-testing — what is under the cursor (the WoW `/framestack` enabler)

`hafen.ui.mouse()` + `hafen.ui.at(x, y)` find *what widget is under a point*. `at()` **mirrors the
engine's own pointer dispatch**: it walks children topmost-first, skips invisible widgets, follows scroll
offsets, and honours non-rectangular hit areas — so it returns exactly the widget a real click would hit
(a naïve `pos..pos+size` rect test is *wrong* inside scrolled lists and for custom hit shapes). Walk
`:parent()` up from the hit for the full stack; `:rootpos()` + `:size()` give the rectangle to outline it.

```lua
-- the /framestack core: the stack of widgets under the cursor, cheaply, every frame
local last                                            -- the leaf we last built the stack for
hafen.events.on("OnUpdate", function(dt)
  local m    = hafen.ui.mouse()
  local leaf = hafen.ui.at(m.x, m.y)                  -- deepest widget under the cursor (or nil)
  if leaf == last then return end                     -- GUARD: hover unchanged → no walk, no rebuild
  last = leaf                                          -- hover changed → rebuild once
  local stack, n = {}, leaf
  while n do stack[#stack + 1] = n; n = n:parent() end -- leaf → root
  -- ... render `stack`; outline the leaf via leaf:rootpos() + leaf:size()
end)
```

The efficiency guard is the point: `OnUpdate` fires every frame, but the expensive walk + relayout run
**only when the hovered widget changes** — and because widgets are interned, that guard is a plain `==`
(it covers "still hovering nothing" too, since `nil == nil`). Reading the cursor + geometry is client-side
data (**ungated**); acting on the resolved widget still goes through the gated
[`hafen.act.raw`](actions.md) on its `:id()`. The bundled **`widgetstack`** addon is a full `/framestack`
clone built on exactly this — and it hangs the [selector inspector](#dont-guess--the-inspector-tells-you)
off the same hover, which is the cheapest way to learn what a widget is and how to name it.

### Overlay / observer handles

`overlay`, `gobOverlay` and `on` return a handle with a single method:

| Method | Description |
|---|---|
| `:remove()` | stop it (also done automatically on reload/disable) |

Note this is *not* a Widget object — a Widget's own removal verb is `:destroy()`.

## The stylesheet — restyling the client

One table says what the client looks like: a **selector as the key**, a **table of style properties as the
value**, applied live and owned by the addon that installed it.

```lua
local body = hafen.asset("fonts/Inter.ttf"):derive{ size = 12 }
hafen.ui.skin{
  ["*"]            = { font = body },                                -- the global fallback
  ["window.title"] = { font = body:derive{ size = 14, bold = true } },
  ["chat"]         = { font = hafen.font("mono"):derive{ size = 13 }, color = {200, 210, 200} },
  ["tooltip"]      = { color = {255, 150, 90} },                     -- colour alone: the font stays stock
}
```

| Call | Description |
|---|---|
| `hafen.ui.skin{…}` | install this addon's stylesheet, **replacing** whatever it had |
| `hafen.ui.skin(nil)` | drop it — every surface it styled falls back |

**An addon owns exactly one sheet.** A second `skin{…}` replaces the first *whole*, not rule by rule: a surface
the new sheet no longer names falls back on the spot. So to turn one rule off, re-apply the sheet without it —
keep your rules in a table and pass that:

```lua
local rules = {}
local function restyle(key, props)
  rules[key] = props                                                  -- props = nil removes the rule
  if next(rules) == nil then hafen.ui.skin(nil) else hafen.ui.skin(rules) end
end
```

The change is **live** — existing text re-renders on the spot — and the sheet is **owned**: it is dropped
automatically on your addon's `:reload`/disable, so the stock client is always restorable.

**A sheet is an ordinary table, so it can come from anywhere — including a file.** The bundled
[`theme`](../../../addons/theme) example addon reads a `theme.json` through
[`hafen.asset`](asset.md#data--text) + [`hafen.json`](json.md) and maps each rule's font descriptor to a
handle; its Lua never names a surface, a font, a size or a colour. A theme with no code of its own is one
`:theme on` away.

> `hafen.font.setFont`, `hafen.font.reset` and `hafen.font.scopes` are **gone** — they read as plain `nil`. A
> font is now one *property* of a rule, and the key is a *selector*, so there is one vocabulary for "which part
> of the UI" instead of a scope enum beside it. [`hafen.font(name)`](fonts.md#the-built-ins--hafenfontname) is
> untouched: it still names an engine font, and a `.ttf` your addon ships is still
> [`hafen.asset(path)`](asset.md).

### Site keys — the surfaces this ships

A key is [a selector](#selectors--naming-a-widget), and it resolves one of two ways. A **site key** names a
place the client *draws*, and is resolved there — these are the eleven that work today:

| Key | What it styles |
|---|---|
| `*` | the global fallback — most UI text, and the cascade for every rule you do not write |
| `window.title` | window captions |
| `heading` | in-window section headings (the embossed fraktur ones) |
| `button` | button captions |
| `label` | body text — attribute rows, list items, explicit-foundry labels |
| `textentry` | text-entry fields **and** the console command line |
| `tooltip` | every tooltip — items, buffs, meters, craft, minimap, the action menu |
| `menu` | flower-menu petals + the action-menu keybind letters |
| `chat` | the chat window — messages, channel tabs, the typed line |
| `world.nick` | floating kin names over characters |
| `world.speech` | speech bubbles |

Each surface keeps **its own stock size and colour** unless your rule overrides them — one key can front two
sites with different stocks (`textentry` covers the serif-12 fields *and* the mono-12 wheat command line), and
both stay native under one rule. What each key does with each property is the
[property × key table](#what-each-key-accepts) below; see [`hafen.font`](fonts.md#site-keys) for the
per-surface notes and geometry caveats.

### Tree keys are accepted, and do nothing yet

Any other valid selector — `@Class`, `[title=…]`, `[res=…]`, or the roles that classify a *widget* rather than a
site (`window`, `inventory`) — is a **tree key**, resolved per widget against the live tree. That is the next
feature. Today such a rule **parses fine and is silently inert**: never an error, so a sheet written for it
loads now, unstyled, instead of blowing up. A key that is not valid *grammar* is still an error, and exactly the
error [`hafen.ui(selector)`](#selectors--naming-a-widget) gives.

### Properties

| Property | Value | Notes |
|---|---|---|
| `font` | a [font handle](fonts.md) | `hafen.font(name)` or `hafen.asset(path)`, optionally `:derive{size=,bold=,…}` |
| `color` | `{r, g, b [, a]}`, 0–255 | also spelled `{r = …, g = …, b = …}` — the shape every reader hands back |

**The two are independent.** A rule may carry either alone: a colour-only rule leaves the surface's own font
exactly as it is, a font-only rule leaves its colour. A rule carrying neither styles nothing.

An **unknown property is an error** naming the ones that exist — unlike an unresolved key, a misspelt property
has no later meaning to wait for.

#### `color` — a surface's colour is the sheet's

Where a rule sets a colour, the surface **draws in it even when the client itself asks for another**. That is
what a stylesheet is for, and it is worth knowing what it costs: while the rule is on, text that carries
*meaning* in its colour is flattened with the rest — a red warning under `["*"] = {color=…}` goes the same
colour as everything else. Style one site rather than `*` when that matters.

Two things still win over a rule, and one surface ignores it:

- **`$col[…]` markup inside the text** — it is part of the string, not the site's choice of colour, so a tooltip's
  green/red attribute deltas survive a `["tooltip"]` colour rule.
- **[`widget:setFont`](fonts.md#restyle-one-widget--widgetsetfonth)**, which sits above every site rule.
- **An embossed surface** — a window caption, a section heading, an ordinary button caption — takes its colour
  from a *texture* tiled through the glyph mask, not from the font, so it follows a `font` rule and ignores a
  `color` one. Not a bug to report; there is nothing there to colour. See
  [what each key accepts](#what-each-key-accepts).

> **A font handle's own `color` does not style a surface.** `hafen.font("serif"):derive{color = {255,0,0}}`
> installed through `skin{}` (or `widget:setFont`) contributes its family, size and antialiasing — its **colour
> is ignored**. That colour is for [your own drawing](fonts.md#draw-with-it--your-own-drawing): `g:text`, your
> own widgets. One question, "what colour is this surface", has exactly one answer, and it is written in the
> sheet where you can see it.

### What each key accepts

Both properties are accepted on **every** key — a sheet never errors because a surface cannot use one. What
differs is what the surface *does* with it, and this is the honest table:

| Key | `font` | `color` | Worth knowing |
|---|---|---|---|
| `*` | ✅ | ✅ | cascades to every key you do not write — including the colour |
| `window.title` | ✅ | ❌ **ignored** | an *embossed* surface: a texture is tiled through the glyph mask, so there is nothing left to tint |
| `heading` | ✅ | ❌ **ignored** | embossed the same way. Two stock sizes (25 px / 18 px) ride this key and a size-less rule keeps each |
| `button` | ✅ | ⚠️ **partly** | the ordinary caption is embossed (ignored); a `wrapped` multi-line caption, or one the client sets *with* a colour, follows the rule. Stock is **bold serif 12** — a serif 12 rule installs correctly and looks like nothing happened |
| `label` | ✅ | ✅ | `size=` clips: row heights were measured at construction |
| `textentry` | ✅ | ✅ | `size=` clips: a field's height comes from its background texture, not the font |
| `tooltip` | ✅ | ✅ | `$col[…]` rows keep their own colour; `size=` is safe (a tip sizes its box around its text) |
| `menu` | ✅ | ✅ | `size=` is safe — a petal re-sizes around its own centre |
| `chat` | ✅ | ✅ | colour is how you tell area from party from private: one rule paints them alike |
| `world.nick` | ✅ | ✅ | a `color` rule flattens the kin-**group** colours; a font-only rule leaves them |
| `world.speech` | ✅ | ✅ | `size=` is safe — the bubble measures its frame around the text every frame |
| any tree key | — | — | [accepted and inert](#tree-keys-are-accepted-and-do-nothing-yet) until C1b; its *properties* are still validated |

**Where `color` is ignored, it is the same reason every time**: the surface is *embossed* — the client renders
the text as a mask and tiles a texture through it, then blurs a shadow behind. The glyph colour is discarded
before anything reaches the screen, so there is nothing for a rule to override. Those surfaces still follow a
`font` rule perfectly. Nothing is refused and nothing warns: a `color` on such a key is simply inert.

Three more limits are structural rather than per-key, and none of them is a bug to report:

- **Text the client rasterised into a `static` field at class-load** can never follow a rule — the JVM does not
  re-run a static initialiser. One such row (a tooltip's `Gilding:` heading) is reached by shipping a local copy
  of that resource's code; see [`hafen.font`](fonts.md#site-keys).
- **`$col[…]` markup wins over a `color` rule**, everywhere. It is part of the *string*, not the site's choice.
- **A rule flattens colour that carried meaning.** While `["*"] = {color=…}` is on, a red warning is the same
  colour as everything else. Style one key rather than `*` when that matters.

Geometry is never changed by a rule: nothing in a sheet resizes a widget. A larger `size=` can still *clip*
where a surface's box was measured from the stock font — the per-surface notes in
[`hafen.font`](fonts.md#site-keys) say which ones, and why.

### Cascade & conflict

Resolution is **most-specific first**: [`widget:setFont`](fonts.md#restyle-one-widget--widgetsetfonth) → the
matching site rule → the `*` rule → the client's stock. So `["*"]` alone changes everything, and any other key
refines one surface out of that cascade.

Two addons styling the same surface is shared client state, resolved the same way as
[`widget:replace`](#replacing-a-native-window): each surface holds a **stack of rules tagged with their owning
addon, and the last applied wins**. Disabling that addon pulls its entries and the surface falls back to the
next owner beneath — or to stock when there is none. Deterministic, and reversible per owner.

## The `g` draw wrapper

Draw callbacks (`onDraw`, `overlay`, `gobOverlay`) receive `g`, a drawing surface. Its coordinates are
the callback's local pixel space (widget-local for a widget, screen for a HUD overlay, the gob's screen
point for a gob overlay). Methods are colon-calls.

| Method | Description |
|---|---|
| `g:text(str, x, y [, {font, color}])` | draw text at the top-left of `(x, y)`; optional per-call font/colour (F2) |
| `g:atext(str, x, y, ax, ay [, {font, color}])` | anchored text; `ax`/`ay` 0..1 pick which point of the text sits at `(x, y)` |
| `g:rect(x, y, w, h)` | one-pixel outline rectangle |
| `g:frect(x, y, w, h)` | filled rectangle |
| `g:line(x1, y1, x2, y2 [, width])` | a line (`width` default 1) |
| `g:poly(x1, y1, x2, y2, x3, y3, ...)` | a **filled** convex polygon (≥ 3 points) in the current colour — e.g. a triangle |
| `g:prect(cx, cy, radius, fraction)` | a clockwise pie/progress wedge (0..1) — for cooldowns/meters |
| `g:image(img, x, y [, w, h])` | draw an [image asset](asset.md) at native size (or scaled into `w × h`) |
| `g:aimage(img, x, y, ax, ay)` | draw an [image asset](asset.md) anchored; `ax`/`ay` 0..1 pick which point sits at `(x, y)` |
| `g:resource(name, x, y [, w, h])` | draw an engine `.res` image **by name** at native size (or scaled into `w × h`) |
| `g:color(r, g, b [, a])` | set the draw colour (0..255); `g:color()` resets to white |

`g` is valid only during the draw callback — stashing it and drawing later does nothing (it goes
inert). To draw your own PNG images, load them with [`hafen.asset`](asset.md) and blit with
`g:image`/`g:aimage`. To draw the **client's own `.res` art** (action icons, hud pieces) — e.g. the icon of
the action a widget received from [`onDrop`](#custom-windows--widgets) — use `g:resource(name, …)`. It resolves the
resource **asynchronously and caches** it, and is **`Loading`-guarded** (draws nothing until the texture is
ready, then blits the resource's default image layer). It draws the **static icon only** — no live sprite /
cooldown sweep. A bad name simply draws nothing.

**Text fonts (F2).** `g:text`/`g:atext` take an optional trailing `{ font = h, color = {r,g,b[,a]} }` — render
one call in a [loaded font](fonts.md) and/or tint it. A widget's `font =` option supplies the default when a
call gives none. The string may also carry rich-text markup — `$font[family,sz]{…}` (mix fonts on one line via
`h:family()`), `$col`, `$b`, `$i`, `$u`, `$size`. Plain text with no font/markup is unchanged. See
[`hafen.font`](fonts.md#draw-with-it--your-own-drawing).

### Text is cached across frames

Rasterising a line of text costs roughly **50x what the geometry calls cost** — a font layout, a glyph raster
and a GPU texture upload. `g:text`/`g:atext` therefore **keep the rendered text and reuse it**, so drawing the
same string in the same font every frame rasterises it *once*. You do not opt in and there is nothing to hold:
the cache is per addon, invisible, and dropped (with its textures) when you `:reload` or disable.

What that means when you write a draw callback:

- **The cache key is the string + the font.** Same text, same font ⇒ a hit, however many draw sites or frames
  apart. **Colour is not in the key** — it is applied as a tint over the same raster, so drawing one string in
  two colours in one frame is *one* entry, and animating a colour costs nothing.
- **A string that changes every frame is re-rasterised every frame.** A clock, an FPS readout or a coordinate
  line whose digits move can never hit, and a miss costs exactly what every draw cost before the cache existed.
  **Budget a live readout by how often its *text* changes, not by how many lines it has** — `"HP: 100/100"`
  redrawn 60 times is free; `"HP: 100/100 (12.483 s)"` is 60 rasterisations.
- **Font overrides still take effect immediately.** The key carries the font generation, so installing, moving
  or resetting a font ([`hafen.ui.skin`](#the-stylesheet--restyling-the-client),
  [`widget:setFont`](#reads--they-answer-on-every-widget)) restyles
  on the next frame — the old entries simply stop being looked up and age out.
- **It is bounded, not a leak.** An LRU of at most **512 entries / 8 MiB** of texture; the least recently used
  entries are evicted and their textures disposed. An addon that draws thousands of distinct strings settles at
  the cap instead of growing.

`hafen.client:profiling():textcache()` reports what your addon's cache is holding and its hit rate — see
[the counters](client.md#textcache). Rich-text markup is cached on the same terms as plain text.
