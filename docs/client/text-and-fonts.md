# Text rendering and fonts

> Foundries, the named surfaces that bake them, and the custom-font paths.

| What | Where |
|---|---|
| Foundry (font+size+colour+aa) | `Text.Foundry`; `renderwrap` builds a `RichText.Foundry` |
| **Global default** | `Text.std` = `new Foundry(sans,10)` (`public static final`); `Text.render(…)` statics; `Label` default |
| Built-in fonts | `Text.sans/serif/mono/fraktur` |
| Window titles | `Window.DefaultDeco.cf/ncf` = `new Text.Foundry(Text.fraktur,15).aa(true)` |
| **Speech bubbles** | `Speaking` — cached `Text` (ctor + `update`), frame measured from `text.sz()` in `draw`; stock = `Text.std`. **`draw` blits the finished raster under `g.chcolor(Color.BLACK)`**, so any colour baked into it — the `Foundry.fixcol` a rule sets, or the `Color` passed to `render` — is multiplied to black on the way to the screen. A colour cannot reach this surface without moving that `chcolor` |
| **Floating kin names** | **NOT in the fork** — published code in the `ui/obj/buddy` resource (`haven.KinInfo` is gone; `OCache.OD_BUDDY` commented `-- Removed`). Adopted with `get-code` → `src/haven/res/ui/obj/buddy/{Buddy,Info,InfoPart}.java`: shared foundry `InfoPart.fnd` + `rendertext`, composed `Tex` invalidated by `Info.dirty()` |
| **Per-run markup** (`$font`) | `RichText` `$font` tag — resolves by **AWT family name** (`TextAttribute.FAMILY`); a custom TTF needs `GraphicsEnvironment.registerFont` at load |
| DPI sizing | `UI.scale(float)` — every produced size passes through it |
| ~81 baked `Foundry` sites | across 36 files (`Label`, `ChatUI`, `SListMenu`, `CharWnd`, `Button`, `FlowerMenu`, …) — route **per slice**, never all at once |
| Custom-TTF load | `Font.createFont(TRUETYPE_FONT, file)` (built-ins from `Text.*`); `Resource.Font` is the resource-backed path |
| **`Text` → GPU** | `Text.tex()` lazily wraps the `BufferedImage` in a `TexI` and **memoises it**; `Text.dispose()` forwards to it. A `Text` is therefore two allocations: the AWT raster (kept) and the texture |
| **The immediate-mode blit** | `GOut.atext` = `Text.render` → `tex()` → `aimage` → `dispose()`, **all four per call, every frame**; `GOut.text` is `atext(…,0,0)`. Drop the `dispose()` and you have the `Label` pattern |
| **`TexI` GL side** | `st()` uploads **lazily on first render** (so building a `TexI` is free of GL); `dispose()` drops the `ColorTex` and nulls it — but `st()` would silently **re-upload**, so a double-free shows up as a slow leak, never a crash. Size is `Tex.nextp2`-rounded (`tdim`): bytes = `4·nextp2(w)·nextp2(h)`, not `4·w·h` |
| **The scope vocabulary** | `Fonts.SCOPES` — the 13 names, `default` being the selector `*`'s twin; `Fonts.isScope` is what makes a stylesheet key a **site** key. Five (`button`, `label`, `textentry`, `chat`, `menu`) are also `Selector.WIDGET_ROLES`; seven name a render site nothing is ever classified as (`Selector.SITE_ROLES`); and the widget roles `window`/`inventory` have **no** scope, so a bare `window` key is a *tree* key |
| **Where COLOUR enters a render** | Three different layers — see the gotcha below. `Text.Foundry.defcol` (the default), the `Color` argument of `render(text, c)`/`renderwrap` (**baked into the raster** by `g.setColor(c)`), and a per-render `TextAttribute.FOREGROUND` extra on a `RichText.Foundry.render(…)` call |

## Gotchas

- A foundry is captured at construction: a site that caches its `Text` must be told to rebuild
  (generation counter) — changing the provider alone does nothing.
- **`Fonts.gen()` is not frame-global.** While a per-widget frame is open (`Widget.draw`'s child loop
  opens one around any widget a style resolves for) it XORs in that style's `Spec.stamp`, so it differs
  *between draw sites within one frame*. Fine for the `gen != mygen` compare each site does on its own
  `Text`; **wrong as a global "clear everything" trigger** for a cache shared by several sites, which
  would then clear on every alternation. Use it as a key component there. The frame opens for any widget
  a **stylesheet tree rule** matches, not only for one an addon named by hand — so assume any widget may
  be drawing under a stamped generation.
- Some text surfaces live in published `.res` code, not in the fork — check before assuming a
  class exists (see the kin-names row).
- **A foundry's `defcol` is almost never what you see**: nearly every site passes its colour *per
  render* — `Label` its `col`, tooltips `Text.white`, `ChatUI` the speaker's as a `FOREGROUND` extra
  (`SimpleMessage.render`, `MultiChat.Rendered.get`).
  And it is baked into the AWT raster: a rendered `Text` cannot be re-tinted, only a *white* glyph
  tex can (the `GOut` draw colour — the `g:text` path).
- **`$col[…]` markup vs a `FOREGROUND` extra**: both land as a foreground attribute on a run, but the
  first comes from the *string* and the second from the *call site* — opposite precedence against an
  override. Check which one you are looking at.
