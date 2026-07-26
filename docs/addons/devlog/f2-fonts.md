# F2 — Own-widget fonts + draw wrapper + `$font` custom families (`hafen.font.*`)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL),
> `ant bin` packages, **LuaJ parse of all addons** (hello/planner/gizmo/bags/walker/hogtest/netdemo/widgetstack).
> The text-render path is a **headless resource skip** — building a `Text.Foundry`/`RichText.Foundry` triggers
> `Text.<clinit>` → `Resource.local().loadwait("ui/fraktur")`, which is absent headless (the same skip A8/A10/R2a
> hit), so the draw path is verified **in-game**, not by a headless render. **In-game verified ✅.** *(Java
> engine change ⇒ `ant` rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F2, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md).

F2 lets an addon apply a loaded [`FontHandle`](f1-fonts.md) to **its own drawing** — a default font for a
custom window/widget, a per-call font/colour on `g:text`, and per-run font mixing via the engine's `$font` tag.
This is the **isolated, conflict-free** half of the font system (spec 21 "Apply to YOUR OWN drawing"): it
touches only the addon's own pixels, so there is **no global state, no override stack, and nothing to revert**
on teardown (unlike F1's `setFont` scopes).

## The API (shipped this slice)

```lua
-- default font for a whole custom window/widget (every g:text it draws):
hafen.ui.window{ ..., font = h }
hafen.ui.widget{ ..., font = h }

-- per-call font + colour on the draw wrapper (positional coords, as everywhere):
g:text(str, x, y [, { font = h, color = {r,g,b[,a]} }])
g:atext(str, x, y, ax, ay [, { font = h, color = {r,g,b[,a]} }])

-- mix TWO fonts on ONE line via the existing rich-text tag (custom family from a loaded TTF):
g:text(("$font[%s,16]{Fancy} normal"):format(h:family()), x, y)   -- $col/$b/$i/$u/$size work too
```

## Signature decision — positional coords kept (one canonical way)

The spec sketch/api-reference wrote `g:text(str, pos, opts)` with `pos` as a `{x,y}` table. But the **shipped**
`g:text`/`g:atext` (2a, and every one of ~30 call sites across `hello`/`planner`/`gizmo`/`bags`/`widgetstack`)
is **positional** `g:text(str, x, y)`, and spec 07 documents it positionally. Changing to a `pos` table would
break every existing addon for no benefit. So F2 keeps the positional coords and adds an **optional trailing
`opts` table** — `g:text(str, x, y [, opts])` — purely additive, zero breakage. The (local) `api-reference.md`
line was corrected to match. This is still "one canonical way" ([D-012](../../../specs/addons/decisions.md)):
one way to draw text, coords positional as everywhere, opts optional decoration.

## How text is drawn now (the mechanism)

Before F2, `g:text` → `GOut.text` → `Text.render(default provider)` — a plain single-line `Text.Line`, no
markup, stock font (following F1's `"default"` override). F2 keeps that as the **fast path** and adds a
**rich path** only when it is actually needed:

```
resolve font  = opts.font  ->  the widget's font= default  ->  stock (null)
resolve colour = opts.color -> the handle's load-time colour -> null (white, tinted by g:color)
markup = (str contains '$')

if font == null AND !markup:   FAST PATH — exact stock GOut.atext (optionally tinted)   ← existing addons unchanged
else:                          RICH PATH — RichText.Foundry.render(str, 0), blit, dispose
```

- **Fast path is byte-for-byte the old behaviour** for any addon that draws plain text with no `font=`/opts —
  confirmed no shipped addon draws a `$`/`{`/`}`/`\` (a `grep` over `addons/`), so nothing regresses.
- **Rich path uses `RichText.Foundry`** (not a plain `Text.Foundry`) so the engine's existing
  `$font[family,sz]{…}` tag — and `$col`/`$b`/`$i`/`$u`/`$size` — all work in an addon's own text. A loaded
  TTF's family is already AWT-registered by `hafen.font.load` (F1), so `$font[h:family(), …]` resolves with
  **zero `RichText` edit** (spec 21's key insight). `render(str, 0)` = single line, no wrapping (`$`-embedded
  `\n` would create lines — a harmless bonus).
- **Colour = a blit-time tint, not a foundry property.** Glyphs are always rasterised **white**; a per-call
  `color` (or the handle's load-time colour) is applied as a temporary `GOut` draw colour around the blit
  (saved via `getcolor()`, restored after). This composes with a bare `g:color(...)` exactly like the stock
  text path, and keeps foundries **colour-agnostic** so they cache well. `$col` runs render their own colour
  and are then subject to the current draw colour, same as the engine.
- **Never throws.** Malformed markup (`f.render` throwing on a stray `$`/`{`) falls back to drawing the literal
  string via the fast path — the forgiving `g`-wrapper contract (a bad arg draws garbage, never crashes the
  render thread).

### Caching (per-frame cost)

`g:text` runs every frame, so foundries must not be rebuilt per call. A `RichText.Foundry` allocates a
`Graphics2D`/`FontRenderContext` at construction, so:

- **`FontHandle.rich(stockPx)`** builds and **caches** a `RichText.Foundry` keyed by effective px (the handle's
  own `size` UI-scaled, else the caller's stock px) — an immutable handle → a stable, tiny cache (usually one
  entry). Glyphs white, `aa` per the handle (default off = `Text.std`).
- **`LuaGOut.stockRich()`** is a lazy static `RichText.Foundry` over `Text.std`'s (already-`UI.scale`d) font —
  used only for `$`-markup drawn with **no** font handle (so `$font` still works bare). `Text.std` never
  changes → no invalidation needed.

The rendered `Text` still rasterises a fresh `TexI` per call and `dispose()`s it after the blit — the same
lifecycle `GOut.atext` already had (text was never cached at this layer).

## Own-widget `font=` (piece a)

[`LuaWidget`](../../../src/io/brodgar/addon/LuaWidget.java) resolves `opts.get("font")` to a `FontHandle` at
construction (`null` if absent/typo/non-handle) and passes it to the shared draw wrapper via a new
`LuaGOut.bind(GOut, FontHandle)` overload; the old `bind(GOut)` (HUD/gob overlays, gizmo) delegates with a
`null` default. So a `hafen.ui.window{font=h}`'s every `g:text`/`g:atext` defaults to `h` unless a call
overrides it. `font=` does **not** restyle the window **title bar** — that is `DefaultDeco`'s `"window.title"`
scope (F3); F2's DoD ("the window renders in its own font") is met by the window **content**.

## Files touched — ZERO `haven` core edit

All in `io.brodgar.addon` (the render path reuses public `GOut`/`Text`/`RichText`/`UI` API):

- **[`FontHandle`](../../../src/io/brodgar/addon/FontHandle.java)** — new `rich(int stockPx)` (cached per-px
  `RichText.Foundry`, white glyphs) + a guarded `richCache`.
- **[`LuaGOut`](../../../src/io/brodgar/addon/LuaGOut.java)** — a default-font field + `bind(GOut, FontHandle)`
  overload (+ `unbind` clears it), a lazy static `stockRich()`, and `drawText`/`blitText` (the fast/rich split +
  colour tint); `g:text`/`g:atext` now forward to `drawText`.
- **[`LuaWidget`](../../../src/io/brodgar/addon/LuaWidget.java)** — resolve `opts.font` → `defaultFont`, pass it
  on `bind`.

## Threading

Unchanged from 2a: `g:text`/`g:atext` run inside the widget/overlay `draw(GOut)` on the render thread, through
`callLua` (watchdog + isolation + CPU accounting). `FontHandle.rich`'s cache is `synchronized(this)`;
`stockRich()` is `synchronized`. No new locks on the hot fast path.

## `hello` harness (v0.48.0)

`OnLoad` now also loads a second handle `monoFont = hafen.font.load("mono", {size=12})`. `OnEnterWorld` opens a
new **"Hello F2 (fonts)"** window with `font = demoFont` (the F1-loaded serif/TTF), whose `onDraw` shows the
four F2 behaviours on one panel: (1) a line in the window's `font=` default; (2) **two fonts on one line** via
`$font[demoFont:family(),16]{Fancy} + $font[SansSerif,12]{plain}` — the DoD; (3) a per-call
`g:text(..., {font=monoFont, color={…}})` override; (4) `$col`/`$b` rich tags. The window is bridge-owned
(auto-destroyed on `:reload`/disable) and recreated on the next `OnEnterWorld`, so disabling the addon leaves
the stock UI untouched (the isolation DoD). The F1 `:hello font` (global `"default"` override, last-wins) is
unchanged.

## In-game DoD (to verify)

1. The "Hello F2 (fonts)" window renders its text in the addon's font (not the stock font).
2. One line clearly shows **two different fonts** (the `$font` mix).
3. The per-call mono line is a different font + coloured; `$col`/`$b` line shows colour + bold.
4. Disabling `hello` (or `:reload`) removes the window and leaves all other UI's fonts untouched — no leak.

## Deferred (later slices)

- **F3** — the UI-chrome scopes (`window.title` — the addon window's *title bar*, `button`, `label`, `tooltip`,
  `menu`, `chat`, `textentry`).
- **F4** — the world scopes (`world.nick`, `world.speech`).
- **F5** — per-instance override on a `WidgetNode` (top of the resolution chain).
- Native `Label` **children** of an addon widget honouring its `font=` (F2 covers the `g` draw wrapper; a
  LuaWidget is a leaf with no native Labels today — that folds into F3/F5).
