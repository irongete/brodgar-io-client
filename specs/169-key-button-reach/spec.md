# 169 — key button reach: a key button for any binding your code may write

## What & why

`hafen.ui():keybinding()` joins the client's key button to a hotkey the addon itself declared, and refuses every
other binding: another addon's hotkey and the client's own bindings are assigned in Options ▸ Game ▸ Keybindings.
An addon holding `client.settings` can already write any of those keys with `binding:key(key)`, but it cannot let
the user *choose* one: an addon's windows receive no key events, so the only way to capture a press is the key
button, and the key button will not join the binding. A setup window — a bundle's first-run guide that sets the keys
of the addons it bundles, or an addon that gathers the client's own keys on one page — has to route every press
through a throwaway hotkey of its own and copy the key across.

Under `client.settings`, `widget:bind(binding)` on a key button joins any binding the addon's code may write: another
addon's hotkey while that addon has it declared, and any of the client's own bindings. Nothing new is reachable —
the addon could already write each of these keys — and the press stays the user's own edit, exactly as on the
panel's row. Without `client.settings` the button keeps its boundary, and the refusal names the permission as the
way past it.

## Acceptance criteria

1. With `client.settings` declared and approved, `:bind(binding)` joins a key button to another addon's hotkey while
   some addon has that hotkey declared, and to one of the client's own bindings (`inv`). `:bind()` reads the same
   Binding (`==`), the button shows the binding's key at once, and `:value()` reads it.
2. A press on such a button is the panel row's press on that binding: the next key is assigned, persisted and
   exclusive; `Escape` cancels; `Backspace` puts the binding back on its default (the client's own key for one of its
   bindings, unbound for an addon's hotkey); `Delete` unbinds. `Changed` fires once per press that moves the key, as
   it does for the addon's own hotkey, and the key shows in Options ▸ Game ▸ Keybindings.
3. Another addon's hotkey that ends while bound — its addon disabled or reloaded away, or `sub:off()` — keeps its
   Binding on the button: a press does nothing and a capture in progress ends, until an addon declares it again. A
   client binding never ends.
4. Under `client.settings`, refused naming why: another addon's hotkey no addon has declared right now (its addon is
   off, not loaded, or ended it), and a Binding that names nothing in the registry. A refused bind changes nothing.
5. Without `client.settings`, another addon's hotkey and a client binding are refused as before, and each refusal
   names `client.settings` as the permission under which a key button joins it. The addon's own hotkeys bind as
   before, with no permission.

## Out of scope

- **A key button without `client.settings` for another addon's hotkey**, by bundle or by dependency. The boundary is
  the permission that already writes the key: a second rule would be a second answer to one question.
- **The client's own key buttons** (the panel's rows, borrowed): `:bind(binding)` on one stays refused.
- **Declaring or ending another addon's hotkey**: the button joins what is declared; what declares it is its addon.

## Docs impact

Pages written: `docs/addons/api/ui/controls/interactive.md` (Key button: the bind row, whose bindings, the press's
`Backspace`), `docs/addons/api/ui/controls/README.md` (the constructor row, the `:bind` rows),
`docs/addons/api/ui/writes.md` (the `:bind` row), `docs/addons/api/client/keybindings.md` (A key button on your own
page), `docs/addons/guides/permissions.md` (what `client.settings` gates).

Derived impact set — `grep -rn -i "key button" docs/ --include=*.md`: the five pages above, plus
`api/client/addon.md` (a key button bound to one of *your* hotkeys on your page: still true, unchanged),
`getting-started.md` and `guides/debugging.md` (your own hotkey: unchanged), `guides/hotkeys-and-commands.md` (the
press is the user's edit: still true, unchanged), `manifest.md` (`api_version` 1.1: unchanged), `api/README.md`
(the list of controls: unchanged), `api/ui/edit.md` (the client's own key button, borrowed: unchanged),
`docs/client/services.md` and `docs/client/ui-panels.md` (upstream map: unchanged).

## Context files

- `src/io/brodgar/addon/CKeybinding.java` — 1, 2
- `src/io/brodgar/addon/HookApi.java` (`keyBinds`, `keyBindIdPrefix`, `newKeyBind`, `teardownKeyBinds`) — 1
- `src/io/brodgar/addon/AddonManager.java` (`permitted`, `requirePermission`) — 1, 2
- `src/io/brodgar/addon/LuaBinding.java` (`resolve`, `of`, `keyName`) — 1
- `docs/addons/api/ui/controls/interactive.md`, `docs/addons/api/client/keybindings.md` — 1, 2
- `docs/addons/api/ui/controls/README.md`, `docs/addons/api/ui/writes.md`, `docs/addons/guides/permissions.md` — 1
