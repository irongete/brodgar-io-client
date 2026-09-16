# Custom Windows & Canvas Surfaces

Build a framed window or a bare canvas of your own, paint it on `Draw`, and let the user move, resize and close it.

```lua
local radar_window = hafen.ui():window()
  :title("Resource Radar")
  :size(220, 80)
  :position(100, 100)
  :resizable(true)
  :remember("radar")

radar_window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  graphics:color(30, 30, 30, 220)
  graphics:frect(0, 0, draw_event:w(), draw_event:h())
  graphics:color(255, 220, 120)
  graphics:text("Status: Active", 10, 10)
end)
```

---

## Builder Methods on `hafen.ui()`

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():window()` | `Widget` | Unprotected | A framed, captioned window the user drags by its title bar. Born 200x140 at (100, 100) with no caption. |
| `hafen.ui():widget()` | `Widget` | Unprotected | A bare canvas: no frame, no caption. Born 200x140 at (100, 100). |

A surface stands in the addon layer, above every session, and is in the tree from the statement that builds it. It draws nothing until the next tick, so a chain of setters is never painted half-configured. `:parent(w)` re-homes it only while it is still pending.

---

## Setters on a surface

Each setter chains; the bare call reads the value back.

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:title(caption)` | `string \| nil` | `self` | Unprotected | The window's caption; `nil` drops it. Refused on a bare canvas. |
| `:size(w, h)` | `number, number` | `self` | Unprotected | The content box, in design pixels. On a window the frame refits around it. |
| `:size()` | None | `{w, h}` | Unprotected | The content box: the pair you passed, and the box `Draw` paints. The frame's own box is `:chrome().frame`. |
| `:position(x, y)` | `number, number` | `self` | Unprotected | The top-left of the frame within the layer. |
| `:font(handle)` | `FontHandle` | `self` | Unprotected | The default font of this surface's `graphics:text`. |
| `:pack()` | None | `self` | Unprotected | Sizes the surface to its controls; a packed window keeps following them. |
| `:resizable(true)` | `boolean` | `self` | Unprotected | The client's own corner grip on a window of yours: the user drags the bottom-right corner, the content box follows live, and `Resized` fires on release. `false` removes it. Refused on a bare canvas and on a window the client built. |
| `:resizable(handle)` | `Widget` | `self` | Unprotected | Sizes the surface from a widget of yours instead (any surface, yours or the client's). `nil` drops the handle. |
| `:draggable(handle)` | `Widget` | `self` | Unprotected | Moves the surface from a widget of yours; a window already moves by its caption. |
| `:remember(name)` | `string` | `self` | Unprotected | Saves where the surface stands and, unless it is packed, its content box, and puts both back at once. See [native.md](native.md). |
| `:visible(shown)` | `boolean` | `self` | Unprotected | Draws or hides it. |
| `:destroy()` | None | None | Unprotected | Removes it and everything in it. |

`:resizable()` reads `true` while the client's grip is on, the handle when one is armed, else `nil`. The grip is the bottom-right corner of the content area, about 25 design pixels along each edge: a control of yours standing there takes the press first, so leave that corner to the canvas.

---

## Events on a surface

Subscribe with `surface:on(key, fn)`; the subscription ends with `sub:off()` and with the surface. `surface:events()` lists what a widget answers.

| Key | Handler receives | Cancel | Fires |
|---|---|---|---|
| `"Draw"` | `event` with `:g()`, `:w()`, `:h()` | No | Every frame, with the content box. |
| `"Update"` | `delta_seconds` | No | Every engine step, with no tree held: the place for reads of other widgets. |
| `"Close"` | `event` with `:preventDefault()` | Yes | The frame's close button. Left alone, the window is destroyed when the handlers return; cancelled, it stands and the handler says what the button means (`:visible(false)` hides it). |
| `"Resized"` | `event` with `:w()`, `:h()` | No | The user released the corner grip or a `:resizable(handle)` handle. The content box, as `:size()` reads it. |
| `"Dragged"` | `event` with `:x()`, `:y()` | No | The user released a `:draggable(handle)` handle. Where the surface landed. |
| `"Drop"` | `event` with `:x()`, `:y()`, `:thing()`, `:preventDefault()` | Yes | An action icon dropped on the surface. |
| `"MouseDown"`, `"MouseUp"` | `event` with `:x()`, `:y()`, `:button()`, `:preventDefault()` | Yes | A button pressed or released over it. |
| `"MouseMove"` | `event` with `:x()`, `:y()`, `:preventDefault()` | Yes | The pointer moved over it. |
| `"Wheel"` | `event` with `:x()`, `:y()`, `:amount()`, `:preventDefault()` | Yes | The wheel turned over it. |
| `"Removed"` | None | No | The surface left the tree. |

A surface that is destroyed fires nothing afterwards; a handler that keeps its window standing on `Close` is the one way to keep a toggle alive across the close button.

```lua
radar_window:on("Close", function(close_event)
  close_event:preventDefault()
  radar_window:visible(false)
end)

radar_window:on("Resized", function(resize_event)
  hafen.log():write(("radar is now %dx%d"):format(resize_event:w(), resize_event:h()))
end)
```

---

## See Also

- [2D Drawing](drawing.md) — what `event:g()` draws.
- [Widget](widget.md) — the reads every widget answers, `:chrome()` included.
- [Native Widgets](native.md) — `:remember`, `:draggable` and `:resizable` on the client's own windows.
- [Layout Columns & Rows](column.md) — controls laid out inside a surface.
