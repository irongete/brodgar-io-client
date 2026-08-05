# hafen.ui: lists

A **row-source control** takes its content from `:rows(t)` — a plain Lua array — rather than a caption or
a picture, and is dressed by the [stylesheet](style/README.md) like any other [control](controls.md). Three
share it: a list keeps every row on screen, a dropdown keeps one closed until clicked, and a menu fires on a
pick and holds nothing.

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

Built bare and configured by chained setters, [the same shape](controls.md#builders) every other control has
— the arming rule included.

## Rows

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

## See also

- [controls](controls.md) — the direct controls, and the vocabulary this page shares with them
- [widget](widget.md) — everything a control answers before it adds anything of its own
- [selectors](selectors.md) — naming a control, yours or the client's
- [style](style/README.md) — the rules that dress it
