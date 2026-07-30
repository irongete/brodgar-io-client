# 005-sandbox-reload-panel — Tasks

- [x] 005.1 — Lua sandbox: constructive env whitelist (`Sandbox.create`) + instruction
      hard-stop watchdog (`Globals.debuglib` assigned, `arm()` per entry); REPL stays
      unsandboxed but armed.
- [x] 005.2 — `:reload` (addon-layer only, queued to the UI tick, reuses teardown/loadAll,
      re-fires `OnEnterWorld`) + persisted enabled set (`:addons enable|disable`,
      default-enabled, apply-on-reload).
- [x] 005.3 — AddOns options panel (`AddonPanel`, one `haven` line) + soft per-tick CPU
      budget with session-only auto-disable + `hogtest` demo addon.
- [x] 005.4 — Fix: panel row tooltip overflowed `GL_MAX_TEXTURE_SIZE` (GL_INVALID_VALUE
      crash) — render wrapped via `RichText.Parser.quote` + rich mode.
