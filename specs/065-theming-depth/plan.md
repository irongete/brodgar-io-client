# 065 — theming depth: plan

## Approach

### The spine: one opaque property bag

`Fonts.Spec` today carries a field per property and `Fonts.combine` a line per field. Its **font half** —
`base`, `size`, `aa`, `color` — is read by `haven` itself to build foundries and stays typed. Its **chrome
half** — `bg`, `border`, `pad` — is already three opaque `Object`s the core never looks inside, read in
exactly three places, all ours (`Chrome.box`, `SkinDeco.check`).

That half becomes **one immutable `Map<String, Object>`**, built at push and folded key by key by `combine`
(inner's keys win, outer fills the rest — the same per-property fold, now honest for any key). `Fonts.Style`
loses `bg()`, `border()` and `pad()` and gains `Object prop(String)`; `Fonts.push` and `Fonts.treeSpec` take
the map instead of three arguments; `Spec.foundry`'s "a rule that names only chrome says nothing about text"
early-out becomes a check on the four typed fields. `Spec.stamp` and the `combos` interning are untouched:
`combine` still keys on the identity of the two `Spec`s, so no site sees a new generation per frame.

After this, **a new theme property is written entirely in `io.brodgar.addon`** — a parser in `Chrome`, a
field in `Sheet.Props`, a setter in `LuaRule`, a reader at the site — and `haven` is edited only where a new
*surface* is routed. That is what makes stages B and C affordable.

### One art value, four spellings; one face value, three

`Chrome.parseArt(ctx, v)` is the single parser behind `bg`, `picture`, and the art inside `close`/`sizer`:
`{color=}`, `{image=<handle>}`, `{asset="path"}`, `{res="gfx/…"}`, plus the named state variants
(`hover`, `pressed`, `disabled`, `checked`) which recurse into the same parser. `border` takes that *or*
`{color=, width=}` (a line) *or* the 9-slice forms, `{…, slice={l,t,r,b}}` and `{box="gfx/hud/wnd"}`.

`{asset=}` resolves through `AssetApi`'s own interning, so a path and a handle are the same object.
`{res=}` and `{box=}` go to `Resource.loadtex`, whose **`.scaled()` view is the one to take** — the HUD art
carries a `scale` (4.0 on `gfx/hud/wnd`), so the raw layer draws four times too large. `Chrome.Border`
becomes two implementations behind one interface — the sliced image, and the engine `IBox` — each answering
`tlIn()`/`brIn()`; for the box form those are `ctloff()`/`cbroff()`, so the frame the draw paints and the
room the layout reserves stay the same rectangle by construction.

`mode = "tile"` is the third implementation, and it is what makes the **client's own decoration** sayable:
`IBox.Scaled` stretches its edges, while `DefaultDeco.drawframe` *repeats* them — `for(x…) g.image(cm, mdo,
Coord.z, cbr)`, a blit per tile clipped to the run. The two differ in that loop and nothing else, so a
tiling `IBox` beside `IBox.Scaled` covers both, with `stretch` the default because it is what every
existing rule means.

**Two of the four kinds also come as a list, and that is the whole of what a 9-slice could not say.** A
`bg` may be an **array of layers**, painted in order, each an ordinary surface value with an optional `at`
and `mode` — which is exactly the stock window's three: the tiled `bg`, then `bgl` down the left, then
`bgr` down the right. A `border` may carry **`parts`**, an array of `{art, at, offset}` pinned with the
same `Chrome.Spot` the ornaments use — which is exactly `lb`, the one piece the stock drops at the foot of
its left run before tiling the rest. Neither is a new primitive: it is an art value and a spot, at a
different arity. Both are what a theme wants anyway — a texture under a vignette, a rivet at a corner.

`Sheet.font` gains the data spellings: `{asset="fonts/x.ttf", size=, bold=, aa=}` and `{builtin="mono", …}`,
resolved to the `FontHandle` the property already takes.

### The window's three ornaments

`SkinDeco` already overrides `iresize` with the theme's numbers and `drawframe` with its art. `caption`,
`close` and `sizer` are parsed into a `Chrome.Spot` — a nine-corner name plus an offset, reusing the corner
vocabulary `Layout.parseAnchor` validates (extracted to a shared helper; `Layout.Anchor` itself is not
reused, since it resolves against a *target widget* and this resolves against the deco's own box). With no
spec, each falls back to the stock constant — `Window.cpo`, `Window.sizer`, `cbtn` at the top right — so a
stock client is untouched to the pixel. The close button's **art** needs the button rebuilt (`IButton`'s
faces are `final`), which `SkinDeco.check` already has the shape for: it swaps a whole deco through
`chdeco` today.

The **caption plate** is the fourth ornament and it is not a new shape at all: `window.title` starts
carrying `bg` and `border`, the plate being the caption's own surface. The client keeps deciding its
geometry — `checkcap` computes `cptl`/`cpsz` from `cmw = max(caption width, sz.x / 4)` — so the theme
supplies the art and the plate still grows with its title, which is the same division of labour a panel
already has. It is what a theme with a title bar wants and what the stock `cl`/`cm`/`cr` run *is*.

### Routing a surface that draws a box

The pattern the panels already use — `Fonts.box("panel", this, stock)` returning the stock box when no rule
names it — is the pattern for every surface in stage B. `Button`'s face is four edge images plus a centre
texture, which is a 9-slice plus a `bg` by another name, so it reuses that machinery verbatim; its stock
`IBox` is built once from its own `bl`/`br`/`bt`/`bb`. `TextEntry`'s three caps are the same shape.
`CheckBox`, `Scrollbar` and `HSlider` blit `Tex`es directly in `draw(GOut)` and need only the lookup.
Invalidation is already paid for: `Fonts.gen()` moves on every rule change and these sites already re-render
on it — `Button.draw(GOut)`'s check is merely gated on `rtext != null` and widens to any generation move.

Two surfaces have no resource at all. The **tooltip's box** is two `chcolor`/`rect2` calls in
`UILoop.drawtooltip`; the **inventory square** is a 33×33 raster built in `Inventory`'s static initialiser
from two colours. Both become "ask the sheet, else paint what you paint today".

### The picture and the letter

`picture` is read at the **draw**, never written into the widget: `Img` is re-pointed by the server
(`uimsg "ch"`), so writing `setimg` would be clobbered and would fight the restore on drop. The HUD's
blitted plates (`GameUI.menubg`, `mapmenubg`, `nkeybg`, the `csearch-bg` group, `MiniMap`'s frame) get one
site key each and the same lookup.

The **chat's colours** are one constant per kind rather than one colour — `(192,192,255)`, `(255,128,128)`,
`(128,128,255)` and the `urgcols` triple — so they split into `chat.system`, `chat.private`, `chat.party`
and `chat.urgent`, each cascading into `chat` and then into `*`. The one colour the client **generates**,
`Color.HSBtoRGB(colseq = (colseq + √2) % 1, 0.5f, 1.0f)` per speaker, becomes a value by naming the
*sequence* rather than its outputs: `chat.speaker` takes `{generate = {step, saturation, brightness}}` — the
stock being `{step = "golden", saturation = 0.5, brightness = 1.0}` — or a `{palette = {…}}` that cycles
colours a theme lists. Flattening it to one colour stays impossible, and should: what it is for is telling
speakers apart.

### The client, read back as data

`sheet:stock()` answers **the whole look this client draws with, as a plain table** — every routed key,
with art named by resource, faces by built-in, colours as numbers — and `sheet:stock(key)` answers one.
Nothing new has to be published for it: every routed site already *hands its stock in* as the third argument
(`Fonts.box(scope, this, stock)`, `Fonts.foundry(scope, stock)`), so the registry records what it is offered,
and a texture is resolved back to its resource name through `AddonManager.onPicture`, the map 063.3 already
fills for `widget:picture()`.

That is what makes a stock catalogue **generated rather than transcribed**: three lines write it to a file,
`hafen.json()` serialises it, `sheet:load()` puts it back, and it cannot rot against an upstream art change
because nobody typed it. It is also the feature's own falsification — a key whose stock cannot be expressed
in the vocabulary shows up here as a hole, in a test, rather than in someone's theme.

`emboss` and `glow` are the client's own `PUtils.TexFurn` and `PUtils.BlurFurn`, which every embossed site
already builds around its resolved foundry — `Window.DefaultDeco.checktitlefont` is the model. `emboss =
false` drops the `TexFurn`, which is precisely what makes `color` reach those glyphs; `glow` supplies the
`BlurFurn`'s two radii and colour. Radii are written in design px and converted with `Px.in`, as the stock
`UI.rscale(0.75)` already is.

## Files to create and modify

**Engine** — `src/haven/Fonts.java` (the bag, `Style.prop`, `combine`, `push`/`treeSpec`, `SCOPES`),
`Window.java` (nothing but the caption extraction already made `protected`), `Button.java`,
`TextEntry.java`, `CheckBox.java`, `IButton.java`, `Scrollbar.java`, `HSlider.java`, `Inventory.java`,
`UILoop.java`, `Speaking.java`, `Img.java`, `GameUI.java`, `MiniMap.java`, `Text.java`/`PUtils.java` (the
furnace seam) — every one of them a `// addon:` line that returns the stock value when no rule applies.

**Addon layer** — `src/io/brodgar/addon/Chrome.java` (the art/face/spot parsers, `Border`'s two shapes, the
per-surface lookups), `Sheet.java` (`Props`, `PROPS`, the load dispatch, `toLua`), `LuaRule.java` (the
setters), `SkinDeco.java` (the ornaments), `Retired.java` (`pad`), `Layout.java` (the corner parse, moved).

**Docs, addons tier** — `api/ui/style/chrome.md`, `keys.md`, `text.md`, `README.md`, `surfaces.md`,
`geometry.md`, `guides/theming.md`, `api/font.md`, `api/asset.md`, `api/ui/pixels.md`, `api/ui/native.md`,
`api/README.md`, `api/ui/controls/interactive.md`, `display.md`.

**Docs, client map** — `docs/client/ui-chrome.md` (the ornaments, and a new section: the boxes the client
draws **in code** rather than from a resource — the tooltip's two colours, the inventory square's raster),
`ui-controls.md` (each control's art, and the `SIWidget` redraw trap), `ui-lists.md` (the slider and
scrollbar art), `text-and-fonts.md` (the furnace stack, and `Style`'s new shape).

## Risks and gotchas

- **`Widget.resize` does not `redraw()` an `SIWidget`.** A `Button` or `IButton` whose face changes keeps its
  old raster at the old size until something invalidates it. Every stage-B task that changes a face calls
  `redraw()` itself; the symptom otherwise reads as a layout bug.
- **`IButton`'s faces are `final`.** The close button cannot be re-faced in place — `SkinDeco.check` rebuilds
  it, and must destroy the one it displaces, exactly as `chdeco` does for a whole deco.
- **An `IBox`'s six measuring methods are read at CONSTRUCTION** (`Frame`'s ctor, `getpos`, `xlate`,
  `checkhit`, `addin`, `SListMenu`'s ctor). A swapped box may paint differently but must measure identically,
  or the frame moves and its contents do not. This is why `padding` and a border's insets stay inert on a
  panel and are honest only on a window, which re-lays itself out through `iresize`/`contarea`.
- **`Frame.around` leaves the framed widgets as *siblings*.** A `bg` on such a panel would bury the rows the
  frame is drawn around; the existing split (border yes, bg inert) holds for every new box key too.
- **`Inventory.invsq` is a `static final` built in a static initialiser.** The JVM never re-runs one, so it
  is never *replaced* — the draw site asks the sheet and falls back to it.
- **`Speaking` is a `GAttrib`, not a `Widget`.** `Fonts.box(scope, wdg, stock)` cannot serve it; the bubble
  resolves through the widget-less `Fonts.style(scope)` path that `RichText` and `ChatUI` already use.
- **A `.res` image carries its own `scale`.** `gfx/hud/wnd` is authored at 4×; `Resource.Image.scaled()` is
  the design-pixel view and the raw layer is not. Taking the wrong one draws the frame four times too large.
- **`Resource.loadtex` throws `NoSuchResourceException`** on a name that does not resolve — on the local
  pool, in about 10 ms, so it is safe on the UI thread, and it must surface as a `pcall`-able Lua error
  naming the resource rather than as an engine exception.
- **Every term of `SkinDeco.iresize`'s sum is device px.** `Window.tlm`/`brm`/`dlmrgn`/`dsmrgn` are
  `UI.scale`d constants, so `padding` and the slice insets convert on the way in through `Px.in`, or the
  frame reserves less room than the draw paints.
- **`Fonts.gen()` is not frame-global.** A per-widget frame XORs that override's `Spec.stamp`, which is what
  makes a `gen != mygen` check fire for a widget constructed outside the frame. Any new cached raster keyed
  on `gen()` inherits that behaviour and must not cache across the check.
- **The tooltip's cache is keyed on the tooltip *object*** (`Utils.eq(tooltip, prevtooltip)`) and dropped
  when `gen()` moves; its box is painted outside that cache every frame, so a chrome rule there needs no
  invalidation at all.
- **`PUtils.BlurFurn(Text.Forge, int grad, int brad, Color)`** takes two radii in device px. The stock
  caption passes `UI.rscale(0.75)`/`UI.rscale(1.0)`; a rule's `radius` is design px and converts.
- **A tiled edge is a blit per tile, clipped.** The stock runs pass a clip rectangle to every `g.image`
  (`g.image(cm, mdo, Coord.z, cbr)`), which is what keeps the last tile from overrunning the corner. A
  tiling box that scales the final tile instead would draw a seam at one end of every long window.
- **The caption plate sizes itself, and the theme does not get to say so.** `checkcap` recomputes
  `cptl`/`cpsz`/`cmw` whenever the caption, the focus or the font generation moves, so a plate rule is read
  at the draw and never cached against a title that has since changed.
- **A stock value is only recorded once its site has drawn.** The registry behind `sheet:stock()` learns
  what a site hands in, so a key belonging to a window nobody has opened answers nothing until it does. The
  catalogue task states that plainly and its suite opens what it means to read, rather than asserting a
  complete table off a fresh login.
- **`AddonManager.onPicture` is filled where a picture is MINTED**, not where it is read (063.3), so a
  texture built by some path that never mints through `Resource.Image` resolves to no name. A catalogue
  entry that cannot name its art says so rather than inventing one.
- **The scope list is a closed vocabulary.** `Fonts.isScope` is what makes a stylesheet key a *site* key and
  `Selector.SITE_ROLES`/`WIDGET_ROLES` split on it, so every key this feature adds is added in all three
  places or it silently becomes a tree key that matches nothing.

## Discarded alternatives

- **A `:hover`/`:pressed` pseudo-class** — rejected: it would make every site publish its state to the
  cascade, for three rasterisers that already know their own. The variant belongs in the value it varies.
- **One `Spec` field per new property** — rejected: seven edit points per property, two of them in `haven`.
  The bag is what keeps core edits to *routing a surface*, which is the only thing that genuinely needs one.
- **Making the font half of `Spec` opaque too** — rejected: `haven` itself reads `base`/`size`/`aa`/`color`
  to build foundries, so hiding them behind a string key would buy nothing and cost a lookup per render.
- **Keeping `pad` as an alias for `padding`** — rejected: nothing is released, and a retired spelling throws
  naming its replacement, exactly as `pos` already does for `position`.
- **`padding` as four properties (`padding.top`, …)** — rejected: one property, one slot. The four-number
  form is what a data table says and what the reader hands back, which is what makes a read round-trip into
  a write.
- **Placing the close button with a layout rule instead of a chrome property** — rejected: `iresize` writes
  `cbtn.c` on every window resize, so the two would fight and the winner would depend on which event fired
  last.
- **Writing `picture` into the widget with `Img.setimg`** — rejected: the server re-points an `Img` by
  `uimsg`, so the write is clobbered and the restore-on-drop fights it. The picture is read at the draw, like
  every other property.
- **Replacing `Inventory.invsq` in place** — rejected: a `static final` from a static initialiser is never
  re-run by the JVM; the draw site asks instead, which is also what keeps the stock path byte-for-byte.
- **A Lua callback per frame for any of this** — rejected on the same ground the chrome property was: text
  has a raster cache behind it and a frame does not, so a declarative value is the only shape that stays
  free.
- **Modelling the client's `lg` decoration as a value shape of its own** — rejected: it would put one
  artwork's composition into the grammar. Everything that separates it from a 9-slice is general — edges
  that repeat, a plate that sizes to its caption, a background of several layers, a piece pinned at the end
  of a run — so each is said as a general property and the decoration falls out of them. Generalising was
  chosen over dropping the last two: what a theme cannot *say* is a hole in the vocabulary, not a detail of
  one frame, and the standing priority is what a JSON file can declare.
- **A `parts`/layers list as a bespoke slot per stock piece** (`footpiece =`, `shadeleft =`) — rejected:
  the same coverage for one artwork and none for any other. An array of the value that already exists costs
  no new vocabulary and serves every theme.
- **Flattening the chat's generated speaker colour to one colour** — rejected: it is what tells speakers
  apart. The *sequence* is named instead, so a theme replaces the generator rather than the colours. The
  kin-group colours beside it stay out on a different ground: the user picked those, they are not the
  client's look.
- **Transcribing the stock catalogue by hand** — rejected: it would be right the day it was written and
  silently wrong after any upstream art change. `sheet:stock()` generates it from what the sites hand in.
- **Three features instead of one** — rejected: this is one grammar and therefore one decision record;
  splitting it would re-litigate half the argument in each folder. The three stages inside `tasks.md` carry
  the cadence instead.
- **A `theme` field in the manifest, or a profile manager** — rejected: a sheet is data, `hafen.store` already
  persists tables, and an addon ships the experience.
