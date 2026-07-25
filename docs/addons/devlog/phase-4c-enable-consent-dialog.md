# Phase 4c — Enable-time write-actions consent dialog (D-027/D-028)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + **9 headless checks** (the
> D-028 per-addon gate — `actionsGranted`/`requireActions` are declaration-only, and `applyActionsDefaults`
> still default-disables a new write addon) + LuaJ parse of `hello`/`walker`. UI itself is not
> headless-testable (a `Window`/`Label`/`Button` needs GL + the text font, like the 1f-3 panel).
> **In-game verification pending.**
> **Design:** [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md)
> ("exact wording and placement of the write-actions permission notice" — its one open item, now resolved),
> [specs/addons/10-options-panel.md](../../specs/addons/10-options-panel.md), decisions **D-027** (the
> per-addon permission) / **D-028** (per-addon ONLY — drop the global master switch) / **D-006** (WoW
> apply-on-reload).

The consent dialog that makes enabling a **write-declaring** addon a knowing, explicit grant — and, together
with **[D-028](../../specs/addons/decisions.md)**, the **sole** user-facing control for the write-actions
tier. When you turn a write addon **on** in the AddOns panel, a dialog appears first, spelling out that it
will be able to act on your behalf; the addon is enabled **only** if you confirm.

## The permission model (after D-028)

Write-actions (`hafen.act.*`) are a **per-addon permission** — there is **no** global master switch (D-028
removed the one 4b had introduced; the action tier is always available at the system level). An addon may act
**iff**:

1. it **declared** `"permissions": ["actions"]` in its manifest (author's responsibility;
   `requireActions` throws a guiding error otherwise), and
2. **you enabled it** — and because a write addon is **disabled by default** (opt-in) and enabling it goes
   through the **consent dialog below**, a running write addon is, by construction, one you knowingly
   permitted. **Enabling-with-consent *is* the grant; disabling revokes it.**

That's the whole gate. `requireActions` need only re-check the declaration; `hafen.act.enabled()` reports
whether *this* addon declared the permission (always true for a running write addon).

## What the user sees

Tick the enable checkbox of an addon whose row carries the `[actions]` marker and, instead of the box ticking
immediately, a titled dialog appears:

```
┌ Enable Walker? ──────────────────────────────────────┐
│ “Walker” wants permission to act on your behalf.      │
│                                                       │
│ If you enable it, it will be able to move your        │
│ character, use items, and interact with the world —   │
│ the same actions you can take by clicking, done       │
│ automatically.                                        │
│                                                       │
│ Enable it only if you trust it — you are granting the │
│ permission to this addon alone. You can disable it    │
│ again here in the AddOns panel at any time.           │
│                                                       │
│            [ Enable ]      [ Cancel ]                 │
└───────────────────────────────────────────────────────┘
```

- **Enable** → the addon is added to the enabled set (persisted; **applies on reload**, D-006) and the
  checkbox ticks. On the next **Reload UI** (or login) it loads like any addon and can act — no second global
  toggle to also flip (D-028).
- **Cancel** / the **✕** close box → nothing changes; the addon stays disabled and the box stays unticked.

Read-only addons (the vast majority — the whole of Phases 1–3) are **unaffected**: their checkbox toggles
instantly with no prompt.

## Why the notice

Enabling a write addon is the moment you grant a consequential capability — like an OS app-permission prompt.
Since D-028 there is no global switch to lean on; the per-addon consent **is** the permission, so it has to be
a deliberate, informed act. That is the point of a per-addon permission (D-027/D-028): you see, and now must
acknowledge, **which** addons drive your character.

## Implementation

Two files, both in the addon package (**zero `haven` edit** — every widget is a public `haven` type, like the
1f-3 panel it extends):

### `io.brodgar.addon.ui.ActionsConsentWnd` (new)

A small `Window` composed of three wrapped `Label`s (the notice) + an **Enable** and a **Cancel** `Button` —
pure public-`haven` composition, `pack()`ed to fit. Constructor `ActionsConsentWnd(String addonName, Runnable
onConfirm)`:

- **Enable** runs `destroy(); onConfirm.run();` — closes the dialog, then runs the caller's confirm
  (persist-enable + panel rebuild). `onConfirm` never runs on Cancel/close.
- **Cancel** and the close box both `destroy()`. Being a **client-side widget with no server**, its default
  `reqclose` (which would send `wdgmsg("close")` to a server that isn't there) is redirected to `destroy()` —
  the same client-side-window handling as a 2a `hafen.ui.window`.

Destroying the window from inside its own button's `action` is safe: `Button.mouseup` releases the mouse grab
and redraws **before** calling `click()`, so nothing touches the button after the handler returns (verified
against `haven.Button`).

### `io.brodgar.addon.ui.AddonPanel` (edited)

- **Row checkbox** — the per-addon enable checkbox's `set(v)` branches: if `v && ai.declaresActions` (enabling
  a write addon) it calls `confirmEnableActions(id, name)` and **leaves the box unticked** (`a` stays `false`)
  until the dialog confirms. Disabling (`v == false`) and read-only addons fall straight through to the old
  `setEnabled` path — no prompt.
- **`confirmEnableActions(id, name)`** — pops the `ActionsConsentWnd` as a **top-level floating window** (a
  `ui.root` child, centered on screen and `raise()`d), so it **drags freely like any window** instead of being
  clipped inside the panel. On confirm it runs `setEnabled(id, true)` + `rebuild()`. A `consent` field guards
  **one dialog at a time**. Because it is top-level, the panel closes it explicitly in `tick()` once the panel
  leaves the screen — see below.
- **Lifecycle** — the panel's `tick()` destroys the open consent when `!tvisible()` (the AddOns panel was
  switched away via Back, or Options was hidden). This is reliable because tick recurses through the whole tree
  **regardless of visibility** (`TickEvent.propagation` doesn't skip invisible subtrees — the same fact the
  1b `AddonRoot` pump relies on), and `OptWnd` is only **hidden**, never destroyed, on close. So the floating
  dialog never lingers with no context (and leaving the panel reads as an implicit Cancel).
- **`enableAll()`** — **skips** write-declaring addons (`if(!ai.declaresActions)`), so the bulk "Enable all"
  can never turn a write addon on behind the consent gate.
- **The master switch checkbox is GONE** (D-028) — the panel's old "Allow addon actions (writes)" checkbox and
  its intro line were removed; a short intro line now points the user at the `[actions]` marker + consent flow.

The panel already exposed everything needed: `AddonInfo.declaresActions` (read fresh from each manifest by
`describeAddons()`, so it is correct even for a disabled/not-loaded write addon — precisely the case we gate).

### Engine (`AddonManager` / `Manifest`) — D-028 cleanup

Removing the master switch: deleted `actionsEnabled()`/`setActionsEnabled()`, the `addons.actions.enabled`
config flag + `addons/actions.enabled` pref, the `writeAddonIds` cache, `loadAll`'s master-switch load gate,
and the `blocked: actions off` status. `actionsGranted(owner)` / `requireActions(owner, verb)` now check
**only** the manifest declaration. **Kept:** the per-addon default-disabled policy (`scanAddonDefaults` /
`applyActionsDefaults` + the `addons/actions.seen` set) — with the consent dialog, that IS the permission.

## `hello` unchanged (functionally); `walker` reworded

4c adds **no Lua-facing API** — it is operator UI plus a gate on the panel, and D-028 is an engine
simplification. The regression harness `hello` stays read-only (only its stale master-switch comments were
updated). The in-game trigger is the existing **`walker`** write addon (declares `"actions"`, disabled by
default); its comments/description were reworded to the per-addon model and it was bumped to **v0.2.0**.

## In-game DoD

1. Open **Options → AddOns**. `walker` shows an `[actions]` marker and an unticked checkbox (disabled by
   default). There is **no "Allow addon actions" checkbox** anymore.
2. **Tick `walker`** → the **"Enable Walker?"** consent dialog appears (centered, on top); the checkbox stays
   **unticked**. It is a normal floating window — **drag it anywhere** on screen, including outside the AddOns
   window (it is not clipped inside it).
3. **Cancel** (or ✕) → dialog closes, `walker` still unticked/disabled.
4. **Tick again → Enable** → dialog closes, checkbox ticks, "changes pending — Reload UI" hint appears.
5. **Reload UI** → `walker` loads; at login it logs `write-actions GRANTED`; run `:walker` → your character
   walks ~2 tiles south (the gated `hafen.act.moveTo`).
6. **"Enable all"** does **not** tick `walker` (write addons stay opt-in) but does enable the read-only rows.
7. Open the dialog, then click **Back** / close Options → **no dialog is left floating**.
8. `hello` (and every read-only addon) still enables/disables **instantly with no prompt** — full regression
   intact. Disable `walker` + **Reload UI** → it stops loading; `:walker` no longer acts.

## Deferred

- **Per-category breakdown** — today the sole declared permission is the coarse `"actions"`, so the notice is
  one general statement. `ActionsConsentWnd` is written as the base for a future
  "moves your character / interacts with objects / …" list once finer categories (`"actions.move"`,
  `"actions.items"`, …) exist (D-027 keeps `permissions` an array for exactly this).
- **Re-consent on a read→write manifest flip** — a write addon is defaulted-disabled once when first *seen* as
  a write addon (the seen-set); a version that newly adds `"actions"` is not yet re-gated.
- **True modality** — the dialog floats on top but does not block the panel beneath (Haven has no modal
  infrastructure); the one-dialog-at-a-time guard is the practical stand-in.
