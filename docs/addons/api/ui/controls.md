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
| `hafen.ui():check()` | [Widget](widget.md) | a checkbox, showing a caption or a picture |
| `hafen.ui():radio()` | [Widget](widget.md) | a set of buttons where exactly one is checked |
| `hafen.ui():slider()` | [Widget](widget.md) | a draggable position within a range |
| `hafen.ui():scrollbar()` | [Widget](widget.md) | a bare scroll thumb, for driving something yourself |

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
| `:onChange(fn)` | `:onChange()` | `fn(v)` — the control's value changed |
| `:source(h)` | `:source()` | the picture a [picture control](#picture) shows |
| `:rows(t)` | `:rows()` | the row labels a [radio](#radio) shows |
| `:range(min, max)` | `:range()` | the value bounds of a [slider or scrollbar](#slider) |

Every setter returns the Widget, so a control is one expression, and every one has a matching bare read.
`:text()` answers on any text-bearing widget, yours or the client's; `:text(s)` writes, and only on a
control you own. `:onPress()` reads `nil` on a widget that has nothing to press.

**`:value()` is the one verb for what a control holds**, whatever shape that is — a [progress bar](#progress-bar)'s
is a fraction, and a control with nothing to hold reads `nil` rather than throwing. A write is checked by the
control it lands on: a [progress bar](#progress-bar) refuses one outside `0..1`, naming the rule, while a
[slider or scrollbar](#slider) instead CLAMPS a write outside its own `:range` to the nearer bound — because
that range is something you set yourself with `:range(min, max)` and can narrow at any time, not a fixed
contract the value can violate.

**`:onPress` is an activation, not a mouse position.** It is what the control *did*, so it also fires when
the button is triggered from the keyboard, and it carries no coordinates. It is safe for the handler to
destroy the window the button is sitting in.

**`:onChange(fn)` fires when a control's value changes — and only from a real interaction.** Setting
`:value(v)` from your own code never re-enters it, so driving a control's value from a script and reacting
to the user changing it are two different things that never loop into each other. Setting it a second time
replaces the handler; only the latest one is called.

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
[button](#a-caption-or-a-picture) or [checkbox](#checkbox) builder that does.

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

## Checkbox

`hafen.ui():check()` is a boolean toggle. `:value(v)` holds the tick and `:onChange(fn)` fires when the user
changes it:

```lua
local c = hafen.ui():check():text("Show grid"):value(true)
  :onChange(function(on) hafen.store():get("cfg").grid = on end)

c:value()          --> true
```

Like a button, it shows text or it shows pictures:

```lua
hafen.ui():check():image(up, down, hoverUp, hoverDown)
```

Four faces here, not two or three — a checkbox carries two persistent states, ticked and not, each with its
own hover: `up`/`down` are the two states at rest, `hoverUp`/`hoverDown` are each of those under the cursor.
All four are required, resolved through the same two doors a button's [face](#a-caption-or-a-picture) is.
Choosing pictures is building-only here too, and the bare `:image()` reads them back as `{up=, down=,
hoverUp=, hoverDown=}`. `:type()` reads `"CheckBox"` or `"ICheckBox"` depending which you built.

## Radio

`hafen.ui():radio()` is a set of buttons where exactly one is checked at a time — one control, not one
object per button. `:rows{...}` names the choices, `:value(v)` reads and writes which one is checked, and
`:onChange(fn)` fires when the user picks a different one:

```lua
local r = hafen.ui():radio()
  :rows{"Quality", "Amount", "Name"}
  :value("Amount")
  :onChange(function(pick) sortBy(pick) end)

r:value()          --> "Amount"
```

The rows are laid out in a single column below the control's own `:position`, each one under the last;
`:size()` reads the box of the whole stack, not one row. Writing `:rows{...}` again replaces the whole set —
the row that was checked does not carry over, and an empty `:rows{}` is a control with nothing in it rather
than an error. `:value(v)` naming a row that is not in the current set is refused, naming the rows that are.

## Slider

`hafen.ui():slider()` is a draggable position within a range. `:range(min, max)` sets the bounds,
`:value(n)` reads and writes the position within them, and `:onChange(fn)` fires while the user drags it:

```lua
local s = hafen.ui():slider()
  :size(140, 20)
  :range(0, 100)
  :value(50)
  :onChange(function(v, final) preview(v); if final then save(v) end end)
```

`:onChange`'s handler takes **two** arguments here — `fn(v, final)` — where every other control's takes one.
`final` is `false` on every step while the thumb is being dragged and `true` exactly once, when the mouse is
released, which is the moment to act on the value rather than merely preview it.

A `:value(v)` outside `:range` **clamps** to the nearer bound rather than refusing; narrowing `:range(min,
max)` later re-clamps a value the new bounds no longer cover, silently — that write is not something the
user did, so it does not fire `:onChange`. `:range(nil)` is refused like any other required argument, naming
the missing bound; there is no "undo" meaning for a control's own bounds the way `:position(nil)` undoes a
layer.

## Scrollbar

`hafen.ui():scrollbar()` is a bare scroll thumb, for driving something yourself — the same `:range(min,
max)`/`:value(n)` as a [slider](#slider), minus the `final` flag:

```lua
local sb = hafen.ui():scrollbar()
  :size(14, 160)
  :range(0, #items - visibleRows)
  :value(0)
  :onChange(function(v) firstRow = v end)
```

`:onChange`'s handler here takes **one** argument, `fn(v)` — a bare scrollbar has no separate "drag ended"
moment to report, so every step just reports where it is now.

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
