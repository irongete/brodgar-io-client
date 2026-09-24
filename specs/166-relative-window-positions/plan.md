# 166 — Plan

## Approach

**One class holds the rule: `io.brodgar.ui.WndPos`.** The name uses upstream's own words (`savewndpos`, `wndc-*`).

- **The rule.** `frac(c, psz, wsz)` returns `(fx, fy)`: per axis `c / (psz − wsz)`, clamped to
  `0..1`. A gap of `UI.scale(10)` or less to an edge counts as the edge. When free space is `<= 0`, the
  axis keeps the value it was given. `place(f, psz, wsz)` goes back the other way, with `Math.round`.
  Tasks 2 and 3 call both.
- **The value.** `fx/fy` by `Double.toString`, read raw with `Utils.getpref(key, null)` — a `/` is a
  fraction, `NxM` today's pixels, anything else (NaN, Infinity) absent — and written with `Utils.setpref`.
- **The registry.** A weak map: each top-level `Window` → its fraction, the `c` the rule last put it at,
  the parent size then, and whether it follows. A window an addon built (`AddonManager.built(w)`, new:
  `Owned.of(w) != null`) follows only after the first move `sync` finds (ruling 7).
- **`sync`.** A `c` other than the recorded one means the window was moved. The fraction is then retaken
  from `c` against the recorded size. This only happens while no addon's layout holds the place:
  `AddonManager.posHeld(w)`, new, reads `UiApi.movedOwner(w, true) != null`.
- **Entry points.**
  - `load(parent, w, key, def)` returns the `c` to add a window at, and registers it.
  - `save(key, w)` runs `sync`, then writes.
  - `stock(w)` returns the user's place at the current size and records it as placed.
  - `relayout(parent, was)` syncs every `Window` child no addon holds and places each one that follows
    at the new size. A window seen for the first time takes its fraction from `c` against `was`, or
    against the new size when `was` has no area. It skips an unpinned `GItem.ContentsWindow`. It hands
    every window it moved to `AddonManager.onWidgetResized(w)`. On the next step that runs
    `Layout.dispatchResized`, then `Layout.moved`, so an `anchor{ to = window }` follows (ruling 8).

**Task 1: the client's windows.**

- `GameUI.resize` keeps `was = this.sz` and calls `WndPos.relayout(this, was)` just before
  `AddonWidgets.relayout(this)`.
- `addchild` places `inv`, `equ`, `chr`, `map`, `menu` (action search), `craft` and `misc` (`id`) through
  `WndPos`. The constructor's `zerg` registers as pending and is placed by the first pass. `makewndc`
  holds the parsed value.
- `savewndpos0` also saves `srchwnd` and a live `iconwnd`. Closing `iconwnd` saves it too. `cdestroy` and
  the crafting window's `destroy` call `save`.
- `ContentsWindow` resolves its place in `added()` and `wndshow`, and saves from `tick`. It gains a
  `// addon:` read, `pinned()`, true when `st == "wnd"`.
- `UiApi.stockPos` answers `WndPos.stock(w)` for a registered window. The stock `Layout.applyHalf` gives
  back becomes the user's relative place now: the position half of the 062 `ROADMAP.md` line.
- `AddonWidgets.stockc` and `AddonManager.stockPos` lose their callers and are deleted.

**Task 2: hand places on an addon's widgets.**

- `LuaWidget.Moved.hand` holds a fraction, or `null`. It is set from the landed `c`, when the widget's
  parent is a `GameUI` or its tree's root, by:
  - `Gesture.write` (a drag);
  - `LuaWidget.levelFollows` (a title-bar drag);
  - `rememberApply` (the restored pixels, until task 3).

  `widget:position(x, y)` clears it.
- `Layout.reapply(parent)` rewrites each hand level as `Anchor.at(Px.out(WndPos.place(hand, …)))` at the
  parent's size, then applies it. The level stays plain, so the widget's own resize moves no origin and
  `widget:style()` still says `position`.
- `Layout.dispatchResized` on a root runs `reapply(root)`.
- `UILoop` calls `WndPos.relayout(layer.root, was)` after `layer.root.resize(sz)`.

**Task 3: `remember` keeps the fraction.**

- `StoreApi.Placement` gains the fraction. `rememberLanded` and `rememberCapture` store it for a widget
  on the screen; a widget anywhere else keeps pixels. `rememberApply` puts it back as the hand level.
- `ClientDb` writes `x` = TEXT `fx/fy` and `y` = `NULL`, and reads that row back as the fraction.
- `placementsJson` includes it.

## Files to create/modify

- New: `src/io/brodgar/ui/WndPos.java`, `docs/client/window-positions.md`, a suite per task.
- Task 1: `GameUI`, `GItem`, `AddonWidgets`, `AddonManager`, `UiApi`; `gameui-windows.md`,
  `docs/client/README.md`, `native.md`, `style/geometry.md`.
- Task 2: `LuaWidget`, `Gesture`, `Layout`, `UiApi`, `UILoop`; `native.md`.
- Task 3: `StoreApi`, `ClientDb`, `LuaWidget`; `native.md`.

## Risks & gotchas

- **Transparency (ruling 8).** An existing addon sees a change only after a screen resize: `:position()`
  of a moved window, `:position(nil)` or a dropped rule landing relatively, a hand place following.
  Nothing refuses, `widget:style()` still says `position`, followers ride the geometry seam, and a
  pre-feature build reading `fx/fy` or the TEXT row falls back to its default place.
- **Verified headlessly** (`build/classes`, `-Dhaven.prefs.<key>=`): a pre-feature `Utils.getprefc`
  gives its default for `0.5/1.0` and throws `NumberFormatException` for `0.5x1.0` (it catches only
  `SecurityException`); `String.format` under `es-ES` writes `0,500`; `Double.toString` round-trips;
  INTEGER affinity stores `1.0` as `1`; a pre-feature `ClientDb.coord()` returns `null` for a `NULL` `y`.
- **No size yet.** `GameUI.<init>` adds `zerg` before `added()` runs `resize(parent.sz)`.
  `ContentsWindow`'s constructor reads its place before it has a parent.
- **`ContentsWindow` in `hover` sits beside its item.** The pass leaves it alone. Its `tick` writes on
  every change of `c`.
- **Writers keep `onscreen()`.** Only `leavingscreen` skips it.
- **Clamps stay upstream's** (`GameUI.fitwdg`, `UiApi.fitc`); `0..1` never trips them: `c` stays in
  `[0, free]`, or `[free, 0]` (still `>= 100 − w`).
- **`Layout.applyHalf` reads `UiApi.stockPos` twice**: at the first touch and at the drop. The hold still
  stands at the drop, so `WndPos.stock` must never take a fraction from an addon's place.
- **Suites shrink the HUD** with `hud:size(w, h)` (reaching `GameUI.resize`), restore it with
  `hud:size(nil)` on every path, and assert within 2 design px (scale 1.5 rounds). Pre-check the pair.
- **`ChatUI.move` takes the chat's base** (a 062 `ROADMAP.md` line). A hand level on the chat inherits
  that.
- **`gameui-windows.md` is at its ceiling.** Two of its sections move to the new page.

## Discarded alternatives

- **A fraction of the whole screen (`c / P`)**: a window glued to an edge comes unstuck as the screen
  grows.
- **Nine points plus a pixel offset (WoW's frame points)**: an anchor vocabulary, which the maintainer ruled
  out.
- **Relative in memory, pixels on disk**: converting after a restart needs the screen size from save time.
- **Stateless re-placement from the old size to the new**: rounding drifts over a border drag, and a screen
  smaller than the window loses the place.
- **New keys (`wndr-*`)**: ruled out by the maintainer. The slash makes the value describe itself.
- **`0.5x1.0`**: a pre-feature `Utils.getprefc` throws on it, while it returns its default for the slash.
- **`String.format`**: locale-dependent.
- **REAL fractions in `placements.x`/`y`**: INTEGER affinity turns `1.0` into `1`, which an old reader
  silently takes for a pixel.
- **New `fx`/`fy` columns**: a schema change. The maintainer chose the TEXT-and-`NULL` row.
- **A new `Layout.Anchor` kind for the fraction**: it would move the origin when the widget resizes itself
  (the grip, `:size`), and `widget:style()` has no way to spell it.
- **Hooking `Widget.move`**: it runs on every drag of every window (062's reason).
- **Re-placing on the window's own resize**: the corner grip would run away from the pointer.
- **Letting every window with no level follow, an addon's too**: an addon's window would move without its
  author asking. Ruled out once the client was released (ruling 8).
- **Minting a hand level on the title-bar drag of an addon's window that has none**: `:position(nil)`
  would then move a window its addon never positioned.
