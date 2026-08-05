# hafen.ui: the client's own controls

A **control** is one of the client's own interactive widgets — the same class its own windows are built
from — that your addon builds, owns and destroys. You do not paint it: you place it, configure it, and the
client draws it, handles the hover and the press, and dresses it from the [stylesheet](style/README.md).

That last part is the reason to reach for one. A rectangle you paint yourself with
[`g`](drawing.md) is outside the theme permanently, and stays outside it as the theme grows; a control is
inside it from the first frame, with nothing written for that.

```lua
local win = hafen.ui():window():title("Harvest"):size(160, 60):position(80, 120)

hafen.ui():button()
  :parent(win):position(20, 20)
  :text("Go")
  :onPress(function() hafen.log():write("pressed") end)
```

## A control is a Widget

There is no separate control type. What a builder here hands back is the same
[Widget object](widget.md) every lookup gives you, so every read and every write on that page answers on a
control with nothing added: `:type()`, `:role()`, `:position(x, y)`, `:size(w, h)`, `:parent(w)`,
`:visible(b)`, `:destroy()`, `:style()`, `:rule()`, `:info()`. A [selector](selectors.md) finds one too —
`hafen.ui():all("button")` includes the buttons you built alongside the client's.

`:type()` reports the **engine's** class, so a button you built and a button you found read the same
`"Button"`. That is what keeps one selector, one role and one stylesheet key pointing at both.

## Builders

| Verb | Returns | The control |
|---|---|---|
| `hafen.ui():button()` | [Widget](widget.md) | a push button, at the client's own button height |

It takes no argument. A control is born bare, with the client's own defaults, and everything about it is a
chained setter on the Widget it hands back — the same shape [`:window()` and `:widget()`](custom.md) have,
including the rule that it **draws nothing until the tick after the statement that built it**. So a control
configured across five lines is never seen half-built, and `:parent(w)` is a *building* verb: it chooses
where the control hangs while it is being built, and once it is on screen the way to move it is
`:position(x, y)`.

## Setters

| Setter | Read | Meaning |
|---|---|---|
| `:text(s)` | `:text()` | the caption the control displays |
| `:onPress(fn)` | `:onPress()` | `fn()` — the button fired |

Every setter returns the Widget, so a control is one expression, and every one has a matching bare read.
`:text()` answers on any text-bearing widget, yours or the client's; `:text(s)` writes, and only on a
control you own. `:onPress()` reads `nil` on a widget that has nothing to press.

**`:onPress` is an activation, not a mouse position.** It is what the control *did*, so it also fires when
the button is triggered from the keyboard, and it carries no coordinates. It is safe for the handler to
destroy the window the button is sitting in.

**Sizing.** `:size(w, h)` sets the box like anywhere else, in raw pixels. A bare button already comes at the
client's own button height, so setting only a width you like and leaving the height alone is usually what
you want — and a caption wider than the box is drawn clipped, not wrapped.

## What a control does not take

The draw and input callbacks of [custom](custom.md) — `:onDraw`, `:onTick`, `:onClick`, `:onMouseUp`,
`:onMouseMove`, `:onWheel`, `:onDrop`, `:onClose` — and `:font(h)` belong to a **surface you paint
yourself**. A control is drawn and driven by the client, so it has nowhere to put them and says so rather
than accepting one silently: how you learn a button fired is `:onPress(fn)`, and what it looks like comes
from the [stylesheet](style/README.md), not from a font handle you hand the widget.

## Owned and borrowed

A control your addon built is [owned](widget.md#owned-vs-borrowed): the setters answer, `:destroy()` ends
it, and a `:reload` or a disable removes it for you. The client's own buttons are **borrowed** — the reads
answer, and `:text(s)`, `:onPress(fn)` and `:destroy()` refuse, naming what to do instead. `:info().owned`
is how you ask rather than provoke the error.

Provenance comes from the tree, so a control you find again with `hafen.ui():at(x, y)` or a selector is the
same object the builder returned, writes and all.

## See also

- [widget](widget.md) — everything a control answers before it adds anything of its own
- [custom](custom.md) — the surfaces a control goes in, and painting one yourself instead
- [selectors](selectors.md) — naming a control, yours or the client's
- [style](style/README.md) — the rules that dress it
