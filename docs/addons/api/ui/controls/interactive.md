# hafen.ui: interactive controls

A button, a text entry, a checkbox, a radio, a slider, a scroll and a scrollbar — the [controls](README.md)
the user drives. Each is born bare and configured by chained setters, [the same shape](README.md#builders)
every control has, and dressed by the [stylesheet](../style/README.md) like any other.

## A caption or a picture

A button shows text, or it shows pictures, and the setter you use is what decides:

```lua
hafen.ui():button():text("Go")                     -- a captioned button
hafen.ui():button():image(up, down, hover)         -- the same builder, a picture button
```

`up` is what the button shows at rest, `down` while it is held, and `hover` the one under the cursor;
leave `hover` out and it is the same picture as `up`. Each face is either an
[image asset](../../asset.md) your addon ships, passed as the handle, or a **string naming one of the
client's own images** — `"gfx/hud/buttons/addu"`, the very art the game's own windows are built from, scaled
the way the client scales it so a button made of game art matches the buttons beside it; a file of yours is
drawn at its own pixels instead.

```lua
local up, down = hafen.asset():get("up.png"), hafen.asset():get("down.png")
local btn = hafen.ui():button():parent(win):position(8, 8):image(up, down)
btn:on("Pressed", refresh)
```

> **A face is chosen while the control is being built** — like `:parent(w)`, and unlike every other setter
> here. The client draws a captioned button and a picture button with two *different* widgets, so choosing
> pictures chooses which widget this is; once the control is on screen `:image` refuses, saying so. A
> caption is not a face: `:text(s)` rewrites one at any time.

The bare `:image()` reads the faces back as `{ up =, down =, hover = }`, exactly as you named them, and
`nil` on a control that shows no picture. `:type()` tells the two buttons apart — `"Button"` and
`"IButton"` — while `:role()` is `button` for both, so one selector still finds every button you built.

## Text entry

`hafen.ui():entry()` is a single-line text field. `:value(s)` is the one way to write its content — the
bare `:text()` still reads it, like on any other text-bearing widget, but `:text(s)` refuses to write it,
naming `:value(s)` instead.

```lua
local e = hafen.ui():entry():size(160, 20):value("gonzalo")
e:on("Changed",   function(s) hafen.log():write("now: " .. s) end)
e:on("Submitted", function(s) doSearch(s) end)

e:value()          --> "gonzalo"
```

`Changed` fires on every keystroke that changes the text; `Submitted` fires once, when Enter is pressed,
carrying the whole text — a programmatic `:value(v)` fires neither one. While it has focus, a keystroke
goes to the field only, never also to your character, a hotkey, or the chat line.

## Checkbox

`hafen.ui():check()` is a boolean toggle. `:value(v)` holds the tick and `Changed` fires when the user
changes it:

```lua
local c = hafen.ui():check():text("Show grid"):value(true)
c:on("Changed", function(on) hafen.store():get("cfg").grid = on end)

c:value()          --> true
```

Like a button, it shows text or it shows pictures:

```lua
hafen.ui():check():image(up, down, hoverUp, hoverDown)
```

A checkbox carries two persistent states, ticked and not, each with its own hover — more faces than a
button, not fewer: `up`/`down` are the two states at rest, `hoverUp`/`hoverDown` are each of those under
the cursor, all required, resolved through the same two doors a button's [face](#a-caption-or-a-picture)
is. Choosing pictures is building-only here too, and the bare `:image()` reads them back as `{up=, down=,
hoverUp=, hoverDown=}`. `:type()` reads `"CheckBox"` or `"ICheckBox"` depending which you built.

## Radio

`hafen.ui():radio()` is a set of buttons where exactly one is checked at a time — one control, not one
object per button. `:rows{...}` names the choices, `:value(v)` reads and writes which one is checked, and
`Changed` fires when the user picks a different one:

```lua
local r = hafen.ui():radio():rows{"Quality", "Amount", "Name"}:value("Amount")
r:on("Changed", function(pick) sortBy(pick) end)

r:value()          --> "Amount"
```

The rows are laid out in a single column below the control's own `:position`, each one under the last;
`:size()` reads the box of the whole stack, not one row. Writing `:rows{...}` again replaces the whole set —
the row that was checked does not carry over, and an empty `:rows{}` is a control with nothing in it rather
than an error. `:value(v)` naming a row that is not in the current set is refused, naming the rows that are.

## Slider

`hafen.ui():slider()` is a draggable position within a range. `:range(min, max)` sets the bounds,
`:value(n)` reads and writes the position within them, and `Changed` fires while the user drags it:

```lua
local s = hafen.ui():slider():size(140, 20):range(0, 100):value(50)
s:on("Changed", function(ev)
  preview(ev:value())
  if ev:final() then save(ev:value()) end
end)
```

`Changed`'s `ev` says **two** things here — `:value()` and `:final()` — where every other control's says
one, which is why this is the one `Changed` that hands over an event object rather than a bare value.
`:final()` is `false` on every step while the thumb is being dragged and `true` exactly once, when the
mouse is released, which is the moment to act on the value rather than merely preview it.

A `:value(v)` outside `:range` **clamps** to the nearer bound rather than refusing; narrowing `:range(min,
max)` later re-clamps a value the new bounds no longer cover, silently — that write is not something the
user did, so it does not fire `Changed`. `:range(nil)` is refused like any other required argument, naming
the missing bound; there is no "undo" meaning for a control's own bounds the way `:position(nil)` undoes a
layer.

## Scroll

`hafen.ui():scroll()` is a scrolling container: give it a size, and anything `:parent()`'d into it lands in
the scrolling area, never beside it. A scrollbar appears down its right edge once the content no longer fits
and disappears once it fits again — a real control in its own right, one of `sp:children()`, answering the
same `:range()`/`:value(n)`/`Changed` as a bare [scrollbar](#scrollbar). For driving a scroll position
with nothing to contain, build that bare control instead.

## Scrollbar

`hafen.ui():scrollbar()` is a bare scroll thumb, for driving something yourself — the same `:range(min,
max)`/`:value(n)` as a [slider](#slider), minus the `final` flag:

```lua
local sb = hafen.ui():scrollbar():size(14, 160):range(0, #items - visibleRows):value(0)
sb:on("Changed", function(v) firstRow = v end)
```

`Changed` hands over the bare value here, `fn(v)` — a bare scrollbar has no separate "drag ended" moment
to report, so every step just reports where it is now.

## See also

- [controls](README.md) — the shared model: `:parent`, `:position`, permissions, owned vs borrowed
- [display](display.md) — the controls with nothing to click
- [widget](../widget.md) — everything a control answers before it adds anything of its own
- [style](../style/README.md) — the rules that dress it
