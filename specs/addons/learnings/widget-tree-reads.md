# Learnings — Widget-tree reads (adapters)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Reaching `GameUI` from the addon layer:** `view.getparent(GameUI.class)` (MapView is a direct
  child of GameUI, added at `GameUI.addchild` :914). `GameUI.chrid`/`genus` are `public final String`;
  `chrid` **is** the local character name (in-game it read `"Irongete W16.1"`; backs `hafen.player.name`).
  `gui()` uses a **fast path (`getparent`) + fallback (DFS from `ui.root`)** — widget tree walk
  `for(Widget c = w.child; c != null; c = c.next)` (`child`/`next`/`parent` public). This robust reach
  backs all of 1c-3 (items/char/party). **NB:** at the *very first* enter-world tick neither reach
  finds GameUI — see below.
- **Widget-tree reads (1d) are the first NON-zero-edit surface** (B5 predicted this). Two core edits,
  both minimal + `// addon:`: (1) the **inbound-`uimsg` tap** and (2) a **`haven`-package accessor**.
- **`UI.uimsg(id,msg,args)` does NOT apply the message** — it `submitcmd`s a `UiMessage` Command that
  runs later on a **Loader thread** under `synchronized(ui)` and calls `dispatch(wdg, MessageEvent)`.
  So for a **post-apply** semantic tap, hook inside `UiMessage.run()` **after** the `dispatch(...)`
  block (widget state is fresh there), NOT at `UI.uimsg` (that's pre-apply — the future `hook.message`
  pre-hook seam). The tap runs off the UI thread → **only enqueue** (mark adapter dirty), never call
  Lua; the tick drains + fires on the UI thread (same pattern as the OCache gob queue).
- **Reaching widget-tree values without reflection in Lua:** put a tiny **package-`haven` accessor**
  class (`AddonWidgets`, the `SpeakerIcon` trick) next to the widgets. `protected`/package fields ARE
  accessible from package `haven` (e.g. `LayerMeter.meters` is `protected` → readable there), so no
  `setAccessible` reflection is needed for same-package fields — expose them as `public static` getters.
  This localizes the fragile read to ONE file (adapters call it), and Lua never touches reflection (D-017).
- **Vitals = `IMeter` bar fractions only** (0..1), NO absolute numbers, NO hunger (they don't exist as
  client state — B5). Locate via `gui().children(IMeter.class)` (public recursive DFS, like Equipory);
  value = first `LayerMeter.Meter.a` (via `AddonWidgets.meters`). `IMeter extends LayerMeter`; `IMeter.bg`
  (public `Indir<Resource>`) is the per-bar identity but the **bg names are server-published** (not in
  the client source), so map the three bars **positionally** in creation order (hp, stamina, energy) —
  `children(Class)` iterates tree/creation order. Update signal: `LayerMeter.uimsg("set")` (synchronous).
- **`IMeter` initial values:** the `im` factory decodes `decmeters(args,1)` from creation args, BUT
  in-game the three bars actually **streamed in as individual `set` uimsgs** a beat after their meters
  were created (observed: `VitalsChanged` fired once per bar, hp→stamina→energy, because a key
  appearing counts as a change) — so on this server the event DID cover initial population. Still, the
  robust pattern for any widget-tree tracker is: read the value once on a post-enter-world timer for
  the initial snapshot, then let the inbound-`uimsg` event stream updates. (Meters/CharWnd/inventory
  all **stream in a beat after enter-world** — the same race as 1c-2/1c-3 data; read on a timer, not
  synchronously in the handler.)
- **`TreeAdapter` mechanism shape:** `interested(Widget,msg)` (off-thread, cheap `instanceof`, marks
  dirty) + `refresh()` (UI thread, re-read snapshot + fire the semantic event). Engine holds a
  session-scoped `List<TreeAdapter>` + a `ConcurrentHashMap.newKeySet()` dirty set (both reset in
  `init()`), drained each tick. Change-detection (`vitalsEqual`): a null/nil cache ⇒ "changed" (first
  populated read fires), a key appearing/disappearing ⇒ changed, else compare the numeric fields — so
  colour/tooltip-only uimsgs are suppressed. Adding an adapter = one class + one `treeAdapters.add`.
- **The `TreeAdapter` now has TWO update paths (1d-2):** the 1d-1 uimsg path (`interested`→dirty→
  `refresh`) for server-pushed uimsgs, PLUS a per-tick **`poll()`** (a `default {}` no-op on the
  interface, so old adapters are untouched) for structural changes no uimsg announces. `tick()` calls
  `refreshTreeAdapters()` then `pollTreeAdapters()` — **refresh before poll** so a brand-new buff (in
  the tree + its `"tt"` already applied) emits a single `BuffAdded` in poll, not `BuffChanged`(from an
  empty cache)-then-`BuffAdded`. An adapter uses whichever path fits: `VitalsAdapter`/`FepAdapter` =
  uimsg-only; `BuffsAdapter` = poll for add/remove + uimsg for content. Adapter caches now live **in
  the adapter instance** (reset by re-instantiation in `init()`), not a static — cleaner than the 1d-1
  static `vitalsCache`.
- **Buffs (1d-2) — mostly zero-edit, all public:** `GameUI.buffs` (public `Bufflist`) →
  `children(Buff.class)` (public recursive DFS, **creation order**). `Buff.res` (public `Indir<Resource>`,
  `.get().name` — Loading), name from `res.get().layer(Resource.tooltip).t` (nullable `layer`, not
  `flayer`) with an `ItemInfo.find(ItemInfo.Name.class, Buff.info())` fallback. `amount`/`cooldown`/
  `number` = `ItemInfo.find` over `Buff.info()` for `Buff.AMeterInfo` (`.ameter()` 0..1) / `GItem.MeterInfo`
  (`.meter()` 0..1) / `GItem.NumberInfo` (`.itemnum()` int) — all public interfaces; often nil; **NOT
  time-varying** (info-cached, rebuilt only on `"tt"`), so per-tick snapshotting doesn't spam `BuffChanged`.
  `Buff.info()` throws Loading and is `emptyList()` until the first `"tt"`. **`Buff.dest` (protected)** is
  the only non-public bit → `AddonWidgets.buffDest` (a `dest` buff is fading out post-removal → exclude it).
- **FEP/food (1d-2) — 100% public, zero haven-edit:** locate `BAttrWnd` via the **public `CharWnd.battr`
  field** (set in `CharWnd.addchild` when the `@RName("battr")` child lands — NO tree-walk / `children`
  needed, though `gui().children(BAttrWnd.class)` also works). `BAttrWnd.feps` (`FoodMeter`) + `glut`
  (`GlutMeter`) are public finals; `FoodMeter.cap`/`els` (List<El>), `El.res`/`a`/`ev()`, `Event.nm`/`col`/`sort`,
  `GlutMeter.glut`/`lglut`/`gmod`/`lbl` are ALL public. `feps.update`/`glut.update` run on a Loader thread
  (uimsg) but only build a new list / set scalars; `els` is swapped in `FoodMeter.tick` on the UI thread —
  copy `els` (`new ArrayList<>(fm.els)`) before iterating for defensiveness. `hunger.level` = `glut` (the
  integer part is the hunger *level*, the fraction is progress within it); `label` = `lbl`; `efficacy` = `gmod`.
- **`msg` interning — don't rely on it:** the client compares uimsg names with `==` (interned), but from
  the addon layer compare with `.equals()` (`"food".equals(msg)`, `"ch".equals(msg)`) to be safe.
- **The 1d-1 inbound-uimsg tap now feeds THREE adapters** with zero further `UI.java` change — the single
  seam scales (B5's prediction holds). `AddonWidgets` is the one growing accessor file (`meters` +
  `buffDest`); each new protected/private widget field = one method there, never reflection in Lua (D-017).
- **1d-3 is the first ZERO-`haven`-edit widget-tree slice** — study + skills are 100% public reads, so
  neither the `UI.java` tap nor `AddonWidgets` grew (they weren't even needed): `CharWnd.sattr`
  (`SAttrWnd`) / `CharWnd.skill` (`SkillWnd`) are **public fields** set in `CharWnd.addchild` (like
  `battr` in 1d-2), null until the tab streams in a beat after enter-world. `SAttrWnd`/`SkillWnd` are
  built via a **`CharWnd.TabProxy`** (`@RName("sattr"/"skill"/"credo"/"expls")` create a proxy that
  lazily constructs the real tab and hands it to `CharWnd.addchild`) — so **locate via the public
  `CharWnd` field, never the `@RName`** (the RName widget is the proxy, not the window).
- **Study (1d-3):** the study window is `SAttrWnd` (a `CharWnd` tab, `chr.sattr`). The study inventory
  is not a named field — reach it via **`sattr.children(SAttrWnd.StudyInfo.class)` → `StudyInfo.study`**
  (public `Widget`); `StudyInfo` is created 1:1 with the study inventory in `SAttrWnd.addchild(child,
  "study")` and also carries the **live totals `texp`/`tw`/`tenc`** (public, recomputed every tick in
  `StudyInfo.tick`→`upd` = total LP / attention(mental weight) / exp-cost) → backs `study.summary()`
  for free. The study inventory holds **`GItem` children DIRECTLY** (NOT `WItem`-wrapped like the main
  inventory) — mirror `StudyInfo.upd`: `study.children(GItem.class)`.
- **`resutil.Curiosity`** is the study-profile `ItemInfo` on each curiosity item (`ItemInfo.find(
  Curiosity.class, gitem.info())`, Loading-guarded): public `exp` (learning points), `mw` (mental
  weight = **attention**), `enc` (experience cost), `time` (**TOTAL** study time, seconds). **There is
  NO per-item "time left" in the client** (only the total) — don't fabricate one (same honesty rule as
  the non-existent seconds buff timers). `GItem.meter` (0..100) is exposed as best-effort `progress`.
- **`StudyChanged` is poll-driven, like buffs** — a curiosity being placed/finished is a widget
  create/`cdestroy` on the study inventory (NOT a uimsg) and its `Curiosity` info resolves a beat after
  the item appears, so `StudyAdapter` overrides only `poll()` (diff `slots()` each tick via
  `studySlotsEqual`, a positional array compare reusing `luaFieldEq`); `interested()` returns false and
  `refresh()` is a no-op. A null cache ⇒ "changed", so the first populated (or first empty) read fires
  once. Order is significant in the diff (study items don't reorder in practice).
- **Skills (1d-3):** `chr.skill` (`SkillWnd`) → `skg` (`SkillGrid extends GridList<Skill>`) → `csk`
  (**known**) / `nsk` (**available**) are `GridList.Group`; `Group.items` is a **public `List<Skill>`
  swapped wholesale off-thread** by the `csk`/`nsk` uimsgs (like `Party.memb`) → **copy before
  iterating** (`new ArrayList<>(...)`), snapshot-safe. `Skill.nm` = the internal token (used for
  `wdgmsg("buy", nm)`), `Skill.res` = `Indir<Resource>` → display name from the resource tooltip
  (fallback to `nm`). `char.skills()` returns known only; `skill(name)` is a substring test (name|res)
  like `buffs.has`. No `SkillsChanged` (skills change only on buy — read on demand). Credos (`SkillWnd.
  credos`: `ccr`/`ncr`/`pcr` + pursuit `pcl/pclt`,`pcql/pcqlt`) and experiences (`exps.seen.items`) are
  public too but deferred.
- **Study in-game finding (1d-3):** a study slot can hold an item with **no `Curiosity` info** — e.g.
  "A Bond of Blood & Soil" (a Hearth-Magic bond). `ItemInfo.find(Curiosity.class, …)` returns null for
  those, so their snapshot is `{res,name}` only and they add 0 to `summary()` (which matched exactly the
  one real curiosity: `lp=25567 att=8 cost=1`). Confirms the Loading/optional-field design is right —
  never fabricate. `GItem.meter` was 0 for all study items (so `progress` was absent) — best-effort, as
  expected. `StudyChanged` streamed `0→1→3` as the widgets + Curiosity infos resolved after enter-world.
- **The action bar is the engine's "belt" — API renamed to `hafen.actionbar` (D-013 one canonical
  name):** H&H calls its hotbar the "belt" (`GameUI.belt`, `BeltSlot`, `setbelt`/`setbelt2`,
  `FKeyBelt`/`NKeyBelt`, `:belt`, pref `belttype`; the bar bg texture is even `gfx/hud/hb-main` =
  "hotbar main"). That collides with the worn-belt *equipment*, so after the maintainer flagged it the
  addon-facing API was renamed **`belt` → `actionbar`** everywhere (namespace `hafen.actionbar`, event
  `ActionbarChanged`, helpers `actionbar*`, adapter `ActionbarAdapter`, doc `phase-1d4-actionbar-equip`).
  **Engine names stay `belt`** (`GameUI.belt`/`setbelt`/`BeltSlot`…) — we don't touch `haven`; the
  adapter/helpers are `actionbar*` but read `belt[]`, bridged by a one-line comment. Rule for future
  gap subsystems: **expose the game's *concept* under a clear name, keep the engine's own identifiers in
  the Java that reads them.**
- **Action bar (1d-4) — poll, NOT the uimsg tap, because the write is deferred:** setting/clearing/
  dragging a slot is a `GameUI` `setbelt`/`setbelt2` uimsg, BUT for the resource/pagina cases the client
  does `belt[slot] = …` inside a **`ui.sess.glob.loader.defer(...)`** task that runs *after* the uimsg
  dispatch (`GameUI.uimsg` :1367/:1381). So the 1d-1 inbound-uimsg tap fires while `belt[slot]` is still
  the OLD value → a refresh-on-uimsg reads stale. Fix: `ActionbarAdapter` is **poll-driven** (diff the
  144 slots each tick), like buffs/study; `interested()` returns false. (Clears and `setbelt2 "p"`/`"d"`
  are synchronous, but the common cases aren't — so never trust the uimsg alone here.) The generalisable
  rule: **if a widget mutation is `loader.defer`-red, use poll(), not the uimsg tap.**
- **Action-bar reads are all public (zero `haven` edit):** `GameUI.belt` (`public BeltSlot[144]`,
  field-init so never null — elements are null). Two concrete slots: `ResBeltSlot` (`public getres()` →
  `rdt.res.get()`; a raw item/resource, no meter) and `PagBeltSlot` (`public pag`; a menu action). For a
  PagBeltSlot: `pag.res()` (icon), `pag.button().name()` (= `act().name`, the display name), and
  `pag.button().meter.get()` (the `cooldown`, an `AttrCache<Double>` from `GItem.MeterInfo`, 0..1). All
  can throw `Loading` via `res.get()`/`flayer` → try/catch each (though **`AttrCache.get` itself catches
  `Loading` and returns null**, `ItemInfo.java:438`). `ResBeltSlot` is a non-static inner class but
  `instanceof`/cast/`getres()` work fine cross-package (all public).
- **`cooldown` is a live meter → EXCLUDE it from `ActionbarChanged` change-detection** (`actionbarEqual`
  compares `res`+`name` only), else a cooling-down ability fires an event every frame. Same reasoning
  drops `wear` from `equipEqual` (`slot`/`res`/`name`/`num` only) — durability drift isn't an equip
  change. The field is still readable live via `hafen.actionbar.slot(n).cooldown` /
  `hafen.items.equipment()`.
- **Action-bar indexing = raw 0-based game index (0..143), NOT 1-based Lua.** The client packs pages as
  `page*12 + i` (12 slots × 12 pages; in-game slot 0 = F1/page 0, slots 120/121 = page 10) and
  `actionbar.use` (Phase 4) will send `wdgmsg("belt", n, …)` with that same n — so read and use share one
  index (D-013 "one canonical way"). The `"partyN"` token is 1-based but that's a naming *ordinal*, not
  an array index into a live game structure — different thing, don't conflate.
- **`EquipChanged` is poll-driven too** — equip/unequip is a `WItem` create/`cdestroy` under `Equipory`
  (not a uimsg). `EquipAdapter` re-reads the equipment snapshot each tick and fires with the **whole
  array** (positional diff, `wear` excluded). Refactored the 1c-3 `equipment` facade body into a shared
  `readEquipment(Equipory)` so the facade and the adapter can't drift. Positional compare is safe: the
  `Equipory` child order is creation-order and stable between equips (same assumption as study).
- **Poll-based streaming fires per-slot at login (expected, not a bug):** action bar / equipment stream
  in a beat after enter-world (like all HUD state), so each slot/item surfacing fires one
  `ActionbarChanged`/`EquipChanged` — consistent with buffs/study/vitals streaming. `hello` reads at now
  (empty) + `+3s` (populated) and throttles the event logs. **Phase 1d is now complete** (all
  widget-tree read surfaces: vitals, buffs, FEP/food, study, skills, action bar, equipment).
- **(025.1) A removed buff is still a child for 0.35 s — `Buff.dest` is the only "gone" signal.**
  `Buff.reqdestroy` does not destroy: it sets the `protected dest` flag and starts a 0.35 s fade
  `NormAnim` that calls `destroy()` at the end (`src/haven/Buff.java:190`). So `Bufflist.children(Buff.class)`
  keeps handing out the buff for ~21 frames after the server removed it. Every scan filters on
  `AddonWidgets.buffDest(b)` — the one `// addon:` accessor for that non-public flag — and there is
  exactly ONE scan (`LuaBuff.actives()`) shared by `hafen.buff()` and the `BuffsAdapter` poll, so the
  list form and the events can never disagree about what is up. `:exists()` is that same predicate,
  which is why a Buff handed to a `BuffRemoved` handler still answers `:res()/:name()` (the widget object
  is merely unlinked) but says `:exists()` → false.
- **(025.1) Buff identity is the WIDGET, never the resource name.** The same res can be up twice
  (two stacks of the same debuff are two `Buff` children), and `Bufflist` child order is arrival order
  from `GameUI.addchild "buff"` — not sorted, not stable across a re-add. So the intern cache is an
  `IdentityHashMap<Buff, Ref>` (weak values + `ReferenceQueue`, drained on every access), unlike
  `LuaSound`'s name-keyed one. The needle lookup `hafen.buff("poison")` is therefore a *search
  convenience* over that array (first match wins), not an address — the same shape/role split as
  `hafen.menugrid(displayName)`.
- **(025.2) When an adapter's payload becomes an object, keep the snapshot as the diff KEY.** The
  `BuffsAdapter` still holds `IdentityHashMap<Buff, LuaValue>` of `LuaBuff.snapshot(b)` and still
  diffs it with `buffEqual` — that value-comparable form is exactly what keeps `BuffChanged` from
  firing every tick. What changed is only that the snapshot is never handed to Lua any more: the
  three events fire `AddonManager.fireBuff(event, b)` (the widget), and `buff:info()` is the
  snapshot on demand. Detection and payload are two different jobs; converting an adapter to OOP
  means replacing the second, not the first. The same split applies to any future poll adapter —
  an object cache is NOT a substitute for a value cache, because objects are interned per addon and
  compare by identity, so they can never tell you that *content* changed.
- **(025.2) Fire `*Removed` before dropping the map entry.** `BuffRemoved` needs nothing from the
  cache (the `Buff` widget is unlinked, not cleared, so it reads fine after `it.remove()`), but
  firing first keeps the payload literally "the entry the adapter is dropping" and leaves no window
  where a handler re-entering the API would see a map the event has not been announced from.
