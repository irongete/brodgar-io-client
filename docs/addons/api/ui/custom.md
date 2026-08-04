# hafen.ui: your own windows and overlays

Two builders make a surface of your own — a window with chrome, or a bare rectangle — and two more paint
over the HUD and the 3D world without owning anything. All four are ungated, and all four are torn down
with your addon.

```lua
local win = hafen.ui.window{
  title = "Clock", size = {160, 40}, pos = {50, 50},
  onDraw = function(g, w, h)
    g:color(255, 255, 0)
    g:text(string.format("%.0f", hafen.time():clock() or 0), 6, 12)
  end,
}
win:position(320, 200)                        -- the same object hafen.ui():at() would give you
```

## Windows and widgets

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.window(opts)` | [Widget](widget.md) | a draggable, titled window wrapping your content |
| `hafen.ui.widget(opts)` | [Widget](widget.md) | a bare content rectangle, no chrome |

`opts` is a table and every key is optional:

| Key | Type | Meaning |
|---|---|---|
| `size` | `{w, h}` | initial size |
| `pos` | `{x, y}` | initial position |
| `parent` | `"root"` \| `"gameui"` | where to attach; default `"root"` |
| `title` | string | window caption (windows only) |
| `font` | [`FontHandle`](../font.md) | default font for this widget's `g:text`/`g:atext` draws, not for the title bar |
| `onDraw` | `fn(g, w, h)` | draw the content — see [the `g` wrapper](drawing.md) |
| `onTick` | `fn(dt)` | per-frame update; `dt` is seconds |
| `onClick` | `fn(x, y, button, mods)` | mouse press; return truthy to consume it |
| `onMouseUp` | `fn(x, y, button, mods)` | mouse release |
| `onMouseMove` | `fn(x, y, mods)` | mouse move over the widget |
| `onWheel` | `fn(x, y, amount, mods)` | mouse wheel |
| `onDrop` | `fn(x, y, drop)` | something was dropped on the widget; return truthy to consume it |
| `onClose` | `fn()` | window close button (windows only) |

Sizes and positions are raw pixels, not DPI-scaled. `pos` is within the parent; on a window `size` is the
**content** size and the chrome is fitted around it.

**`mods`** is the trailing `{shift, ctrl, alt}` boolean table on every mouse callback — the modifier state
**at press time**, so you can branch a Shift-drag against a plain click. It is additive: a handler that
ignores the extra argument is unaffected. Same shape as [`hafen.hook():grab`](../hook.md#hafenhookgrabmove-up)'s
`mods`.

### `onDrop` makes a widget a drop target

`onDrop` opts the widget into the client's own drag gesture: drag a menu-grid action onto it and
`onDrop(x, y, drop)` fires with widget-local pixels and a neutral descriptor,
`drop = { kind = "pagina", res = "<resource name>" }`. `res` is a plain resource name — draw its icon with
[`g:resource`](drawing.md), persist it with [`hafen.store`](../store.md). It is present only for
resource-based actions; an id-only action carries `kind` alone, which is usable in-session but not reliably
persistable. Firing the dropped action is not part of `onDrop`.

## Overlays

An overlay paints every frame without being a widget: there is nothing to place, nothing to size and
nothing in the tree.

| Function | Returns | Description |
|---|---|---|
| `hafen.ui.overlay(fn)` | [handle](#overlay-and-observer-handles) | paint `fn(g, w, h)` on top of the HUD each frame; `w, h` is the screen size |

```lua
hafen.ui.overlay(function(g, w, h)
  g:color(255, 200, 0)
  g:atext("hello", w / 2, 4, 0.5, 0)      -- centred along the top of the screen
end)
```

This is the **HUD**. To paint over a **game object** instead, the verb is on the object:
[`gob:overlay()`](../gob.md#overlays) — you name the gob it hangs on, so nothing is searched per
frame. To stand something in the **world** rather than over it, use [`hafen.render`](../render/README.md)
for your own images and models, or [`hafen.ghost`](../ghost.md) for the game's own props.

## Overlay and observer handles

`overlay` and [`hafen.ui.on`](replace.md#watching-for-a-widget) each return a handle with a
single method:

| Method | Description |
|---|---|
| `:remove()` | stop it; also done automatically on reload or disable |

This is not a [Widget](widget.md) — a Widget's own removal verb is `:destroy()`.

## See also

- [drawing](drawing.md) — what `g` can do, and why text is nearly free to redraw
- [widget](widget.md) — the object both builders return, and what you can do to it afterwards
- [`hafen.font`](../font.md) — the handle the `font` option takes
- [style](style/README.md) — restyling the client's surfaces rather than drawing your own
- [`hafen.render`](../render/README.md) — the same idea in the 3D world
