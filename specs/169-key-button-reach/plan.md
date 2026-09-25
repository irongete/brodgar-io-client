# 169 — key button reach: plan

## Approach

Everything happens in `CKeybinding`, the adapter behind `hafen.ui():keybinding()`. Two members decide what a button
joins and whether it answers, and both widen; the press path (`SetButton.handle`, `KeyMatch.Capture`,
`CKeybinding.keydown` firing `Changed`) is untouched, because it already acts on whatever `KeyBinding` sits in `cmd`.

- **`CKeybinding.hotkey(owner, v)`**, the bind-time classifier, keeps its order: not a Binding, the addon's own
  prefix (live, else ended), then another addon's, then the client's, then the rest. After the own-prefix branch it
  asks `AddonManager.permitted(owner, Permission.CLIENT_SETTINGS)` — the non-throwing half of `requirePermission`,
  reading the consent record, never the manifest alone.
  - Another addon's id (`addon/` prefix, not the owner's): permitted → the `KeyBinding` of a live `LuaKeyBind` in
    `HookApi.keyBinds` whose `binding.id` is the id, else refused naming that no addon has it declared right now.
    Not permitted → the existing refusal, extended with the permission (169.2).
  - A client id (`KeyBinding.get(id) != null`): permitted → that `KeyBinding`. Not permitted → the existing refusal,
    extended (169.2).
  - Anything else: permitted → refused naming that the id names no binding the client or any addon has declared;
    not permitted → the existing "taken before `keybindings:on`" refusal, unchanged.
- **`CKeybinding.live()`**, the press-time test `answers()` and `tick()` read: an `addon/` id is live while any
  `LuaKeyBind` in `HookApi.keyBinds` is alive over that same `KeyBinding` (the owner's own hotkeys are a subset, so
  one rule replaces the owner-only loop); any other id — a client binding — is live while `cmd` is set. A capture on
  another addon's hotkey therefore ends by itself when that addon is disabled, exactly as one on the addon's own does.
- The permission is read at bind time only. A consent cannot be withdrawn from a running addon: disabling it tears
  down its widgets with it.

## Files to create/modify

- `src/io/brodgar/addon/CKeybinding.java` — `hotkey`, `live`, the class comment (169.1); the two refusal texts (169.2).
- `docs/addons/api/ui/controls/interactive.md` — Key button: the `:bind(binding)` row, the "Your own hotkeys" rule
  becoming whose bindings it joins, the Unprotected rule, the `Backspace` row of the press (169.1); the refusal
  wording (169.2).
- `docs/addons/api/ui/controls/README.md` — the `hafen.ui():keybinding()` row, the `:bind` row and the `:bind(binding)`
  rule (169.1).
- `docs/addons/api/ui/writes.md` — the owned `:bind` cell (169.1).
- `docs/addons/api/client/keybindings.md` — A key button on your own page: the "Your own hotkeys" rule (169.1).
- `docs/addons/guides/permissions.md` — the `client.settings` row names the key button's reach (169.1).
- `addons/169-key-button-reach.1/`, `addons/169-key-button-reach.2/` — the suites.

## Risks & gotchas

- **`HookApi.keyBinds` is global and copy-on-write**: iterating it from the tick is safe, and it holds every live
  hotkey of every addon. `LuaKeyBind.alive` is cleared before removal, so a racing read sees a dead entry, not a
  live one.
- **One `KeyBinding` per id** (`KeyBinding.get` is a process-global registry), so `==` on the `KeyBinding` is the
  identity test, and a hotkey re-declared after a reload is the same object: the button answers again with no rebind.
- **`bound()` hands back `LuaBinding.of(owner, id)`**: for a foreign id that is the registry id as written, the same
  handle `keybindings:binding():get(id)` gives the caller, so `:bind() == binding` holds.
- **`Backspace` on a client binding** reverts to its own default key (`KeyBinding.set(null)`), which may shadow or be
  shadowed; that is the panel row's behaviour, inherited.
- **A suite cannot hold and lack a permission at once**, which is why the refusal without `client.settings` is its
  own task with its own suite (169.2).

## Discarded alternatives

- **Letting a bundle bind its dependencies' hotkeys without any permission**: a second rule for the same reach, keyed
  on the manifest's shape rather than on what the addon may already write; `client.settings` is the one key that
  writes a binding, and the button should ask the same question.
- **Gating the press rather than the bind**: a press is the user's own edit, unprotected on the addon's own hotkey;
  gating it would make one button behave two ways, and a refused press is invisible to the author.
- **Exporting a "set my key" function from each addon and capturing through a throwaway hotkey**: works today, but
  every bundled addon then needs `client.settings` to write its own key, the button reads `None` after each copy,
  and the throwaway hotkeys show up as rows in the Keybindings panel.
- **Throwing the standard `requirePermission` refusal at bind time**: it names the manifest line but not what the
  button joins without the permission; the key button's own refusal already names both.
