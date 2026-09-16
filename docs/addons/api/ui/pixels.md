# hafen.ui: Design Pixels

Every coordinate and size a widget takes or gives is a design pixel: one pixel before the client applies the user's interface scale, the unit the client's own layout and art are written in.

```lua
local panel = hafen.ui():widget():size(100, 40):position(30, 20)
panel:size()                 -- {w = 100, h = 40}, on every client
hafen.ui():scale()           -- 1.5 on a client the user scaled up; nothing multiplies by it
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():scale()` | `number` | Unprotected | The interface scale in force, `1.0` or more. A diagnostic to print, not a unit: the numbers are already right. |
| `hafen.ui():scale(v)` | — | — | Raises, naming [`hafen.client():options():interface():scale(v)`](../client/README.md#interface), the user's preference, which takes a client restart; until then the two differ. |

---

## What the unit covers

| Where | Rule |
|---|---|
| Widget geometry | `:position(x, y)`, `:size(w, h)`, `:rootPos()`, `:info().pos`, `:info().size` — [a size in `w`/`h`, a place in `x`/`y`](../shapes.md#the-anonymous-shapes). What you write is what you read back, at any scale: the conversion happens once, at the edge, and is exact in that direction. |
| The screen's questions | The box a widget occupies, the point [`hafen.ui():hit(x, y)`](selectors.md#hit-testing) tests, [the pointer](mouse.md), and the `:x()`/`:y()` an [input event](widget.md#subscribing) carries are one space; none is reached through a [Session](../session.md), since the client draws one screen however many characters it holds. |
| The world | [`s:world():worldToScreen(p)`](../world.md#the-screen-and-the-world) answers a root pair in this unit, and `s:world():screenToWorld(pt, fn)` takes that table back, so a projected point is hit-tested, drawn and fed to the ground with nothing between. |
| Drawing | Every coordinate, width and radius a [`g:` verb](drawing.md) takes; the `:w()`/`:h()` a `Draw` reports; the `w, h` an [overlay](overlay.md) painter gets; the `sx, sy` at a [gob overlay](../overlay.md). |
| The stylesheet | A rule's `position` and `size`, an [`anchor`](style/geometry.md#anchor)'s `offset`, a [`padding`](style/chrome.md#padding), a `border`'s `slice` insets, a [listbox's](lists.md#listbox) `:rowHeight(n)` and a [grid's](lists.md#grid) `:cellSize(w, h)`: a theme is a set of numbers meaning one thing on every client. |
| A control's height | A fact of the client's pictures, not a number you write: [`:size(w)`](controls/README.md#sizing) takes the width alone, a [column](column.md) stacks controls without adding heights up, [`:pack()`](writes.md#owned-vs-borrowed) sizes a window around them. |
| Your images | A 32×32 PNG is 32×32 to [`img:size()`](../asset/handles.md#image) and covers 32 design pixels drawn, at every scale. |
| The client's art | Authored several times larger and resampled once per scale, so a `{res = …}` in a `bg` or `border` is measured and sliced in the same unit, crisper than a file of that apparent size on a scaled-up client — [naming a picture](style/chrome.md#naming-a-picture). |
| A font's `size` | A design pixel too: a `14` px caption fits a `20` px row on every client — [`hafen.font`](../font.md). |

```lua
local mouse = hafen.ui():mouse()
assert(hafen.ui():hit(mouse:x(), mouse:y()) == mouse:over())   -- one pair, asked two ways

local world = hafen.session():current():world()
world:screenToWorld({x = mouse:x(), y = mouse:y()}, function(position)   -- the ground under the cursor, a frame later
  if position then hafen.log():write("standing spot: " .. tostring(position:tileCoord().x)) end
end)

hafen.ui():sheet():rule("window[title=Equipment]"):position(40, 200):install()
local equipment_position = hafen.session():current():ui():match("window[title=Equipment]"):position()   -- {x = 40, y = 200}, at any scale
```

> The inverse conversion is not exact: a widget the client placed does not generally sit on a whole design pixel, so reading a client widget's position and writing the same pair back may shift it by less than one design pixel. Hand a place back with [`:position(nil)`](native.md), not by rewriting the numbers.

---

## See Also

- [Widget](widget.md#read-methods) — `:position()`, `:size()` and `:rootPos()`.
- [Native](native.md) — writing the same two on a client widget, and taking the write back.
- [Mouse](mouse.md) — the pointer, in these coordinates.
- [Custom](custom.md), [Drawing](drawing.md) — a surface's `Draw` and the `g:` verbs.
- [Style](style/README.md) — a rule's coordinates, sizes and insets.
- [`hafen.font`](../font.md) — a type size, in the same space.
