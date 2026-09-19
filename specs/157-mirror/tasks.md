# 157 — mirror: tasks

- [x] **157.1 — `hafen.ui():mirror()`: a surface showing another widget's live picture.** Adds
      `MirrorWidget` (`Owned`, `Controls.Source`): a texture sized to its source's box, the flipped
      2D blit, the stock/rule chrome around it, and `renderAll(drawn, layer, out)`, the per-mirror
      offscreen pass issued from `UILoop.display` beside `drawSurfaces` — under the source tree's
      monitor alone, a `GTickEvent` first for a tree the frame did not tick. Adds the `mirror`
      builder to `UiApi`, the `MirrorWidget` branch of `Controls.source` (a non-widget, the mirror
      inside its own picture, the 2048 px ceiling) and of `LuaWidget`'s `size(nil)`, and
      `AddonManager.drawMirrors`, and `PView.draw`'s once-per-frame seam (a second draw into the
      frame's `Render` only resolves: `GLDrawList.draw` is once per frame). Ships `docs/addons/api/ui/mirror.md`, the three index rows, the
      impact-set lines in `native.md` and `widget.md`, the `docs/client/widget-draw.md` rows on
      `PView.draw`/`resolve` and `GTickEvent`, and `tools/docverbs.py`'s `mirror` receiver. Covers
      acceptance criteria 1–7.
      *Its suite* builds a `hafen.ui():widget()` with a `Draw` handler as the source and a mirror
      of it, then asserts: `:type()` is `MirrorWidget`; `:source()` is `nil` before the write and
      `==` the source after; the box equals the source's after `:source(w)`, keeps a `:size(w, h)`
      across a second `:source(w)`, and returns to the source's on `:size(nil)`; the source's
      `:parent()`, `:position()`, `:size()`, `:visible()` and `:exists()` are unchanged after the
      write; a mirror parented into the drawn session's HUD with the HUD's `@Avaview` as its source
      answers `:source()` `==` that widget; `hafen.ui():hit()` over the mirror's own place answers
      the mirror; destroying the source makes `:source()` read `nil` while the mirror `:exists()`;
      `:destroy()` on the mirror leaves the source `:exists()`; two mirrors of one source both read
      it back. Refusals, each asserted with the words the message must carry: `hafen.ui():mirror(1)`
      — "takes no arguments"; `:source("x")` — "a Widget"; `:source(mirror)` — "its own picture";
      `:source(parent_of_mirror)` — "its own picture"; `:source(w)` with a 3000 px source —
      "2048"; `:size(10)` — "widget:size(w, h)".
      `[manual]`: with two characters logged in, `:t157` builds a mirror of each session's HUD
      portrait in the layer — expect: both portraits drawn side by side, the one nobody is looking
      at moving like the other; the HUD's own portrait still in its corner.
