# Subsystem: the addon engine (`src/io/brodgar/addon/`)

> File layout of the AddOn layer, the `haven` seams it owns, and the integration pattern to
> mirror. Area `addons` owns this file. Max 60 lines.

## File layout (after the god-class split)

| File | Owns |
|---|---|
| `AddonManager` (hub, ~1.6k lines) | lifecycle (attach/init/tick), the `haven` seams (`onUimsg`/`onWdgmsg`/`onMessage`/`onGlobKey`/`onWidgetPlaced`/`onGhostClick` — core edits call these by name; they delegate; `onWidgetCreated` went with the descriptor in 032.2), the shared gob-read/engine substrate (`getgob`/`gobMatches`/`gobSnapshot`/`gui`/…), event bus + `callLua`, timers, the `:lua` REPL, `installHafen` |
| `AddonRegistry` | discovery/loadAll, enabled set (+ D-027 defaults), `reload`, per-addon teardown, the AddOns-panel data API |
| `WorldApi` | `hafen.world/map/markers/radar/time/sound/music` (per-gob reads are `LuaGob`) |
| `CharApi` | `hafen.player/char/study/party/kin/buff/actionbar/quest/wound/fight` — the section MOUNTS only; the reads live on the entity classes (`LuaQuest`/`LuaCondition`/`LuaWound` since 039.13) + the `TreeAdapter`s + `itemSnapshot` (the one Item producer; `hafen.items` was cut in 029.3) |
| `UiApi` | `hafen.ui` — a **callable** namespace (`hafen.ui(sel)`/`.all(sel)`/`hafen.ui()`, `root()` cut in 030.1): windows/overlays, selector events, the window-toggle seam + the `widget:replace(view)` substitution and its per-tick sweep, the entry points + selector resolution + hit-testing, the container-subscription poll (`adopt` deleted in 029.2; `hafen.ui.replace` + `LuaReplacer`/`LuaModel` in 032.2) |
| **`Selector`** | the selector grammar (030.1): parse `*`/role/`@Class`/`[title=]`/`[res=]` **once**, then match one widget. Pure — no Lua, no state — so the events and the inspector reuse it. The widget→role classifier itself lives in `LuaWidget.role` (D-067), beside `typeName`/`text`/`resName` |
| **`Sheet`** | one addon's stylesheet — the whole of `hafen.ui.skin{…}` (033): parse a Lua table of selector→properties **once**, classify each key site-vs-tree (`Fonts.isScope`), and fill the `haven.Fonts` owner-tagged stack. Properties are `font`/`color` (033.2), `bg`/`border` (035.1) and `pad` (035.2) — parsed by `Chrome`, drawn by `SkinDeco`/`SkinBox` — plus `pos`/`size` (036.2), each independently optional. Keys go through `Selector`, so the grammar and its errors are `hafen.ui(sel)`'s. The **tree** half resolves per widget into a `WeakHashMap` cache (034.1, read by `widget:style()`) and reaches the draw through `Fonts.treeStyles` → `specOf`, which the provider asks for every widget it draws (034.2) — on the **drawing** half alone, so a layout-only sheet never opens a frame |
| **`Layout`** | the layout half of that same cascade (036.2): resolve `pos`/`size` (tree rules, then the `widget:pos`/`:size` verbs as the hand-named top level) and **enforce** it — layout is a write, not something read at the draw. Applied on events only: `Sheet.skin`/`forget` sweep the tree (from *outside* the sheet's lock — the tree is touched under `ui`, and `ui`→`Sheet.class` is the draw's order), `onWidgetPlaced` + a bounded re-check catch a window as it opens, and the tick prunes records whose widget has left. The stock values live in `Addon.movedNative` (036.1) and answer `GameUI`'s position store |
| `HookApi` | `hafen.slash`; owns the hotkey registry behind `hafen.client:options():keybindings()`. `hafen.hook` (L1/L2/L3 + grab) is GONE (041): input is `widget:on(key, fn)` (`WidgetSubs`), action/message are `hafen.event():action()`/`:message()`, grab is `hafen.ui():mouse():grab()` (`LuaGrab`/`LuaMouseGrab`) |
| `ActApi` | `hafen.act` (gated verbs) + `hafen.speed`; `hafen.craft()` mounts here but its reads AND its gated `:make()` are on `LuaCraft` (039.13) — only `makewindow()` stays |
| `RenderApi` | `hafen.ghost` + `hafen.render` (world entities) |
| `StoreApi` / `HttpApi` / `FontApi` | `hafen.store` / `hafen.http` / `hafen.font` |
| **`LuaGob`** | the `hafen.gob(id)` Gob class (D-044): id-only userdata + per-addon metatable/intern cache (D-045) |
| `OptionsHandle` + `*Options` | `hafen.client:options()` — one subsystem per OptWnd panel (`interface`/`video`/`audio`/`camera`/`client`/`keybindings`), read/write by arity via `OptionsMethod` |
| Support classes | `Addon`, `Manifest`, `Json`, `Sandbox`, `AddonRoot`, `LuaMarshal`, **`Subs`**/**`WidgetSubs`** (the one subscription mechanism every emitter owns — keyed multimap + `fire` + the per-key profiling category, 041.1/041.3), **`LuaEvent`** (the `ev` object, one class + a per-shape methods table, 041.2-.4/.7), **`LuaMouse`**/**`LuaGrab`** (the pointer entity + its grab, 041.5), `Retired` (every retired spelling throws naming its replacement), `Lua*` (sub/widget/entities/images/meshes/nodes/fonts/GOut/WorldEntity), `GhostGob`, `SpriteQuad`, `FollowMoving`, `Gltf`, `ui/AddonPanel`, `ui/ActionsConsentWnd` |

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
| `UI.AddWidget` (`onWidgetPlaced(id, wdg)`) | the widget-**placement** seam, and the only one left: `hafen.ui.on` selector events (030.2) and, since 036.2, the layout cascade (`Layout.placed`) — two consumers, each with its own fast-path flag and its own bounded re-check for a late `[title=]`. The `UI.NewWidget` edit and the `{id,type,place,caption,parentType}` descriptor went with `hafen.ui.replace` (032.2) |
| [`GameUI.addchild`](src/haven/GameUI.java:910) | HUD placement switch (place-string routing) |
| `MapView` client-gob seam (`addClientGob`) + `Click.hit` intercept + `placeSnap` | the 3D-scene seams for client-only entities. `addClientGob`'s `Loading` is not only `MCache.LoadingMap` (an unstreamed tile) — a busy render backend can also throw `Defer.NotDoneException` from `TexL.prepare` ("finalizing texture …"), a *different*, rotating blocker on every retry rather than one stable wait, which is why `RenderApi`'s `Resolve.on` call for a scene add uses a much higher retry bound than the library default (042.12) |
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
