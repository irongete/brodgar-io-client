# 036-ui-layout — Plan

## Approach

**Move the widget for real; keep the client's memory of it clean.**

1. **Move `c`/`sz` for real, do not offset at draw time.** Everything downstream — drawing, hit-testing, drag,
   `fitwdg`, the deco — already works off `Widget.c`. An offset applied at paint time would make the widget draw
   somewhere it cannot be clicked, which is the 030 hit-testing lesson in reverse. So the layer writes the same
   fields the user's own drag writes.
2. **The one real problem is persistence, and it is one seam.** `GameUI.savewndpos()` writes `wndc-inv`,
   `wndc-equ`, `wndc-chr`, `wndc-zerg` and the map window through `Utils.setprefc`, called from
   **`GameUI.dispose()`** — at logout. If we move `invwnd.c` and leave it moved, the client persists **our**
   position as the user's, and uninstalling the addon leaves those windows displaced forever. The fix is the
   discipline 029/031 already built: **record the stock value at first touch and restore it before the client
   reads it** — one `// addon:` line at the top of `savewndpos`, plus the existing teardown path. The record is
   `Addon.hiddenNative`'s shape: *what it was before we touched it*.
3. **`anchor` is the concept; `pos` is its degenerate case.** An absolute `pos` is an anchor to the root's
   top-left with that offset, so there is one resolution path, not two. An anchor names a target (the screen or
   another widget), a corner, and an offset; the **anchor** is what a rule keeps and the position is derived.
   That is what survives a resolution change, and it is why `pos` alone would have been a trap.
4. **Resolve on events, never per frame.** Re-derive when: the widget appears (030's `ui.on` already fires for
   what is open too, D-068), the root resizes, the UI scale changes, or the anchor's target moves. Nothing
   about layout belongs in the draw path.
5. **Let `fitwdg` keep doing its job.** The client already clamps a window onto the screen; re-implementing that
   would give two answers to "is this on screen". A rule that would place a widget outside is handed to the
   client's own clamp, and the *documented* behaviour is whatever `fitwdg` does — measured, then written down.
6. **The fold is 034/035's, unchanged.** `pos`/`size`/`anchor` join `Fonts.combine` per property (D-076); the
   geometry **verbs** become the hand-named top level of that same fold (D-077), which is what turns 029's
   "error — a later feature" row into a working one without a second mechanism.

## Files to create / modify

- `src/haven/GameUI.java` — **one `// addon:` line** in `savewndpos()`: restore addon-moved widgets before the
  prefs are written. No other core edit expected.
- `src/io/brodgar/addon/LuaWidget.java` — the geometry writes stop refusing on a BORROWED widget; the
  moved-widget record (stock `c`/`sz`) and its restore; `style()` reports the three new properties.
- `src/io/brodgar/addon/Sheet.java` — `pos`/`size`/`anchor` validated as properties (an unknown one still
  errors, D-072); `src/haven/Fonts.java` — the fold carries them as opaque values, as `bg`/`border` already are.
- `src/io/brodgar/addon/UiApi.java` — the re-derive hooks (root resize, UI scale) beside the existing seams.
- `docs/addons/api/ui.md` — the Widget table row that promises E is **corrected**; the property × key table
  gains the three; a new section on anchors; and the closing statement of what the skinning system covers.
- `addons/theme/` — a layout in its `theme.json`, and the demonstration that **an addon persists its own**
  layout through `hafen.store`; `addons/036-ui-layout.1..4/` — one suite per task.
- `specs/codebase/gameui-windows.md` — extended with `savewndpos`/`getprefc` (the client's own position store).

## Risks & gotchas

*(prior art: `learnings/ui-widgets.md`, `specs/codebase/gameui-windows.md` — grepped, not read whole)*

- **The disk write is the whole feature's risk.** Everything else is reversible in memory; a bad `setprefc` is
  not, and the user only finds out after uninstalling. Test it the way it breaks: move, log out, disable, log
  in, read the numbers.
- **A window's `c` is relative to its parent, not the screen.** An anchor to "the screen" must convert, and
  `GameUI` is not the root. Getting this wrong puts windows off by the HUD's offset — subtly, so it will look
  like a rounding bug rather than a coordinate-space bug.
- **`UI.scale` is in play**: every offset a rule states is logical px and must pass through the same scaling the
  client uses, or a theme is correct on one DPI and wrong on another.
- **A write on a stale widget is a silent chaining no-op** (029, D-065's neighbour) — keep that; a layout rule
  that throws when a window closed mid-frame would be worse than one that does nothing.
- **Never re-lay-out inside a draw** (035.1's `chdeco` lesson: it destroys a widget and re-lays out). Layout
  changes belong in `tick` or an event.
- **`fitwdg` may move the widget after we do.** Read back the final `c` rather than assuming ours stuck, or the
  suite's assertions will disagree with the screen.

## Discarded alternatives

- **Offsetting at draw time instead of moving `c`** — rejected: the widget would draw where it cannot be
  clicked or dragged.
- **Writing our position into the client's prefs so it "persists"** — rejected: that *is* the bug; the client's
  store belongs to what the user placed by hand.
- **A profile/layout store in the engine** — rejected: `hafen.store` (account scope) already exists and a sheet
  is plain data, so it would be a second store beside the one addons have.
- **Re-implementing on-screen clamping** — rejected: `fitwdg` already answers that question, and a second answer
  is a disagreement waiting to happen.
- **`pos` and `anchor` as independent properties** — rejected: two resolution paths for one question; `pos` is
  the anchor whose target is the root's top-left.
