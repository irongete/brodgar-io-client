# hafen.ui — windows, overlays, widget replacement

Draw your own client-side UI, paint over the HUD and the 3D world, and observe/adopt/replace the
client's own server widgets. Everything here is client-side and bridge-owned — it is torn down
automatically on reload/disable. (Client-side UI cannot send actions to the server; that is
[`hafen.act`](actions.md).)

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
| `onClick` | `fn(x, y, button)` | mouse press; return truthy to consume it |
| `onMouseUp` | `fn(x, y, button)` | mouse release |
| `onMouseMove` | `fn(x, y)` | mouse move over the widget |
| `onWheel` | `fn(x, y, amount)` | mouse wheel |
| `onClose` | `fn()` | window close button (windows only) |

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
| `g:color(r, g, b [, a])` | set the draw colour (0..255); `g:color()` resets to white |

`g` is valid only during the draw callback — stashing it and drawing later does nothing (it goes
inert). Image drawing is not yet available.
