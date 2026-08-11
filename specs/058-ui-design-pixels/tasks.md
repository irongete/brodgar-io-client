# 058 — tasks

- [x] **058.1 — the unit at the widget boundary: hafen.ui measures in design pixels.** Adds
      `io.brodgar.addon.Px` (`in`/`out` over `UI.scale`/`UI.unscale`) and routes every widget-geometry
      crossing through it: `LuaWidget`'s `:position`/`:size`/`:rootPos`/`:info` on both arities, the
      builders' initial box, `UiApi.at`/`tipAt`, `LuaMouse.x`/`y`, and `LuaEvent`'s single `x, y` pair —
      which funnels INPUT, DROP, GRAB_MOVE/UP, CLICKED *and* DRAW/CELL's `w`/`h`, so it is one site, not
      seven. `LuaWidget.Moved` keeps device values. Adds `hafen.ui():scale()`, the running factor. Retires
      the deferral comment at `AddonWidget.java:54`. Writes `docs/client/ui-scaling.md`.
      *Its suite* writes `:size(100, 40)` and `:position(30, 20)` on a surface, a control and a native
      window, and asserts each reads back the exact pair — the round-trip is the whole claim, and it is what
      fails today at any scale above 1.0. Asserts `hafen.ui():at(m:x(), m:y()) == m:over()` (`==` is
      identity on an interned widget), proving the read pair and the hit-test pair are one space. Prints
      `hafen.ui():scale()` beside a bare `hafen.ui():button():size().y`, asserted equal to the integer the
      suite hard-codes from the art — the *same* integer at every scale, which is what makes a second run a
      proof rather than a repeat. Refusal: `hafen.ui():scale(1.5)` raises, naming
      `hafen.client():options():interface():scale(v)` as the way to write it.
      `[manual]`: run once at Interface scale 1.0 and once at 1.5, restarting between — expect two identical
      summaries, differing only in the printed scale.

- [x] **058.2 — the draw surface measures the same way, and an addon's PNG is design-sized.** Every
      coordinate `LuaGOut` reads (`text`, `atext`, `rect`, `frect`, `line`, `poly`, `prect`, `image`,
      `aimage`, `resource`) goes through `Px.in`; `LuaHudOverlay`'s `w, h` and `LuaGobOverlay`'s `sx, sy`
      through `Px.out`. `g:image` blits `UI.scale(img.tex)`, so an asset's own pixels are design pixels —
      the rule `hafen.vr` sprites already follow. `g:resource` is the trap in the other direction: its
      texture comes from `Resource.Image.scaled()` and is already device-sized, so only its explicit `w, h`
      box converts. `LuaWidgetEntity`'s `:screen(wx, wy)` and its inverse move with them.
      *Its suite* builds a window of a known design size and, inside its `Draw`, asserts `ev:w()`/`ev:h()`
      equal that size — the setter and the callback are then provably one space, which a device-pixel
      `ev:w()` breaks. Asserts an overlay's `onDraw(g, w, h)` matches `hafen.ui():root():size()`, and that
      `:screen()` and its inverse compose to the identity on a standing panel. Refusal, inverted: the draw
      verbs stay forgiving — `pcall` a `g:resource("gfx/nosuch", …)` and a `nil` image handle and assert
      both **succeeded**, since the conversion must not turn a silent no-draw into a throw.
      `[manual]`: at scale 1.5, the addon's own PNG is the same visual size as the client's icon beside it,
      and a `g:rect` traced at `0, 0, ev:w(), ev:h()` sits on the window's edge with no gap.

- [x] **058.3 — the stylesheet says design pixels too.** `Layout.Anchor`'s parsed `offset`; `SkinDeco`'s
      `pad`, which is added to `Window.dlmrgn`/`dsmrgn` and is today the only unscaled term in that sum;
      `Chrome.Border`'s four slice insets, cut in the image's own — now design — pixels, with the *draw*
      carrying the scale and the existing slice-vs-image validation unchanged; and `Controls`'
      `:rowHeight(n)` / `:cell(w, h)`, whose stock defaults read back in design.
      *Its suite* installs a sheet giving a window `position(40, 200)` and `size(300, 220)`, asserts both
      read back exactly, then `sheet:drop()` and asserts the window is byte-for-byte where it was found —
      the restore is exact only if the recorded stock value never round-tripped through design. Anchors a
      second window at `offset = {-8, -8}` from the screen's bottom-right and asserts its `rootPos()` plus
      `size()` lands 8 design px inside `hafen.ui():root():size()`. Sets `:rowHeight(18)` on a list and
      reads 18 back. Refusal: `rule:pad(-1)` still raises naming `>= 0`, and a slice bigger than its image
      still raises naming the image's size — the conversion must not swallow either check.
      `[manual]`: at 1.5, a themed `border` reads at the weight of the stock chrome beside it, not thinner.

- [ ] **058.4 — a control keeps the height its art gives it, a container packs, and the workarounds go.**
      `:size(w)` — one number — sets the width and leaves the height to the art; the `!a.arg(2).isnil()`
      branch that throws today becomes that write. A two-number write below a new `Owned.Control.minsz()`
      (`Button.hs`, `CDropdown`'s arrow box, `CEntry`, `CCheck`) raises. `pack()` drops its
      `content.widget() != w` guard and sizes any owned widget to its content, window or bare. Deletes
      `addons/timers/`'s `measure()`, `px()` and `centred()` (its *text* rulers stay — font metrics are not
      linear), and the control heights in `stockfilter` and `hello`.
      *Its suite* gives a button `:size(80)` and asserts `:size()` reads `{80, <the art's design height>}` —
      the dimension it never named, which is the whole claim; then `:pack()`s a window holding three rows of
      controls and asserts its content box equals the design sum, with no measuring anywhere in the suite.
      Refusal, three of them: `button:size(80, 4)` raises naming the minimum height *and* `:size(w)`;
      `dropdown:size(80, 4)` raises the same way, which is the 040 defect closing; `window:size(160)` raises
      naming `:size(w, h)` or `:pack()`, a surface having no art to ask.
      `[manual]`: at 1.5, every button's bottom border is drawn whole, no two rows overlap, and `:stockfilter`
      and `:timers` read exactly as at 1.0, only larger.
