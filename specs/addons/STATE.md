# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**Active:** `024-audio-oop` — audio to OOP, and it is `hafen.sound` **alone**: `hafen.sound(name)` = an interned Sound (`:res/:play([volume])/:stop/:playing/:info`; no `:exists()` — a name-keyed handle has no lifetime to go stale), `hafen.sound()` = the addon's own still-playing set (auto-silenced on disable/reload); volume is `:play`'s FIRST arg. Flat `hafen.sound.play` hard-cut. **ZERO core edits in the whole feature.** **024.1 DONE** — `LuaSound` (userdata + per-addon weak intern cache keyed by resource NAME, the `LuaPagina` shape; no catalogue behind it — sound resources are not enumerable): `hafen.sound(name)` → `:res/:play([volume])/:info`, ungated (client-local); the play path is the old `AddonManager.playSound` folded in (`Resource.local()` + `loader.defer`, a bogus name is silent) plus an `Audio.VolAdjust(cs, vol)` wrap when volume ≠ 1; volume checking lives in one shared `LuaSound.volume(v, method)` that 024.3's `track:play` reuses. **024.2 DONE** — `:stop()` → self, `:playing()`, `hafen.sound()` = the addon's still-playing Sounds, and the teardown sweep. ZERO core edits: `ActAudio.RootChannel.remove(cs)` stops, `mixer().playing(cs)` tests, and since `Audio.Mixer.get` drains a finished clip LAZILY (the only end-of-clip signal) every read IS the prune. Playback state lives in the per-addon `LuaSound.Cache` keyed by resource NAME — not on the handle, which is weakly interned. `:play()` returns before the loader resolves, so `:stop()` carries a per-name generation stamp the deferred task re-checks, and that task registers the clip + calls `UI.sfx` under ONE monitor (lock order `Live` → mixer) or a stop in between "stops" a clip the mixer has not got yet. Swept by `AddonRegistry.teardown` (disable/`:reload`/session init) plus an explicit `reload()` sweep of the `:lua` REPL, which is not an addon and never gets teardown. Harness: `:hello sound` toggles a long clip. **024.3 = the CUT (D-058)** — the Track section was built to spec and then removed whole (`LuaMusic` deleted, `Addon.tracks` gone, `haven/Music.java` reverted to pristine): `haven.Music` is MIDI driven only by `RootWidget`'s `"bgm"` uimsg, which this server never sends (**0 `midi` layers in 132,777 cached resources** vs 36 `audio`, all sfx). The audible "music" is `ActAudio.Ambience` on the `amb` channel (a render-tree node with a lifetime, not a clip handle — its own feature if ever wanted). `hafen.music` is deliberately absent. Remaining: 024.4 (sound-only docs, `hello`'s full exercise + the `hafen.music == nil` contract check, D-059/D-060). Prior: `023-menugrid-oop` **DONE** (023.1–023.3). The action menu (`MenuGrid` paginae) as OOP: `hafen.menugrid()` = the catalogue (interned, grid sort order, `:find/:roots/:list`), `hafen.menugrid(key)` = one Pagina — key by SHAPE (`/` ⇒ res name = identity, else display name = a search convenience, not unique; a number errors, a miss ⇒ `nil`); `:res/:name/:path/:parent/:children/:tooltip/:hotkey/:isnew/:exists/:info`. The catalogue is `paginae` **plus the parent closure** (categories live only in the private `pmap`); `Loading`-guarded ⇒ short right after login, fills in sub-second. The verb `:use()` → self, **no arguments**, UNGATED on purpose — drives `PagButton.use(new Interaction(1,0))` (the pure message half: `"act"`-by-path vs `"use"`-by-id, so id-only paginae fire), NOT `MenuGrid.use` (the click handler); a category errors and points at `:children()`; firing demo `:walker menugrid <name>` (`hello` stays read-only: summary + contract check each login, tree under `:hello actions`). Docs: `docs/addons/api/menugrid.md`. Prior: `022-actionbar-set` **DONE** (gated `slot:set(resourceName)`, async write), `021-actionbar-oop` **DONE** (LuaSlot + hard cut, `ActionbarChanged` = a Slot), `020-kin-oop` **DONE** (hard cut, Kin ↔ Gob, `KinChanged` = `Kin[]`), `019-profiling` **DONE**. **Next:** ROADMAP's OOP migration continues (party/fight/… still flat).

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft
  per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`;
  `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` /
  `:reload`; AddOns panel (toggle/status).
- **Event bus** `hafen.events.on` (OnLoad/OnEnterWorld/OnUpdate/OnDisable, GobAdded/Removed (a Gob), VitalsChanged,
  Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked, ChatMessage…) — error-isolated, UI-thread-marshalled;
  timers `hafen.timer.after/every`. **Store**: `hafen.store` JSON saved-vars, per-char + account (D-023).

## Read API (`hafen.*`), UI (`hafen.ui`), hooks, input
- **Gobs (OOP, D-044/045)**: `hafen.gob(id)` → an interned Gob
  (`:pos/name/health/facing/moving/speed/speech/icon/overlays/isplayer/distance/exists/id/info`); the flat
  `gob.*(ref)` table and the `"player"`/`"partyN"` tokens are GONE. `world.*` hands out Gobs. **Kin (OOP, D-056,
  020.1)**: `hafen.kin` is CALLABLE-ONLY — `hafen.kin()` = the roster of interned Kin (+`:find/:list/:add`),
  `hafen.kin(idOrName)` = one Kin (`:id/:name/:group/:color/:online/:exists/:info`; gated
  `:rename/:setGroup(0..254)/:endkin/:forget` chain on self); flat `kin.*` GONE; `KinChanged` = a per-addon `Kin[]`
  (`fireKin`, `hasSub`-gated, 020.3). **Kin ↔ Gob (020.2)**: `gob:kin()` = the `ui/obj/buddy` attrib (+ name fallback
  on a pin bump), `kin:gob()` = an `OCache` sweep **preferring the body** (hearth fires are marked too —
  `world.gobs`+`g:kin()` = the many-case). **Actionbar (OOP, D-057, 021.1)**: CALLABLE-ONLY — `hafen.actionbar(n)` =
  the Slot at the raw 0-based game index (0..143, OOB throws), `hafen.actionbar()` = the **1-based** array of all 144
  (same interned objects, `#`=144); `slot:index/:empty/:res/:name/:cooldown/:info` + gated `:use([mods])` → self; flat
  `.slot/.use` GONE; `ActionbarChanged` = the changed **Slot** (`fireSlot`, `hasSub`-gated, 021.2; `hello` re-checks the contract each login, 021.3). **Write (022)**: gated `slot:set(resourceName)` → self = the drag's own `wdgmsg("setbelt", n, "res", …)`; ASYNC (echoed back ⇒ `:set():use()` uses the OLD content — watch `ActionbarChanged`), unknown name silently dropped, no `"pag"`; demo `:walker setbar`. **Map** `map.*`: tile/height/grid/gridPos + conversions, grid ids = exact decimal strings.
  **Player/char**: `hafen.player()` → Player object (`:gob()/:name()/:vitals()/:worldToScreen()`, D-046); `char.*`
  (attrs/food/skills/lp/weight), `time/party/buffs/study.*`, `items.*`. **Gap subsystems** (A1–A11)
  `markers/radar/kin/speed/craft/quests/wounds/fight` — widget-tree adapters. Windows/widgets (`ui.window`/`widget`) +
  `LuaGOut` draw wrapper; overlays `ui.overlay`/`ui.gobOverlay`.
- **Client options** `hafen.client:options()` → `interface/video/audio/camera/client/keybindings`, one per OptWnd
  panel over the stores OptWnd writes; arity is the verb (`opt:name()` reads, `opt:name(v)` writes + chains).
  `keybindings` = `register(name, fn)` (UNBOUND, D-047) + `get/set/list/unregister`, keybind-panel integrated;
  `hafen.key` GONE (018.2); slash `hafen.slash.register`.
- **Profiling** (019, detail in `019-profiling/`): Options ▸ **Client** ▸ "Enable profiling" =
  `options():client():profiling()` = `:profile on` — ONE switch (`prof.Prof.arm`, D-049), next-frame; engine in
  `io.brodgar.prof`/`ui`, handles in `addon` (D-048). `hafen.client:profiling()` → `:frame()`/`:history(n)` (600-frame
  ring)/`:reset()`; snapshot tables, absent key = not measured, off ⇒ `{}` (D-050). **Pull-only counters**
  `:memory/:net/:loader/:render` (D-051) answer **off**, match `:stats on`; `:addons()`+`:scope`/ `:measure` = the
  D-018 watchdog's OWN measurement split by `callLua` category, never re-timed (D-052). `:widgets()` = ONE `// addon:`
  `long[] prof` on `Widget`, inclusive at the seams, self derived at snapshot time (D-053); `:passes()` = FIXED
  `shadow`/`scene`/`ui2d`, CPU+GPU, SELF time under the frame's `draw` (D-054; `:gl()` = ARMED-ONLY); `:overhead()` =
  five tiers, **0.54% of frame** (D-055).
- Hooks: `hook.input` (L1 pre-widget), `hook.action` (L2 outbound `UI.wdgmsg`), `hook.message` (L3 inbound).
  Interception/replacement: `ui.onWidgetCreate`, `ui.adopt` (model handle), `ui.replace` (bags replaces the native
  inventory, restores on disable), `widgetstack` = `/framestack`. Introspection: `ui.root()/node(id)` WidgetNode
  walk (D-041), `ui.at/mouse` hit-testing + `node:rootpos` (D-042).

## Actions (gated write tier), virtual entities, rendering, data, fonts, example addons
- `hafen.act.*`: moveTo/clickGob(gob)/useItemOn/place/select/raw, menu/flower, item verbs + per-subsystem verbs
  (`slot:use`, speed.set, `kin:rename/:setGroup/:endkin/:forget`, craft.make…; **`pag:use()`** 023, ungated —
  the first section owning its own verb). Per-addon permission + enable-time
  consent, NO global switch (D-027/D-028); write addons default-disabled. **Example addons (regression harness)**:
  `hello` (grows with every feature — one login re-checks everything), `hogtest`, `bags`, `planner`, `widgetstack`,
  `netdemo`, `walker` (writes), `optionstest`, `profiler`.
- **Ghosts** `hafen.ghost`: client-only virtual gobs (D-029..D-033) — clickable, oriented, grid-anchored layouts,
  transform gizmo. **Render** `hafen.render`: image (screen), sprite (fixed + billboard world quads), glTF 2.0
  static models — textures, materials, lighting (D-034/D-035). **Data/net**: `hafen.json` (D-036); `hafen.http`
  async get/post, manifest `network` allowlist (D-037). **Fonts** `hafen.font` (D-043): all 11 scopes + `node:setFont`.
