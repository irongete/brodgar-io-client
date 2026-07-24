# Phase 1f-2 — Reload UI + enabled set

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **14/14 headless
> checks** on the enabled-set persistence (default-enabled, disable/enable round-trip, idempotency,
> persistence across a fresh read, null/empty no-op — run against an isolated prefs node so the real
> client prefs are untouched) + LuaJ parse of the updated `hello`. The reload *sequence* needs the live
> game (session + widgets) and is verified by the in-game DoD below. **In-game verification pending.**
> **Design:** [specs/addons/05-lifecycle-and-reload.md](../../specs/addons/05-lifecycle-and-reload.md)
> (reload sequence + teardown), [specs/addons/04-engine.md](../../specs/addons/04-engine.md),
> decisions **D-005** (Reload UI reloads the addon layer only) + **D-006** (enable/disable uses the WoW
> "apply on reload" model).

The second slice of **1f**. It adds the **WoW dev loop** to the client: edit a `.lua`, run **`:reload`**,
and see the change **without relogging** — plus a persisted **enabled set** so individual addons can be
turned off/on. Both are operator-facing (console for now; the AddOns options panel is 1f-3, D-004).

## `:reload` — reload the addon layer only (D-005)

`:reload` rebuilds **only the addon layer** — every addon's Lua state and every resource it created —
from disk, keeping the session connected and the native/server-driven Java UI untouched. It does **not**
rebuild server-driven widgets (inventory, menus, HUD): in Hafen those exist only because the server sent
them, and the client cannot recreate them without a reconnect (that would be a relog). Reloading the
addon layer is the part of the WoW intent that matters here.

**Sequence** (`AddonManager.reload()`, on the UI thread):

1. **Tear down** every loaded addon in **reverse load order** — fire `OnDisable`, **flush** its saved
   variables, then drop its owned resources (event subscriptions + timers). This reuses the exact
   `teardown(Addon)` that a relogin already uses, so reload and relogin can't drift.
2. **Re-scan** `addons/`, re-read each `manifest.json` **and the enabled set**, and **re-run** each
   enabled addon's files from disk → fire `OnLoad` (this is `loadAll()`, shared with session init).
3. If already **in-world**, **restore per-character saved variables** and **re-fire `OnEnterWorld`** so
   addons re-initialize as if freshly logged in — the WoW `PLAYER_LOGIN` analog. (So an addon's
   login-counter saved vars also tick on each reload; that is expected.)

Per-addon steps are error-isolated, so one addon failing to tear down or load never aborts the whole
reload. What is **not** touched: the per-frame tick pump (`AddonRoot`), the `OCache` gob callback, and
the inbound-`uimsg` tap are all **session-scoped** — reload leaves them in place and only rebuilds the
Lua layer on top.

### Threading — queued onto the UI-thread tick

Reload destroys/creates state and (from Phase 2 on) widgets, so it must run on the **UI thread**. But a
console command can arrive on either the UI thread (in-game chat → `ConsoleHost.done`) or the stdin
reader thread (terminal `:` line → `Chatwindow`). So `:reload` just **queues** it (`reloadPending`), and
the tick pump runs the actual reload at the top of the next frame (mirroring how `enterWorldPending`
defers `OnEnterWorld`). `reload()` is `synchronized` on the same monitor as session `init()`, so a reload
and a concurrent relogin serialize cleanly instead of interleaving.

### Teardown = no leaks (the correctness core, P2)

Because the bridge is the **sole factory** for everything an addon creates (P1 + P2), teardown is
complete: `Addon.subs` and `Addon.timers` are the owned-resource registry, cleared on teardown; the Lua
environment is dropped for GC. There is no way for an addon to stash a widget/callback the bridge didn't
record, so nothing survives to fire into a dead env. (Custom widgets/overlays arrive in Phase 2 and plug
into the same registry; the spec already notes `Window.reqdestroy()` is async, so their teardown will
call `destroy()`/`remove()` directly.)

## Enable / disable — the WoW "apply on reload" model (D-006)

Toggling an addon **does not** load/unload it live; it updates a **persisted set** and takes effect on
the next `:reload` (or login). Internally this is a **disabled set** (an addon runs *unless* its id is in
it), so a freshly-installed addon **defaults to enabled** — the WoW default. It is stored in the
client's own preferences (`Utils.getprefsl`/`setprefsl`, key `addons/disabled`), i.e. **under the client
folder, not per-character**. `loadAll()` skips any disabled id, so both a fresh login and a reload honor
the set.

Console surface:

```
:addons                  list every discovered addon + status  ->  hello [v0.12.0], other [disabled]
:addons disable hello    mark hello disabled  (pending — run :reload to apply)
:addons enable  hello    mark hello enabled   (pending — run :reload to apply)
:reload                  apply: rebuild the addon layer from disk
```

`:addons` scans the `addons/` folder (not just what's loaded), so it lists disabled addons too and flags
`(changes pending — run :reload to apply)` after a toggle. `AddonManager.isEnabled(id)` /
`setEnabled(id, bool)` are the public accessors the **1f-3 AddOns panel** will drive from its checkboxes.

## Implementation (zero `haven` edit)

Pure engine — only `io.brodgar.addon.AddonManager` changed; no `haven` files were touched.

- **`reload()`** (public, `synchronized`, UI thread) — the 3-step sequence above, reusing `teardown` +
  `loadAll` + `restorePerChar` so nothing is re-implemented.
- **`queueReload()`** + a `volatile reloadPending`, checked first in `tick(dt)` (and reset in `init()` so
  a reload queued against a dead session is dropped).
- **`disabledSet()` / `isEnabled(id)` / `setEnabled(id, on)`** over `Utils.getprefsl`/`setprefsl`
  (`addons/disabled`); `setEnabled` is idempotent and flags `reloadNeeded`.
- **`loadAll()`** now reads the disabled set once and skips disabled folders; it clears `reloadNeeded`
  (whatever is on disk after a load *is* the applied set).
- **`listAddons()`** rewritten to scan the folder and report each addon's status (loaded version /
  `disabled` / `not loaded` / `error`) plus the pending-changes hint.
- Console: `:addons` gained `enable`/`disable` subcommands; `:reload` registered.

## `hello` demo

Bumped to **v0.12.0**. The header documents the reload cycle, and the first `OnLoad` handler now logs a
**reload marker** you can edit to see the dev loop, plus an account-scoped **`acct.loads`** counter that
proves saved variables survive a reload:

```
OnLoad fired — reload marker: edit me and :reload  [OnLoad #3]
```

The existing `OnDisable` handler (`"OnDisable fired"`) makes the teardown visible, and the existing
per-character/account login counters (1e) demonstrate that `OnEnterWorld` re-fires on reload. The full
read battery (`readInv`/`readChar`/`readVitals`/…) re-runs on the re-fired `OnEnterWorld`, so a reload
also re-exercises every prior feature — a one-command regression pass.

## How to test in-game (DoD)

1. Log in; confirm `hello loaded (v0.12.0)`, `OnLoad fired — reload marker … [OnLoad #1]`, and the usual
   `entered the world` + read battery.
2. **Edit the reload-marker line** in `addons/hello/main.lua` (change its text), then run **`:reload`** in
   the console. Expect: `OnDisable fired` → `reloading addons…` → `hello loaded (v0.12.0)` → the **new**
   marker text with `[OnLoad #2]` → `entered the world` again → `reload complete (1 addon[s] active)`.
   The change appears **with no relog**, and the load counter incremented (saved vars survived).
3. **Enabled set:** `:addons` → `hello [v0.12.0]`. Then `:addons disable hello` → “pending”; `:reload` →
   `skipping disabled addon 'hello'`, no `hello loaded` line, `:addons` shows `hello [disabled]`. Then
   `:addons enable hello` → “pending”; `:reload` → `hello` loads again.
4. **No leaks:** after several `:reload`s, the heartbeat/timer/event logs still appear **once** per
   interval (not doubled) — teardown released the old subscriptions.

## Deferred (later 1f slices / follow-ups)

- **`:reload <id>`** (single-addon), **live enable/disable** (no full reload) — spec 05 lists these as
  cheap enhancements once teardown is proven; v1 follows the WoW full-reload model.
- **AddOns options panel (1f-3):** the in-game panel (D-004) with enable/disable checkboxes (driving
  `setEnabled`), a Reload UI button (`reload()`), and per-addon status/error display — plus the D-018
  soft per-tick budget + auto-disable, whose warning surfaces there.
