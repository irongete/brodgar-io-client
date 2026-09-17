# hafen.ui: The Client's UI and Your Own

`hafen.ui` builds windows and controls of your own, reads and changes the client's widgets, and restyles both through one stylesheet.

```lua
local session = hafen.session():current()
hafen.log():write(session:ui():inventory():items():count() .. " items in the backpack")

local clock = hafen.ui():window():title("Clock"):size(160, 40):position(50, 50)
clock:on("Draw", function(draw_event)
  draw_event:g():text(string.format("%d", hafen.time():clock() or 0), 6, 12)
end)
```

---

## One type, two trees

Every widget is the same [Widget](widget.md) object. One you build, one the client put up, the one under the cursor, the container an `Added` subscription hands you.

| Tree | Reached through | Holds |
|---|---|---|
| The addon layer | `hafen.ui():window()`, `:widget()`, `:column()`, the controls | What you build. Drawn above every session and above the login screen. Stays when the player tabs between characters. |
| A character's tree | `session:ui():match(selector)`, `:matchAll(selector)`, `:root()`, `:node(id)`, `:inventory()`, `:equipment()` | What the client put up for that character. Nothing you built is under it. Nothing the client built is reachable without it. |

Both trees take the same [selector](selectors.md), which is also the key of a [stylesheet](style/README.md) rule. The pointer, hit tests and the [scale](pixels.md) are `hafen.ui()`'s alone: one screen, however many characters are logged in.

| Fact | Rule |
|---|---|
| Permission | Unprotected, except [`widget:send`](widget.md#send-a-message-protected) (`widget.send`) and [`widget:value(v)`](edit.md#driving-one-protected) (`widget.value`) on a client widget. |
| Lifetime | Every window, overlay, sheet, subscription and native-widget change your addon made is given back on `:reload` and on disable. |

---

## Pages

| Page | Covers |
|---|---|
| [custom](custom.md) | Your own windows and bare canvases: setters, events, packing, resizing. |
| [overlay](overlay.md) | Painting over the screen or over one widget: the keyed collection and its draw order. |
| [controls](controls/README.md) | The client's own controls, built and owned by your addon. |
| [column](column.md) | A column or a row that lays its children out and sizes itself to them. |
| [lists](lists.md) | Listbox, dropdown, menu, grid and table: the row-source controls. |
| [widget](widget.md) | The Widget object: every read, subscriptions, tooltips, focus, sending a message. |
| [writes](writes.md) | Which writes answer on a widget you built and on one you found. Disabling one of yours. |
| [selectors](selectors.md) | The selector grammar, the roles, hit-testing, the inspector. |
| [items](items.md) | The items the client draws: reads and the protected item actions. |
| [contents](contents.md) | What one item holds: nested items, a stated line, a fill meter. |
| [container](container.md) | `ItemAdded`/`ItemRemoved` on a container, and how deep they reach. |
| [mouse](mouse.md) | The pointer: position, hover, pick, modifiers, cursor, grab. |
| [pixels](pixels.md) | Design pixels, the unit of every coordinate, and the scale in force. |
| [native](native.md) | Moving, hiding and re-homing client widgets. Letting the user drag and size one. `remember`. |
| [edit](edit.md) | Changing what a client control says or does, and intercepting it. |
| [replace](replace.md) | Watching for a widget and standing your own window in its place. |
| [drawing](drawing.md) | The `g` wrapper: text, shapes, images, measuring, the raster cache. |
| [style](style/README.md) | The stylesheet: rules, properties, the cascade. |

---

## See Also

- [`hafen.font`](../font.md) — the handles a `font` property and `g:text` take.
- [`hafen.asset`](../asset/README.md) — the images and fonts your addon ships.
- [Widgets in the world](../virtual/widgets.md) — a surface of yours drawn in the 3D scene.
- [References](../references.md#widget-a-piece-of-the-ui) — where a Widget sits among the other reference types.
- [Events](../event/bus/README.md) — the bus, for everything that is not a widget subscription.
