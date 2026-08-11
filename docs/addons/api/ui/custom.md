# hafen.ui: your own windows and overlays

Three builders make a surface of your own — a window with chrome, a bare rectangle, or a painter over the
whole HUD. Each is built **bare** and configured by chained setters. All three are unprotected, and all three
are torn down with your addon.

This page is about the surfaces you **paint**. To put one of the client's own controls in one instead of
drawing it, see [controls](controls/README.md).

```lua
local win = hafen.ui():window()
  :title("Clock")
  :size(160, 40)
  :position(50, 50)

win:on("Draw", function(ev)
  local g = ev:g()
  g:color(255, 255, 0)
  g:text(string.format("%.0f", hafen.time():clock() or 0), 6, 12)
end)

win:position(320, 200)                        -- the same object hafen.ui():at() would give you
```

## Windows and widgets

| Verb | Returns | Description |
|---|---|---|
| `hafen.ui():window()` | [Widget](widget.md) | a draggable, titled window wrapping your content |
| `hafen.ui():widget()` | [Widget](widget.md) | a bare content rectangle, no chrome |

Neither takes an argument. A surface is born with the client's own defaults — no caption, a place and a
size it did not choose — and every property is a setter on the [Widget](widget.md) it hands back:

| Setter | Read | Meaning |
|---|---|---|
| `:title(s)` | `:title()` | window caption; a bare widget has no chrome to write it on and refuses |
| `:parent(w)` | `:parent()` | which widget it hangs under; the default is `hafen.ui():root()` |
| `:position(x, y)` | `:position()` | place within the parent, in [design pixels](pixels.md) |
| `:size(w, h)` | `:size()` | content size; a window's chrome is fitted around it |
| `:font(h)` | `:font()` | default font for this widget's `g:text`/`g:atext` draws, not for the title bar |

Every setter returns the widget, so a whole surface is one expression; every one has a matching bare read,
so nothing you configured needs a variable of its own to be readable later. All five answer only on a
surface **your** addon painted — a native widget has nowhere to put a caption of yours, and neither does
a [control](controls/README.md), which the client draws and drives.

Sizes and positions are [design pixels](pixels.md): what you write is what you read back, on every client
whatever the user's interface scale. `:position` is within the parent; on a window `:size` is the
**content** size, so the outer box it reads back is that plus the chrome.

## Subscribing

A window or a bare widget answers the five universal [`:on(key, fn)`](widget.md#subscribing) keys every
widget does — `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Destroy` — plus four more of its own, since
it is a surface with content to paint and a lifetime to report:

| Key | handler receives | Cancelable | Fires |
|---|---|---|---|
| `Draw` | `ev` — `:g()` `:w()` `:h()` | no | every frame; `:w()`/`:h()` is the box you sized, in [design pixels](pixels.md) — see [the `g` wrapper](drawing.md) |
| `Tick` | `dt` | no | every frame, before `Draw` |
| `Drop` | `ev` — `:x()` `:y()` `:thing()` `:preventDefault()` | yes | the client's drag gesture drops something on it |
| `Close` | — | no | the window's close button; a bare widget has none, so it never fires |

`:on(key, fn)` is its own statement, after the builder chain that made the widget finishes — it hands back
a subscription, not the widget, so it cannot sit mid-chain or be a chain's last call. Two handlers on
`Draw` both paint, in registration order; two on any of these both fire.

### A surface never paints half-configured

A bare `:window()` is in the tree the instant it is built — `hafen.ui():at(x, y)`, a selector and an
`"appear"` subscription all find it at once — but it **draws nothing until the tick after the statement
that built it**. So a caption, a size and a place you set across several lines are all in place before the
first pixel, whatever falls between them, and there is no "commit" verb to forget.

Two things follow. A surface you build and destroy in the same breath never appears at all. And
`:parent(w)` is a *building* verb: it answers while the surface is still being built and refuses once it is
on screen, where the way to move a widget is `:position(x, y)`.

```lua
local hud = hafen.ui():find("@GameUI")               -- the HUD is just another widget
local panel = hafen.ui():widget():parent(hud):size(120, 40)
```

### `Drop` makes a widget a drop target

`:on("Drop", fn)` opts the widget into the client's own drag gesture: drag a menu-grid action onto it and
`fn(ev)` fires with `ev:x()`/`ev:y()` in widget-local [design pixels](pixels.md) and `ev:thing()` a neutral descriptor,
`{ kind = "pagina", res = "<resource name>" }`. `res` is a plain resource name — draw its icon with
[`g:resource`](drawing.md), persist it with [`hafen.store`](../store.md). It is present only for
resource-based actions; an id-only action carries `kind` alone, which is usable in-session but not reliably
persistable. Firing the dropped action is not part of it.

## Overlays

An overlay paints every frame without being a widget: there is nothing to place, nothing to size and
nothing in the tree.

| Verb | Returns | Description |
|---|---|---|
| `hafen.ui():overlay()` | Overlay | a painter over the whole HUD, built bare |

| Method | Description |
|---|---|
| `:onDraw(fn)` / `:onDraw()` | paint `fn(g, w, h)` on top of the HUD each frame; `w, h` is the screen size, the pair `hafen.ui():root():size()` answers |
| `:destroy()` | stop it; also done automatically on reload or disable |
| `:exists()` | is it still painting |

```lua
hafen.ui():overlay():onDraw(function(g, w, h)
  g:color(255, 200, 0)
  g:atext("hello", w / 2, 4, 0.5, 0)      -- centred along the top of the screen
end)
```

Until it has a painter it paints nothing, which is the same rule the widget builders get from not drawing
before their first tick. `hafen.ui():overlay()` **mints** one rather than handing back a collection: a HUD
painter has no key, so there would be nothing to address into.

This is the **HUD**. To paint over a **game object** instead, the verb is on the object:
[`gob:overlay()`](../overlay.md) — you name the gob it hangs on, so nothing is searched per
frame. To stand something in the **world** rather than over it, use [`hafen.vr`](../vr/README.md) — your
own images and models, the game's own props, or [this very window](../vr/widgets.md), drawn out there
instead of on the screen.

The bundled **`widgetstack`** addon is all three at once: a window it builds and toggles, an inspector
window per widget you click, and a HUD overlay that outlines whatever the cursor is over.

## Observer handles

[`hafen.ui():on`](replace.md#watching-for-a-widget) returns a handle with a single method:

| Method | Description |
|---|---|
| `:remove()` | stop watching; also done automatically on reload or disable |

This is neither a [Widget](widget.md) nor an overlay — a Widget's own removal verb is `:destroy()`, and so
is an overlay's.

## See also

- [drawing](drawing.md) — what `g` can do, and why text is nearly free to redraw
- [widget](widget.md) — the object both builders return, and what you can do to it afterwards
- [`hafen.font`](../font.md) — the handle `:font(h)` takes
- [style](style/README.md) — restyling the client's surfaces rather than drawing your own
- [`hafen.vr`](../vr/README.md) — the same idea in the 3D world
