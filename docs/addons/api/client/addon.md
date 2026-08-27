# hafen.client: your addon's own options

`hafen.client():options():addon()` is where your addon's own settings live. You name a row and its type;
the client draws the control, stores the value in its own preference store and answers reads — so a setting
of yours has the standing your [hotkeys](keybindings.md) already have, and the user finds it where they find
every other setting. Nothing here is protected. The
[guide](../../guides/hotkeys-and-commands.md) puts it beside the hotkey and the command, the other two ways
a user drives an addon by hand.

```lua
local opts = hafen.client():options():addon()

local show = opts:boolean("show-timer"):label("Show the timer"):default(true):add()

hafen.log():write("timer shown: " .. tostring(show:value()))
show:value(false)
show:on("Changed", function(v) hafen.log():write("now " .. tostring(v)) end)
```

Declare your rows in your file body. A row exists from the moment `:add()` runs and goes with your addon on
`:reload` or a disable — the value the user set stays, because it belongs to the client.

## The six rows

Each row is a **builder**: you call the verb for the type you want, chain the setters, and `:add()`
dispatches it. Every setter is legal until `:add()` and none after — `:add()` is where the declaration is
checked whole, because it is the first moment every part of it is in hand. Nothing is declared until then,
so a builder you abandon costs nothing and does not take its name.

| Builder | Drawn as | Beyond `:label` and `:tooltip` |
|---|---|---|
| `opts:boolean(name)` | a checkbox | `:default(true)` or `:default(false)` |
| `opts:number(name)` | a slider | `:range(lo, hi)` and `:default(n)`, both whole numbers |
| `opts:choice(name)` | a dropdown | `:choices(t)`, a 1-based array of strings, and `:default(s)`, one of them |
| `opts:text(name)` | a text field | `:default(s)` |
| `opts:button(name)` | a button | `:press(fn)`, run when the user presses the row |
| `opts:label(name)` | a line of text | `:text(s)`, the line, which you rewrite whenever you like |

`name` is **your own name for the row**, unique within your addon: it is what addresses the row afterwards
and what the value is stored under. It never reaches the screen — `:label(s)` is what the user reads, and it
falls back to the name when you give none. A `label` row is the exception: its caption is empty unless you
give one, so a bare `opts:label("status"):text("idle"):add()` states the line and nothing else.

| Setter | Takes | On |
|---|---|---|
| `:label(s)` | the caption drawn for the row | every builder |
| `:tooltip(s)` | the hover text | every builder |
| `:default(v)` | the value a client that has never been told otherwise reads | the four that carry a value |
| `:range(lo, hi)` | the inclusive bounds of a number row, `lo` below `hi` | `number` |
| `:choices(t)` | what the dropdown offers, in order | `choice` |
| `:press(fn)` | the function the row runs | `button` |
| `:text(s)` | the line a label row states | `label` |
| `:add()` | dispatch: the row is declared, and the Option comes back | every builder |

Every setter returns the builder, so they chain. A setter the type has not got is an error naming the
builder that does take it and what this one takes instead.

## Four carry a value, two do not

The four value rows persist. Their value is stored by the client, under a key of your addon's own, and it
survives `:reload`, a disable and a restart — it is **one per client**, like every other setting the Options
window edits, never one per character. Your addon cannot wipe it and does not have to save it.

A `button` and a `label` carry no value and store nothing: a button runs the function you gave it, a label
states the line you gave it. `value()` on either is an error naming what the row does carry, rather than
answering `nil` and letting the mistake fail a line later.

A **number** row is a whole number, because a slider is: `:range(1, 10)`, `:default(5)`, and a fractional
write is refused. Scale in your own addon if you need fractions — declare `0..100` and divide by a hundred.

## Where the user finds them

Your rows are drawn in **Options ▸ AddOns**, on a page of your addon's own. The list there holds the
addons that have declared a row, in the order they loaded; a page holds that addon's rows in the order it
declared them. Your addon appears the moment its first `:add()` runs and is absent while it has declared
none, so an addon with nothing to configure never puts an empty page there.

The client draws the control the type names, with `:label(s)` beside it and `:tooltip(s)` on hover. You
build no widget and choose no file.

> **The page and your value are one thing.** Each control reads its option as it draws, so a `value(v)`
> from your addon moves an open control with nothing to notify and no listener to register; and the user
> moving that control is a write through the same verb, so it fires the same `Changed`. There is no third
> place for the value to be, and nothing to keep in step.

## The Option object

`:add()` hands back the row, and so does `opts:option():get(name)`. It is the **same object** both ways and
every time after, so it works as a table key.

| Method | Returns | Description |
|---|---|---|
| `name()` | string | your own name for the row, its identity |
| `type()` | string | which of the six it is: `"boolean"`, `"number"`, `"choice"`, `"text"`, `"button"`, `"label"` |
| `label()` | string | the caption the client draws; empty on a label row you gave none |
| `tooltip()` | string \| nil | the hover text, `nil` where you gave none |
| `value()` | the value | what the row holds; **an error** on a `button` or a `label` |
| `value(v)` | the option | write it, checked against the row's own declaration |
| `default()` | the value | the default you declared; on the four that carry a value |
| `text()` / `text(s)` | string / the option | a `label` row's line, and the rewrite of it |
| `on("Changed", fn)` | a [subscription](../event/README.md#subscribe) | on the four that carry a value; see below |
| `info()` | table | `{name=, type=, label=, tooltip=, value=, default=}`, plus `min`/`max` on a number, `choices` on a choice, `text` on a label |

Reading and writing is [arity as the verb](../conventions.md#verbs-arity-is-the-verb), as everywhere else:
`value()` reads, `value(v)` writes and hands the option back so writes chain. A write is checked against
what the row declared — a boolean takes `true` or `false`, a number a whole one inside its range, a choice
one of the choices it offers, a text row a string — and an invalid one raises rather than being clipped.

```lua
local size = opts:number("rows"):label("Rows"):range(1, 20):default(8):add()
local mode = opts:choice("mode"):label("Mode"):choices{"compact", "wide"}:default("compact"):add()

size:value(size:value() + 1)
mode:value("wide")
```

## `Changed`: the one event

`opt:on("Changed", fn)` runs `fn` with the **new value** whenever the value moves, whether your addon wrote
it or the user moved the control. It is the only key an option has, and it is on the four that carry a
value.

```lua
local show = opts:boolean("show-timer"):label("Show the timer"):default(true):add()

local sub = show:on("Changed", function(v)
  hafen.log():write("the timer is " .. (v and "on" or "off"))
end)

sub:off()                       -- stop listening; the option and its value stay
```

The user moving a control moves the value as they move it, not when they are finished: `Changed` on a
`text` row fires once per keystroke, and on a `number` row once per step of a drag. A handler that does
something expensive with the new value does it that often, so do the expensive part on a
[timer](../timer.md) the handler restarts.

**A write of the value already held is not a change**: nothing is stored again and nothing fires. That is
what lets a control write back what it just read without a loop, and what makes a handler counting edges
count edges. Subscriptions are torn down with your addon like every other one, so there is nothing to end in
`Disable`.

## The collection

`opts:option()` is every option **your** addon declared, in declaration order. Another addon's options are
not reachable: remapping any key is something a user asks an addon to do, and writing another addon's
settings behind its back is not.

It carries the
[collection verbs](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) —
`:list(filter)`, `:count(filter)`, `:find(filter)` and `:get(name)` — and a string filter is a substring
test on the name. `:get(name)` answers `nil` for a name you have not declared.

```lua
for _, o in ipairs(opts:option():list()) do
  hafen.log():write(o:name() .. " (" .. o:type() .. ")")
end
```

## What a bad declaration says

`:add()` refuses a declaration the client cannot honour, and the message names the fix:

| The mistake | What you get |
|---|---|
| a value row with no `:default` | the setter to call |
| a `:default` outside the `:range` it declared | widen the range or move the default |
| a `number` row with no `:range` | a slider has no bounds without it |
| a `choice` row with no `:choices` | the dropdown offers nothing |
| a `button` row with no `:press` | the row would do nothing |
| a name your addon already declared | the row it already has, addressed |
| a setter after `:add()` | the row is declared; configure it before |
| a name too long for the client's store | give the row a shorter name |

## Example

```lua
local opts = hafen.client():options():addon()

local show  = opts:boolean("show"):label("Show the panel"):default(true):add()
local rows  = opts:number("rows"):label("Rows"):tooltip("how many lines"):range(1, 20):default(8):add()
local sort  = opts:choice("sort"):label("Sort by"):choices{"name", "amount"}:default("name"):add()
local title = opts:text("title"):label("Title"):default("Stock"):add()
local state = opts:label("state"):text("idle"):add()

opts:button("reset"):label("Reset to defaults"):press(function()
  for _, o in ipairs(opts:option():list()) do
    if o:type() ~= "button" and o:type() ~= "label" then o:value(o:default()) end
  end
  state:text("reset")
end):add()

show:on("Changed", function(v) state:text(v and "showing" or "hidden") end)

hafen.log():write(title:value() .. ": " .. rows:value() .. " rows, sorted by " .. sort:value())
```

## See also

- [`hafen.client():options()`](README.md) — the client's own settings, beside yours in the same window
- [keybindings](keybindings.md) — the hotkey your addon declares, the other thing of yours this window holds
- [conventions](../conventions.md) — builders, collections, and arity as the verb
- [`hafen.console`](../console.md) — a console command, the other way a user drives an addon by hand
- [`hafen.store`](../store.md) — your addon's own saved variables, for what is not a setting
