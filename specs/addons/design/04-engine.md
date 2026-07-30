# Engine

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [01-architecture.md](01-architecture.md), [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md), [12-security-and-permissions.md](12-security-and-permissions.md)

The engine is `AddonManager` (+ helpers) in `src/io/brodgar/addon/`. It discovers, loads, runs,
ticks, and reloads addons, and hosts the Lua runtime.

## Lua runtime: LuaJ

- **Choice:** [LuaJ](https://github.com/luaj/luaj) (`org.luaj:luaj-jse`, reference version
  `3.0.1`), a pure-Java Lua 5.2 implementation. ([D-003](../decisions/filesystem-build.md))
- **Why:** the client is pure Java and cross-platform (JOGL/LWJGL on Windows/Linux/macOS); LuaJ
  ships as a single jar exactly like the voice jar, with no native dependency. Performance is
  interpreted (slower than LuaJIT) but ample for UI/logic — it never runs the render pipeline.
- **Packaging:** jar handled by Ant, not committed ([Q-003](../DECISIONS.md)).

## Per-addon environments

- Each addon gets **its own environment** (a distinct `Globals`, or a shared `Globals` with a
  per-addon `_ENV` sandbox — implementation choice, TBD). This isolates addon globals so two
  addons cannot clobber each other, and lets the engine drop an addon's environment on reload
  for GC.
- Injected into each environment: `hafen` (the facade), `ADDON` (`{id,name,version,dir}`), and a
  **safe subset** of the Lua stdlib.
- **Sandbox** ([Q-007](../DECISIONS.md), [12-security-and-permissions.md](12-security-and-permissions.md)):
  default-strict. Expose `string`, `table`, `math`, `os.time`/`os.clock`, `pcall`/`error`,
  `select`, `pairs`/`ipairs`, `tostring`/`tonumber`, etc. **Withhold** `io`, `os.execute`,
  `os.exit`, `os.getenv`, unrestricted `require`/`loadfile`/`dofile`, and `debug` (or a
  restricted `debug`). A controlled `require` may resolve only within the addon's own folder.

## The tick pump

Per [P5](01-architecture.md) all Lua runs on the UI thread. The engine is driven from the
per-frame tick:

- **Mechanism (zero core edit):** the engine attaches an **invisible addon-root widget** to
  `ui.root`. Its [`tick(double dt)`](src/haven/Widget.java:748) is invoked by the
  [`UI.tick`](src/haven/UI.java:371) `TickEvent` broadcast — on the UI thread, under
  `synchronized(ui)`, after [`Glob.ctick()`](src/haven/Glob.java:143) has advanced game state.
- **One-line alternative:** call `AddonManager.tick(dt)` directly at
  [`UILoop.java:445`](src/haven/UILoop.java:445) (right after `ui.tick()`). Either is acceptable;
  the invisible-widget path avoids a core edit and also provides the overlay draw hook (its
  `draw` paints addon overlays). See [07-ui-and-drawing.md](07-ui-and-drawing.md).

Each tick the engine, in order:
1. Drains the **event queue** (events captured since the last tick from non-UI threads, plus
   synchronous ones), dispatching to subscribers.
2. Fires **`OnUpdate(dt)`** to addons that registered for it.
3. Runs due **timers** (`hafen.timer`).
4. Flushes throttled **saved-vars** if dirty.

All wrapped by the watchdog (below) and per-callback error isolation.

## Error isolation

- Every call **into Lua** (event handlers, `OnUpdate`, timers, widget callbacks, draw callbacks)
  is wrapped in a protected call. A Lua error is caught, formatted (with a traceback if
  available), and:
  - logged to the client console and to a per-addon log (`hafen.log` sink),
  - surfaced in the AddOns panel as the addon's error state,
  - optionally, after repeated errors, the addon is **auto-disabled** for the session.
- One addon's error never aborts the tick or other addons.

## Watchdog (runaway protection) ([Q-009](../DECISIONS.md))

Because Lua runs on the UI/render thread, an infinite loop would freeze the client. Two layers:

- **Hard stop:** a LuaJ instruction-count hook aborts a single callback after N instructions (or
  a wall-clock bound), raising a Lua error that the isolation layer catches.
- **Soft budget:** a per-addon per-tick time budget; an addon that repeatedly exceeds it is
  auto-disabled with a panel warning.

Exact limits and configurability: TBD.

## Java↔Lua coercion

The bridge converts between Java and Lua values so addons work with plain Lua tables, not Java
handles:

- **Out (Java→Lua):** primitives → Lua numbers/strings/booleans; game snapshots → Lua tables
  (e.g. a gob → `{id=, name=, x=, y=, hp=, ...}`); resource-backed things exposed via small
  **proxy userdata** with methods (e.g. an item handle, a window handle, a widget-model handle).
- **In (Lua→Java):** numbers → `int`/`double`; explicit `Coord`/`Coord2d` constructors provided
  by the API where the server protocol needs them (so args encode correctly, see
  [06-lua-api.md](06-lua-api.md)).
- Handles returned to Lua (windows, item models, widget models) are **owned by the bridge** and
  registered against the addon so they can be torn down ([P2](01-architecture.md)).

## Lifecycle entry points (where the engine hooks the client)

- **Global init (no session):** after `setupres()` in [`Client.main2`](src/haven/Client.java:363)
  — discover addons and read the enabled set from disk. GL/UI/session not up yet.
- **Per-session init:** [`RemoteUI.init(UI ui)`](src/haven/RemoteUI.java:147) — the earliest
  point `ui.sess` is bound; the engine attaches its addon-root widget and starts the tick pump.
  (Alternatively bind to the in-game HUD in the [`GameUI`](src/haven/GameUI.java:257) ctor.)
- **Console:** register a fixed set of commands **once** via
  [`Console.setscmd`](src/haven/Console.java:54) — `:addon`, `:reload`, `:lua`. **Note:**
  `Console` has no unregister; these are engine-lifetime dispatchers that route to current
  state, so Reload never touches `Console`.

The exact core edits (if any beyond public seams) are enumerated in
[11-core-hooks.md](11-core-hooks.md).

## Open items

- Shared vs per-addon `Globals` (memory vs isolation) — TBD.
- Sandbox whitelist finalization — [Q-007](../DECISIONS.md).
- Watchdog limits — [Q-009](../DECISIONS.md).
- JSON facility for saved data (may be a pure-Lua module shared with addons) — [Q-006](../DECISIONS.md).
