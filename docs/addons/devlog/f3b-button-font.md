# F3b — The `"button"` font scope (button captions)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL),
> **15 headless provider-resolution checks** for the `"button"` path (no-override → stock fast path; the
> `"default"` cascade reaches the unset scope and keeps the site's stock size; the per-stock foundry cache;
> a `"button"` override refines over the cascade *without* disturbing `"window.title"`; last-wins across two
> owners; `reset` falls back to the owner beneath and is `false` when unset; `removeOwner` restores the stock
> fast path) + LuaJ parse of all 7 addons. The caption render itself is a **headless resource skip** — the
> `Button` class can't even load without its `gfx/hud/buttons/tbtn/*` images and `Window.ctex` (the same skip
> A8/A10/R2a/F2/F3a hit), so the on-screen wiring is verified **in-game**. **In-game verified ✅.** *(Java engine change ⇒ `ant` rebuild
> + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F3, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md) (the provider + `gen` +
> `"default"` scope) and [f3a-window-title-font](f3a-window-title-font.md) (the first chrome scope).

F3b routes the **second UI-chrome scope**: **`"button"`** — the caption drawn on every standard client button.
`setFont("button", h)` now restyles **only** button captions (window titles and body text stay stock), while
`setFont("default", h)` still restyles them **via the cascade** (until a `"button"` override refines them), and
everything reverts on `:reload`/disable (owned-resource model). Remaining F3 slices: F3c
(`label` + `textentry`), F3d (`menu` + `tooltip` + `chat`).

## The API (unchanged — this slice makes one more scope *effective*)

```lua
local h = hafen.font.load("serif", { size = 12, bold = true })
hafen.font.setFont("button", h)     -- button CAPTIONS restyle live (titles/body stay stock)
hafen.font.reset("button")          -- drop it (also automatic on :reload/disable)
-- cascade: with no "button" override, setFont("default", h) restyles button captions too
```

## Why buttons are a different render site than F3a's captions

A window caption is rendered by the *decoration* every frame from a cached `Text`. A `Button` is an
[`SIWidget`](../../../src/haven/SIWidget.java): its whole face is **rasterized once into a `BufferedImage`** and
uploaded as a `Tex`; `draw(GOut)` just blits that surface until something calls `redraw()`. Two consequences
shaped the slice:

1. **The caption is baked twice** — first into a `Text` (`text`/`cont`) at *construction* time, then into the
   button's rasterized surface. Restyling therefore needs *both* a re-render of the `Text` **and** a `redraw()`.
2. **The original string is not kept** by stock code — the ctor does `this.text = nf.render(text)` and the
   `String` is gone. Without it a button cannot re-render its own caption later, so F3b had to record it.

Stock also has **two** foundry-ish objects: the plain `Text.Foundry tf` (bold serif 12, used by
`change(text, col)` and `wrapped`) and `Text.Furnace nf` — a `BlurFurn(TexFurn(tf, Window.ctex), …)` giving the
embossed brown caption look (the default `change(text)`/ctor path). Both had to be re-derived from the provider.

## What changed (1 file, all edits tagged `// addon:`)

### [`Button.java`](../../../src/haven/Button.java) — provider-resolved foundry pair + per-button re-render

- `tf`/`nf` are **left exactly as they were** (the *stock* foundry/furnace). This matters: `Charlist.df`
  (`static final`, the character-selection list entries) derives from `Button.tf`, and a `static final` field
  can never be re-derived — leaving `tf` stock keeps that call site correct and unchanged instead of freezing an
  override into it at class-init time.
- New provider-resolved pair, rebuilt lazily on a `Fonts.gen()` move:

  ```java
  private static Text.Foundry btf;  private static Text.Furnace bnf;  private static int fontgen = -1;
  private static void checkfont() {
      int g = Fonts.gen();
      if((btf == null) || (fontgen != g)) {
          btf = Fonts.foundry("button", tf);
          bnf = (btf == tf) ? nf : new PUtils.BlurFurn(new PUtils.TexFurn(btf, Window.ctex), UI.rscale(0.75), UI.rscale(0.75), new Color(80, 40, 0));
          fontgen = g;
      }
  }
  static Text.Foundry tfont() {checkfont(); return(btf);}
  static Text.Furnace nfont() {checkfont(); return(bnf);}
  ```

  The `(btf == tf) ? nf : …` branch is the **stock identity fast path**: when no override resolves, the provider
  hands back the very same `tf` instance, so we reuse the stock furnace instead of allocating a byte-identical
  blur pipeline. With no addon override installed the rendering objects are *literally* the stock ones.
- Every caption the button renders **itself** now goes through `tfont()`/`nfont()`, and the recipe is recorded so
  it can be replayed:

  ```java
  private String rtext = null;   // null = the caller supplied a ready Text/BufferedImage — not ours to restyle
  private Color  rcol  = null;   // non-null -> the plain tfont() path (change(text, col))
  private int    rwrap = 0;      // >0 -> the renderwrap path (wrapped())
  private int    contgen = -1;   // Fonts.gen() at the last caption render

  private void render() {
      if(rwrap > 0)        this.text = tfont().renderwrap(rtext, rwrap);
      else if(rcol != null) this.text = tfont().render(rtext, rcol);
      else                  this.text = nfont().render(rtext);
      this.cont = this.text.img;
      this.contgen = Fonts.gen();
  }
  ```

  `Button(int w, String text, boolean lg, Runnable action)` (the funnel every String ctor reaches),
  `change(String)`, `change(String, Color)` and the static `wrapped(int, String)` all now set `rtext`/`rcol`/
  `rwrap` and call `render()`. `wrapped` previously pre-rendered with `tf` and went through the
  `Button(int, Text)` ctor; it now builds the button and renders **inside** it, so wrapped captions are
  restylable too (same geometry — `largep(w)` and the `w - margin` wrap width are unchanged).
- Live restyle, in the one place that is guaranteed to run for a *visible* button:

  ```java
  public void draw(GOut g) {
      if((rtext != null) && (contgen != Fonts.gen())) { render(); redraw(); }
      super.draw(g);
  }
  ```

  `redraw()` drops the cached `Tex`, so `SIWidget.draw` re-rasterizes the face with the new caption on the same
  frame. Buttons whose face came from the caller as a `Text` or `BufferedImage` (`Button(int, Text)`,
  `Button(int, BufferedImage)`, and `IButton`-style icon buttons, which aren't `Button`s at all) keep
  `rtext == null` and are skipped — the client owns those pixels, so F3b does not touch them.

**Core edits: 1 file (`Button.java`)**, no new class, no `io.brodgar` change. Every `Button` subclass in the tree
(`Tabs.TabButton`, `OptWnd.PButton`, `OptWnd.PointBind`, `KeyMatch.Capture`, `KeyMatch.ModCapture`) reaches the
String ctor and/or `change()`, so all of them are covered for free — including runtime caption changes like
`PointBind`'s `Click element...` and `Capture`'s key names.

## Threading / cost

Identical to F3a: the static `btf`/`bnf`/`fontgen` and the per-instance `contgen` are read and written only from
the render thread during draw, so buttons never race each other; `Fonts.gen()` is a `volatile` int read. With no
override installed the per-frame cost of a button is one `volatile` read plus an `int` compare (`contgen ==
gen`) — nothing is re-rendered and nothing is allocated. On a `setFont`/`reset`/teardown the *visible* buttons
re-render once each, on their next draw; off-screen ones re-render whenever they are next drawn (lazily, from
their recorded `rtext`), so a mass restyle never stalls a frame with the whole widget tree.

## Verification

- **Clean compile** (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL).
- **15 headless checks** on the `"button"` resolution path via `haven.Fonts` directly (no GL), using a
  foundry built exactly like stock `Button.tf`: no-override → stock identity (so `nfont()` reuses stock `nf`);
  `"button"` is a declared scope; `push` bumps `gen`; `"default"` cascades to the unset `"button"` and keeps the
  site's stock size while adopting the override family; the per-stock foundry cache returns the same instance;
  a `"button"` override refines over the cascade and does **not** disturb `"window.title"` (still on the
  cascade); last-wins across two owners; `reset` of the top owner falls back to the exact foundry beneath and is
  `false` for an owner with nothing installed; `removeOwner` restores the stock fast path on every scope.
- **LuaJ parse** of all 7 shipped addons (hello/planner/bags/walker/hogtest/netdemo/widgetstack).
- The rasterized caption itself is the documented **resource skip** → verified in-game.

## `hello` harness (v0.50.1)

Added **`:hello button`** — toggles `setFont("button", monoFont:derive{size = 12, bold = true})` /
`reset("button")`. **Harness gotcha found in the first in-game pass:** the stock button caption font *is* bold
serif 12, so overriding `"button"` with the serif `demoFont` installs a correct override that is **invisible**;
the demo now picks a visibly different family (mono) at the same size (captions stay inside the buttons). Open the Options window (or any window with buttons) while flipping it: the button captions
change, but window titles and body text stay stock (independence). Combined with `:hello title` and
`:hello font` the harness now proves the full resolution chain in one login: `"default"` alone cascades to
captions *and* buttons; adding `"window.title"` or `"button"` refines just that surface; resetting either drops
it back onto the cascade; `:reload`/disable reverts everything (the state flags reset in `OnLoad`, P2).

## Deferred (later F3 slices)

- **F3c** — `"textentry"` + `"label"` (plain `Text.Foundry` sites: `TextEntry.fnd` + `ReadLine`, and
  explicit-foundry `Label`s).
- **F3d** — `"menu"` + `"tooltip"` + `"chat"` (`FlowerMenu.ptf`/`MenuGrid`, the tooltip site, and `ChatUI.fnd`
  which is a **`RichText.Foundry`** → needs its own provider primitive). Completes F3.
- **`Charlist.df`** (character-selection entry captions) stays stock by design — see above.
