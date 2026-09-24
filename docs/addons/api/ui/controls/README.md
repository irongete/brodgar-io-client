# hafen.ui: The Client's Own Controls

A control is one of the client's interactive widgets, the class its own windows are built from. Your addon builds, configures and owns it. The client draws it, handles the press and dresses it from the [stylesheet](../style/README.md).

```lua
local harvest_window = hafen.ui():window():title("Harvest"):size(160, 60):position(80, 120)

local go_button = hafen.ui():button():parent(harvest_window):position(20, 20):text("Go")
go_button:on("Pressed", function() hafen.log():write("pressed") end)
```

---

## A control is a Widget

A builder hands back the same [Widget](../widget.md) every lookup gives you. Every read and write on that page answers on a control: `:type()`, `:role()`, `:position(x, y)`, `:size(w, h)`, `:parent(w)`, `:visible(b)`, `:destroy()`, `:style()`, `:rule()`, `:info()`. `:type()` reports the client's class, so a button you built and one you found both read `"Button"`. One [selector](../selectors.md), one role and one stylesheet key name both.

## Builders

| Method | Returns | Permission | The control | Page |
|---|---|---|---|---|
| `hafen.ui():button()` | [`Widget`](../widget.md) | Unprotected | A push button showing a caption or a picture. | [interactive](interactive.md#a-caption-or-a-picture) |
| `hafen.ui():entry()` | `Widget` | Unprotected | A single-line text field. | [interactive](interactive.md#text-entry) |
| `hafen.ui():label()` | `Widget` | Unprotected | A line of text. | [display](display.md#label) |
| `hafen.ui():image()` | `Widget` | Unprotected | A static picture. | [display](display.md#picture) |
| `hafen.ui():separator()` | `Widget` | Unprotected | A horizontal rule. | [display](display.md#separator) |
| `hafen.ui():progress()` | `Widget` | Unprotected | A fill-fraction bar. | [display](display.md#progress-bar) |
| `hafen.ui():check()` | `Widget` | Unprotected | A checkbox showing a caption or a picture. | [interactive](interactive.md#checkbox) |
| `hafen.ui():radio()` | `Widget` | Unprotected | Buttons of which exactly one is checked. | [interactive](interactive.md#radio) |
| `hafen.ui():slider()` | `Widget` | Unprotected | A draggable position within a range. | [interactive](interactive.md#slider) |
| `hafen.ui():scroll()` | `Widget` | Unprotected | A scrolling container for other controls. | [interactive](interactive.md#scroll) |
| `hafen.ui():scrollbar()` | `Widget` | Unprotected | A bare scroll thumb, for driving something yourself. | [interactive](interactive.md#scrollbar) |
| `hafen.ui():keybinding()` | `Widget` | Unprotected | The client's key button, joined to one of your hotkeys. | [interactive](interactive.md#key-button) |
| `hafen.ui():listbox()`, `:dropdown()`, `:menu()`, `:grid()`, `:table()` | `Widget` | Unprotected | The row-source controls. | [lists](../lists.md) |
| `hafen.ui():column()`, `:row()` | `Widget` | Unprotected | Not controls: surfaces of yours that lay the controls in them out. | [column](../column.md) |

No builder takes an argument. A control is built bare with the client's defaults and configured by chained setters. It draws nothing until the tick after the statement that built it, so it is never seen half-built. `:parent(w)` chooses where it hangs while it is being built. On screen it moves by `:position(x, y)`.

## Setters

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:text(caption)` / `:text()` | `self` / `string \| nil` | Unprotected | The caption the control displays. The read answers on any text-bearing widget. On [a client control](../edit.md#what-a-window-says) the write is a restoring level. |
| `:image(up, down [, hover])` / `:image()` | `self` / `table \| nil` | Unprotected | The pictures the control shows instead of a caption. |
| `:value(v)` / `:value()` | `self` / `any \| nil` | Unprotected on a control you built. [`widget.value`](../edit.md#driving-one-protected) on a client control | What the control holds. |
| `:source(h)` / `:source()` | `self` / `string \| userdata \| nil` | Unprotected | The picture a [picture control](display.md#picture) shows. |
| `:rows(t)` / `:rows()` | `self` / `table \| nil` | Unprotected | The row source a [radio](interactive.md#radio) or a [row-source control](../lists.md) takes. |
| `:range(min, max)` / `:range()` | `self` / `{min=, max=} \| nil` | Unprotected | The bounds of a [slider or scrollbar](interactive.md#slider). |
| `:bind(opt)`, `:bind(binding)` / `:bind()` | `self` / `Option \| Binding \| nil` | Unprotected | The [option of your addon's](../../client/addon.md#binding-a-control-shows-the-option) a checkbox, slider, dropdown, radio or entry shows and writes, or the [hotkey of yours](interactive.md#key-button) a key button assigns. `:bind(nil)` unbinds. |

Every setter returns the Widget and has a bare read. None is protected on a control you built: it is your own client-side UI, restored with your addon.

| Rule | Detail |
|---|---|
| `:bind(opt)` | The control takes the option's value at once and is configured from it. A slider's `:range` comes from its bounds, a dropdown's or radio's `:rows` from its choices. The user moving the control writes `opt:value(v)`. A write to the option moves the control without firing its `Changed`. A control of another kind than the option's is refused naming the one it takes. So is a control holding no value, and a client control. |
| `:bind(binding)` | A key button's alone: a [Binding](../../client/keybindings.md#the-binding-object) of a hotkey your addon declared and has not ended, its key shown at once. An Option given to a key button, and a Binding given to any other control, are refused naming the control that takes it. |
| `:value()` | One verb for whatever a control holds: a [progress bar](display.md#progress-bar)'s fraction, a checkbox's boolean, an entry's string, a [key button](interactive.md#key-button)'s key. `nil` where it holds nothing. On a client control the write is [driving](../edit.md#driving-one-protected) and protected. A key button you built refuses the write: its key is its binding's. |
| Value checks | The type first: a number is a number and `"50"` is [a string](../../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). `:range(min, max)`'s bounds are read the same way. A progress bar refuses a value outside `0..1`, naming the rule. A slider or scrollbar clamps a value outside its `:range` to the nearer bound, that range being yours to narrow at any time. |

## Sizing

| Method | Returns | Permission | Description |
|---|---|---|---|
| `:size(w)` | `self` | Unprotected | Sets the width. The height is the control's own art. Answered by the button, key button, text entry, checkbox, dropdown, slider and separator. |
| `:size(w, h)` | `self` | Unprotected | Sets both, in [design pixels](../pixels.md). A box under the art's own raises, naming the height it needs: `widget:size(w, h) — a Button is 24 design px tall, which is its own ART's box: 4 clips it`. |

```lua
local go_button = hafen.ui():button():size(80):text("Go")
go_button:size()                     -- {w = 80, h = 24}, at any interface scale
```

| Rule | Detail |
|---|---|
| A control showing a picture | A button or checkbox given `:image(...)` is that picture in both directions and takes no `:size`. |
| Stretchable controls | A [listbox](../lists.md#listbox), [grid](../lists.md#grid), [table](../lists.md#table) or [picture](display.md#picture) has no art to ask: it takes `:size(w, h)` and refuses `:size(w)`, as a [surface](../custom.md) does. |
| Clipping | A caption wider than its box is drawn clipped, not wrapped. |
| Containers | A [column](../column.md) places controls one under the other at their own size. [`:pack()`](../custom.md#packing-a-surface-around-what-is-inside-it) sizes a window around them and keeps it sized. |

## Subscribing

A control answers the universal [`:on(key, fn)`](../widget.md#subscribing) keys plus one capability key, the thing that control does.

| Builder | Key | Handler receives |
|---|---|---|
| `:button()` | `Pressed` | — |
| `:check()`, `:radio()`, `:slider()`, `:scrollbar()`, `:scroll()`, `:listbox()`, `:dropdown()` | `Changed` | The value — [interactive](interactive.md), [lists](../lists.md). |
| `:entry()` | `Changed`, `Submitted` | The text. |
| `:menu()` | `Selected` | The picked row. |
| `:grid()` | `Cell` | `event` — [grid](../lists.md#grid). |
| `:label()`, `:image()`, `:separator()`, `:progress()`, `:table()` | none | — |

| Rule | Detail |
|---|---|
| `Pressed` is an activation | What the button did, from the mouse or the keyboard. No coordinates. The handler may destroy the window the button sits in. |
| `Changed` fires on a real interaction only | A `:value(v)` write of yours never re-enters it. Two handlers on one key both fire, in registration order. |
| The key belongs to the control, not to who built it | The same key answers on a client control, with an `event` that can stop or run the client's action — [edit](../edit.md). On yours the handler is the action: nothing to cancel, only the value handed over. A borrowed slider's or scrollbar's `Changed` is cancelable on neither: the value is written before it is reported. |
| Inner client widgets | A dropdown's arrow is one of the client's checkboxes with a `Changed` of its own. Your `Changed` on the dropdown is the picked row and never the arrow's or the popup list's. |

## What a control does not take

`Draw`, `Update`, `Drop`, `Close` and `:font(h)` belong to a [surface you paint](../custom.md). A control refuses them, naming `:on("Pressed", fn)` and the [stylesheet](../style/README.md). The universal input keys (`MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Removed`) it answers like any widget.

## Owned and borrowed

A control your addon built is [owned](../writes.md#owned-vs-borrowed): the setters answer, `:destroy()` ends it, `:reload` and disable remove it. A client control is borrowed. The reads answer. Every setter refuses naming what to do instead (`:info().owned` tells which you hold). Its capability key answers on both: [subscribing to a borrowed control](../edit.md) is how you take over what it does. A control found again with `hafen.ui():hit(x, y)` or a selector is the same object the builder returned.

---

## See Also

- [Display](display.md) — label, picture, separator, progress bar.
- [Interactive](interactive.md) — button, text entry, checkbox, radio, slider, scroll, scrollbar, key button.
- [Lists](../lists.md) — listbox, dropdown, menu, grid, table.
- [Widget](../widget.md) — everything a control answers before it adds its own.
- [Edit](../edit.md) — the same capability keys on the client's controls.
- [Custom](../custom.md), [Column](../column.md) — the surfaces a control goes in.
- [Style](../style/README.md) — the rules that dress it.
