# 015-widget-introspection — Spec

## What & why
**Generic, read-only widget-tree introspection** (D-041/D-042): walk ANY client widget tree
to arbitrary depth from Lua via opaque `WidgetNode` handles, and hit-test coordinates the
way the engine's own pointer dispatch does — the WoW `/framestack` enablers. Lets a
maintainer build a window adapter (a barter stand, any server window) in pure Lua instead
of a new Java adapter per window. Ships the `widgetstack` inspector addon.
Design: [design/20-widget-introspection.md](../design/20-widget-introspection.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.ui.root()`/`node(id)`/`model:node()` → a `WidgetNode` with `:type()` (climbs
      past anonymous subclasses to the nearest named class), `:id()` (server id, nil for
      client-only), `:children()`, `:parent()`, `:pos()`, `:size()`, `:visible()`,
      `:text()` (a single best-effort type switch), `:walk(fn)` (prune on false),
      `:same(other)` (reference identity). Dumped a live Barter Stand's full nested tree.
- [x] Nodes are transient GobRef-style handles — NOT owned-registry entries (a deep walk
      mints thousands); liveness checked per access (`hasparent(ui.root)`), stale nodes
      null their widget ref (no pinned subtrees) — no teardown hook, no leak by construction.
- [x] `hafen.ui.mouse()` + `hafen.ui.at(x,y)` + `node:at(coord)` + `node:rootpos()` — the
      hit-test mirrors `PointerEvent.propagation` + leaf `checkhit` (topmost-first,
      visibility, scroll-aware `xlate`, custom hit shapes — NOT a naive rect test); a point
      over nothing resolves honestly to root.
- [x] `widgetstack`: live under-cursor stack + green outline, the `:same` per-frame guard
      (rebuild counter frozen while the cursor rests), Ctrl+Shift+F freeze, and the v0.2.0
      click-to-inspect cascaded Inspector (pure Lua over the shipped surface); `:reload`
      leaks nothing.
- [x] Acting stays gated: read a server-bound `:id()` → `hafen.act.raw` (no new gate).

## Out of scope
- A native-widget `onMouseOver` (invasive, poll covers it — D-042); a built-in `:inspect`
  (the addon covers it); writes of any kind.

## Context files
- `design/20-widget-introspection.md`; decisions D-041/D-042 in `../decisions/widgets-ui.md`
- `src/io/brodgar/addon/LuaWidgetNode.java`, `UiApi.java` (post-split home — nodeHandle/
  hitTest/nodeText/nodeType)
- `src/haven/Widget.java` — children/wdgid/xlate/checkhit/rootpos/hasparent (the backings)
- `docs/addons/api/ui.md` — shipped surface
- `addons/widgetstack/` — the framestack/inspector example
- `../008-widget-replacement/` (models this complements), `../010-write-actions/` (`act.raw`)
