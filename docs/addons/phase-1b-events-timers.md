# Phase 1b — Tick pump, events & timers

> **Status:** ✅ Implemented; compile + headless logic verified. In-game check pending.
> **Design:** [specs/addons/04-engine.md](../../specs/addons/04-engine.md) (tick pump),
> [specs/addons/09-events-catalog.md](../../specs/addons/09-events-catalog.md) (events),
> [specs/addons/01-architecture.md](../../specs/addons/01-architecture.md) (threading, P4/P5).

Addons stopped being run-once scripts: the engine now **ticks every frame**, synthesizes an **event
bus**, and runs **timers**. An addon reacts to the game over time instead of only at load. This is
all **zero core edit** — it reuses the existing `RemoteUI.init` and `MapView` hooks plus the public
`OCache.callback`.

## The tick pump (how the engine runs each frame)

The engine attaches an **invisible, zero-size widget** (`AddonRoot`) to `ui.root` once per session.
Hafen broadcasts a per-frame tick to every widget, so `AddonRoot.tick(dt)` is called **on the UI
thread**, under the UI lock, right after the game state has advanced — with no change to `haven`.
Each tick, in order, the engine:

1. drains gob spawn/despawn captured on other threads → fires `GobAdded` / `GobRemoved`,
2. fires `OnEnterWorld` once, when the world becomes ready,
3. fires `OnUpdate(dt)`,
4. runs any due timers.

Everything the engine calls into Lua is **error-isolated**: a Lua error is caught, logged (with its
`file:line`), and never breaks the frame or another addon.

## Events — `hafen.events`

```lua
local sub = hafen.events.on("OnUpdate", function(dt) ... end)
sub:off()   -- unsubscribe (also released automatically on reload/disable)
```

`hafen.events.on(name, fn)` registers a handler and returns a subscription with `:off()`.
Handlers **always run on the UI thread**. Events available in this phase:

| Event | Payload | When |
|---|---|---|
| `OnLoad` | — | right after this addon's files finish running |
| `OnEnterWorld` | — | when the map view comes up (you're in the world) |
| `OnUpdate` | `dt` (seconds) | every frame |
| `OnDisable` | — | on teardown (reload / relog / disable) |
| `GobAdded` | `{ id, x, y }` | a game object appeared near you |
| `GobRemoved` | `{ id, x, y }` | a game object disappeared |

**Snapshots, not handles.** `GobAdded`/`GobRemoved` hand you a plain Lua table, not a live object.
The gob table is minimal for now (`id`, `x`, `y`); the full attribute set arrives with the read API
(Phase 1c). Prefer `GobAdded`/`GobRemoved` over scanning every frame — it's cheaper and idiomatic.

### Lifecycle ordering
`OnLoad` fires **per addon** as it loads (like WoW's `ADDON_LOADED`). `OnEnterWorld` fires once the
world is up (like `PLAYER_ENTERING_WORLD`) — this is where reading `hafen.gob.pos("player")` works,
since at `OnLoad` the world doesn't exist yet.

## Timers — `hafen.timer`

```lua
hafen.timer.after(2, function() hafen.log("2 seconds later") end)   -- fires once
local t = hafen.timer.every(1, function() hafen.log("every second") end)
t:cancel()   -- stop a repeating (or pending one-shot) timer
```

- `hafen.timer.after(seconds, fn)` — fires **once**, ~`seconds` after it's created.
- `hafen.timer.every(seconds, fn)` — fires **repeatedly**, every `seconds` (at most once per frame;
  it won't try to "catch up" multiple fires after a lag spike).
- Both return a handle with `:cancel()`.

Timers are driven by the tick clock (accumulated frame `dt`), so they pause when the client isn't
ticking and never run off the UI thread.

## Try it from the `:lua` console

The `:lua` REPL is itself a resource owner, so events/timers work there for quick tests:

```
:lua hafen.timer.after(2, function() hafen.log("timer fired!") end)
:lua hafen.events.on("OnUpdate", function(dt) hafen.log("dt="..dt) end)
```
> The REPL's subscriptions **persist across relogs** (the REPL env is long-lived) and `OnUpdate`
> logs every frame — handy for a one-off check, spammy if you leave it on. Re-log or restart to
> clear REPL subscriptions.

## The `hello` example (`addons/hello/main.lua`)

Now subscribes to `OnLoad`, `OnEnterWorld`, a throttled `OnUpdate` heartbeat, `GobAdded`, and a
one-shot `timer.after(2, …)`. See the file for the full, commented version.

## How to test

```
ant run
```
On login, the **terminal** shows the addon loading and `OnLoad`:
`[hello] hello loaded (v0.2.0)`, `[hello] OnLoad fired`.
Then, once you're in the world:
- `[hello] entered the world` (and `player at X, Y` once the player gob resolves) — **`OnEnterWorld`**,
- `[hello] timer.after(2) fired once` about 2 seconds later — **timers**,
- `[hello] GobAdded id=… (N so far)` as objects stream in — **gob events**,
- `[hello] tick heartbeat (dt=…)` every ~5 s — **`OnUpdate`**.

Quick REPL checks: `:lua hafen.timer.after(2, function() hafen.log("fired") end)` and
`:addons` (should list `hello`).

## Files

- `src/io/brodgar/addon/AddonRoot.java` — the invisible per-frame tick widget (new).
- `src/io/brodgar/addon/AddonManager.java` — tick pump, event bus (`hafen.events`), timers
  (`hafen.timer`), lifecycle firing, `OCache` gob source, per-addon error isolation.
- `src/io/brodgar/addon/Addon.java` — per-addon owned-resource registry (`subs`, `timers`).
- `src/io/brodgar/addon/Manifest.java` — `internal(id)` synthetic manifest for the `:lua` owner.
- `addons/hello/` — example updated to exercise events + timers.
- **No `haven` core edits** — reuses the Phase 0/1a hooks (`RemoteUI.init`, `MapView.attach`).

## Limitations (next phases)

- Gob snapshots are `{id, x, y}` only — the full read API (`hafen.world`, richer `hafen.gob.*`) is
  Phase 1c.
- No sandbox hardening / watchdog yet (a runaway Lua loop can still hang the UI thread) — Phase 1f.
- No `hafen.store` (saved variables) — Phase 1e. No enable/disable, options panel, or `:reload` — 1f.
- One-liner events (`ChatMessage`, `InventoryChanged`, …) come later (see the events catalog).
