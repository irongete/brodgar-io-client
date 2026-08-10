# hafen.ui: the client's UI and your own

`hafen.ui` is everything on screen: windows you draw yourself, the client's own widgets, and the
stylesheet that says what all of it looks like. Reach for it to add a HUD, to read or move a native
window, or to restyle the client.

**There is one type.** A window you create, a native window you find, the deepest widget under the
cursor and the container an [`appear` subscription](replace.md#watching-for-a-widget) hands your
callback are all the same [Widget object](widget.md). What you create and what you find are not
different things.

**And one way to name one.** `hafen.ui()` is the section, and every lookup is a verb on it:
`hafen.ui():find("window[title=Cupboard]")` is the one matching widget, `hafen.ui():all("inventory")` is
every one, and `hafen.ui():root()` is the top of the whole client tree. That same [selector](selectors.md)
is the key of a [stylesheet](style/README.md) rule, so there is one vocabulary for "which part of the UI",
not two.

Everything here is client-side and **unprotected**, and everything is bridge-owned: a window you create, an
overlay you install, a sheet you apply and a widget you moved are all given back on `:reload` or disable.
Client-side UI cannot send actions to the server; the one door that does is the protected
[`widget:send`](widget.md#send-a-message-protected).

```lua
local clock = hafen.ui():window():title("Clock"):size(160, 40):position(50, 50)
clock:on("Draw", function(ev)
  ev:g():text(string.format("%.0f", hafen.time():clock() or 0), 6, 12)
end)
```

## Reading order

Four tracks, each self-contained. Start wherever your task is.

**Draw your own UI** — [custom](custom.md) builds the window or overlay, [drawing](drawing.md) paints
inside it, [controls](controls/README.md) puts the client's own buttons in it instead of painting them, and
[lists](lists.md) does the same for a list, a dropdown or a menu of rows.

**Point at the client's UI** — [selectors](selectors.md) names a widget, [widget](widget.md) reads it,
[items](items.md) reads what is inside a container, [mouse](mouse.md) says where the pointer is. At a
screen point, `hafen.ui():at(x, y)` is what is under it and
[`hafen.ui():tipAt(x, y)`](widget.md#tooltips-and-focus) is whose tooltip would speak for it.

**Change the client's UI** — [native](native.md) moves and hides one, [replace](replace.md) waits for a
window and puts yours in its place.

**Restyle it** — [style](style/README.md) is the sheet: one table of rules for fonts, colours, chrome
and layout.

## Pages

| Page | What it covers |
|---|---|
| [custom](custom.md) | your own windows, widgets and overlays, and their callbacks |
| [controls](controls/README.md) | the client's own controls, built and owned by your addon |
| [lists](lists.md) | a list, dropdown or menu of rows, and the row source they share with a radio |
| [widget](widget.md) | the Widget object: every read, and which writes answer on a widget you do not own |
| [selectors](selectors.md) | naming a widget: the grammar, the roles, the inspector, hit-testing |
| [items](items.md) | the items inside a container, and the three subscriptions on it |
| [mouse](mouse.md) | the pointer: where it is, what is under it, the modifiers, and the grab |
| [native](native.md) | moving and hiding the client's own widgets, and what comes back |
| [replace](replace.md) | watching for a widget, and standing your own window in its place |
| [drawing](drawing.md) | the `g` wrapper: text, shapes, images, and the raster cache behind them |
| [style](style/README.md) | the stylesheet: rules, the cascade, and where skinning ends |

## See also

- [`hafen.font`](../font.md) — the handles a `font` property and a `g:text` call take
- [`hafen.asset`](../asset.md) — the images and fonts your addon ships
- [the Widget object](widget.md#send-a-message-protected) — sending a message from a bound widget
- [widgets in the world](../vr/widgets.md) — any of this, drawn in the 3D scene instead of on the screen
- [references](../references.md#widget-a-piece-of-the-ui) — where a Widget sits among the other references
- [events](../event.md) — the bus, for everything that is not a widget subscription
