# hafen.client: your addon's own options

`hafen.client():options():addon()` is where your addon's own settings live. You name a row and its type;
the client stores the value in its own preference store and answers reads — so a setting of yours has the
standing your [hotkeys](keybindings.md) already have. The page the user finds it on, in **Options ▸ AddOns**,
is yours to fill: [`opts:panel(fn)`](#the-page) registers the function the client calls with a column of
your own each time the page is opened. Nothing here is protected. The
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

| Builder | Holds | Beyond `:label` and `:tooltip` |
|---|---|---|
| `opts:boolean(name)` | `true` or `false` | `:default(true)` or `:default(false)` |
| `opts:number(name)` | a whole number inside its range | `:range(lo, hi)` and `:default(n)`, both whole numbers |
| `opts:choice(name)` | one of its choices | `:choices(t)`, a 1-based array of strings, and `:default(s)`, one of them |
| `opts:text(name)` | a string | `:default(s)` |
| `opts:button(name)` | no value: a function | `:press(fn)`, the function the row runs |
| `opts:label(name)` | no value: a line of text | `:text(s)`, the line, which you rewrite whenever you like |

`name` is **your own name for the row**, unique within your addon: it is what addresses the row afterwards
and what the value is stored under. `:label(s)` is the row's caption, and it falls back to the name when you
give none. A `label` row is the exception: its caption is empty unless you give one, so a bare
`opts:label("status"):text("idle"):add()` states the line and nothing else.

| Setter | Takes | On |
|---|---|---|
| `:label(s)` | the caption of the row | every builder |
| `:tooltip(s)` | the hover text | every builder |
| `:default(v)` | the value a client that has never been told otherwise reads | the four that carry a value |
| `:range(lo, hi)` | the inclusive bounds of a number row, `lo` below `hi` | `number` |
| `:choices(t)` | what the row offers, in order | `choice` |
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

A **number** row is a whole number: `:range(1, 10)`, `:default(5)`, and a fractional write is refused. Scale in your own addon if you need fractions — declare `0..100` and divide by a hundred.

## The page

`opts:panel(fn)` registers **the page**: the one function the client calls with `root`, a
[column](../ui/column.md) of your addon's own inside your page of **Options ▸ AddOns**, each time the user
opens that page. Your addon has a row in the AddOns list exactly while it holds a page — an addon with
nothing to configure never puts an empty page there — and the row is the addon's display name, in the order
the addons loaded.

| Verb | Returns | Description |
|---|---|---|
| `opts:panel(fn)` | the handle | register `fn(root)` as the page; a second call replaces the first; unprotected |
| `opts:panel()` | function \| nil | the function registered, `nil` while there is none |
| `opts:panel(nil)` | the handle | withdraw the page: the row leaves the list, and an open page closes |

```lua
local opts = hafen.client():options():addon()

opts:panel(function(root)
  root:gap(4)
  hafen.ui():label():parent(root):text("Harvest")
  hafen.ui():check():parent(root):text("Only ripe")
  hafen.ui():button():parent(root):size(120):text("Reset")
end)
```

**What the page is.** The client draws the heading — your addon's display name — and under it a scrolling
box the same size on every page of the window; `root` stands inside that box, and everything you build is
placed by it. `root:role()` reads `"column"`, its width is pinned to the box and its height follows what you
put in it, so a page taller than the box scrolls and a shorter one leaves the rest empty. It is a column
you own, so `:gap`, `:stock` and `:enabled` answer on it, `:parent(root)` on a control you are building
puts the control in, and a theme's rule for `["column"]` reaches it. Its `:parent()` is the client's:
walking up from `root` reaches a window titled `Options`, in the tree of the character whose window it is.

**When `fn` runs.** On the [step](../threading.md), one frame after the page is opened, holding no tree —
like an `Added` handler it may build anything and reach any tree. It runs every time the page is opened:
picking the row, coming back to it, once per Options window, and again after `:reload`. It runs with
`hafen.client():stepping()` reading `true`, and the ordinary rule on
[what a handler may reach](../threading.md#where-each-handler-runs) is the whole of what you need to know.

**What it built dies with the page.** The client rebuilds the page on every visit, so `root` and every
control you parented into it are destroyed when the user leaves the row, when the list is re-read, and when
your addon reloads. A handle you kept reads `:exists()` `false` after that, and there is nothing of yours
to end: build the page in `fn` and build it again the next time `fn` runs. A value the page shows belongs to
an option, not to the page, so nothing is lost with it.

**An error in `fn` is logged**, with the line that raised it, and the page shows the heading and whatever
`fn` had built before it stopped.

**Your rows are not drawn by the client.** A row you declare with the builders above is stored and
answers reads and writes; it is placed on the page only where your `fn` puts a control for it.

## The Option object

`:add()` hands back the row, and so does `opts:option():get(name)`. It is the **same object** both ways and
every time after, so it works as a table key.

| Method | Returns | Description |
|---|---|---|
| `name()` | string | your own name for the row, its identity |
| `type()` | string | which of the six it is: `"boolean"`, `"number"`, `"choice"`, `"text"`, `"button"`, `"label"` |
| `label()` | string | the caption you declared; empty on a label row you gave none |
| `tooltip()` | string \| nil | the hover text, `nil` where you gave none |
| `value()` | the value | what the row holds; **an error** on a `button` or a `label` |
| `value(v)` | the option | write it, checked against the row's own declaration |
| `default()` | the value | the default you declared; on the four that carry a value |
| `text()` / `text(s)` | string / the option | a `label` row's line, and the rewrite of it |
| `on("Changed", fn)` | a [subscription](../event/README.md#subscribe) | on the four that carry a value; see below |
| `info()` | table | `{name=, type=, label=}`, plus `tooltip` where you gave one, `value` and `default` on the four that carry a value, `min`/`max` on a number, `choices` on a choice and `text` on a label |

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

`opt:on("Changed", fn)` runs `fn` with the **new value** whenever the value moves. It is the only key an
option has, and it is on the four that carry a value.

```lua
local show = opts:boolean("show-timer"):label("Show the timer"):default(true):add()

local sub = show:on("Changed", function(v)
  hafen.log():write("the timer is " .. (v and "on" or "off"))
end)

sub:off()                       -- stop listening; the option and its value stay
```

**A write of the value already held is not a change**: nothing is stored again and nothing fires, which is
what makes a handler counting edges count edges. Subscriptions are torn down with your addon like every
other one, so there is nothing to end in `Disable`.

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
