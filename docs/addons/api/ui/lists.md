# hafen.ui: Row-Source Controls

A row-source control takes its content from `:rows(t)`, a plain Lua array. A listbox keeps every row on screen. A dropdown keeps one closed until clicked. A menu fires on a pick and holds nothing. A grid paints its own cells. A table lays rows out in named columns.

```lua
local material_list = hafen.ui():listbox():size(200, 160):rows{"Wood", "Stone", "Clay"}
material_list:on("Changed", function(row) hafen.log():write("picked " .. row) end)
material_list:value("Stone")
```

---

## Builders

| Method | Returns | Permission | The control |
|---|---|---|---|
| `hafen.ui():listbox()` | [`Widget`](widget.md) | Unprotected | A scrolling list of rows. |
| `hafen.ui():dropdown()` | `Widget` | Unprotected | One row, closed until clicked. |
| `hafen.ui():menu()` | `Widget` | Unprotected | A list of actions that fires and holds nothing. |
| `hafen.ui():grid()` | `Widget` | Unprotected | A wrapping grid of cells you paint. |
| `hafen.ui():table()` | `Widget` | Unprotected | Rows laid out in named columns. |

Built bare and configured by chained setters, [the same shape](controls/README.md#builders) every control has, the arming rule included. A listbox, dropdown and menu take the row shape below. A grid's rows are whatever its `Cell` handler reads, and a table's whatever its `:columns(t)` reads.

## Rows (listbox, dropdown, menu)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `control:rows(t)` | `self` | Unprotected | The row source: an array whose every element is a string or an `{icon =, text =}` table, mixed freely. Replaces the whole set and clears the selection (listbox, dropdown) or the contents (menu). `:rows{}` is an empty control, not an error. |
| `control:rows()` | `table` | Unprotected | The table last given. |

```lua
material_list:rows{ "Alpha", { icon = hafen.asset():get("bucket.png"), text = "Bucket" }, "Gamma" }
```

| Rule | Detail |
|---|---|
| `icon` | A [face](controls/interactive.md#a-caption-or-a-picture): an asset handle or a client resource name. |
| No holes | Every row from `1` to the last is read. A `nil` in the middle raises naming its index. |
| At most 4096 rows | Refused naming the number. Every row and every icon is resolved before the call returns. A list of a hundred thousand rows is a stalled client. A list that long is a filter not yet applied. |

## Listbox

| Method | Returns | Permission | Description |
|---|---|---|---|
| `listbox:value()` | `any \| nil` | Unprotected | The selected row: the exact Lua value `:rows(t)` was given, so it compares with `==`. `nil` until a row is picked. |
| `listbox:value(row)` | `self` | Unprotected | Selects that row, scrolling to it. A value not among the current rows is refused, naming the rows that are. |
| `listbox:rowHeight(n)` | `self` | Unprotected | The height of a row in [design pixels](pixels.md). Defaults to the client's own label height. Building-only: refused once the listbox is on screen. |
| `listbox:rowHeight()` | `number` | Unprotected | The row height. |
| `listbox:on("Changed", fn)` | `Sub` | Unprotected | `fn(row)` when the user picks a different row. A `:value(v)` of yours never re-enters it. |

## Dropdown

Answers the same `:value()`, `:value(row)`, `:rowHeight(n)` and `Changed` as a listbox. It stays closed until the user clicks it open, and shows only the current pick the rest of the time.

```lua
local kind_filter = hafen.ui():dropdown():size(120, 20):rows{"All", "Seeds", "Tools"}:value("All")
kind_filter:on("Changed", function(pick) hafen.log():write("filter: " .. pick) end)
```

## Menu

| Method | Returns | Permission | Description |
|---|---|---|---|
| `menu:value()` | `nil` | Unprotected | A menu holds nothing. |
| `menu:rowHeight(n)` | `self` | Unprotected | As a listbox's. |
| `menu:on("Selected", fn)` | `Sub` | Unprotected | `fn(row)` on a pick. `Selected`, not `Changed`: there is no value to report a change against. |

```lua
local actions = hafen.ui():menu():size(120, 90):rows{"Rename", "Delete", "Move"}
actions:on("Selected", function(row) hafen.log():write("picked " .. row) end)
```

## Grid

The one row-source control that builds no row widget. It lays cells out in a wrapping grid and answers `Cell` to paint each. `:rows(t)` is an array of whatever your cells need.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `grid:rows(t)` | `self` | Unprotected | An array of any Lua values, one per cell. `:rows{}` draws nothing. |
| `grid:cellSize(w, h)` | `self` | Unprotected | The cell box in design pixels. Defaults to the client's inventory-slot size, `32x32`. Building-only. |
| `grid:cellSize()` | `{w=, h=}` | Unprotected | The cell box. |
| `grid:value()` | `nil` | Unprotected | A grid holds nothing and fires no `Changed`. |
| `grid:on("Cell", fn)` | `Sub` | Unprotected | `fn(event)` to paint one cell: `event:g()` the [`g` wrapper](drawing.md) with `(0, 0)` the cell's top-left, `event:w()`/`event:h()` the cell box, `event:item()` the row for that cell. A handler that errors costs that cell alone, that frame and after. |

```lua
local owned_resources = { "gfx/invobjs/axe-b", "gfx/invobjs/saw", "gfx/invobjs/shovel-w" }
local icons = {}
for index, resource in ipairs(owned_resources) do icons[index] = {icon = resource} end

local icon_grid = hafen.ui():grid():size(200, 200):cellSize(48, 48):rows(icons)
icon_grid:on("Cell", function(event)
  event:g():image(event:item().icon, 0, 0, event:w(), event:h())
end)
```

## Table

| Method | Returns | Permission | Description |
|---|---|---|---|
| `table:columns(t)` | `self` | Unprotected | One descriptor per column. `title` heads it. `width` is its box in design pixels (a number). `of(row)` is called once per row and returns that cell's text (a string: `tostring` a number yourself). Writing again replaces the set and re-reads every row. Building-only. |
| `table:columns()` | `table` | Unprotected | The descriptors last given. |
| `table:rows(t)` | `self` | Unprotected | An array of any Lua values, one per row, read by each column's `of`. `:rows{}` is an empty table. |
| `table:rowHeight(n)` | `self` | Unprotected | As a listbox's. |
| `table:value()` | `nil` | Unprotected | A table holds nothing and fires no `Changed`. |

```lua
local stock = { { name = "Board", quality = 34 }, { name = "Block", quality = 21 } }
local stock_table = hafen.ui():table():size(300, 200)
  :columns{
    { title = "Name",    width = 160, of = function(row) return row.name end },
    { title = "Quality", width = 60,  of = function(row) return tostring(row.quality) end },
  }
  :rows(stock)
```

## The client's own row controls

`Changed`, `Selected` and `Cell` answer on a listbox, dropdown, menu or grid the client built. The `event` can stop the pick or run it ([edit](edit.md)). That page also says which widget the key is addressed to when a control keeps its rows in an inner list of its own.

---

## See Also

- [Edit](edit.md) — the same keys on the client's row controls.
- [Controls](controls/README.md) — the direct controls and the vocabulary shared with them.
- [Widget](widget.md) — everything a control answers before it adds its own.
- [Selectors](selectors.md) — naming a control, yours or the client's.
- [Style](style/README.md) — the rules that dress it.
