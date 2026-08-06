# hafen.ui: your own windows and overlays

Three builders make a surface of your own — a window with chrome, a bare rectangle, or a painter over the
whole HUD. Each is built **bare** and configured by chained setters. All three are ungated, and all three
are torn down with your addon.

This page is about the surfaces you **paint**. To put one of the client's own controls in one instead of
drawing it, see [controls](controls/README.md).

```lua
local win = hafen.ui():window()
  :title("Clock")
  :size(160, 40)
  :position(50, 50)
  :onDraw(function(g, w, h)
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
| `:position(x, y)` | `:position()` | place within the parent, in pixels |
| `:size(w, h)` | `:size()` | content size; a window's chrome is fitted around it |
| `:font(h)` | `:font()` | default font for this widget's `g:text`/`g:atext` draws, not for the title bar |
| `:onDraw(fn)` | `:onDraw()` | `fn(g, w, h)` draws the content — see [the `g` wrapper](drawing.md) |
| `:onTick(fn)` | `:onTick()` | `fn(dt)` per frame; `dt` is seconds |
| `:onClick(fn)` | `:onClick()` | `fn(x, y, button, mods)` mouse press; return truthy to consume it |
| `:onMouseUp(fn)` | `:onMouseUp()` | `fn(x, y, button, mods)` mouse release |
| `:onMouseMove(fn)` | `:onMouseMove()` | `fn(x, y, mods)` mouse move over the widget |
| `:onWheel(fn)` | `:onWheel()` | `fn(x, y, amount, mods)` mouse wheel |
| `:onDrop(fn)` | `:onDrop()` | `fn(x, y, drop)` something was dropped on it; return truthy to consume |
| `:onClose(fn)` | `:onClose()` | `fn()` the window's close button; inert on a bare widget |

Every setter returns the widget, so a whole surface is one expression; every one has a matching bare read,
so nothing you configured needs a variable of its own to be readable later. All thirteen answer only on a
surface **your** addon painted — a native widget has nowhere to put a caption or a callback of yours, and
neither does a [control](controls/README.md), which the client draws and drives.

Sizes and positions are raw pixels, not DPI-scaled. `:position` is within the parent; on a window `:size`
is the **content** size, so the outer box it reads back is that plus the chrome.

**`mods`** is the trailing `{shift, ctrl, alt}` boolean table on every mouse callback — the modifier state
**at press time**, so you can branch a Shift-drag against a plain click. Same shape as
[`hafen.hook():grab`](../hook.md#hafenhookgrabmove-up)'s `mods`.

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

### `onDrop` makes a widget a drop target

`:onDrop(fn)` opts the widget into the client's own drag gesture: drag a menu-grid action onto it and
`fn(x, y, drop)` fires with widget-local pixels and a neutral descriptor,
`drop = { kind = "pagina", res = "<resource name>" }`. `res` is a plain resource name — draw its icon with
[`g:resource`](drawing.md), persist it with [`hafen.store`](../store.md). It is present only for
resource-based actions; an id-only action carries `kind` alone, which is usable in-session but not reliably
persistable. Firing the dropped action is not part of `onDrop`.

## Overlays

An overlay paints every frame without being a widget: there is nothing to place, nothing to size and
nothing in the tree.

| Verb | Returns | Description |
|---|---|---|
| `hafen.ui():overlay()` | Overlay | a painter over the whole HUD, built bare |

| Method | Description |
|---|---|
| `:onDraw(fn)` / `:onDraw()` | paint `fn(g, w, h)` on top of the HUD each frame; `w, h` is the screen size |
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
[`gob:overlay()`](../gob.md#overlays) — you name the gob it hangs on, so nothing is searched per
frame. To stand something in the **world** rather than over it, use [`hafen.render`](../render/README.md)
for your own images and models, or [`hafen.ghost`](../ghost.md) for the game's own props.

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
- [`hafen.render`](../render/README.md) — the same idea in the 3D world
