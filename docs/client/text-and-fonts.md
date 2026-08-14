# Text rendering and fonts

> Foundries, the named surfaces that bake them, and the custom-font paths.

| What | Where |
|---|---|
| Foundry (font+size+colour+aa) | `Text.Foundry`; `renderwrap` builds a `RichText.Foundry` |
| **Global default** | `Text.std` = `new Foundry(sans,10)` (`public static final`); `Text.render(…)` statics; `Label` default |
| Built-in fonts | `Text.sans/serif/mono/fraktur`. **Only two of the four name an AWT logical family.** `serif` and `mono` ask for `"Serif"`/`"Monospaced"`, which exist; `sans` asks for `"Sans"`, which does not, so it resolves to `Dialog` and `getFamily()` on it is indistinguishable from a `Dialog` font's. `getName()` keeps the string the `Font` was constructed with and **survives every `deriveFont`**, so it, not the family, is what tells the four apart in a font some site has already derived. `fraktur` is a resource font and answers its own family |
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
| **The scope vocabulary** | `Fonts.SCOPES` — the names a stylesheet key may be a **site** key for, `default` being the selector `*`'s twin; `Fonts.isScope` is the test. Some (`button`, `label`, `textentry`, `chat`, `menu`) are also `Selector.WIDGET_ROLES`; the rest name a render site nothing is ever classified as (`Selector.SITE_ROLES`); and the widget roles `window`/`inventory` have **no** scope, so a bare `window` key is a *tree* key |
| **Where COLOUR enters a render** | Three different layers — see the gotcha below. `Text.Foundry.defcol` (the default), the `Color` argument of `render(text, c)`/`renderwrap` (**baked into the raster** by `g.setColor(c)`), and a per-render `TextAttribute.FOREGROUND` extra on a `RichText.Foundry.render(…)` call |

## The furnace stack

A `Text.Furnace` is "render this string"; `Text.Forge` narrows it to one that also answers `height()`,
`strsize()` and a `Slug`. `Text.Foundry` is the only leaf that owns a `Font`; everything else is a
**decorator** — `Text.OffsetForge` wraps a backing `Forge` and post-processes the raster, reporting the
room it took as `tloff()`/`broff()`.

| Decorator | Does | Costs |
|---|---|---|
| `PUtils.TexFurn(bk, BufferedImage)` | `PUtils.tilemod` — tiles the image through the glyph raster **in place**, so the mask is what you see and the foundry's colour is gone | no growth (`tloff`/`broff` are `Coord.z`) |
| `PUtils.BlurFurn(bk, grad, brad, Color)` | `PUtils.blurmask2` — a coloured halo behind the glyphs | grows the raster by `grad + brad` on every side, which is `tloff`/`broff` and moves what the site lays out around it |

The two are always stacked the same way — `BlurFurn(TexFurn(foundry, tex), …)` — at four places, and each
rebuilds its statics on a `Fonts.gen()` compare. The blur's `grad`/`brad`/`col` are per site and none of them
is shared:

| Site | Stock foundry | Texture | Blur |
|---|---|---|---|
| `Window.DefaultDeco.checktitlefont` → `cf`, `ncf` | `DefaultDeco.titlefnd` (fraktur 15) | `Window.ctex` = `Resource.loadsimg("gfx/hud/fonttex")` | `UI.rscale(0.75)`, `UI.rscale(1.0)`; `Color(96,96,0)` focused, `Color.BLACK` not |
| `CharWnd.checkcapfont` → `bcatf`, `bfailf` | `CharWnd.capfnd` (fraktur 25) | `Window.ctex`, and `gfx/hud/fontred` for the failed twin | `UI.scale(3)`, `UI.scale(2)`, `Color(96,48,0)` |
| `GridList.dcatfont` → `bdcatf` | `GridList.dcatfnd` (fraktur 18) | `Window.ctex` | `2`, `1`, `Color(96,48,0)` — **unscaled literals**, unlike the three above |
| `Button.checkfont` → `bnf` | `Button.tf` (bold serif 12) | `Window.ctex` | `UI.rscale(0.75)` twice, `Color(80,40,0)` |

`Charlist`, `Fightsess`, `MapView` and `QuestWnd` build furnaces of their own from the same two classes
and are **not** routed.

## The chat's colours, one per kind

There is no table of them anywhere: a chat line's colour is a literal at the place the line is **built**,
and they live in three classes. Nothing distinguishes the kinds at the render — every one of them is a
`SimpleMessage` or a `NamedMessage` carrying a `Color` — so the only way to tell a System line from a
private one is **which channel it was appended to**, or which `Message` subclass built it.

| Kind | Where the colour is | Value |
|---|---|---|
| System, informational | `UI.Notice.color()`'s default, via `GameUI.msg` → `syslog.append` | `Color.WHITE` |
| System, error | `UI.ErrorMessage.color` (`defcolor()` overrides `SimpleMessage`'s) | `(192, 0, 0)` |
| Your own line | `ChatUI.MultiChat.MyMessage` ctor | `(192, 192, 255)` |
| Private, received | `ChatUI.PrivChat.InMessage` ctor | `(255, 128, 128)` |
| Private, sent | `ChatUI.PrivChat.OutMessage` ctor | `(128, 128, 255)` |
| A speaker | `ChatUI.MultiChat.nextcol` — `Color.HSBtoRGB(colseq = (colseq + √2) % 1, 0.5f, 1.0f)`, memoised per sender id in `pc` | walked |
| A party speaker | `ChatUI.PartyChat.uimsg` — `Party.Member.col` blended with white | server-assigned |
| Unread urgency | `ChatUI.urgcols` (the toggle button's glow, `GameUI`) and `ChatUI.Selector.uc` (the tab, `namedeco`) | two arrays, both index-0-is-not-a-level |

⚠️ **The two urgency arrays are not the same array.** `urgcols[0]` is `null` — no glow — while `uc[0]` is
`(80, 40, 0)`, the resting tab colour. Levels 1–3 agree. Anything routing "the urgency colour" has to take
each site's own array as the fallback rather than assume one.

⚠️ **`MyMessage` is `MultiChat`'s, so the Party channel has it too** (`PartyChat extends MultiChat`): your
own party line is `(192, 192, 255)`, not a party colour. And `PrivChat`'s error path builds a bare
`SimpleMessage(err, Color.RED)` — a third red, unrelated to `ErrorMessage`'s.

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
- **`TexFurn` mutates the slug it is given** (`tilemod(text.img.getRaster(), …)`) and hands the same
  `BufferedImage` back. It is the last word on colour for anything it wraps: `Foundry.defcol`, a `Color`
  passed to `render`, and `Foundry.fixcol` alike are all overwritten. Removing it from the stack is the
  only way a colour survives to the screen on one of the four sites above.
- **`Text.Foundry` is both leaf and `Forge`**, so `new TexFurn(foundry, tex)` and
  `new TexFurn(otherFurn, tex)` compile identically — the `Text.Furnace` overloads of both decorators are
  `@Deprecated` and exist only for that ambiguity. Prefer the `Forge` one.
