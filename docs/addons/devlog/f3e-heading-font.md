# F3e — The `"heading"` font scope (in-window section headings)

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) +
> `ant bin`, **36 headless provider-resolution checks** (the 26 from [F3c](f3c-textentry-label-fonts.md) plus 10 for
> the new scope: it is in the enum and starts on the stock fast path; the `"default"` cascade reaches it while each
> heading keeps **its own** stock size — 25 px window headings vs the 18 px credo group captions; a `"heading"`
> override refines over the cascade, keeps the site's stock size and leaves `"label"` untouched; `reset` falls back
> to the cascade; teardown restores the stock foundry for **both** heading stocks) + LuaJ parse of all 7 addons.
> The furnace/`Img` render path is a **headless resource skip** (`CharWnd` needs `Window.ctex` +
> `gfx/hud/fontred`, the usual A8/A10/R2a/F2/F3a/F3b/F3c skip) → verified **in-game**. **In-game verified ✅.**
> *(Java engine change ⇒ `ant` rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F3, decision
> **[D-043](../../../specs/addons/decisions.md)** — this slice **amends** it with a new scope (see below). Builds on
> [f1-fonts](f1-fonts.md), [f3a-window-title-font](f3a-window-title-font.md) (the furnace pattern),
> [f3b-button-font](f3b-button-font.md) (the recorded-recipe pattern) and
> [f3c-textentry-label-fonts](f3c-textentry-label-fonts.md).

F3c routed the character sheet's **body text** (`"label"`), and the maintainer immediately spotted what it did
*not* touch: the big embossed fraktur captions **inside** the windows — "Base Attributes", "Food Satiations",
"Abilities", "Study Report", "Lore & Skills", "Quest Log", "Health & Wounds", "Martial Arts & Combat Schools",
"Kin", the credo group captions, a village name. They are a third kind of surface — neither a window title
(`"window.title"`) nor body text (`"label"`) — so they get **their own scope**: **`"heading"`**.

## Decision: a new scope, not a reuse (D-043 amendment)

The scope enum was declared in full at F1 and `"heading"` was not in it. The alternatives were folding these
headings into `"label"` (which would chain a 25 px embossed display font to the 18 px body text — you could never
restyle one without the other) or into `"window.title"` (same fraktur family, but it would tie section headings to
the window chrome). The maintainer chose the **new enum member**, so `hafen.font.scopes()` now returns 11 names
and `"heading"` is refinable on its own while `setFont("default", h)` still reaches it through the cascade.

## The API

```lua
hafen.font.setFont("heading", h)   -- in-window section headings only
hafen.font.reset("heading")        -- drop it (also automatic on :reload/disable)
```

## What changed (10 files, every edit tagged `// addon:`)

### [`Fonts.java`](../../../src/haven/Fonts.java) — the enum

One line: `"heading"` joins `SCOPES`, between `"window.title"` and `"button"`.

### [`CharWnd.java`](../../../src/haven/CharWnd.java) — the provider-resolved furnace pair + a restylable `Heading`

Headings are **`Text.Furnace`s**, not foundries: `BlurFurn(TexFurn(Foundry(fraktur, 25), Window.ctex), …)` — an
embossed blur over a texture. So this is F3a's furnace pattern, with the inner foundry extracted so the provider
has a stock to fall back to:

```java
public static final Text.Foundry capfnd = new Text.Foundry(Text.fraktur, 25).aa(true);   // the extracted stock
public static final Text.Furnace catf  = new BlurFurn(new TexFurn(capfnd, Window.ctex), …);   // unchanged recipe
public static final Text.Furnace failf = new BlurFurn(new TexFurn(capfnd, failtex),     …);   // (quest failed)

private static void checkcapfont() {                    // rebuild both when Fonts.gen() moves
    Text.Foundry f = Fonts.foundry("heading", capfnd);
    bcatf  = (f == capfnd) ? catf  : new BlurFurn(new TexFurn(f, Window.ctex), …);   // stock-identity fast path
    bfailf = (f == capfnd) ? failf : new BlurFurn(new TexFurn(f, failtex),     …);
}
public static Text.Furnace catfont()  {checkcapfont(); return(bcatf);}
public static Text.Furnace failfont() {checkcapfont(); return(bfailf);}
```

`catf`/`failf` stay stock and public (other code may use them), and the `gfx/hud/fontred` image is now loaded once
into a field instead of inline (it would otherwise reload on every rebuild).

The second half is the F3b problem in a different costume. Stock baked a heading straight into an image and
dropped the string:

```java
add(CharWnd.settip(new Img(catf.render("Base Attributes").tex()), "gfx/hud/chr/tips/base"), Coord.z);
```

So a new **`CharWnd.Heading extends Img`** remembers its text plus a `Supplier<Text.Furnace>` (which furnace it
draws with), and re-renders its `Tex` in `draw` when `Fonts.gen()` moved — disposing the previous one, and its own
on `dispose()` (stock never disposed these at all). `CharWnd.heading(text)` is the factory for the 25 px variant;
`new CharWnd.Heading(text, GridList::dcatfont)` covers the smaller group captions.

### [`GridList.java`](../../../src/haven/GridList.java) — the 18 px group headings

Same shape at a different size: `dcatfnd` extracted as the stock, `dcatfont()` rebuilds `BlurFurn(TexFurn(…), 2, 1,
…)` on a `gen` move. Note both heading stocks live under **one** scope — a single `setFont("heading", h)` fronts
them and each keeps its own size, courtesy of the provider's per-stock foundry cache. `Group.rname()` re-renders
its caption when the generation moves; a `GridList` constructed with an *explicit* furnace keeps it (the same rule
as an explicit-foundry `Label`).

### The 16 heading sites

`new Img(<furnace>.render("X").tex())` → `CharWnd.heading("X")` in
[`BAttrWnd`](../../../src/haven/BAttrWnd.java) (×4), [`SAttrWnd`](../../../src/haven/SAttrWnd.java) (×2),
[`SkillWnd`](../../../src/haven/SkillWnd.java) (×2 + the 3 credo group captions via `GridList::dcatfont`),
[`FightWnd`](../../../src/haven/FightWnd.java), [`WoundWnd`](../../../src/haven/WoundWnd.java),
[`QuestWnd`](../../../src/haven/QuestWnd.java), [`BuddyWnd`](../../../src/haven/BuddyWnd.java) and
[`GameUI`](../../../src/haven/GameUI.java) (the village-name cap). In `QuestWnd` the quest-completed **popup** also
rendered its banner from two `private static final Tex` baked at class-init — those could never follow an override,
so they are now rendered per popup through `catfont()`/`failfont()` (the popup is transient; nothing to
invalidate).

## Verification

- **Clean compile** (`rm -rf build/classes && ant hafen-client`) → BUILD SUCCESSFUL, then `ant bin` → BUILD
  SUCCESSFUL. A grep confirms **no** `catf.render` / `failf.render` / `dcatf.render` call sites remain outside the
  provider path (only doc comments).
- **36 headless provider checks** (the F3c suite + 10 new, listed in the status block above) → 36 ok, 0 failed.
- **LuaJ parse** of all 7 addons → ok.
- **In-game (the DoD):** with the character sheet **open**, `:hello heading` → "Base Attributes" / "Food
  Satiations" / "Abilities" / "Study Report" change font while window titles, buttons, body text and list rows stay
  stock; the credo captions in Lore & Skills change at their own smaller size; toggling again restores stock, as
  does `:reload`. `:hello font` alone restyles headings too, via the cascade; `:hello heading` on top refines them.

## Harness (`hello` v0.52.0)

New `:hello heading` sub-command, independent of `:hello title` / `:hello button` / `:hello entry` /
`:hello label` — five scopes now togglable in one login, which together exercise the whole resolution chain
(scope override → `"default"` cascade → stock). It passes **no** size, so each heading keeps its own (25 px window
headings, 18 px credo captions) and nothing around them moves.
