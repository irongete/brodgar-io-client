# hafen.ui: display controls

A label, a picture, a separator and a progress bar — the four [controls](README.md) with nothing to click.
Each is born bare and configured by chained setters, [the same shape](README.md#builders) every control has,
and dressed by the [stylesheet](../style/README.md) like any other.

## Label

`hafen.ui():label()` is a line of text, dressed by the [stylesheet](../style/README.md) like any other
control. `:text(s)` is its only content:

```lua
local l = hafen.ui():label():text("Stamina"):position(4, 4)
l:text(("%d%%"):format(n))     -- writing new text RESIZES the label to fit it
```

The box is exactly the rendered text, so writing a new caption changes `:size()` — a label placed against
the right edge of something else needs re-positioning after a write that changes its length. A label holds
text only: `:image(...)` refuses on one, naming the [button](interactive.md#a-caption-or-a-picture) or
[checkbox](interactive.md#checkbox) builder that takes a picture.

## Picture

`hafen.ui():image()` is a static picture with no interaction of its own. `:source(h)` gives it its content —
an [asset](../../asset.md) handle or a string naming one of the client's own resources, the same two doors a
button's [face](interactive.md#a-caption-or-a-picture) resolves:

```lua
hafen.ui():image():source(hafen.asset():get("logo.png")):position(0, 0)
```

Unlike a button's face, the picture is **not** chosen while the control is built: `:source(h)` may replace it
at any time, on screen or not. The bare `:source()` reads back exactly what was named, and `nil` before the
first `:source(h)`.

[`:picture()`](../selectors.md#the-picture-is-a-different-read) is the other half, and it asks a different
question: not what you named, but what the control is **showing**. On a client resource the two agree; on an
asset handle of your own it is `nil`, because the name it answers is a client resource name or nothing.

## Separator

`hafen.ui():separator()` is a plain horizontal rule, with no setter of its own — `:size(w, h)` is all there
is to it:

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

## See also

- [controls](README.md) — the shared model: `:parent`, `:position`, permissions, owned vs borrowed
- [interactive](interactive.md) — the controls that take a click or a drag
- [widget](../widget.md) — everything a control answers before it adds anything of its own
- [style](../style/README.md) — the rules that dress it
