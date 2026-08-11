# 058 — plan

## Approach

**1. One conversion seam, and it is grep-able.** A new package-private `io.brodgar.addon.Px` with
`in(int)`/`in(Coord)` (design → device, `UI.scale`) and `out(int)`/`out(Coord)` (device → design,
`UI.unscale`). **Every** Lua↔`haven` crossing of a UI coordinate goes through it and nothing in the bridge
calls `UI.scale` directly any more. That is the audit: after this feature,
`grep -rn "UI\.scale\|UI\.unscale" src/io/brodgar/addon/` hits `Px.java`, `FontHandle.rich` (a *type* size,
which was always right) and `CScrollport` (a wheel step, device by nature) — and nowhere else. `Px` also
carries the one-line proof that `scalef >= 1` makes `out(in(n)) == n`, so the guarantee lives beside the code
that owes it.

**2. Widget geometry, input and hit-testing** (058.1). `LuaWidget`'s `position`/`size`/`rootPos`/`info` on
both arities; the `Moved` record keeps **device** values (it restores what the client had, so it must not
round), and only the Lua-facing edges convert. `UiApi.at`/`tipAt`, `LuaMouse.x`/`y`, and `LuaEvent` — whose
single `x, y` int pair funnels INPUT, DROP, GRAB_MOVE, GRAB_UP, CLICKED **and** DRAW/CELL's `w`/`h`, so it is
one place, not seven. `hafen.ui():scale()` is added on the section, reading `UI.scale(1000) / 1000.0`.

**3. Draw space and the asset pixel** (058.2). Every `Coord.of(a.arg(n).toint(), …)` in `LuaGOut` becomes
`Px.in(…)`; `LuaHudOverlay`'s `w, h`; `LuaGobOverlay`'s `sx, sy`. `g:image` blits `UI.scale(img.tex)`
(`UI.scale(Tex)` → `ScaledTex`) so an addon PNG's own pixels *are* design pixels, matching what
`vr/sprites.md` already does; `LuaImage.sz` therefore already answers design px and needs no change.
`g:resource` is the trap in the other direction — `Resource.Image.tex()` is built from `scaled()` and is
**already** device-sized, so its native blit converts nothing and only its explicit `w, h` box does.

**4. The sheet** (058.3). `Layout.Anchor.resolve` converts the parsed `offset`; `Chrome.Border`'s four
insets and `SkinDeco`'s `pad` (which is added to `Window.dlmrgn`/`dsmrgn`, already `UI.scale`d, so today it
is the odd one out); `Controls`' `rowHeight` and `cell`. `Sheet` stores what the rule said — design — and
each consumer converts where it meets the engine.

**5. The art's height, and a real `pack()`** (058.4). `LuaWidget`'s `size` gains the one-number arity: the
`!a.arg(2).isnil()` branch that throws today becomes the width-only write. Two-number writes are checked
against a per-control **minimum**, exposed by a new `Owned.Control` default method `minsz()` — `Button.hs`
(via `largep(w)` for the `hl` case), `CDropdown`'s arrow box, `CEntry`, `CCheck` — and a violation raises
naming the minimum and `:size(w)`. `pack()` drops its `content.widget() != w` guard and packs the content
widget (`Widget.pack` = `resize(contentsz())`) before refitting a window's chrome.

## Files to create / modify

- **New** `src/io/brodgar/addon/Px.java`.
- `src/io/brodgar/addon/`: `LuaWidget`, `UiApi`, `LuaMouse`, `LuaEvent`, `LuaGOut`, `LuaHudOverlay`,
  `LuaGobOverlay`, `LuaWidgetEntity`, `Layout`, `Chrome`, `SkinDeco`, `Controls`, `CtlButton`, `CtlIButton`,
  `CDropdown`, `CEntry`, `CCheck`, `Owned`, `AddonWidget` (the deferral comment at its line 54 goes).
- `addons/`: `timers/main.lua` (delete `measure()`/`px()`, keep the text rulers), `stockfilter/main.lua`,
  `hello/main.lua`.
- `docs/addons/api/ui/`: `custom.md`, `widget.md`, `native.md`, `drawing.md`, `mouse.md`, `lists.md`,
  `controls/README.md`, `style/README.md`, `style/chrome.md`, `style/geometry.md`.
  `docs/addons/api/vr/{sprites,widgets}.md`; `docs/addons/api/client/README.md`.
- **New** `docs/client/ui-scaling.md` — the upstream scale system, which no page maps today and which every
  task here had to read: `UI.scalef`/`scale`/`unscale`/`rscale`/`ScaledTex`, `loadscale`'s `uiscale` pref and
  its `[1.0, maxscale()]` clamp, `Resource.Image.ssz`/`scaled()`/`tex()` vs `rawtex()`, `Button.hs`,
  `DefaultDeco`'s scaled margins. Add its row to `docs/client/README.md`.
- `docs/client/widgets.md` — rows for `Widget.Position`/`getpos`/`pos`/`adds`/`pack`/`contentsz`/`addhlp`,
  none of which the page names.

## Risks & gotchas

- **`Coord.div(int)` floors** (`Utils.floordiv`) while `Coord.div(double)` rounds. `UI.unscale(Coord)` is the
  `double` one and is correct; anything hand-rolled with an `int` divisor silently drifts a pixel downward.
- **`Moved.pos`/`Moved.size` must stay device.** `LuaWidget.sizeArg` records the argument that reproduces the
  widget's geometry through `Widget.resize` (a `Window`'s `csz()`); rounding it into design and back would
  make the restore inexact and hand the user back a window a pixel off.
- **`GameUI.savewndpos` and `UiApi.fitc`** run in device space, below the seam. Convert on the way out of
  Lua, never inside the clamp.
- **A border's nine `TexSI` views are cut in the *image's own* pixels**, which are now design pixels — so
  `Chrome.Border`'s slice validation against the image size stays unchanged, and it is the **draw** that
  scales. `TexSI` over a `ScaledTex` is not the shape to reach for.
- **`Button.hs` is a static read of `bl.getHeight()`** on art loaded through `Resource.loadsimg` →
  `Image.scaled()`, whose `ssz` is `round(UI.scale(sz / scale))` with a res-declared `scale` divisor. The
  design height is therefore the art's own pre-scale height, and is the same integer at every UI scale — which
  is what makes a suite's numeric assertion cross-scale at all.
- **`UI.scalef` is `static final`, read once at class init.** No suite may assert across a rescale (040's
  rule). Cross-scale proof is: a design number that is *the same integer* at every scale, printed beside
  `hafen.ui():scale()`, run twice by the maintainer.
- **`CScrollport`'s `UI.scale(15)` wheel step stays device**, and `FontHandle.rich`'s `UI.scale(size)` stays:
  a type size was always design, and this feature does not touch it.

## Discarded alternatives

- **Expose the unit only (`hafen.ui():scale()` as a multiplier, or a `px(n)` helper).** It leaves the
  arithmetic at all 45 call sites and fixes nothing about heights — a control clipped by a number too small
  is still clipped when the author multiplies it. Kept only as a *read*, and documented as not a unit.
- **Design pixels for writes, device pixels for reads.** This is what the client itself does, but the client
  spells the multiply visibly at each literal; an invisible one-way conversion makes `w:size(100, 40)` read
  back `{150, 60}` and every read↔write pair in an addon a bug. A round-trip that is not the identity is not
  a boundary, it is a trap.
- **Two spaces — layout in design pixels, drawing and input in device pixels.** `ev:x()` and `:size()` are
  both "widget-local pixels"; splitting them means an addon's own hit rectangle disagrees with the box it
  drew. The pair that must agree is exactly the pair the split separates.
- **Refuse a too-short box rather than offer `:size(w)`.** A refusal alone still forces the author to
  discover the art's height by measuring, which is the thing this feature exists to delete.
- **Clamp a too-short box up to the art's minimum, the way a slider clamps a value.** A slider's range is
  something the addon set and may narrow; an art height is a fact it cannot know, so silence there teaches
  nothing and leaves a number in the source that means nothing.
- **Port the client's relative-placement vocabulary** (`getpos("bl").adds(…)`, `addhlp`) in this feature.
  Once the unit is right the remaining numbers are correct, so this buys concision, not correctness — and a
  second coordinate grammar beside `:position` would be the dual style the API forbids until the first one
  is proven insufficient.
