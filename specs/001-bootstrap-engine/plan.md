# 001-bootstrap-engine — Plan

> History: this work appears in git history and `learnings/` tagged **Phase 0, Phase 1a, Phase 1b**.

## Approach
- **Engine spike first** (prove the loop end-to-end before building anything): a static
  `AddonManager` facade modelled on `io.brodgar.voice.Voice`, capturing the live `MapView` via
  two one-line attach/detach hooks; one shared LuaJ `Globals`; the `:lua` console command; one
  read (`hafen.gob.pos(ref)`, the D-012 reference-based accessor with `nil`/`"player"`/`"me"`/id).
- **LuaJ via the build, not the repo** (D-003/D-014): `ant get-luaj` fetches
  `lib/brodgar/luaj-jse-3.0.1.jar` from Maven Central (gitignored); one manifest `Class-Path`
  token; `get-luaj` is a dependency of the normal build.
- **REPL output**: results serialize to compact single-line JSON (own recursive writer — later
  reused by the store and `hafen.json`); cycles → `"<cycle>"`. The console strips quotes
  (`Utils.splitwords`), so `Console` gained `rawcmd()` and `:lua` evaluates the raw line.
- **Disk loading** (D-001/D-015/D-016): own dependency-free `Json` reader; `Manifest` parse +
  validate (`id` must equal folder, non-empty `files`); per-addon Lua env; malformed manifests
  skip only that addon. Load happens per-session at `RemoteUI.init` (one-line hook).
  Addon dir = jar-sibling `addons/` (build copies repo `addons/` → `bin/addons/`).
- **Runtime with zero further core edits**: an invisible zero-size `AddonRoot` widget on
  `ui.root` is the tick pump (hafen ticks invisible widgets; `ui.root` already exists at
  `RemoteUI.init`). Each tick: drain gob queue → fire `OnEnterWorld` once (gated on the HUD
  being up) → `OnUpdate(dt)` → due timers. Gob spawn/despawn comes from the public
  `OCache.callback` (network/loader thread) and is queued to the UI thread.
- **Ownership from day one** (P2): every subscription/timer is registered on its owning `Addon`
  (`subs`/`timers`); the `:lua` REPL is itself a resource owner via a synthetic
  `Manifest.internal("(console)")`. All entries into Lua are error-isolated per handler.

## Files created / modified
- `src/io/brodgar/addon/AddonManager.java` — facade, discovery/loading, tick pump, event bus,
  timers, `:lua`/`:addons`, error isolation (new)
- `src/io/brodgar/addon/Addon.java`, `Manifest.java`, `Json.java`, `AddonRoot.java` — new
- `src/haven/MapView.java` — two `// addon:` attach/detach one-liners
- `src/haven/RemoteUI.java` — one `// addon:` init hook (per-session load)
- `src/haven/Console.java` — `rawcmd()` (raw command line for `:lua`)
- `build.xml` — `get-luaj` target + manifest token + `addons/` copy
- `addons/hello/` — the standing example/regression addon (manifest + main.lua)

## Risks & gotchas hit (detail: learnings/, tagged Phase 0/1a/1b)
- `OCache.callback` holds callbacks in a **WeakList** → keep a strong static ref or events die
  ([learnings/engine-lifecycle.md](../learnings/engine-lifecycle.md)).
- `MapView.attach` runs on a **loader thread** → only set a volatile flag; fire `OnEnterWorld`
  from the next UI-thread tick ([learnings/threading.md](../learnings/threading.md)).
- Addons load pre-HUD, world state is nil at `OnLoad` — reads belong in `OnEnterWorld`+.
- REPL subscriptions persist across relogs (its env is long-lived, never torn down).

## Discarded alternatives
- Committing the LuaJ jar — build-fetched instead (D-003).
- A third-party JSON dependency — 60-line own reader (D-016).
- A `haven` tick hook — the invisible-widget pump needs zero core edits.
