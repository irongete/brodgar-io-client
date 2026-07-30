# 006-custom-ui — Tasks

- [x] 006.1 — Custom windows/widgets + the `g` draw wrapper: `hafen.ui.window/widget`,
      `LuaWidget` forwarding (draw/tick/mouse, truthy=consume), handle ops, bridge-owned
      teardown; `OnEnterWorld` gated on the HUD being attached to `ui.root`.
- [x] 006.2 — HUD overlays + gob overlays: `hafen.ui.overlay(fn)` (re-queued afterdraw above
      the HUD) + `hafen.ui.gobOverlay(filter, fn)` (`LuaGobOverlay`, shared per-gob attrib +
      throttled sweep); `LuaGOut` extracted as the shared draw surface.
