# Architecture

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [04-engine.md](04-engine.md), [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md), [11-core-hooks.md](11-core-hooks.md)

## Layered architecture

```
┌───────────────────────────────────────────────────────────────────┐
│  ADDONS (Lua)      <client>/addons/<name>/                          │
│    manifest.toml + *.lua + res/*.res                                │
├───────────────────────────────────────────────────────────────────┤
│  LUA API — the "SDK", a STABLE FACADE (Lua tables)                  │
│    hafen.world · hafen.player · hafen.items · hafen.char            │
│    hafen.act · hafen.ui · hafen.events · hafen.timer                │
│    hafen.store · hafen.log · hafen.key                              │
├───────────────────────────────────────────────────────────────────┤
│  BRIDGE (Java, io.brodgar.addon.api.*)                              │
│    thin wrappers over haven.* · LuaJ coercion · thread marshalling  │
│    · error isolation · OWNS every resource an addon creates         │
├───────────────────────────────────────────────────────────────────┤
│  ENGINE (io.brodgar.addon.AddonManager)                             │
│    LuaJ globals-per-addon · load/enable · tick pump ·               │
│    event dispatch · watchdog · saved-vars                           │
├───────────────────────────────────────────────────────────────────┤
│  CORE HOOKS (haven.*)                                               │
│    ~0 edits for Phases 0–2 (public seams) + a few one-liners        │
│    for a richer event catalog. See 11-core-hooks.md                 │
└───────────────────────────────────────────────────────────────────┘
```

The **engine and ~95% of the bridge live in `src/io/brodgar/addon/`**. Edits to the `haven`
core are minimized and centralized, in the same spirit as the existing voice integration
([`Voice.java`](src/io/brodgar/voice/Voice.java) + ~14 lines across `MapView`). See the
invasiveness ledger in [11-core-hooks.md](11-core-hooks.md).

## Core principles

### P1 — The Lua API is a stable facade
Addons **never touch `haven.*` directly**. Only the thin Java bridge does. Consequences:
- Upstream refactors (like the `MainFrame`→`iosys` refactor already present in this fork) are
  absorbed in one Java layer instead of breaking every addon.
- The bridge is the single choke point for validation, coercion, threading, and error handling.

### P2 — The bridge owns every resource an addon creates
Every widget, overlay, event subscription, timer, keybinding handler, and `OCache` callback an
addon creates is **created through the bridge and tracked per-addon**. This is what makes
**disable** and **Reload UI** correct and leak-free (see
[05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)). P1 (no direct `haven.*`) is what
guarantees the bridge sees everything.

### P3 — One universal action bus, one universal state root
- **Actions:** all player actions are [`Widget.wdgmsg(String, Object...)`](src/haven/Widget.java:737)
  → [`UI.wdgmsg`](src/haven/UI.java:665) → `RemoteUI` → server. The bridge wraps this.
- **State:** the world model hangs off [`Glob`](src/haven/Glob.java:34) via `ui.sess.glob`
  (gobs, terrain, party, character *attributes*, time). ⚠ **Caveat (audit B1):** `Glob` is *a*
  root, not *the* root — much high-value state lives in **`GameUI` widget trees**, not `Glob`
  (vitals, buffs, the action bar, char sheet/skills/study, chat, map/markers, crafting). Those surfaces are
  pinned to widget-tree shape + resource names and are **higher-maintenance** for the bridge than
  the `Glob`-backed reads. `wdgmsg` is a genuinely universal action bus; `Glob` is **not** a
  universal *state* root. See [coverage-gaps.md](../ROADMAP.md) B1.
- Because the action channel is a single choke point (and `Glob` covers the core reads), the API
  can expose generic primitives plus wrappers and grow much coverage without new core edits —
  though widget-tree-bound reads (above) need per-subsystem work.

### P4 — Events are synthesized
Hafen has **no** formal event bus. We build one in the engine, fed by a small, fixed set of
sources. Crucially, several event sources need **zero core edits** because Hafen already exposes
public callbacks (e.g. [`OCache.callback`](src/haven/OCache.java:75) for gob spawn/despawn,
[`KeyBinding`](src/haven/KeyBinding.java) for hotkeys). Others need a one-liner. See
[09-events-catalog.md](09-events-catalog.md).

### P5 — Everything Lua runs on the UI thread
The engine drives Lua exclusively from the UI-thread tick. This removes concurrency hazards
when reading game state and mutating the widget tree. Anything arriving on other threads (e.g.
network events) is marshalled onto the UI thread before reaching Lua. See the threading model
below.

### P6 — Wrap, don't reimplement
When replacing native UI, keep the real server-bound widget as a hidden "model" and present a
custom "view"; delegate interactions back to the real widget. This gives protocol fidelity for
free. See [08-widget-replacement.md](08-widget-replacement.md).

## Threading model (critical)

Verified thread map of the client:

| Thread | Role | Mutates |
|---|---|---|
| **"Haven UI thread"** ([`UILoop`](src/haven/UILoop.java)) | Render + tick loop | Widget tree, ticks `Glob`/`OCache`/`MCache` (all under `synchronized(ui)`) |
| **"Haven main thread"** ([`RemoteUI.run`](src/haven/RemoteUI.java:90)) | Server message pump | Enqueues widget commands (does not touch the tree directly) |
| **"Connection worker"** ([`Connection`](src/haven/Connection.java)) | UDP NIO reader/writer | Mutates `MCache` directly under the `grids` lock; for `OCache` only **enqueues** deltas — gob apply runs on a Loader thread (`GobInfo.apply`); posts widget rels |
| **"Loader thread" ×N** ([`Loader`](src/haven/Loader.java)) | Executes deferred `UI.CommandQueue` commands (server-driven widget create/add/destroy/uimsg) | Widget tree, each under `synchronized(ui)` |
| **"Defer worker" ×N** | Sprite/mesh building, general compute | — |
| **AWT EDT** | GL context thread; executes queued GL command buffers | GL only |

Key facts the engine relies on:

- The **`UI` instance monitor** (`synchronized(ui)`) serializes **all** widget-tree mutation and
  event dispatch across the UI thread and Loader threads.
- The per-frame tick runs in [`UILoop.Frame.tick()`](src/haven/UILoop.java:433):
  `synchronized(ui) { dispatch input; glob.ctick(); glob.gtick(); ui.tick(); ui.gtick(); … }`.
  `ui.tick()` broadcasts a `TickEvent` to every widget's [`tick(double dt)`](src/haven/Widget.java:748).
- **Engine tick placement (zero core edit):** attach an invisible **addon-root widget** to
  `ui.root`; its `tick(dt)` is invoked by the `UI.tick` broadcast — on the UI thread, under the
  lock, after game state is ticked. (One-line alternative: call the engine directly at
  [`UILoop.java:445`](src/haven/UILoop.java:445).) See [04-engine.md](04-engine.md).
- **State reads must respect cache locks:** iterate [`OCache`](src/haven/OCache.java) inside
  `synchronized(oc)` (copy first), read each `Gob` under `synchronized(gob)`, iterate
  `MCache.grids` under `synchronized(grids)`, and **catch `Loading`** (world data resolves
  asynchronously). The bridge does this and hands Lua clean snapshots.
- **Network-thread events:** the lowest-level event surface (`Transport.Callback`, `OCache`
  deltas) fires on the Connection worker thread. The bridge **must marshal to the UI thread**
  before invoking Lua. Never pass raw network-thread data to Lua.

## Data-flow examples

### Reading state (`hafen.gob.pos("player")`)
```
Lua: hafen.gob.pos("player")
 → bridge GobApi.pos(ref)  → resolve "player" → MapView.plgob
 → gameui.map.player()  (haven)          [UI thread, holds ui lock during tick]
 → gob.rc  (Coord2d, read under sync)
 → coerced to a Lua {x=,y=} table
```

### Performing an action (`hafen.act.moveTo`, gated)
```
Lua: hafen.act.moveTo(x, y)
 → bridge ActApi.moveTo()
 → map.wdgmsg("click", pc, world.floor(posres), 1, mods)   (haven)
 → UI.wdgmsg → RemoteUI.rcvmsg → Session.queuemsg (enqueue; socket write on Connection worker)
```

### Server creating a widget we intercept (bag replacement)
```
server → RMSG_NEWWDG(id, "inv", ...) → UI.NewWidget.run()
 → Widget.gettype3("inv")   ← engine can override this factory entry
 → factory.create(ui, cargs)  → real Inventory (kept as hidden MODEL, bound to id)
 → engine notifies addon → addon builds custom VIEW, hides the model
 → server uimsg/addchild still hit the real widget by id (protocol intact)
```

See [08-widget-replacement.md](08-widget-replacement.md) for the full mechanism.

## Package layout (planned)

```
src/io/brodgar/addon/
  AddonManager.java        engine: discovery, load/enable, tick pump, reload, watchdog
  Addon.java               one loaded addon: manifest + Lua globals + owned-resource registry
  Manifest.java            manifest.toml parsing
  lua/                     LuaJ setup, sandbox, coercion helpers
  api/                     the bridge: WorldApi, PlayerApi, ItemsApi, UiApi, EventsApi, ...
  event/                   the event bus + source adapters
  ui/                      LuaWidget, LuaWindow, GOut wrapper, widget-replacement plumbing
  store/                   saved-vars (JSON) load/save
haven/                     tiny package-private helper(s), only where needed (see 11-core-hooks.md)
```

> `haven`-package helpers are used **only** where package-private access is required (the same
> trick the voice integration uses with `SpeakerIcon`/`VoiceTarget`). Everything else stays in
> `io.brodgar.addon`.
