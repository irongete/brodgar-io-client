# 169 — key button reach: tasks

- [x] **169.1 — Under client.settings a key button joins another addon's hotkey and the client's bindings.**
      `CKeybinding.hotkey` asks `AddonManager.permitted(owner, CLIENT_SETTINGS)` after the own-prefix branch: another
      addon's id joins the `KeyBinding` of a live `LuaKeyBind` in `HookApi.keyBinds`, a client id joins
      `KeyBinding.get(id)`; under the permission, a foreign hotkey nobody declares and an id the registry lacks are
      refused naming why. `CKeybinding.live` answers for any `addon/` id through `HookApi.keyBinds` and for a client
      id while bound. The key-button pages, the controls table, `writes.md`, `keybindings.md` and the `client.settings`
      row of `permissions.md` say whose bindings it joins. Criteria 1–4.
      *Its suite* declares `client.settings` and `api_version` 1.1. It binds a button to `inv` and asserts `:bind()`
      is that Binding and `:value()` its key; it walks `keybindings:binding():list("addon/")` for another addon's
      hotkey (not its own prefix) and asserts one binds, reading back the same way — the check names the id, and fails
      saying no other addon declares a hotkey when none is loaded. It asserts `addon/169-nobody/toggle` is refused
      naming "no addon has it declared" and `nothing-169` naming "names no binding", each leaving the earlier Binding
      on the button, and that its own hotkey still binds.
      `[manual]`: press the button bound to the other addon's hotkey, then `F9` — expect it reads `F9`, the `[changed]`
      line the suite prints names `F9`, and that addon's row in Options ▸ Game ▸ Keybindings reads `F9`; then press it
      and `Delete` to leave it unbound. Press the `inv` button and `Escape` — expect the key unchanged and no line.

- [ ] **169.2 — Without client.settings the refusals name the permission.** The two refusals in
      `CKeybinding.hotkey` for another addon's hotkey and for a client binding keep their opening words and add that
      under `client.settings` a key button joins it. `interactive.md` quotes the rule. Criterion 5.
      *Its suite* declares no permission. It asserts `inv` is refused naming both "one of the client's own" and
      `client.settings`, and `addon/169-nobody/toggle` naming both "another addon's" and `client.settings` —
      before any lookup, so no other addon needs to be loaded — and that its own hotkey binds and reads back.
      `[manual]`: none.
