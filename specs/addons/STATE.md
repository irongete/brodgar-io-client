# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**Active:** `019-profiling` — `hafen.client:profiling()` (frame/CPU/GPU, counters, per-addon cost, Lua scopes,
per-widget cost, named render passes) + the Options **Client** panel. **019.1–019.4 DONE**; 019.5..019.8 pending.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog
  + soft per-tick CPU budget with auto-disable (D-018); `ant get-luaj` fetches deps.
- **Loading/lifecycle**: `manifest.json` discovery from `addons/`, per-addon env; `:reload` rebuilds the addon
  layer only (D-005); enabled set persisted (D-006); teardown complete-by-construction (bridge-owned). Console
  `:lua` REPL / `:addons` / `:reload`; AddOns panel (toggle/reload/status/warnings).
- **Store**: `hafen.store` JSON saved-vars, per-char + account scopes (D-023); atomic, throttled.
- Event bus `hafen.events.on` (OnLoad/OnEnterWorld/OnUpdate/OnDisable, GobAdded/Removed (payload = a Gob),
  VitalsChanged, Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked, ChatMessage…) — error-isolated,
  UI-thread-marshalled; timers `hafen.timer.after/every`.

## Read API (`hafen.*`)
- **Gobs (OOP, D-044/045)**: `hafen.gob(id)` → an interned Gob object (`gob:pos/name/health/facing/moving/speed/
  speech/icon/overlays/isplayer/distance/exists/id/info`); the flat `gob.*(ref)` table and the `"player"`/`"partyN"`
  tokens are GONE. `world.*` hands out Gobs; every other namespace stays flat.
- **Map** `map.*`: tile/height/grid/gridPos + coord conversions; grid ids = exact decimal strings.
- **Player/char**: `hafen.player()` → Player object (`:gob()/:name()/:vitals()/:worldToScreen()`, D-046 — no
  forwarded methods); `char.*` (attrs/food/skills/lp/weight), `time/party/buffs/study.*`, `actionbar.slot`, `items.*`.
- **Gap subsystems** (A1–A11) `markers/radar/kin/speed/craft/quests/wounds/fight` — widget-tree adapters, marshalled.

## UI (`hafen.ui`), hooks, input
- Windows/widgets (`ui.window`/`widget`) + `LuaGOut` draw wrapper; overlays: `ui.overlay` / `ui.gobOverlay`.
- **Client options** `hafen.client:options()` → `interface/video/audio/camera/client/keybindings`, one per OptWnd
  panel, over the stores OptWnd writes; arity is the verb (`opt:name()` reads, `opt:name(v)` writes + chains).
  `keybindings` = `register(name, fn)` (UNBOUND, D-047) + `get/set/list/unregister`, keybind-panel integrated;
  `hafen.key` GONE (018.2); slash `hafen.slash.register`.
- **Profiling** (019.1–019.3): Options ▸ **Client** ▸ "Enable profiling" = `options():client():profiling()` =
  `:profile on` — ONE switch (`prof.Prof.arm`, D-049), next-frame; client engines live in sibling packages
  (`io.brodgar.prof`/`ui`), handles stay in `addon` (D-048). `hafen.client:profiling()` → `:frame()` (fps/ms/
  idle/latency, `phases` + `render` groups, `ui`/`addons` roll-ups, late `gpuMs`+`gpuFrameno`), `:history(n)`
  (600-frame ring, oldest→newest), `:reset()` — snapshot tables, absent key = not measured, off ⇒ `{}` (D-050).
  Plus the **pull-only counters** `:memory()/:net()/:loader()/:render()` (D-051) — getters beside the client's own
  `stats()` strings: no new counting, they answer with profiling **off** and match `:stats on` field by field.
  019.4: `:addons()` (per-addon ms/avg/peak/share + `calls`/`cost` by category, `total` == `:frame().addons`) and
  `:scope(name)`/`:measure(name,fn)` — the D-018 watchdog's OWN measurement split by a mandatory `callLua`
  category, never re-timed, so `hogtest` still trips identically (D-052); scopes are per-addon, die with `:reload`.
- Hooks: `hook.input` (L1 pre-widget), `hook.action` (L2 outbound `UI.wdgmsg`), `hook.message` (L3 inbound).
- Widget interception/replacement: `ui.onWidgetCreate`, `ui.adopt` (model handle), `ui.replace` (bags replaces
  the native inventory, restores on disable); `widgetstack` = the `/framestack` analog.
- Introspection: `ui.root()/node(id)` WidgetNode walk (D-041), `ui.at/mouse` hit-testing + `node:rootpos` (D-042).

## Actions (gated write tier)
- `hafen.act.*`: moveTo/clickGob(gob)/useItemOn/place/select/raw, menu/flower, item verbs + per-subsystem
  verbs (actionbar.use, speed.set, kin.*, craft.make…). Per-addon permission + enable-time consent dialog, NO
  global switch (D-027/D-028); write addons default-disabled.

## Virtual entities, rendering, data, fonts
- **Ghosts** `hafen.ghost`: client-only virtual gobs (D-029..D-033) — clickable, oriented, grid-anchored
  layouts, transform gizmo. **Render** `hafen.render`: image (screen), sprite (fixed + billboard world quads),
  glTF 2.0 static models — textures, materials, lighting (D-034/D-035).
- **Data/net**: `hafen.json` (D-036); `hafen.http` async get/post, manifest `network` allowlist (D-037).
- **Fonts** `hafen.font` (D-043): per-addon handles; all 11 scopes (chrome + world) + own-widget/per-instance `node:setFont`.

## Example addons (regression harness)
- `hello` (grows with every feature — one login re-checks everything), `hogtest` (CPU watchdog), `bags` (inventory),
  `planner` (ghosts), `widgetstack`, `netdemo` (http/json), `walker` (writes), `optionstest`; 019.8 adds `profiler`.
