# 001-bootstrap-engine — Spec

## What & why
Bootstrap the WoW-style Lua AddOn system: embed a Lua VM (LuaJ) in the client, prove it can run
on the UI thread and read live game state through a stable `hafen.*` facade, then turn addons
into real things — folders on disk with a manifest, each in its own Lua environment, driven by a
per-frame runtime (tick pump, event bus, timers) instead of run-once scripts. Everything later in
the project builds on this slice. Vision: [design/00-vision-scope.md](../design/00-vision-scope.md);
architecture: [design/01-architecture.md](../design/01-architecture.md).

## Acceptance criteria (all verified in-game)
- [x] `:lua <expr>` evaluates Lua in-game; expression results show as compact single-line JSON;
      string literals survive the console's word-splitting (`Console.rawcmd()`); errors show as
      an in-game notice, never crash the client.
- [x] `:lua hafen.gob.pos().x` prints the player's X — Lua on the UI thread reading live state
      (the Phase-0 definition of done).
- [x] `addons/hello/` (manifest.json + main.lua) is discovered and loaded at session start from
      the client's `addons/` dir (`bin/addons/` at runtime; `-Dhaven.addondir` override);
      `:addons` lists it; a malformed manifest skips only that addon.
- [x] `hafen.log(msg)` reaches stdout always and the in-game chat once the HUD exists.
- [x] `hello` reacts over time: `OnLoad` at load, `OnEnterWorld` once world + HUD are up,
      a throttled `OnUpdate(dt)` heartbeat, `GobAdded`/`GobRemoved` as gobs stream in, and a
      one-shot `hafen.timer.after(2, …)`; `sub:off()` / `timer:cancel()` work; a handler error
      is isolated (logged with `file:line`, other addons and the frame unaffected).

## Out of scope (later features)
- Full read API beyond `gob.pos` → [002-read-api](../002-read-api/spec.md).
- Sandbox/watchdog, reload, enabled set, options panel → [005-sandbox-reload-panel](../005-sandbox-reload-panel/spec.md).
- Saved variables → [004-saved-variables](../004-saved-variables/spec.md).
- Dependency ordering between addons (manifest fields parsed, unused).

## Context files
- `design/01-architecture.md` — layering, threading principles (P1-P5)
- `design/04-engine.md` — engine/tick-pump design
- `design/03-addon-format.md` — manifest + addon folder format
- `design/02-filesystem-and-build.md` — addon dir resolution, build wiring
- `design/09-events-catalog.md` — the event catalog this slice starts
- `src/io/brodgar/addon/AddonManager.java`, `Addon.java`, `Manifest.java`, `Json.java`,
  `AddonRoot.java` — the engine this slice created
- `src/haven/RemoteUI.java`, `src/haven/MapView.java`, `src/haven/Console.java` — the hook sites
- `build.xml` — `get-luaj`, addons copy, manifest Class-Path token
- `docs/addons/api/console.md`, `events.md`, `timer.md`, `gob.md`, `conventions.md` — shipped surface
