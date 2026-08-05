package io.brodgar.addon;

import haven.BAttrWnd;
import haven.BuddyWnd;
import haven.Buff;
import haven.Bufflist;
import haven.CharWnd;
import haven.Coord2d;
import haven.Coord3f;
import haven.Coord3f;
import haven.Equipory;
import haven.FightWnd;
import haven.GameUI;
import haven.GItem;
import haven.Glob;
import haven.IMeter;
import haven.Indir;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.MapView;
import haven.Party;
import haven.QuestWnd;
import haven.Resource;
import haven.SAttrWnd;
import haven.SkillWnd;
import haven.Speedget;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.WoundWnd;
import haven.resutil.Curiosity;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


import static io.brodgar.addon.AddonManager.*;

/**
 * The character-read subsystem:
 * {@code hafen.player}/{@code char}/{@code items}/{@code study}/{@code party}/{@code kin}/{@code buffs}/
 * {@code actionbar}/{@code quests}/{@code wounds}/{@code fight} — the widget-tree reads of character state,
 * plus the change-detection {@link TreeAdapter}s that fire the semantic events (BuffAdded, FepChanged,
 * ...). Owns the adapter registry. {@link AddonManager} drives it via {@link #dispatchUimsg}
 * (the onUimsg tap), {@link #refreshTreeAdapters}/{@link #pollTreeAdapters} (the tick), and
 * {@link #resetSession} (init — clears + re-registers the adapters). Not instantiable.
 */
final class CharApi {
    private CharApi() {}

    private static final java.util.List<TreeAdapter> treeAdapters = new java.util.concurrent.CopyOnWriteArrayList<TreeAdapter>();
    private static final java.util.Set<TreeAdapter> treeDirty = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The inbound-uimsg tap body (behind AddonManager.onUimsg): flag the interested adapter(s) dirty. */
    static void dispatchUimsg(Widget w, String msg) {
        if((w == null) || treeAdapters.isEmpty())
            return;
        for(TreeAdapter a : treeAdapters) {
            try {
                if(a.interested(w, msg))
                    treeDirty.add(a);
            } catch(RuntimeException e) {
                /* an adapter's recognizer must never break server message application */
            }
        }
    }

    /** Session init: re-register the change-detection adapters, each with a fresh cache (from AddonManager.init). */
    static void resetSession() {
        treeDirty.clear();
        treeAdapters.clear();
        treeAdapters.add(new MeterAdapter());
        treeAdapters.add(new BuffsAdapter());
        treeAdapters.add(new FepAdapter());
        treeAdapters.add(new StudyAdapter());
        treeAdapters.add(new ActionbarAdapter());
        treeAdapters.add(new EquipAdapter());
        treeAdapters.add(new KinAdapter());
        treeAdapters.add(new QuestAdapter());
        treeAdapters.add(new WoundAdapter());
    }

    static void refreshTreeAdapters() {
        if(treeDirty.isEmpty())
            return;
        for(TreeAdapter a : treeAdapters) {
            if(treeDirty.remove(a)) {
                try {
                    a.refresh();
                } catch(RuntimeException e) {
                    log("tree adapter error: " + e);
                }
            }
        }
    }

    /** Give every adapter a per-tick look (UI thread) for changes no inbound uimsg announces. */
    static void pollTreeAdapters() {
        for(TreeAdapter a : treeAdapters) {
            try {
                a.poll();
            } catch(RuntimeException e) {
                log("tree adapter poll error: " + e);
            }
        }
    }

    /**
     * A widget-tree read adapter (spec {@code 14-widget-tree-reads.md}): the one place that knows a
     * target widget tree's shape, localizing that upstream-volatile knowledge. Two update paths:
     * <ul>
     *   <li><b>uimsg-driven</b> ({@link #interested} off-thread → dirty → {@link #refresh} on the UI
     *       thread): for state the server pushes via a targeted {@code uimsg} (meter values, FEP, buff
     *       content).</li>
     *   <li><b>poll-driven</b> ({@link #poll} every tick, UI thread): for structural changes the tap
     *       can't see — buff add/remove is a widget create/{@code cdestroy} on the {@code Bufflist},
     *       not a {@code uimsg}. Default is a no-op; only adapters that need it override it.</li>
     * </ul>
     */
    private interface TreeAdapter {
        boolean interested(Widget w, String msg);
        void refresh();
        default void poll() {}
    }

    /**
     * HUD meters — the {@link IMeter} bars in {@code GameUI}'s {@code place == "meter"} slot. The reads live
     * on {@link LuaMeter} since {@code 027-meters-oop} (the entity owns them); only change <i>detection</i> is
     * this adapter's business. The old positional {@code hp}/{@code stamina}/{@code energy} snapshot and its
     * {@code VitalsChanged} event are GONE with that spec's hard cut.
     *
     * <p>Two paths, exactly the {@link BuffsAdapter} split. A meter appearing / being destroyed is a widget
     * create/{@code cdestroy} on the HUD's meter slot, NOT a {@code uimsg}, so it is detected by <b>poll</b>
     * (diffing {@link LuaMeter#hud()} each tick against a cache keyed by widget identity) and fires
     * {@code MeterAdded} / {@code MeterRemoved}. The bar CONTENT is pushed by the server as a targeted
     * {@code "set"} (values) or {@code "col"} (colours) {@code uimsg}, so <b>refresh</b> re-reads the cached
     * meters and fires {@code MeterChanged} — colour is in the key because it is now in the read surface
     * ({@code meter:color()}), which the old {@code vitalsEqual} deliberately ignored.
     *
     * <p>All three carry the <b>Meter object</b> ({@link AddonManager#fireMeter}), so a handler reads the payload
     * with the same methods as {@code hafen.meter():list()}. The per-meter snapshot stays, purely as the diff KEY: an
     * interned object compares by identity and so cannot detect a content change (the 025.2 lesson). It is never
     * handed to Lua — {@code meter:info()} is that, on demand.
     */
    private static final class MeterAdapter implements TreeAdapter {
        // Live HUD meter -> its last segment snapshot (the change-detection key, NOT a payload). UI-thread-only
        // (poll + refresh); reset per session by re-instantiation in resetSession(). IdentityHashMap: IMeter
        // widgets are keyed by object identity, like the buffs.
        private final Map<IMeter, LuaValue> cache = new IdentityHashMap<IMeter, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof IMeter) && ("set".equals(msg) || "col".equals(msg));
        }

        public void refresh() {
            for(Map.Entry<IMeter, LuaValue> e : cache.entrySet()) {
                LuaValue snap = LuaMeter.segments(e.getKey());
                if(!meterEqual(snap, e.getValue())) {
                    e.setValue(snap);
                    fireMeter("MeterChanged", e.getKey());
                }
            }
        }

        public void poll() {
            List<IMeter> hud = LuaMeter.hud();
            for(IMeter m : hud) {                         // additions (unseen meters)
                if(!cache.containsKey(m)) {
                    cache.put(m, LuaMeter.segments(m));
                    fireMeter("MeterAdded", m);
                }
            }
            for(Iterator<Map.Entry<IMeter, LuaValue>> it = cache.entrySet().iterator(); it.hasNext();) {
                Map.Entry<IMeter, LuaValue> e = it.next();   // removals (no longer in the HUD slot)
                if(!contains(hud, e.getKey())) {
                    // The widget is unlinked, not cleared: the payload still answers :res()/:value()/… and now
                    // reports :exists() false. Fire BEFORE dropping the entry — the map holds nothing the
                    // payload needs, but the order keeps "the meter the adapter just dropped" literal.
                    fireMeter("MeterRemoved", e.getKey());
                    it.remove();
                }
            }
        }

        /** Identity membership (an {@link IMeter} is compared as a widget, never by equals). */
        private static boolean contains(List<IMeter> hud, IMeter m) {
            for(IMeter c : hud) {
                if(c == m)
                    return true;
            }
            return false;
        }
    }

    /**
     * Do two meter segment arrays carry the same values and colours? (for change-detection.) The segment list is
     * the whole change key: {@code :value()}/{@code :color()} are its first entry, so comparing it covers both
     * the {@code "set"} and the {@code "col"} update in one pass — and a multi-segment bar whose later segments
     * move is a real change too. {@code :res()} is NOT in the key: a resource resolving out of {@code Loading}
     * renames nothing, and {@code :index()} is layout, not state.
     */
    private static boolean meterEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return false;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue sa = a.get(i), sb = b.get(i);
            if(!luaFieldEq(sa, sb, "value"))
                return false;
            LuaValue ca = sa.get("color"), cb = sb.get("color");
            if(ca.isnil() != cb.isnil())
                return false;
            if(!ca.isnil() && !(luaFieldEq(ca, cb, "r") && luaFieldEq(ca, cb, "g")
                                && luaFieldEq(ca, cb, "b") && luaFieldEq(ca, cb, "a")))
                return false;
        }
        return true;
    }

    /**
     * Buffs — the {@link Buff} widgets under {@link GameUI#buffs} (a {@link Bufflist}). Add and
     * remove are widget create/{@code cdestroy}, NOT a {@code uimsg}, so they are detected by
     * <b>poll</b> (diffing {@code children(Buff.class)} each tick against a cache keyed by widget
     * identity); the per-buff {@code "ch"}/{@code "tt"} content updates ARE {@code uimsg}s, so
     * <b>refresh</b> re-reads the cached buffs and fires {@code BuffChanged}. A buff fading out after a
     * server removal ({@code Buff.dest}) is treated as already gone (excluded), so removal is timely.
     *
     * <p>The reads themselves live on {@link LuaBuff} since {@code 025-buffs-oop} (the entity owns them);
     * only change <i>detection</i> — the per-buff snapshot diff — is this adapter's business. Since 025.2 the
     * three events carry the <b>Buff object</b> ({@link AddonManager#fireBuff}), so a handler reads the
     * payload with the same methods as {@code hafen.buff():list()}. The snapshot stays, purely as the diff KEY: it
     * is the cheap value-comparable form of the buff, and it is what makes {@code BuffChanged} fire on real
     * content changes only. It is never handed to Lua any more — {@code buff:info()} is that, on demand.
     */
    private static final class BuffsAdapter implements TreeAdapter {
        // Active buff -> its last snapshot (the change-detection key, NOT a payload). UI-thread-only (poll +
        // refresh); reset per session by re-instantiation in init(). IdentityHashMap: Buff widgets are keyed
        // by object identity.
        private final Map<Buff, LuaValue> cache = new IdentityHashMap<Buff, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof Buff) && ("ch".equals(msg) || "tt".equals(msg));
        }

        public void refresh() {
            for(Map.Entry<Buff, LuaValue> e : cache.entrySet()) {
                LuaValue snap = LuaBuff.snapshot(e.getKey());
                if(!buffEqual(snap, e.getValue())) {
                    e.setValue(snap);
                    fireBuff("BuffChanged", e.getKey());
                }
            }
        }

        public void poll() {
            Set<Buff> active = new LinkedHashSet<Buff>(LuaBuff.actives());
            for(Buff b : active) {                        // additions (unseen buffs)
                if(!cache.containsKey(b)) {
                    cache.put(b, LuaBuff.snapshot(b));
                    fireBuff("BuffAdded", b);
                }
            }
            for(Iterator<Map.Entry<Buff, LuaValue>> it = cache.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Buff, LuaValue> e = it.next();  // removals (gone or fading out)
                if(!active.contains(e.getKey())) {
                    // The widget is unlinked, not cleared: the payload still answers :res()/:name()/… and now
                    // reports :exists() false. Fire BEFORE dropping the entry — the map holds nothing the
                    // payload needs, but the order keeps "the buff the adapter just dropped" literal.
                    fireBuff("BuffRemoved", e.getKey());
                    it.remove();
                }
            }
        }
    }

    /**
     * FEP + hunger — the {@link BAttrWnd} (character-sheet "Base Attributes" tab). Located directly via
     * the public {@code CharWnd.battr} field (no tree-walk), then its public {@code feps}
     * ({@link BAttrWnd.FoodMeter}) and {@code glut} ({@link BAttrWnd.GlutMeter}) are read — all public
     * fields, so this needs no {@code haven}-package accessor. Both update via a {@code BAttrWnd}
     * {@code "food"}/{@code "glut"} {@code uimsg}, so it is purely uimsg-driven; each is a genuine
     * server change, so {@code FepChanged} fires whenever one lands (no change-detection needed).
     */
    private static final class FepAdapter implements TreeAdapter {
        public boolean interested(Widget w, String msg) {
            return (w instanceof BAttrWnd) && ("food".equals(msg) || "glut".equals(msg));
        }

        public void refresh() {
            LuaValue snap = readFood();
            if(!snap.isnil())
                fire("FepChanged", snap);
        }
    }

    /**
     * Study / curiosity — the items placed in the study window, each carrying a {@link Curiosity}
     * study profile. Located via the public {@code CharWnd.sattr} ({@link SAttrWnd}) → its
     * {@link SAttrWnd.StudyInfo} child → the study inventory it wraps. Like buffs, a curiosity being
     * added/removed is a widget create/{@code cdestroy} (not a {@code uimsg}) and its study data
     * streams in a beat after the item appears, so this is <b>poll-driven</b>: each tick it re-reads
     * the slots snapshot and fires {@code StudyChanged} only when it differs from the cache (an
     * add/remove, or a slot's fields resolving/changing). While the sattr tab is not up yet the poll is
     * skipped (the cache is kept), so no spurious event fires before there is anything to read.
     */
    private static final class StudyAdapter implements TreeAdapter {
        private LuaValue cache;   // last study-slots snapshot (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // study changes are structural / streamed, not a targeted uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            SAttrWnd.StudyInfo si = studyInfo();
            if(si == null)
                return;           // study window not up yet — keep the cache, fire nothing
            LuaValue snap = readStudySlots(si.study);
            if(!studySlotsEqual(snap, cache)) {
                cache = snap;
                fire("StudyChanged", snap);
            }
        }
    }

    /**
     * Action bar / hotbar — the 144 {@link GameUI.BeltSlot}s of {@code GameUI.belt} (the F-key /
     * number-key hotbar; the engine's own name for the action bar is the "belt"). Setting/clearing/
     * dragging a slot is a {@code setbelt}/{@code setbelt2} {@code uimsg} to {@code GameUI}, but for the
     * common (resource/pagina) cases the slot array is mutated on a <b>deferred loader task</b> that runs
     * after the message is dispatched — so a synchronous refresh-on-uimsg would race the write. Hence this
     * is <b>poll-driven</b> (like buffs/study): each tick it diffs the occupied slots against a per-index
     * cache and fires {@code ActionbarChanged} on a set/clear/change (or a slot's data resolving).
     * Change-detection ignores {@code cooldown} (a live meter that would otherwise fire every frame while
     * an ability cools down); {@code hafen.actionbar():get(n)} still reads it live.
     *
     * <p>The snapshots are the diff's <i>input only</i>: what reaches Lua is a per-addon <b>Slot object</b> for
     * the changed slot ({@link AddonManager#fireSlot}, 021.2), so a handler reads the payload with the same
     * methods as {@code hafen.actionbar():get(n)} and can key a table by it.
     */
    private static final class ActionbarAdapter implements TreeAdapter {
        // slot index -> last snapshot, occupied slots only. UI-thread-only; reset per session by
        // re-instantiation in init(). Keyed by Integer (value identity), not widget identity.
        private final Map<Integer, LuaValue> cache = new HashMap<Integer, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return false;         // slot set/clear mutates belt[] on a deferred loader task — see poll()
        }

        public void refresh() {}

        public void poll() {
            GameUI g = gui();
            if((g == null) || (g.belt == null))
                return;           // HUD not up yet — keep the cache, fire nothing
            GameUI.BeltSlot[] belt = g.belt;
            for(int n = 0; n < belt.length; n++) {
                GameUI.BeltSlot s = belt[n];
                LuaValue prev = cache.get(n);
                if(s == null) {
                    if(prev != null) {                        // occupied -> empty (cleared)
                        cache.remove(n);
                        fireSlot(n);
                    }
                } else {
                    LuaValue snap = actionbarSnapshot(s);
                    if((prev == null) || !actionbarEqual(snap, prev)) {   // empty->occupied or content changed
                        cache.put(n, snap);
                        fireSlot(n);
                    }
                }
            }
        }
    }

    /**
     * Equipment — the {@link WItem}s worn in the {@link Equipory}. Equipping/removing is a widget
     * create/{@code cdestroy} under the Equipory (not a targeted {@code uimsg}), and item data streams
     * in a beat after each item appears, so this is <b>poll-driven</b>: each tick it re-reads the
     * equipment snapshot (the same array {@code hafen.ui.equipment():items()} returns) and fires {@code
     * EquipChanged} with it when the set changes. Change-detection compares {@code slot}/{@code res}/
     * {@code name}/{@code num} — not {@code wear} (a slowly-changing durability that is not an equip
     * change; read it live via {@code hafen.ui.equipment():items()}).
     */
    private static final class EquipAdapter implements TreeAdapter {
        private LuaValue cache;   // last equipment snapshot (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // equip/unequip is a widget create/cdestroy, not a uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            Equipory eq = equipory();
            if(eq == null)
                return;           // equipory not up yet — keep the cache, fire nothing
            LuaValue snap = readEquipment(eq);
            if(!equipEqual(snap, cache)) {
                cache = snap;
                fire("EquipChanged", snap);
            }
        }
    }

    /**
     * Kin/buddy roster (A6) — the {@link BuddyWnd.Buddy} entries in the Kin window ({@link
     * GameUI#buddies}, a {@link BuddyWnd}). Every roster change the client learns of arrives as a
     * targeted {@code uimsg} to the {@code BuddyWnd} — a kin added ({@code "add"}), removed ({@code
     * "rm"}), edited ({@code "upd"}: nick/group) or an online-status flip ({@code "chst"}) — so unlike
     * the buff/study adapters (whose add/remove is a widget create, invisible to the tap) this is
     * <b>uimsg-driven</b>: {@link #interested} flags those four messages, and {@link #refresh} re-reads
     * the snapshot list and fires {@code KinChanged} when it actually differs.
     * Change-detection is a snapshot diff, NOT {@code BuddyWnd.serial} — {@code serial} does not bump on
     * {@code "chst"} (an online/offline flip), which a kin-alert addon most wants to hear.
     *
     * <p>The snapshots are the diff's <i>input only</i>: what reaches Lua is a per-addon array of <b>Kin
     * objects</b> ({@link AddonManager#fireKin}, 020.3), so a handler reads the payload with the same
     * methods as {@code hafen.kin():list()} and can key a table by an entry.
     */
    private static final class KinAdapter implements TreeAdapter {
        private LuaValue cache = LuaValue.NIL;   // last kin snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return (w instanceof BuddyWnd) &&
                   ("add".equals(msg) || "rm".equals(msg) || "chst".equals(msg) || "upd".equals(msg));
        }

        public void refresh() {
            LuaValue snap = kinSnapshotList();
            if(!kinListEqual(snap, cache)) {
                cache = snap;
                fireKin(kinIds(snap));
            }
        }
    }

    /**
     * Quest log (A9) — the quests under the character sheet's "Quest Log" tab ({@link QuestWnd},
     * reached via the public {@code CharWnd.quest} field). Every quest change the client learns of
     * arrives as a targeted {@code "quests"} {@code uimsg} to the {@code QuestWnd} (a quest added, its
     * status advanced, or removed) — so, like the {@link KinAdapter}, this is <b>uimsg-driven</b>:
     * {@link #interested} flags that message and {@link #refresh} re-reads the full quest set and diffs
     * it against a per-id status cache via the pure {@link #questDiff}. Fires {@code QuestAdded} when a
     * new <i>active</i> quest (pending/disabled) appears and {@code QuestDone} when a previously-active
     * quest becomes <i>finished</i> (done/failed) — mirroring {@code QuestWnd}'s own completion trigger.
     * Completed quests already present at login are recorded silently (no {@code QuestAdded}), so the
     * quest history doesn't spam events. Payload = the quest snapshot.
     */
    private static final class QuestAdapter implements TreeAdapter {
        // quest id -> its last-seen status int. UI-thread-only (refresh); reset per session by
        // re-instantiation in init().
        private final Map<Integer, Integer> cache = new HashMap<Integer, Integer>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof QuestWnd) && "quests".equals(msg);
        }

        public void refresh() {
            QuestWnd qw = questwnd();
            UI u = ui;
            if((qw == null) || (u == null))
                return;
            // Copy both quest lists under the ui monitor (QuestWnd.uimsg mutates them off-thread), then
            // build snapshots outside it (names may Loading) — the marker "copy under the lock" discipline.
            List<QuestWnd.Quest> all = new ArrayList<QuestWnd.Quest>();
            synchronized(u) {
                all.addAll(qw.cqst.quests);          // "Current" tab (pending / disabled)
                all.addAll(qw.dqst.quests);          // "Completed" tab (done / failed)
            }
            Map<Integer, Integer> fresh = new LinkedHashMap<Integer, Integer>();   // id -> done (in order)
            Map<Integer, QuestWnd.Quest> byId = new HashMap<Integer, QuestWnd.Quest>();
            for(QuestWnd.Quest q : all) {
                fresh.put(q.id, q.done);
                byId.put(q.id, q);
            }
            for(Object[] ev : questDiff(cache, fresh)) {          // pure diff (also updates the cache)
                QuestWnd.Quest q = byId.get((Integer)ev[1]);
                if(q != null)
                    fire((String)ev[0], questSnapshot(q));
            }
        }
    }

    /**
     * Diff a fresh {@code id -> done} quest map against {@code cache}, returning the events to fire as
     * {@code {String event, Integer id}} pairs and updating {@code cache} to match {@code fresh}. Pure
     * (no widget / Lua access) so the add/complete semantics are headless-testable:
     * <ul>
     *   <li><b>QuestAdded</b> — an id not previously cached whose status is <i>active</i>
     *       (pending/disabled). A quest already finished when first seen (e.g. the completed history that
     *       streams in at login) is recorded silently — no event.</li>
     *   <li><b>QuestDone</b> — a previously-<i>active</i> id that is now <i>finished</i> (done/failed),
     *       mirroring {@code QuestWnd}'s own completion trigger.</li>
     * </ul>
     * An id absent from {@code fresh} (server-removed) is pruned with no event, so a later re-add re-fires
     * {@code QuestAdded}.
     */
    private static List<Object[]> questDiff(Map<Integer, Integer> cache, Map<Integer, Integer> fresh) {
        List<Object[]> events = new ArrayList<Object[]>();
        for(Map.Entry<Integer, Integer> e : fresh.entrySet()) {
            Integer id = e.getKey();
            int done = e.getValue().intValue();
            Integer prev = cache.get(id);
            if(prev == null) {
                if(questActive(done))
                    events.add(new Object[]{"QuestAdded", id});
            } else if(questActive(prev.intValue()) && !questActive(done)) {
                events.add(new Object[]{"QuestDone", id});
            }
        }
        cache.keySet().retainAll(fresh.keySet());    // prune ids the server dropped
        cache.putAll(fresh);                          // update to the current statuses
        return events;
    }

    /**
     * Wounds (A9-2) — the wounds under the character sheet's "Health &amp; Wounds" tab ({@link WoundWnd},
     * reached via the public {@code CharWnd.wound} field). A wound being added / healed / worsening arrives
     * as a {@code "wounds"} {@code uimsg}, but a wound's <b>severity</b> (its {@link WoundWnd.QuickInfo}
     * magnitude) comes from resource-published {@code ItemInfo} that <b>streams in a beat after</b> the
     * wound row (its {@code res.get()} still Loading on the first refresh) — exactly the {@link StudyAdapter}
     * situation. So, like study/buffs, this is <b>poll-driven</b>: each tick it re-reads the full wound
     * list and fires <b>{@code WoundChanged}</b> only when it differs from the cache (an add/heal, a
     * severity resolving nil→value, or a wound getting worse) — the {@link #woundListEqual} change-detection,
     * with {@code severity} in the key so a worsening fires it. While the wound tab is not up yet the poll
     * is skipped (the cache is kept), so nothing fires before there is anything to read. Payload = the list
     * (the {@code KinChanged} shape). Read the initial state with {@code list()}; listen for deltas after.
     */
    private static final class WoundAdapter implements TreeAdapter {
        private LuaValue cache;   // last wound snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // wound add/heal is a uimsg, but severity streams in a beat later — see poll()
        }

        public void refresh() {}

        public void poll() {
            if(woundwnd() == null)
                return;           // Health & Wounds tab not up yet — keep the cache, fire nothing
            LuaValue snap = woundList(LuaValue.NIL);
            if(!woundListEqual(snap, cache)) {
                cache = snap;
                fire("WoundChanged", snap);
            }
        }
    }

    /* The buff READS (bufflist/res/name/amount/duration/number + the snapshot) moved onto LuaBuff with
     * 025-buffs-oop — the entity owns them. What stays here is change DETECTION, below. */

    /** Do two buff snapshots carry the same res/name/amount/duration/number? (for change-detection.) */
    private static boolean buffEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null))
            return false;
        return luaFieldEq(a, b, "res") && luaFieldEq(a, b, "name") && luaFieldEq(a, b, "amount")
            && luaFieldEq(a, b, "duration") && luaFieldEq(a, b, "number");
    }

    /** Field-level equality for a snapshot key: nil/number/string aware (used by buffEqual). */
    private static boolean luaFieldEq(LuaValue a, LuaValue b, String k) {
        LuaValue va = a.get(k), vb = b.get(k);
        if(va.isnil() != vb.isnil())
            return false;
        if(va.isnumber())
            return vb.isnumber() && (va.todouble() == vb.todouble());
        if(va.isstring())
            return vb.isstring() && va.tojstring().equals(vb.tojstring());
        return true;
    }

    /** The character sheet's Base-Attributes widget ({@code CharWnd.battr}), or {@code null}. */
    private static BAttrWnd battrwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.battr;
    }

    /**
     * A food snapshot ({@code hafen.char.food}): {@code fep = {cap,total,entries=[{res,name,amount}]}}
     * from the {@link BAttrWnd.FoodMeter}, and {@code hunger = {level,label,efficacy}} from the
     * {@link BAttrWnd.GlutMeter}. All backing fields are public; per-entry name/res are Loading-guarded
     * (skipped while resolving). nil until the character sheet's {@code battr} tab exists (it streams
     * in a beat after enter-world, like vitals/char/items).
     */
    private static LuaValue readFood() {
        BAttrWnd w = battrwnd();
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        try {
            BAttrWnd.FoodMeter fm = w.feps;
            if(fm != null) {
                LuaTable fep = new LuaTable();
                fep.set("cap", LuaValue.valueOf(fm.cap));
                LuaTable entries = new LuaTable();
                double total = 0;
                int i = 0;
                for(BAttrWnd.FoodMeter.El el : new ArrayList<BAttrWnd.FoodMeter.El>(fm.els)) {
                    LuaTable e = new LuaTable();
                    try {
                        Resource r = el.res.get();
                        if(r != null)
                            e.set("res", LuaValue.valueOf(r.name));
                        BAttrWnd.FoodMeter.Event ev = el.ev();
                        if((ev != null) && (ev.nm != null))
                            e.set("name", LuaValue.valueOf(ev.nm));
                    } catch(RuntimeException ex) {
                        /* this event's resource is still Loading — keep the amount */
                    }
                    e.set("amount", LuaValue.valueOf(el.a));
                    total += el.a;
                    entries.set(++i, e);
                }
                fep.set("total", LuaValue.valueOf(total));
                fep.set("entries", entries);
                t.set("fep", fep);
            }
        } catch(RuntimeException e) {
            /* partial snapshot is fine while food data streams in */
        }
        try {
            BAttrWnd.GlutMeter gm = w.glut;
            if(gm != null) {
                LuaTable h = new LuaTable();
                h.set("level", LuaValue.valueOf(gm.glut));
                if(gm.lbl != null)
                    h.set("label", LuaValue.valueOf(gm.lbl));
                h.set("efficacy", LuaValue.valueOf(gm.gmod));
                t.set("hunger", h);
            }
        } catch(RuntimeException e) {
            /* partial */
        }
        return t;
    }

    /**
     * Build {@code hafen.player} for {@code owner}. From installHafen. Since D-046 {@code hafen.player} is a
     * <b>callable table</b> ({@code __call}, like {@code hafen.gob}) returning the addon's single <b>Player
     * object</b>: {@code hafen.player():gob()} is the composition anchor for every per-gob read of the player
     * (position/health/moving/facing/…). Player forwards <b>nothing</b> — a {@code player:pos()} living beside
     * {@code player:gob():pos()} is exactly the dual style D-013 forbids — and {@code exists}/{@code id} are
     * dropped: {@code player:gob()} (nil before entering the world) and {@code gob:id()} already answer both.
     * The object is a per-addon singleton (cached on {@link Addon#playerObj}), so {@code hafen.player() ==
     * hafen.player()}; it is userdata with a per-addon metatable, immutable from Lua, like a {@link LuaGob}.
     */
    static void installPlayer(LuaTable hafen, final Addon owner) {
        LuaTable methods = new LuaTable();
        // gob() — the player's Gob object, or nil before entering the world. hafen.gob(id) interning makes this
        // the SAME object as hafen.gob(<player id>).
        methods.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapView m = view;
                if((m == null) || (m.plgob < 0))
                    return LuaValue.NIL;
                return LuaGob.of(owner, m.plgob);
            }
        });
        methods.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GameUI g = gui();
                return ((g == null) || (g.chrid == null)) ? LuaValue.NIL : LuaValue.valueOf(g.chrid);
            }
        });
        /* vitals() is GONE (027-meters-oop's hard cut): the HUD bars are hafen.meter():list(), which is every meter
         * the server puts in the slot rather than a hard-coded hp/stamina/energy triple read by position. */
        methods.set("worldToScreen", new ThreeArgFunction() {
            public LuaValue call(LuaValue self, LuaValue x, LuaValue y) {
                MapView m = view;
                if((m == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord3f sc = m.screenxf(Coord2d.of(x.todouble(), y.todouble()));
                    return (sc == null) ? LuaValue.NIL : xy(sc.x, sc.y);
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        final LuaTable pmt = new LuaTable();
        pmt.set(LuaValue.INDEX, methods);
        pmt.set("__name", LuaValue.valueOf("Player"));
        pmt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Player");
            }
        });
        LuaTable player = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(owner.playerObj == null)
                    owner.playerObj = LuaValue.userdataOf(new PlayerMark(), pmt);
                return owner.playerObj;
            }
        });
        player.setmetatable(mt);   // a callable table (not a bare function) so hafen.player.name reads as nil
        hafen.set("player", player);
    }

    /** The opaque instance behind a Player userdata (facade-safe: no Java object of the engine's crosses). */
    private static final class PlayerMark {
        public String toString() { return "Player"; }
    }

    // hafen.items is a HARD CUT (029.3, D-013). In Hafen there is no inventory model outside the widget tree —
    // GameUI.maininv is an Inventory exactly like a chest's — so a section of its own only preserved the
    // player-inventory privilege the Widget entity removes. Items are now a RELATION on their container:
    // hafen.ui.inventory():items() / hafen.ui.equipment():items() / hafen.ui.hand(), and :items() answers on ANY
    // container widget (a chest, a cupboard, another player's equipory) with nothing hidden. `find` had no
    // replacement built for it: it was a name/res substring filter over one array, which is a Lua one-liner over
    // :items(). The item SHAPE is unchanged — itemSnapshot below is still the one Item producer.

    /** Build a char namespace for owner. From installHafen. */
    static void installChar(LuaTable hafen, final Addon owner) {
        LuaTable chr = new LuaTable();
        chr.set("attr", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                return name.isstring() ? attrSnapshot(name.tojstring()) : LuaValue.NIL;
            }
        });
        chr.set("attrs", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                for(String nm : ATTR_NAMES) {
                    LuaValue a = attrSnapshot(nm);
                    if(!a.isnil())
                        out.set(nm, a);
                }
                return out;
            }
        });
        chr.set("lp", new ZeroArgFunction() {
            public LuaValue call() {
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.exp);
            }
        });
        chr.set("weight", new ZeroArgFunction() {
            public LuaValue call() {
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.enc);
            }
        });
        // food() — FEP + hunger via the widget-tree mechanism (BAttrWnd; 1d-2). Returns
        // { fep = {cap,total,entries={{res,name,amount}}}, hunger = {level,label,efficacy} } or nil
        // until the character sheet's base-attributes tab exists (it streams in after enter-world).
        // Subscribe to FepChanged for updates (fired on the server's "food"/"glut" uimsgs).
        chr.set("food", new ZeroArgFunction() {
            public LuaValue call() {
                return readFood();
            }
        });
        // skills() — the character's KNOWN skills as {name, res} snapshots; skill(name) — a substring
        // membership test over them (name OR res, matching hafen.buff():find(needle)). Backed by the SkillWnd
        // "Skills" tab (widget-tree), which streams in after enter-world like the rest of the sheet.
        chr.set("skills", new ZeroArgFunction() {
            public LuaValue call() {
                return readSkills();
            }
        });
        chr.set("skill", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                return (name.isstring() && hasSkill(name.tojstring())) ? LuaValue.TRUE : LuaValue.FALSE;
            }
        });
        // A4 (completes the study/skills subsystem): the rest of the "Lore & Skills" window beyond the
        // known skills above. skillsAvailable() = the BUYABLE skills {name,res,cost} (the nsk group next
        // to the known csk group; cost = LP price). credos() = the Credos tab — {acquired, available}
        // lists (each of {name,res}) + the currently-pursued credo under `pursuing` ({name,res,level,
        // levelTotal,quest,questTotal,questId}, absent when none) + `cost` (LP to begin pursuing one), or
        // nil until the window exists. experiences() = the Lore tab {name,res,score,mtime}. All stream in
        // after enter-world like the known skills; there is no *Changed event — these change only on
        // explicit, infrequent player actions (buy / pursue / quest progress), so read them on demand.
        chr.set("skillsAvailable", new ZeroArgFunction() {
            public LuaValue call() {
                return readAvailableSkills();
            }
        });
        chr.set("credos", new ZeroArgFunction() {
            public LuaValue call() {
                return readCredos();
            }
        });
        chr.set("experiences", new ZeroArgFunction() {
            public LuaValue call() {
                return readExperiences();
            }
        });
        hafen.set("char", chr);
    }

    /** Build a char namespace for owner. From installHafen. */
    static void installStudy(LuaTable hafen, final Addon owner) {
        LuaTable study = new LuaTable();
        study.set("slots", new ZeroArgFunction() {
            public LuaValue call() {
                SAttrWnd.StudyInfo si = studyInfo();
                return (si == null) ? new LuaTable() : readStudySlots(si.study);
            }
        });
        study.set("summary", new ZeroArgFunction() {
            public LuaValue call() {
                return studySummary();
            }
        });
        hafen.set("study", study);
    }

    /** Build a char namespace for owner. From installHafen. */
    static void installParty(LuaTable hafen, final Addon owner) {
        LuaTable party = new LuaTable();
        party.set("members", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Party.Member m : partyMembers())
                    out.set(++i, memberSnapshot(m));
                return out;
            }
        });
        party.set("leader", new ZeroArgFunction() {
            public LuaValue call() {
                Party p = party();
                return ((p == null) || (p.leader == null)) ? LuaValue.NIL : memberSnapshot(p.leader);
            }
        });
        party.set("member", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                Party p = party();
                if((p == null) || !id.isnumber())
                    return LuaValue.NIL;
                Party.Member m = p.memb.get(Long.valueOf((long)id.todouble()));
                return (m == null) ? LuaValue.NIL : memberSnapshot(m);
            }
        });
        hafen.set("party", party);
    }

    /**
     * Install {@code hafen.kin} for owner. From installHafen. <b>The section object IS the roster</b> (uniform
     * grammar §2.1): {@code hafen.kin()} is the {@link LuaCollection} {@link LuaKin#collection} builds, and one
     * kin is {@code hafen.kin():get(idOrName)}. Only the kin-side plumbing the event adapter still needs
     * ({@link #buddywnd}, {@link #kinSnapshot}, {@link #kinListEqual}, {@link #kinIds}) stays here.
     */
    static void installKin(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "kin", LuaKin.collection(owner),
                      "hafen.kin(idOrName) is now hafen.kin():get(idOrName), and hafen.kin() is"
                      + " hafen.kin():list()");
    }

    /** Build a char namespace for owner. From installHafen. */
    static void installQuests(LuaTable hafen, final Addon owner) {
        LuaTable quests = new LuaTable();
        quests.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return questList(filter);
            }
        });
        quests.set("selected", new ZeroArgFunction() {
            public LuaValue call() {
                return questSelected();
            }
        });
        hafen.set("quests", quests);
    }

    /** Build a char namespace for owner. From installHafen. */
    static void installWounds(LuaTable hafen, final Addon owner) {
        LuaTable wounds = new LuaTable();
        wounds.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return woundList(filter);
            }
        });
        wounds.set("has", new OneArgFunction() {
            public LuaValue call(LuaValue q) {
                if(!q.isstring())
                    return LuaValue.FALSE;
                String needle = q.tojstring();
                for(WoundWnd.Wound w : copyWounds()) {
                    String res = resIdent(w.res), name = woundName(w);
                    if(((res != null) && res.contains(needle)) || ((name != null) && name.contains(needle)))
                        return LuaValue.TRUE;
                }
                return LuaValue.FALSE;
            }
        });
        hafen.set("wounds", wounds);
    }

    /** Build a char namespace for owner. From installHafen. */
    static void installFight(LuaTable hafen, final Addon owner) {
        LuaTable fight = new LuaTable();
        fight.set("maneuvers", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return fightManeuvers(filter);
            }
        });
        fight.set("deck", new ZeroArgFunction() {
            public LuaValue call() {
                return fightDeck();
            }
        });
        fight.set("summary", new ZeroArgFunction() {
            public LuaValue call() {
                return fightSummary();
            }
        });
        hafen.set("fight", fight);
    }

    /**
     * Build the buff namespace for owner. From installHafen. <b>The section object IS the buff bar</b>
     * (uniform grammar §2.1): {@code hafen.buff()} is the {@link LuaCollection} of the active buffs and
     * {@code hafen.buff():find(needle)} the first whose res or name contains it. A buff has no key, so the
     * collection carries no {@code :get}; the reads live on the {@link LuaBuff} object itself.
     */
    static void installBuffs(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "buff", LuaBuff.collection(owner),
                      "hafen.buff(needle) is now hafen.buff():find(needle), and hafen.buff() is"
                      + " hafen.buff():list()");
    }

    /**
     * Install {@code hafen.meter} — <b>the section object IS the meter slot</b> (uniform grammar §2.1, spec
     * {@code 027-meters-oop}): {@code hafen.meter()} is the {@link LuaCollection} of every meter in the HUD's
     * meter slot and {@code hafen.meter():find(needle)} the first whose server-published res name contains it.
     * A meter has no key, so the collection carries no {@code :get}; the reads live on the {@link LuaMeter}
     * object itself.
     */
    static void installMeters(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "meter", LuaMeter.collection(owner),
                      "hafen.meter(needle) is now hafen.meter():find(needle), and hafen.meter() is"
                      + " hafen.meter():list()");
    }

    /**
     * Build a char namespace for owner. From installHafen. <b>The section object IS the bar</b> (uniform
     * grammar §2.1): {@code hafen.actionbar()} is the {@link LuaCollection} of all 144 slots and
     * {@code hafen.actionbar():get(n)} is one {@link LuaSlot} by its raw game index; the reads and the two
     * gated verbs live on the Slot object itself.
     */
    static void installActionbar(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "actionbar", LuaSlot.collection(owner),
                      "hafen.actionbar(n) is now hafen.actionbar():get(n), and hafen.actionbar() is"
                      + " hafen.actionbar():list()");
    }

    // ------------------------------------------------------------- items / char / party reads

    /** The nine base character attributes (content-defined; not discoverable from {@link Glob}). */
    private static final String[] ATTR_NAMES =
        {"str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"};

    /** The player's main inventory widget, or {@code null} before the HUD/inventory exists. */
    static Inventory maininv() {
        GameUI g = gui();
        return (g == null) ? null : g.maininv;
    }

    /**
     * The player's equipment widget: the {@link Equipory} under the HUD. {@code GameUI.equwnd} is a
     * private {@code Window}, so we descend to the Equipory itself — typically the only one open (a
     * second appears only while inspecting another gob's equipment). {@code null} before it exists.
     */
    static Equipory equipory() {
        GameUI g = gui();
        if(g != null) {
            for(Equipory e : g.children(Equipory.class))
                return e;
        }
        return null;
    }

    /** The character window (created hidden at login, but live), or {@code null} before it exists. */
    private static CharWnd charwnd() {
        GameUI g = gui();
        return (g == null) ? null : g.chrwdg;
    }


    /** The equipment slot index of a {@link WItem} under an {@link Equipory}, or {@code -1}. */
    private static int slotOf(Equipory eq, WItem w) {
        try {
            return eq.epat(w.c);
        } catch(RuntimeException e) {
            return -1;
        }
    }

    /** The human-readable equipment slot name for an ep index, or nil. */
    private static LuaValue slotName(int ep) {
        if((ep >= 0) && (ep < Equipory.etts.length) && (Equipory.etts[ep] != null))
            return LuaValue.valueOf(Equipory.etts[ep].text);
        return LuaValue.NIL;
    }

    /** {@code ItemInfo.Name} display text for an item, or {@code null} (Loading-guarded). */
    private static String itemName(GItem it) {
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, it.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** Resource name (stable identity) for an item, or {@code null} (Loading-guarded). */
    private static String itemRes(GItem it) {
        try {
            Resource r = it.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    /**
     * An Item snapshot (the {@code Item} shape in api-reference.md) from a {@link GItem}. {@code pos}
     * is supplied by the caller (grid cell for inventory, slot name for equipment; nil for the hand).
     * Every field is optional / Loading-guarded: {@code num == -1} and {@code meter == 0} are treated
     * as "absent" (matching the client's own convention). {@code quality}/{@code contents} are deferred
     * (content-defined value / container widgets).
     *
     * <p>{@code handle} is the item's <b>server widget id</b> ({@link GItem#wdgid()}): the stable, facade-safe
     * {@code ItemRef} (principle P1 — just an int) that the gated {@code hafen.act.item(item, verb)} verb (4f)
     * takes to re-resolve the live {@link GItem} and drive it. It is a live reference on an otherwise
     * point-in-time snapshot (the other fields are a copy, like {@code gob:info()}) — the only way to
     * address an item, since items carry no other stable id (D-022: handle-only). Omitted for an unbound item.
     */
    static LuaValue itemSnapshot(GItem it, LuaValue pos) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = itemRes(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = itemName(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        if(it.num != -1)
            t.set("num", LuaValue.valueOf(it.num));
        if(it.meter > 0)
            t.set("wear", LuaValue.valueOf(it.meter));   // 0..100 %, only meaningful when > 0
        int handle = it.wdgid();
        if(handle >= 0)
            t.set("handle", LuaValue.valueOf(handle));   // server widget id → the ItemRef hafen.act.item(item, verb) takes (4f)
        if((pos != null) && !pos.isnil())
            t.set("pos", pos);
        return t;
    }

    /**
     * A character attribute {@code {base, comp}} for a name, or nil. {@link Glob#getcattr} never
     * returns {@code null} (it auto-creates a zero entry for unknown names), so a {@code base==0 &&
     * comp==0} entry is reported as nil ("not populated by the server yet").
     */
    private static LuaValue attrSnapshot(String name) {
        Glob g = glob();
        if(g == null)
            return LuaValue.NIL;
        Glob.CAttr a = g.getcattr(name);
        if((a == null) || ((a.base == 0) && (a.comp == 0)))
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("base", LuaValue.valueOf(a.base));
        t.set("comp", LuaValue.valueOf(a.comp));
        return t;
    }

    // -- study / curiosity + skills (1d-3): all-public reads off the character sheet (no haven edit) --

    /**
     * The character sheet's study widget ({@link SAttrWnd}, the "Abilities / Study Report" tab) → its
     * {@link SAttrWnd.StudyInfo} (which references the study inventory and holds the live totals), or
     * {@code null} until the sattr tab streams in (a beat after enter-world, like {@code battr}). There
     * is exactly one StudyInfo per study inventory, so the first hit is it.
     */
    private static SAttrWnd.StudyInfo studyInfo() {
        CharWnd c = charwnd();
        if((c == null) || (c.sattr == null))
            return null;
        for(SAttrWnd.StudyInfo si : c.sattr.children(SAttrWnd.StudyInfo.class))
            return si;
        return null;
    }

    /** Read a study inventory's {@link GItem} children into an array of curiosity snapshots. */
    private static LuaValue readStudySlots(Widget study) {
        LuaTable out = new LuaTable();
        if(study == null)
            return out;
        int i = 0;
        for(GItem it : study.children(GItem.class))
            out.set(++i, studySnapshot(it));
        return out;
    }

    /**
     * A study-slot snapshot: {@code res}/{@code name} (the curiosity item) plus its {@link Curiosity}
     * study profile — {@code lp} (learning points), {@code attention} (mental weight), {@code cost}
     * (experience cost), {@code time} (total study time, seconds). {@code progress} (0..1) is the item
     * meter, best-effort (present only when the client tracks it for that item). All Loading-guarded:
     * {@code res} may be the only field until the item's info resolves, then the rest fills in (which
     * surfaces as another {@code StudyChanged}). NB {@code time} is the TOTAL study time — the client
     * has no per-item countdown, so there is no true "time left".
     */
    private static LuaValue studySnapshot(GItem it) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = itemRes(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = itemName(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        try {
            Curiosity ci = ItemInfo.find(Curiosity.class, it.info());   // may throw Loading
            if(ci != null) {
                t.set("lp", LuaValue.valueOf(ci.exp));
                t.set("attention", LuaValue.valueOf(ci.mw));
                t.set("cost", LuaValue.valueOf(ci.enc));
                t.set("time", LuaValue.valueOf(ci.time));
            }
        } catch(RuntimeException e) {
            /* info still Loading — res/name may be set; the Curiosity fields arrive on a later read */
        }
        if(it.meter > 0)
            t.set("progress", LuaValue.valueOf(it.meter / 100.0));   // 0..1, best-effort (item meter)
        return t;
    }

    /** Do two study-slot arrays carry the same items/fields? (positional; for change-detection.) */
    private static boolean studySlotsEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return false;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(!luaFieldEq(ea, eb, "res") || !luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "lp")
               || !luaFieldEq(ea, eb, "attention") || !luaFieldEq(ea, eb, "cost")
               || !luaFieldEq(ea, eb, "time") || !luaFieldEq(ea, eb, "progress"))
                return false;
        }
        return true;
    }

    /**
     * Study totals from {@link SAttrWnd.StudyInfo} (recomputed live each tick): {@code lp} (total
     * learning points across the curiosities), {@code attention} (total mental weight used — compare
     * with {@code hafen.char.attr("int").comp}, the cap), {@code cost} (total experience cost). nil
     * until the study window exists.
     */
    private static LuaValue studySummary() {
        SAttrWnd.StudyInfo si = studyInfo();
        if(si == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("lp", LuaValue.valueOf(si.texp));
        t.set("attention", LuaValue.valueOf(si.tw));
        t.set("cost", LuaValue.valueOf(si.tenc));
        return t;
    }

    /** The character sheet's "Lore &amp; Skills" widget ({@link SkillWnd}), or {@code null}. */
    private static SkillWnd skillwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.skill;
    }


    /** Display name of a skill: the resource tooltip, else the internal skill token ({@code Skill.nm}). */
    private static String skillName(SkillWnd.Skill s) {
        return resTipName(s.res, s.nm);
    }

    /** Resource name (stable identity) of a skill, or {@code null} (Loading-guarded). */
    private static String skillRes(SkillWnd.Skill s) {
        return resIdent(s.res);
    }

    /**
     * The character's KNOWN skills ({@code SkillWnd.skg.csk}) as {@code {name, res}} snapshots.
     * {@code name} is always present (the resource tooltip, else the internal token); {@code res} is
     * Loading-guarded. The list is copied defensively (the {@code Group.items} reference is swapped
     * wholesale off-thread by the {@code csk}/{@code nsk} uimsgs). "Available to learn" ({@code nsk})
     * and credos/experiences are deferred.
     */
    private static LuaValue readSkills() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.csk.items)) {
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(skillName(s)));
                String res = skillRes(s);
                if(res != null)
                    t.set("res", LuaValue.valueOf(res));
                out.set(++i, t);
            }
        } catch(RuntimeException e) {
            /* skg/csk not ready or the list was swapped mid-read — return what we have */
        }
        return out;
    }

    /** Does the character KNOW a skill whose name or resource contains {@code needle}? */
    private static boolean hasSkill(String needle) {
        SkillWnd w = skillwnd();
        if(w == null)
            return false;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.csk.items)) {
                String name = skillName(s), res = skillRes(s);
                if(((name != null) && name.contains(needle)) || ((res != null) && res.contains(needle)))
                    return true;
            }
        } catch(RuntimeException e) {
            /* list swapped mid-read — treat as not found */
        }
        return false;
    }

    /**
     * The character's AVAILABLE (buyable) skills ({@code SkillWnd.skg.nsk}) as {@code {name, res, cost}}
     * snapshots — {@code cost} = the LP price to learn. The counterpart to {@link #readSkills()} (known
     * skills). Same discipline: {@code name} always present, {@code res} Loading-guarded, the list copied
     * defensively (the {@code Group.items} reference is swapped wholesale off-thread by the {@code nsk} uimsg).
     */
    private static LuaValue readAvailableSkills() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Skill s : new ArrayList<SkillWnd.Skill>(w.skg.nsk.items)) {
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(skillName(s)));
                String res = skillRes(s);
                if(res != null)
                    t.set("res", LuaValue.valueOf(res));
                t.set("cost", LuaValue.valueOf(s.cost));
                out.set(++i, t);
            }
        } catch(RuntimeException e) {
            /* nsk not ready or the list was swapped mid-read — return what we have */
        }
        return out;
    }

    /** A credo snapshot {@code {name, res}} — name from the resource tooltip (else the {@code Credo.nm} token). */
    private static LuaValue credoSnapshot(SkillWnd.Credo c) {
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(resTipName(c.res, c.nm)));
        String res = resIdent(c.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        return t;
    }

    /** A list of credos as {@code {name,res}} snapshots (defensively copied — swapped off-thread). */
    private static LuaValue readCredoList(List<SkillWnd.Credo> list) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(SkillWnd.Credo c : new ArrayList<SkillWnd.Credo>(list))
            out.set(++i, credoSnapshot(c));
        return out;
    }

    /**
     * The Credos tab ({@code SkillWnd.credos}): {@code acquired}/{@code available} (arrays of
     * {@code {name,res}}), the currently-pursued credo under {@code pursuing} ({@code {name,res,level,
     * levelTotal,quest,questTotal,questId}}, absent when none), and {@code cost} (LP to begin pursuing a
     * new credo). nil until the "Lore &amp; Skills" window exists (it streams in after enter-world).
     */
    private static LuaValue readCredos() {
        SkillWnd w = skillwnd();
        if(w == null)
            return LuaValue.NIL;
        SkillWnd.CredoGrid cg = w.credos;
        LuaTable out = new LuaTable();
        try {
            out.set("acquired", readCredoList(cg.ccr));
            out.set("available", readCredoList(cg.ncr));
            out.set("cost", LuaValue.valueOf(cg.cost));
            SkillWnd.Credo p = cg.pcr;
            if(p != null) {
                LuaTable pt = new LuaTable();
                pt.set("name", LuaValue.valueOf(resTipName(p.res, p.nm)));
                String res = resIdent(p.res);
                if(res != null)
                    pt.set("res", LuaValue.valueOf(res));
                pt.set("level",      LuaValue.valueOf(cg.pcl));
                pt.set("levelTotal", LuaValue.valueOf(cg.pclt));
                pt.set("quest",      LuaValue.valueOf(cg.pcql));
                pt.set("questTotal", LuaValue.valueOf(cg.pcqlt));
                pt.set("questId",    LuaValue.valueOf(cg.pqid));
                out.set("pursuing", pt);
            }
        } catch(RuntimeException e) {
            /* credo lists swapped mid-read — return the partial table */
        }
        return out;
    }

    /**
     * An experience/lore snapshot {@code {name, res, score, mtime}} from the Lore tab. An {@code Experience}
     * has no internal token, so {@code name} is the resource tooltip (else the resource path, else absent);
     * {@code score} = experience points, {@code mtime} = the server-supplied time field (faithful passthrough).
     */
    private static LuaValue experienceSnapshot(SkillWnd.Experience e) {
        LuaTable t = new LuaTable();
        String nm = resTipName(e.res, resIdent(e.res));
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        String res = resIdent(e.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("score", LuaValue.valueOf(e.score));
        t.set("mtime", LuaValue.valueOf(e.mtime));
        return t;
    }

    /**
     * The character's seen experiences / lore ({@code SkillWnd.exps.seen}) as {@code {name,res,score,mtime}}
     * snapshots (the "Lore" tab). Defensively copied (swapped off-thread by the {@code exps} uimsg).
     */
    private static LuaValue readExperiences() {
        LuaTable out = new LuaTable();
        SkillWnd w = skillwnd();
        if(w == null)
            return out;
        int i = 0;
        try {
            for(SkillWnd.Experience e : new ArrayList<SkillWnd.Experience>(w.exps.seen.items))
                out.set(++i, experienceSnapshot(e));
        } catch(RuntimeException ex) {
            /* seen not ready or swapped mid-read — return what we have */
        }
        return out;
    }

    // ------------------------------------------------ action bar / hotbar + equipment (1d-4)
    // NB the engine calls the action bar the "belt" (GameUI.belt / BeltSlot / setbelt) — H&H's own term;
    // the addon-facing API deliberately exposes it as `hafen.actionbar` (clearer, WoW-like). These helpers
    // are named actionbar* but read the engine's belt[] array; the two names denote the same thing.

    /**
     * An action-bar slot snapshot: {@code res} (the icon resource — stable identity), {@code name} (the
     * action's display name for a pagina slot, else the resource tooltip), and {@code cooldown} (0..1,
     * present only for a pagina action carrying a meter — e.g. an ability recharging; not seconds). Every
     * field is optional / Loading-guarded, so a slot resolving surfaces as a partial-then-full snapshot.
     *
     * <p>Two consumers: {@code slot:info()} — the one snapshot escape hatch on a {@link LuaSlot} — and the
     * {@link ActionbarAdapter}'s change detection. The per-field readers below back the Slot's
     * {@code :res()}/{@code :name()}/{@code :cooldown()}, which is why they are package-visible.
     */
    static LuaValue actionbarSnapshot(GameUI.BeltSlot s) {
        if(s == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        Resource r = actionbarResObj(s);
        if(r != null)
            t.set("res", LuaValue.valueOf(r.name));
        String name = actionbarName(s, r);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Double cd = actionbarCooldown(s);
        if(cd != null)
            t.set("cooldown", LuaValue.valueOf(cd));
        return t;
    }

    /** The icon {@link Resource} behind an action-bar slot (a {@code ResBeltSlot} item or a
     *  {@code PagBeltSlot} action), or {@code null} (Loading-guarded). */
    static Resource actionbarResObj(GameUI.BeltSlot s) {
        try {
            if(s instanceof GameUI.ResBeltSlot)
                return ((GameUI.ResBeltSlot)s).getres();
            if(s instanceof GameUI.PagBeltSlot)
                return ((GameUI.PagBeltSlot)s).pag.res();
        } catch(RuntimeException e) {   // Loading etc.
        }
        return null;
    }

    /** Display name of an action-bar slot: the pagina action's name, else the resource tooltip, else nil. */
    static String actionbarName(GameUI.BeltSlot s, Resource r) {
        if(s instanceof GameUI.PagBeltSlot) {
            try {
                return ((GameUI.PagBeltSlot)s).pag.button().name();
            } catch(RuntimeException e) {   // Loading — fall through to the tooltip
            }
        }
        if(r != null) {
            try {
                Resource.Tooltip tt = r.layer(Resource.tooltip);
                if((tt != null) && (tt.t != null))
                    return tt.t;
            } catch(RuntimeException e) {
            }
        }
        return null;
    }

    /** Cooldown/meter fraction (0..1) of a pagina action-bar slot, or {@code null} (none / Loading). */
    static Double actionbarCooldown(GameUI.BeltSlot s) {
        if(s instanceof GameUI.PagBeltSlot) {
            try {
                return ((GameUI.PagBeltSlot)s).pag.button().meter.get();   // AttrCache swallows Loading -> null
            } catch(RuntimeException e) {   // button() itself may be Loading
            }
        }
        return null;
    }

    /** Do two action-bar slot snapshots carry the same res/name? ({@code cooldown} is excluded — a live
     *  meter must not fire {@code ActionbarChanged} every frame; for change-detection only.) */
    private static boolean actionbarEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null))
            return false;
        return luaFieldEq(a, b, "res") && luaFieldEq(a, b, "name");
    }

    /**
     * Read an {@link Equipory}'s worn {@link WItem} children into an array of item snapshots, each with
     * its equipment {@code slot} index and slot {@code pos} name. Backs both {@code hafen.ui.equipment():items()}
     * and the {@code EquipChanged} change-detection. A two-slot item appears as two entries (distinct
     * {@code slot}).
     */
    static LuaValue readEquipment(Equipory eq) {
        LuaTable out = new LuaTable();
        if(eq == null)
            return out;
        int i = 0;
        for(WItem w : eq.children(WItem.class))
            out.set(++i, equipSnapshot(eq, w));
        return out;
    }

    /**
     * One worn item's snapshot: the usual {@link #itemSnapshot} plus its equipment {@code slot} index and the slot
     * name as {@code pos}. Shared by {@link #readEquipment} (the bulk read + {@code EquipChanged}) and by
     * {@code widget:items()} on an {@link Equipory} (029.3) — one shape for the worn items, wherever they are read.
     */
    static LuaValue equipSnapshot(Equipory eq, WItem w) {
        int ep = slotOf(eq, w);
        LuaValue snap = itemSnapshot(w.item, slotName(ep));
        if((ep >= 0) && snap.istable())
            ((LuaTable)snap).set("slot", LuaValue.valueOf(ep));
        return snap;
    }

    /** Do two equipment snapshots carry the same slot/res/name/num? ({@code wear} is excluded — a slow
     *  durability drift is not an equip change; positional, for change-detection.) */
    private static boolean equipEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return false;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(!luaFieldEq(ea, eb, "slot") || !luaFieldEq(ea, eb, "res")
               || !luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "num"))
                return false;
        }
        return true;
    }

    /** The live {@link Party}, or {@code null} before a session is up. */
    private static Party party() {
        Glob g = glob();
        return (g == null) ? null : g.party;
    }

    /**
     * Party members ordered by {@link Party.Member#seq} (the roster order {@code hafen.party.list} hands out;
     * the {@code "partyN"} GobRef it also used to back is gone with the hard cut — reach a member's gob with
     * {@code hafen.gob(m.id)} until Party migrates to OOP). {@code party.memb} is replaced wholesale off-thread,
     * so a {@code values()} copy is snapshot-safe (defensive catch for the rare in-flight swap).
     */
    private static List<Party.Member> partyMembers() {
        List<Party.Member> out = new ArrayList<Party.Member>();
        Party p = party();
        if(p == null)
            return out;
        try {
            out.addAll(p.memb.values());
        } catch(RuntimeException e) {
            return out;
        }
        out.sort((a, b) -> Integer.compare(a.seq, b.seq));
        return out;
    }

    /** A PartyMember snapshot: id / x,y / color / leader (there is no name for party members). */
    private static LuaValue memberSnapshot(Party.Member m) {
        if(m == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)m.gobid));
        Coord2d c = m.getc();               // live gob pos if in view, else last-known; may be null
        if(c != null) {
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
        }
        if(m.col != null)
            t.set("color", color(m.col));
        Party p = party();
        t.set("leader", LuaValue.valueOf((p != null) && (p.leader == m)));
        return t;
    }


    // ---- kin / buddy (A6: hafen.kin) -------------------------------------------------------------
    // The kin/buddy roster lives in the BuddyWnd (GameUI.buddies) — the same widget the in-client Kin tab
    // shows. Its Buddy list is mutated on the network/loader thread as the server pushes add/rm/chst/upd
    // uimsgs; BuddyWnd.iterator() copies the list under its own lock, so iterating it is snapshot-safe.
    // All our reads run on the UI thread (addon tick / REPL). online is a tri-state internally (1 online,
    // 0 offline, -1 hearth-secret-only) that we expose as a boolean (online == 1) — the common "is this
    // kin online" question; the group index maps to a fixed colour palette (BuddyWnd.gc).
    //
    // Since 020-kin-oop the Lua-facing surface is OOP and lives in LuaKin (hafen.kin() = the roster
    // collection, :get(idOrName) = an interned Kin object, gated verbs on the object). What stays HERE is the
    // plumbing LuaKin and the KinAdapter share: the buddywnd() resolve funnel, the kinSnapshot() escape
    // hatch (kin:info()) and the snapshot diff that drives KinChanged.

    /** The Kin/buddy window ({@link GameUI#buddies}), or {@code null} before the HUD/Kin window exists.
     *  The one resolve funnel: {@link LuaKin} re-reads every Kin object through it, every call (D-012). */
    static BuddyWnd buddywnd() {
        GameUI g = gui();
        return (g == null) ? null : g.buddies;
    }

    /** A kin snapshot: {@code {id, name, group, color={r,g,b,a}, online(bool)}} — {@code kin:info()}'s
     *  answer (the one snapshot escape hatch) and the change-detection input below. */
    static LuaValue kinSnapshot(BuddyWnd.Buddy b) {
        if(b == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(b.id));
        if(b.name != null)
            t.set("name", LuaValue.valueOf(b.name));
        t.set("group", LuaValue.valueOf(b.group));
        if((b.group >= 0) && (b.group < BuddyWnd.gc.length))
            t.set("color", color(BuddyWnd.gc[b.group]));
        t.set("online", LuaValue.valueOf(b.online == 1));
        return t;
    }

    /** The whole roster as snapshots, in the window's current sort order — the {@link KinAdapter}'s
     *  change-detection input (never a Lua-facing list any more: Lua sees Kin objects, {@link LuaKin}). */
    private static LuaValue kinSnapshotList() {
        LuaTable out = new LuaTable();
        BuddyWnd bw = buddywnd();
        if(bw == null)
            return out;
        int i = 0;
        for(BuddyWnd.Buddy b : bw)                 // iterator() copies under the BuddyWnd's own lock
            out.set(++i, kinSnapshot(b));
        return out;
    }

    /** The buddy ids of a snapshot list, in roster order — what {@link AddonManager#fireKin} mints the
     *  per-addon {@code KinChanged} payload from (the snapshots themselves never reach Lua as an event). */
    private static int[] kinIds(LuaValue list) {
        int n = list.length();
        int[] ids = new int[n];
        for(int i = 0; i < n; i++)
            ids[i] = list.get(i + 1).get("id").toint();
        return ids;
    }

    /** Do two kin snapshot lists carry the same id/name/group/online per entry? (change-detection.) */
    private static boolean kinListEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return a == b;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(ea.get("id").toint() != eb.get("id").toint())
                return false;
            if(!ea.get("name").tojstring().equals(eb.get("name").tojstring()))
                return false;
            if(ea.get("group").toint() != eb.get("group").toint())
                return false;
            if(ea.get("online").toboolean() != eb.get("online").toboolean())
                return false;
        }
        return true;
    }

    // ---- quest log (A9: hafen.quests) ------------------------------------------------------------
    // The quest log is a QuestWnd (@RName("quests")) — the character sheet's "Quest Log" tab, held by the
    // public CharWnd.quest field (created hidden at login but live, so quests read without opening it). It
    // keeps two lists: cqst (the "Current" tab: pending/disabled quests) and dqst (the "Completed" tab:
    // done/failed). Each Quest carries {id, res (Indir<Resource>), title (may be null), done (a status
    // int), mtime}. The conditions/objectives of a quest are loaded only for the one the player has SELECTED
    // (QuestWnd.quest, a Quest.Box with a Condition[]) — a faithful client limitation (like A8's no per-item
    // countdown), so selected() is the only place conds appear. All backings are public (QuestWnd.cqst/dqst/
    // quest, QuestList.quests/get, Quest.id/res/title/done/mtime, Quest.Box.id/cond, Quest.Condition.desc/
    // done/status) → zero haven edit, like A8/A7/A6/A4/A2. The status ints (QST_PEND/DONE/FAIL/DISABLED) are
    // compile-time constants → inlined, so the status helpers do NOT load Quest (whose <clinit> renders text
    // and would fail headless) — they stay headless-testable.
    //
    // Threading: the quest lists (and the selected box's cond[]) are mutated on a Loader thread by
    // QuestWnd.uimsg("quests")/Box.uimsg("conds") under synchronized(ui). So we copy the list/array refs
    // under the ui monitor, then build the Lua snapshots outside it (res.get() may Loading) — the marker
    // "copy under the lock, snapshot outside it" discipline (A1/A8).

    /** The Quest Log window (the character sheet's "Quest Log" tab — created hidden at login but live),
     *  or {@code null} before it exists. Via the public {@code CharWnd.quest} field (no tree-walk). */
    private static QuestWnd questwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.quest;
    }

    /** {@code hafen.quests.list([filter])} — every quest (Current + Completed) as {@code {id, name, res,
     *  status, mtime}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue questList(LuaValue filter) {
        LuaTable out = new LuaTable();
        QuestWnd qw = questwnd();
        UI u = ui;
        if((qw == null) || (u == null))
            return out;
        List<QuestWnd.Quest> all = new ArrayList<QuestWnd.Quest>();
        synchronized(u) {                            // the quest lists mutate off-thread (QuestWnd.uimsg)
            all.addAll(qw.cqst.quests);              // "Current" tab (pending / disabled)
            all.addAll(qw.dqst.quests);              // "Completed" tab (done / failed)
        }
        int i = 0;
        for(QuestWnd.Quest q : all) {                // resolve names outside the lock (res.get() may Loading)
            LuaValue snap = questSnapshot(q);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** {@code hafen.quests.selected()} — the quest currently open in the log (the only one whose conditions
     *  the client loads), as a list snapshot plus {@code conds={{desc, status, text?}}}, or {@code nil}. */
    private static LuaValue questSelected() {
        QuestWnd qw = questwnd();
        UI u = ui;
        if((qw == null) || (u == null))
            return LuaValue.NIL;
        QuestWnd.Quest q;
        QuestWnd.Quest.Condition[] conds;
        synchronized(u) {                            // qw.quest / box.cond are swapped off-thread (uimsg)
            QuestWnd.Quest.Info info = qw.quest;     // the selected quest's Box, or null (nothing selected)
            if(!(info instanceof QuestWnd.Quest.Box))
                return LuaValue.NIL;
            QuestWnd.Quest.Box box = (QuestWnd.Quest.Box)info;
            conds = box.cond;                        // Condition[] (swapped wholesale on the "conds" uimsg)
            q = qw.cqst.get(box.id);                 // the matching Quest (for status/mtime) in either tab
            if(q == null)
                q = qw.dqst.get(box.id);
        }
        if(q == null)
            return LuaValue.NIL;                     // selected id not in either list (shouldn't happen)
        LuaTable t = (LuaTable)questSnapshot(q);
        t.set("conds", questConds(conds));
        return t;
    }

    /** One quest as {@code {id, name, res, status, mtime}}. {@code name} = the quest title (the explicit
     *  title, else the resource tooltip); {@code res} = the stable resource id. Loading-guarded. */
    private static LuaValue questSnapshot(QuestWnd.Quest q) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(q.id));
        // Quest.title() prefers the explicit title over the tooltip; mirror it (both Loading-guarded).
        String name = (q.title != null) ? q.title : resTipName(q.res, null);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        String res = resIdent(q.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        t.set("status", LuaValue.valueOf(questStatus(q.done)));
        t.set("mtime", LuaValue.valueOf(q.mtime));
        return t;
    }

    /** The selected quest's conditions as a 1-based array of {@code {desc, status, text?}}. */
    private static LuaValue questConds(QuestWnd.Quest.Condition[] cond) {
        LuaTable out = new LuaTable();
        if(cond == null)
            return out;
        int i = 0;
        for(QuestWnd.Quest.Condition c : cond)
            out.set(++i, questCond(c));
        return out;
    }

    /** One condition as {@code {desc, status ("pending"/"done"/"failed"), text?}}. {@code text} = the
     *  condition's extra status string (absent when none). */
    private static LuaValue questCond(QuestWnd.Quest.Condition c) {
        LuaTable t = new LuaTable();
        if(c.desc != null)
            t.set("desc", LuaValue.valueOf(c.desc));
        t.set("status", LuaValue.valueOf(questCondStatus(c.done)));
        if(c.status != null)
            t.set("text", LuaValue.valueOf(c.status));
        return t;
    }

    /** Is a quest status "active" (shown in the Quest Log's Current tab)? — pending or disabled. */
    private static boolean questActive(int done) {
        return (done == QuestWnd.Quest.QST_PEND) || (done == QuestWnd.Quest.QST_DISABLED);
    }

    /** The API status string for a {@code Quest.done} code (QST_PEND/DONE/FAIL/DISABLED). */
    private static String questStatus(int done) {
        if(done == QuestWnd.Quest.QST_DONE)     return "done";
        if(done == QuestWnd.Quest.QST_FAIL)     return "failed";
        if(done == QuestWnd.Quest.QST_DISABLED) return "disabled";
        return "pending";                            // QST_PEND (and any unexpected code)
    }

    /** The API status string for a condition's {@code done} code (0=pending, 1=done, 2=failed). */
    private static String questCondStatus(int done) {
        if(done == QuestWnd.Quest.QST_DONE) return "done";
        if(done == QuestWnd.Quest.QST_FAIL) return "failed";
        return "pending";
    }

    // ---- wounds (A9-2: hafen.wounds) -------------------------------------------------------------
    // Wounds are a WoundWnd (@RName("wounds")) — the character sheet's "Health & Wounds" tab, held by the
    // public CharWnd.wound field (created hidden at login but live, so wounds read without opening it). The
    // window keeps a WoundList whose public List<Wound> is the flat set of wounds; the client renders it as
    // a TREE (Wound.parentid links a complication to its parent wound, -1 = a root; Wound.level = the depth
    // the WoundList's treesort computes for indentation). Each Wound carries {id, parentid (public final
    // int), res (Indir<Resource>), level (public int)} and, from its resource-published ItemInfo, a display
    // name (ItemInfo.Name) and a severity indicator (the highest-priority WoundWnd.QuickInfo's qstr() — the
    // magnitude the client shows beside the wound; content-defined, usually a number, NOT seconds). All
    // backings are public (WoundWnd.wounds, WoundList.wounds, Wound.id/parentid/res/level/info(),
    // WoundWnd.QuickInfo.qstr/qprio) → zero haven edit, like A9-1/A8/A7/A6/A4/A2.
    //
    // Threading: the wound list is mutated on a Loader thread by WoundWnd.uimsg("wounds") (decwound adds /
    // updates / removes) under synchronized(ui), and reassigned by WoundList.tick's treesort on the UI
    // thread. So copyWounds() copies the list reference under the ui monitor (the marker "copy under the
    // lock, snapshot outside it" discipline), then names/severity resolve outside it (res.get()/info() may
    // Loading — guarded). WoundChanged is fired by the poll-driven WoundAdapter (severity streams in a beat
    // after the wound row, like study's Curiosity info, so a per-tick snapshot diff catches it) — not a
    // targeted uimsg, since a uimsg refresh would see severity still Loading and miss it.

    /** The Health &amp; Wounds window (the character sheet's "Health & Wounds" tab — created hidden at login
     *  but live), or {@code null} before it exists. Via the public {@code CharWnd.wound} field (no tree-walk). */
    private static WoundWnd woundwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.wound;
    }

    /** The live wound list copied under the {@code ui} monitor (WoundWnd.uimsg mutates it off-thread), or
     *  empty when the character sheet's wound tab isn't up yet. Snapshot the copies outside the lock. */
    private static List<WoundWnd.Wound> copyWounds() {
        List<WoundWnd.Wound> out = new ArrayList<WoundWnd.Wound>();
        WoundWnd ww = woundwnd();
        UI u = ui;
        if((ww == null) || (u == null))
            return out;
        synchronized(u) {
            out.addAll(ww.wounds.wounds);
        }
        return out;
    }

    /** {@code hafen.wounds.list([filter])} — every wound as {@code {id, name, res, severity, parentid,
     *  level}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue woundList(LuaValue filter) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(WoundWnd.Wound w : copyWounds()) {        // resolve names/severity outside the lock (may Loading)
            LuaValue snap = woundSnapshot(w);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** One wound as {@code {id, name, res, severity, parentid, level}}. {@code name}/{@code res}/{@code
     *  severity} are Loading-guarded (omitted while resolving); {@code id}/{@code parentid}/{@code level}
     *  are plain public ints. */
    private static LuaValue woundSnapshot(WoundWnd.Wound w) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(w.id));
        String name = woundName(w);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        String res = resIdent(w.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String sev = woundSeverity(w);
        if(sev != null)
            t.set("severity", LuaValue.valueOf(sev));
        t.set("parentid", LuaValue.valueOf(w.parentid));
        t.set("level", LuaValue.valueOf(w.level));
        return t;
    }

    /** Display name of a wound: the resource tooltip, else the server-pushed {@code ItemInfo.Name}, else
     *  {@code null} (Loading-guarded — like {@code buffName}). */
    private static String woundName(WoundWnd.Wound w) {
        String tip = resTipName(w.res, null);
        if(tip != null)
            return tip;
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, w.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /**
     * The severity indicator the client shows beside a wound — its highest-priority {@link
     * WoundWnd.QuickInfo}'s {@code qstr()} (a content-defined string, usually the wound's magnitude
     * number; <b>not</b> seconds), or {@code null} if the wound publishes none / is still Loading. Mirrors
     * the client's own quick-info pick ({@code WoundWnd.WoundList.Item.getqdat}: the highest {@code qprio}).
     */
    private static String woundSeverity(WoundWnd.Wound w) {
        try {
            List<ItemInfo> info = w.info();           // may throw Loading
            WoundWnd.QuickInfo best = null;
            for(ItemInfo inf : info) {
                if(inf instanceof WoundWnd.QuickInfo) {
                    WoundWnd.QuickInfo qi = (WoundWnd.QuickInfo)inf;
                    if((best == null) || (best.qprio() < qi.qprio()))
                        best = qi;
                }
            }
            return (best == null) ? null : best.qstr();   // qstr() itself may be null (no quick string)
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /** Do two wound snapshot lists carry the same id/parentid/level/name/res/severity per entry? (change-
     *  detection for {@code WoundChanged}, mirroring {@code kinListEqual}). */
    private static boolean woundListEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null) || !a.istable() || !b.istable())
            return a == b;
        int n = a.length();
        if(n != b.length())
            return false;
        for(int i = 1; i <= n; i++) {
            LuaValue ea = a.get(i), eb = b.get(i);
            if(ea.get("id").toint() != eb.get("id").toint())
                return false;
            if(ea.get("parentid").toint() != eb.get("parentid").toint())
                return false;
            if(ea.get("level").toint() != eb.get("level").toint())
                return false;
            if(!luaFieldEq(ea, eb, "name") || !luaFieldEq(ea, eb, "res") || !luaFieldEq(ea, eb, "severity"))
                return false;
        }
        return true;
    }

    // ---- combat schools (A10: hafen.fight) -------------------------------------------------------
    // The combat-school / maneuver-deck builder is a FightWnd (@RName("fmg")) — the character sheet's
    // "Martial Arts & Combat Schools" tab, held by the public CharWnd.fight field (created hidden at login
    // but live, so it reads without opening the window, exactly like A9's quests/wounds). This is the
    // OUT-OF-COMBAT configuration surface, distinct from the in-combat Fightview/Fightsess deck (which has
    // live rtime cooldowns and is the separate hafen.combat.* view). It keeps three data structures:
    //   • acts   — public List<Action>: every maneuver/attack you know. Each Action {res (public Indir<
    //              Resource>), a (public int = how many you can slot), u (public int = how many slotted)}.
    //   • order  — public final Action[]: the current school's card LAYOUT, index i → the maneuver bound to
    //              key FightWnd.keys[i] ("1".."5","⇧1".."⇧5"); a null entry is an empty slot.
    //   • saves[] + usesave/nsave/maxact — the saved schools (names in the PRIVATE saves[], so deferred) plus
    //              the active slot (usesave), slot count (nsave) and the action-point budget cap (maxact).
    // All the fields we read are public → zero haven edit (like A9/A8/A7/A6/A4/A2). Read-only; editing/
    // switching schools (wdgmsg load/save/use, drag, set counts) is the gated Phase-4 tier.
    //
    // Threading: the FightWnd.uimsg handlers run on a Loader thread under synchronized(ui): "avail" REPLACES
    // acts wholesale, "used"/"max" mutate act.u / maxact / order[] entries, and Actions.tick re-sorts acts on
    // the UI thread. So — the marker discipline — we copy the acts list / order[] array and read the scalars
    // under the ui monitor, then resolve resource names OUTSIDE the lock (res.get() may Loading). The public
    // int reads (a/u/maxact/usesave) outside the lock are snapshot-atomic like A9-2's wound ints.

    /** The Combat Schools window (the character sheet's "Martial Arts & Combat Schools" tab — created hidden
     *  at login but live), or {@code null} before it exists. Via the public {@code CharWnd.fight} field. */
    private static FightWnd fightwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.fight;
    }

    /** {@code hafen.fight.maneuvers([filter])} — every known combat maneuver/attack as {@code {res, name,
     *  avail, used}} snapshots, filtered by the canonical nil=all / name-substring / predicate. */
    private static LuaValue fightManeuvers(LuaValue filter) {
        LuaTable out = new LuaTable();
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return out;
        List<FightWnd.Action> acts = new ArrayList<FightWnd.Action>();
        synchronized(u) {                          // acts is swapped wholesale off-thread (the "avail" uimsg)
            acts.addAll(fw.acts);
        }
        int i = 0;
        for(FightWnd.Action a : acts) {            // resolve names outside the lock (res.get() may Loading)
            LuaValue snap = maneuverSnapshot(a);
            if(matches(filter, snap))
                out.set(++i, snap);
        }
        return out;
    }

    /** One maneuver as {@code {res, name, avail, used}}. {@code res}/{@code name} are Loading-guarded;
     *  {@code avail} ({@code Action.a}) / {@code used} ({@code Action.u}) are plain public ints. */
    private static LuaValue maneuverSnapshot(FightWnd.Action a) {
        LuaTable t = new LuaTable();
        String res = resIdent(a.res);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = resTipName(a.res, res);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("avail", LuaValue.valueOf(a.a));
        t.set("used", LuaValue.valueOf(a.u));
        return t;
    }

    /** {@code hafen.fight.deck()} — the current school's configured card layout: the filled {@code order[]}
     *  slots in key order, each {@code {slot, key, res, name, used}}. Empty deck slots are omitted. */
    private static LuaValue fightDeck() {
        LuaTable out = new LuaTable();
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return out;
        FightWnd.Action[] order;
        synchronized(u) {                          // order[] entries are reassigned off-thread (the "used" uimsg)
            order = java.util.Arrays.copyOf(fw.order, fw.order.length);
        }
        int i = 0;
        for(int slot = 0; slot < order.length; slot++) {
            FightWnd.Action a = order[slot];
            if(a == null)
                continue;                          // an empty deck slot — omit (slot/key convey position)
            LuaTable t = new LuaTable();
            t.set("slot", LuaValue.valueOf(slot));
            t.set("key", LuaValue.valueOf(deckKey(slot)));
            String res = resIdent(a.res);          // resolved outside the lock (may Loading)
            if(res != null)
                t.set("res", LuaValue.valueOf(res));
            String name = resTipName(a.res, res);
            if(name != null)
                t.set("name", LuaValue.valueOf(name));
            t.set("used", LuaValue.valueOf(a.u));
            out.set(++i, t);
        }
        return out;
    }

    /** The hotkey label for deck slot {@code slot} (the game's own {@code FightWnd.keys}: "1".."5",
     *  "⇧1".."⇧5"), or a 1-based fallback if the deck is larger than the key table. */
    private static String deckKey(int slot) {
        String[] keys = FightWnd.keys;
        if((keys != null) && (slot >= 0) && (slot < keys.length) && (keys[slot] != null))
            return keys[slot];
        return String.valueOf(slot + 1);
    }

    /** {@code hafen.fight.summary()} — the scalars {@code {maxact, used, nact, nsave, usesave}}, or
     *  {@code nil} before the Combat Schools tab exists. {@code used} = the total action points spent
     *  (sum of every maneuver's {@code u}), mirroring the window's own "Used: u/maxact" count. */
    private static LuaValue fightSummary() {
        FightWnd fw = fightwnd();
        UI u = ui;
        if((fw == null) || (u == null))
            return LuaValue.NIL;
        int maxact, usesave, nsave, nact, used;
        synchronized(u) {                          // acts/order/maxact all mutate off-thread — read under the lock
            maxact = fw.maxact;
            usesave = fw.usesave;
            nsave = fw.nsave;
            nact = fw.order.length;
            used = 0;
            for(FightWnd.Action a : fw.acts)
                used += a.u;
        }
        LuaTable t = new LuaTable();
        t.set("maxact", LuaValue.valueOf(maxact));
        t.set("used", LuaValue.valueOf(used));
        t.set("nact", LuaValue.valueOf(nact));
        t.set("nsave", LuaValue.valueOf(nsave));
        t.set("usesave", LuaValue.valueOf(usesave));
        return t;
    }

}
