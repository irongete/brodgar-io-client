# hafen.ui: columns and rows

A **column** lays its children out top to bottom, and a **row** left to right — in tree order, a `:gap(n)`
apart, the cascade's `padding` in from the edge — and its box is exactly what they take. Reach for one
wherever you were adding `y` up by hand: a panel of labels, a line of an icon and a checkbox, a form.

Both are surfaces of your own, built bare like [a window or a bare widget](custom.md) and torn down with your
addon; the client's own controls and your bare widgets go inside them.

```lua
local col = hafen.ui():column():gap(4):position(40, 60)
hafen.ui():label():parent(col):text("Harvest")
hafen.ui():check():parent(col):text("Only ripe")
hafen.ui():button():parent(col):size(120):text("Go")
col:size()                     -- the three stacked: as wide as the widest, as tall as the lot plus two gaps
```

## The two builders

| Verb | Returns | Description |
|---|---|---|
| `hafen.ui():column()` | [Widget](widget.md) | a surface that places its children top to bottom |
| `hafen.ui():row()` | [Widget](widget.md) | the same surface, placing them left to right |

Neither takes an argument. What comes back is a [bare surface](custom.md) with one thing added, so
everything a bare widget does answers on it unchanged: `:parent(w)` puts it in a window or in the HUD,
`:position(x, y)` places it there,
[`:name(s)` and `:stock(t)`](custom.md#naming-and-dressing-your-own-surfaces) let a theme reach it, and it
answers `Draw` and the other [surface keys](custom.md#subscribing), so a column can paint a background under
its own rows. `:type()` reads `"AddonWidget"`, as every bare surface does; `:role()` reads `"column"` or
`"row"`, and those are [selector roles](selectors.md#roles), so `["column"]` is a tree key that styles every
column.

**A child is anything you built.** A [control](controls/README.md), a bare `hafen.ui():widget()`, a
[scroll](controls/interactive.md#scroll), another column: `:parent(col)` on it while it is being built puts
it in, and where it lands is the next slot. Two shapes are refused at that door, naming why: a **window**,
which the user places and whose chrome measures itself without telling the column, and **one of the
client's own widgets**, whose place is the client's — take that one into a bare widget of yours instead,
and put the bare widget in the column.

## Where a child sits

| Verb | Read | Meaning |
|---|---|---|
| `:gap(n)` | `:gap()` | the room between two children, in [design pixels](pixels.md), `0` from birth; on a widget that is not a column or a row the read answers `nil` and the write refuses naming the two builders |

Each visible child is placed after the one before it, along the axis: a column writes `y` and keeps every
child's `x` at the left edge, a row writes `x` and keeps `y` at the top. The first child sits at the
`padding` the column resolves to — `0` until a [rule](style/chrome.md#padding), a
[`:stock`](custom.md#naming-and-dressing-your-own-surfaces) or a
[`widget:rule()`](style/README.md#restyle-one-widget) gives it one — and each next child `:gap()` further
on. A child sits at its own width: across the axis it is at the start edge, and there is no alignment or
stretch.

**Laid out on the events that change it, never per frame.** A child entering, changing size, being hidden
or shown, or leaving; a `:gap`, a `:size` or a `padding` written on the column — each is re-laid before the
call that made it returns, so the line after `two:visible(false)` reads `three:position()` already moved
up, and the line after `one:text("a longer caption")` reads the column already as wide as it:

```lua
local col = hafen.ui():column():gap(6)
local one = hafen.ui():label():parent(col):text("one")
local two = hafen.ui():label():parent(col):text("two")
two:position().y == one:size().h + 6                 -- true: placed by the column
one:text("a much longer first line")
col:size().w == one:size().w                         -- true, on this same line
```

**A hidden child takes no room**, and the rest close up; showing it again gives it its slot back. A child
that is `:destroy()`ed leaves the same way. A child's own `:size(w, h)` is honoured — the column never
resizes a child — and moves everything after it.

## The box follows the content

A column is exactly as wide as its widest visible child and as tall as the lot, gaps and padding
included; an empty one is `0` by `0`. `:size()` reads that back, and the three writes below say how much
of it you take over:

| Verb | Meaning |
|---|---|
| `:size(w)` | pin the width to `w`; the height still follows the children |
| `:size(w, h)` | pin both; children past the box are clipped, as in any widget |
| `:size(nil)` | let both follow the content again |

`:size(w)` is the one-number arity a [control's own art](controls/README.md#sizing) earns it, and it means
the same thing here: the height is the one measurement you do not make, because the children answer it.
**`:pack()` is refused** on a column, naming this — a column is packed by construction, and there is nothing
a second measure could add. A window *around* a column still packs: [`win:pack()`](custom.md) sizes the
window to the column inside it, and the column has already sized itself.

A rule's `size` on the column is inert, as it is on every
[widget that owns its size](style/keys.md#what-each-key-accepts); its `position` and `anchor` are honoured,
the column being a widget like any other where *it* sits.

## The room inside

`padding` is the room between the column's edge and its children — the first child starts at the left and
top inset, and the box ends one right and bottom inset after the last. It is the same
[`padding`](style/chrome.md#padding) a window's frame keeps, said in the same three places, and it is the
[cascade](style/README.md#the-cascade) that decides: a tree rule that names the column, its own `:stock`
beneath every rule, or `widget:rule()` above them.

```lua
local col = hafen.ui():column():name("panel")
col:stock{ bg = {color = {0, 0, 0, 120}}, padding = 8 }   -- the default look, 8 px inside the edge
hafen.ui():label():parent(col):text("first")               -- sits at 8, 8
```

## A child's place is its order

A child has no position of its own: it sits where the column put it, and the column puts it after the
child before it. So on a child:

- **`:position(x, y)` and `:position(nil)` are refused**, naming this. The way to move a child is to change
  the order — `:parent(other)` while it is being built takes it out, `:destroy()` at any time — or the
  gap.
- **A rule's `position` or `anchor` is inert.** `s:rule("[name=myaddon/two]"):position(50, 50)` installs,
  matches, and moves nothing; [`widget:style()`](style/README.md#restyle-one-widget) still reports it.
- **`:position()` still reads**, and it reads the slot the column chose, in design pixels within the column.

Nesting is the vocabulary for everything else. A row inside a column is an icon and a checkbox on one
line; a column inside a column with a left `padding` is an indent; a column inside a
[scroll](controls/interactive.md#scroll) scrolls once it outgrows the box. An inner column that grows re-lays
the outer one in the same call, because a child changing size is one of the events above.

## See also

- [custom](custom.md) — the bare surface a column is, and everything it inherits from one
- [controls](controls/README.md) — what goes inside, and the one-number `:size(w)` a control takes
- [chrome](style/chrome.md#padding) — `padding`, the property that is a column's inner room
- [geometry](style/geometry.md) — `position`, `size` and `anchor`: honoured on the column, inert on a child
- [widget](widget.md) — every read a column answers, `:gap()` among them
