# 058 — design pixels: one unit for everything hafen.ui measures

## What & why

`hafen.ui()` takes and gives **device** pixels for every coordinate and every size, while the client's own
art, its controls' heights and a `hafen.font` handle's `size` all grow with the UI scale. So an addon's
numbers are correct at scale 1.0 and wrong everywhere else: `:size(80, 20)` on a button clips its bottom
border at 1.5 (`Button.hs` is the scaled art's height; `Button.draw` puts the bottom border at
`hs - bb.getHeight()`), rows overlap, and `stockfilter` — the worked example the controls tier points at —
is broken at any non-default scale. 45 literal `:size`/`:position` call sites across 7 shipped addons, none
compensating; `addons/timers/` compensates by hand, measuring control heights and multiplying its margins.

**The decision: the API's pixel is a design pixel.** One space, no dual style, no `px()` helper and nothing
to multiply. The bridge converts at every crossing into `haven` and nowhere else — `UI.scale` on the way in,
`UI.unscale` on the way out. `UI.scalef` is clamped to `>= 1.0`, so `unscale(scale(n)) == n` exactly: **a
number an addon writes reads back unchanged, at every scale.** That is the rounding story. The residual is
honest and documented: a *native* widget's device position is not a whole number of design pixels, so
reading one and writing it back may shift it by under a design pixel.

This aligns with the precedent rather than breaking it. `chrome.md` already says a font's `size` scales
"because a type size is not a coordinate"; the sentence inverts — a coordinate scales too, and the device
pixel simply stops being a thing the API ever names.

## Acceptance criteria

1. **Round-trip.** `w:size(100, 40)` reads back `{100, 40}` and `w:position(30, 20)` reads `{30, 20}` at any
   scale, on a surface, a control and a native widget alike.
2. **`hafen.ui():scale()`** answers the **running** device factor (`>= 1.0`). `interface():scale()` reads the
   persisted pref, which is not what is in force — it defaults to `1.0` where `UI.loadscale` defaults to the
   density-derived `defscale`, and it is not clamped to `maxscale`. Both are documented for what they are.
3. **Input shares layout's space.** `mouse():x()/:y()`, `ev:x()/:y()` on the five input keys, `:at`, `:tipAt`,
   `:rootPos()` and `:info().pos/size` are design pixels, and `hafen.ui():at(m:x(), m:y()) == m:over()`.
4. **Drawing shares it too.** Every `g:` coordinate, `ev:w()/:h()` on `Draw`, `overlay:onDraw`'s `w, h` and a
   gob overlay's `sx, sy`. An addon's own PNG blits at its **design** size, so `g:image(icon, x, y)` covers
   exactly `icon:size()`; `g:resource` — whose texture is already device-scaled — is not scaled twice.
5. **The sheet says the same space**: `position`, `size`, an `anchor`'s `offset`, `pad`, a `border`'s `slice`,
   and `:rowHeight(n)` / `:cell(w, h)`. A themed 8 px border reads at the weight of the chrome it replaced.
6. **No control can be given a box its art will not fit.** `:size(w)` — one number — sets the width and
   leaves the height to the art; `:size(w, h)` with an `h` under the art's minimum **raises**, naming that
   minimum and `:size(w)`. This closes the ROADMAP `dropdown:size(w, h)` defect.
7. **`w:pack()` sizes a widget to its content** — a bare widget as well as a window — so an addon never
   computes a container's box.
8. **No measuring, no multiplying in the shipped addons.** `addons/timers/`'s `measure()`/`px()` are deleted
   (its *text*-width rulers may stay: font metrics are not linear); `stockfilter` and `hello` give no control
   a height.

## Out of scope

- A **relative placement vocabulary** (the client's `getpos("bl").adds(…)` corners as a Lua verb). The
  numbers are correct without it; removing them is a second feature.
- **Observing a scale change at runtime.** `UI.scalef` is `static final` and the option says *requires
  restart*, so no criterion asks a suite to assert across a rescale (040's rule, still in force).
- World and tile coordinates (`hafen.world`, `hafen.map` drawings), and `hafen.font`'s `size`, already design
  pixels and unchanged.

## Docs impact

`grep -rn "raw pixel\|DPI-scaled\|not DPI\|logical px\|unscaled" docs/addons/` → **7 places on 6 pages**:
`api/ui/custom.md:48`, `api/ui/controls/README.md:71`, `api/ui/style/chrome.md:41-42` and `:97-98`,
`api/ui/style/geometry.md:69`, `api/vr/sprites.md:66`, `api/font.md:63` (the one that stays true).

**Derived impact set** — `grep -rn "in pixels\|px\b" docs/addons/api/ui docs/addons/api/vr` → the pages that
state the unit *without* the word "raw", invisible to the grep above: `api/ui/style/README.md:97,99` (the
property table's "raw px"), `api/ui/widget.md:61,72,73,195`, `api/ui/native.md:37`, `api/ui/lists.md:57,108`,
`api/ui/custom.md:39`, `api/ui/style/chrome.md:67,84`, `api/ui/style/geometry.md:72`, `api/vr/widgets.md:57`
(a standing panel's world size comes from the widget's pixels — now design, so it no longer depends on the
user's HUD scale). Plus `api/client/README.md:50`, where `interface():scale()` must stop reading as the
factor in force.

New: `docs/client/ui-scaling.md` — the upstream scale system nothing maps today; rows on
`docs/client/widgets.md` for `Widget.Position`/`getpos`/`pack`/`contentsz`.

## Context files

- `src/io/brodgar/addon/Px.java` — **the seam**, and the only place a UI coordinate converts: `in`/`out`
  over `UI.scale`/`UI.unscale` for an `int`, a `Coord` and a `double` (an unrounded length), `in(TexI)` for a
  whole raster, plus `factor()` behind `hafen.ui():scale()` — 2, 3, 4
- `docs/client/ui-scaling.md` — the upstream scale system, mapped: `UI.scalef` and its converters, where the
  number comes from, `Resource.Image.ssz`/`scaled()`/`tex()`, `Button.hs`, `Window.dlmrgn`/`dsmrgn` — 2, 3, 4
- `src/haven/UI.java` (`ScaledTex`, and whatever the map above does not answer) — 2
- `src/io/brodgar/addon/LuaWidget.java` — 3, 4
- `src/io/brodgar/addon/UiApi.java` — 2, 4
- `src/io/brodgar/addon/{LuaGOut,LuaHudOverlay,LuaGobOverlay,LuaImage,LuaWidgetEntity}.java` — 2
- `src/io/brodgar/addon/{Sheet,LuaRule,Layout,Chrome,SkinDeco}.java` — 3
- `src/io/brodgar/addon/Controls.java` — 3, 4
- `src/io/brodgar/addon/{CGrid,CList,CDropdown,CMenu,CTable}.java` (the `cell`/`rowHeight` stock defaults,
  beside the initial boxes 058.1 already converted) — 3
- `src/io/brodgar/addon/{CtlButton,CtlIButton,CDropdown,CEntry,CCheck,Owned}.java` — 4
- `src/haven/{Button,Widget,Resource}.java` (`hs`/`largep`, `pack`/`contentsz`, `Image.scaled`) — 4
- `addons/{timers,stockfilter,hello}/main.lua` — 4
- `docs/addons/api/ui/**` (`pixels.md` is where the unit is stated; every other page links to it),
  `docs/addons/api/vr/{sprites,widgets}.md`, `docs/addons/api/client/README.md`,
  `docs/client/{widgets,text-and-fonts}.md`, `DOCUMENTATION.md` — 2, 3, 4 (each task its own pages)
