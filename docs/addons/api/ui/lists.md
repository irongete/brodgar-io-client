# hafen.ui: lists

A **row-source control** takes its content from `:rows(t)` — a plain Lua array — rather than a caption or
a picture, and is dressed by the [stylesheet](style/README.md) like any other [control](controls.md). Five
share it: a list keeps every row on screen, a dropdown keeps one closed until clicked, a menu fires on a pick
and holds nothing, a grid draws its own cells instead of building rows at all, and a table lays them out in
named columns — the first three take the same string-or-`{icon=,text=}` row shape below; a [grid](#grid)'s
rows are whatever your own `:onCell` reads, and a [table](#table)'s whatever its own `:columns(t)` reads.

```lua
local list = hafen.ui():list()
  :size(200, 160)
  :rows{"Wood", "Stone", "Clay"}
  :onChange(function(row) hafen.log():write("picked " .. row) end)
```

## Builders

| Verb | Returns | The control |
|---|---|---|
| `hafen.ui():list()` | [Widget](widget.md) | a scrolling list of rows |
| `hafen.ui():dropdown()` | [Widget](widget.md) | one row, closed until clicked |
| `hafen.ui():menu()` | [Widget](widget.md) | a row of actions that fires and holds nothing |
| `hafen.ui():grid()` | [Widget](widget.md) | a laid-out grid of cells you draw yourself |
| `hafen.ui():table()` | [Widget](widget.md) | rows laid out in named columns |

Built bare and configured by chained setters, [the same shape](controls.md#builders) every other control has
— the arming rule included.

## Rows (list, dropdown, menu)

Every row is a string, or a `{icon =, text =}` table — the client's own art beside a label — and a table
may mix both freely, one shape per element:

```lua
list:rows{ "Alpha", { icon = hafen.asset():get("bucket.png"), text = "Bucket" }, "Gamma" }
```

`icon` is a [face](controls.md#a-caption-or-a-picture) — an asset handle or a client resource name. Writing
`:rows(t)` again replaces the whole set and clears the selection (a list or a dropdown), or its contents (a
menu); an empty `:rows{}` is a control with nothing in it, not an error.

## List

`:value()`/`:value(v)` is the selected row — the **exact** Lua value `:rows(t)` was given, so it can be
compared with `==` or handed straight back to `:value(v)`. Writing a value that is not one of the current
rows is refused, naming the rows that are.

```lua
list:value()               --> nil, until a row is picked
list:value("Stone")        --> selects it; refused if "Stone" is not one of the current rows
```

`:onChange(fn)` fires when the user picks a different row, carrying it — a programmatic `:value(v)` never
re-enters it, so driving the selection from a script and reacting to the user changing it never loop into
each other.

`:rowHeight(n)` sets the height of a row, in pixels, defaulting to the client's own label height. Like a
[button's face](controls.md#a-caption-or-a-picture), it is chosen while the control is being built: it
refuses once the list is on screen.

## Dropdown

`hafen.ui():dropdown()` answers the same `:value()`/`:value(v)`/`:onChange(fn)` as a list — the picked row,
round-tripped the same way — but stays closed until the user clicks it open, and shows only the current pick
the rest of the time.

```lua
local kind = hafen.ui():dropdown()
  :size(120, 20)
  :rows{"All", "Seeds", "Tools"}
  :value("All")
  :onChange(function(pick) hafen.log():write("filter: " .. pick) end)
```

`:rowHeight(n)` behaves exactly as it does on a list.

## Menu

`hafen.ui():menu()` fires and holds nothing: it answers no `:value()` at all — reading it is always `nil` —
and a pick is `:onSelect(fn)`, not `:onChange(fn)`, a different name for a control with no value to report a
change against.

```lua
hafen.ui():menu()
  :size(120, 90)
  :rows{"Rename", "Delete", "Move"}
  :onSelect(function(row) hafen.log():write("picked " .. row) end)
```

`:rowHeight(n)` behaves exactly as it does on a list.

## Grid

`hafen.ui():grid()` is the one row-source control that does not build a row widget per item — it lays cells
out in a wrapping grid and calls `:onCell(fn)` to PAINT each one, so `:rows(t)` here is an array of whatever
your own cells need, not the string-or-`{icon=,text=}` shape above:

```lua
local items = {}
for i, res in ipairs(ownedResources) do items[i] = {icon = res} end

hafen.ui():grid()
  :size(200, 200)
  :cell(48, 48)
  :rows(items)
  :onCell(function(g, item, w, h) g:image(item.icon, 0, 0, w, h) end)
```

`fn(g, item, w, h)` gets the same [`g` wrapper](drawing.md) a surface's `:onDraw(fn)` does — draw at `(0,0)`,
the cell's own top-left, in a `w`×`h` box — plus the row `:rows(t)` gave for that cell. A handler that errors
costs only that cell's line; the rest of the grid still draws, that frame and every one after it.

`:cell(w, h)` is the cell box, in pixels, defaulting to the client's own inventory-slot size (32×32); like a
[list's row height](#list), it is chosen while the control is being built and refuses once the grid is on
screen. A grid answers no `:value()`/`:onChange(fn)` — it holds nothing, the same as a [menu](#menu) — and an
empty `:rows{}` draws nothing rather than erroring.

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

`:columns(t)` names each column: `title` heads it, `width` is its pixel box, and `of(row)` is called once
per row to produce that cell's text, which must be a **string** — `tostring` a number yourself, the same as
`"Quality"` does above. Writing `:columns(t)` again replaces the whole set and re-reads every current row
against it; like a [grid's cell box](#grid), it is chosen while the control is being built and refuses once
the table is on screen.

`:rowHeight(n)` behaves exactly as it does on a list. A table answers no `:value()`/`:onChange(fn)` — it
holds nothing, the same as a [menu](#menu) or a [grid](#grid) — and an empty `:rows{}` is a table with
nothing in it rather than an error.

## See also

- [controls](controls.md) — the direct controls, and the vocabulary this page shares with them
- [widget](widget.md) — everything a control answers before it adds anything of its own
- [selectors](selectors.md) — naming a control, yours or the client's
- [style](style/README.md) — the rules that dress it
