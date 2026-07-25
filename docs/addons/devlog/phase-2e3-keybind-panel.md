# Phase 2e-3 — Addon hotkeys in the client keybind panel

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 8/8 headless checks for the panel
> (`describeKeyBinds` grouping: no-hotkeys→no-sections, one section per addon in first-registration order,
> per-addon rows in registration order, each row carries the real `KeyBinding`, dead binds excluded, an addon
> with no live hotkey drops its section) + **9/9 headless checks for keybinding exclusivity** (assigning a key
> steals it from any other binding — incl. one holding it only as a default, and across char/code
> representations of the same key; different modifiers coexist; disable/revert-to-default never steal) + LuaJ
> parse of the harness under the sandbox.
> **In-game DoD pending.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.key` — "shown in the
> client keybind panel"), [specs/addons/code-map.md](../../specs/addons/code-map.md) (`OptWnd.BindingPanel`,
> `KeyBinding`), decision **D-011** (invasiveness allowed where it yields a better feature — this is one small
> core edit). Follows [2e-2](phase-2e2-global-hotkeys.md) (the `hafen.key.bind` hotkeys themselves).

The seventh Phase-2 slice — the **WoW-style keybind-panel integration** promised by the `hafen.key` contract but
deferred in 2e-2. When an addon registers **any** hotkey (`hafen.key.bind`), it now appears in **Options >
Keybindings** under its **own section, named after the addon**, with a capture button per hotkey; an addon that
registers **no** hotkey shows no section. Re-mapping there **persists across restarts**, exactly like every
built-in keybinding (nothing new — it rides the client's existing `KeyBinding` persistence). **DoD**: *bind a
hotkey in an addon; a section named after the addon appears in the keybind options; re-map it and it sticks
across a restart.*

## What the user sees

Open **Options > Keybindings** in-game. After the built-in sections (Main menu, Map options, …, Voice chat),
there is now **one section per addon** that registered a hotkey:

```
Hello
    toggle            [ Ctrl+H ]     <- click to re-map (Escape cancels, Backspace = default, Delete = disable)
    ping              [ None    ]     <- unbound by default; click to assign a key from scratch
```

- The **section header** is the addon's display name (its `manifest.name`).
- Each **row** is one `hafen.key.bind(name, …)`, labelled by its `name`, with the client's standard capture
  button (the same widget every built-in binding uses).
- **Only addons with a live hotkey appear.** A disabled or not-loaded addon has no live binding, so it shows no
  section (its persisted key choice still survives in the `KeyBinding` registry for when it loads again).

## How it works — one small core edit + one accessor

The binding half already existed in 2e-2: `hafen.key.bind` creates a real client
[`KeyBinding`](../../src/haven/KeyBinding.java) (`addon/<id>/<name>`), which is **remappable and persisted** in
the client prefs (`keybind/addon/<id>/<name>`). All this slice adds is **surfacing** those bindings in the panel.

- **`AddonManager.describeKeyBinds()`** *(new accessor)* returns the live addon hotkeys **grouped by owning
  addon** (`KeyBindGroup { addon, binds }`, each `KeyBindEntry { name, binding }`). Only addons with ≥1 live
  hotkey get a group; order is first-registration (addons) then registration order (within an addon). It reads
  the live `keyBinds` list on the UI thread at panel-build time.
- **`OptWnd.BindingPanel`** *(the one core edit — a `// addon:` block)*: after the "Voice chat" section, it loops
  `describeKeyBinds()`, adding a `Label(group.addon)` header and, for each entry, the existing
  `addbtn(cont, name, binding, y)` row. **`addbtn` + the client's `SetButton` (a `KeyMatch.Capture`) already do
  everything else** — capturing a new key and calling `KeyBinding.set(key)`, which writes the pref. So the panel
  needs **no new capture/persistence code**; re-mapping an addon hotkey is byte-for-byte the same path as
  re-mapping "Inventory" or "Push to talk".

**Persistence is free.** `KeyBinding.set` → `Utils.setpref("keybind/" + id, …)`; on next launch
`KeyBinding.get(id, default)` restores it via `KeyMatch.restore`. This is the client's own binding persistence —
the addon layer touches none of it. (2e-2 already made a point of **not** removing the `KeyBinding` on
teardown, precisely so a re-map survives.)

**Why panel-build-time is enough.** The panel is rebuilt every time it is opened, and an addon binds its hotkeys
at load (file body / `OnLoad`) — before you can open the options — so the sections are always current. Reopening
after a `:reload` re-reads the live list.

## Keybinding exclusivity — a client-wide fix

Testing the panel surfaced a **pre-existing client behaviour**: the vanilla client lets you bind the **same key
to several actions at once** — `KeyBinding.set` just writes the pref, with **no conflict detection** (whichever
widget the `GlobKeyEvent` walk reaches first wins; the rest are silently shadowed). This is not something the
addon layer introduced — addon bindings are ordinary `KeyBinding`s and merely inherited it — but it is wrong
(WoW-style, *a key can only be bound to one action*), so we patched it at the source.

**The fix (one core edit, client-wide):** `KeyBinding.set(key)` now, when a **real** key is assigned, unbinds
that key from **every other** binding that fires on the same key+modifiers (setting them to `None`) before
storing it. So assigning a key that another action already holds **steals** it — the other shows `None` in the
panel (its `SetButton` re-reads `KeyBinding.key()` each frame, so it updates live). This applies to **all**
bindings (client + addon), exactly as the report requested.

- **Only explicit assignment steals.** Reverting-to-default (`Backspace` → `set(null)`) and disabling
  (`Delete` → `set(nil)`) never clear others; loading persisted binds (`KeyBinding.get`) bypasses `set`, so
  there is no load-time cascade. Shipped **default** overlaps are preserved until the user reassigns one.
- **Char vs code are reconciled.** A binding stored as a character (`forchar('G', …)`, how the client's letter
  bindings are defined) and one stored as a keycode (`forcode`/`forevent`, how the panel captures) are
  normalised to one keycode (`KeyEvent.getExtendedKeyCodeForChar`), so "Ctrl+G" in either form conflicts.
- **Modifiers matter.** `J` and `Ctrl+J` are distinct and coexist; only the same key **and** the same required
  modifiers conflict.

## Files

**Core (`haven`) — two edits:**
- **`OptWnd.java`** — `BindingPanel` appends the dynamic per-addon hotkey sections after "Voice chat" (one
  `// addon:` block that loops `AddonManager.describeKeyBinds()` and reuses the existing `addbtn`). This is the
  **second** `OptWnd` addon edit (after 1f-3's AddOns-panel button).
- **`KeyBinding.java`** — `set(key)` now enforces **keybinding exclusivity** (unbinds a newly-assigned key from
  any other binding that fires on it) + two small private helpers (`clear`, `sameKey`/`keycode`). A `// addon:`
  block; benefits **all** bindings, not just addons. (First `KeyBinding` edit.)

**Engine (`io.brodgar.addon`):**
- **`AddonManager`** — new public `describeKeyBinds()` + the immutable `KeyBindGroup` / `KeyBindEntry` types
  (mirroring the `AddonInfo` pattern the 1f-3 panel uses). No change to the 2e-2 dispatch/registration.

**Example:**
- **`hello`** v0.19.0 — a second hotkey, **`ping`, unbound by default** (`hafen.key.bind("ping", nil, …)`), so
  the "Hello" section shows two rows (`toggle` = Ctrl+H, `ping` = None) — demonstrating multi-row grouping and
  the panel's assign-from-scratch flow (assign `ping` a key, and it plays a sound + persists).

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (8/8 panel):** `describeKeyBinds` — empty list → no sections; two addons (registered A-then-B, A
  with `toggle`+`ping`, B with `x`) → two groups in registration order, labelled by `manifest.name`, rows in
  registration order, each row carrying the **identical** `KeyBinding`; a **dead** bind is excluded; an addon
  whose every bind is dead drops its section. Plus `hello/main.lua` (v0.19.0) parses under `Sandbox.create()`.
- **Headless (9/9 exclusivity):** assigning a key steals it from another binding; steals even when the other
  held it only as a **default**; **char-vs-code** representations of the same key conflict; **different
  modifiers** coexist (`J` vs `Ctrl+J`); **disabling** (nil) doesn't steal; **reverting-to-default** (null)
  neither throws nor steals.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.19.0**) enabled, open **Options > Keybindings**:
  1. **Section appears:** scroll to the bottom — a **"Hello"** section lists **`toggle` = Ctrl+H** and
     **`ping` = None**.
  2. **Re-map + persist:** click `toggle`'s button, press a new key (e.g. `Ctrl+J`) → it updates; press Ctrl+J
     in-world → the window toggles. Restart the client and reopen the panel → `toggle` still shows `Ctrl+J`
     (persisted). `Backspace` on the button reverts to the Ctrl+H default; `Delete` disables it.
  3. **Assign from scratch:** click `ping`'s `None` button, press a key → assign it; pressing that key in-world
     plays the ping sound. It persists across a restart too.
  4. **No hotkeys → no section:** `:addons disable hello` + `:reload` (or an addon that binds no key) → the
     "Hello" section is gone; re-enable + `:reload` → it returns.
  5. **Exclusivity (the reported fix):** assign the same key to two rows — e.g. set `ping` to the key `toggle`
     already uses. The moment you assign it to `ping`, **`toggle` flips to `None`** (a key can't be bound to two
     actions). This works across sections too (assign an addon hotkey a key a built-in action holds → the
     built-in shows `None`), and it persists across a restart.

## Known gaps / deferred

- **Per-binding label is the `name` argument** (`toggle`, `ping`), not a separate human-readable title. WoW
  exposes a display string per binding; our API is `hafen.key.bind(name, default, fn)`. A future optional
  display-name argument (or an `opts` table) could give friendlier row labels — deferred to keep the 2e-2 API
  stable (one canonical way).
- **The panel lists currently-loaded addons only.** A disabled addon's persisted re-map survives (in the
  `KeyBinding` registry) but isn't shown until the addon loads — matching WoW (a disabled addon exposes no
  bindings). Showing stored-but-inactive bindings is out of scope.
- **No live refresh while the panel is open** — it is built on open. Binding a hotkey (e.g. via `:reload`) while
  the keybind panel is already open won't add the row until it is reopened. Acceptable (matches how the panel
  handles everything else).
- **Exclusivity edge cases.** Conflict detection compares the required modifiers exactly (`modmatch`) and ignores
  the `modign` "ignored modifiers" field (used by e.g. push-to-talk), so a modign-bearing binding could still
  double-bind in theory. Reverting a binding to its **default** (`Backspace`) does not re-check conflicts, so it
  can re-introduce an overlap with a shipped default. And a **pre-existing** duplicate (created before this
  patch, or two shipped defaults that share a key) is not auto-resolved on load — it clears only when the user
  next assigns one of them. All minor; the common case (assigning the same key twice) is handled.
