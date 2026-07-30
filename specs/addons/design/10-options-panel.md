# Options: the AddOns Panel

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md), [DECISIONS.md](../DECISIONS.md) (D-004, D-006)

An in-game **AddOns** button in Options, WoW-style, to list addons, enable/disable them, and
**Reload UI**. A direct clone of the existing voice panel pattern.

## Pattern to clone

The voice integration already added a panel + a main-options button:
- `OptWnd.VoiceChatPanel extends OptWnd.Panel` (checkboxes/sliders routing to `Voice.*`).
- A `PButton` "Voice Chat Integration" in the main options list of
  [`OptWnd`](src/haven/OptWnd.java).

We replicate:
- **`AddonPanel extends OptWnd.Panel`** in `io.brodgar.addon.ui` (or a `haven`-package class if
  it needs package-private access — likely not).
- A **`PButton` "AddOns"** added to the main [`OptWnd`](src/haven/OptWnd.java) options list that
  opens `AddonPanel`.

Availability: **in-game only** ([D-004](../decisions/lifecycle.md)), since `OptWnd` is created inside
`GameUI`.

## Panel contents

```
┌ AddOns ───────────────────────────────────────────────┐
│ [x] PartyFrames        0.1.0   gonzalo                 │
│ [ ] BetterFishing      0.3.2   gonzalo    ⚠ error      │
│ [x] BagOverhaul        1.0.0   someone                 │
│ ...                                    (scrollable)    │
│                                                        │
│ changes apply on reload                                │
│ [ Reload UI ]   [ Open addons folder ]   [Enable all]  │
└────────────────────────────────────────────────────────┘
```

Each row:
- **Checkbox** — enabled/disabled (see semantics below).
- **Name + version + author** — from the manifest ([03-addon-format.md](03-addon-format.md)).
- **Status** — loaded / disabled / error (with the error message as a tooltip, like WoW's error
  display). "Out of date" if `api_version` mismatches ([03](03-addon-format.md)).
- **Description tooltip** — from the manifest.

Global controls:
- **Reload UI** — triggers the addon-layer reload ([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).
- **Open addons folder** — opens `<client>/addons/` in the OS file browser (convenience).
- **Enable all / Disable all** — optional bulk toggles.

## Enable/disable semantics ([D-006](../decisions/lifecycle.md))

**WoW model:** toggling a checkbox only updates the persisted **enabled set**
([02-filesystem-and-build.md](02-filesystem-and-build.md)) and marks the panel "changes pending".
Changes take effect on **Reload UI** (or next login). This is predictable and avoids
partial-live-load edge cases.

- A subtle hint ("changes apply on reload") appears when there are pending changes.
- Optionally, a **Reload now** prompt after toggling.

**Live toggle (later enhancement).** Since reload teardown is per-addon, live disable (tear down
one addon) and live enable (run one addon) are cheap to add once teardown is proven. Not in v1.

## Write-actions permission in the panel ([D-027](../decisions/actions-permissions.md)/[D-028](../decisions/actions-permissions.md))

Write-actions (`hafen.act.*`) are a **per-addon** permission — there is **no** global master switch
([D-028](../decisions/actions-permissions.md) dropped it). The panel is where the user grants it, per addon:

- An addon that declares `"permissions": ["actions"]` shows an **`[actions]` marker** on its row.
- Such an addon is **disabled by default** (opt-in; a persisted "seen" set defaults it exactly once).
- **Enabling it raises a consent dialog** (slice 4c, `ActionsConsentWnd`) that explains it will be able
  to act on your behalf (move your character, use items, interact with the world). It is enabled (and
  can then act) **only** if the user confirms; **Cancel**/close leaves it disabled. Enabling-with-consent
  **is** the grant; disabling revokes it.
- **"Enable all" skips write addons** — the per-addon consent can't be bypassed by a bulk action.
- Read-only addons (the majority) are unaffected: their checkbox toggles instantly, no prompt.

## Persistence

The enabled set is stored under the client folder (not `%APPDATA%`) — proposed
`addons/enabled.txt` or `addons/state.json` ([02-filesystem-and-build.md](02-filesystem-and-build.md)).
Scope (account-wide vs per-character) is open ([Q-002](../DECISIONS.md)); v1 can be
account-wide.

## Interactions with keybindings

Addon hotkeys registered via `hafen.key.bind` appear in the client's existing **keybind panel**
automatically (they use [`KeyBinding.get`](src/haven/KeyBinding.java)). The AddOns panel does not
duplicate keybind editing; it may link to it.

## Open items

- Row layout details (icons? per-row reload/config button?).
- Whether an addon can contribute its **own** options sub-panel (a `config` entry point in the
  manifest that opens an addon-provided `hafen.ui` panel) — likely yes, deferred.
- "Load out of date addons" checkbox (WoW parity) if `api_version` gating is enforced.
