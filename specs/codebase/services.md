# Subsystem: cross-cutting client services

> Console, keybindings, resources, prefs + the Options window (`GSettings`), audio, chat, combat,
> buffs, kin, vitals, FEP, study, skills, crafting, the action menu. Line numbers are indicative;
> the **class + field/method name is the stable anchor**. Max 70 lines.

| Service | Where |
|---|---|
| Console commands (register only; no unregister) | [`Console.setscmd`](src/haven/Console.java:54), `Directory` (:47); input via [`ConsoleHost`](src/haven/ConsoleHost.java) / `GameUI` `:` line |
| Keybinding registry (remappable, persisted) | [`KeyBinding.get`](src/haven/KeyBinding.java:93) (id → binding, process-global), `set` (:45), `key()` (:89), `all()` (fork addition), `Bindable` (:117); stored as `keybind/<id>` |
| Resource system (classpath / local dir / server) | [`Resource`](src/haven/Resource.java): `local()` (:845), `remote()` (:866), `FileSource`/`JarSource` (~:318/:368); local dir via `haven.resdir`/`HAFEN_RESDIR` (:44) |
| Resource-code adoption | `java -cp bin/hafen.jar haven.Resource get-code <res>` + [`@FromResource`](src/haven/FromResource.java) (name+version must match — [`ResClassLoader.loadClass`](src/haven/Resource.java:1552)); `haven.Resource find-updates src` checks the pins |
| Jar-relative path resolution | [`Utils.srcpath`](src/haven/Utils.java:122) |
| Local data dir (%APPDATA%) | [`Config.localdir`](src/haven/Config.java:100) |
| Preferences (base client only) | [`Utils.getpref/setpref`](src/haven/Utils.java:408), `prefs()` (:389) |
| Audio / sfx | play: [`UI.sfx`](src/haven/UI.java:967) (→ `audio.aui.add`) → [`Audio.fromres`](src/haven/Audio.java:585); music: [`Music.play`](src/haven/Music.java:138); volume `Audio.Root.volume()`. **Per-clip volume** = wrap the `CS` in [`Audio.VolAdjust(cs, vol)`](src/haven/Audio.java:366) (`vol`/`bal` are public mutable fields, so it also adjusts a clip already playing) — `UI.sfx` takes any `Audio.CS`, so a wrapper goes in transparently |
| Chat | send: [`ChatUI.EntryChannel.send`](src/haven/ChatUI.java:814) (`wdgmsg("msg",text)`); read: `Channel.rmsgs` [:124](src/haven/ChatUI.java:124), append [:275](src/haven/ChatUI.java:275); `Message.time` (epoch sec) [:131](src/haven/ChatUI.java:131); channels `Channel` [:123](src/haven/ChatUI.java:123)/`sel` [:53](src/haven/ChatUI.java:53) |
| Combat | [`Fightview`](src/haven/Fightview.java): `lsrel` [:41](src/haven/Fightview.java:41), `current` [:47](src/haven/Fightview.java:47), `Relation.gobid/ip/oip` [:54](src/haven/Fightview.java:54)/[:57](src/haven/Fightview.java:57); deck [`Fightsess.actions`](src/haven/Fightsess.java:46); `GameUI.fv` [:48](src/haven/GameUI.java:48) |
| Buffs | [`GameUI.buffs`](src/haven/GameUI.java:71) → `children(Buff.class)`; [`Buff.res`](src/haven/Buff.java:42), [`Buff.info()`](src/haven/Buff.java:70) |
| Kin / buddy roster | [`GameUI.buddies`](src/haven/GameUI.java:58) ([`BuddyWnd`](src/haven/BuddyWnd.java:34), `Iterable<Buddy>`); [`iterator()`](src/haven/BuddyWnd.java:171) (copies under lock) / [`find(int)`](src/haven/BuddyWnd.java:177); [`Buddy.id`/`name`/`online`/`group`](src/haven/BuddyWnd.java:87) (public); palette [`BuddyWnd.gc`](src/haven/BuddyWnd.java:50); changes via `uimsg` `add`/`rm`/`chst`/`upd` ([:547](src/haven/BuddyWnd.java:547)) — `serial` skips `chst`. Mutate: `wdgmsg` `rm`/`nick`/`grp` |
| Kin ↔ gob | The buddy id is carried **on the gob**: [`res/ui/obj/buddy/Buddy.id`](src/haven/res/ui/obj/buddy/Buddy.java:17), a `GAttrib` set/cleared by [`Buddy.parse`](src/haven/res/ui/obj/buddy/Buddy.java:31) outside the gob lifecycle. **1→N**: a kin's body *and* their hearth fire are both marked. No reverse index exists — kin→gob is an `OCache` sweep |
| Player vitals (bars only) | meters via `GameUI.addchild "meter"` [:1014](src/haven/GameUI.java:1014); value [`LayerMeter.meters[i].a`](src/haven/LayerMeter.java:35); identity [`IMeter.bg`](src/haven/IMeter.java:36) — **no absolute numbers, no hunger** |
| FEP / food / hunger | [`CharWnd.battr`](src/haven/CharWnd.java:53) ([`BAttrWnd`](src/haven/BAttrWnd.java)) `feps` (`FoodMeter.cap`/`els`, `El.res`/`a`/`ev()`) + `glut` (`GlutMeter.glut`/`lbl`/`gmod`) — all public; **the one place absolute FEP/hunger numbers exist** |
| Study / curiosity | [`CharWnd.sattr`](src/haven/CharWnd.java:54) ([`SAttrWnd`](src/haven/SAttrWnd.java)) → `children(StudyInfo.class)` → [`StudyInfo.study`](src/haven/SAttrWnd.java:141) (`children(GItem.class)`) + totals `texp`/`tw`/`tenc`; per item [`resutil.Curiosity`](src/haven/resutil/Curiosity.java:36) `exp`/`mw`/`enc`/`time` (public). `time` = **total** (no countdown) |
| Skills | [`CharWnd.skill`](src/haven/CharWnd.java:55) ([`SkillWnd`](src/haven/SkillWnd.java)) → `skg.csk`/`nsk` ([`GridList.Group.items`](src/haven/GridList.java:44), swapped off-thread) → [`Skill.nm`/`res`](src/haven/SkillWnd.java:60); credos `credos.ccr`/`ncr`/`pcr`, experiences `exps.seen.items` (deferred) |
| Action bar / belt | State: [`GameUI.belt`](src/haven/GameUI.java:68) (`BeltSlot[144]`, dense) + `beltwdg` (:69, [`Belt`](src/haven/GameUI.java:171)); activate = [`Belt.act`](src/haven/GameUI.java:176) (`wdgmsg("belt", n, …)`). **Mutate**: assign = [`Belt.dropthing`](src/haven/GameUI.java:224) `wdgmsg("setbelt", n, "res", pag.res().name)` (or `"pag", pag.id` — session-local id), clear = right-click [`Belt.mousedown`](src/haven/GameUI.java:207) `wdgmsg("setbelt", n)`. The server ECHOES back: [`uimsg "setbelt"`](src/haven/GameUI.java:1367) / `"setbelt2"` (:1381) fill `belt[n]` via `loader.defer` → **the write is async and a bad res name is silently dropped** |
| Action menu (paginae) | [`GameUI.menu`](src/haven/GameUI.java:44) → [`MenuGrid`](src/haven/MenuGrid.java): [`paginae`](src/haven/MenuGrid.java:70) (`HashSet<Pagina>`, mutated on the UI thread **under its own monitor** — leaf entries only), intern table [`pmap`](src/haven/MenuGrid.java:73) (private `CacheMap`, WEAK) + [`paginafor`](src/haven/MenuGrid.java:425); [`Pagina`](src/haven/MenuGrid.java:89) `id`/`res`/`anew` (>0 = new discovery), `res()`, `parent()`, `button()`; [`PagButton`](src/haven/MenuGrid.java:154) `name()`, `act()` ([`Resource.AButton`](src/haven/Resource.java:1333): `.ad` = the action path, `.parent`), tooltip = `res.layer(`[`Resource.Pagina`](src/haven/Resource.java:1322)`).text`, `bind` ([`KeyBinding.key()`](src/haven/KeyBinding.java:89) → [`KeyMatch`](src/haven/KeyMatch.java) `.chr`/`.keyname`/`.modmatch`), [`sortkey()`](src/haven/MenuGrid.java:270), [`use(Interaction)`](src/haven/MenuGrid.java:195); layout closure [`cons`](src/haven/MenuGrid.java:445) → [`updlayout`](src/haven/MenuGrid.java:492) |
| Crafting | [`Makewindow`](src/haven/Makewindow.java:37) (`@RName("make")`), wrapped in private [`GameUI.makewnd`](src/haven/GameUI.java:52) at [`place="craft"`](src/haven/GameUI.java:977) → locate via `children(Makewindow.class)`. Public: `rcpnm`, `inputs`([`Input`](src/haven/Makewindow.java:331))/`outputs`([`SpecWidget`](src/haven/Makewindow.java:260)) → [`Spec`](src/haven/Makewindow.java:59) (`item`/`constraint` [`ResData.res`](src/haven/ResData.java:32), `num`, `opt()`), `qmod`/`tools` (`List<Indir<Resource>>`). `inputs`/`outputs`/`qmod` swapped wholesale off-thread (`inpop`/`opop`/`qmod` uimsg); `tools` **in-place** `add` (`tool` uimsg) → copy under `synchronized(ui)`. Make: `wdgmsg("make",0\|1)` |

## Options / Preferences (what OptWnd actually writes)

**Two disjoint stores.** Most settings are plain prefs — [`Utils.getpref*`/`setpref*`](src/haven/Utils.java:408)
(`java.util.prefs`, string-keyed, written immediately). Graphics settings are **not**: they live in
[`GSettings`](src/haven/GSettings.java:34), a render `State` value object.

| Setting group | Backing |
|---|---|
| Panels (read these for the authoritative write) | [`VideoPanel`](src/haven/OptWnd.java:98), [`AudioPanel`](src/haven/OptWnd.java:392), [`InterfacePanel`](src/haven/OptWnd.java:547), [`BindingPanel`](src/haven/OptWnd.java:634), [`CameraPanel`](src/haven/OptWnd.java:845) (fork) |
| Video | `GSettings` **named fields**, not constants: `lshadow` (:167), `vsync` (:173), `hz`/`bghz` (:193/:196), `rscale` (:199), `lightmode` (:221), `maxlights` (:224) |
| UI scale | pref `uiscale` (restart to take effect) |
| Placement granularity | [`MapView.plobpgran`](src/haven/MapView.java:57) / `plobagran` (:58) statics + like-named prefs |
| Camera inversion | [`MapView.invcamx`](src/haven/MapView.java:59) / `invcamy` (:60) statics + like-named prefs; consumed by `Camera.invdx`/`invdy` (:98) |
| Audio master / buffer | [`Audio.Root.volume()`](src/haven/Audio.java:614) (persists `sfxvol`), `bufsize()` (:623, **in samples** @44100 Hz, persists `audiobuf`) |
| Audio channels | [`ActAudio.Root`](src/haven/ActAudio.java:170) `.aui`/`.pos`/`.amb` → [`RootChannel.setvolume`](src/haven/ActAudio.java:131) + public `volume` field |
| Stop / is-it-playing a clip | all public, no core edit: [`RootChannel.remove(cs)`](src/haven/ActAudio.java:158) (→ `Mixer.stop`, identity match on the very `CS` you added) and [`RootChannel.mixer()`](src/haven/ActAudio.java:117) → [`Mixer.playing(cs)`](src/haven/Audio.java:125) / `size` / `current` / `clear` |

**Gotchas that cost time.**
- **`GSettings` is immutable.** `update()` ([:284](src/haven/GSettings.java:284)) returns a **new** `GSettings`;
  nothing changes until you publish it with [`UI.setgprefs`](src/haven/UI.java:99). Read via `ui.gprefs.<field>.val`.
  There are no `GSettings.SHADOWS`-style constants — the settings are instance fields with short wire names
  (`"sdw"`, `"rscale"`, `"lighting"`…).
- **`lightmode` is `simple` / `zoned`** (the `LightMode` enum), *not* "global".
- **A pref-only write is a no-op until restart** for anything mirrored in a static. `OptWnd` always writes both
  in one statement — `Utils.setprefb("invcamx", MapView.invcamx = val)` — and so must any other writer.
- **`plobagran` is a divisor, not degrees**: the panel displays `180 / plobagran`.
- **`MenuGrid.paginae` is NOT the whole menu.** It holds only the entries the server granted; the **categories
  they hang under** exist solely in the private `pmap`, reached through `Pagina.parent()`. Anything enumerating
  the menu must walk the parent closure (what `cons` does) or it gets no categories and no roots.
- **Everything on a pagina can throw `Loading`** — `res()`, `button()`, `parent()`, `act()`. Right after login the
  set is therefore *short* and fills in sub-second. Never resolve while holding the `paginae` monitor: `res.get()`
  can block on the loader. Copy under the monitor, resolve outside.
- **`PagButton.use(Interaction)` ignores `Interaction.modflags`** — it reads `ui.modflags()` live and branches
  `"act"`-by-path vs `"use"`-by-id internally (the only route to an id-only pagina). `MenuGrid.use(btn,…)` is the
  *widget's* click handler instead: for a category it flips the visible page and resets grid state.
- **A finished clip is dropped LAZILY, by the mixer thread** — [`Audio.Mixer.get`](src/haven/Audio.java:68) removes
  a `CS` the moment its `get()` returns `< 0`, and that is the *only* end-of-clip signal: there is no callback and
  no `CS.done()`. So `Mixer.playing(cs)` is how you find out, and asking is also how the list drains.
- **`UI.msg(String)` blips.** It builds an [`InfoMessage`](src/haven/UI.java:823), whose `defsfx` is `sfx/msg`, so
  every console line that prints a value plays a sound — mistake it for your own clip and you will debug a
  non-bug (`:lua` returning a value is enough; use the statement form when testing audio).
- **The keybind panel lists bindings by hand** ([`BindingPanel`](src/haven/OptWnd.java:634)) — a registered
  binding with no `addbtn` line is invisible, and keys handled by raw `ev.code` in a `globtype` override (e.g.
  the belt's 1–0 in `GameUI.NKeyBelt`) are not in the registry at all.
