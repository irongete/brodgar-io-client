# Subsystem: text rendering & fonts

> Foundries, the named surfaces that bake them, and the custom-font paths. Line numbers are
> indicative; the **class + field/method name is the stable anchor**. Max 40 lines.

| What | Where |
|---|---|
| Foundry (font+size+colour+aa) | [`Text.Foundry`](src/haven/Text.java:127); `renderwrap` builds a `RichText.Foundry` |
| **Global default** | [`Text.std`](src/haven/Text.java:50) = `new Foundry(sans,10)` (`public static final`); [`Text.render(…)`](src/haven/Text.java:359) statics; [`Label`](src/haven/Label.java:62) default |
| Built-in fonts | [`Text.sans/serif/mono/fraktur`](src/haven/Text.java:37) |
| Window titles | [`Window.DefaultDeco.cf/ncf`](src/haven/Window.java:178) = `new Text.Foundry(Text.fraktur,15).aa(true)` |
| **Speech bubbles** | [`Speaking`](src/haven/Speaking.java:32) — cached `Text` (ctor + `update`), frame measured from `text.sz()` in `draw`; stock = `Text.std` |
| **Floating kin names** | **NOT in the fork** — published code in the `ui/obj/buddy` resource (`haven.KinInfo` is gone; `OCache.OD_BUDDY` commented `-- Removed`). Adopted with `get-code` → `src/haven/res/ui/obj/buddy/{Buddy,Info,InfoPart}.java`: shared foundry `InfoPart.fnd` + `rendertext`, composed `Tex` invalidated by `Info.dirty()` |
| **Per-run markup** (`$font`) | [`RichText` `$font` tag](src/haven/RichText.java:566) — resolves by **AWT family name** (`TextAttribute.FAMILY`); a custom TTF needs `GraphicsEnvironment.registerFont` at load |
| DPI sizing | [`UI.scale(float)`](src/haven/UI.java:982) — every produced size passes through it |
| ~81 baked `Foundry` sites | across 36 files (`Label`, `ChatUI`, `SListMenu`, `CharWnd`, `Button`, `FlowerMenu`, …) — route **per slice**, never all at once |
| Custom-TTF load | `Font.createFont(TRUETYPE_FONT, file)` (built-ins from `Text.*`); [`Resource.Font`](src/haven/Resource.java) is the resource-backed path |

## Gotchas

- A foundry is captured at construction: a site that caches its `Text` must be told to rebuild
  (generation counter) — changing the provider alone does nothing.
- Some text surfaces live in published `.res` code, not in the fork — check before assuming a
  class exists (see the kin-names row).
