# 005-sandbox-reload-panel — Plan

> History: this work appears in git history and `learnings/` tagged **1f-1, 1f-2, 1f-3**
> (Phase 1f) + the panel-tooltip fix note.

## Approach
- **Sandbox constructively, not subtractively** (D-017): `new Globals()` + load ONLY the safe
  libs + the compiler — `io`/`luajava`/`debug`/`coroutine`/`bit32` are absent by construction.
  `PackageLib` loads (stdlib modules self-register in `package.loaded`) then
  `require`/`package`/`module` are stripped; dangerous `os.*` entries and base `load*` nil'd.
- **Watchdog layer 1 without exposing `debug`**: a `DebugLib` subclass **assigned** to
  `Globals.debuglib` (never `load()`-ed) — `onInstruction` fires but no `debug` table is
  reachable; `traceback`/`onCall`/`onReturn` no-op'd (assigned-not-loaded → null-`globals`
  NPE otherwise). `Sandbox.arm(env)` re-arms the budget before EVERY entry into Lua.
- **The `:lua` REPL stays unsandboxed** (trusted operator console, incl. `luajava`) but
  watchdog-armed — typo protection, documented so it isn't mistaken for a hole.
- **Reload = reuse the relogin path** (D-005): `reload()` = teardown-all (reverse order:
  `OnDisable` → flush → drop owned resources) + `loadAll` + restore per-char vars +
  **re-fire `OnEnterWorld`** (the WoW `PLAYER_LOGIN` analog). Queued to the UI-thread tick
  (`volatile reloadPending` — console commands arrive on UI or stdin threads); `synchronized`
  with session `init`. Session infra (tick pump, gob callback, uimsg tap) is untouched.
- **Enabled set = a persisted DISABLED set** (default-enabled, WoW) in client prefs
  (`Utils.getprefsl`, key `addons/disabled`); apply-on-reload (D-006).
- **Panel = the voice-panel pattern, addon-owned**: `AddonPanel extends OptWnd.Panel`
  cross-package (qualified `opt.super()`/`opt.new PButton`) driving the static facade — ONE
  `haven` line total. No listener (polls in `tick`) → no leak; rows capture only the id string
  → reload-safe; rebuilds on `reloadGen()` bumps.
- **Watchdog layer 2 rides the ONE Lua choke point**: `callLua` times each call into
  `Addon.tickLuaNanos`; `tick` zeroes at top, `enforceSoftBudget()` at end — >10 ms for 30
  CONSECUTIVE ticks (one good tick resets) → session-only auto-disable + panel warning. REPL
  exempt. Tunables `-Dhaven.addon.insncap/tickbudgetms/tickstrikes`.
- **`hogtest` is a dedicated dormant addon** (account var `cfg.arm`, default false) — folding
  a hog into `hello` would break the regression harness.

## Files created / modified
- `src/io/brodgar/addon/Sandbox.java` — new (env factory + Watchdog + budget constants)
- `src/io/brodgar/addon/AddonManager.java` / `AddonRegistry.java` (post-split home) — reload,
  enabled set, soft budget, panel data API (`AddonInfo`/`describeAddons`/`liveStatus`/…)
- `src/io/brodgar/addon/Addon.java` — `tickLuaNanos`/`overBudgetStrikes`
- `src/io/brodgar/addon/ui/AddonPanel.java` — new (the panel; later fix: tooltip rendered via
  `RichText.Parser.quote` + rich mode so long descriptions wrap instead of building a
  >16384 px one-line texture)
- `src/haven/OptWnd.java` — ONE `// addon:` line (the AddOns PButton)
- `addons/hogtest/` — new demo addon

## Risks & gotchas hit (detail: learnings/luaj-bridge.md, engine-lifecycle.md, ui-widgets.md)
- Assigned-not-loaded `DebugLib` → must override `traceback` or error hooks NPE.
- Headless enabled-set tests against an isolated `-Dhaven.prefspec` node (real prefs untouched).
- `Widget.settip(text, false)` renders ONE unbroken line — a 7000-char description overflowed
  `GL_MAX_TEXTURE_SIZE` and killed the render thread (the tooltip fix).
- `:addons` must scan the folder, not the loaded list (disabled addons are invisible otherwise).

## Discarded alternatives
- Blacklisting on top of `standardGlobals()` — absent-by-construction is the only safe shape.
- Sandboxing the REPL — it's the operator's trusted tool.
- Persisting auto-disable — session-only; the user's checkbox is the only persistent kill.
