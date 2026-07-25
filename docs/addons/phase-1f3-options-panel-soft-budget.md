# Phase 1f-3 — AddOns options panel + soft CPU-budget auto-disable

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **16/16 headless checks**
> on the soft-budget logic (strikes accumulate → auto-disable at the limit, a single under-budget tick
> resets the count, `== budget` never strikes, `callLua` accumulates wall time, the REPL owner is exempt)
> + LuaJ parse of the new `hogtest` addon under the sandbox. The panel is in-game UI (D-004) and is
> verified by the in-game DoD below. **In-game verification pending.**
> **Design:** [specs/addons/10-options-panel.md](../../specs/addons/10-options-panel.md) (the panel),
> [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md) +
> [specs/addons/04-engine.md](../../specs/addons/04-engine.md) (the watchdog), decisions **D-004**
> (in-game only), **D-006** (enable/disable = WoW apply-on-reload), **D-018** (watchdog: layer 1 = the
> per-call instruction cap of 1f-1, **layer 2 = this soft per-tick budget**).

The final slice of **1f**, and it closes Phase 1f. Two pieces: a WoW-style **AddOns options panel** in
the in-game Options window, and the **soft per-tick CPU budget + auto-disable** that layer-1's hard
instruction cap can't catch. The panel is where an auto-disabled addon's warning surfaces, so they ship
together.

## The AddOns panel (Options → AddOns)

A clone of the voice-integration pattern (`OptWnd.VoiceChatPanel` + a main-list `PButton`), but the
panel itself lives in a **new `io.brodgar.addon.ui.AddonPanel`** — spec 10's intended home. It needs no
package-private `haven` access: like the voice panel drives the all-static `Voice`, this one drives the
all-static **`AddonManager`** facade. The only `haven` edit is **one line** in
[`OptWnd`](../../src/haven/OptWnd.java) adding the button (tagged `// addon:`).

It extends `OptWnd.Panel` (a non-static inner class) from another package via the qualified
`opt.super()` / `opt.new PButton(...)` forms; every widget it uses (`Scrollport`, `CheckBox`, `Button`,
`Label`) is a public `haven` type.

**Contents** — one row per **discovered** addon (the folder scan, not just what's loaded):

- an **enable/disable checkbox** — writes the persisted enabled set via `AddonManager.setEnabled(id, on)`
  (WoW "apply on reload", D-006: the toggle takes effect on the next reload/login, never live);
- **name · version · author** from the manifest, with the **description as a tooltip**;
- a **live status** — `loaded vX.Y` / `disabled` / `error: …` / **`auto-disabled (…)`** — refreshed
  cheaply each frame via `AddonManager.liveStatus(id)`.

Global controls: **Reload UI** (`AddonManager.requestReload()` — queues the 1f-2 addon-layer reload),
**Enable all**, **Open addons folder** (`java.awt.Desktop`, best-effort), and a **"Changes pending -
Reload UI to apply."** hint driven by `AddonManager.reloadNeeded()`.

**No listener, no leak.** Unlike the voice panel (which registers/unregisters a `VoiceListener`), this
panel **polls** the facade in `tick()` — it holds no subscription, so there is nothing to unregister on
close. Rows capture only the addon **id** (a `String`) and call `AddonManager` statics; they never
reference an `Addon` object or a Lua env, so a reload that tears addons down leaves no dangling ref. The
panel watches `AddonManager.reloadGen()` (bumped by each completed reload) and rebuilds its rows when the
addon set changes — so a `:reload`, the Reload UI button, and Enable-all all refresh the list.

### The data API on `AddonManager` (all public, for the panel)

| Method | Use |
|---|---|
| `describeAddons()` → `List<AddonInfo>` | Row list (reads each manifest from disk; call on build/reload) |
| `liveStatus(id)` → `String` | Per-frame status label (cheap; no disk I/O) |
| `isEnabled(id)` / `setEnabled(id, on)` | Checkbox state / toggle (from 1f-2) |
| `reloadNeeded()` / `reloadGen()` | "changes pending" hint / rebuild trigger |
| `requestReload()` | Reload UI button |
| `openAddonsFolder()` | Open addons folder button |

`AddonInfo` is an immutable public snapshot `{id, name, version, author, description, apiVersion,
enabled, loaded, error, warning}`.

## Soft per-tick CPU budget + auto-disable (D-018 layer 2)

Layer 1 (1f-1) is a **per-call** instruction cap (~10 M instructions ≈ 36 ms) that aborts a single
runaway callback. It cannot catch an addon whose *individual* handlers each stay under the cap but which
**burns most of every frame** — sustained jank rather than a hard freeze. Layer 2 bounds an addon's
**total Lua time within one engine tick**, summed across its `OnUpdate`, timers, and event handlers.

**Mechanism** (all in `AddonManager`, on the UI thread — the one place all Lua is dispatched):

- **`callLua`** — the single choke point for every entry into Lua — wraps `fn.invoke(...)` in
  `System.nanoTime()` and adds the elapsed time to the owner's `Addon.tickLuaNanos`.
- **`tick(dt)`** zeroes every addon's `tickLuaNanos` at the top of the tick (skipped on a reload tick,
  which returns early — its `OnLoad`/`OnEnterWorld` are one-offs), and calls **`enforceSoftBudget()`** at
  the end.
- **`enforceSoftBudget()`** — an addon over `Sandbox.SOFT_BUDGET_NANOS` (default **10 ms**, strict `>`)
  adds a strike; one tick **at or under** budget clears the count (it must be **sustained**, so a heavy
  `OnEnterWorld` or a single janky frame doesn't count). On reaching `Sandbox.SOFT_STRIKE_LIMIT` (default
  **30**) **consecutive** over-budget ticks, the addon is **auto-disabled**.
- **`autoDisable(a, reason)`** — records a warning (surfaced in the panel + logged), runs the addon's
  **teardown** (`OnDisable` → flush saved vars → drop owned resources — the same 1f-2 teardown), and
  removes it from the live set so it stops ticking. Runs at end of tick, so mutating `addons` is safe.

**Session-only, not persisted (deliberate).** Auto-disable does **not** touch the persisted enabled set
(the checkbox) — a `:reload`/login gives the addon a fresh start (`loadAll()` clears the warnings). This
matches the spec ("auto-disabled *for the session*"), never silently overrides the user's explicit
choice, and is failure-safe (a transient spike can't permanently kill a good addon). To make it stick,
the user un-ticks the addon in the panel. The **`:lua` REPL owner is exempt** — it isn't in `addons`
(the sandbox constrains shared addon code, not the operator's own console).

**Tunables** (read once at class-load, like `insncap`): `-Dhaven.addon.tickbudgetms` (ms; `<= 0`
disables the soft budget) and `-Dhaven.addon.tickstrikes`.

## `hogtest` — the soft-budget test addon (dormant by default)

A dedicated example addon `addons/hogtest/` (folding a CPU hog into the `hello` regression harness would
break it — the PLAN blesses a dedicated addon for a distinct feature). It is **dormant by default**:
gated on an **account-scope saved var** `cfg.arm` (default `false`, seeded on first run), so a normal
login is undisturbed and `hello`'s regression run is unaffected.

When **armed**, its `OnUpdate` busy-waits ~15 ms on `os.clock` every tick — over the 10 ms budget but
well under the hard per-call cap — so it strikes every tick and is auto-disabled after ~30 ticks, with
the warning appearing on its AddOns-panel row.

**To arm it** (see the file header): set `"arm": true` inside `savedata/account/hogtest.json` (shape:
`{"cfg":{"arm":false}}`) — either edit the file, or from the unsandboxed `:lua` console:

```
:lua io.open("savedata/account/hogtest.json","w"):write('{"cfg":{"arm":true}}'):close()
```

then `:reload`. To stop: set `"arm": false` (or un-tick hogtest in the panel), then `:reload`.

## Core edits

- **`haven/OptWnd.java`** — **one line** (`// addon:`): a `PButton "AddOns"` in the main options list
  that opens `io.brodgar.addon.ui.AddonPanel`.
- **Engine (`io.brodgar.addon`):**
  - `Sandbox` — new `SOFT_BUDGET_NANOS` + `SOFT_STRIKE_LIMIT` constants (+ the class doc updated: layer 2
    is no longer a "follow-up").
  - `Addon` — new `tickLuaNanos` + `overBudgetStrikes` per-tick accounting fields.
  - `AddonManager` — `callLua` timing; the `tick` reset + `enforceSoftBudget`/`autoDisable`; the panel
    data API (`AddonInfo`, `describeAddons`, `liveStatus`, `reloadNeeded`, `reloadGen`, `requestReload`,
    `openAddonsFolder`); `autoDisabledWarn` (cleared in `loadAll`); `reloadGen++` in `reload()`.
  - **new `io.brodgar.addon.ui.AddonPanel`** — the panel.
- **New addon:** `addons/hogtest/` (`manifest.json` + `main.lua`).

## How to test in-game (DoD)

1. **Panel opens & lists:** log in, open **Options → AddOns**. Expect a row per addon —
   `[x] Hello  v0.12.0  brodgar   loaded v0.12.0` and `[x] Hog Test  v0.1.0  brodgar   loaded v0.1.0`
   (hogtest logs `hogtest dormant …`). Hover a row → the manifest description tooltip.
2. **Enable/disable + Reload UI (no leaks):** un-tick **Hog Test** → the hint shows "Changes pending".
   Click **Reload UI** → the row shows `disabled`, and after several reloads the `hello` heartbeat/timer
   logs still appear **once** per interval (teardown released the old subscriptions). Re-tick + Reload UI
   → it loads again. **Enable all** re-checks every box.
3. **Soft-budget auto-disable + panel warning:** arm hogtest (above) and `:reload`. Expect a brief
   (~1 s) hitch, then a console line `hogtest: AUTO-DISABLED this session by the CPU watchdog (…)` and
   the AddOns row for **Hog Test** now reads **`auto-disabled (>10ms/tick x30 ticks; …)`**. The client
   keeps running; `hello` is unaffected. Disarm (`arm=false`) or un-tick it, then `:reload` to clear.
4. **Regression:** the full `hello` read battery (vitals/buffs/study/actionbar/items/char/party/… +
   saved-var counters) still runs on enter-world/reload, unchanged.

## Deferred

- **Live enable/disable** (tear down / start one addon without a full reload) — cheap now that per-addon
  teardown is proven, but v1 keeps the WoW full-reload model (D-006).
- **Per-addon config sub-panel** (a manifest `config` entry opening an addon-provided `hafen.ui` panel)
  — waits on Phase 2 UI.
- **"Load out of date addons"** toggle — only meaningful once `api_version` gating is enforced.
- Per-env string metatable hardening (the one remaining sandbox gap noted in 1f-1) — a later refinement.

**This completes Phase 1f** (sandbox + reload/enabled-set + options panel + full watchdog).
