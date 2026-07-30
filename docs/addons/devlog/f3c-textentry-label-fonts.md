# F3c — The `"textentry"` + `"label"` font scopes

> **Status:** ✅ Implemented; clean compile (`rm -rf build/classes && ant hafen-client` → BUILD SUCCESSFUL) +
> `ant bin`, **26 headless provider-resolution checks** for the two new scopes (both are in the enum and start on
> the stock fast path; the `"default"` cascade reaches each unset scope while keeping **that site's** stock size
> *and* colour — a 12 px serif entry, a 14 px label, the wheat-coloured command line; the per-stock foundry cache;
> a `"textentry"` override refines only its own scope and leaves `"label"`/`"window.title"` alone, and vice versa;
> last-wins across two owners; `reset` falls back to the owner beneath and returns `false` when unset;
> `removeOwner` restores the stock fast path for every routed site), plus a **`Label` restyle check** (an
> explicit-foundry label goes fraktur 15 → Monospaced **15** on `setFont("label", …)` and back to the *identical*
> stock foundry object on teardown), + LuaJ parse of all 7 addons. The render sites themselves are a **headless
> resource skip** (`TextEntry`/`ConsoleHost`/`CharWnd`/`SListWidget` can't load without their `gfx/hud/*` images
> and a `Text` toolkit — the same skip A8/A10/R2a/F2/F3a/F3b hit), so the on-screen wiring is verified **in-game**.
> **In-game verified ✅.** *(Java engine change ⇒ `ant` rebuild + full client restart.)*
> **Design:** [specs/addons/21-fonts.md](../../../specs/addons/21-fonts.md) §F3, decision
> **[D-043](../../../specs/addons/decisions.md)**. Builds on [f1-fonts](f1-fonts.md) (the provider + `gen` +
> `"default"` scope + the default-`Label` routing), [f3a-window-title-font](f3a-window-title-font.md) and
> [f3b-button-font](f3b-button-font.md).

F3c routes the **third and fourth UI-chrome scopes** — the two "plain `Text.Foundry`" families the F3 split left:

- **`"textentry"`** — the client's editable text, at **both** of its render sites: every
  [`TextEntry`](../../../src/haven/TextEntry.java) field (chat input, search boxes, the login name/password
  fields, …) **and** the [`ConsoleHost`](../../../src/haven/ConsoleHost.java) command line (the `:` prompt — so
  `:lua` and every `hafen.slash` command is typed in the addon's font too). Both are
  [`ReadLine`](../../../src/haven/ReadLine.java)-backed, which is why the spec named them together.
- **`"label"`** — the client's **body text**, in two halves. (1) The **explicit-foundry** `Label`s: F1 routed the
  *default* labels (`new Label(text)`) through `"default"`, but a label constructed with its own hand-picked
  foundry (`new Label(text, myfnd)`) was deliberately left **fixed** (`fontscope == null`); F3c closes that hole —
  those labels now follow `"label"` with **their own foundry as the stock fallback**. (2) The shared
  **`CharWnd.attrf`** surface — see *"The `Label` class was not the surface"* below, which is where the visible
  body text actually lives.

`setFont("textentry", h)` / `setFont("label", h)` each refine only their surface; `setFont("default", h)` still
reaches both **via the cascade**; everything reverts on `:reload`/disable (owned-resource model). Remaining F3
slice: **F3d** (`menu` + `tooltip` + `chat`).

## The API (unchanged — this slice makes two more scopes *effective*)

```lua
local h = hafen.font.load("mono", { size = 12 })
hafen.font.setFont("textentry", h)   -- every text field + the console command line restyle live
hafen.font.setFont("label", h)       -- explicit-foundry labels swap FAMILY but keep their own sizes
hafen.font.reset("textentry")        -- drop it (also automatic on :reload/disable)
-- cascade: with neither set, setFont("default", h) restyles fields and labels too
```

## Why these sites were easy where F3b was hard

A [`Button`](../../../src/haven/Button.java) bakes its caption twice (a `Text` **and** a rasterized face) and
throws the `String` away, so F3b had to record a caption recipe. The F3c sites are the opposite: each keeps the
**live source of truth** for its text and caches only a `Text`/`Text.Line` that is *already* invalidated on every
edit. So the pattern is the minimal one the spec describes — resolve the foundry through the provider, and drop
the cached line when `Fonts.gen()` moved:

- `TextEntry` re-renders `tcache` from `buf.line()` whenever `redraw()` nulls it (every keystroke already does).
- `ConsoleHost.drawcmd` re-renders `cmdtext` whenever the line text differs (`cmdline.lneq`).
- `Label` keeps `texts` (the source string) + `col` + its wrap width, and F1 already added the `restyle()` hook
  called from `draw`.

## What changed — part 1: the two `ReadLine` surfaces (every edit tagged `// addon:`)

### [`TextEntry.java`](../../../src/haven/TextEntry.java) — provider-resolved foundry + per-field invalidation

`public static final Text.Foundry fnd` is **left untouched** (it is public API of the class and other code may
use it directly); the text a `TextEntry` renders *itself* goes through the provider:

```java
private static Text.Foundry efnd = null;
private static int fontgen = -1;
static Text.Foundry tfont() {
    int g = Fonts.gen();
    if((efnd == null) || (fontgen != g)) {
        efnd = Fonts.foundry("textentry", fnd);   // override, else stock `fnd`, cascading through "default"
        fontgen = g;
    }
    return(efnd);
}
private int tcgen = -1;    // Fonts.gen() at the last tcache render
```

and `draw(GOut)` gained the two-line invalidation + one substitution:

```java
if((this.tcache != null) && (tcgen != Fonts.gen()))
    redraw();                                    // the override moved -> drop the cached line
Text.Line tcache = this.tcache;
if(tcache == null) {
    this.tcache = tcache = tfont().render(dtext(), (dshow && dirty) ? dirtycol : defcol);   // was fnd.render(...)
    this.tcgen = Fonts.gen();
}
```

The resolved foundry is a **per-class** cache (one field for all entries) — resolution happens at most once per
`gen` move, and the per-instance cost is a single `int` compare per frame. `redraw()` disposes the old
`Text.Line`'s texture, so nothing leaks. Caret and selection maths already derive from `tcache.advance(...)`, so
they follow the new glyph advances for free; `LoginScreen.UserEntry` (the only `TextEntry` subclass) inherits it.

### [`ConsoleHost.java`](../../../src/haven/ConsoleHost.java) — the same pattern for the command line

`cmdfoundry` (stock **mono 12, wheat**) stays as-is; a `cmdfont()` twin of `tfont()` resolves
`Fonts.foundry("textentry", cmdfoundry)` and `drawcmd` re-renders on a `gen` move:

```java
if((cmdtext == null) || !cmdline.lneq(cmdtextf) || (cmdgen != Fonts.gen())) {
    cmdtext = cmdfont().render(":" + (cmdtextf = cmdline.line()));   // was cmdfoundry.render(...)
    cmdgen = Fonts.gen();
}
```

Note this is a **different stock** than `TextEntry`'s (mono + a wheat default colour vs serif + the entry
colours), which is exactly what the provider's per-stock `Spec` cache is for: one override fronts both sites and
each keeps its own size/colour unless the handle specifies them.

### [`Label.java`](../../../src/haven/Label.java) — the explicit-foundry path joins the `"label"` scope

F1 left `fontscope == null` for explicit-foundry labels and hard-coded `Text.std` as the stock in `restyle()`.
Two changes fix both:

1. A new **`fontstock`** field (the site's own foundry = the fallback handed to the provider), and `restyle()`
   now asks `Fonts.foundry(fontscope, fontstock)` instead of `Fonts.foundry(fontscope, Text.std)`.
2. All four public constructors funnel into **one private constructor** carrying `(text, w, stock, scope)`:

   ```java
   public Label(String text, int w, Text.Foundry f) {this(text, w, f, "label");}
   public Label(String text, Text.Foundry f)        {this(text, -1, f, "label");}
   public Label(String text, int w)                 {this(text, w, Text.std, "default");}
   public Label(String text)                        {this(text, -1, Text.std, "default");}
   ```

   The funnel matters: F1's default constructors chained through the *explicit* one, passing an
   **already-resolved** foundry. Had the explicit constructor started resolving `"label"` on its own, a default
   label would have been resolved twice (and could have picked up a `"label"` override it must not follow). The
   private constructor resolves **exactly once**, at construction, so a label has its correct size immediately
   (no first-frame resize that would land after its parent laid it out), and `w < 0` selects `render` vs
   `renderwrap` — the two old constructor bodies collapse into one line.

Everything else (`settext`/`setcolor`/`dispose`/`uimsg`) is untouched, and `CharWnd.RLabel` (the only `Label`
subclass) uses the default constructor, so it stays on `"default"`.

## The `Label` class was not the surface (the correction that shaped this slice)

The spec calls the scope *"explicit non-default labels"*, so the first pass routed exactly that: `Label` widgets
built with a foundry argument. In-game the maintainer reported **nothing changed anywhere** — correctly, and the
wiring was fine (a headless reflection check proves an explicit-foundry `Label` switches family and keeps its
size). The surface was simply almost empty: a grep found only ~10 such construction sites, and every one of them
lives in a rarely-open corner — the credo `Level:`/`Quest:` lines (present only with a credo in progress),
`WoundWnd`'s quality string, `FightWnd`'s `0/0` counter, `BAttrWnd`'s `%`, the village name, and the login screen.

The body text a player actually reads — the character sheet's attribute rows, the skill / lore / quest / wound
lists — is **not a `Label` at all**. It is `CharWnd.attrf` (fraktur 18, `public static final`) rendered
**directly** inside custom widgets' `draw`, referenced across 10 files. So F3c grew a second half: route that
shared foundry, using F3b's twin pattern.

### [`CharWnd.java`](../../../src/haven/CharWnd.java) — the provider-resolved twin

`attrf` is left **exactly** as it was. It must be: it is `public static final` (un-re-derivable), it is aliased by
`MenuSearch.elf` / `SListMenu.bigf` / `GobIcon.elf`, and — crucially — `attrf.height()` is what fixes **row
geometry** all over the character sheet at construction time. The new twin is the F3b shape:

```java
private static Text.Foundry battrf;  private static int attrfgen = -1;
public static Text.Foundry attrfont() {
    int g = Fonts.gen();
    if((battrf == null) || (attrfgen != g)) {battrf = Fonts.foundry("label", attrf); attrfgen = g;}
    return(battrf);
}
```

### [`SListWidget.java`](../../../src/haven/SListWidget.java) — every list item (the big win)

`TextItem` and `IconText` are the client's generic list rows (skills, lore/credos, quests, wounds, maneuvers,
radar icon settings, …). Both already had `protected Text.Forge foundry() {return(CharWnd.attrf);}` and a cached
`Text.Slug text` — so each needed exactly two changes: `foundry()` → `CharWnd.attrfont()`, and a private
`dropfont()` (a `fontgen` compare that disposes **only** the cached text) called at the top of `drawtext`. It is
deliberately **not** `invalidate()`: `IconText.invalidate()` also throws away the convolved icon, which a font
change has no business re-doing.

### [`BAttrWnd.java`](../../../src/haven/BAttrWnd.java) + [`SAttrWnd.java`](../../../src/haven/SAttrWnd.java) — the attribute rows

Each row renders the attribute **name** once in its constructor (`public final Text rnm`) and its **value** in
`tick` when the number changes. Both were routed through `attrfont()`, `rnm` was made **non-final**, the name
string is now remembered (`rnms`), and a `checkfont()` at the top of `draw` re-renders the name and clears the
cached-value sentinel (`ccv = -1` — never a real value) so the next `tick` re-renders the number **with its
buff/debuff colour intact**. The row's **height** stays the stock one (it was sized from `attrf.height()` before
any override existed), which is why an override should not carry a bigger `size`.

### [`MenuSearch.java`](../../../src/haven/MenuSearch.java) + [`SListMenu.java`](../../../src/haven/SListMenu.java) — the aliases

`MenuSearch`'s result rows override `foundry()` with its `elf` alias → now `CharWnd.attrfont()` (invalidation
comes free from `IconText.dropfont`). `SListMenu`'s `bigf` **defaults** (`TextMenu`/`IconMenu` and the two
`of(...)` factories) resolve through the provider too, so a menu opened after a `setFont` uses it; the `elh`/
`elf.height()` row-geometry constants stay stock on purpose. Caller-supplied foundries are still honoured
untouched.

**Known skips** (deliberate, same spirit as F3b's caller-supplied button faces): `FightWnd`'s **saved-school
names** and the in-place rename editor render `attrf` directly from strings the server pushed via `uimsg` and
stock does not keep, so restyling them live would need a recorded-recipe pass like F3b's; and the italic
`"Unused save"` placeholder is a pre-rendered `Text` built from `attrf.font` at class-init. Both keep the stock
font until (if ever) a follow-up slice records their sources. Every `attrf.height()`-derived **row height** is
likewise left stock — that is geometry, not typography.

## Deliberate design points

- **The command line rides `"textentry"`**, not a scope of its own. It is the client's other `ReadLine` surface
  and the spec names them in one slice; a separate `"console"` scope would add an enum member with no distinct
  user intent (and the enum is fixed from F1 — adding one later is a spec change, not a code detail).
- **A `"label"` override inherits each site's stock size** (the provider only overrides the size when the handle
  carries one). This is what makes the scope *usable*: a label resizes itself to its text and its container will
  not re-lay-out around it, so swapping the family while keeping per-site sizes is the safe default. The `hello`
  harness therefore passes **no** `size` for `"label"` — the honest DoD for "does the cascade preserve per-site
  sizing?".
- **A field's height is fixed by its background texture** (`gfx/hud/text/m`), not by the font, so a much larger
  `"textentry"` size draws clipped. That is client geometry, not an API limit; documented in
  [api/fonts.md](../api/fonts.md).
- **Stock foundries stay stock.** `TextEntry.fnd`, `ConsoleHost.cmdfoundry` and each label's own foundry remain
  what they were (an override never mutates them), so any unrouted consumer keeps working and the stock UI is
  always restorable — the same choice as F3b's `Button.tf`/`nf`.

## Verification

- **Clean compile:** `rm -rf build/classes && ant hafen-client` → **BUILD SUCCESSFUL** (634 sources; only the
  three pre-existing `URL` deprecation warnings), then `ant bin` → BUILD SUCCESSFUL.
- **26 headless provider-resolution checks** (`haven.Fonts` driven directly, no GL, with the three real stock
  recipes rebuilt by hand): baseline stock identity for all three sites + both scopes present in the enum; a
  `"default"` push bumps `gen` and cascades into both unset scopes while preserving each site's stock size (12 /
  14) and the command line's wheat colour; the per-stock foundry cache is stable and distinct stocks get distinct
  foundries; a `"textentry"` override refines only itself (`"label"` stays on the cascade, `"window.title"`
  untouched) and a `"label"` override likewise; last-wins across two owners, `reset` falls back to the owner
  beneath and is `false` when already unset; `removeOwner` restores the stock fast path for every site.
  **26 ok, 0 failed.**
- **LuaJ parse** of all 7 addons → ok.
- **`Label` restyle check** (headless, reflection over the private `restyle()`): an explicit-foundry label built
  on the `SkillWnd.prsf` recipe reports `f == stock` at construction, becomes `Monospaced` at **size 15** after
  `setFont("label", mono)` (size preserved — the whole point), and returns to the *identical* stock foundry object
  after `removeOwner`. This is what proved the `"label"` wiring was correct while the *surface* was empty.
- **In-game (the DoD):** `:hello entry` → click any text field (or the console line) and type: the glyphs are the
  new font, caret/selection still land correctly; window titles, buttons and body text stay stock. `:hello label`
  → open the **character sheet** (attribute rows) and **Skills & Lore / Quests / Wounds** (list items): the body
  text changes **family** but keeps its sizes and no row jumps. `:hello font` alone (no per-scope override)
  restyles fields *and* body text via the cascade; adding `:hello entry` on top refines only the fields.
  `:reload` / a second toggle restores stock everywhere.

## Harness (`hello` v0.51.1)

Two new independent sub-commands, in the same shape as `:hello title`/`:hello button`:

- **`:hello entry`** — toggles `setFont("textentry", mono 12)` (mono vs the stock serif 12, so the change is
  visible; the size stays 12 to avoid the texture clipping).
- **`:hello label`** — toggles `setFont("label", mono)` with **no size**, proving per-site sizes survive; the
  places to look are the character sheet's attribute rows and any list (Skills & Lore, Quests, Wounds).

Both flags reset in `OnLoad` (a reload rebuilt the env and the overrides were torn down — the P2 rule), and both
are listed in the `:hello` help line.
