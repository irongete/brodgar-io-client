# 007-hooks-hotkeys — Tasks

- [x] 007.1 — Input hooks (L1): `hafen.hook.input(target, event, fn)` over `Widget.listen`,
      `ev:preventDefault()` = consume; map-lock demo.
- [x] 007.2 — Action hooks (L2): `hafen.hook.action(msg, fn)` at the `UI.wdgmsg` split
      (`rawWdgmsg`), resolved args + `resend`/`send`; `holdsLock` threading; move-intercept DoD.
- [x] 007.3 — Message hooks (L3): `hafen.hook.message(msg, fn)` at `UiMessage.run`,
      swallow/`rewrite`; shared `LuaMarshal`; vitals-freeze demo.
- [x] 007.4 — Global hotkeys: `hafen.key.bind(name, defaultKey, fn)` over
      `AddonRoot.globtype` + persisted `KeyBinding`; Ctrl+H toggle demo.
- [x] 007.5 — Keybind-panel integration: per-addon sections in `OptWnd.BindingPanel`
      (`describeKeyBinds`) + the client-wide `KeyBinding.set` exclusivity fix.
