# 166 — Tasks

Every suite does three things:

- It shrinks the HUD with `hud:size(w, h)` (`hud` = `session:ui():match("@GameUI")`) and gives it back
  with `hud:size(nil)` on every path, `pcall` included.
- It predicts places with the rule in Lua (`plan.md`) and asserts within 2 design px.
- Each timed prompt is one `[manual]` line: one action, scored by the suite on the next line within 20 s.

- [x] **166.1 — The client's windows keep their place relative to the screen.**
      - Add `io.brodgar.ui.WndPos`: the rule, the `fx/fy` value, the registry with its *follows* flag,
        and `load`/`save`/`stock`/`relayout` (`plan.md`).
      - `GameUI.resize` runs the pass before `AddonWidgets.relayout`.
      - Every read and write of `wndc-inv/-equ/-chr/-zerg/-map`, `wndc-misc/<id>`, `makewndc` and
        `cont-wndc/<id>` goes through `WndPos`. `wndc-srch` and `wndc-icon` are written too.
      - `GItem.ContentsWindow.pinned()` is added.
      - `AddonManager.posHeld` and `AddonManager.built` are added.
      - `UiApi.stockPos` answers `WndPos.stock`.
      - `AddonWidgets.stockc` and `AddonManager.stockPos` are deleted.
      - Docs:
        - `native.md`: the `widget:position(nil)` row, *The disk is the user's*, *Re-layout*.
        - `geometry.md`: *Dropping the rule*.
        - `docs/client/window-positions.md`: new, taking over the position-store and `GameUI.resize`
          sections of `gameui-windows.md`, and indexed in `docs/client/README.md`.
      - Pre-check headlessly:
        - `frac` gives the magnet at ≤ 10 px, the clamp past an edge, and keeps the fraction when free
          space is ≤ 0;
        - `760x780`, `0.5/1.0` and garbage read correctly through `WndPos`;
        - `hud:size` reaches `GameUI.resize`.

      *Its suite* builds three windows on the HUD, A, B and C, none with a position level. Two timed
      prompts come first: drag A against the right edge, then drag B half past the bottom edge. Then a
      HUD resize must leave:
      - A glued to the right edge (the magnet, and an addon window follows once moved);
      - B whole at the bottom (the clamp);
      - C at its pixel (criterion 6: an addon window nobody moved).

      After that:
      - Inventory, Equipment and Character Sheet keep the fraction computed from their places
        (criterion 1).
      - Back at full size, each is at the pixel it left (criterion 2).
      - With the HUD narrower than the Character Sheet and then back, the sheet is where it was.
      - Equipment under `:position(40, 40)` keeps that pixel. `:position(nil)` then lands it at its
        fraction of the shrunk size (criterion 3).
      - A window D, held by a sheet rule `anchor{ to = <Inventory>, at = "topright" }`, is still on that
        corner a tick after the pass moves the Inventory (criterion 9).

      `[manual]`, each on its own line:
      - The first start of this build opened every window where the previous build left it
        (criterion 5).
      - Restart in a window of another size and open the inventory: it is at the same relative place
        (criterion 4).
      - Move the action search window, restart and open it: it is where you left it.

- [x] **166.2 — A place the user's hand gives an addon's widget follows the screen.**
      - Add `LuaWidget.Moved.hand`. It is set, for a widget whose parent is a `GameUI` or a root, by
        `Gesture.write`, `LuaWidget.levelFollows` and `rememberApply`. `widget:position(x, y)` clears it.
      - `Layout.reapply` rewrites hand levels from their fraction.
      - `Layout.dispatchResized` on a root runs `reapply(root)`.
      - `UILoop` runs `WndPos.relayout(layer.root, was)`.
      - Docs: `native.md`, *A drag writes your `:position` level* and *Re-layout*.

      *Its suite* first checks criterion 6 automatically:
      - A HUD window of its own at `:position(x, y)` is remembered, destroyed and rebuilt under the same
        name. It comes back as a hand place and keeps its fraction through a HUD resize.
      - A window C at `:position(40, 40)` keeps its pixels.

      Then four timed prompts:
      1. Drag C by its title. C then keeps its fraction through a HUD resize.
      2. Drag the grey `:draggable` square in the layer.
      3. Drag the layer window titled `166.2` by its title.
      4. Resize the game window. The square and window `166.2` must keep their fractions, while a layer
         window nobody moved stays at its pixel (criterion 8).

      `remember(nil)` deletes the row at the end.

- [ ] **166.3 — `widget:remember` keeps a place relative to the screen.**
      - `StoreApi.Placement` gains the fraction. `rememberLanded` and `rememberCapture` store it for a
        widget on the screen, and `rememberApply` puts it back as the hand level.
      - `ClientDb` writes `x` = TEXT `fx/fy`, `y` = `NULL`, and reads it back.
      - `placementsJson` carries the fraction.
      - Docs: `native.md`, *What is saved*.
      - Pre-check headlessly: the `ClientDb` round trip of a fraction row and of a pixel row, on a scratch
        file.

      *Its suite* checks criterion 7:
      - A HUD window of its own is remembered at full size and destroyed, then rebuilt under the name
        with the HUD shrunk. It lands at its fraction of the shrunk free space, not at the old pixels.
      - A widget remembered inside a window of its own comes back at the same pixels in it.
      - After `remember(nil)`, a fresh window under the name keeps its default place.
