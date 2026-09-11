# hafen.ui: the client's own controls

A **control** is one of the client's own interactive widgets — the same class its own windows are built
from — that your addon builds, owns and destroys. You do not paint it: you place it, configure it, and the
client draws it, handles the hover and the press, and dresses it from the [stylesheet](../style/README.md).

That last part is the reason to reach for one: a rectangle you paint with [`g`](../drawing.md) stays outside
the theme as it grows, where a control is inside it from the first frame, with nothing written for that.

```lua
local win = hafen.ui():window():title("Harvest"):size(160, 60):position(80, 120)

local go = hafen.ui():button():parent(win):position(20, 20):text("Go")
go:on("Pressed", function() hafen.log():write("pressed") end)
```

## A control is a Widget

There is no separate control type. What a builder here hands back is the same
[Widget object](../widget.md) every lookup gives you, so every read and every write on that page answers on a
control with nothing added: `:type()`, `:role()`, `:position(x, y)`, `:size(w, h)`, `:parent(w)`,
`:visible(b)`, `:destroy()`, `:style()`, `:rule()`, `:info()`. A [selector](../selectors.md) finds one too —
`s:ui():matchAll("button")` finds the client's buttons in that character's tree, and a button you built is
found through the handle its builder gave you. `:type()` reports the **engine's** class, so a button you
built and a button you found read the same `"Button"` — one selector, one role and one stylesheet key
point at both.

## Builders

| Verb | Returns | The control | Page |
|---|---|---|---|
| `hafen.ui():button()` | [Widget](../widget.md) | a push button, showing a caption or a picture | [interactive](interactive.md#a-caption-or-a-picture) |
| `hafen.ui():entry()` | [Widget](../widget.md) | a single-line text field | [interactive](interactive.md#text-entry) |
| `hafen.ui():label()` | [Widget](../widget.md) | a line of text | [display](display.md#label) |
| `hafen.ui():image()` | [Widget](../widget.md) | a static picture | [display](display.md#picture) |
| `hafen.ui():separator()` | [Widget](../widget.md) | a horizontal rule | [display](display.md#separator) |
| `hafen.ui():progress()` | [Widget](../widget.md) | a fill-fraction bar | [display](display.md#progress-bar) |
| `hafen.ui():check()` | [Widget](../widget.md) | a checkbox, showing a caption or a picture | [interactive](interactive.md#checkbox) |
| `hafen.ui():radio()` | [Widget](../widget.md) | a set of buttons where exactly one is checked | [interactive](interactive.md#radio) |
| `hafen.ui():slider()` | [Widget](../widget.md) | a draggable position within a range | [interactive](interactive.md#slider) |
| `hafen.ui():scroll()` | [Widget](../widget.md) | a scrolling container for other controls | [interactive](interactive.md#scroll) |
| `hafen.ui():scrollbar()` | [Widget](../widget.md) | a bare scroll thumb, for driving something yourself | [interactive](interactive.md#scrollbar) |
| `hafen.ui():column()` | [Widget](../widget.md) | not a control — a surface of yours that stacks the controls put in it top to bottom | [column](../column.md) |
| `hafen.ui():row()` | [Widget](../widget.md) | the same surface, placing them left to right | [column](../column.md) |

Every builder here takes no argument. A control is born bare, with the client's own defaults, and everything
about it is a chained setter on the Widget it hands back — the same shape [`:window()` and
`:widget()`](../custom.md) have, including the rule that it **draws nothing until the tick after the
statement that built it**. So a control configured across five lines is never seen half-built, and
`:parent(w)` is a *building* verb: it chooses where the control hangs while being built, and once on screen
it moves by `:position(x, y)`. A row-source control has its own page: [lists](../lists.md).

## Setters

| Setter | Read | Meaning |
|---|---|---|
| `:text(s)` | `:text()` | the caption the control displays |
| `:image(up, down [, hover])` | `:image()` | the pictures the control shows instead of a caption |
| `:value(v)` | `:value()` | what the control **holds** |
| `:source(h)` | `:source()` | the picture a [picture control](display.md#picture) shows |
| `:rows(t)` | `:rows()` | the row source a [radio](interactive.md#radio) or a [listbox, dropdown, menu or grid](../lists.md) takes |
| `:range(min, max)` | `:range()` | the value bounds of a [slider or scrollbar](interactive.md#slider) |
| `:bind(opt)` | `:bind()` | the [option of your addon's](../../client/addon.md#binding-a-control-shows-the-option) a checkbox, slider, dropdown, radio or entry shows and writes; `:bind(nil)` unbinds |

Every setter returns the Widget, so a control is one expression, and each has a matching bare read: `:text()`
answers on any text-bearing widget, and so does `:text(s)` — on a control you built it writes the caption
outright, and on [one of the client's](../edit.md#what-a-window-says) it is a level that restores.

**`:bind(opt)` is what a control's `:value` becomes when the value is a setting.** Bound to one of your
addon's options, the control takes the option's value at once and is configured from it — a slider's
`:range` from the option's bounds, a dropdown's or a radio's `:rows` from its choices — the user moving it
writes `opt:value(v)`, and a write to the option moves the control without firing its own `Changed`. Each
kind of option has its control, and a control of another kind is refused naming the one it takes; a control
that holds no value, or one of the client's own, is refused the same way.

**`:value()` is the one verb for what a control holds**, whatever shape that is — a
[progress bar](display.md#progress-bar)'s is a fraction, and a control with nothing to hold reads `nil`
rather than throwing. Both halves answer on one of the client's own controls as well, but the write means
something else there — [driving](../edit.md#driving-one-protected) a control the user is looking at, which
the server sees, so that one is protected. A write is checked by the control it lands on: a
[progress bar](display.md#progress-bar) refuses one outside `0..1`, naming the rule, while a
[slider or scrollbar](interactive.md#slider) instead CLAMPS a write outside its own `:range` to the nearer
bound — because that range is something you set
yourself with `:range(min, max)` and can narrow at any time, not a fixed contract the value can violate.
What all three refuse first is the **type**: a number is a number here, and `"50"` is
[still a string](../../conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number) — the
mirror of what a [text entry](interactive.md#text-entry) takes. The two bounds of `:range(min, max)` are
read the same way.

None of this is protected on a control **you** built: it is your own UI, just as
[a surface you paint](../custom.md) is — every setter above is client-side state, and every one of it
restores with your addon.

## Sizing

`:size(w)` — one number — sets the width and leaves the height to the control's own **art**. That height is
the one measurement you cannot make: a button's bottom border is drawn at the bottom of the button's own
picture, so a box a pixel short of it simply loses the border, and there is no number you could write
instead that means the same thing on every client.

```lua
local go = hafen.ui():button():size(80):text("Go")
go:size()                                          -- {w = 80, h = 24}, at any interface scale
```

The controls whose art fixes a height answer it: the button, the text entry, the checkbox, the dropdown, the
slider and the separator. A control showing a **picture** — a button or a checkbox given `:image(...)` — is
that picture in both directions and wants no `:size` at all.

`:size(w, h)` sets both, in [design pixels](../pixels.md), and a box under the art's own **raises**, naming
the height it needs and this verb:

```lua
hafen.ui():button():size(80, 4)
-- widget:size(w, h) — a Button is 24 design px tall, which is its own ART's box: 4 clips it. …
```

A control the client stretches to whatever box it is given — a [listbox](../lists.md#listbox), a
[grid](../lists.md#grid), a [table](../lists.md#table), a [picture](display.md#picture) — has no art to ask,
so it takes `:size(w, h)` and refuses `:size(w)`, exactly as a [surface](../custom.md) does. A caption wider
than its box is drawn clipped, not wrapped.

**And you rarely add a container up.** A [column](../column.md) places the controls put in it one under the
other and is exactly their size, so a panel of rows is neither positioned nor measured by hand — and
[`:pack()`](../custom.md#packing-a-surface-around-what-is-inside-it) sizes the window around it, or
around controls you placed yourself, and keeps it sized as they change, so the box that holds them is read
rather than computed. A whole list of rows lays out this way: every row on one grid, not one of them
given a height.

## Subscribing

A control answers the five universal [`:on(key, fn)`](../widget.md#subscribing) keys every widget does —
it is a Widget first — plus exactly **one** capability key, the one thing that control does:

| Builder | Key | handler receives |
|---|---|---|
| `:button()` | `Pressed` | — |
| `:check()` / `:radio()` / `:slider()` / `:scrollbar()` / `:scroll()` / `:listbox()` / `:dropdown()` | `Changed` | varies — see [interactive](interactive.md) and [lists](../lists.md) |
| `:entry()` | `Changed` and `Submitted` | the text |
| `:menu()` | `Selected` | the picked row |
| `:grid()` | `Cell` | `ev` — see [grid](../lists.md#grid) |
| `:label()` `:image()` `:separator()` `:progress()` `:table()` | *(none)* | — |

```lua
go:on("Pressed", function() hafen.log():write("pressed") end)
```

**`Pressed` is an activation, not a mouse position.** It is what the button *did*, so it also fires from
the keyboard and carries no coordinates; it is safe for the handler to destroy the window the button sits
in.

**`Changed` fires when a control's value changes — and only from a real interaction.** A `:value(v)`
write from your own code never re-enters it, so driving a value from a script and reacting to the user
changing it never loop into each other. Two handlers on one key both fire, in registration order.

**The key belongs to the control, not to the addon that built it.** The same key answers on one of the
client's own controls, where the handler is also given an `ev` that can stop the client's action or run it
— [editing](../edit.md) is that page. Here your handler *is* the action, so there is nothing under it to
cancel and nothing is handed over but the value. **Cancelling is therefore a question of provenance, not of
the key**: one `Changed` is cancelable on the client's own checkbox and not on yours, and on a
[slider or scrollbar](interactive.md#slider) it is cancelable on neither — that control writes its value
before it reports it, whoever built it.

**A control you built is often made of the client's own smaller ones**, and those are borrowed: a
dropdown's drop arrow is one of the client's checkboxes, with a `Changed` of its own. Your `Changed` on the
dropdown is still the row the user picked — the key you own fires the one way it always did, and you never
receive the arrow's as well, nor the one its popup list of rows carries for it.

## Reading order

**The passive ones** — [display](display.md) is a label, a picture, a separator and a progress bar: nothing
here takes a click.

**The ones the user drives** — [interactive](interactive.md) is a button, a text entry, a checkbox, a radio,
a slider, a scroll and a scrollbar — the caption-or-picture vocabulary a button and a checkbox share lives
there too.

## What a control does not take

The four extra subscription keys of [custom](../custom.md) — `Draw`, `Update`, `Drop`, `Close` — and
`:font(h)` belong to a **surface you paint yourself**. A control is drawn and driven by the client, so it
has nowhere to put them and refuses rather than accepting one silently: how you learn a button fired is
`:on("Pressed", fn)`, and its look comes from the [stylesheet](../style/README.md), not a font handle you
hand the widget. The five universal input keys — `MouseDown`, `MouseUp`, `MouseMove`, `Wheel`, `Removed`
— are not among these: a control answers those too, being a Widget like any other.

## Owned and borrowed

A control your addon built is [owned](../writes.md#owned-vs-borrowed): the setters answer, `:destroy()` ends
it, and a `:reload` or a disable removes it for you. The client's own controls are **borrowed** — the reads
answer, and every setter on this page refuses, naming what to do instead (`:info().owned` is how you ask
rather than provoke the error). Its capability key is not a setter and answers on both:
[subscribing to a borrowed control](../edit.md) is how you take over what it does. Provenance comes from
the tree, so a control you find again with `hafen.ui():hit(x, y)` or a selector is the same object the
builder returned, writes and all.

## See also

- [display](display.md) — the label, picture, separator and progress bar
- [interactive](interactive.md) — the button, text entry, checkbox, radio, slider, scroll and scrollbar
- [widget](../widget.md) — everything a control answers before it adds anything of its own
- [edit](../edit.md) — the same capability keys, on the client's own controls
- [custom](../custom.md) — the surfaces a control goes in, and painting one yourself instead
- [selectors](../selectors.md) — naming a control, yours or the client's
- [style](../style/README.md) — the rules that dress it
