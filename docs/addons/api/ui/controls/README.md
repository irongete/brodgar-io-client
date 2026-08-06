# hafen.ui: the client's own controls

A **control** is one of the client's own interactive widgets — the same class its own windows are built
from — that your addon builds, owns and destroys. You do not paint it: you place it, configure it, and the
client draws it, handles the hover and the press, and dresses it from the [stylesheet](../style/README.md).

That last part is the reason to reach for one: a rectangle you paint with [`g`](../drawing.md) stays outside
the theme as it grows, where a control is inside it from the first frame, with nothing written for that.

```lua
local win = hafen.ui():window():title("Harvest"):size(160, 60):position(80, 120)

hafen.ui():button()
  :parent(win):position(20, 20)
  :text("Go")
  :onPress(function() hafen.log():write("pressed") end)
```

## A control is a Widget

There is no separate control type. What a builder here hands back is the same
[Widget object](../widget.md) every lookup gives you, so every read and every write on that page answers on a
control with nothing added: `:type()`, `:role()`, `:position(x, y)`, `:size(w, h)`, `:parent(w)`,
`:visible(b)`, `:destroy()`, `:style()`, `:rule()`, `:info()`. A [selector](../selectors.md) finds one too —
`hafen.ui():all("button")` includes the buttons you built alongside the client's. `:type()` reports the
**engine's** class, so a button you built and a button you found read the same `"Button"` — one selector,
one role and one stylesheet key point at both.

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
| `:onPress(fn)` | `:onPress()` | `fn()` — the button fired |
| `:value(v)` | `:value()` | what the control **holds** |
| `:onChange(fn)` | `:onChange()` | `fn(v)` — the control's value changed |
| `:source(h)` | `:source()` | the picture a [picture control](display.md#picture) shows |
| `:rows(t)` | `:rows()` | the row source a [radio](interactive.md#radio) or a [list, dropdown, menu or grid](../lists.md) takes |
| `:range(min, max)` | `:range()` | the value bounds of a [slider or scrollbar](interactive.md#slider) |
| `:onSubmit(fn)` | `:onSubmit()` | `fn(s)` — Enter was pressed in a [text entry](interactive.md#text-entry) |

Every setter returns the Widget, so a control is one expression, and each has a matching bare read: `:text()`
answers on any text-bearing widget, `:text(s)` writes only on one you own, `:onPress()` reads `nil` otherwise.

**`:value()` is the one verb for what a control holds**, whatever shape that is — a
[progress bar](display.md#progress-bar)'s is a fraction, and a control with nothing to hold reads `nil`
rather than throwing. A write is checked by the control it lands on: a [progress bar](display.md#progress-bar)
refuses one outside `0..1`, naming the rule, while a [slider or scrollbar](interactive.md#slider) instead
CLAMPS a write outside its own `:range` to the nearer bound — because that range is something you set
yourself with `:range(min, max)` and can narrow at any time, not a fixed contract the value can violate.

**`:onPress` is an activation, not a mouse position.** It is what the control *did*, so it also fires from
the keyboard and carries no coordinates; it is safe for the handler to destroy the window the button sits in.

**`:onChange(fn)` fires when a control's value changes — and only from a real interaction.** A `:value(v)`
write from your own code never re-enters it, so driving a value from a script and reacting to the user
changing it never loop into each other. Setting it a second time replaces the handler.

**Sizing.** `:size(w, h)` sets the box like anywhere else, in raw pixels. A bare button already comes at the
client's own button height, so setting only a width you like and leaving the height alone is usually what
you want — and a caption wider than the box is drawn clipped, not wrapped. A button with a picture comes at
the size of that picture and normally wants no `:size` at all.

None of this is gated: a control is your own UI, the same as [a surface you paint](../custom.md) instead —
every setter above is client-side state, and every one of it restores with your addon.

## Reading order

**The passive ones** — [display](display.md) is a label, a picture, a separator and a progress bar: nothing
here takes a click.

**The ones the user drives** — [interactive](interactive.md) is a button, a text entry, a checkbox, a radio,
a slider, a scroll and a scrollbar — the caption-or-picture vocabulary a button and a checkbox share lives
there too.

## What a control does not take

The draw and input callbacks of [custom](../custom.md) — `:onDraw`, `:onTick`, `:onClick`, `:onMouseUp`,
`:onMouseMove`, `:onWheel`, `:onDrop`, `:onClose` — and `:font(h)` belong to a **surface you paint
yourself**. A control is drawn and driven by the client, so it has nowhere to put them and says so rather
than accepting one silently: how you learn a button fired is `:onPress(fn)`, and its look comes from the
[stylesheet](../style/README.md), not a font handle you hand the widget.

## Owned and borrowed

A control your addon built is [owned](../widget.md#owned-vs-borrowed): the setters answer, `:destroy()` ends
it, and a `:reload` or a disable removes it for you. The client's own controls are **borrowed** — the reads
answer, and every setter on this page refuses, naming what to do instead (`:info().owned` is how you ask
rather than provoke the error). Provenance comes from the tree, so a control you find again with
`hafen.ui():at(x, y)` or a selector is the same object the builder returned, writes and all.

## See also

- [display](display.md) — the label, picture, separator and progress bar
- [interactive](interactive.md) — the button, text entry, checkbox, radio, slider, scroll and scrollbar
- [widget](../widget.md) — everything a control answers before it adds anything of its own
- [custom](../custom.md) — the surfaces a control goes in, and painting one yourself instead
- [selectors](../selectors.md) — naming a control, yours or the client's
- [style](../style/README.md) — the rules that dress it
