# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**Active:** `018-client-options` — 018.1 + 018.2 + 018.3 DONE (verified; `docs/addons/api/client.md` is the
`hafen.client` reference and both catalogs list it). Next 018.4 — extend the `hello` harness.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog
  + soft per-tick CPU budget with auto-disable (D-018); `ant get-luaj` fetches deps.
- **Loading/lifecycle**: `manifest.json` discovery from `addons/`, per-addon env; `:reload` rebuilds the addon
  layer only (D-005); enabled set persisted (D-006); teardown complete-by-construction (bridge-owned).
- **Console/panel**: `:lua` REPL, `:addons`, `:reload`; AddOns panel (toggle/reload/status/warnings).
- **Store**: `hafen.store` JSON saved-vars, per-char + account scopes (D-023); atomic, throttled.
- Event bus `hafen.events.on` (OnLoad/OnEnterWorld/OnUpdate/OnDisable, GobAdded/Removed (payload = a Gob),
  VitalsChanged, Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked, ChatMessage…); error-isolated,
  UI-thread-marshalled. Timers `hafen.timer.after/every`.

## Read API (`hafen.*`)
- **Gobs (OOP, D-044/045)**: `hafen.gob(id)` → an interned Gob object; `gob:pos/name/health/facing/moving/
  speed/speech/icon/overlays/isplayer/distance/exists/id/info`. The flat `gob.*(ref)` table + the `"player"`/
  `"partyN"` tokens are GONE. `world.*` hands out Gobs; every other namespace stays flat (transitional).
- **Map**: `map.*` — tile/height/grid/gridPos + coord conversions; grid ids = exact decimal strings.
- **Player/char**: `hafen.player()` → Player object (`:gob()/:name()/:vitals()/:worldToScreen()`, D-046 — no
  forwarded methods); `char.*` (attrs/food/skills/lp/weight), `time.*`, `party.*`, `buffs.*`, `study.*`,
  `actionbar.slot`; `items.*` (inventory/equipment/hand/find) — snapshots.
- **Gap subsystems** (A1–A11): `markers`, `radar`, `kin`, `speed`, `craft`, `quests`, `wounds`, `fight` — all
  via widget-tree adapters (Locator + uimsg tap + per-tick poll) marshalled to the UI thread.

## UI (`hafen.ui`), hooks, input
- Custom windows/widgets (`ui.window`/`widget`) + `LuaGOut` draw wrapper (text/rect/image/resource…).
- HUD overlays (`ui.overlay`) + world gob overlays (`ui.gobOverlay`).
- Hotkeys: `options:keybindings():register(name, fn)` (starts UNBOUND, D-047) + keybind-panel integration; the
  `hafen.key` namespace is GONE (018.2); slash cmds `hafen.slash.register`.
- **Client options** `hafen.client:options()` → `interface/video/audio/camera/keybindings`; arity is the verb
  (`opt:name()` reads, `opt:name(v)` writes + returns the handle, so writes chain). Same stores OptWnd writes
  (`Utils.pref*`, `GSettings` via `ui.setgprefs`, `Audio`/`ActAudio`, MapView statics); `keybindings` =
  `register(name, fn)` (starts UNBOUND, D-047) + `get/set/list/unregister`.
- Hooks: `hook.input` (L1 pre-widget), `hook.action` (L2 outbound `UI.wdgmsg` — preventDefault/resend/send),
  `hook.message` (L3 inbound uimsg — swallow/rewrite).
- Widget interception/replacement: `ui.onWidgetCreate`, `ui.adopt` (model handle), `ui.replace` (bags
  replaces the native inventory, restores on disable).
- Introspection: `ui.root()/node(id)` WidgetNode walk (D-041), `ui.at/mouse` hit-testing + `node:rootpos`
  (D-042); `widgetstack` addon = the `/framestack` analog.

## Actions (gated write tier)
- `hafen.act.*`: moveTo/clickGob(gob)/useItemOn/place/select/raw, menu/flower, item verbs + per-subsystem verbs
  (actionbar.use, speed.set, kin.*, craft.make…). Per-addon permission + enable-time consent dialog, NO global
  switch (D-027/D-028); write addons default-disabled.

## Virtual entities, rendering, data, fonts
- **Ghosts** `hafen.ghost`: client-only virtual gobs (D-029..D-033) — clickable, oriented, grid-anchored layouts, transform gizmo.
- **Render** `hafen.render`: image (screen), sprite (fixed + billboard world quads), glTF 2.0 static
  models (textures, materials, lighting) (D-034/D-035).
- **Data/net**: `hafen.json` (D-036); `hafen.http` async get/post gated by a manifest `network` allowlist (D-037).
- **Fonts** `hafen.font` (D-043): per-addon handles; full scope enum (default, window.title, button, textentry,
  label, heading, menu, tooltip, chat, world.nick/speech); own-widget + per-instance `node:setFont`.

## Example addons (regression harness)
- `hello` (grows with every feature — one login re-checks everything), `hogtest` (CPU watchdog), `bags`
  (inventory replacement), `planner` (ghost layouts + gizmo), `widgetstack` (framestack).
