# Decisions — Lifecycle, reload & management

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-004 — AddOns management is in-game only (initially) ✅
**Decision.** The **AddOns** options panel is available **in-game** only (via
[`OptWnd`](src/haven/OptWnd.java) inside `GameUI`), not on the login/character-select screen.
**Rationale.** User preference; `OptWnd` already lives in `GameUI`; pre-login management is extra
wiring for little initial value.
**Revisit.** Pre-login management is a possible later enhancement.
**See.** [10-options-panel.md](../design/10-options-panel.md).

### D-005 — "Reload UI" reloads the addon layer only ✅
**Decision.** Reload UI tears down and rebuilds **only the addon layer** (all Lua state + every
resource addons created) from disk, keeping the session connected and the native/server-driven
Java UI untouched.
**Rationale.** In Hafen the base UI is Java and server-driven; the client cannot rebuild those
widgets without the server resending them (a reconnect). Reloading the addon layer delivers the
WoW dev-loop intent (edit Lua → reload → see changes, no relog), which is the part that matters.
**Alternatives.** Full rebuild incl. server UI ≈ a soft relog — heavier, flickers, out of scope.
**See.** [05-lifecycle-and-reload.md](../design/05-lifecycle-and-reload.md).

### D-006 — Enable/disable uses the WoW model (apply on reload) ✅ — **default narrowed by [D-027](actions-permissions.md)**
**Decision.** Toggling an addon's checkbox updates the persisted enabled-set and marks "changes
pending"; the change is applied on **Reload UI** (or next login), not live.
**Rationale.** User preference; predictable; avoids partial live-load edge cases. Live
enable/disable is a possible later enhancement (cheap once teardown is proven, since Reload needs
teardown anyway).
**Default (D-027).** A newly-discovered addon defaults to **enabled** — EXCEPT an addon that declares
the `"actions"` write permission, which defaults to **disabled** (opt-in per addon). The apply-on-reload
model is unchanged.
**See.** [10-options-panel.md](../design/10-options-panel.md), [05-lifecycle-and-reload.md](../design/05-lifecycle-and-reload.md).

### D-019 — Topological load order, no LoadOnDemand v1 ✅ (closes Q-010)
Order by `dependencies`/`optional_dependencies` (topological), alphabetical-by-id tiebreak. No
LoadOnDemand initially. See [03-addon-format.md](../design/03-addon-format.md).
