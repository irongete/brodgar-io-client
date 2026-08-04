# hafen.ui: the client's UI and your own

`hafen.ui` is everything on screen: windows you draw yourself, the client's own widgets, and the
stylesheet that says what all of it looks like. Reach for it to add a HUD, to read or move a native
window, or to restyle the client.

**There is one type.** A window you create, a native window you find, the deepest widget under the
cursor and the container an [`appear` subscription](replace.md#watching-for-a-widget) hands your
callback are all the same [Widget object](widget.md). What you create and what you find are not
different things.

**And one way to name one.** `hafen.ui` is callable: `hafen.ui("window[title=Cupboard]")` is the first
matching widget, `hafen.ui.all("inventory")` is every one. That same [selector](selectors.md) is the key
of a [stylesheet](style/README.md) rule, so there is one vocabulary for "which part of the UI", not two.

Everything here is client-side and **ungated**, and everything is bridge-owned: a window you create, an
overlay you install, a sheet you apply and a widget you moved are all given back on `:reload` or disable.
Client-side UI cannot send actions to the server — that is [`hafen.act`](../act.md).

```lua
hafen.ui.window{
  title = "Clock", size = {160, 40}, pos = {50, 50},
  onDraw = function(g, w, h)
    g:text(string.format("%.0f", hafen.time():clock() or 0), 6, 12)
  end,
}
```

## Reading order

Four tracks, each self-contained. Start wherever your task is.

**Draw your own UI** — [custom](custom.md) builds the window or overlay, [drawing](drawing.md) paints
inside it.

**Point at the client's UI** — [selectors](selectors.md) names a widget, [widget](widget.md) reads it,
[items](items.md) reads what is inside a container.

**Change the client's UI** — [native](native.md) moves and hides one, [replace](replace.md) waits for a
window and puts yours in its place.

**Restyle it** — [style](style/README.md) is the sheet: one table of rules for fonts, colours, chrome
and layout.

## Pages

| Page | What it covers |
|---|---|
| [custom](custom.md) | your own windows, widgets and overlays, and their callbacks |
| [widget](widget.md) | the Widget object: every read, and which writes answer on a widget you do not own |
| [selectors](selectors.md) | naming a widget: the grammar, the roles, the inspector, hit-testing |
| [items](items.md) | the items inside a container, and the three subscriptions on it |
| [native](native.md) | moving and hiding the client's own widgets, and what comes back |
| [replace](replace.md) | watching for a widget, and standing your own window in its place |
| [drawing](drawing.md) | the `g` wrapper: text, shapes, images, and the raster cache behind them |
| [style](style/README.md) | the stylesheet: rules, the cascade, and where skinning ends |

## See also

- [`hafen.font`](../font.md) — the handles a `font` property and a `g:text` call take
- [`hafen.asset`](../asset.md) — the images and fonts your addon ships
- [`hafen.act`](../act.md) — acting on a widget you found, through its `:id()`
- [conventions](../conventions.md#widget-a-piece-of-the-ui) — where a Widget sits among the other references
- [events](../event.md) — the bus, for everything that is not a widget subscription
