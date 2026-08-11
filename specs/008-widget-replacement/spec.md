# 008-widget-replacement — Spec

## What & why
The WoW "replace native UI" capability (bag addons, unit frames): addons can **observe** every
server widget as the client builds it, **adopt** one by id as a hidden but fully-live *model*
(the "wrap, don't reimplement" golden rule, D-009 — the real widget keeps receiving the server
protocol; the addon never re-implements it), and **replace** it wholesale — find by descriptor
(D-024), hide the native window, present a custom view, restore on teardown.
Design: [design/08-widget-replacement.md](../design/08-widget-replacement.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.ui.onWidgetCreate(fn)` fires for every server widget at placement with the full
      descriptor `{id,type,place,caption,parentType}` (login burst + a live-opened cupboard
      with its caption); observe-only; register from the file body to catch the login burst.
- [x] `hafen.ui.adopt(id)` → a model handle: `:hide/:show/:visible/:raw/:items()` +
      `:onItemAdded/:onItemRemoved/:onDestroy`. The golden rule verified live: a ground
      pick-up **while the grid was hidden** fired `onItemAdded` — delivery is by id, not
      visibility (the headless model).
- [x] `hafen.ui.replace(type, opts, fn)` — the Phase-3 DoD: Ctrl+Shift+I in the `bags` addon
      replaced the **already-open** inventory (the scan path — exactly the `:reload` gap 3b
      flagged) with a custom view drawing the real items at their real cells; toggle-off,
      closing the view, and disable+`:reload` all restored the stock inventory at its
      **original** visibility; `hello` regression intact.
- [x] Teardown un-hide: `teardownModels` restores any hidden widget/wrapper (recorded
      original visibility — `invwnd` is hidden-by-default and must return to hidden).

## Out of scope
- Item **mutating** verbs from the model (`take`/`drop`/`transfer`/`use`) — gated Phase-4 tier
  (D-010/D-025) → [010-write-actions](../010-write-actions/spec.md).
- Factory-override seam (A, `Widget.types`) and resource-`/`-named widget types; intercepting
  the client's own Tab/menu re-show; item icons in views (needs `g:image` → 012).
- The map window (client-side `MapFile` subsystem, not a server widget — audit B3) → 009.

## Context files
- `design/08-widget-replacement.md` — golden rule, seams, replace sketch
- `src/haven/UI.java` — the two 3a seams (`NewWidget.run`/`AddWidget.run`), `getwidget`
- `src/haven/GameUI.java` (`addchild` place routing, `maininv`), `Window.java` (`Hidewnd`)
- `src/io/brodgar/addon/LuaWidgetObserver.java`, `LuaModel.java`, `LuaReplacer.java`,
  `UiApi.java` (post-split home)
- `docs/addons/api/ui.md` — shipped surface
- `addons/bags/` — the dormant replacement example (Ctrl+Shift+I)
- `../003-widget-tree-reads/` (poll patterns), `../006-custom-ui/` (views), `../007-hooks-hotkeys/` (hotkeys)
