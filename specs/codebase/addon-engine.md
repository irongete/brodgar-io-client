# Subsystem: the addon engine (`src/io/brodgar/addon/`)

> File layout of the AddOn layer, the `haven` seams it owns, and the integration pattern to
> mirror. Area `addons` owns this file. Max 60 lines.

## File layout (after the god-class split)

| File | Owns |
|---|---|
| `AddonManager` (hub, ~1.6k lines) | lifecycle (attach/init/tick), the `haven` seams (`onUimsg`/`onWdgmsg`/`onMessage`/`onGlobKey`/`onWidgetCreated`/`onWidgetPlaced`/`onGhostClick` — core edits call these by name; they delegate), the shared gob-read/engine substrate (`getgob`/`gobMatches`/`gobSnapshot`/`gui`/…), event bus + `callLua`, timers, the `:lua` REPL, `installHafen` |
| `AddonRegistry` | discovery/loadAll, enabled set (+ D-027 defaults), `reload`, per-addon teardown, the AddOns-panel data API |
| `WorldApi` | `hafen.world/map/markers/radar/time/sound/music` (per-gob reads are `LuaGob`) |
| `CharApi` | `hafen.player/char/study/party/kin/buffs/actionbar/quests/wounds/fight` + the `TreeAdapter`s + `itemSnapshot` (the one Item producer; `hafen.items` was cut in 029.3) |
| `UiApi` | `hafen.ui` — windows/overlays, observe/replace, the entry points + hit-testing, the container-subscription poll (`adopt` deleted in 029.2) |
| `HookApi` | `hafen.hook` (L1/L2/L3 + grab), `hafen.slash`; owns the hotkey registry behind `hafen.client:options():keybindings()` |
| `ActApi` | `hafen.act` (gated verbs) + `hafen.craft`/`hafen.speed` writes |
| `RenderApi` | `hafen.ghost` + `hafen.render` (world entities) |
| `StoreApi` / `HttpApi` / `FontApi` | `hafen.store` / `hafen.http` / `hafen.font` |
| **`LuaGob`** | the `hafen.gob(id)` Gob class (D-044): id-only userdata + per-addon metatable/intern cache (D-045) |
| `OptionsHandle` + `*Options` | `hafen.client:options()` — one subsystem per OptWnd panel (`interface`/`video`/`audio`/`camera`/`client`/`keybindings`), read/write by arity via `OptionsMethod` |
| Support classes | `Addon`, `Manifest`, `Json`, `Sandbox`, `AddonRoot`, `LuaMarshal`, `Lua*` (widget/hooks/entities/images/meshes/nodes/fonts/GOut/WorldEntity), `GhostGob`, `SpriteQuad`, `FollowMoving`, `Gltf`, `ui/AddonPanel`, `ui/ActionsConsentWnd` |

**Sibling packages under `io.brodgar` (D-048).** This package is the addon *system* + its Lua bridge; a
**client** capability that merely has an addon consumer lives on its own and leaves only its handle here:
`io.brodgar.voice` (`Voice`), `io.brodgar.prof` (`Prof` — the profiling master switch, whose callers are
`UILoop`/`Widget`/`MapView`/GL; handle = `ClientOptions`), `io.brodgar.ui` (`ClientPanel`, a *client* Options
panel — the addon-manager panels stay in `addon/ui/`). Keep `addon` itself **flat**: 16 of its top-level
classes are package-private, and subpackages would force them public.

Pattern: cluster files `import static AddonManager.*`; the seams stay in the hub as one-line
delegates so `haven` core edits never move. `haven.Fonts` + `haven.AddonWidgets` are the two
`haven`-package helper files the addon layer owns. Addon-relative file reads go through
[`Addon.dir.resolve(name)`](src/io/brodgar/addon/Addon.java:173) + `Files.readAllBytes` (reject
`..`/absolute — D-017).

## Extension points (where new features hook in)

| Seam | Use |
|---|---|
| `@RName` registry ([`Widget.types`](src/haven/Widget.java:51)) | server type name → widget factory (replacement seam A — unused so far; B = adopt-after-create) |
| [`RemoteUI.init`](src/haven/RemoteUI.java:147) | per-session attach — the engine's entry point |
| The invisible `AddonRoot` on `ui.root` | per-frame tick + `globtype` hotkeys, zero core edit (invisible widgets still tick) |
| [`UI.wdgmsg`](src/haven/UI.java:665)/[`UI.uimsg`](src/haven/UI.java:702) taps | outbound L2 / inbound L3 + tree-read events (already split/tapped, `// addon:`) |
| `UI.NewWidget`/`AddWidget` (`onWidgetCreated`/`onWidgetPlaced`) | widget-creation interception + descriptors |
| [`GameUI.addchild`](src/haven/GameUI.java:910) | HUD placement switch (place-string routing) |
| `MapView` client-gob seam (`addClientGob`) + `Click.hit` intercept + `placeSnap` | the 3D-scene seams for client-only entities |
| `Widget.listen`/`deafen`, `KeyBinding.get`, `Console.setscmd`, `OptWnd.Panel`+`PButton`, `OCache.callback`, `UI.drawafter`, `DropTarget` | zero-edit seams the engine already provides |
| `haven.Fonts` provider (+ `Fonts.enter` dynamic scope, `Fonts.frame` per-instance) | font routing for any new render site |
| Resource-code adoption (`get-code` + `@FromResource`) | edit published `.res` code via a version-pinned local copy |

## Integration template (the voice feature — the pattern to mirror)

| Piece | Where |
|---|---|
| Static facade | [`io.brodgar.voice.Voice`](src/io/brodgar/voice/Voice.java) (all-static, `attach`/`detach`/`tick`) |
| Lifecycle hooks (~14 lines) | `MapView` ctor (~:502), `dispose` (~:514), `tick` (~:1690), keydown/keyup (~:2128) |
| UI piggyback: gob overlay | [`SpeakerIcon`](src/haven/SpeakerIcon.java) (`GAttrib` + `RenderTree.Node` + `PView.Render2D`) |
| UI piggyback: flower petal | `FlowerMenu.addVoicePetal` (~:224), `choose` intercept (~:278) |
| UI piggyback: options panel | `OptWnd.VoiceChatPanel` (~:458), main button (~:843) |
| Keybinding | `Voice.kb_ptt = KeyBinding.get("brodgar/ptt", …)` (~:65) |
