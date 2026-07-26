# hafen.ui — windows, overlays, widget replacement, introspection

Draw your own client-side UI, paint over the HUD and the 3D world, observe/adopt/replace the client's
own server widgets, and [walk any widget's tree](#introspecting-the-widget-tree). Everything here is
client-side and bridge-owned — it is torn down automatically on reload/disable. (Client-side UI cannot
send actions to the server; that is [`hafen.act`](actions.md).)

## Custom windows & widgets

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.window(opts)` | [window handle](#window--widget-handle) | a draggable, titled window wrapping your content |
| `hafen.ui.widget(opts)` | [widget handle](#window--widget-handle) | a bare content rectangle (no chrome) |

`opts` (all optional):

| Key | Type | Meaning |
|---|---|---|
| `size` | `{w, h}` | initial size |
| `pos` | `{x, y}` | initial position |
| `parent` | `"root"` \| `"gameui"` | where to attach (default `"root"`) |
| `title` | string | window title (windows only) |
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

### Window / widget handle

| Method | Description |
|---|---|
| `:move(x, y)` | reposition |
| `:show()` / `:hide()` | toggle visibility |
| `:visible()` | is it visible? |
| `:pack()` | shrink-wrap to content |
| `:size(w, h)` | resize |
| `:destroy()` | remove it |

```lua
local win = hafen.ui.window({
  title = "Clock", size = {160, 40}, pos = {50, 50},
  onDraw = function(g, w, h)
    g:color(255, 255, 0)
    g:text(string.format("%.0f", hafen.time.clock() or 0), 6, 12)
  end,
})
```

## Overlays

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.overlay(fn)` | [`{ :remove() }`](#overlay--observer-handles) | paint `fn(g, w, h)` on top of the HUD each frame (`w`,`h` = screen size) |
| `hafen.ui.gobOverlay(filter, fn)` | [`{ :remove() }`](#overlay--observer-handles) | paint `fn(g, gob, sx, sy)` over each matching gob |

For `gobOverlay`, `filter` is the canonical [filter](conventions.md#the-filter-argument) over the gob
snapshot (a function or a name substring), `gob` is the [`Gob`](types.md#gob) snapshot, and `sx, sy` is
its projected screen point (just above the head).

```lua
hafen.ui.gobOverlay("gfx/borka/body", function(g, gob, sx, sy)
  g:color(0, 255, 0)
  g:atext("player", sx, sy, 0.5, 1)   -- centred just above the head
end)
```

## Observing & replacing the client's own UI

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.onWidgetCreate(fn)` | [`{ :remove() }`](#overlay--observer-handles) | `fn(desc)` for every server widget as it is placed |
| `hafen.ui.adopt(id)` | [model handle](#model-handle) \| nil | adopt a live server widget by its id as a hidden model |
| `hafen.ui.replace(type, opts, fn)` | [`{ :remove() }`](#overlay--observer-handles) | replace a native window with your own view |

### The widget descriptor

`onWidgetCreate`'s `fn(desc)` receives `desc = { id, type, place, caption, parentType }` — e.g. the
inventory is `{ type = "inv", place = "inv", parentType = "GameUI" }`; a cupboard is
`{ type = "wnd", place = "misc", caption = "Cupboard", parentType = "GameUI" }`. Any field may be
absent. This is observe-only (the return is ignored); to take over a widget, adopt or replace it.

### Model handle

`hafen.ui.adopt(id)` keeps the real, server-bound widget as a hidden **model** you present your own
view over. A hidden model still receives server updates, so it stays live.

| Method | Description |
|---|---|
| `:hide()` / `:show()` | toggle the widget's visibility (chainable) |
| `:visible()` | is it visible? |
| `:raw()` | the server widget id (a [WidgetRef](conventions.md#widgetref--a-window-or-widget)) |
| `:items()` | array of [`Item`](types.md#item) snapshots off its item children (empty for a non-inventory) |
| `:onItemAdded(fn)` | `fn(item)` when an item enters |
| `:onItemRemoved(fn)` | `fn(item)` when an item leaves |
| `:onDestroy(fn)` | `fn()` once, when the server destroys the widget |

`:items()` is read-only; to move items use the gated
[`hafen.act.item`](actions.md#hafenactitem) with the item's `handle`.

### Replacing a native window

`hafen.ui.replace(type, opts, fn)` finds a server widget by descriptor, adopts it as a hidden model,
and calls `fn(model)` — which draws a custom view (e.g. a `hafen.ui.window`) and **returns** it. It
also scans once for an already-open match, so it works whether the window is already open or opens
later. Disabling/reloading the addon (or the handle's `:remove()`) un-hides the native window,
restoring the stock UI.

`opts` (all optional): `context` (`"main"` = the main inventory), `caption` (an exact window title),
`match` (a predicate `match(desc)` over the [descriptor](#the-widget-descriptor)).

## Introspecting the widget tree

A generic, **read-only** way to walk *any* widget's children to arbitrary depth. Where `adopt`/`replace`
(above) target one known widget and the [typed reads](char.md) expose a fixed set of surfaces, this lets you
discover the structure of *any* open window from Lua — so you can build a pure-Lua adapter for a window
(products/prices/buttons of a Barter Stand, a vendor, a container) without a new Java adapter per UI.

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.root()` | [WidgetNode](#widgetnode) \| nil | the top of the whole client tree — walk **down** to any open window |
| `hafen.ui.node(id)` | [WidgetNode](#widgetnode) \| nil | a node for a server widget **id** (a `desc.id`, a `model:raw()`, another node's `:id()`); nil if it doesn't resolve |

`model:node()` is sugar for `hafen.ui.node(model:raw())` on an [adopted model](#model-handle).

### WidgetNode

An opaque, **facade-safe** handle over one widget (no raw widget crosses into Lua). It is a **lazy
handle**, not an owned resource — cheap to make and never registered, so a deep walk mints many nodes
freely and there is nothing to tear down. Every accessor returns `nil`/empty once the widget is
destroyed (the node detects it and lets go).

| Method | Returns | Description |
|---|---|---|
| `:type()` | string | class name, e.g. `"Inventory"`, `"Label"`, `"Button"` (for an anonymous subclass — common in Hafen — the nearest named superclass) |
| `:id()` | int \| nil | server widget id, or **nil if the widget is not server-bound** (client-only) |
| `:children()` | array | child `WidgetNode`s in tree order (empty for a leaf) |
| `:parent()` | WidgetNode \| nil | the parent node, or nil at the root |
| `:pos()` | `{x=,y=}` | position within the parent (widget-local px) |
| `:size()` | `{x=,y=}` | size |
| `:visible()` | boolean | is it visible? |
| `:text()` | string \| nil | best-effort text for text-bearing widgets (Label/Button/Window/TextEntry), else nil |
| `:walk(fn)` | (self) | depth-first visit — `fn(node, depth)`; **return `false` to prune** that subtree |
| `:same(other)` | boolean | true iff both handles wrap the **same live widget** (nil-safe) — the identity check |

**`:id()` is the pivot for acting.** Reading the tree is ungated client-side data. To *act*, read a
**server-bound** node's `:id()` and pass it to the gated [`hafen.act.raw(id, msg, …)`](actions.md) with
the message a client-only button would have sent (learned from the upstream widget class) — a `wdgmsg`
from an unbound (client-only, no `:id()`) node is dropped, so you never target the button itself, but
its nearest server-bound ancestor.

**`:same` is the identity primitive.** Each `node`/`children`/`walk` call mints a *fresh* handle, and
client-only children have no `:id()` to compare — so `:same` is the only reliable "is this the same
widget as before?" check. `:find`/`:collect` helpers are one-liners over `:walk` you keep in your own Lua.

```lua
-- dump an open window's full nested tree from the :lua REPL
hafen.ui.root():walk(function(n, d)
  hafen.log(string.rep("  ", d) .. n:type()
    .. (n:id()   and (" #" .. n:id())            or "")
    .. (n:text() and (" '" .. n:text() .. "'")   or ""))
end)
```

**Limits.** Read-only (no mutating a widget's Java state — desyncs from the server); `:text()` is
best-effort over a known type set (unknown → nil, never throws); the whole client tree is reachable via
`root()`/`:parent()` (all client-side data — actions stay separately gated); which child is a price vs. a
spacer is upstream-defined knowledge your Lua adapter supplies.

### Overlay / observer handles

`overlay`, `gobOverlay`, `onWidgetCreate`, and `replace` return a handle with a single method:

| Method | Description |
|---|---|
| `:remove()` | stop it (also done automatically on reload/disable) |

## The `g` draw wrapper

Draw callbacks (`onDraw`, `overlay`, `gobOverlay`) receive `g`, a drawing surface. Its coordinates are
the callback's local pixel space (widget-local for a widget, screen for a HUD overlay, the gob's screen
point for a gob overlay). Methods are colon-calls.

| Method | Description |
|---|---|
| `g:text(str, x, y)` | draw text at the top-left of `(x, y)` |
| `g:atext(str, x, y, ax, ay)` | anchored text; `ax`/`ay` 0..1 pick which point of the text sits at `(x, y)` |
| `g:rect(x, y, w, h)` | one-pixel outline rectangle |
| `g:frect(x, y, w, h)` | filled rectangle |
| `g:line(x1, y1, x2, y2 [, width])` | a line (`width` default 1) |
| `g:poly(x1, y1, x2, y2, x3, y3, ...)` | a **filled** convex polygon (≥ 3 points) in the current colour — e.g. a triangle |
| `g:prect(cx, cy, radius, fraction)` | a clockwise pie/progress wedge (0..1) — for cooldowns/meters |
| `g:image(img, x, y [, w, h])` | draw a [`hafen.render.image`](render.md) at native size (or scaled into `w × h`) |
| `g:aimage(img, x, y, ax, ay)` | draw a [`hafen.render.image`](render.md) anchored; `ax`/`ay` 0..1 pick which point sits at `(x, y)` |
| `g:resource(name, x, y [, w, h])` | draw an engine `.res` image **by name** at native size (or scaled into `w × h`) |
| `g:color(r, g, b [, a])` | set the draw colour (0..255); `g:color()` resets to white |

`g` is valid only during the draw callback — stashing it and drawing later does nothing (it goes
inert). To draw your own PNG images, load them with [`hafen.render.image`](render.md) and blit with
`g:image`/`g:aimage`. To draw the **client's own `.res` art** (action icons, hud pieces) — e.g. the icon of
the action a widget received from [`onDrop`](#windows--widgets) — use `g:resource(name, …)`. It resolves the
resource **asynchronously and caches** it, and is **`Loading`-guarded** (draws nothing until the texture is
ready, then blits the resource's default image layer). It draws the **static icon only** — no live sprite /
cooldown sweep. A bad name simply draws nothing.
