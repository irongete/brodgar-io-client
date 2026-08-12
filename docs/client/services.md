# Cross-cutting client services

> Console, keybindings, resources, prefs + the Options window (`GSettings`), audio, chat, combat,
> buffs, kin, vitals, FEP, study, skills, quests, wounds, crafting, the action menu. Line numbers
> | Service | Where |
|---|---|
| Console commands (register only; no unregister) | `Console.setscmd`, `Directory`; input via `ConsoleHost`. **The `:` that opens the line is `RootWidget.globtype`** (`ev.c == ':'` → `entercmd()`, above the `gk` fallthrough), and `RootWidget.draw`  paints `cmdline` bottom-left — so the command line is a **root-level** facility, live on the login screen as well as in-world |
| Keybinding registry (remappable, persisted) | `KeyBinding.get` (id → binding, process-global), `set`, `key()`, `all()` (fork addition), `Bindable`; stored as `keybind/<id>` |
| Resource system (classpath / local dir / server) | `Resource`: `local()`, `remote()`, `FileSource`/`JarSource` (/); local dir via `haven.resdir`/`HAFEN_RESDIR` |
| Resource-code adoption | `java -cp bin/hafen.jar haven.Resource get-code <res>` + `@FromResource` (name+version must match — `ResClassLoader.loadClass`); `haven.Resource find-updates src` checks the pins. **"Which resource is this class from?"** — `Resource.classres` answers but is **not** a cheap per-object test: for a `@FromResource` copy it does `remote().loadwait(name, ver)` (**blocking**) and for an ordinary class it **throws**. The non-blocking read is `cl.getClassLoader() instanceof ``ResClassLoader`` → getres().name`, else `ResClassLoader.getsource(cl)``.name()` (the annotation, no load) — climbing **superclasses**, since resource widgets are routinely anonymous subclasses. A server widget gets its code this way whenever the type name contains `/` (`Widget.gettype3`) |
| Jar-relative path resolution | `Utils.srcpath` |
| Local data dir (%APPDATA%) | `Config.localdir` |
| Preferences (base client only) | `Utils.getpref/setpref`, `prefs()` |
| Audio / sfx | play: `UI.sfx` (→ `audio.aui.add`) → `Audio.fromres`; volume `Audio.Root.volume()`. **Per-clip volume** = wrap the `CS` in `Audio.VolAdjust(cs, vol)` (`vol`/`bal` are public mutable fields, so it also adjusts a clip already playing) — `UI.sfx` takes any `Audio.CS`, so a wrapper goes in transparently |
| Ambient audio ("the music") | `ActAudio.Ambience`, a `RenderTree.Node` carrying an `"amb"` `Audio.clip` layer; the sound is made by its static `Glob` — one per resource, a looping `Audio.Repeater` on the **`amb`** channel, volume fading with how many slots are currently in view. Published by world resources: `AudioSprite`, `StaticSprite`, `RenderLink`. **A lifetime-bound scene node, not a clip handle** |
| Music (MIDI) — **dead on this server** | `Music` is `javax.sound.midi`, entirely outside `Audio`/`ActAudio` (the Audio panel does not touch it). `Music.play` has exactly ONE caller: `RootWidget`'s `"bgm"` uimsg (`RootWidget.java`), which **this server never sends** — 132,777 cached resource files hold **0** `midi` layers vs 36 `audio` ones, all sfx. Do not build on it (see D-058) |
| Chat | send: `ChatUI.EntryChannel.send` (`wdgmsg("msg",text)`); read: `Channel.rmsgs`, append; `Message.time` (epoch sec); channels `Channel` /`sel`. **The inbound `"msg"` uimsg's arg SHAPE depends on the channel class**, not on the message name alone — three different `(Number, String)`/`(String, String)` layouts share one name: `SimpleChat.uimsg` is `(line, color?, urgency?)`, no sender at all; `MultiChat.uimsg` (and `PartyChat`, its subclass,) is `(from, line)` where **`from == null` marks the player's own line** (`MyMessage`) vs. someone else's (`NamedMessage`) — a Java `null`/Lua `nil` that is a real, common outcome, not an edge case; `PrivChat.uimsg` is `(direction, line)` where `direction` is the string `"in"`/`"out"`, never a sender id. A handler that reads args by position without checking the channel's `:type()` first will misread one of the three |
| Combat — the live fight | `Fightview`: `lsrel`  (a `LinkedList`, added/removed by the `new`/`del` uimsgs under the ui monitor), `current`  (the picked opponent, reassigned by `setcur`  off the `cur` uimsg — **`null` out of a fight**), `Relation.gobid/ip/oip`  (`ip` is YOURS, `oip` theirs — `Fightsess` paints them left/right), `Relation.invalid` set by `remove()`; deck `Fightsess.actions`; `GameUI.fv`. **The only id it publishes is the gob id** — no name, no creature data |
| Combat schools — the deck BUILDER | `FightWnd` (`@RName("fmg")`), reached via the public `CharWnd.fight` field — created hidden at login but live, so it reads without opening the tab. `acts`  (every maneuver you know) is **replaced wholesale** by the `avail` uimsg, which nevertheless **carries an existing `Action` over** via `findact(resid)`  — so the Action object is stable and the LIST is not. `Action{res, private id, a (slottable), u (slotted)}`; `order[]`  is the fixed-length hotkey LAYOUT (`null` = empty slot), its entries reassigned by the `used` uimsg; labels `FightWnd.keys` (`"1".."5"`, `"⇧1".."⇧5"` — **non-ASCII**); scalars `maxact`/`nsave`/`usesave`. Every field read is public → zero `haven` edit; the handlers run on a loader thread under `synchronized(ui)`, so copy the list/array under it and resolve resource names outside |
| Buffs | `GameUI.buffs` is a `Bufflist` (a plain `Widget`, 5 per row) — the buffs are its `children(Buff.class)` in **child order = draw order** (`GameUI.addchild "buff"`  `addchild`s them, so it is arrival order, not sorted). `Buff.res` (an `Indir` → `Loading`-throwing), `Buff.info()` (`ItemInfo` list, empty until the server's `tt`). **The same resource can be up twice** ⇒ identity is the *widget*, never the res name. **Removal is not immediate**: `Buff.reqdestroy` only sets `protected dest` and starts a 0.35 s fade `NormAnim`, calling `destroy()` at its end — so a removed buff stays a live child for ~21 frames. Read the flag via `AddonWidgets.buffDest` and treat `dest` as gone, or removals lag by that fade. **A destroyed buff stays READABLE**: `Widget.destroy` only unlinks the widget from its parent — it clears neither `res` nor the cached `info`, so a `Buff` a handler stashed keeps answering after removal (`:exists()` is a *bar-membership* scan, not a liveness flag on the widget) |
| Kin / buddy roster | `GameUI.buddies` (`BuddyWnd`, `Iterable<Buddy>`); `iterator()` (copies under lock) / `find(int)`; `Buddy.id`/`name`/`online`/`group` (public); palette `BuddyWnd.gc`; changes via `uimsg` `add`/`rm`/`chst`/`upd`  — `serial` skips `chst`. Mutate: `wdgmsg` `rm`/`nick`/`grp` |
| Kin ↔ gob | The buddy id is carried **on the gob**: `res/ui/obj/buddy/Buddy.id`, a `GAttrib` set/cleared by `Buddy.parse` outside the gob lifecycle. **1→N**: a kin's body *and* their hearth fire are both marked. No reverse index exists — kin→gob is an `OCache` sweep |
| HUD meters (the "vitals" bars) | The engine's own ordered record is the `private List<Widget> meters` field of `GameUI` (relaxed to package-private, `// addon:`) — appended at the `place == "meter"` seam, dropped in `cdestroy`; prefer it over a `children(IMeter.class)` DFS, whose order is a tree artefact. Identity = `IMeter.bg`, a **server-published** `Indir<Resource>` (`Loading` until cached; observed `gfx/hud/meter/{hp,stam,nrj,häst,mount}`, the last two only while mounted). Bar contents = the `protected List<Meter>` of `LayerMeter` — genuinely multi-segment, each `Meter` a fraction `a` (0..1) + a `Color`, replaced wholesale by the `"set"`/`"col"` uimsgs. **No absolute numbers, no hunger** |
| FEP / food / hunger | `CharWnd.battr` (`BAttrWnd`) `feps` (`FoodMeter.cap`/`els`, `El.res`/`a`/`ev()`) + `glut` (`GlutMeter.glut`/`lbl`/`gmod`) — all public; **the one place absolute FEP/hunger numbers exist** |
| Study / curiosity | `CharWnd.sattr` (`SAttrWnd`) → `children(StudyInfo.class)` → `StudyInfo.study` (`children(GItem.class)`) + totals `texp`/`tw`/`tenc`; per item `resutil.Curiosity` `exp`/`mw`/`enc`/`time` (public). `time` = **total** (no countdown) |
| Skills / credos / lore | `CharWnd.skill` (`SkillWnd`). Skills: `skg.csk`/`nsk` (`GridList.Group.items`) → `Skill.nm`/`res`/`cost`/`has` — `has` is the known/buyable flag, `nm` the server's token. Credos: `CredoGrid` `ccr`/`ncr` (`List<Credo>`), `Credo.nm`/`res`/`has`, plus the pursued one in `pcr` with `pcl`/`pclt` (level), `pcql`/`pcqlt` (quest), `pqid` (quest id) and `cost` (LP to begin one) — all public. Lore: `ExpGrid.seen.items` → `Experience.res`/`mtime`/`score`, which carries **no token** — the resource is its only identity. **Every one of these lists is replaced WHOLESALE** by its `csk`/`nsk`/`ccr`/`ncr`/`exps` uimsg, off-thread ⇒ copy before iterating, and never key anything on a record's Java identity; `pcr` is built as a **separate** `Credo` instance, so the pursued credo is not `==` its twin in `ccr`/`ncr` either |
| Action bar / belt | State: `GameUI.belt` (`BeltSlot[144]`, dense) + `beltwdg` (`Belt`); activate = `Belt.act` (`wdgmsg("belt", n, …)`). **Mutate**: assign = `Belt.dropthing` `wdgmsg("setbelt", n, "res", pag.res().name)` (or `"pag", pag.id` — session-local id), clear = right-click `Belt.mousedown` `wdgmsg("setbelt", n)`. The server ECHOES back: `uimsg "setbelt"` / `"setbelt2"`  fill `belt[n]` via `loader.defer` → **the write is async and a bad res name is silently dropped**. A slot is one of two `BeltSlot` kinds: `ResBeltSlot` (an inner class, `rdt` + a `GSprite`) or **`PagBeltSlot`** — `public static`, `new PagBeltSlot(idx, pag)`, whose whole body is `pag.button().spr()` to draw and `pag.scm.use(pag.button(), iact, false)` to fire |
| Action menu (paginae) | `GameUI.menu` → `MenuGrid`: `paginae` (`HashSet<Pagina>`, mutated on the UI thread **under its own monitor** — leaf entries only), intern table `pmap` (private `CacheMap`, WEAK) + `paginafor`; `Pagina` `id`/`res`/`anew` (>0 = new discovery), `res()`, `parent()`, `button()`; `PagButton` `name()`, `act()` (`Resource.AButton`: `.ad` = the action path, `.parent`), tooltip = `res.layer(``Resource.Pagina``).text`, `bind` (`KeyBinding.key()` → `KeyMatch` `.chr`/`.keyname`/`.modmatch`), `sortkey()`, `use(Interaction)`; layout closure `cons` → `updlayout` |
| Minimap icon registry | `GameUI.iconconf` is a `GobIcon.Settings` — the registry the "Icon settings" window edits. `public Map<Setting.ID, Setting> settings`; **the key is a PAIR**, `Setting.ID` = (`res` the icon resource name, `sub` an opaque `Object[]` decoded from the resource's own `Icon.Factory.enumerate` — `Icon.nilid` for the built-in one-icon-per-resource case). Per setting: public `show` / `notify` / `defshow` booleans and `Setting.icon.name()` (the tooltip; `Loading`-throwing). Write exactly as the checkboxes do — flip the boolean, then `dsave()` |
| Crafting | `Makewindow` (`@RName("make")`), wrapped in private `GameUI.makewnd` at `place="craft"` → locate via `children(Makewindow.class)`. Public: `rcpnm`, `inputs`(`Input`)/`outputs`(`SpecWidget`) → `Spec` (`item`/`constraint` `ResData.res`, `num`, `opt()`), `qmod`/`tools` (`List<Indir<Resource>>`). `inputs`/`outputs`/`qmod` swapped wholesale off-thread (`inpop`/`opop`/`qmod` uimsg); `tools` **in-place** `add` (`tool` uimsg) → copy under `synchronized(ui)`. Make: `wdgmsg("make",0\|1)` | **A window is per RECIPE**: `Makewindow(String rcpnm)` takes the name on the ctor and `GameUI` places a fresh one, so opening another recipe REPLACES this window rather than changing it. `inpop`/`opop` `destroy()` **every** slot widget and rebuild — including the partial form (`INT.is(args,0)`, one slot) — so nothing may key on a `Spec`/`Input` identity |
| Quest log | `CharWnd.quest` → `QuestWnd` (`@RName("quests")`), created hidden at login but LIVE, so quests read without opening it. Two `QuestList`s: `cqst` (Current: pending/disabled) and `dqst` (Completed: done/failed), each with public `quests`/`get(id)`. `Quest` = `{final id, res, title, done, mtime}` and the `"quests"` uimsg **mutates it in place then MOVES it between the two lists** — a completion is the same object — while a null res removes it from both (the only way a quest leaves). Status ints `QST_PEND/DONE/FAIL/DISABLED`  are compile-time constants → inlined, so a status helper does **not** load `Quest` (whose `<clinit>` renders text and dies headless). Objectives exist for the SELECTED quest only: `Quest.Box` (`QuestWnd.quest`, swapped by `addchild`/`cdestroy`) holds `Condition[] cond`, and its `"conds"` uimsg calls `findcond(desc)` to **carry an existing `Condition` over**, rewriting only `done`/`status` — `Condition.desc` is `final`, so the server's key for an objective is its TEXT |
| Wounds | `CharWnd.wound` → `WoundWnd` (`@RName("wounds")`), also hidden-but-live. `wounds` is a `WoundList` whose public `List<Wound>` is FLAT but held in TREE order: `treesort` recurses from `parentid == -1` writing `Wound.level` (the indent depth), and it runs on the **UI thread** in `tick` while `decwound` adds/updates/removes off-thread → copy under `synchronized(ui)`. `Wound` = `{final id, final parentid, res, level}`; `decwound` looks the record up by id and mutates it, so a wound worsening is the same object. Name and **severity** come from resource-published `ItemInfo` that streams in a beat LATER — severity is the highest-`qprio` `QuickInfo.qstr()`, a content-defined string (usually a magnitude number, **not** seconds) — which is why the event is poll-driven, not uimsg-driven |
| Equipment | `GameUI.equwnd` → private `Window` wrapping the single `Equipory` (`@RName("epry")`), reached via `children(Equipory.class)` (typically the only one open; a second appears while inspecting another gob's gear). A worn item is a **direct `GItem` child** of the `Equipory` — `addchild` wraps it in one `WItem` per slot index it fills (`wmap`), `cdestroy` tears those down. `GItem` carries `res` (an `Indir<Resource>`, set at construction) and `rawinfo`/`info()` — **derived**, rebuilt from `rawinfo` on demand and **throws a bare `Loading`** (not resolvable — `addcontinfo`/`sprite()` internals) when the resource itself is still streaming; the `"num"`/`"chres"`/`"tt"`/`"meter"` uimsgs update stack count / resource / tooltip / wear respectively |

## Options, Preferences (what OptWnd actually writes)

**Two disjoint stores.** Most settings are plain prefs — `Utils.getpref*`/`setpref*`
(`java.util.prefs`, string-keyed, written immediately). Graphics settings are **not**: they live in
`GSettings`, a render `State` value object.

| Setting group | Backing |
|---|---|
| Panels (read these for the authoritative write) | `VideoPanel`, `AudioPanel`, `InterfacePanel`, `BindingPanel`, `CameraPanel` (fork) |
| Video | `GSettings` **named fields**, not constants: `lshadow`, `vsync`, `hz`/`bghz` (/), `rscale`, `lightmode`, `maxlights` |
| UI scale | pref `uiscale` (restart to take effect) |
| Placement granularity | `MapView.plobpgran` / `plobagran`  statics + like-named prefs |
| Camera inversion | `MapView.invcamx` / `invcamy`  statics + like-named prefs; consumed by `Camera.invdx`/`invdy` |
| Audio master / buffer | `Audio.Root.volume()` (persists `sfxvol`), `bufsize()` (**in samples** @44100 Hz, persists `audiobuf`) |
| Audio channels | `ActAudio.Root` `.aui`/`.pos`/`.amb` → `RootChannel.setvolume` + public `volume` field. The three `AudioPanel` sliders map 1:1: "Interface volume"→`aui`, "In-game event volume"→`pos`, "Ambient volume"→`amb`. **There is no music slider** — `Music` is a separate MIDI player, see above |
| Stop / is-it-playing a clip | all public, no core edit: `RootChannel.remove(cs)` (→ `Mixer.stop`, identity match on the very `CS` you added) and `RootChannel.mixer()` → `Mixer.playing(cs)` / `size` / `current` / `clear` |

**Gotchas that cost time.**
- **`GSettings` is immutable.** `update()`  returns a **new** `GSettings`;
  nothing changes until you publish it with `UI.setgprefs`. Read via `ui.gprefs.<field>.val`.
  There are no `GSettings.SHADOWS`-style constants — the settings are instance fields with short wire names
  (`"sdw"`, `"rscale"`, `"lighting"`…).
- **`lightmode` is `simple` / `zoned`** (the `LightMode` enum), *not* "global".
- **A pref-only write is a no-op until restart** for anything mirrored in a static. `OptWnd` always writes both
  in one statement — `Utils.setprefb("invcamx", MapView.invcamx = val)` — and so must any other writer.
- **`plobagran` is a divisor, not degrees**: the panel displays `180 / plobagran`.
- **Nothing in `haven` reserves `belt[n]` for the server.** It is a plain array the uimsg arms assign, so a
  `PagBeltSlot` written into it from outside draws and fires like any other slot — the array is simply
  overwritten, and what was there is lost unless the writer kept it. **Six arms write it** across the two
  messages (`setbelt`: clear, res; `setbelt2`: clear, `"p"`, `"r"`, `"d"`), and **two of them defer** the write
  onto a `glob.loader.defer` task — `setbelt`-with-res and `setbelt2 "r"` — so those land *after* the message
  has been dispatched, on a Loader thread. `uimsg` itself runs on the message thread, not the UI thread.
- **A deferred belt write can land arbitrarily late, and it overwrites blind.** The gap between the message
  and its `belt[slot] = …` is a resource load, so at **login** the whole burst is dispatched around the time
  `GameUI` is built while the resource-backed slots fill in over the following moments. Anything written into
  `belt[n]` from outside in that gap is silently replaced by a task that was queued before it existed — the
  lambda captures only `slot` and `rdt` and re-reads nothing. A writer that means to keep the slot has to
  re-assert it after the write lands, and the deferred lambda is the only place that instant is observable.
- **`GameUI.menu` is not built by `GameUI`.** It is assigned in `GameUI.addchild` when the server places a
  child with `place == "menu"`, so it is `null` for some ticks after `GameUI` itself is in the widget tree —
  the same is true of every other `place`-named panel. Anything that needs the grid has to wait for the
  field, not for the HUD: the two are different instants, and nothing in `haven` announces the second.
- **`PagBeltSlot.use` goes through `MenuGrid.use(btn, iact, false)`, not `PagButton.use`** — the *widget's*
  click handler. For an entry with children that flips the grid's visible page instead of acting, which is how
  a category on the bar behaves; for a leaf it clears `anew`/`tnew` and then calls `PagButton.use`.
- **`MenuGrid.paginae` is NOT the whole menu.** It holds only the entries the server granted; the **categories
  they hang under** exist solely in the private `pmap`, reached through `Pagina.parent()`. Anything enumerating
  the menu must walk the parent closure (what `cons` does) or it gets no categories and no roots.
- **Everything on a pagina can throw `Loading`** — `res()`, `button()`, `parent()`, `act()`. Right after login the
  set is therefore *short* and fills in sub-second. Never resolve while holding the `paginae` monitor: `res.get()`
  can block on the loader. Copy under the monitor, resolve outside.
- **A `Pagina` can be SUBCLASSED into the grid, and `MenuGrid` needs no edit for it.** The engine reaches
  everything about an entry through two virtuals — `Pagina.button()` and the `PagButton` it returns — so a
  subclass pair is the whole seam: `cons` walks `paginae` plus the `parent()` closure, `updlayout` sorts on
  `PagButton.sortkey()`, `draw` reclips to `spr().sz()` (device pixels; the cell interior is
  `Inventory.sqsz.sub(1,1)`), and `MenuGrid.use` decides "category" by `cons(pag, sub).size() > 0`. Two
  traps: `Pagina.button` is **private**, so the `next`/`bk` trick of pre-assigning it from an initialiser is
  unavailable — override `button()` and cache in the subclass; and `Resource`'s constructor is **private and
  pool-managed**, so a synthetic entry has to pass an existing resource as a stand-in and nothing may key on
  its `res()`/`PagButton.res`.
- **`PagButton(Pagina)` calls the virtual `binding()` from its own constructor** — after `pag` and `res`,
  before any subclass field exists, so an override must read `pag` and nothing else (no warning). The stock
  `binding()` reaches `hotkey()` → `act()` → `res.flayer(Resource.action)`, which **throws for a resource
  with no action layer** (`gfx/hud/sc-next` has neither an `action` nor a `pagina` layer), so a stand-in
  entry must override it — `KeyBinding.get(id, KeyMatch.nil)` also gives it an unbound, remappable id
- **`PagButton.parent()` MEMOISES** the `paginafor(act().parent)` it derived, into a private field, and
  `Pagina.parent()` is just `button().parent()` — so a parent that is not fixed by the resource has to be
  answered by an override on both, from state read live, or the first call freezes the tree.
- **`cons` reaches a category through `parent()` alone** and never asks whether that parent is still in
  `paginae`. A parent taken out of the set is therefore still emitted onto whatever screen *its* parent names:
  removing a category without re-rooting its children draws the removed category itself back on the root
  screen. Its BFS does tolerate a **cycle** (the `close`/`open` sets absorb one), but the `anew`/`tnew` walk at
  the head of the same method is an unguarded `for(p; p != null; p = p.parent())` — one new discovery whose
  chain leads into a cycle hangs the client.
- **Relayout has one public door.** `updlayout()` and the `recons` flag are private; `MenuGrid.change(cur)`
  is what rebuilds `curbtns` and `layout` after `paginae` is mutated outside a `"fill"` uimsg. It resets
  `curoff`, so a change made while the player is on page 2 of a category puts them back on page 1
- **A pagina's tooltip is COMPOSED, not a string.** `PagButton.rendertt(withpg)` renders `name()` (plus the
  bound key) and, for the long form, appends `ItemInfo.longtip(info())` — where `info()` is
  `ItemInfo.buildinfo(this, pag.rawinfo)` plus an `ItemInfo.Pagina` built from `res.layer(Resource.pagina).text`.
  So an entry over a stand-in resource, which has no `pagina` layer, has to override `info()` to say anything
  at all below its name. `rendertt` **deletes from the list `info()` hands it** (`removeIf` on `ItemInfo.Name`),
  so an override must return a fresh list rather than a cached one. `MenuGrid.tooltip` then caches the rendered
  `Tex` per hovered button (`curttp`), and text that changes while the pointer is already on the button
  re-renders on the next hover.
- **`PagButton.use(Interaction)` ignores `Interaction.modflags`** — it reads `ui.modflags()` live and branches
  `"act"`-by-path vs `"use"`-by-id internally (the only route to an id-only pagina). `MenuGrid.use(btn,…)` is the
  *widget's* click handler instead: for a category it flips the visible page and resets grid state.
- **A finished clip is dropped LAZILY, by the mixer thread** — `Audio.Mixer.get` removes
  a `CS` the moment its `get()` returns `< 0`, and that is the *only* end-of-clip signal: there is no callback and
  no `CS.done()`. So `Mixer.playing(cs)` is how you find out, and asking is also how the list drains.
- **`UI.msg(String)` blips.** It builds an `InfoMessage`, whose `defsfx` is `sfx/msg`, so
  every console line that prints a value plays a sound — mistake it for your own clip and you will debug a
  non-bug (`:lua` returning a value is enough; use the statement form when testing audio).
- **The keybind panel lists bindings by hand** (`BindingPanel`) — a registered
  binding with no `addbtn` line is invisible, and keys handled by raw `ev.code` in a `globtype` override (e.g.
  the belt's 1–0 in `GameUI.NKeyBelt`) are not in the registry at all.
- **`GobIcon.Settings.settings` is SWAPPED WHOLESALE by the loader thread** (`Loader.run` builds `nset` and assigns it), minting **fresh `Setting` objects** as icon resources resolve. So a local reference to the map is a stable snapshot to iterate — but anything keyed on a `Setting`'s Java identity goes stale the first time a new icon arrives, writing to an orphan. Key on `Setting.ID.res`.
- **`dsave()` is debounced through `Defer`** : it returns immediately and `save()` runs on another thread, coalescing a second request into `saveagain`. `save()` itself no-ops when `ResCache.global == null`, which is what makes a headless subclass-the-sink test possible.
- **The registry is EMPTY until the HUD is up and grows as new icon types are seen** — the server pushes them via `Settings.receive`, whose two arms (one resource, or an `Object[]` of them) both build a `Setting` with `nilid`; the `sub` variants only appear from a stored config's `"sub"` list.
