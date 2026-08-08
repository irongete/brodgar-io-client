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
  `flayer`) with an `ItemInfo.find(ItemInfo.Name.class, Buff.info())` fallback. `amount`/`duration`/
  `number` (the API names since 025.3 — on a buff the radial meter is the run that is LEFT, so only the
  action bar calls the identical `GItem.MeterInfo` a cooldown) = `ItemInfo.find` over `Buff.info()` for
  `Buff.AMeterInfo` (`.ameter()` 0..1) / `GItem.MeterInfo` (`.meter()` 0..1) / `GItem.NumberInfo` (`.itemnum()` int) — all public interfaces; often nil; **NOT
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
  (two stacks of the same buff are two `Buff` children), and `Bufflist` child order is arrival order
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
- **(027.1) A positional read of the HUD meters was silently lossy — the bars are a LIST, not a
  triple.** The retired `VITAL_KEYS = {hp, stamina, energy}` zipped the first three `IMeter`s against
  three fixed names. Mount a horse and the server adds two more bars (`gfx/hud/meter/häst`, `.../mount`);
  the old surface could not see them at all, and any server that reorders or inserts a bar would have
  mislabelled the lot. `hafen.meter()` is the whole list in HUD order and `hafen.meter(needle)` searches
  the **server-published** `IMeter.bg` resource name, so the client ships no alias dictionary. Two
  consequences for the docs: the name list is *observed on this server today*, not contract
  (`027-meters-oop/observed-res-names.md`), and one of the real names is non-ASCII — advise an ASCII
  substring (`"st"`, `"mount"`) over an accented Lua string literal.
- **(027.1) Use `GameUI.meters`, not a `children(IMeter.class)` DFS.** The engine keeps its own ordered
  list of what sits in the `place == "meter"` HUD slot (appended at `GameUI.java:1014`, removed in
  `cdestroy` at `:1151`). A DFS would walk the whole HUD in tree order — an artefact, not layout order —
  and would also collect any `IMeter` living somewhere else. The field was `private`, so it cost ONE
  `// addon:` core edit (`private` → package-private; same-package `AddonWidgets` cannot reach a private
  field), and `AddonWidgets.hudMeters(GameUI)` filters the `List<Widget>` to `IMeter` rather than casting.
  That one predicate is the single scan behind `hafen.meter()`, the needle lookup, `:index()`, `:exists()`
  and (027.2) the adapter poll — the `LuaBuff.actives()` shape, so they can never disagree.
- **(027.1) A meter's colour is state, not decoration.** `LayerMeter` takes a `"col"` uimsg alongside
  `"set"`, so the server recolours a bar on its own (and the type is genuinely multi-segment — the old
  single-fraction snapshot threw the rest away). Hence `:color()` and `:segments()` are first-class reads
  and the 027.2 adapter must diff colour too, or a pure recolour would never fire `MeterChanged`.
  Colours go out as `AddonManager.color` → `{r,g,b,a}` 0..255, the same shape `kin:color()` already uses.
- **(027.1) Every meter read is `Loading`-guarded and may answer nil.** `IMeter.bg.get()` throws until
  the resource is cached, so a brand-new meter is routinely nameless for a beat and its segments stream
  in after it appears — `:res()`/`:value()`/`:color()` answer `nil` rather than erroring, and only
  `:exists()` always answers. Same reason `Widget.destroy` unlinks without clearing `bg` or the segment
  list, so a removed meter keeps reading with `:exists()` false (the `LuaBuff` property).
- **(027.2) The whole change key is the segment array — one comparison covers both `uimsg`s.**
  `LuaMeter.segments(m)` is what `MeterAdapter` caches and diffs, not `snapshot()`: `:value()` and
  `:color()` ARE the first segment, so the same array comparison catches a `"set"` and a `"col"`
  push, and a multi-segment bar whose later segments move is a real change too. `:res()` is
  deliberately out of the key (a resource leaving `Loading` renames nothing — it would fire a
  spurious `MeterChanged` a beat after every add) and so is `:index()` (layout, not state).
  Verified in-game: standing still is silent, moving fires on `stam`/`nrj` only, one line per
  server push.
- **(027.2) Engine order is refresh→poll, so a brand-new meter surfaces as ONE `MeterAdded`.**
  `CharApi` runs the uimsg-driven refresh before the per-tick poll, and the poll seeds the cache
  with the meter's CURRENT segments at the moment it fires `MeterAdded`. A bar that arrives and is
  filled in the same tick therefore never produces `MeterChanged`-then-`MeterAdded` (the payload
  already carries the values); the seed-on-add is what buys that, not the ordering alone.
- **(027.2) An event payload can be younger than its resource — `:res()` may be nil AT FIRE TIME.**
  Mounting fires `MeterAdded` for two bars and the first one's `bg` is still `Loading` when the
  event goes out, so a handler printing `tostring(m)` gets `Meter(?)` and `m:res()` is `nil` — then
  the SAME object answers a beat later, because the handle re-reads the widget on every call
  (027.1's `Loading` guard). This is the expected behaviour of a handle-not-snapshot API and must
  be a docs line: name-match a meter on a later tick (or in `MeterChanged`), never inside the
  `MeterAdded` handler.
- **(039.11) `SkillWnd` swaps every one of its lists WHOLESALE, so nothing in that window can be interned on
  Java identity.** `csk`/`nsk`/`ccr`/`ncr`/`exps` each replace `Group.items` (or the `List` field) entirely
  on their uimsg, so a `Skill`/`Credo`/`Experience` record is a fresh object after any resend even when
  nothing about that skill changed. The defensive `new ArrayList<>(…)` copies the old readers made were
  already an acknowledgement of this; interning made it a key decision (D-132) — token for skills and credos,
  resource name for lore, which has no token. The same window's `CredoGrid.pcr` is built by its own `pcr`
  uimsg as a **separate** `Credo` instance, so "the credo I am pursuing" is not `==` any member of `ccr`/`ncr`
  at the Java level either; resolving both through the token is what makes it one object in Lua.
- **(042.2) A widget that FADES needs its own tap at the "gone" moment — the removal seam (M1) fires for it
  too, but 0.35s late.** `Buff.reqdestroy()` (and `Window.reqdestroy()`, same shape) does not unlink: it sets
  a protected `dest`/`animst="dest"` flag and starts an animation, so the widget is still a live tree child
  for the whole fade. There is no `uimsg` at the moment the flag flips either — the server's widget-destroy is
  a distinct wire command (`UI.DstWidget` → `UI.destroy(Widget)` → `removeid(wdg); wdg.reqdestroy();`), never
  routed through `Widget.uimsg`. The fix that cost the least invasiveness: call the SAME
  `AddonManager.onWidgetRemoved(Widget)` hub method M1 already uses, from a second call site right where the
  fade flag is set, rather than inventing a new hub method/queue. The consuming adapter (not the seam) is what
  makes this safe: guard `removed(w)` behind a cache-membership check, so M1's real (late) firing for the same
  widget, 0.35s afterwards, is a no-op instead of a second event. **Any future adapter over a fading widget
  needs both halves — the early tap AND the idempotency guard — or it either fires late (skip the tap) or
  fires twice (skip the guard).**
- **(042.5) `Resolve`'s `UnwaitableEvent` refusal is EXPECTED and ROUTINE for any `GItem`/`SpriteOwner` info
  read -- silence it, don't log it per occurrence.** The built-in `"defn"` name factory
  (`ItemInfo.Default.get`, [ItemInfo.java:242-259](../../../src/haven/ItemInfo.java:242)) checks `owner
  instanceof SpriteOwner` and calls `sprite()` BEFORE falling back to the resource's static tooltip name;
  `GItem implements SpriteOwner` and its `sprite()` (NOT `spr()`, the other accessor) throws a BARE,
  non-waitable `Loading` whenever `spr == null` -- the normal state for a frame or two after equip/`"chres"`
  resets it. So `Resolve.on(...)` wrapping `it.info()` on any `GItem` (EquipAdapter, and any future item
  adapter) hits this on every fast equip/unequip -- not a resource-loading race, just the sprite genuinely not
  built yet. Traced via a temporary "thrown at: <site>" stack-trace tag on `Resolve`'s refusal catch
  (reverted after use -- the established learnings-file diagnostic pattern). `GItem.spr()` retries silently
  forever with no notify of its own, so `Resolve` can never usefully wait on this specific `Loading` -- logging
  the refusal every time is noise, not information, past the first occurrence. Fixed in `Resolve.register`:
  the `UnwaitableEvent` catch is now silent (no log at all); the rarer `MAX_RETRIES` give-up (a `Loading` that
  keeps re-throwing across REAL resource loads, so more likely to mean something) still logs via the new
  `AddonManager.logDiag` -- stdout only, never the in-game chat `AddonManager.log` also posts to.
- **(042.6) SUPERSEDES the "Action bar (1d-4)" entry above: "if a widget mutation is `loader.defer`-red, use
  `poll()`, not the uimsg tap" is retired. The rule is now "put the notify where the write lands" (D-178).**
  `ActionbarAdapter.poll()` is gone. Three of the five `setbelt`/`setbelt2` paths write `belt[slot]`
  synchronously, so the existing uimsg tap already sees the new value on `refresh()` (a full 144-slot diff,
  since the tap hands over only `(widget, msg)`, never the args, so which index changed is unknown until the
  diff runs -- but it now runs only when a message arrives, not every tick). The other two (resource/pagina)
  still write from a `glob.loader.defer` task that lands AFTER the tap fires -- the trap the retired rule was
  reacting to -- but the fix is not to poll around it: a one-line core edit,
  `AddonManager.onBeltSet(slot)`, sits right after `belt[slot] = ...` inside each of the two lambdas (`src/haven/GameUI.java`), which is the only place the change actually happens. The Loader thread only enqueues (P5); `AddonManager.tick`
  drains it and re-diffs just that one slot (`ActionbarAdapter.beltSet`), sharing the same `checkSlot` the
  uimsg-driven refresh uses, so the two paths can never disagree about what "changed" means. The
  generalisable rule a deferred write should prompt from now on: find where the write actually lands and put
  the notify there, never fall back to a poll to route around a race.
- **(042.7) A container's presentation `WItem` never reaches `onWidgetPlaced` — only its `GItem` does, and
  checking the wrong one produces a bug that looks like a random race.** `Inventory.addchild(child, args)`
  places the `GItem` (the widget the SERVER actually created) and then, in the SAME call, mints a `WItem`
  wrapper and `add()`s it directly in Java — a client-side add, which per this feature's own hazard list
  never routes through `Widget.add`'s placement seam. A `widget:on("ItemAdded", fn)` implementation that
  watches only for `w instanceof WItem` on placement therefore never fires for a genuinely new item (one
  picked up off the ground): it "worked" only by accident, when some UNRELATED sibling item's own `WItem`
  happened to create/destroy nearby (a grid reflow) and incidentally triggered a re-scan that caught the real
  change too. Once that accident does not happen, the container's cache never learns the new item exists —
  and then the item's own later removal goes unreported too (nothing to remove from a cache that never had
  it), which is the tell that distinguishes this from a genuine timing race. `Equipory` has no such wrapper
  (its own child IS the `GItem`), which is why an equipment adapter keyed on `GItem` alone is already
  correct — the rule generalises as: **watch the widget the SERVER placed, never the client's own
  presentation wrapper around it**, and when a container has both, check for either.
- **(042.7) `Window.reqdestroy()` needed the SAME early tap `Buff.reqdestroy()` got in 042.2 — the "any
  future fading widget" line in that entry was not rhetorical.** `animst = "dest"` is set well before the
  real unlink (the fade), exactly like `Buff.dest`, so `widget:on("Destroy", fn)` needs the notify at the
  flag flip, not at the eventual `remove()`. Guarded by a local boolean so the no-op branch (`reqdestroy()`
  called again while already fading) does not double-fire; the late `remove()`-driven firing is a no-op
  because by then the watching `WidgetSubs` has already unregistered itself (list membership is the
  idempotency guard here, not a separate cache-membership flag like `BuffsAdapter`'s — simpler because one
  `WidgetSubs` watches exactly one widget). Planning documents that count core edits ("N total") need
  updating whenever a new fading widget is wired, the same way 042.2 first bumped the count.
- **(042.7) `widget:on("ItemAdded", fn)` must seed synchronously at subscribe time, not wait for the next
  tick.** The documented contract ("items already inside fire `ItemAdded` on the first poll after you
  subscribe") predates the event-driven rewrite and still holds — but an event-driven implementation has no
  "next poll" to lean on by default. The fix is to run the same diff `on()` calls when a container is watched
  for the first time (or when an `ItemAdded`/`ItemRemoved` key is added to an already-watched widget), inline
  in the same call — cheaper than the old poll's version of this (immediate rather than one tick later) but
  easy to silently drop if the seeding call is forgotten, since nothing else exercises that path in a
  same-session test (only a fresh subscribe does).
- **(042.9) A consumer of `onUimsg` that reads widget state or calls Lua must MARSHAL, never act inline —
  a second, independent near-miss of the same hazard 042.1's javadoc fix already named.** A first draft had
  the selector-watch late-refiner re-check call `Selector.matches`/`callLua` directly from inside
  `CharApi.dispatchUimsg` — which runs on whatever thread applied the uimsg (a Loader thread), OUTSIDE
  `synchronized(ui)` (`UI.java:730-732` closes the monitor before calling `AddonManager.onUimsg`). That is a
  race with tick/draw and a P5 violation, caught only by re-deriving the threading claim from `UI.java` rather
  than trusting the doc comment. The fix, same shape as `removedWidgets`/`resolveQueue`/`beltSetQueue`: the
  uimsg tap sets a bare `volatile boolean` flag and nothing else; the actual read + any Lua call happens from
  `AddonManager.tick`, under the UI monitor. **Rule for any future `onUimsg` consumer:** the tap body may only
  mark/enqueue; if the fix "obviously" fits in the tap itself, that is the tell that hazard 1 is about to bite.
- **(042.9) A per-tick poll being deleted can leave behind orphaned STATE, not just orphaned CODE — check what
  the poll used to clean up besides firing events.** The old `pollSelectorWatches()` special-cased clearing
  `pending` (a list of widgets awaiting a late `[title=]`/`[res=]`) whenever `selectorWatches` went empty; once
  the poll was gone, nothing did, so removing the last selector subscription silently leaked every `Widget`
  reference still sitting in `pending`. Fixed at both removal sites (`removeSelectorWatch`,
  `teardownSelectorWatches`): clear `pending` when `selectorWatches.isEmpty()`. **`Layout.java` owns the
  identical shape** (its own `pending`/`RECHECK_TICKS` bounded re-check, same reason) — 042.10, deleting
  `Layout.poll()`, needs the same audit: read the WHOLE poll method being deleted for side-effects beyond
  the event it fires, not just the event.
- **(044.8) A container closes by UNLINKING its grid and every item widget in one batch, and the events reach
  Lua after the fact — so "what was in it" can only be built while it is open, and the read that tells the two
  apart is `:exists()`.** `Widget.remove()` unlinks *before* firing the removal seam, and the seam only
  enqueues; the server destroys the grid and the items together and only then the window, which announces
  itself at its fade start. By the time the drain runs the Lua handlers, the grid is already detached, the
  still-live window reads **0 items**, and the close fires one `ItemRemoved` per item — every one of them
  about something already gone. A snapshot taken at `Destroy` is therefore always empty.
  **Subscribing to the WINDOW is not equivalent to subscribing to the GRID**, and that difference cost three
  verification rounds: the window OUTLIVES the grid, so those after-the-fact removals are read back through a
  widget that is still live and wipe the record to zero before the close is ever handled; the grid has left
  the tree by then, so `snapshot`'s `if not w:exists() then return end` refuses exactly the reads that are
  lying, while a removal the *user* makes (grid still live) updates it normally. Rule: **read a container's
  contents from the container, guard every read with `:exists()`, and never expect to look once the thing is
  going.** `addons/cupboard/` is the worked example.
- **(044.8) `WidgetSubs.refreshItems` reads the RAW widget while `widget:items()` goes through `live()`.** The
  event machinery diffs `LuaItem.items(wdg)` with no root-reachability test, so `ItemAdded`/`ItemRemoved` keep
  firing correctly for a container that has already left the tree — while the same container's `:items()`
  answers empty. The asymmetry is benign and is precisely what makes the `:exists()` guard above work, but it
  means **an event firing is not proof that its subject is still live**: a handler that re-reads must check.
- **(044.8) `:items()` IS a deep search, including through a standing surface — measured, not assumed.** With
  a container window standing in the 3D world, `window:items()` and `grid:items()` both answered 37 while
  both were in the tree, so `Widget.children(Class)` (a full depth-first walk of the subtree, despite its
  name) is unaffected by the re-home. `docs/addons/api/ui/items.md`'s "the search is deep" holds.
