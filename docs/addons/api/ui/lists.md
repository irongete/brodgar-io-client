# hafen.ui: row-source controls

A **row-source control** takes its content from `:rows(t)` — a plain Lua array — rather than a caption or
a picture, and is dressed by the [stylesheet](style/README.md) like any other [control](controls/README.md). A
listbox keeps every row on screen, a dropdown keeps one closed until clicked, a menu fires on a pick and
holds nothing, a grid draws its own cells instead of building rows at all, and a table lays them out in named
columns. A listbox, a dropdown and a menu take the same string-or-`{icon=,text=}` row shape below; a
[grid](#grid)'s rows are whatever your own `Cell` handler reads, and a [table](#table)'s whatever its own
`:columns(t)` reads.

```lua
local box = hafen.ui():listbox():size(200, 160):rows{"Wood", "Stone", "Clay"}
box:on("Changed", function(row) hafen.log():write("picked " .. row) end)
```

## Builders

| Verb | Returns | The control |
|---|---|---|
| `hafen.ui():listbox()` | [Widget](widget.md) | a scrolling list of rows |
| `hafen.ui():dropdown()` | [Widget](widget.md) | one row, closed until clicked |
| `hafen.ui():menu()` | [Widget](widget.md) | a row of actions that fires and holds nothing |
| `hafen.ui():grid()` | [Widget](widget.md) | a laid-out grid of cells you draw yourself |
| `hafen.ui():table()` | [Widget](widget.md) | rows laid out in named columns |

Built bare and configured by chained setters, [the same shape](controls/README.md#builders) every other
control has — the arming rule included. None of it is protected, the same as any other control.

## Rows (listbox, dropdown, menu)

Every row is a string, or a `{icon =, text =}` table — the client's own art beside a label — and a table
may mix both freely, one shape per element:

```lua
box:rows{ "Alpha", { icon = hafen.asset():get("bucket.png"), text = "Bucket" }, "Gamma" }
```

`icon` is a [face](controls/interactive.md#a-caption-or-a-picture) — an asset handle or a client resource
name. Writing `:rows(t)` again replaces the whole set and clears the selection (a listbox or a dropdown), or
its contents (a menu); an empty `:rows{}` is a control with nothing in it, not an error.

## Listbox

`:value()`/`:value(v)` is the selected row — the **exact** Lua value `:rows(t)` was given, so it can be
compared with `==` or handed straight back to `:value(v)`. Writing a value that is not one of the current
rows is refused, naming the rows that are.

```lua
box:value()                --> nil, until a row is picked
box:value("Stone")         --> selects it; refused if "Stone" is not one of the current rows
```

`Changed` fires when the user picks a different row, carrying it — a programmatic `:value(v)` never
re-enters it, so driving the selection from a script and reacting to the user changing it never loop into
each other.

`:rowHeight(n)` sets the height of a row, in [design pixels](pixels.md), defaulting to the client's own label
height — which reads back as the same number at every interface scale, like every other size here. Like a
[button's face](controls/interactive.md#a-caption-or-a-picture), it is chosen while the control is being
built: it refuses once the listbox is on screen.

## Dropdown

`hafen.ui():dropdown()` answers the same `:value()`/`:value(v)`/`Changed` as a listbox — the picked row,
round-tripped the same way — but stays closed until the user clicks it open, and shows only the current pick
the rest of the time.

```lua
local kind = hafen.ui():dropdown():size(120, 20):rows{"All", "Seeds", "Tools"}:value("All")
kind:on("Changed", function(pick) hafen.log():write("filter: " .. pick) end)
```

`:rowHeight(n)` behaves exactly as it does on a listbox.

## Menu

`hafen.ui():menu()` fires and holds nothing: it answers no `:value()` at all — reading it is always `nil` —
and a pick is `Selected`, not `Changed`, a different key for a control with no value to report a change
against.

```lua
local m = hafen.ui():menu():size(120, 90):rows{"Rename", "Delete", "Move"}
m:on("Selected", function(row) hafen.log():write("picked " .. row) end)
```

`:rowHeight(n)` behaves exactly as it does on a listbox.

## Grid

`hafen.ui():grid()` is the one row-source control that does not build a row widget per item — it lays cells
out in a wrapping grid and answers `Cell` to PAINT each one, so `:rows(t)` here is an array of whatever
your own cells need, not the string-or-`{icon=,text=}` shape above:

```lua
local items = {}
for i, res in ipairs(ownedResources) do items[i] = {icon = res} end

local grid = hafen.ui():grid():size(200, 200):cell(48, 48):rows(items)
grid:on("Cell", function(ev)
  ev:g():image(ev:item().icon, 0, 0, ev:w(), ev:h())
end)
```

`Cell`'s `ev` answers `:g()` — the same [`g` wrapper](drawing.md) a surface's `Draw` does — `:w()`/`:h()`
the cell's box, drawn at `(0,0)`, its own top-left, and `:item()` the row `:rows(t)` gave for that cell. A
handler that errors costs only that cell's line; the rest of the grid still draws, that frame and every one
after it.

`:cellSize(w, h)` is the cell box, in [design pixels](pixels.md), defaulting to the client's own
inventory-slot size — `32×32`, the same box at every interface scale; like a
[listbox's row height](#listbox), it is chosen while the control is being built and refuses once the grid
is on screen. A grid answers no `:value()` and no
`Changed` — it holds nothing, the same as a [menu](#menu) — and an empty `:rows{}` draws nothing rather than
erroring.

## Table

`hafen.ui():table()` lays rows out in named columns, so — like a [grid](#grid)'s — its rows are not the
string-or-`{icon=,text=}` shape above, but whatever each column's own accessor reads:

```lua
hafen.ui():table()
  :size(300, 200)
  :columns{
    { title = "Name",    width = 160, of = function(r) return r.name end },
    { title = "Quality", width = 60,  of = function(r) return tostring(r.q) end },
  }
  :rows(stock)
```

`:columns(t)` names each column: `title` heads it, `width` is its pixel box — a **number**, the same as any
other [control's numbers](controls/README.md#setters) — and `of(row)` is called once per row to produce that
cell's text, which must be a **string**: `tostring` a number yourself, the same as `"Quality"` does above.
Writing `:columns(t)` again replaces the whole set and re-reads every current row against it; like a
[grid's cell box](#grid), it is chosen while the control is being built and refuses once the table is on
screen.

`:rowHeight(n)` behaves exactly as it does on a listbox. A table answers no `:value()` and no `Changed` — it
holds nothing, the same as a [menu](#menu) or a [grid](#grid) — and an empty `:rows{}` is a table with
nothing in it rather than an error.

## The client's own row controls

`Changed`, `Selected` and `Cell` answer on a listbox, dropdown, menu or grid the **client** built too, where
they carry an `ev` that can stop the pick or run it — [editing](edit.md) is that page. It also says which
widget the key is addressed to when a control keeps its rows in a list of its own.

## See also

- [edit](edit.md) — the same keys on one of the client's own row controls
- [controls](controls/README.md) — the direct controls, and the vocabulary this page shares with them
- [widget](widget.md) — everything a control answers before it adds anything of its own
- [selectors](selectors.md) — naming a control, yours or the client's
- [style](style/README.md) — the rules that dress it
