# 015-widget-introspection — Plan

> History: this work appears in git history and `learnings/` tagged **W1, W2** (the
> W-series). W1 shipped no example addon (maintainer's request); `widgetstack` came with W2.

## Approach
- **Facade-safe lazy handles**: a node is a table of closures over a Java `LuaWidgetNode`
  holding the widget inside Java, plus the opaque metatable-less userdata (`KEY`) for the
  `:same` round-trip (the LuaImage/LuaMarshal pattern — unforgeable, no reflection reach).
- **Transient by design** (deliberate P2 exception): GobRef-style — borrow the engine-owned
  widget, check liveness on EVERY access via `hasparent(ui.root)` (remove() nulls parent),
  null the ref once stale. No registry → nothing to tear down → `:reload` leak-free by
  construction.
- **`:type()` climbs anonymous subclasses** (Hafen builds masses of `new Button(){...}` —
  empty simple names; the first in-game run surfaced it); `:text()` is ONE volatile switch
  over the known public text fields (Label/Button/Window/TextEntry) — churn breaks that
  method, not addons.
- **The hit-test replicates the engine's own dispatch**: topmost-first (`lchild`/`prev`),
  `visible()` skip, parent `xlate` (scroll-aware), recurse child-local, leaf `checkhit` —
  a naive rect test lies inside Scrollports and custom hit shapes. `node:at` converts
  root→node-local via `rootxlate` so one `mouse()` value serves both entries.
- **Hover = polling + `:same`**: no native onMouseOver (WoW doesn't hook hover either);
  `widgetstack` caches the last leaf and bails early when unchanged — `:same` exists
  precisely because each `at()` mints a fresh handle and client-only leaves have no id.
- All accessors under `synchronized(ui)`; `:walk` callbacks through `callLua`, stale
  mid-walk nodes just stop descending.

## Files created / modified
- `src/io/brodgar/addon/LuaWidgetNode.java` — new (holder + resolve)
- `AddonManager.java` (→ `UiApi.java`) — root/node/mouse/at facades, `nodeHandle` (12
  accessors), `nodeLive`/`nodeType`/`nodeText`/`nodeWalk`/`hitTest`/`coordArg`
- `addons/widgetstack/` — new example (stack window + outline overlay + freeze hotkey +
  the v0.2.0 cascaded Inspector)
- No `haven` edits.

## Risks & gotchas hit (detail: learnings/ui-widgets.md)
- Anonymous-subclass blank type names (first in-game run).
- Models detect death by id-remap; nodes by parent-reachability — a hidden widget is alive,
  a removed one is not.
- Headless: `hitTest` is fully testable over a hand-built `Widget` tree (no GL) — 12 checks
  incl. the scrolled-xlate and checkhit-false cases.

## Discarded alternatives
- Registering nodes as owned resources — thousands per walk; transient is the right shape.
- A rect-based hit test — wrong under scroll/custom hit shapes.
- A native onMouseOver hook — invasive, buys nothing over the poll (D-042).
