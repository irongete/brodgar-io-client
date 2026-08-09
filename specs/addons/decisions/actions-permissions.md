# Decisions — Gated actions & permissions

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-010 — Actions API (`hafen.act`) is gated behind explicit opt-in ✅ (finalized by [D-025](actions-permissions.md))
**Decision (provisional).** The action/automation surface is delivered in a later phase and
gated behind an explicit opt-in.
**Rationale.** Write-actions let an addon act on the player's behalf, so they are an explicit,
user-granted permission; read/UI tiers only change presentation and are ungated. Scope of the action
tier is still open — see [Q-001](../DECISIONS.md).
**See.** [12-security-and-permissions.md](../design/12-security-and-permissions.md).

### D-025 — Actions tier: Phase 4, config-gated, permission notice ✅ (closes Q-001; finalizes D-010) — **refined by [D-027](actions-permissions.md)**
`hafen.act.*` ships in Phase 4, **disabled by default** via `addons.actions.enabled=false`
(`haven-config.properties`); enabling surfaces a notice that addons will be able to act on the
player's behalf. Read/UI/event tiers are unaffected. See [12-security-and-permissions.md](../design/12-security-and-permissions.md).
**Refinement (D-027).** The single global flag is kept as the **master switch**, but the opt-in is
now a **per-addon permission model**: the switch alone is not enough — an addon must ALSO declare the
capability. The user-facing control moves from a config-only flag to the AddOns panel checkbox.

### D-027 — Write-actions are a declared per-addon PERMISSION + a global master switch ✅ (refines D-010/D-025; maintainer, 2026-07-25) — **master switch dropped by [D-028](actions-permissions.md)**
**Decision.** The gated write/automation tier (`hafen.act.*` and every per-subsystem *(gated action)*
verb) is granted to an addon only when **BOTH** hold — a two-part permission model, like app permissions:
1. **Global master switch ON.** For now the `haven-config.properties` flag `addons.actions.enabled`
   (default `false`; a config edit / `-Daddons.actions.enabled=true` enables it, restart to apply). Slice
   **4b** layers a **persisted, panel-toggled pref** on top of it (an **AddOns-panel checkbox**, with a
   notice that addons will be able to act on your behalf) so it can be flipped at runtime. This is the
   *user's* choice. (The switch reads the config flag ONLY until 4b — with no runtime toggle yet, a stray
   persisted pref must not be able to strand it ON with no UI to turn it off.)
2. **The addon DECLARED the permission** in its manifest: `"permissions": ["actions"]` (an array, so it
   can grow into finer categories — `"actions.move"`, `"actions.items"`, … — without a format change).
   This is the *addon author's* responsibility, and it lets the client tell the user, **before** the addon
   runs, that it wants to drive the character (the basis for a future "this addon moves your character /
   interacts with objects" breakdown at enable-time).

`requireActions(owner, verb)` enforces both with a **distinct** error for each (missing declaration vs.
master off). `hafen.act.enabled()` reports the **granted** state (both halves) so an addon can adapt.

**Consequences.**
- **Addons that declare `"actions"` are DISABLED BY DEFAULT** when newly discovered (opt-in per addon) —
  a narrowing of [D-006](lifecycle.md)'s "default-enabled"; **read-only addons keep default-enabled**.
- **Turning the master switch OFF disables the write-declaring addons entirely** (they don't load — a
  read+write addon loses its reads too; chosen for simplicity/safety over partial loading).
- Supersedes the provisional `:addons actions on|off` console command (4a) — the control is the panel
  checkbox; the console command is removed.
- The engine stays **server-authoritative** regardless: the permission gates *convenience/automation*,
  not capability — an addon can still only send what a player click could send. The permission exists so
  the **user stays in control** of which addons act on their behalf, not for any client-security reason.
**Rationale.** Maintainer direction: reads and writes should both be plain `hafen.*` API usable by
addons, with the only difference being an explicit *permission* for writes — declared by the addon and
authorized by the user — not a surface bolted onto the `:addons` console command.
**See.** [12-security-and-permissions.md](../design/12-security-and-permissions.md), [10-options-panel.md](../design/10-options-panel.md).
Order: **widget-tree-read mechanism** ([14-widget-tree-reads.md](../design/14-widget-tree-reads.md), foundational)
→ A5 overlays (done) → A1 map/markers → A4 study/curiosity/FEP → A3 action bar → A2 radar/GobIcon settings
→ A11 slash commands → A6–A10. Rationale: the widget-tree mechanism unblocks vitals/buffs/char/FEP/
action bar at once ([coverage-gaps.md](../ROADMAP.md) B1/C2), so it comes first.

### D-028 — Write-actions permission is PER-ADDON ONLY; drop the global master switch ✅ (refines D-027; maintainer, 2026-07-25)
**Decision.** Remove the global master switch that [D-027](actions-permissions.md) point 1 introduced. The gated
write/automation tier (`hafen.act.*` and every per-subsystem *(gated action)* verb) is now granted to an
addon by a **single condition**: the addon **declared** the `"actions"` permission in its manifest
(`"permissions": ["actions"]`). The action tier is **always available at the system level** — there is no
config flag, no persisted `addons/actions.enabled` pref, and no AddOns-panel "Allow addon actions (writes)"
checkbox. **The user's control is entirely per-addon:** a write-declaring addon is **disabled by default**
(opt-in; kept from D-027, via the persisted `addons/actions.seen` set) and **enabling it in the AddOns panel
raises a consent dialog** (slice **4c**, `ActionsConsentWnd`) that spells out it will act on your behalf. So a
running write addon is, by construction, one the user **knowingly permitted** — enabling **is** the grant.

`requireActions(owner, verb)` now checks **only** the manifest declaration (throws a guiding Lua error if
absent). `actionsGranted(owner)` (backing `hafen.act.enabled()`) is therefore just "did this addon declare
`"actions"`" — always true for a running write addon.

**Consequences.**
- **Removed:** `AddonManager.actionsEnabled()` / `setActionsEnabled()`, the `addons.actions.enabled` config
  flag + `addons/actions.enabled` pref, the panel master checkbox, `loadAll`'s master-switch load gate, and
  the `blocked: actions off` status. **Kept:** the per-addon default-disabled policy (`scanAddonDefaults` /
  `applyActionsDefaults` + `addons/actions.seen`) and the 4c consent dialog — together they ARE the permission.
- A write addon now **loads as soon as it is enabled** (like any addon); no second global toggle to also flip.
- Simpler + one canonical control: the user grants/revokes the permission by **enabling/disabling the addon**
  (with the consent prompt on enable), instead of a two-part (global switch × per-addon) gate.
- Still **server-authoritative**: the permission gates *which addons* may act, never *what* they can send.
**Rationale.** Maintainer direction (2026-07-25): the global on/off was redundant with the per-addon opt-in —
"las actions están habilitadas siempre, pero hay que darle permisos individualmente a cada addon". The
per-addon consent (4c) already makes granting a knowing, explicit act; a second global switch added friction
without adding user control. One canonical way: enable-with-consent = grant.
**See.** [12-security-and-permissions.md](../design/12-security-and-permissions.md), [10-options-panel.md](../design/10-options-panel.md),
[D-027](actions-permissions.md) (the model this refines), [D-006](lifecycle.md) (default-enabled, narrowed for write addons).

---

### D-213 — a write onto something the API also ANNOUNCES refuses rather than answering, and the refusal names what is there ✅ (047.2, 2026-08-09)
**Decision.** `hafen.flowermenu():select(label|n)` and `:cancel()` **raise** when no menu is open, when no
caption matches, when the position is outside `1..count`, and when the key is neither a string nor a number —
and every one of those errors carries the ring that IS open, numbered (`1. Chop, 2. Pick branch`). The older
door onto the same `FlowerMenu.choose`, `hafen.act():flower(label)`, keeps returning `false` for "no menu / no
match" and is untouched.
**Rationale.** [4e's rule](../learnings/actions-gated.md) — *an action that does nothing to pick returns
`false`, doesn't throw* — was written for a verb fired **blind on a timer**, where "the menu was not up yet" is
an ordinary retry the caller obviously tests. `:select` is reached from `FlowerMenuOpened`, i.e. from inside the
moment the menu **is** up, and there the same `false` means a race that was lost, which the caller has no reason
to test and will not. A radial menu lives about a second, so a silently skipped pick has no symptom until the
automation is wrong three steps later. A refusal can also say what a boolean cannot: what the ring actually
offers — which is exactly what a caller that guessed a caption needs.
**Consequences.** The rule generalises past this menu: **a write onto a transient the API itself announces
refuses; a write onto a target that may simply not have arrived yet answers.** Two doors onto one engine method
now differ in failure shape and each page states its own — deliberate, and it lasts only until the older door is
retired (a separate, already-planned task). Also settled here is the gate's ORDER: `requireActions` runs before
the argument check **and** before the target lookup, so an addon that did not declare the permission always
hears about its manifest and never about "no menu open" — [D-027](#d-027)'s own declaration-first ordering,
applied at a second door.
**See.** [D-027](#d-027), [D-072](architecture-api.md#d-072), [D-009](widgets-ui.md#d-009),
[D-212](architecture-api.md#d-212), [047-flowermenu](../047-flowermenu/spec.md).
