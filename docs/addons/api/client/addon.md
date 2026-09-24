# hafen.client: Your Addon's Own Options

`hafen.client():options():addon()` holds your addon's own settings. An option is a stored value you name and type, kept in the client's preference store, checked on every write and answered on every read. It draws nothing. The page in **Options ▸ AddOns** is yours to fill with [`options:panel(fn)`](#the-page), a control you build and [bind](#binding-a-control-shows-the-option) shows an option there, and a [key button](../ui/controls/interactive.md#key-button) assigns one of your hotkeys. Nothing here is protected.

```lua
local options = hafen.client():options():addon()
local show_timer = options:boolean("show-timer"):default(true):add()

hafen.log():write("timer shown: " .. tostring(show_timer:value()))
show_timer:value(false)
show_timer:on("Changed", function(value) hafen.log():write("now " .. tostring(value)) end)
```

Declare options in your file body. One exists from the moment `:add()` runs and goes with your addon on `:reload` or a disable. The value the user set stays, because it belongs to the client. The [guide](../../guides/hotkeys-and-commands.md) puts an option beside the hotkey and the command.

---

## Declaring an option

Each option is a builder: the verb for the type, chained setters, `:add()` to dispatch. Every setter is legal until `:add()` and none after. `:add()` checks the declaration whole. A builder you abandon costs nothing and does not take its name.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `options:boolean(name)` | a builder | Unprotected | Declares an option holding `true` or `false`. `:default` is its only setter. |
| `options:number(name)` | a builder | Unprotected | Declares an option holding a whole number inside its range: `:range(low, high)`, both whole numbers, then `:default`. |
| `options:choice(name)` | a builder | Unprotected | Declares an option holding one of its choices: `:choices(table)`, a 1-based array of strings, then `:default`. |
| `options:text(name)` | a builder | Unprotected | Declares an option holding a string. `:default` is its only setter. |

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:default(value)` | the builder | Unprotected | The value a client never told otherwise reads. Every builder takes it. |
| `:range(low, high)` | the builder | Unprotected | The inclusive bounds of a number, `low` below `high`. A `number` builder takes it. Any other refuses naming the builder that does. |
| `:choices(table)` | the builder | Unprotected | What the option offers, in order. A `choice` builder takes it. Any other refuses naming the builder that does. |
| `:add()` | [`Option`](#the-option-object) | Unprotected | Dispatch: the option is declared and the Option comes back. A second `:add()` and [a bad declaration](#what-a-bad-declaration-says) refuse. |

| Rule | Detail |
|---|---|
| `name` | Your own name for the option, unique within your addon: what addresses it and what the value is stored under. |
| Setters chain | A setter the type lacks raises naming the builder that takes it. |
| No caption, hover text, button or line of text | Those are the control's on [your page](#the-page): `hafen.ui():check():text(text)` or a label beside a slider, the control's `:tooltip(text)`, `hafen.ui():button()`, `hafen.ui():label()`. A builder given a caption or hover text, and the handle asked for a button or a label, refuse naming the control to build. |
| The value persists | Stored by the client under a key of your addon's own. Survives `:reload`, a disable and a restart. One per client, never per character. Your addon cannot wipe it and need not save it. |
| A number is whole | `:range(1, 10)`, `:default(5)`. A fractional write is refused. Scale in your addon for fractions (declare `0..100`, divide). |

## The page

| Method | Returns | Permission | Description |
|---|---|---|---|
| `options:panel(fn)` | the handle | Unprotected | Register `fn(root)` as the page. A second call replaces the first. |
| `options:panel()` | `function \| nil` | Unprotected | The function registered. |
| `options:panel(nil)` | the handle | Unprotected | Withdraw the page: the row leaves the list, an open page closes. |

```lua
local options = hafen.client():options():addon()
options:panel(function(root)
  root:gap(4)
  hafen.ui():label():parent(root):text("Harvest")
  hafen.ui():check():parent(root):text("Only ripe")
  hafen.ui():button():parent(root):size(120):text("Reset")
end)
```

| Rule | Detail |
|---|---|
| The row | Your addon has a row in the AddOns list exactly while it holds a page. The row is the display name, in load order. |
| `root` | A [column](../ui/column.md) of your own inside a scrolling box the same size on every page, no heading. `root:role()` reads `"column"`. Its width is pinned to the box and its height follows what you put in it, so a taller page scrolls. `:gap`, `:stock` and `:enabled` answer on it, `:parent(root)` puts a control in, a theme's `["column"]` rule reaches it. Its `:parent()` is the client's: walking up reaches a window titled `Options`, in the tree of the character whose window it is. |
| A hotkey on the page | [`hafen.ui():keybinding()`](../ui/controls/interactive.md#key-button) bound to one of your hotkeys is the key button Options ▸ Game ▸ Keybindings gives it. The user assigns the key there as on the panel's row. |
| When `fn` runs | On the [step](../threading.md), one frame after the page is opened, holding no tree (it may build anything and reach any tree, `hafen.client():stepping()` reads `true`). Every time the page is opened: picking the row, coming back, once per Options window, again after `:reload`. |
| What it built dies with the page | `root` and every control parented into it are destroyed when the user leaves the row, when the list is re-read, when your addon reloads. A kept handle reads `:exists()` `false`, and there is nothing to end. A value belongs to an option, not the page. |
| An error in `fn` | Logged with the line. The page shows what `fn` built before it stopped. |

## Binding: a control shows the option

`widget:bind(option)` joins a [control](../ui/controls/README.md) you built to an option. The control takes the option's value at once. The user moving the control writes `option:value(value)`, so the option's `Changed` fires. A write to the option from anywhere moves every control bound to it.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:bind(option)` | the widget | Unprotected | Join the control to `option`, configured from it, taking the value in force. A second call replaces the first. |
| `widget:bind()` | `Option \| nil` | Unprotected | The option bound. `nil` while none, and on anything that binds nothing. |
| `widget:bind(nil)` | the widget | Unprotected | Unbind: the control keeps what it shows and moves with the option no more. |

| Control | Binds to | Configured from the option |
|---|---|---|
| `hafen.ui():check()` | `boolean` | The tick. |
| `hafen.ui():slider()` | `number` | `:range()` is the option's bounds, then the position. |
| `hafen.ui():dropdown()`, `hafen.ui():radio()` | `choice` | `:rows()` are the choices, in order, then the pick. |
| `hafen.ui():entry()` | `text` | The text. |

```lua
local options = hafen.client():options():addon()
local show = options:boolean("show"):default(true):add()
local rows = options:number("rows"):range(1, 20):default(8):add()
local sort = options:choice("sort"):choices{"name", "amount"}:default("name"):add()

options:panel(function(root)
  root:gap(4)
  hafen.ui():check():parent(root):text("Show the stock"):bind(show)
  hafen.ui():label():parent(root):text("Rows")
  hafen.ui():slider():parent(root):size(160):bind(rows)          -- 1..20, at the value in force
  hafen.ui():dropdown():parent(root):size(120):bind(sort)
end)
```

| Rule | Detail |
|---|---|
| The option fires | The user ticking the box is `show:value(true)`: the option's `Changed` fires first, then a handler on the control (`check:on("Changed", fn)`). `show:value(false)` from your code moves the box and fires no `Changed` on the box, [as on every control](../ui/controls/README.md#subscribing). A bound control in another tree than the write's moves on the next [step](../threading.md). |
| One option, many controls | A control binds to one option. An option may have any number of controls, moving together. A binding ends with the control (a rebuilt page, `:destroy()`). The option and its value stay. A control `:range`d or `:rows`ed past its option can be moved to a value the option refuses. The refusal is logged and the option keeps its value. |
| Refused | Different kinds (`slider:bind(show)` names the check a boolean takes). A control holding nothing an option stores (a button, a label, a listbox) names the pairs above. One of the client's own controls names the client, since [driving it](../ui/edit.md#driving-one-protected) is `:value(value)`. A [Binding](keybindings.md#the-binding-object) names `hafen.ui():keybinding()`, the control that joins one of your hotkeys. |

## The Option object

`:add()` hands back the option, and so does `options:option():get(name)`: the same object both ways, so it works as a table key.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `option:name()` | `string` | Unprotected | Your name for the option, its identity. |
| `option:type()` | `string` | Unprotected | `"boolean"`, `"number"`, `"choice"` or `"text"`. |
| `option:value()` | the value | Unprotected | What the option holds. |
| `option:value(value)` | the option | Unprotected | Write it, checked against the declaration: a boolean `true`/`false`, a number whole and in range, a choice one of its choices, a text a string. Invalid raises rather than clips. |
| `option:default()` | the value | Unprotected | The default you declared. |
| `option:on("Changed", fn)` | [subscription](../event/README.md#subscribe) | Unprotected | Run `fn(new_value)` whenever the value moves. The only key an option has. |
| `option:info()` | `table` | Unprotected | `{name=, type=, value=, default=}`, plus `min` and `max` on a number and `choices` on a choice. |

| Rule | Detail |
|---|---|
| Arity is the verb | `value()` reads, `value(value)` writes and hands the option back ([conventions](../conventions.md#verbs-arity-is-the-verb)). |
| A write of the value held is not a change | Nothing is stored again and nothing fires. |
| Subscriptions | Torn down with your addon. Nothing to end in `Disable`. `subscription:off()` stops listening, the option and its value stay. |

```lua
local rows = options:number("rows"):range(1, 20):default(8):add()
local mode = options:choice("mode"):choices{"compact", "wide"}:default("compact"):add()
rows:value(rows:value() + 1)
mode:value("wide")
```

## The collection

`options:option()` is every option your addon declared, in declaration order, with the [collection verbs](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(name)`. A string filter is a substring test on the name. `:get(name)` answers `nil` for a name not declared. Another addon's options are not reachable.

```lua
for _, option in ipairs(options:option():list()) do
  hafen.log():write(option:name() .. " (" .. option:type() .. ")")
end
```

## What a bad declaration says

| Mistake | `:add()` names |
|---|---|
| No `:default` | The setter to call. |
| A `:default` outside the `:range` | Widen the range or move the default. |
| A `number` with no `:range` | A slider bound to it has no bounds without it. |
| A `choice` with no `:choices` | The option offers nothing. |
| A choice listed twice | The repeat, by index: each choice is offered once, and a radio shows one row per choice. |
| A name your addon already declared | The option it already has. |
| A setter after `:add()` | The option is declared. Configure it before. |
| A name too long for the client's store | Give the option a shorter name. |

## Example

```lua
local options = hafen.client():options():addon()
local show  = options:boolean("show"):default(true):add()
local rows  = options:number("rows"):range(1, 20):default(8):add()
local sort  = options:choice("sort"):choices{"name", "amount"}:default("name"):add()
local title = options:text("title"):default("Stock"):add()

hafen.console():on("stockreset", function()
  for _, option in ipairs(options:option():list()) do option:value(option:default()) end
end)
show:on("Changed", function(value) hafen.log():write(value and "showing" or "hidden") end)

options:panel(function(root)
  root:gap(4)
  hafen.ui():check():parent(root):text("Show the stock window"):bind(show)
  hafen.ui():slider():parent(root):size(160):bind(rows)
  hafen.ui():radio():parent(root):bind(sort)
  hafen.ui():entry():parent(root):size(160):bind(title)
end)
hafen.log():write(title:value() .. ": " .. rows:value() .. " rows, sorted by " .. sort:value())
```

---

## See Also

- [`hafen.client():options()`](README.md) — the client's own settings, beside yours in the same window.
- [Keybindings](keybindings.md) — the hotkey your addon declares, the other thing of yours this window holds.
- [Columns and rows](../ui/column.md) — what `root` is, and how it places what you build.
- [Controls](../ui/controls/README.md) — what you bind an option to, and what each control holds.
- [Conventions](../conventions.md) — builders, collections, and arity as the verb.
- [`hafen.store`](../store/README.md) — your addon's own vars, for what is not a setting.
