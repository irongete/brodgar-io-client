# 005-sandbox-reload-panel — Spec

## What & why
The **operational safety layer** that makes addons trustworthy and the dev loop WoW-fast:
a strict constructive Lua sandbox (D-017) + a two-layer CPU watchdog (D-018), the `:reload`
addon-layer reload + a persisted enabled set (D-005/D-006), and the in-game **AddOns options
panel** (D-004). Before this, addons ran in full `standardGlobals()` — `io`, `os.execute` and
`luajava` (straight bypass of the `hafen.*` facade) included — and a `while true do end` froze
the client. Design: [design/12-security-and-permissions.md](../design/12-security-and-permissions.md),
[design/05-lifecycle-and-reload.md](../design/05-lifecycle-and-reload.md),
[design/10-options-panel.md](../design/10-options-panel.md).

## Acceptance criteria (verified in-game)
- [x] Sandbox: `hello`'s self-check logs `withheld={io,require,load,loadfile,dofile,debug,
      luajava,package,os.execute}; safe-stdlib=true; os.execute usable=false`; the safe stdlib
      (string/table/math/os-time subset/pcall…) remains.
- [x] Watchdog layer 1: `:lua while true do end` aborts with "instruction budget exceeded"
      (~36 ms at the default 10 M cap) — client keeps running.
- [x] `:reload`: edit `hello`'s reload-marker line → `:reload` → `OnDisable` fires, files
      re-run from disk, `OnEnterWorld` re-fires (login counters tick), the new text appears
      **with no relog**; after several reloads events/timers fire once per interval (no leaks).
- [x] Enabled set: `:addons disable hello` → pending → `:reload` skips it; re-enable loads it
      again; persisted in client prefs, default-enabled for new addons.
- [x] Panel (Options → AddOns): one row per discovered addon (checkbox → enabled set,
      name·version·author, description tooltip, live status incl. `auto-disabled`), Reload UI /
      Enable all / Open addons folder buttons, "changes pending" hint.
- [x] Watchdog layer 2: an armed `hogtest` (~15 ms/tick, over the 10 ms soft budget × 30
      consecutive strikes) is auto-disabled **for the session** with a panel warning; `hello`
      unaffected; a reload gives it a fresh start (never overrides the user's checkbox).
- [x] Fix: hovering a row with a very long manifest description no longer crashes the render
      thread (`GL_INVALID_VALUE` — unwrapped one-line tooltip texture wider than
      `GL_MAX_TEXTURE_SIZE`); tooltip now renders wrapped.

## Out of scope
- Live enable/disable and `:reload <id>` (→ ROADMAP); per-addon config sub-panels; controlled
  addon-folder `require`; per-env string metatable hardening (→ ROADMAP).

## Context files
- `design/12-security-and-permissions.md`, `design/05-lifecycle-and-reload.md`,
  `design/10-options-panel.md`, `design/04-engine.md`
- `src/io/brodgar/addon/Sandbox.java` — env whitelist + both watchdog layers
- `src/io/brodgar/addon/AddonRegistry.java` — loadAll/reload/enabled set/panel data API
- `src/io/brodgar/addon/ui/AddonPanel.java` — the panel (voice-panel pattern)
- `src/haven/OptWnd.java` — the single `// addon:` line (panel button)
- `addons/hogtest/` — the CPU-hog demo addon (dormant by default)
- `docs/addons/api/console.md` — `:addons`/`:reload` surface
- `../001-bootstrap-engine/` — the load/teardown lifecycle this hardens
