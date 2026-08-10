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

---

### D-219 — a verb that COMMITS a real server action is protected wherever it lives ✅ (048.5, 2026-08-10)
**Decision.** `pag:use()` — the action-menu entry's one verb, shipped by 023 and **ungated** ever since, with
`docs/addons/api/menugrid.md` saying so in bold — now requires the per-addon `actions` permission. The gate
lands on the **write half only**: every `hafen.menugrid()` read (`:list()`, `:count()`, `:get(name)`,
`pag:name()`, `:res()`, `:exists()`, `:children()`, `:info()`) still answers an addon that declares nothing.
This is a delta on **D-028**'s *surface*, not on its model: the declaration string, the consent dialog and the
default-disabled policy are untouched.
**Rationale.** The tier is a property of what a verb DOES, not of where it sits. That was invisible while every
write lived in one section named for the permission — the section was the tier — and it becomes load-bearing
the moment [D-215](architecture-api.md#d-215) disperses those verbs onto the things they change: once
`hafen.act()` is gone, "protected" has to be carried by each verb or it is carried by nothing. `pag:use()` had
been the one hole in that: it drives `MenuGrid.PagButton.use`, which puts the client's own `"act"`-by-path /
`"use"`-by-id message on the wire — **the same message** the gated `hafen.act():menu(path...)` sent. Deleting
`menu` in the same task without gating `use` would have turned a dissolution into a permission *downgrade*: the
one door left onto that message would be the free one.
**Consequences.** An installed addon that fires `pag:use()` without declaring `"actions"` breaks — so gating a
**shipped** verb carries an obligation the greenfield ones do not: grep every installed addon for the call and
confirm each caller declares, before shipping (here only `walker`, which does). The read/write split is what
keeps the change safe for the browsing addons (`hello` reads the catalogue at every login and declares
nothing), and it is asserted in the same run as the refusal rather than reasoned about. The gate runs FIRST,
before the live-entry lookup — [D-213](#d-213)'s order, so an addon that may not act at all is told THAT and
not *"not in the menu"*. `pag:use()` still takes **no arguments**: `PagButton.use` reads `ui.modflags()` live,
so a `mods` parameter could only lie about the keys physically held.
**See.** [D-028](#d-028), [D-213](#d-213), [D-215](architecture-api.md#d-215),
[D-103](architecture-api.md#d-103) (the absorbed mechanism whose old door, `hafen.act():menu`, this task
closed), [048-act-dissolved](../048-act-dissolved/spec.md), [023-menugrid-oop](../023-menugrid-oop/spec.md).

---

### D-220 — a feature-detection verb whose answer is a fact about the CALLER is deleted, not relocated ✅ (048.7, 2026-08-10)
**Decision.** `hafen.act():enabled()` — *may this addon act?* — is **deleted**, and nothing replaces it anywhere.
It is the one verb of the dissolved section that was not moved onto the thing it changes, because it changed
nothing and read nothing: `AddonManager.actionsGranted(owner)` was literally `owner.manifest.usesActions()`. The
backing method went with it. The permission model is untouched — `"permissions": ["actions"]`, per addon,
default-disabled, consent at enable time.
**Rationale.** A feature probe earns its place when the answer lives somewhere the caller cannot see: a client
version, a server capability, a user switch. This one's answer lived in the caller's **own manifest.json**, a
file the addon author wrote and ships beside the code asking the question. **D-028** had already removed the
global master switch it was originally built to report — with that gone, the only caller it could ever answer
`false` was one that did not declare, which is a fact its author can read without calling anything. And its
existence made the guard look load-bearing: `walker` opened every sub-command with `if not
hafen.act():enabled() then …`, a branch that could not be taken. Deleting it is also what makes the section
**dissolvable** rather than merely smaller — it was the last unprotected member of `hafen.act()`, so with it
gone there is nothing left that a permission-shaped grouping could still be said to hold together.
**Consequences.** An addon that may not act learns it **per verb**, from the refusal `requireActions` already
raises, which names the verb rather than the tier — strictly more specific than the boolean, and impossible to
forget to check. The retirement row says exactly this, so a ported addon is told the question answered itself.
The same reasoning does not reach the `network` allowlist: `hafen.http` asks about *hosts*, and which hosts a
request will need is not always static in the manifest — this decision is about an answer that is a **constant**
of the caller, not about feature probes in general.
**See.** [D-027](#d-027), [D-028](#d-028), [D-215](architecture-api.md#d-215) (the dissolution this completes),
[D-103](architecture-api.md#d-103) (the sibling deletion in the same task: `act():flower`, whose door
`hafen.flowermenu():select` already owned), [048-act-dissolved](../048-act-dissolved/spec.md).

### D-228 — the permission is PER VERB, keyed `<section>.<verb>`, declared exactly or by a `<prefix>.*` group ✅ (050.1, 2026-08-10)
**Decision.** The single coarse tier becomes a **catalogue of 22 keys**, one per protected verb, named after the
section the verb LIVES on — `gob.click`, `item.transfer`, `menugrid.use` for `pag:use()`, `actionbar.use` for
`slot:use()`, and the one nested case `player.hand.use`. A manifest declares an **exact key** or a
`<prefix>.*` **group** matched on the key's own dot segments (`item.*` reaches every `item.<verb>` and can never
reach `itemx.y`); an entry that is neither **throws at load**, listing the whole vocabulary, so the addon fails
to load and the AddOns panel shows the reason. The bare `"*"` parses **nowhere** — the trusted `:lua` REPL owner
is built from the catalogue's own `values()`, so the allow-all shape has no spelling a disk manifest could reach
for. One `Permission` constant carries the key, the Lua spelling of the verb, and the plain-language line the
consent dialog renders, so the gate's refusal, the manifest validator, the internal owner and the dialog all
read one list instead of four that can drift. D-028's model is untouched: per-addon, default-disabled,
enable-with-consent, no global switch, server-authoritative.
**Rationale.** With one tier the consent dialog can only make one blanket statement — it over-warns about an
addon that wants to change the movement speed and under-warns about one that wants `widget:send`. **What the
user grants has to be the list they read**, and that is only possible if the declaration names verbs. Naming
each key after what the verb CHANGES is D-215 applied to the declaration itself: a permission is not a
namespace, so `pag:use()` asks for `menugrid.use` and not for something called after the tier. Accepting an
unknown key silently (the old behaviour) is the exact failure the catalogue exists to delete: a typo would grant
nothing and fail at the first call, in-game, on the user's real character. Groups exist because an addon that
does all four item verbs should read as one line in the dialog, not four — and they match on segments, not
characters, so a group can never widen by accident.
**Consequences.** Adding a protected verb is one catalogue entry plus one gate call; there is no second list to
update. The gate takes a constant instead of a verb string, so a call site wired to the wrong key is a
*compile-time-typed* mistake that a suite can still catch by reading the key back out of the refusal — which is
what `:t050-1` does for all 22. The refusal names the verb, the key, the manifest line to add and the group that
also grants it. `walker` declares 11 entries covering all 22 keys; a suite may declare a deliberate subset, and
that subset IS its proof (below). An **exact** key is not a prefix: `player.move` does not grant
`player.hand.use`, which is the one bug in this design worth planting a falsification for.
**See.** [D-027](#d-027), [D-028](#d-028), [D-215](architecture-api.md#d-215), [D-213](#d-213) (the gate runs
before the argument check, which is what lets a suite prove a grant without acting),
[D-229](#d-229), [050-granular-permissions](../050-granular-permissions/spec.md).

### D-229 — the persisted record says WHAT the user consented to, not THAT they were asked ✅ (050.1, 2026-08-10)
**Decision.** The pref that made the default-disable happen exactly once stops recording ids and starts
recording, per addon, the **set of keys the user approved** (`addons/permissions.consented`). The whole policy is
one line: **declared ⊆ consented → the user's choice stands; otherwise disable and let the consent dialog ask
again.** An addon declaring nothing is never touched; one asking for **less** than it was granted never
re-prompts; a never-consented declaration is not contained by an empty record, which is exactly the
disabled-by-default a newly discovered addon gets. The record is **additive per addon** and written only where
consent is GIVEN, so the policy itself is pure and read-only — headless-testable, and it only ever ADDS to the
disabled set, keeping change detection a size compare. The consented set holds **resolved** keys, so swapping
`item.use` for `item.*` reads as the widening it is whatever the entry count says.
**Rationale.** With one permission, recording only *that this addon was defaulted once* was harmless: declared →
declared could not change what was granted. With 22 keys it is a **silent escalation** — an addon the user
enabled for `item.*` could ship a new manifest asking for `widget.send` and keep running, which is the same
shape as the bulk-enable bypass 4c closed. Re-prompting on *any* declaration change would be cheaper but re-asks
when an addon DROPS a permission, which trains the user to click the dialog away.
**Consequences.** The enabled bit can still be flipped from anywhere, but the **grant** happens only in the
consent dialog — so every other path (the panel's bulk enable, the console's `:addons enable`) is fail-closed by
construction: the next scan simply defaults the addon back to disabled. The console's enable says so rather than
letting the following `:reload` look broken. The pref key changed with the model, so nothing carries the retired
tier's name; every addon that declared before re-consents once, which is moot because every one of them changed
its declaration in the same task.
**See.** [D-028](#d-028), [D-228](#d-228), `learnings/actions-gated.md` (4b, the seen set this replaces; 4c, the
bulk-enable bypass whose skip widened with the predicate).
