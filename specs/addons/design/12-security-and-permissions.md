# Security, Sandbox & Permissions

> **Status:** 🟠 Outline · **Spec:** AddOns
> **Related:** [04-engine.md](04-engine.md), [06-lua-api.md](06-lua-api.md), [DECISIONS.md](../DECISIONS.md) (D-010), [Q-001](../DECISIONS.md), [Q-007](../DECISIONS.md)

Three separate concerns: **server authority** (a hard boundary), the **write-actions permission**
(a user-control decision), and the **Lua sandbox** (protecting the user's machine and the client from
addon code).

## Server authority (hard boundary)

The client is **server-authoritative**. Addons act only through
[`Widget.wdgmsg`](src/haven/Widget.java:737), i.e. they can do **only what a player could click**:
- No teleport, no movement the server wouldn't accept.
- No seeing beyond what the server streams (no map/entity data the client doesn't already have).
- No client-only state changes that matter to the server.

This is a property of the architecture, not a feature we add or restrict. It bounds the whole
system: the worst an addon can do to *gameplay* is drive legal actions faster/automatically — which
is why write-actions are an **explicit permission** (below), a user-control matter, not a client
exploit.

## Write-actions: an explicit, per-addon permission (the important design point)

- **Reading state, custom UI, overlays, and UI replacement are pure "modding."** They change
  presentation, not what the character does. This is the bulk of the system (Phases 1–3), and it is
  ungated.
- **Write-actions let an addon act on your behalf** — move your character, use items, interact with
  the world. That is powerful and consequential, so it is a **declared, per-addon permission** you
  grant knowingly, like app permissions: you opt in, and you can see **which** addons are allowed to
  drive your character before you enable them.

Design consequences ([D-010](../decisions/actions-permissions.md), [D-027](../decisions/actions-permissions.md), [D-028](../decisions/actions-permissions.md), [Q-001](../DECISIONS.md)):
- The **actions tier is a separate, later phase** (Phase 4).
- It is a **per-addon permission** ([D-028](../decisions/actions-permissions.md), which dropped [D-027](../decisions/actions-permissions.md)'s global
  master switch): a verb is granted to an addon **only** if that addon **declared**
  `"permissions": ["actions"]` in its manifest. The action tier itself is always available at the system
  level — there is no global switch to also flip.
- **The user grants it per addon, by enabling the addon.** A write-declaring addon is **disabled by
  default** (opt-in), and enabling it in the AddOns panel raises a **consent dialog** (slice 4c) that says
  it will act on your behalf. Enabling-with-consent **is** the grant; disabling revokes it. So a running
  write addon is, by construction, one the user knowingly permitted.
- This keeps the user in control of **which** addons act, surfaced clearly at the moment of granting.

> Note: custom clients and automation are an **established, normal** part of Haven & Hearth. This
> permission exists purely so **you** stay in control of which addons act on your behalf and know what
> they do — nothing more.

## Lua sandbox (protecting the machine & the client)

Even for a personal client, the sandbox matters — addons may be **shared between users**, and it
prevents buggy/hostile addons from touching the filesystem or hanging the UI. LuaJ makes the
stdlib opt-in.

### Environment restrictions ([Q-007](../DECISIONS.md))
Default-strict per-addon environment ([04-engine.md](04-engine.md)):

- **Expose:** `string`, `table`, `math`, `os.time`/`os.clock`/`os.date`, `select`, `pairs`/
  `ipairs`, `next`, `type`, `tostring`/`tonumber`, `pcall`/`xpcall`/`error`/`assert`, `unpack`.
- **Withhold:** `io` (no arbitrary file access — saved data goes through `hafen.store` only),
  `os.execute`/`os.exit`/`os.getenv`/`os.remove`/`os.rename`, `dofile`/`loadfile`/`load` of
  arbitrary paths, unrestricted `require`, and `debug` (or a restricted subset).
- **Controlled `require`:** if provided, resolves **only within the addon's own folder**
  (`ADDON.dir`), never arbitrary filesystem or Java classes.
- **No Java reflection from Lua.** Addons get the `hafen.*` facade only; they cannot reach
  arbitrary Java classes (which would bypass all of the above and [P1](01-architecture.md)).

### Runaway protection (watchdog) ([Q-009](../DECISIONS.md))
Because Lua runs on the UI/render thread, an infinite loop freezes the client. Two layers:
- **Hard stop:** a LuaJ instruction-count / wall-clock hook aborts a single callback.
- **Soft budget:** a per-addon per-tick time budget; repeat offenders are auto-disabled.
See [04-engine.md](04-engine.md).

### Error isolation
Every call into Lua is protected; errors are logged and surfaced in the AddOns panel, never crash
the tick or other addons ([04-engine.md](04-engine.md)).

### Resource ownership as a safety property
[P2](01-architecture.md): the bridge owns every resource an addon creates, so a disabled/reloaded
addon cannot leave dangling widgets, callbacks, or timers firing into a dead environment
([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).

## Threat model summary

| Concern | Mitigation |
|---|---|
| Addon reads/writes arbitrary files | No `io`; saved data only via `hafen.store` under `savedata/` |
| Addon runs shell commands | No `os.execute`/`os.exit` |
| Addon hangs the client | Watchdog (hard stop + soft budget) |
| Addon crashes the client | Per-callback error isolation |
| Addon leaks on reload/disable | Bridge-owned resource registry + teardown |
| Addon reaches Java internals | Facade-only; no reflection; no arbitrary class loading |
| Addon acts on your behalf without your knowledge | Write-actions gated by a declared per-addon permission, granted by enabling the addon with a consent dialog ([D-027](../decisions/actions-permissions.md)/[D-028](../decisions/actions-permissions.md)) |
| Addon can cheat vs server | Impossible: server-authoritative; addons only send legal `wdgmsg` |

## Open items

- Final stdlib whitelist ([Q-007](../DECISIONS.md)).
- Whether any trusted/first-party addons get relaxed permissions.
- Watchdog limits and configurability ([Q-009](../DECISIONS.md)).
- ~~Exact wording and placement of the write-actions permission notice.~~ **Resolved** (slice 4c):
  the enable-time consent dialog (`ActionsConsentWnd`) — see [10-options-panel.md](10-options-panel.md)
  and [D-028](../decisions/actions-permissions.md).
