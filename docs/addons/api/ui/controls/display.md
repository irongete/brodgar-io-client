# hafen.ui: Display Controls

A label, a picture, a separator and a progress bar: the [controls](README.md) with nothing to click, each built bare, configured by chained setters and dressed by the [stylesheet](../style/README.md).

```lua
local stamina_label = hafen.ui():label():text("Stamina"):position(4, 4)
local stamina_bar = hafen.ui():progress():size(120, 20):position(4, 20):value(0.35)
hafen.ui():image():source("gfx/hud/chr/farming"):size(24, 24):position(130, 4)
hafen.ui():separator():size(180, 1):position(0, 44)
```

---

## Label

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():label()` | [`Widget`](../widget.md) | Unprotected | A line of text. |
| `label:text(caption)` | `self` | Unprotected | Its only content. Writing resizes the label to the rendered text, so `:size()` changes with the caption; a label placed against another widget's edge needs re-positioning after a longer write. |
| `label:image(...)` | — | — | Refused, naming the [button](interactive.md#a-caption-or-a-picture) and [checkbox](interactive.md#checkbox) builders, which take a picture. |

```lua
stamina_label:text(("%d%%"):format(percent))      -- resizes the label to fit
```

## Picture

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():image()` | `Widget` | Unprotected | A static picture with no interaction. |
| `picture:source(h)` | `self` | Unprotected | Its content: an [asset](../../asset/README.md) handle or a string naming a client resource, the two doors a button's [face](interactive.md#a-caption-or-a-picture) resolves. Not building-only: it may replace the picture at any time, on screen or not. |
| `picture:source()` | `userdata \| string \| nil` | Unprotected | What was named; `nil` before the first write. |
| `picture:size(w, h)` | `self` | Unprotected | Scales the picture into the box; a later `:source(h)` keeps that box. |
| `picture:size(nil)` | `self` | Unprotected | Gives the box back to the picture's own. |
| `picture:size(w)` | — | — | Refused: there is no art to answer for the height. |

| Rule | Detail |
|---|---|
| A resource name | Read when the picture is set: the control shows the resource's current `image` layer, a [written](../../resource/writes.md) one included, until `:source(h)` is written again. |
| [`:picture()`](../selectors.md#the-picture-is-a-different-read) | Answers what the control is showing, as a client resource name: it agrees with `:source()` on a client resource and reads `nil` on an asset handle of yours. |
| The `picture` rule | A [`picture`](../style/chrome.md#picture) rule naming the control fills the same box by its own `mode`; `["@Img"]` reaches yours and every client picture alike. Yours has `:source(h)` and needs no rule. |

## Separator

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():separator()` | `Widget` | Unprotected | A horizontal rule with no setter of its own; `:size(w, h)` shapes it. |

## Progress bar

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():progress()` | `Widget` | Unprotected | A fill-fraction bar. |
| `bar:value(fraction)` | `self` | Unprotected | The filled fraction, `0..1`. A value outside is refused, not clamped: a raw percentage passed by mistake fails instead of pinning at full. |
| `bar:value()` | `number` | Unprotected | The fraction. |

---

## See Also

- [Controls](README.md) — the shared model: `:parent`, `:position`, sizing, owned vs borrowed.
- [Interactive](interactive.md) — the controls that take a click or a drag.
- [Widget](../widget.md) — everything a control answers before it adds its own.
- [Style](../style/README.md) — the rules that dress it.
