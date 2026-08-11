# 008-widget-replacement — Plan

> History: this work appears in git history and `learnings/` tagged **3a, 3b, 3c** (Phase 3).

## Approach
- **Adopt-after-create (seam B), not factory override (A)**: no tampering with the
  package-private `Widget.types` registry, no off-monitor `create()` threading caveat, and it
  covers resource-published widgets too. The descriptor needs BOTH creation and placement —
  `type` is only known at creation (`NewWidget.run` records id→type), the rest only at
  placement (`AddWidget.run` builds `{id,type,place,caption,parentType}` and fires) — two
  one-line `// addon:` edits in `UI.java`.
- **The model** (`LuaModel`): `UI.getwidget(id)` is the public id→widget map both uimsg and
  wdgmsg route by. Hide = `Widget.hide()` — the widget stays bound to its id, so it keeps
  receiving the protocol (headless model). Poll-driven lifecycle (per tick, like buffs/study):
  server-destroy = id no longer maps to the widget; item add/remove = identity-keyed diff of
  `WItem` children. Read-only `:items()` snapshots (mutating verbs are gated, Phase 4).
- **Replace = find + adopt + hide-the-WRAPPER + view**, with TWO find paths: the creation
  path (matcher on `onWidgetPlaced`) for targets opened later, and a **scan at registration**
  for already-open targets — the `:reload` case, where no creation event will ever fire. The
  scan can't know the server type string → keys on the Java class (`inv`→`Inventory`…);
  `context="main"` short-circuits to the public `GameUI.maininv`. `nativeWindowOf` hides the
  enclosing `Hidewnd`, and teardown restores the **recorded original** visibility (invwnd is
  hidden-by-default — blind `show()` would be wrong).
- **Ownership**: view auto-destroys with the model (server destroy → `onDestroy` + view
  death); `:remove()` is a full live undo; teardown restores everything on reload/disable.

## Files created / modified
- `src/haven/UI.java` — two `// addon:` one-liners (the only Phase-3 core edits)
- `src/io/brodgar/addon/LuaWidgetObserver.java`, `LuaModel.java`, `LuaReplacer.java` — new
- `AddonManager.java` (→ `UiApi.java`) — observe/adopt/replace facades, `pollModels`,
  `teardownModels`/`teardownReplacers`, shared `descTable`
- `Addon.java` — owned lists `widgetObservers`/`models`
- `addons/bags/` — new dormant example; `addons/hello/` — v0.20.0/v0.21.0 (observer + Ctrl+B
  adopt demo)

## Risks & gotchas hit (detail: learnings/widget-replacement.md)
- The scan must NOT match on the parent: the main inventory's parent differs between the
  creation path and the live tree — key on class + criteria instead.
- `replace` hides the wrapper window but the client's own Tab toggle can re-show it (honest
  limit, noted; full intercept deferred).
- A live `:remove()` must UNDO (restore + destroy view), not just stop matching.
- `haven.Window` needs GL → wrapper-hide branches are in-game-verified; everything else
  headless (36 checks).

## Discarded alternatives
- Factory override (seam A) — threading caveat + covers less; deferred until a pre-emptive
  swap is actually needed.
- Action-capable item handles on the model — the gated tier owns writes (D-010/D-025).
- Folding `bags` into `hello` — a dormant dedicated addon keeps the harness clean.
