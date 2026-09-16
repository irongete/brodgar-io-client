# hafen.ui: Columns and Rows

A column lays its children out top to bottom and a row left to right — in tree order, a gap apart, the cascade's `padding` in from the edge, each child's `margin` around it — and its box is what they take.

```lua
local settings_column = hafen.ui():column():gap(4):position(40, 60)
hafen.ui():label():parent(settings_column):text("Harvest")
hafen.ui():check():parent(settings_column):text("Only ripe")
hafen.ui():button():parent(settings_column):size(120):text("Go")
settings_column:size()     -- as wide as the widest child, as tall as the three plus two gaps
```

---

## Builders

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():column()` | [`Widget`](widget.md) | Unprotected | A surface that places its children top to bottom. |
| `hafen.ui():row()` | [`Widget`](widget.md) | Unprotected | The same surface, placing them left to right. |

| Fact | Detail |
|---|---|
| It is a [bare surface](custom.md) with an axis | `:parent(w)`, `:position(x, y)`, [`:name(s)`, `:stock(t)`](custom.md#naming-and-dressing-your-own-surfaces), `Draw` and the other [surface keys](custom.md#subscribing) answer unchanged; a column can paint a background under its rows. |
| `:type()`, `:role()` | `"AddonWidget"`, like every bare surface; `"column"` or `"row"`, which are [selector roles](selectors.md#roles): `["column"]` is a tree key styling every column. |
| A child is anything you built | A [control](controls/README.md), a bare widget, a [scroll](controls/interactive.md#scroll), another column: `:parent(column)` while it is being built puts it in the next slot. |
| Refused children | A window (the user places it, and its chrome measures itself without telling the column) and a client widget (its place is the client's — take it into a bare widget of yours and put that in the column). Both refusals say why. |

---

## Where a child sits

| Method | Returns | Permission | Description |
|---|---|---|---|
| `column:gap(n)` | `self` | Unprotected | The room between two children, in [design pixels](pixels.md); `0` from birth. Re-lays before it returns. |
| `column:gap()` | `number \| nil` | Unprotected | Reads it; `nil` on a widget that is not a column or a row, where the write refuses naming the two builders. |

| Rule | Detail |
|---|---|
| Along the axis | A column writes `y` and keeps every child's `x` at the left edge; a row writes `x` and keeps `y` at the top. The first child sits at the `padding` the column resolves to (`0` until a [rule](style/chrome.md#padding), a [`:stock`](custom.md#naming-and-dressing-your-own-surfaces) or [`widget:rule()`](style/README.md#restyle-one-widget) gives it one); each next child sits `:gap()` further on, inside [its own margin](#the-room-around-a-child). |
| Across the axis | A child sits at its own width at the start edge. No alignment, no stretch. |
| When it re-lays | On the events that change it, before the call returns: a child entering, resizing, hiding, showing or leaving; `:gap`, `:size` or a `padding` written on the column; a `margin` written on a child. Never per frame. |
| A hidden child | Takes no room; the rest close up; showing it gives the slot back. A destroyed child leaves the same way. |
| A child's own `:size(w, h)` | Honoured — the column never resizes a child — and moves everything after it. |

```lua
local labels = hafen.ui():column():gap(6)
local first = hafen.ui():label():parent(labels):text("one")
local second = hafen.ui():label():parent(labels):text("two")
assert(second:position().y == first:size().h + 6)   -- placed by the column
first:text("a much longer first line")
assert(labels:size().w == first:size().w)            -- re-laid on this same line
```

---

## The box follows the content

A column is as wide as its widest visible child and as tall as the lot, gaps, padding and margins included; an empty one is `0` by `0`.

| Method | Description |
|---|---|
| `column:size()` | Reads the box. |
| `column:size(w)` | Pins the width; the height follows the children. The one-number arity a [control's art](controls/README.md#sizing) earns it means the same here. |
| `column:size(w, h)` | Pins both; children past the box are clipped. |
| `column:size(nil)` | Both follow the content again. |
| `column:pack()` | Refused: a column is packed by construction. A window around a column still packs — [`window:pack()`](custom.md#packing-a-surface-around-what-is-inside-it) sizes the window to the column and follows it from then on. |

A rule's `size` on the column is inert, as on every [widget that owns its size](style/keys.md#what-each-key-accepts); its `position` and `anchor` are honoured.

---

## The room inside

`padding` is the room between the column's edge and its children: the first child starts at the left and top inset, the box ends one right and bottom inset after the last. It is the same [`padding`](style/chrome.md#padding) a window frame keeps, resolved by the [cascade](style/README.md#the-cascade): a tree rule naming the column, its own `:stock` beneath every rule, or `widget:rule()` above them.

```lua
local panel = hafen.ui():column():name("panel")
panel:stock{ bg = {color = {0, 0, 0, 120}}, padding = 8 }   -- the default look, 8 px inside the edge
hafen.ui():label():parent(panel):text("first")              -- sits at 8, 8
```

---

## The room around a child

`margin` is the room the column keeps around one child: four insets outside that child's box, a property of the same cascade said about the child — a tree rule naming it, its own `:stock`, or `widget:rule()` on it. [Geometry](style/geometry.md#margin) is the property's page.

```lua
local boxes = hafen.ui():column():gap(4)
local first = hafen.ui():widget():parent(boxes):size(40, 20)
local second = hafen.ui():widget():parent(boxes):size(40, 20)
second:rule():margin(16, 2, 0, 3)                -- left, top, right, bottom, in design pixels
second:position()                                -- {x = 16, y = 20 + 4 + 2}
```

| Rule | Detail |
|---|---|
| Added to the gap, never collapsed | The child sits its left and top inset further in; the next child starts after its bottom inset and the gap; two margins meeting across a gap are both kept. `:position()` on a child is that arithmetic. The sum is made in the client's own pixels, so a total added up from controls' `:size()` reads can differ from the read by one on a scaled client; bare widgets you sized add up exactly. |
| Across the axis | The column is as wide as its widest child with that child's left and right insets: a right inset is room the box keeps, a left one an indent for that row alone. |
| Whose margin it is | A rule naming a child gives that child room; a rule naming the column gives the column room in what it stands in. A default for the rows is a `:stock` each declares, under any theme's rule. |
| Outside a column or row | Moves nothing; the property still resolves and [`widget:style()`](style/README.md#restyle-one-widget) reads it. |

---

## A child's place is its order

| Rule | Detail |
|---|---|
| `:position(x, y)` and `:position(nil)` on a child | Refused, naming this. Change the order instead: `:parent(other)` while it is being built takes it out, `:destroy()` at any time, or change the gap. |
| A rule's `position` or `anchor` on a child | Inert: it installs, matches and moves nothing; `widget:style()` still reports it. `margin` is the one layout property a rule writes on a child. |
| `:position()` on a child | Reads the slot the column chose, in design pixels within the column. |

Nesting covers the rest: a row inside a column is an icon and a checkbox on one line; a column inside a column with a left `padding` is an indent; a column inside a [scroll](controls/interactive.md#scroll) scrolls once it outgrows the box. An inner column that grows re-lays the outer one in the same call.

---

## A panel, composed

A window packed around a column; in it a row (icon and checkbox), a switch, the group it governs (indented by a left padding, disabled until the switch is ticked) and a scroll holding a column of rows.

```lua
local harvest_window = hafen.ui():window():title("Harvest"):position(80, 120)
local panel = hafen.ui():column():gap(4):parent(harvest_window):position(0, 0)

local header_row = hafen.ui():row():gap(4):parent(panel)
hafen.ui():image():source("gfx/hud/chr/farming"):size(24, 24):parent(header_row)
hafen.ui():check():parent(header_row):text("Only ripe")

local advanced_switch = hafen.ui():check():parent(panel):text("Advanced")
local group = hafen.ui():column():gap(4):parent(panel)
group:stock{ padding = {16, 0, 0, 0} }                            -- a left padding is an indent
hafen.ui():check():parent(group):text("Also unripe")
hafen.ui():entry():parent(group):size(120)
group:enabled(false)
advanced_switch:on("Changed", function(checked) group:enabled(checked) end)

local scroll_box = hafen.ui():scroll():parent(panel):size(160, 120)
local list = hafen.ui():column():parent(scroll_box):position(0, 0)
for index = 1, 30 do hafen.ui():label():parent(list):text("row " .. index) end

harvest_window:pack()                                             -- the window is exactly the panel
```

| Part | What it relies on |
|---|---|
| The row | Both children at `y = 0`, the checkbox `:gap()` after the icon's [`:size(24, 24)`](controls/display.md#picture) box. |
| The indent | The group's `padding`: its first child sits 16 further right and the group is 16 wider than its rows. A theme naming the group can change it; a `margin` on the group would move the group itself. |
| The switch | Stands outside the group, so `group:enabled(false)` reaches the rows and not the switch; each row's own [`:enabled()`](writes.md#enabled-and-disabled) stays `true`. |
| The scroll's bar | One of `scroll_box:children()`; its [`:range()`](controls/interactive.md#scroll) follows the column inside: `max` is `0` while it is empty and moves with every row. |
| The window | Packed once, it follows the panel: a row added after `:pack()` grows the window before the call returns. |
| Only two `:position` writes | The panel's inside the window and the list's inside the scroll — the two parents that do not lay children out. |

The same panel mounts on [your addon's page of Options ▸ AddOns](../client/addon.md#the-page): `opts:panel(fn)` hands a `root` that is already the column (`root:gap(4)` stands where the `panel` line does), there is no window to pack, the page's box scrolls what outgrows it, a control showing a setting is [bound](../client/addon.md#binding-a-control-shows-the-option) to its option, and the page is rebuilt on every visit.

---

## See Also

- [Custom](custom.md) — the bare surface a column is.
- [Controls](controls/README.md) — what goes inside, and the one-number `:size(w)`.
- [Chrome](style/chrome.md#padding) — `padding`, the column's inner room.
- [Geometry](style/geometry.md) — `position`, `size`, `anchor` and `margin`.
- [Widget](widget.md) — every read a column answers, `:gap()` among them.
