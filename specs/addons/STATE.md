# STATE — what works right now

> Maintained by REPLACING (max 60 lines, never accumulate). One line per subsystem.
> Branch `feature/addons`. Detail per feature: its `NNN-` folder; history: git + LEARNINGS.md.

**Active feature:** `017-gob-oop` — migrate the Gob surface from the flat `hafen.gob.*(ref)` accessor
to an OOP class (`hafen.gob(id)` + `gob:health()`), hard cut, every other namespace stays flat.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction
  watchdog + soft per-tick CPU budget with auto-disable (D-018); `ant get-luaj` fetches deps.
- **Loading**: `manifest.json` discovery from `addons/`; per-addon Lua env; topological-free load order.
- **Lifecycle**: `:reload` rebuilds the addon layer only (D-005); enabled set persisted (WoW
  apply-on-reload, D-006); teardown is complete-by-construction (bridge-owned resources).
- **Console/panel**: `:lua` REPL, `:addons`, `:reload`; AddOns panel (toggle/reload/status/warnings).
- **Store**: `hafen.store` JSON saved-vars, per-char + account scopes (D-023); atomic, throttled.

## Events & timers
- Event bus `hafen.events.on` (OnLoad/OnEnterWorld/OnUpdate/OnDisable, GobAdded/Removed,
  VitalsChanged, Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked, ChatMessage…); error-isolated,
  UI-thread-marshalled. Timers `hafen.timer.after/every`.

## Read API (`hafen.*`)
- **World/gobs**: `gob.*(ref)` (canonical accessor), `world.*` (gobs/count/nearest/within).
- **Map**: `map.*` — tile/height/grid/gridPos + coord conversions; grid ids = exact decimal strings.
- **Player/char**: `player.*` (vitals via widget-tree), `char.*` (attrs/food/skills/lp/weight),
  `time.*`, `party.*`, `buffs.*`, `study.*`, `actionbar.slot`.
- **Items**: `items.*` (inventory/equipment/hand/find) — snapshots.
- **Gap subsystems**: `markers`, `radar`, `kin`, `speed`, `craft`, `quests`, `wounds`, `fight` (A1–A11).
- Mechanism: widget-tree adapters (Locator + uimsg tap + per-tick poll) marshalled to UI thread.

## UI (`hafen.ui`), hooks, input
- Custom windows/widgets (`ui.window`/`widget`) + `LuaGOut` draw wrapper (text/rect/image/resource…).
- HUD overlays (`ui.overlay`) + world gob overlays (`ui.gobOverlay`).
- Hotkeys `hafen.key.bind` + client keybind-panel integration; slash commands `hafen.slash.register`.
- Hooks: `hook.input` (L1 pre-widget), `hook.action` (L2 outbound `UI.wdgmsg` — preventDefault/
  resend/send), `hook.message` (L3 inbound uimsg — swallow/rewrite).
- Widget interception/replacement: `ui.onWidgetCreate`, `ui.adopt` (model handle), `ui.replace`
  (bags example replaces native inventory, restores on disable).
- Introspection: `ui.root()/node(id)` WidgetNode tree walk (D-041), `ui.at/mouse` hit-testing +
  `node:rootpos` (D-042); `widgetstack` addon = the `/framestack` analog.

## Actions (gated write tier)
- `hafen.act.*`: moveTo/clickGob/useItemOn/place/select/raw, menu/flower, item verbs + per-subsystem
  verbs (actionbar.use, speed.set, kin.*, craft.make…). Per-addon permission + enable-time consent
  dialog, NO global switch (D-027/D-028); write addons default-disabled.

## Virtual entities, rendering, data, fonts
- **Ghosts** `hafen.ghost`: client-only virtual gobs (D-029..D-033) — clickable, look/orientation,
  grid-anchored layouts, Unity-style transform gizmo (move/rotate/scale); `planner` example addon.
- **Render** `hafen.render`: image (screen), sprite (fixed + billboard world quads), glTF 2.0 static
  models (textures, materials, lighting) (D-034/D-035).
- **Data/net**: `hafen.json` (D-036); `hafen.http` async get/post gated by manifest `network`
  allowlist (D-037).
- **Fonts** `hafen.font` (D-043): per-addon handles; full scope enum (default, window.title, button,
  textentry, label, heading, menu, tooltip, chat, world.nick, world.speech); own-widget fonts;
  per-instance `node:setFont`.

## Example addons (regression harness)
- `hello` (grows with every feature — one login re-checks everything), `hogtest` (CPU watchdog demo),
  `bags` (inventory replacement), `planner` (ghost layouts + gizmo), `widgetstack` (framestack).
