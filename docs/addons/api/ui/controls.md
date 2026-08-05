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
| `hafen.ui():button()` | [Widget](widget.md) | a push button, showing a caption or a picture |
| `hafen.ui():label()` | [Widget](widget.md) | a line of text |
| `hafen.ui():image()` | [Widget](widget.md) | a static picture |
| `hafen.ui():separator()` | [Widget](widget.md) | a horizontal rule |
| `hafen.ui():progress()` | [Widget](widget.md) | a fill-fraction bar |

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
| `:image(up, down [, hover])` | `:image()` | the pictures the control shows instead of a caption |
| `:onPress(fn)` | `:onPress()` | `fn()` — the button fired |
| `:value(v)` | `:value()` | what the control **holds** |
| `:source(h)` | `:source()` | the picture a [picture control](#picture) shows |

Every setter returns the Widget, so a control is one expression, and every one has a matching bare read.
`:text()` answers on any text-bearing widget, yours or the client's; `:text(s)` writes, and only on a
control you own. `:onPress()` reads `nil` on a widget that has nothing to press.

**`:value()` is the one verb for what a control holds**, whatever shape that is — a [progress bar](#progress-bar)'s
is a fraction, and a control with nothing to hold reads `nil` rather than throwing. A write is checked by the
control it lands on and refused, naming the rule, when it does not fit; it never silently clamps.

**`:onPress` is an activation, not a mouse position.** It is what the control *did*, so it also fires when
the button is triggered from the keyboard, and it carries no coordinates. It is safe for the handler to
destroy the window the button is sitting in.

**Sizing.** `:size(w, h)` sets the box like anywhere else, in raw pixels. A bare button already comes at the
client's own button height, so setting only a width you like and leaving the height alone is usually what
you want — and a caption wider than the box is drawn clipped, not wrapped. A button with a picture comes at
the size of that picture and normally wants no `:size` at all.

## A caption or a picture

A button shows text, or it shows pictures, and the setter you use is what decides:

```lua
hafen.ui():button():text("Go")                     -- a captioned button
hafen.ui():button():image(up, down, hover)         -- the same builder, a picture button
```

Two faces or three. `up` is what the button shows at rest, `down` while it is held, and `hover` the one
under the cursor; leave `hover` out and it is the same picture as `up`. Each face is either an
[image asset](../asset.md) your addon ships, passed as the handle, or a **string naming one of the client's
own images** — `"gfx/hud/buttons/addu"`, the very art the game's own windows are built from. Where the
picture comes from also decides how it is scaled: a file of yours is drawn at its own pixels, and the
client's art is scaled the way the client scales it, so a button made of game art matches the buttons
beside it.

```lua
local up, down = hafen.asset():get("up.png"), hafen.asset():get("down.png")
hafen.ui():button():parent(win):position(8, 8):image(up, down):onPress(refresh)
```

> **A face is chosen while the control is being built** — like `:parent(w)`, and unlike every other setter
> here. The client draws a captioned button and a picture button with two *different* widgets, so choosing
> pictures chooses which widget this is; once the control is on screen `:image` refuses, saying so. A
> caption is not a face: `:text(s)` rewrites one at any time.

The bare `:image()` reads the faces back as `{ up =, down =, hover = }`, exactly as you named them, and
`nil` on a control that shows no picture. `:type()` tells the two buttons apart — `"Button"` and
`"IButton"` — while `:role()` is `button` for both, so one selector still finds every button you built.

## Label

`hafen.ui():label()` is a line of text, dressed by the [stylesheet](style/README.md) like any other control.
`:text(s)` is its only content:

```lua
local l = hafen.ui():label():text("Stamina"):position(4, 4)
l:text(("%d%%"):format(n))     -- writing new text RESIZES the label to fit it
```

The box is exactly the rendered text, so writing a new caption changes `:size()` — a label placed against the
right edge of something else needs re-positioning after a write that changes its length, not just its
content. A label holds text only: it takes no picture, and `:image(...)` refuses on one naming the
[button/checkbox](#a-caption-or-a-picture) builder that does.

## Picture

`hafen.ui():image()` is a static picture with no interaction of its own. `:source(h)` gives it its content —
an [asset](../asset.md) handle or a string naming one of the client's own resources, the same two doors a
button's [face](#a-caption-or-a-picture) resolves:

```lua
hafen.ui():image():source(hafen.asset():get("logo.png")):position(0, 0)
```

Unlike a button's face, the picture is **not** chosen while the control is built: `:source(h)` may replace it
at any time, on screen or not. The bare `:source()` reads back exactly what was named, and `nil` before the
first `:source(h)`.

## Separator

`hafen.ui():separator()` is a plain horizontal rule, with no setter of its own — `:size(w, h)` is all there is
to it:

```lua
hafen.ui():separator():size(180, 1):position(0, 40)
```

## Progress bar

`hafen.ui():progress()` shows a fraction filled. `:value(v)` writes it, `0..1`, and `:value()` reads it back:

```lua
local p = hafen.ui():progress():size(120, 20):value(0.35)
p:value()          --> 0.35
```

A write outside `0..1` is refused rather than clamped — a raw percentage (`0..100`) passed by mistake fails
loudly instead of pinning silently at full.

## What a control does not take

The draw and input callbacks of [custom](custom.md) — `:onDraw`, `:onTick`, `:onClick`, `:onMouseUp`,
`:onMouseMove`, `:onWheel`, `:onDrop`, `:onClose` — and `:font(h)` belong to a **surface you paint
yourself**. A control is drawn and driven by the client, so it has nowhere to put them and says so rather
than accepting one silently: how you learn a button fired is `:onPress(fn)`, and what it looks like comes
from the [stylesheet](style/README.md), not from a font handle you hand the widget.

## Owned and borrowed

A control your addon built is [owned](widget.md#owned-vs-borrowed): the setters answer, `:destroy()` ends
it, and a `:reload` or a disable removes it for you. The client's own controls are **borrowed** — the reads
answer, and every setter on this page refuses, naming what to do instead. `:info().owned` is how you ask
rather than provoke the error.

Provenance comes from the tree, so a control you find again with `hafen.ui():at(x, y)` or a selector is the
same object the builder returned, writes and all.

## See also

- [widget](widget.md) — everything a control answers before it adds anything of its own
- [custom](custom.md) — the surfaces a control goes in, and painting one yourself instead
- [selectors](selectors.md) — naming a control, yours or the client's
- [style](style/README.md) — the rules that dress it
