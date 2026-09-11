# hafen.ui: columns and rows

A **column** lays its children out top to bottom, and a **row** left to right — in tree order, a `:gap(n)`
apart, the cascade's `padding` in from the edge and each child's own `margin` around it — and its box is
exactly what they take. Reach for one wherever you were adding `y` up by hand: a panel of labels, a line of
an icon and a checkbox, a form.

Both are surfaces of your own, built bare like [a window or a bare widget](custom.md) and torn down with your
addon; the client's own controls and your bare widgets go inside them.

```lua
local col = hafen.ui():column():gap(4):position(40, 60)
hafen.ui():label():parent(col):text("Harvest")
hafen.ui():check():parent(col):text("Only ripe")
hafen.ui():button():parent(col):size(120):text("Go")
col:size()                   -- as wide as the widest of the three, as tall as the lot plus two gaps
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
on, each inside [its own `margin`](#the-room-around-a-child) where it has one. A child sits at its own
width: across the axis it is at the start edge, and there is no alignment or stretch.

**Laid out on the events that change it, never per frame.** A child entering, changing size, being hidden
or shown, or leaving; a `:gap`, a `:size` or a `padding` written on the column, a `margin` written on a
child — each is re-laid before the call that made it returns, so the line after `two:visible(false)` reads
`three:position()` already moved up, and the line after `one:text("a longer caption")` reads the column
already as wide as it:

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

A column is exactly as wide as its widest visible child and as tall as the lot, gaps, padding and each
child's margin included; an empty one is `0` by `0`. `:size()` reads that back, and the three writes below
say how much of it you take over:

| Verb | Meaning |
|---|---|
| `:size(w)` | pin the width to `w`; the height still follows the children |
| `:size(w, h)` | pin both; children past the box are clipped, as in any widget |
| `:size(nil)` | let both follow the content again |

`:size(w)` is the one-number arity a [control's own art](controls/README.md#sizing) earns it, and it means
the same thing here: the height is the one measurement you do not make, because the children answer it.
**`:pack()` is refused** on a column, naming this — a column is packed by construction, and there is nothing
a second measure could add. A window *around* a column still packs:
[`win:pack()`](custom.md#packing-a-surface-around-what-is-inside-it) sizes the window to the column inside
it, the column having already sized itself — and from then on the window follows the column, so a row
added to it re-packs the window before the call returns.

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

## The room around a child

`margin` is the room the column keeps **around one child** — four insets outside that child's box, where
`padding` is the column's own, inside its edge. It is a property of the same
[cascade](style/README.md#the-cascade), said in the same three places about the *child*: a tree rule that
names it, its own `:stock`, or `widget:rule()` on it. [geometry](style/geometry.md#margin) is the
property's page; this is what a column does with it.

```lua
local col = hafen.ui():column():gap(4)
local one = hafen.ui():widget():parent(col):size(40, 20)
local two = hafen.ui():widget():parent(col):size(40, 20)
two:rule():margin(16, 2, 0, 3)                  -- left, top, right, bottom, in design pixels
two:position()                                   --> {x = 16, y = 20 + 4 + 2}
```

- **Added to the gap, never collapsed.** The child sits its left and top inset further in; the next child
  starts after its bottom inset *and* the gap; two margins that meet across a gap are both kept. So
  `:position()` on any child is the arithmetic — the padding, then for each child before it its top inset,
  its box, its bottom inset and one gap — and the line after the rule is written reads it moved. The sum is
  made in the client's own pixels: a [control's height](pixels.md) is its art's and not a whole design pixel
  on a scaled client, so a total you add up from `:size()` reads of controls can differ from the read by
  one, while a bare widget you sized adds up exactly.
- **Room across the axis too.** A column is as wide as its widest child *with* that child's left and right
  insets, so a right inset is room the box keeps, and a left one is an indent for that row alone.
- **Whose margin it is.** A rule that names a child gives *that child* room; a rule that names the column
  gives the column room in whatever it stands in, and nothing otherwise. A default for the rows is a
  [`:stock`](custom.md#naming-and-dressing-your-own-surfaces) each declares, under any theme's rule.
- **Outside a column or a row it moves nothing.** A widget placed by hand keeps its `:position()`; the
  property still resolves and [`widget:style()`](style/README.md#restyle-one-widget) reads it, inert like
  `padding` on a surface that does not own its layout.

## A child's place is its order

A child has no position of its own: it sits where the column put it, and the column puts it after the
child before it. So on a child:

- **`:position(x, y)` and `:position(nil)` are refused**, naming this. The way to move a child is to change
  the order — `:parent(other)` while it is being built takes it out, `:destroy()` at any time — or the
  gap.
- **A rule's `position` or `anchor` is inert.** `s:rule("[name=myaddon/two]"):position(50, 50)` installs,
  matches, and moves nothing; [`widget:style()`](style/README.md#restyle-one-widget) still reports it. Its
  [`margin`](#the-room-around-a-child) is the one layout property a rule *does* write on a child.
- **`:position()` still reads**, and it reads the slot the column chose, in design pixels within the column.

Nesting is the vocabulary for everything else. A row inside a column is an icon and a checkbox on one
line; a column inside a column with a left `padding` is an indent; a column inside a
[scroll](controls/interactive.md#scroll) scrolls once it outgrows the box. An inner column that grows re-lays
the outer one in the same call, because a child changing size is one of the events above — and
[a panel](#a-panel-composed) is the three of them in one window.

## A panel, composed

A panel is columns inside columns. The one below is a window packed around a column, and in that column: a
row holding an icon and a checkbox on one line, a switch, the group it governs — a column indented by a
left `padding`, greyed out until the switch is ticked — and a scroll holding a column of rows too tall for
its box.

```lua
local win   = hafen.ui():window():title("Harvest"):position(80, 120)
local panel = hafen.ui():column():gap(4):parent(win):position(0, 0)

local row = hafen.ui():row():gap(4):parent(panel)                -- an icon and a checkbox, one line
hafen.ui():image():source("gfx/hud/chr/farming"):size(24, 24):parent(row)
hafen.ui():check():parent(row):text("Only ripe")

local sw    = hafen.ui():check():parent(panel):text("Advanced")  -- the switch, outside the group
local group = hafen.ui():column():gap(4):parent(panel)
group:stock{ padding = {16, 0, 0, 0} }                            -- a left padding is an indent
hafen.ui():check():parent(group):text("Also unripe")
hafen.ui():entry():parent(group):size(120)
group:enabled(false)                                             -- greyed and silent, rows included
sw:on("Changed", function(on) group:enabled(on) end)

local sp   = hafen.ui():scroll():parent(panel):size(160, 120)    -- a fixed box in the column...
local list = hafen.ui():column():parent(sp):position(0, 0)       -- ...and a column that outgrows it
for i = 1, 30 do hafen.ui():label():parent(list):text("row " .. i) end

win:pack()                                                        -- the window is exactly the panel
```

Every piece is placed by the column it stands in, so the only `:position` written is the panel's own inside
the window and the list's inside the scroll — the two parents that do not lay children out. What each
part relies on:

- **The row** keeps both children at `y = 0` and places the checkbox `:gap()` after the icon's box — the
  24 pixels [`:size(24, 24)`](controls/display.md#picture) gave a skill icon the client draws far larger; a
  wider icon moves the checkbox and nothing else.
- **The indent is the group's `padding`**: its first child sits 16 further right than the panel's, and the
  group is 16 wider than its rows. It is the same property a rule writes, so a theme that names the group
  can change it; a [`margin`](#the-room-around-a-child) on the group would move the group itself instead.
- **The switch stands outside the group**, so `group:enabled(false)` reaches the rows and not the thing
  that brings them back; each row's own [`:enabled()`](writes.md#enabled-and-disabled) stays `true`, and
  enabling the group restores them as they were.
- **The scroll's bar** is one of `sp:children()`, and its [`:range()`](controls/interactive.md#scroll)
  follows the column inside: `max` is `0` while the column is empty and moves with every row it gains.
- **The window** was packed once and follows the panel from then on: a row added to the panel after
  `win:pack()` grows the window by that row before the call returns —
  [packing a surface](custom.md#packing-a-surface-around-what-is-inside-it) is the rule.

**Where a panel is mounted.** In a window of your own, as above — or on
[your addon's page of Options ▸ AddOns](../client/addon.md#the-page), where the client hands
`opts:panel(fn)` a `root` that is already the column: `root:gap(4)` stands where the `panel` line does, the
same rows go in with `:parent(root)`, and there is no window to pack — the page's own box scrolls what
outgrows it. A control there that shows a setting is
[bound](../client/addon.md#binding-a-control-shows-the-option) to the option rather than read on `Changed`,
and the page is rebuilt on every visit, so a panel that keeps nothing between visits is the same panel in
both places.

## See also

- [custom](custom.md) — the bare surface a column is, and everything it inherits from one
- [controls](controls/README.md) — what goes inside, and the one-number `:size(w)` a control takes
- [chrome](style/chrome.md#padding) — `padding`, the property that is a column's inner room
- [geometry](style/geometry.md) — `position`, `size` and `anchor`, honoured on the column and inert on a
  child; `margin`, the other way about
- [widget](widget.md) — every read a column answers, `:gap()` among them
