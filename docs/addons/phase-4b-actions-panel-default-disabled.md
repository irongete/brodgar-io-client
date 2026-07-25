# Phase 4b — AddOns-panel master switch + default-disabled for write addons (D-027)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **12 headless checks**
> (`applyActionsDefaults` — the pure D-027 write-addon default policy: read addon ignored / new write addon
> default-disabled + seen / mixed / a user-enabled write addon NOT re-disabled / a still-disabled one unchanged /
> `writeIds` = all write addons / additions-only; and the master switch — `actionsEnabled` default-OFF from the
> config flag, `setActionsEnabled` round-trip + `reloadNeeded` + idempotency) + LuaJ parse of `hello`/`walker`.
> **In-game verified ✅** (maintainer confirmed).
> **Design:** [specs/addons/10-options-panel.md](../../specs/addons/10-options-panel.md),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md), decisions
> **D-027** (the permission model) / **D-006** (its narrowing).

The second slice of **Phase 4**. Slice **4a** shipped the permission mechanism (`hafen.act`, the manifest
declaration, `requireActions`) with the master switch as a **config-file-only** flag. **4b** delivers the
runtime **user control** and the two enabled-set consequences D-027 called for:

1. **The AddOns-panel master checkbox** — **"Allow addon actions (writes)"** — the one place the user turns
   the whole write tier on/off, replacing the config-file stop-gap.
2. **Write addons default to DISABLED** when newly discovered (opt-in per addon; a "seen" set tells a new
   addon from one the user deliberately enabled).
3. **Turning the master switch OFF stops write-declaring addons loading entirely.**

## The master switch is now a persisted, panel-toggled pref

`actionsEnabled()` was `CFG_ACTIONS.get()` (the `addons.actions.enabled` config flag). It is now

```java
return Utils.getprefb("addons/actions.enabled", CFG_ACTIONS.get());
```

— a **persisted client-side pref layered on top of the config flag** (the config flag is the *default*; a
`-Daddons.actions.enabled=true` / `haven-config.properties` line still seeds it). `setActionsEnabled(boolean)`
writes the pref and flags `reloadNeeded` (write addons load/unload with it — **apply on reload**, like the
enabled set). This is the **user's** runtime choice, never the addon's.

> **Why a pref is safe NOW (it wasn't in 4a).** 4a deliberately read the config flag **only**: with no runtime
> toggle yet, a stray persisted pref could have stranded the switch ON with no UI to clear it (it had, once,
> from an even-earlier experiment). 4b's panel checkbox **is** that UI, so honoring the pref is safe. The pref
> key is fresh (`addons/actions.enabled`) so any stale value from the 4a-era experiment is ignored.

The panel checkbox (in `AddonPanel`, cloning the voice checkbox pattern) reads `actionsEnabled()` and drives
`setActionsEnabled`, with a tooltip notice that such addons "may act on your behalf — move your character,
use items, interact with the world."

## Default-disabled for write addons (the "seen" set) — D-027's narrowing of D-006

D-006 (WoW model) defaults a newly-discovered addon to **enabled** (absent from the persisted **disabled**
set). D-027 narrows that: an addon that **declares `"actions"`** defaults to **disabled** — opt-in per addon.
The catch is telling a *genuinely new* write addon (→ default it disabled) from one the user *already enabled*
(→ respect their choice, don't keep re-disabling it every load). That is what the **seen set** solves:

- `scanAddonDefaults()` (run at the top of `loadAll()` and `describeAddons()`) reads each manifest, then applies
  the pure policy `applyActionsDefaults(seen, disabled, declares)`: for every addon that **declares actions and
  is NOT in the persisted "seen" set** (`addons/actions.seen`), it adds the id to **both** the disabled set
  (default-disabled) and the seen set. A write addon **already in the seen set** is left to whatever the user
  has since chosen. Read addons are ignored. The sets only ever **grow** here.
- So a write addon is default-disabled **exactly once** — at first discovery. After that its checkbox is the
  user's, exactly like any other addon.

`scanAddonDefaults()` also refreshes `writeAddonIds` (all discovered write addons) for the panel's status.

**No migration/grandfathering is needed** because — crucially — **`hello` is now read-only** (see below), so
after this slice **no pre-existing addon declares `"actions"`**; the only write addon is the brand-new
`walker`, which is correctly default-disabled on first discovery. (A first-run grandfather would only have been
needed to protect an *existing* enabled write addon from being retroactively disabled; there is none.)

## Master switch OFF ⇒ write addons don't load at all

In `loadAll()`, after a manifest loads:

```java
if(!actionsEnabled() && m.usesActions()) {   // D-027
    log("skipping write-addon '" + m.id + "' — the actions master switch is OFF (enable it in Options > AddOns)");
    continue;
}
```

A read+write addon loses its reads too — chosen for simplicity/safety over partial loading (D-027). This is a
**load-time** gate, independent of the enabled set: a write addon can be *enabled* (checked) yet still not load
while the switch is off. `liveStatus()` reports that state as **`blocked: actions off`** (vs `disabled` /
`not loaded` / `loaded vX`), so the panel row makes the reason obvious. The panel also tags every write addon's
row with a **`[actions]`** marker (`AddonInfo.declaresActions`) so you can see which addons can drive your
character at a glance.

## Why `hello` became read-only, and `walker` was born

`hello` is the **standing regression harness** — it must load on **every** login to re-check all prior slices.
But 4a made `hello` a **write addon** (it declared `"actions"` to demo `moveTo`), and 4b's "master OFF ⇒ don't
load" rule means a write addon **doesn't load while the switch is off** — which is the **default**. A write
`hello` would therefore vanish on a default login, taking the entire regression suite with it.

The clean, architecturally-correct fix (a genuine consequence of D-027): **the always-on harness cannot be a
write addon.** So:

- **`hello` is now READ-ONLY** — its manifest declares no `permissions`, and the `hafen.act`/`moveTo`/`:hello
  walk` demo was removed. It always loads regardless of the switch, keeping the full read/UI/event regression
  intact. **v0.33.0.**
- **`walker`** is a new, small, **dormant, opt-in** write-demo addon (the `hogtest`/`bags` pattern) that
  declares `"permissions": ["actions"]`. Because it declares actions it is **disabled by default** and only
  loads when you enable it **and** the master switch is on — so `hafen.act.enabled()` is always `true` when it
  runs. It logs the granted state at login and `:walker` walks you ~2 tiles south via `hafen.act.moveTo`.

This keeps the write tier demonstrable **without** coupling the harness's loading to the master switch.

## Zero `haven` core edit

Pure engine — `AddonManager.java` (the pref-backed master switch + `setActionsEnabled`; `scanAddonDefaults` +
the pure `applyActionsDefaults`; the `loadAll` load-gate; `AddonInfo.declaresActions`; the `liveStatus`
`blocked: actions off` branch) and `AddonPanel.java` (the master checkbox + the `[actions]` row marker). No
`haven`-package file changed.

## Files

- `src/io/brodgar/addon/AddonManager.java` — `actionsEnabled()` now reads the `addons/actions.enabled` pref
  (default = the config flag); new `setActionsEnabled(boolean)`; `scanAddonDefaults()` + the pure
  `applyActionsDefaults(seen, disabled, declares)` (seen set `addons/actions.seen`); `loadAll` calls
  `scanAddonDefaults` + skips write addons while the switch is off; `describeAddons` calls `scanAddonDefaults`
  + fills the new `AddonInfo.declaresActions`; `liveStatus` adds the `blocked: actions off` state; the
  `requireActions` "off" message now points at the panel; the `writeAddonIds` cache.
- `src/io/brodgar/addon/ui/AddonPanel.java` — the **"Allow addon actions (writes)"** master checkbox (with the
  notice tooltip) driving `setActionsEnabled`, and the per-row `[actions]` marker.
- `addons/hello/` — **read-only now**: `manifest.json` drops `"permissions": ["actions"]` (+ trims the
  description), `main.lua` removes `readActions`/`walkDemo`/`:hello walk`; **v0.33.0**.
- `addons/walker/` — **new** opt-in write-demo addon (declares `"actions"`; `:walker` = `moveTo` ~2 tiles
  south); **v0.1.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

1. **The harness still loads read-only.** At login, `hello` loads and re-checks every prior slice as before
   (it no longer logs any `actions:` line — that moved to `walker`). Confirm `hello` is **not** gated:
   whatever the master switch is, `hello` loads.

2. **The master checkbox.** Open **Options → AddOns**. There is a new **"Allow addon actions (writes)"**
   checkbox (hover it for the notice). Its state reflects the current setting (if you still have
   `addons.actions.enabled=true` in `bin/haven-config.properties` from 4a it starts checked; you can remove
   that line now — the checkbox is the control). `walker` appears in the list tagged **`[actions]`** and
   **unchecked** (disabled by default — the D-027 opt-in).

3. **Default-disabled + enable it.** With `walker` unchecked, `:walker` does nothing (it isn't loaded). Check
   `walker`, make sure **"Allow addon actions (writes)"** is **on**, then **Reload UI**. `walker` now loads
   (`walker: write-actions GRANTED …`), and `:walker` walks your character ~2 tiles south.

4. **Master OFF unloads it.** Uncheck **"Allow addon actions (writes)"** and **Reload UI**. The log shows
   `skipping write-addon 'walker' — the actions master switch is OFF`; `:walker` is gone and its panel row
   reads **`blocked: actions off`** (still checked, but held back). Turn the switch back on + Reload UI to
   restore it. Throughout, `hello` keeps loading — the harness is never gated.

5. **The "seen" set is respected.** Once you've enabled `walker`, it stays enabled across reloads/relogs (it
   is not re-default-disabled). (Drop in *another* addon that declares `"actions"` to watch a genuinely-new
   write addon come up unchecked.)

## Limitations / deferred (later Phase-4 slices)

- **4c** — the **enable-time warning dialog** when checking a write addon (the base for the future
  per-category "this addon moves your character / interacts with objects" breakdown, built from the declared
  permissions).
- **4d–4g** — the actual action verbs (`clickGob`/`useItemOn`/`place`/`select`/`raw`, menu/flower, item verbs,
  and the folded-in per-subsystem gated verbs), all behind this same permission.
- "Enable all" enables write addons too (they still need the master switch on to load) — an explicit bulk user
  action; a per-write confirm could be added with 4c.
- No live panel refresh of a write row's status while the master switch is toggled (applies on reload, like
  the per-addon checkboxes).
