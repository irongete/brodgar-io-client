# 007-hooks-hotkeys — Spec

## What & why
The **interception layer**: addons can intercept a client widget's input before the widget sees
it (L1), an outbound player action before it reaches the server (L2 — args already resolved,
cancel/resend/rewrite), and an inbound server update before the widget applies it (L3 —
swallow/rewrite) — plus **remappable, persisted global hotkeys** integrated into the client's
own keybind panel (WoW-style). The JS `preventDefault` model at three seams.
Design: [design/13-hooks-and-interception.md](../design/13-hooks-and-interception.md).

## Acceptance criteria (verified in-game)
- [x] `hafen.hook.input(target, event, fn)` (L1, zero core edit — `Widget.listen`): `hello`'s
      map-lock cancels MapView clicks (character doesn't move) and only observes when off;
      preventDefault is also stopPropagation at this seam (one canonical consume).
- [x] `hafen.hook.action(msg, fn)` (L2, the `UI.wdgmsg` split): the move-intercept DoD — a
      map click is cancelled, the resolved **world destination** logged (`ev.args[2]`,
      impossible at L1), then `ev:resend()` re-issues it and the character still moves;
      `resend`/`send` bypass the hook chain (no loops) and imply preventDefault; map-lock ON
      pre-empts it (L1 cancels before any `"click"` exists).
- [x] `hafen.hook.message(msg, fn)` (L3, `UiMessage.run`): the vitals-freeze DoD — swallowing
      `IMeter "set"` freezes the real HUD bars (and suppresses `VitalsChanged`); toggling off
      thaws to true values; `ev:rewrite(t)` applies with new args; preventDefault wins over
      rewrite, last rewrite wins.
- [x] `hafen.key.bind(name, defaultKey, fn)`: Ctrl+H toggles the window; focused text fields
      naturally suppress typed keys; client bindings win (AddonRoot walked last); binding is a
      real `KeyBinding` (`addon/<id>/<name>`) — remap persists across restarts.
- [x] Keybind panel: one section per addon (manifest name) in Options → Keybindings with the
      standard capture button; unbound-by-default rows assignable from scratch; **keybinding
      exclusivity** (client-wide fix): assigning a key steals it from any other binding.
- [x] All hooks/hotkeys bridge-owned: `:reload` deafens/unregisters cleanly (engine widgets
      survive a reload — a leaked listener would fire into a dead env); `hello` v0.15.0→v0.19.0.

## Out of scope
- Hook priority/ordering (D-021) and post-hooks (→ ROADMAP); widget-*type* input targets
  (needs the 008 factory seam); message-*name* rewriting; key-up/repeat/chord bindings.

## Context files
- `design/13-hooks-and-interception.md` — the L1/L2/L3 ladder; `design/09-events-catalog.md`
- `src/haven/UI.java` — the `wdgmsg` split (`rawWdgmsg`) + the `UiMessage.run` hook
- `src/haven/Widget.java` (`listen`/`handle`/`globtype`), `KeyBinding.java` (exclusivity fix),
  `OptWnd.java` (`BindingPanel` addon sections)
- `src/io/brodgar/addon/LuaInputHook.java`, `LuaActionHook.java`, `LuaMessageHook.java`,
  `LuaKeyBind.java`, `LuaMarshal.java`, `HookApi.java` (post-split home)
- `docs/addons/api/hooks.md`, `keys.md` — shipped surface
- `../003-widget-tree-reads/` — the uimsg tap L3 shares; `../006-custom-ui/` — callLua/consume conventions
