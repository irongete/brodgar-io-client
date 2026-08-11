# 001-bootstrap-engine — Tasks

- [x] 001.1 — Engine spike: embed LuaJ (build-fetched), `:lua` REPL (compact-JSON results, raw
      command line), `hafen.gob.pos(ref)`; MapView attach hooks. Verified in-game
      (`:lua hafen.gob.pos().x`).
- [x] 001.2 — Addon loading from disk: `manifest.json` (own JSON reader), per-addon Lua envs,
      `hafen.log`, `:addons`; `RemoteUI.init` hook; build copies `addons/` → `bin/addons/`.
- [x] 001.3 — Runtime: `AddonRoot` tick pump, event bus (`OnLoad`/`OnEnterWorld`/`OnUpdate`/
      `OnDisable`, `GobAdded`/`GobRemoved` via `OCache.callback`), timers
      (`hafen.timer.after/every`), per-handler error isolation, owned-resource registry.
- [x] 001.4 — (late infra, behaviour-preserving) Split the 9 264-line `AddonManager`
      god-class into per-system files: hub (seams + shared substrate) + `AddonRegistry` +
      `HttpApi`/`StoreApi`/`HookApi`/`ActApi`/`WorldApi`/`UiApi`/`RenderApi`/`CharApi`;
      static-import pattern, delegating seams, zero `haven` edits; clean-rebuild verified.
