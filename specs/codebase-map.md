# Codebase Map (the whole client)

> **Status:** 🟢 Living. One-stop `file:line` index of the client's key code points, by
> subsystem. Line numbers are indicative (upstream merges shift them); the **class +
> method/field name is the stable anchor**. Per-subsystem detail beyond an anchor belongs in
> `specs/codebase/<subsystem>.md` (generated on demand by /plan). Max 300 lines.

## Boot / entry / init

| What | Where |
|---|---|
| `main()` → spawns "Haven main thread" | [`Client.main`](src/haven/Client.java:397) → [`main2`](src/haven/Client.java:363) |
| Resource setup (global init point, no session) | [`Client.setupres`](src/haven/Client.java:280), called at `main2` (~:367) |
| Runner state machine (`task.run(newui(task))`) | [`Client.run`](src/haven/Client.java:212) |
| Login → session establishment → returns `RemoteUI` | [`Bootstrap.run`](src/haven/Bootstrap.java:164), `Session.connect` (~:293) |
| **Per-session init (`ui.sess` bound)** ← engine attach point | [`RemoteUI.init(UI)`](src/haven/RemoteUI.java:147) |
| In-game HUD construction | [`GameUI` ctor](src/haven/GameUI.java:257) (`GameUI(String chrid, long plid, String genus)`) |

## Frame / tick loop

| What | Where |
|---|---|
| Render+tick thread ("Haven UI thread") | [`UILoop`](src/haven/UILoop.java) (thread created ~:58) |
| **Per-frame tick (holds `synchronized(ui)`)** | [`UILoop.Frame.tick`](src/haven/UILoop.java:433) |
| Game-state tick | `glob.ctick()` at [UILoop.java:442](src/haven/UILoop.java:442) → [`Glob.ctick`](src/haven/Glob.java:143) |
| **Widget-tree tick broadcast** ← engine `OnUpdate` point | `ui.tick()` at [UILoop.java:445](src/haven/UILoop.java:445) → [`UI.tick`](src/haven/UI.java:371) → `TickEvent` → [`Widget.tick`](src/haven/Widget.java:748) |
| Draw + one-shot after-draws | [`UI.draw`](src/haven/UI.java:386) (afterdraws cleared at [:391](src/haven/UI.java:391)) |
| Register a one-shot overlay | [`UI.drawafter`](src/haven/UI.java:365) |

## Threading & locks

| Concern | Rule / where |
|---|---|
| Widget tree + event dispatch | serialized by the **`UI` monitor** (`synchronized(ui)`); writers: UI thread + Loader threads |
| Deferred server widget ops | [`UI.CommandQueue`](src/haven/UI.java:245); executed on Loader threads, each under `synchronized(UI.this)` |
| OCache mutation | net receive on Connection worker ([`OCache.receive`](src/haven/OCache.java:514)); apply on Loader ([`GobInfo.apply`](src/haven/OCache.java:370)); `objs` under `synchronized(OCache)` |
| MCache mutation | Connection worker (`mapdata`) under `synchronized(grids)`/`synchronized(req)` |
| `Loading` exception protocol | [`Loader.Future.run`](src/haven/Loader.java:76); any code touching resources/gobs/grids must tolerate it |

## Widget system

| What | Where |
|---|---|
| Widget base (tree, `tick`, `draw`, input) | [`Widget`](src/haven/Widget.java) |
| Add/attach child | [`Widget.add`](src/haven/Widget.java:250), `add0` (~:236), `attach` (:220) |
| **Type registry (`@RName` → Factory)** ← replacement seam A | [`Widget.types`](src/haven/Widget.java:51), [`Factory`](src/haven/Widget.java:142), [`initnames`](src/haven/Widget.java:156), [`gettype3`](src/haven/Widget.java:169) |
| UI root / id map / dispatch | [`UI`](src/haven/UI.java): `root` (:48), `widgets`/`rwidgets` (:50), `bind`/`getwidget`/`widgetid` (:343–363) |
| **Server → widget create** ← replacement seam A | [`UI.NewWidget.run`](src/haven/UI.java:433), `newwidgetp` (:510) |
| **Server → widget place** ← replacement seam B | [`UI.AddWidget.run`](src/haven/UI.java:470) → `pwdg.addchild(...)` |
| HUD placement switch (per type: inv/equ/chr/craft/…) | [`GameUI.addchild`](src/haven/GameUI.java:910) |
| Window chrome (title/drag/close) | [`Window`](src/haven/Window.java:35) |
| CPU-buffered widget base (complex panels) | [`SIWidget`](src/haven/SIWidget.java) |
| 2D drawing context | [`GOut`](src/haven/GOut.java) (image/text/rect/line/prect/chcolor) |

## UI-API extensions (U-series — [07-ui-and-drawing.md](addons/design/07-ui-and-drawing.md), D-038/D-039/D-040)

> Additive addon-UI seams: widget **drops** (`onDrop`), the **`g:resource`** engine-`.res` draw verb, and
> **`mods`** on mouse callbacks. All in `io.brodgar.addon`; **zero `haven` core edit**.

| What | Where |
|---|---|
| **Drop dispatch (the source)** | [`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) → `DropTarget.dropthing(ui.root, ui.mc, dragging)`; `dragging` = a [`MenuGrid.Pagina`](src/haven/MenuGrid.java:63) |
| Generic drop interface + tree walk | [`DropTarget`](src/haven/DropTarget.java:29) (`dropthing(Coord,Object)`); `Drop` event via `PointerEvent.propagation` ([`Widget`](src/haven/Widget.java:981)) — calls the first `DropTarget` under the cursor |
| Pagina → `{kind,res}` descriptor | [`Pagina.res().name`](src/haven/MenuGrid.java:77) (Loading-guarded); res-vs-id split like the [belt `dropthing`](src/haven/GameUI.java:224) |
| **`onDrop` / `mods` host** | [`LuaWidget`](src/io/brodgar/addon/LuaWidget.java) — `implements DropTarget`; mouse forwards add `mods` from `ui.modflags()`; opts via [`AddonManager.newUi`](src/io/brodgar/addon/AddonManager.java:3431) |
| **`g:resource` draw verb** | [`LuaGOut`](src/io/brodgar/addon/LuaGOut.java) — name→`Indir` via [`Resource.remote().load`](src/haven/Resource.java:866), default image layer `Resource.imgc`, `Loading`-guarded + cached; blit [`GOut.image(Tex,Coord[,Coord])`](src/haven/GOut.java:97) |
| Modifier-flags source + helper | `ui.modflags()` ([`UI`](src/haven/UI.java)); `modsTable(...)` in [`AddonManager`](src/io/brodgar/addon/AddonManager.java) |
| Native empty-slot look (NOT a `.res`) | [`Inventory.invsq`](src/haven/Inventory.java:34) `TexI` (code-built) + [`Inventory.sqsz`](src/haven/Inventory.java:33) — an addon draws its own slot bg |

## Widget introspection (W-series — [20-widget-introspection.md](addons/design/20-widget-introspection.md), D-041/D-042)

> W1 = generic read-only walk of any widget's guts (`hafen.ui.root()`/`node(id)` → `WidgetNode`); W2 = coordinate
> hit-testing (`hafen.ui.at`/`mouse`/`node:rootpos`) = a WoW-`/framestack` clone. All backings **public**; in
> `io.brodgar.addon`; **zero `haven` core edit**. To act, pass `node:id()` to the gated `hafen.act.raw`.

| What | Where |
|---|---|
| Child list (tree order) | [`Widget.children()`](src/haven/Widget.java:1742) (returns a `Children` view — copy under `synchronized(ui)`) |
| Server id (`-1` = not server-bound) | [`Widget.wdgid()`](src/haven/Widget.java:560) → [`UI.widgetid`](src/haven/UI.java:588) (looks up `rwidgets`) |
| Pos / size / visibility / parent / class | `Widget.c`, `Widget.sz`, [`Widget.visible()`](src/haven/Widget.java), `Widget.parent`, `getClass().getSimpleName()` |
| **`:text()` source (best-effort, one switch)** | [`Label.texts`](src/haven/Label.java:34) (public `String`); button captions / `TextEntry` per type; unknown → `nil` |
| Why `:id()` gates action | [`Widget.wdgmsg`](src/haven/Widget.java:741) bubbles to [`UI.wdgmsg`](src/haven/UI.java:667)→[`rawWdgmsg`](src/haven/UI.java:680); **unbound sender (`id<0`) is dropped** ([:681](src/haven/UI.java:681)) |
| Node host (opaque, lazy, stale-graceful) | **`LuaWidgetNode`** (new, `io.brodgar.addon`) — holds the `Widget` in Java (P1/D-017), `GobRef`-style liveness check, **not** an owned-registry entry |
| **`:same(other)` identity** | reference equality of the two wrapped `Widget`s inside `LuaWidgetNode` (nil-safe; a stale node is never `:same` as a live one) — the per-frame guard `widgetstack` needs |
| **W2: cursor position (root coords)** | [`UI.mc`](src/haven/UI.java:54) (public `Coord`) → `hafen.ui.mouse()` |
| **W2: hit-test walk to mirror (`at`)** | [`PointerEvent.propagation`](src/haven/Widget.java:981) — `lchild→prev` (topmost-first), skip `!visible()`, `parent.xlate(child.c,true)`+rect-isect; leaf uses [`checkhit`](src/haven/Widget.java:794) |
| **W2: coord translation (scroll offsets)** | [`Widget.xlate`](src/haven/Widget.java:482) / [`rootxlate`](src/haven/Widget.java:504) — `at()` must respect these, not a naïve rect test |
| **W2: highlight box** | [`Widget.rootpos()`](src/haven/Widget.java:496) → `node:rootpos()` `{x,y}` + `:size()` |

## Fonts (F-series — [21-fonts.md](addons/design/21-fonts.md), D-043, `hafen.font.*`)

> Per-addon font handles + owned overrides on named client surfaces. Provider carries a **generation counter**;
> routed sites (tagged `// addon:`) rebuild their foundry when it moves. New code in `io.brodgar.addon`
> (`FontApi` + a `haven`-reachable `Fonts` provider facade + `FontHandle`); per-slice one-liners at render sites.

| What | Where |
|---|---|
| Foundry (font+size+colour+aa) | [`Text.Foundry`](src/haven/Text.java:127); `renderwrap` builds a `RichText.Foundry` |
| **Global default** (`"default"` scope, F1) | [`Text.std`](src/haven/Text.java:50) = `new Foundry(sans,10)` (`public static final`); [`Text.render(…)`](src/haven/Text.java:359) statics; [`Label`](src/haven/Label.java:62) default |
| Built-in fonts | [`Text.sans/serif/mono/fraktur`](src/haven/Text.java:37) |
| Window titles (`"window.title"`, F3) | [`Window.DefaultDeco.cf/ncf`](src/haven/Window.java:178) = `new Text.Foundry(Text.fraktur,15).aa(true)` |
| **Speech bubbles** (`"world.speech"`, F4) | [`Speaking`](src/haven/Speaking.java:32) — cached `Text` (ctor + `update`), frame measured from `text.sz()` in `draw`; stock = `Text.std` |
| **Floating kin names** (`"world.nick"`, F4) | **NOT in the fork** — published code in the `ui/obj/buddy` resource (`haven.KinInfo` is gone; `OCache.OD_BUDDY` commented `-- Removed`). Adopted with `get-code` → `src/haven/res/ui/obj/buddy/{Buddy,Info,InfoPart}.java`: shared foundry `InfoPart.fnd` + `rendertext`, composed `Tex` invalidated by `Info.dirty()` |
| Resource-code adoption | `java -cp bin/hafen.jar haven.Resource get-code <res>` + [`@FromResource`](src/haven/FromResource.java) (name+version must match — [`ResClassLoader.loadClass`](src/haven/Resource.java:1552)); `haven.Resource find-updates src` checks the pins |
| **Per-run markup** (`$font`, F2) | [`RichText` `$font` tag](src/haven/RichText.java:566) — resolves by **AWT family name** (`TextAttribute.FAMILY`); custom TTF needs `GraphicsEnvironment.registerFont` at `load` |
| DPI sizing | [`UI.scale(float)`](src/haven/UI.java:982) — every produced size passes through it |
| ~81 baked `Foundry` sites | across 36 files (`Label`, `ChatUI`, `SListMenu`, `CharWnd`, `Button`, `FlowerMenu`, …) — routed **per slice**, not at once |
| Custom-TTF load | `Font.createFont(TRUETYPE_FONT, file)` (built-ins from `Text.*`); [`Resource.Font`](src/haven/Resource.java) is the resource-backed path |

## Networking / session

| What | Where |
|---|---|
| Session (connection + glob + resource ids) | [`Session`](src/haven/Session.java) — `glob` (:65), `queuemsg`; created by `Bootstrap.run` |
| The wire connection (worker threads) | [`Connection`](src/haven/Connection.java) — receive callbacks land on the **Connection worker** thread (never touch Lua/widgets there; marshal to the UI tick) |
| Server → client UI messages | `RemoteUI.uimsg` → [`UI.uimsg`](src/haven/UI.java:702) (queues a `UiMessage` Command, applied on a Loader thread under `synchronized(ui)`) |
| Client → server | [`UI.wdgmsg`](src/haven/UI.java:665) → [`RemoteUI.rcvmsg`](src/haven/RemoteUI.java:39) → `Session.queuemsg` |
| Relog/session rebind | a new `RemoteUI.init(UI)` per session — session-scoped engine state resets there |

## Action channel (client → server)

| What | Where |
|---|---|
| **Universal action send** | [`Widget.wdgmsg`](src/haven/Widget.java:737) → [`UI.wdgmsg`](src/haven/UI.java:665) → [`RemoteUI.rcvmsg`](src/haven/RemoteUI.java:39) → `Session.queuemsg` |
| Map clicks / move / itemact / place / sel | [`MapView`](src/haven/MapView.java) (Click.hit ~:2007, drop ~:2085, itemact ~:2094, place ~:2038, sel ~:2279) |
| Gob click arg encoding | [`Gob.GobClick.clickargs`](src/haven/Gob.java:677), [`Composited.CompositeClick`](src/haven/Composited.java:480) |
| Menu action by path / id | [`GameUI.act`](src/haven/GameUI.java:1683), [`MenuGrid.PagButton.use`](src/haven/MenuGrid.java:169) |
| Flower petal select | [`FlowerMenu.choose`](src/haven/FlowerMenu.java:278) (`wdgmsg("cl", num)`) |
| Item take/drop/transfer/iact/itemact | [`WItem`](src/haven/WItem.java:171) |
| Server → widget update | [`Widget.uimsg`](src/haven/Widget.java:677), dispatched via [`UI.uimsg`](src/haven/UI.java:702) |

## State roots (read surfaces)

| What | Where |
|---|---|
| Global root | [`Glob`](src/haven/Glob.java:34) via `ui.sess.glob` ([`UI.sess`](src/haven/UI.java:55), [`Session.glob`](src/haven/Session.java:65)) |
| Object cache (gobs) | [`OCache`](src/haven/OCache.java:35): iterate (:164), `getgob` (:199), `callback`/`uncallback` (:75/:79) |
| Game object | [`Gob`](src/haven/Gob.java:33): `id` (:38), `rc` (:34), `a` (:35), `getattr` (:611), `getc` (:584) |
| Gob attributes | [`GAttrib`](src/haven/GAttrib.java): `Drawable.getres()`, [`Moving`](src/haven/Moving.java), [`GobHealth.hp`](src/haven/GobHealth.java:35), [`GobIcon`](src/haven/GobIcon.java), [`Speaking`](src/haven/Speaking.java) |
| Map / terrain | [`MCache`](src/haven/MCache.java:36): `gettile` (:929), `tilesetr` (:1107), `getcz` (:948), `getgrid` (:909); `tilesz`/`cmaps` (:37/:39) |
| Player id / gob / camera | [`MapView.plgob`](src/haven/MapView.java:45), `player()` (:1133), `getcc()` (:1137), `camera` (:51) |
| Inventory / items | [`GameUI.maininv`](src/haven/GameUI.java:54), [`Inventory.wmap`](src/haven/Inventory.java:38), [`GItem`](src/haven/GItem.java) (`res`, `num` :39, `meter` :39, `info()` :199) |
| Equipment | [`GameUI.equwnd`](src/haven/GameUI.java) → [`Equipory.wmap`](src/haven/Equipory.java:83) |
| Item metadata / name | [`ItemInfo`](src/haven/ItemInfo.java): `Name` (:177), `find` (:354), `buildinfo` (:362) |
| Character attributes | [`Glob.getcattr`](src/haven/Glob.java:344), `CAttr{base,comp}` (:87); [`CharWnd`](src/haven/CharWnd.java) (exp/enc :60) |
| Party | [`Glob.party`](src/haven/Party.java:32): `memb` (:33), `Member.getc()` (:60), `col` (:49) |
| Time / astronomy | [`Glob.globtime`](src/haven/Glob.java:210) (`gtime` :39); [`Glob.ast`](src/haven/Glob.java:40) → [`Astronomy`](src/haven/Astronomy.java:32) `dt`/`night`/`mp`/`yt`/`is` ([:32–36](src/haven/Astronomy.java:32)); light fields (:42–47) |
| Gob speech / icon | [`Speaking.text`](src/haven/Speaking.java:37) (reliable); [`GobIcon.Icon.name()`](src/haven/GobIcon.java:64) (Loading-guarded) |

## Cross-cutting client services

| Service | Where |
|---|---|
| Console commands (register only; no unregister) | [`Console.setscmd`](src/haven/Console.java:54), `Directory` (:47); input via [`ConsoleHost`](src/haven/ConsoleHost.java) / `GameUI` `:` line |
| Keybinding registry (remappable, persisted) | [`KeyBinding.get`](src/haven/KeyBinding.java:57), `Bindable` (:81) |
| Resource system (classpath / local dir / server) | [`Resource`](src/haven/Resource.java): `local()` (:845), `remote()` (:866), `FileSource`/`JarSource` (~:318/:368); local dir via `haven.resdir`/`HAFEN_RESDIR` (:44) |
| Jar-relative path resolution | [`Utils.srcpath`](src/haven/Utils.java:122) |
| Local data dir (%APPDATA%) — **not** used for addons | [`Config.localdir`](src/haven/Config.java:100) |
| Preferences (base client only) | [`Utils.getpref/setpref`](src/haven/Utils.java:408), `prefs()` (:389) |
| Audio / sfx | play: [`UI.sfx`](src/haven/UI.java:931) → [`Audio.fromres`](src/haven/Audio.java:585); music: [`Music.play`](src/haven/Music.java:138); volume `Audio.Root.volume()` |
| Chat | send: [`ChatUI.EntryChannel.send`](src/haven/ChatUI.java:814) (`wdgmsg("msg",text)`); read: `Channel.rmsgs` [:124](src/haven/ChatUI.java:124), append [:275](src/haven/ChatUI.java:275); `Message.time` (epoch sec) [:131](src/haven/ChatUI.java:131); channels `Channel` [:123](src/haven/ChatUI.java:123)/`sel` [:53](src/haven/ChatUI.java:53) |
| Combat | [`Fightview`](src/haven/Fightview.java): `lsrel` [:41](src/haven/Fightview.java:41), `current` [:47](src/haven/Fightview.java:47), `Relation.gobid/ip/oip` [:54](src/haven/Fightview.java:54)/[:57](src/haven/Fightview.java:57); deck [`Fightsess.actions`](src/haven/Fightsess.java:46); `GameUI.fv` [:48](src/haven/GameUI.java:48) |
| Buffs | [`GameUI.buffs`](src/haven/GameUI.java:71) → `children(Buff.class)`; [`Buff.res`](src/haven/Buff.java:42), [`Buff.info()`](src/haven/Buff.java:70) |
| Kin / buddy roster (A6) | [`GameUI.buddies`](src/haven/GameUI.java:58) ([`BuddyWnd`](src/haven/BuddyWnd.java:34), `Iterable<Buddy>`); [`iterator()`](src/haven/BuddyWnd.java:171) (copies under lock) / [`find(int)`](src/haven/BuddyWnd.java:177); [`Buddy.id`/`name`/`online`/`group`](src/haven/BuddyWnd.java:87) (public); palette [`BuddyWnd.gc`](src/haven/BuddyWnd.java:50); changes via `uimsg` `add`/`rm`/`chst`/`upd` ([:547](src/haven/BuddyWnd.java:547)) — `serial` skips `chst`. Mutate (Phase 4): `wdgmsg` `rm`/`nick`/`grp` |
| Player vitals (bars only) | meters via `GameUI.addchild "meter"` [:1014](src/haven/GameUI.java:1014); value [`LayerMeter.meters[i].a`](src/haven/LayerMeter.java:35); identity [`IMeter.bg`](src/haven/IMeter.java:36) — **no absolute numbers, no hunger** |
| FEP / food / hunger (1d-2) | [`CharWnd.battr`](src/haven/CharWnd.java:53) ([`BAttrWnd`](src/haven/BAttrWnd.java)) `feps` (`FoodMeter.cap`/`els`, `El.res`/`a`/`ev()`) + `glut` (`GlutMeter.glut`/`lbl`/`gmod`) — all public; **the one place absolute FEP/hunger numbers exist** |
| Study / curiosity (1d-3) | [`CharWnd.sattr`](src/haven/CharWnd.java:54) ([`SAttrWnd`](src/haven/SAttrWnd.java)) → `children(StudyInfo.class)` → [`StudyInfo.study`](src/haven/SAttrWnd.java:141) (`children(GItem.class)`) + totals `texp`/`tw`/`tenc`; per item [`resutil.Curiosity`](src/haven/resutil/Curiosity.java:36) `exp`/`mw`/`enc`/`time` (public). `time` = **total** (no countdown) |
| Skills (1d-3) | [`CharWnd.skill`](src/haven/CharWnd.java:55) ([`SkillWnd`](src/haven/SkillWnd.java)) → `skg.csk`/`nsk` ([`GridList.Group.items`](src/haven/GridList.java:44), swapped off-thread) → [`Skill.nm`/`res`](src/haven/SkillWnd.java:60); credos `credos.ccr`/`ncr`/`pcr`, experiences `exps.seen.items` (deferred) |
| Crafting (A8) | [`Makewindow`](src/haven/Makewindow.java:37) (`@RName("make")`), wrapped in private [`GameUI.makewnd`](src/haven/GameUI.java:52) at [`place="craft"`](src/haven/GameUI.java:977) → locate via `children(Makewindow.class)`. Public: `rcpnm`, `inputs`([`Input`](src/haven/Makewindow.java:331))/`outputs`([`SpecWidget`](src/haven/Makewindow.java:260)) → [`Spec`](src/haven/Makewindow.java:59) (`item`/`constraint` [`ResData.res`](src/haven/ResData.java:32), `num`, `opt()`), `qmod`/`tools` (`List<Indir<Resource>>`). `inputs`/`outputs`/`qmod` swapped wholesale off-thread (`inpop`/`opop`/`qmod` uimsg); `tools` **in-place** `add` (`tool` uimsg) → copy under `synchronized(ui)`. Make (Phase 4): `wdgmsg("make",0\|1)` |

## Virtual entities / 3D scene (V-series — [16-virtual-entities.md](addons/design/16-virtual-entities.md))

| What | Where |
|---|---|
| **Client-only world entity (template)** | [`MapView.Plob extends Gob`](src/haven/MapView.java:1779) — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | [`Gob(Glob,Coord2d)`](src/haven/Gob.java:441) / [`Gob(Glob,Coord2d,long)`](src/haven/Gob.java:433); `Gob implements RenderTree.Node, Sprite.Owner` ([:33](src/haven/Gob.java:33)) |
| Visual attr | [`ResDrawable`](src/haven/ResDrawable.java:79) (`Gob.setattr`) |
| Add/remove in 3D scene | `MapView.basic` scene slot + `Gob.placed` (Plob does `basic.add(placed)` / `slot.remove()`) → **needs a `// addon:` MapView seam**, opt (A) `addClientGob`/`removeClientGob` or (B) `AddonWidgets` accessor |
| Screen → world (ground raycast) | [`MapView.Maptest`](src/haven/MapView.java:1810) (`Plob.Adjust.hit(Coord pc, Coord2d mc)`) → `hafen.map.screenToWorld` |
| World → screen | `hafen.player.worldToScreen` (`MapView.screenxf`, exists) |
| **Placement snapping (V5/V6) — placegrid/placeangle** | [`PlobAdjust`](src/haven/MapView.java:1740) / [`StdPlace`](src/haven/MapView.java:1746) (position [:1749](src/haven/MapView.java:1749), rotation [:1764](src/haven/MapView.java:1764)); **public** [`plobpgran`/`plobagran`](src/haven/MapView.java:57); `:placegrid`/`:placeangle` cmds ([:2391](src/haven/MapView.java:2391)) → `hafen.map.snapPlace/snapAngle/placeGrid/placeAngle` |
| Pick pass (V2 clickable ghosts, V5 handles) | [`ClickMap`](src/haven/MapView.java:507), [`MapClick extends Clickable`](src/haven/MapView.java:911), [`Clicklist`](src/haven/MapView.java:1155), [`ClickLocation`](src/haven/MapView.java:1409), [`Gob.GobClick`](src/haven/Gob.java:673) |
| **Click dispatch ← ghost-click intercept point (V2)** | [`MapView.Hittest`](src/haven/MapView.java:1962) resolves pick → [`Click.hit`](src/haven/MapView.java:2017) ends in `wdgmsg("click", …)` ([:2023](src/haven/MapView.java:2023)); the **voice feature already hooks here** ([:2018-2019](src/haven/MapView.java:2018)) → `// addon:` intercept: client ghost ⇒ dispatch + return (no `wdgmsg`) |
| Modal mouse capture (drag) | [`UI.grabmouse(Widget)`](src/haven/UI.java:575) / [`UI.grab`](src/haven/UI.java:538); + `hafen.hook.input`+`preventDefault` (2c) |

## Custom rendering (R-series — [17-custom-rendering.md](addons/design/17-custom-rendering.md) / [18-custom-models-gltf.md](addons/design/18-custom-models-gltf.md), `hafen.render.*` non-`.res`)

> `hafen.ghost` (`.res` game models) is unchanged; these are the seams for the **non-`.res`** siblings
> (images 2D screen/world + glTF 3D models) on the **generalized V-series world-entity core**. Visuals attach as a
> `Gob` `Drawable` attr, so the whole transform/gizmo/teardown stack ([Virtual entities](codebase-map.md) above) is reused.

| What | Where |
|---|---|
| **`.res` is already PNG** (the substrate we expose) | [`Resource.readimage`](src/haven/Resource.java:1061) `ImageIO.read(fp)` → [`Resource.Image` `new TexI(img)`](src/haven/Resource.java:1182) |
| PNG → GPU texture (no `.res`) | [`new TexI(BufferedImage)`](src/haven/TexI.java:52); GPU upload lazy/thread-safe in [`TexI.st()`](src/haven/TexI.java:59) → a [`ColorTex`](src/haven/render/ColorTex.java:34) |
| Addon-relative file read (sandbox root) | [`Addon.dir.resolve(name)`](src/io/brodgar/addon/Addon.java:173) + `Files.readAllBytes` (reject `..`/absolute, [D-017](addons/decisions/security-sandbox.md)) |
| **R1 — 2D screen blit** | [`GOut.image(Tex,Coord)`](src/haven/GOut.java:97) / [scaled `(Tex,Coord,Coord)`](src/haven/GOut.java:117) / [`aimage(Tex,Coord,ax,ay)`](src/haven/GOut.java:107) — wired into [`LuaGOut`](src/io/brodgar/addon/LuaGOut.java) as `g:image`/`g:aimage` |
| Bare-`TexI` blit precedent (no `.res`) | [`SpeakerIcon.GLYPH`](src/haven/SpeakerIcon.java:51) = `new TexI(img)` drawn via `g.image(...)` ([:87](src/haven/SpeakerIcon.java:87)) |
| **R2b billboard — world-anchored 2D blit** *(pending)* | [`SpeakerIcon`](src/haven/SpeakerIcon.java:44) `extends GAttrib implements RenderTree.Node, PView.Render2D` ([:406](src/haven/PView.java:406)); `draw(GOut,Pipe)` projects via [`Homo3D.obj2view`](src/haven/render/Homo3D.java:201) then `g.image(...)` |
| **R2a fixed — world textured quad** ✅ | [`SpriteQuad`](src/io/brodgar/addon/SpriteQuad.java) = a resource-free `Sprite`: `quadVerts`(upright x=0 plane, z 0→h, y ±w/2, t-inverted) → [`Model(TRIANGLE_STRIP,VertexArray,null,0,4)`](src/haven/render/Model.java:45), [`Layout`](src/haven/render/VertexArray.java:65) of [`Homo3D.vertex` VEC3](src/haven/render/Homo3D.java:41)+[`Tex2D.texc` VEC2](src/haven/render/Tex2D.java:36) (world-vertex xf from the scene's `Homo3D.state`, [PView:385](src/haven/PView.java:385)) |
| R2a texture material (fixed) ✅ | `new Material(tr.draw, tr.clip, Material.nofacecull)`[`.apply(model)`](src/haven/Material.java:165) → a `RenderTree.Node` — `tr` = a [`TexRender`](src/haven/TexRender.java:34) over the `TexI` sampler (`tex.st().data`); [`TexDraw`](src/haven/TexRender.java:73) samples + [`TexClip`](src/haven/TexRender.java:97) **alpha-discards** (the `.res` [`$tex`](src/haven/TexRender.java:138) matpart, `clip=true`) → SOLID, not blended; double-sided, unlit. *(NOT `ColorTex`+`FragColor.blend` — that translucent-overlay recipe read as a 1% ghost.)* |
| **Resource-free `Drawable` (the visual attach)** ✅ | [`SprDrawable(Gob, Sprite.Mill)`](src/haven/SprDrawable.java:35), [`getres()==null`](src/haven/SprDrawable.java:63); wraps the `SpriteQuad` (`Mill` resolves the owner cycle); same [`Drawable`](src/haven/Drawable.java:31) attr slot as `ResDrawable` |
| Shared world-entity core (generalized in R2a) ✅ | base [`LuaWorldEntity`](src/io/brodgar/addon/LuaWorldEntity.java) (transform/look/scene/lifecycle) → `LuaGhost`/`LuaSprite` visuals; scene add [`MapView.addClientGob`](src/haven/MapView.java:1868); transform [`Gob.Placed`](src/haven/Gob.java:922); look/scale [`GhostGob.obstate`](src/io/brodgar/addon/GhostGob.java:93) (shared); `AddonManager` `*Entity` helpers; the gizmo (`planner/gizmo.lua`) |
| R2a anchor — follow a gob ✅ (`:follow`/`offset=`) | [`FollowMoving extends Moving`](src/io/brodgar/addon/FollowMoving.java): `getc()` = [`target.getc()`](src/haven/Gob.java:584)`.add(offset)`, re-resolved via [`OCache.getgob`](src/haven/OCache.java:199) each frame; the render tree's per-frame [`Placed.autotick`](src/haven/Gob.java:942) tracks it (NOT a [`Following`](src/haven/Following.java:32) subclass → keeps own facing). Zero core edit (`Moving`/`Gob.glob`/`getc`/`Coord3f.add` public). The world-space [`gobOverlay`](src/io/brodgar/addon/LuaGobOverlay.java) analog |
| Upright option (fixed sprite) | [`Location.nullrot`](src/haven/render/Location.java:169) (ignore gob facing) |
| **R3 — glTF parse** | JSON via [`Json`](src/io/brodgar/addon/Json.java); `.glb` chunks + accessor/bufferView decode by hand (`ByteBuffer` LE); data-URIs via `java.util.Base64`; textures via `ImageIO` → `TexI` |
| **R3 — glTF → geometry** | per primitive → a `Model` (POSITION→[`Homo3D.vertex`](src/haven/render/Homo3D.java:41), NORMAL→[`Homo3D.normal`](src/haven/render/Homo3D.java:42) [VEC3 `"normal"` attr, shaded to eye space as `mat3(cam)·mat3(wxf)·objn`], TEXCOORD_0→[`Tex2D.texc`](src/haven/render/Tex2D.java:36), indices via `Model.Indices`); baseColor `TexI.st()` + `BaseColor` factor → `Material.apply`; collect into a `Sprite` → `SprDrawable` |
| **R3c — lighting (done)** | material state = [`Light.PhongLight`](src/haven/Light.java:145) (frag; ctor takes emi/amb/dif/spc/shine; `PhongLight.defamb/defdif/defspc` neutral defaults) → the [`Phong`](src/haven/render/Phong.java:35) shader multiplies the scene's [`Lighting.lights`](src/haven/render/Lighting.java:38)/[`Light.LightList`](src/haven/Light.java:81) (applied at the MapView/PView scene root — [`PView.lights`](src/haven/PView.java:40)) into the fragment. Normals baked by the **inverse-transpose** of `basis·node` ([`Matrix4f.invert`](src/haven/Matrix4f.java:175)/[`transpose`](src/haven/Matrix4f.java:149)/[`trim3`](src/haven/Matrix4f.java:159)) in `Gltf`; emissive = glTF `emissiveFactor`→Phong `emi`. **sRGB = no-op** ([`Texture.srgb`](src/haven/render/Texture.java:38) left `false` in the model load path, like all game textures) |

## Integration template (existing voice feature — the pattern to mirror)

| Piece | Where |
|---|---|
| Static facade | [`io.brodgar.voice.Voice`](src/io/brodgar/voice/Voice.java) (all-static, `attach`/`detach`/`tick`) |
| Lifecycle hooks (~14 lines) | `MapView` ctor (~:502), `dispose` (~:514), `tick` (~:1690), keydown/keyup (~:2128) |
| UI piggyback: gob overlay | [`SpeakerIcon`](src/haven/SpeakerIcon.java) (`GAttrib` + `RenderTree.Node` + `PView.Render2D`) |
| UI piggyback: flower petal | `FlowerMenu.addVoicePetal` (~:224), `choose` intercept (~:278) |
| UI piggyback: options panel | `OptWnd.VoiceChatPanel` (~:458), main button (~:843) |
| Keybinding | `Voice.kb_ptt = KeyBinding.get("brodgar/ptt", …)` (~:65) |

> See [11-core-hooks.md](addons/design/11-core-hooks.md) for how these map to the addon engine's own hooks,
> and [08-widget-replacement.md](addons/design/08-widget-replacement.md) for the creation-path seams in detail.

## The addon engine (`src/io/brodgar/addon/`) — file layout after the god-class split

| File | Owns |
|---|---|
| `AddonManager` (hub, ~1.6k lines) | lifecycle (attach/init/tick), the `haven` seams (`onUimsg`/`onWdgmsg`/`onMessage`/`onGlobKey`/`onWidgetCreated`/`onWidgetPlaced`/`onGhostClick` — core edits call these by name; they delegate), the shared gob-read/engine substrate (`resolve`/`gobSnapshot`/`gui`/…), event bus + `callLua`, timers, the `:lua` REPL, `installHafen` |
| `AddonRegistry` | discovery/loadAll, enabled set (+ D-027 defaults), `reload`, per-addon teardown, the AddOns-panel data API |
| `WorldApi` | `hafen.gob/world/map/markers/radar/time/sound/music` |
| `CharApi` | `hafen.player/char/items/study/party/kin/buffs/actionbar/quests/wounds/fight` + the `TreeAdapter`s |
| `UiApi` | `hafen.ui` — windows/overlays, observe/adopt/replace, WidgetNode + hit-testing |
| `HookApi` | `hafen.hook` (L1/L2/L3 + grab), `hafen.key`, `hafen.slash` |
| `ActApi` | `hafen.act` (gated verbs) + `hafen.craft`/`hafen.speed` writes |
| `RenderApi` | `hafen.ghost` + `hafen.render` (V/R-series entities) |
| `StoreApi` / `HttpApi` / `FontApi` | `hafen.store` / `hafen.http` / `hafen.font` |
| Support classes | `Addon`, `Manifest`, `Json`, `Sandbox`, `AddonRoot`, `LuaMarshal`, `Lua*` (widget/hooks/entities/images/meshes/nodes/fonts), `GhostGob`, `Gltf`, `ui/AddonPanel`, `ui/ActionsConsentWnd` |

Pattern: cluster files `import static AddonManager.*`; the seams stay in the hub as one-line
delegates so `haven` core edits never move. `haven.Fonts` + `haven.AddonWidgets` are the two
`haven`-package helper files the addon layer owns.

## Extension points (where new features hook in)

| Seam | Use |
|---|---|
| `@RName` registry ([`Widget.types`](src/haven/Widget.java:51)) | how server type names map to widget factories (replacement seam A — unused so far; B = adopt-after-create) |
| [`RemoteUI.init`](src/haven/RemoteUI.java:147) | per-session attach — the engine's entry point |
| The invisible `AddonRoot` on `ui.root` | per-frame tick + `globtype` hotkeys, zero core edit (invisible widgets still tick) |
| [`UI.wdgmsg`](src/haven/UI.java:665)/[`UI.uimsg`](src/haven/UI.java:702) taps | outbound L2 / inbound L3 + tree-read events (already split/tapped, `// addon:`) |
| `UI.NewWidget`/`AddWidget` (`onWidgetCreated`/`onWidgetPlaced`) | widget-creation interception + descriptors |
| [`GameUI.addchild`](src/haven/GameUI.java:910) | HUD placement switch (place-string routing) |
| `MapView` client-gob seam (`addClientGob`) + `Click.hit` intercept + `placeSnap` | the 3D-scene seams for client-only entities |
| `Widget.listen`/`deafen`, `KeyBinding.get`, `Console.setscmd`, `OptWnd.Panel`+`PButton`, `OCache.callback`, `UI.drawafter`, `DropTarget` | zero-edit seams the engine already provides |
| `haven.Fonts` provider (+ `Fonts.enter` dynamic scope, `Fonts.frame` per-instance) | font routing for any new render site |
| Resource-code adoption (`get-code` + `@FromResource`) | edit published `.res` code via a version-pinned local copy |

## Gotchas (client-wide; detail in specs/addons/learnings/)

- **One UI thread**: the frame loop is `tick → draw → swap` under `synchronized(ui)`; Loader
  threads apply server messages under the same monitor. All Lua must run holding it. Never
  call Lua from a Connection worker — queue and drain on the tick.
- **`Loading` is a control-flow exception**: any resource/gob/grid read can throw it —
  swallow → nil/partial, or defer to a loader task; never let it cross into Lua.
- **Widget creation runs off the UI lock** (Loader) before attach/bind — do tree work in
  `added()`/`attached()`. `Widget.add` links directly (does NOT route through `addchild`).
- **`.res` lifecycle**: resources resolve async (`Resource.remote()`, `Indir.get()` throws
  `Loading` until cached); some `.res` files carry published Java code the fork does not
  ship — never assume a resource's name (servers ship `-alt` variants); read it off the
  running client.
- **Java engine changes need `ant` rebuild + full client restart** (no hot-reload); only
  Lua addon files reload live (`:reload`). `ant hafen-client` is INCREMENTAL — a moved
  symbol can false-green; `rm -rf build/classes` for a true compile check.
- **No global world position**: `rc` is login-relative; the shareable anchor is grid id +
  within-grid offset. Grid/segment ids are 64-bit → expose as decimal strings.
- **Windows/Git-Bash**: `;`-separated `-cp` needs `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`;
  write throwaway test files to the scratchpad, not `/tmp`.
