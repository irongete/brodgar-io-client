# hafen.ui: A Mirror of Another Widget

A mirror is a surface of yours whose picture is another widget's, redrawn every frame: the widget stays where it is, and the mirror shows it wherever a surface of yours can stand — the addon layer included, over a character nobody is looking at.

```lua
local dock = hafen.ui():widget():size(60, 200):position(0, 200)
local row = 0
for _, session in ipairs(hafen.session():list()) do
  local portrait = session:ui():matchAll("@Avaview")[1]     -- the HUD portrait comes first in tree order
  if portrait then
    hafen.ui():mirror():source(portrait):parent(dock):position(6, 6 + row * 54):size(48, 48)
    row = row + 1
  end
end
```

---

## Building one

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():mirror()` | [`Widget`](widget.md) | Unprotected | A mirror showing nothing yet, 200x140 at (100, 100) in the addon layer. No arguments. |
| `mirror:source(w)` | `self` | Unprotected | The widget it shows: any [Widget](widget.md), the client's or an addon's, in any tree. Not building-only: it may name another widget at any time. |
| `mirror:source()` | `Widget \| nil` | Unprotected | The widget named, the same object (`==` holds). `nil` before the first write, and once that widget has left its tree. |
| `mirror:size(w, h)` | `self` | Unprotected | Scales the picture into the box. A later `:source(w)` keeps that box. A rule's [`size`](style/geometry.md#position-and-size) naming the mirror is inert. |
| `mirror:size(nil)` | `self` | Unprotected | Gives the box back to the source's own, whatever a rule says. |
| `mirror:size(w)` | — | — | Refused: there is no art to answer for the height. |

`:parent(w)`, `:position(x, y)`, `:visible(b)`, `:name(word)`, `:stock(t)`, `:tooltip(s)`, `:enabled(b)` and `:destroy()` answer as on any [surface of yours](custom.md). `:type()` reads `MirrorWidget`; a [selector](selectors.md) reaches it as `@MirrorWidget` or by its `[name=]`.

| Rule | Detail |
|---|---|
| The picture is the source's own draw | Its whole subtree, drawn again into a texture of the mirror's, every frame the mirror is shown. A [painter](overlay.md#over-one-widget) an addon hung on the source is in it; a source the client keeps but hides is drawn too. |
| A session nobody is looking at is drawn | The source is drawn in its own tree, with its own session behind it: a portrait shows that character as it stands, a minimap that character's ground, a meter its fill. |
| The source is untouched | Not moved, hidden, resized or re-parented: its `:parent()`, `:position()`, `:size()` and `:visible()` read the same before and after, and its clicks stay its own. There is nothing to restore. |
| A press is the mirror's | `MouseDown` and the other [universal keys](widget.md#subscribing) fire on the mirror; nothing reaches the source. [`hafen.ui():hit(x, y)`](selectors.md#hit-testing) over it answers the mirror. |
| The box | `:source(w)` sizes the mirror to the source's box unless `:size(w, h)` pinned one. Scaled at the blit: a 48 px mirror of a 74 px portrait samples the full picture. |
| Dressed like a surface | A [`:stock`](custom.md#naming-and-dressing-your-own-surfaces) or a rule naming it paints its `bg` under the picture and its `border` over it. |
| Hidden costs nothing | A mirror that is hidden, or stands under something hidden, is not drawn and its source is not drawn for it. |
| A source that goes | The mirror draws nothing and `:source()` reads `nil`; the mirror stands. Name another widget, or destroy it. |
| Ceiling | A source over 2048 device pixels on a side is refused naming the ceiling. Mirror a widget inside it. |
| Its own picture | The mirror itself, or a widget the mirror stands in, is refused as its source: a mirror inside its own picture. |
| Not a Widget | `:source("gfx/hud/…")` or an asset handle is refused naming what a mirror takes; a [picture control](controls/display.md#picture) is what shows art. |
| Lifetime | `:destroy()` frees the picture and leaves the source standing. `:reload` and disable take every mirror down the same way. Two mirrors of one source both draw. |

---

## See Also

- [Custom](custom.md) — the surfaces of yours a mirror stands beside, and the layer they stand in.
- [Native widgets](native.md#taking-one-into-a-surface-of-your-own-unprotected) — moving the widget itself into a surface of the same tree, clicks and all.
- [Widgets in the world](../virtual/widgets.md) — a widget standing in the 3D scene, still answering its own clicks.
- [Display controls](controls/display.md#picture) — a picture control, for art rather than a widget.
- [Selectors](selectors.md) — finding the widget to mirror in a character's tree.
