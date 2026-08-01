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
| **`Text` → GPU** | [`Text.tex()`](src/haven/Text.java:400) lazily wraps the `BufferedImage` in a `TexI` and **memoises it**; [`Text.dispose()`](src/haven/Text.java:406) forwards to it. A `Text` is therefore two allocations: the AWT raster (kept) and the texture |
| **The immediate-mode blit** | [`GOut.atext`](src/haven/GOut.java:212) = `Text.render` → `tex()` → `aimage` → `dispose()`, **all four per call, every frame**; `GOut.text` is `atext(…,0,0)`. Drop the `dispose()` and you have the `Label` pattern |
| **`TexI` GL side** | [`st()`](src/haven/TexI.java:59) uploads **lazily on first render** (so building a `TexI` is free of GL); [`dispose()`](src/haven/TexI.java:117) drops the `ColorTex` and nulls it — but `st()` would silently **re-upload**, so a double-free shows up as a slow leak, never a crash. Size is `Tex.nextp2`-rounded ([`tdim`](src/haven/TexI.java:44)): bytes = `4·nextp2(w)·nextp2(h)`, not `4·w·h` |

## Gotchas

- A foundry is captured at construction: a site that caches its `Text` must be told to rebuild
  (generation counter) — changing the provider alone does nothing.
- **`Fonts.gen()` is not frame-global.** While a per-instance frame is open (F5, `node:setFont` —
  `Widget.draw`'s child loop) it XORs in that override's `Spec.stamp`, so it differs *between draw
  sites within one frame*. Fine for the `gen != mygen` compare each site does on its own `Text`;
  **wrong as a global "clear everything" trigger** for a cache shared by several sites, which would
  then clear on every alternation. Use it as a key component there (026.1).
- Some text surfaces live in published `.res` code, not in the fork — check before assuming a
  class exists (see the kin-names row).
