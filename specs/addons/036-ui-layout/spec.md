# 036-ui-layout — Spec

## What & why

**The debt this pays is written in the public contract.** Since 029, the Widget table in
`docs/addons/api/ui.md` has said that `:pos(x, y)` on a **native** widget is an **error** — *"moving a native
widget is layout, a later feature"*. E is that feature: it makes the promised surface work, it invents none.

```lua
hafen.ui.skin{
  ["window[title=Inventory]"] = { anchor = {to = "screen", at = "bottomright", offset = {-8, -8}} },
}
hafen.ui("window[title=Cupboard]"):pos(40, 200)   -- the same cascade, named by hand
```

**One cascade, not two mechanisms** — D-077 verbatim: *per-instance is a LEVEL of the cascade, not a mechanism
beside it*. `pos`, `size` and `anchor` join the sheet as properties, folded per property by the same
`Fonts.combine` (D-076) as `font`/`color`/`bg`; the geometry **verbs** stop erroring on a native widget and
become the top level of that same fold. Nothing new is addressed and nothing new is stored.

**Anchors are not optional**: an absolute position does not survive a resolution or UI-scale change, and a HUD
that drifts when the window resizes is worse than one that never moved. A rule anchors a widget to the screen
or to another widget with an offset; the anchor is what is kept and the position is derived.

### The risk that is worse than 031's, and it is verified

The client **already persists window positions of its own**: `GameUI.savewndpos()` writes `wndc-inv`,
`wndc-equ`, `wndc-chr`, `wndc-zerg` and the map window through `Utils.setprefc`, from **`GameUI.dispose()`** —
at logout. So a naive implementation that moves `invwnd.c` has the client save the **addon's** position as the
user's own preference, and uninstalling the addon leaves those windows moved **forever**: D-070's restoration
discipline defeated by a write to disk. The rule this feature adopts, and must prove: **an addon's layout is a
layer over the client's, never a write into it.** What the client saves at logout is what the user last placed.

### What this feature deliberately does not build

**Profiles.** `hafen.store` (account scope) exists, a sheet is plain data and a theme can already be JSON
(D-074), so a theme already persists its own layout and a profile system would be a second store beside the one
addons have. E ships the layer and the anchors; **saving a layout is an addon's own business**, shown by
`theme`. Feature **E**, the last of the run — its close states where the skinning boundary sits for good.

## Acceptance criteria — per `TESTING.md`; geometry is numeric, so this feature asserts nearly everything

- [ ] `w:pos(x, y)` / `w:size(w, h)` **work on a native widget**; `ui.md`'s table row is corrected in the same
      task that changes the behaviour.
- [ ] A sheet `pos`/`size` rule moves a matched native window, read back through `widget:pos()`/`:size()`, and
      **removing the rule restores the exact previous numbers** (the 035.2 method).
- [ ] **The hand-named level wins**: on a widget a sheet rule also names, the verb's value resolves, and
      dropping it falls back to the rule — one fold, asserted step by step.
- [ ] `anchor` holds a widget to a screen edge or to another widget: asserted by **changing the UI scale or the
      window size and re-reading `:pos()`**, which must track the anchor rather than stay put.
- [ ] **The client's own store is never written by us**: with a layout rule live, log out and back in with the
      addon **disabled** — the `wndc-*` windows sit where the user last put them by hand, not where the rule
      put them. The criterion this feature exists to satisfy.
- [ ] Nothing becomes unreachable: a rule placing a widget off-screen is clamped or refused (decide which, then
      document it), and `hafen.ui.at()` still finds every moved widget where it is drawn.
- [ ] A layout rule applies on the events that matter (widget appears, resolution/scale changes) and **not per
      frame** — measured with `hafen.client:profiling()`; and the docs state the **boundary of the skinning
      system**: what layout can and cannot reach, and what would be a new chapter rather than a gap.

## Out of scope

- **Profiles / a layout store** — an addon's own business (above); `theme` demonstrates it.
- Re-flowing a window's *contents*: moving a widget inside a native window, docking, grids.
- Animating a move, drag-to-configure UI, a layout editor; role/classifier changes; new sheet keys.

## Context files

- `docs/addons/api/ui.md` — the Widget row promising E, §"What each key accepts" (C2's geometry doctrine) and
  the `pad` precedent; `specs/addons/TESTING.md` — the suite format
- `src/haven/GameUI.java` — `savewndpos`/`dispose` (:894, :475), `fitwdg`, the `getprefc` reads at
  construction: the store this feature must not write into
- `src/haven/Window.java` — `c`/`sz`, the anim state machine, `chdeco`; `specs/codebase/gameui-windows.md`
  already covers both (031, extended by 035.4)
- `src/haven/Fonts.java` — the fold (`combine`, the frame) the new properties join
- `src/io/brodgar/addon/Sheet.java` — key/property validation; `LuaWidget.java` — the geometry verbs that
  currently refuse, and where the layer records what it moved
- `035-ui-chrome/` (D-078, the geometry-stays-stock assertions), `034-ui-stylesheet-tree/` (D-076/077),
  `031-window-lifecycle/` (D-069/070 — restoring what the user saw, against a client that also owns it)
- `learnings/ui-widgets.md` — **grep, never read whole**; `decisions/widgets-ui.md`, `architecture-api.md`
