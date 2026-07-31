# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**Active:** none — `020-kin-oop` **DONE** (020.1–020.3: hard cut, Kin ↔ Gob, `KinChanged` = `Kin[]`); ROADMAP's OOP migration continues. (`019-profiling` **DONE**, 019.1–019.8.)

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog +
  soft per-tick CPU budget with auto-disable (D-018); `ant get-luaj` fetches deps. **Loading/lifecycle**:
  `manifest.json` discovery from `addons/`, per-addon env; `:reload` rebuilds the addon layer only (D-005);
  enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status).
- **Event bus** `hafen.events.on` (OnLoad/OnEnterWorld/OnUpdate/OnDisable, GobAdded/Removed (a Gob), VitalsChanged,
  Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked, ChatMessage…) — error-isolated, UI-thread-marshalled;
  timers `hafen.timer.after/every`. **Store**: `hafen.store` JSON saved-vars, per-char + account (D-023).

## Read API (`hafen.*`), UI (`hafen.ui`), hooks, input
- **Gobs (OOP, D-044/045)**: `hafen.gob(id)` → an interned Gob object (`gob:pos/name/health/facing/moving/speed/
  speech/icon/overlays/isplayer/distance/exists/id/info`); the flat `gob.*(ref)` table and the `"player"`/`"partyN"`
  tokens are GONE. `world.*` hands out Gobs. **Kin (OOP, D-056, 020.1)**: `hafen.kin` is CALLABLE-ONLY —
  `hafen.kin()` = the roster of interned Kin (+`:find/:list/:add`), `hafen.kin(idOrName)` = one Kin (`:id/:name/
  :group/:color/:online/:exists/:info`; gated `:rename/:setGroup(0..254)/:endkin/:forget` chain on self); the flat
  `kin.*` table is GONE; `KinChanged` = a per-addon `Kin[]` (`fireKin`, `hasSub`-gated, 020.3). **Kin ↔ Gob
  (020.2)**: `gob:kin()` = the `ui/obj/buddy` attrib (+ name fallback on a pin bump), `kin:gob()` = an `OCache`
  sweep **preferring the body** (hearth fires are marked too — `world.gobs`+`g:kin()` = the many-case). Flat
  elsewhere. **Map** `map.*`: tile/height/grid/gridPos + conversions,
  grid ids = exact decimal strings. **Player/char**: `hafen.player()` → Player object (`:gob()/:name()/:vitals()/
  :worldToScreen()`, D-046); `char.*` (attrs/food/skills/lp/weight), `time/party/buffs/study.*`, `actionbar.slot`,
  `items.*`. **Gap subsystems** (A1–A11) `markers/radar/kin/speed/craft/quests/wounds/fight` — widget-tree
  adapters. Windows/widgets (`ui.window`/`widget`) + `LuaGOut` draw wrapper; overlays `ui.overlay`/`ui.gobOverlay`.
- **Client options** `hafen.client:options()` → `interface/video/audio/camera/client/keybindings`, one per OptWnd
  panel, over the stores OptWnd writes; arity is the verb (`opt:name()` reads, `opt:name(v)` writes + chains).
  `keybindings` = `register(name, fn)` (UNBOUND, D-047) + `get/set/list/unregister`, keybind-panel integrated;
  `hafen.key` GONE (018.2); slash `hafen.slash.register`.
- **Profiling** (019, detail in `019-profiling/`): Options ▸ **Client** ▸ "Enable profiling" =
  `options():client():profiling()` = `:profile on` — ONE switch (`prof.Prof.arm`, D-049), next-frame; engine in
  `io.brodgar.prof`/`ui`, handles in `addon` (D-048). `hafen.client:profiling()` → `:frame()`/`:history(n)`
  (600-frame ring)/`:reset()`; snapshot tables, absent key = not measured, off ⇒ `{}` (D-050). **Pull-only
  counters** `:memory/:net/:loader/:render` (D-051) answer **off** and match `:stats on`. `:addons()` +
  `:scope`/`:measure` = the D-018 watchdog's OWN measurement split by `callLua` category, never re-timed (D-052).
  `:widgets()` = ONE `// addon:` `long[] prof` on `Widget`, inclusive at the traversal seams, self derived at
  snapshot time (D-053). `:passes()` = a FIXED `shadow`/`scene`/`ui2d`, CPU+GPU, nested UNDER the frame's `draw`,
  SELF time so they stay disjoint; `:gl()` = the only ARMED-ONLY counters (D-054). `:overhead()` = five tiers,
  fold timed + probes modelled + 1-in-64 **control frames** (D-055) — **0.54% of frame**. **`profiler` addon**
  (019.8): six tabs, dormant, PAUSE freezes every snapshot and scrubs the graph; it exposed LuaJ's broken
  `string.format` widths (`learnings/luaj-bridge`) and the client's GC hitch (`learnings/profiling`).
- Hooks: `hook.input` (L1 pre-widget), `hook.action` (L2 outbound `UI.wdgmsg`), `hook.message` (L3 inbound).
  Interception/replacement: `ui.onWidgetCreate`, `ui.adopt` (model handle), `ui.replace` (bags replaces the native
  inventory, restores on disable), `widgetstack` = `/framestack`. Introspection: `ui.root()/node(id)` WidgetNode
  walk (D-041), `ui.at/mouse` hit-testing + `node:rootpos` (D-042).

## Actions (gated write tier), virtual entities, rendering, data, fonts, example addons
- `hafen.act.*`: moveTo/clickGob(gob)/useItemOn/place/select/raw, menu/flower, item verbs + per-subsystem verbs
  (actionbar.use, speed.set, `kin:rename/:setGroup/:endkin/:forget`, craft.make…). Per-addon permission +
  enable-time consent dialog, NO global switch (D-027/D-028); write addons default-disabled. **Example addons
  (regression harness)**: `hello` (grows with every feature — one login re-checks everything), `hogtest`, `bags`,
  `planner`, `widgetstack`, `netdemo`, `walker` (writes), `optionstest`, `profiler`.
- **Ghosts** `hafen.ghost`: client-only virtual gobs (D-029..D-033) — clickable, oriented, grid-anchored layouts,
  transform gizmo. **Render** `hafen.render`: image (screen), sprite (fixed + billboard world quads), glTF 2.0
  static models — textures, materials, lighting (D-034/D-035). **Data/net**: `hafen.json` (D-036); `hafen.http`
  async get/post, manifest `network` allowlist (D-037). **Fonts** `hafen.font` (D-043): all 11 scopes + `node:setFont`.
