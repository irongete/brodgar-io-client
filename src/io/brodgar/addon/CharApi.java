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
     *
     * <p>The payload is the <b>Food object</b> ({@link AddonManager#fireFood}), so a handler reads it with
     * the same verbs as {@code hafen.char():food()} and a stashed payload goes on tracking the meal after
     * it. The reads themselves live on {@link LuaFood}; only firing is this adapter's business.
     */
    private static final class FepAdapter implements TreeAdapter {
        public boolean interested(Widget w, String msg) {
            return (w instanceof BAttrWnd) && ("food".equals(msg) || "glut".equals(msg));
        }

        public void refresh() {
            BAttrWnd w = battrwnd();
            if(w != null)
                fireFood(w);
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
     *
     * <p>The payload is an array of <b>StudySlot objects</b> ({@link AddonManager#fireStudy}), so a handler
     * reads it with the same verbs as {@code hafen.study():slot():list()}. The snapshots stay, purely as the
     * diff KEY: an interned object compares by identity and so cannot detect a slot's numbers resolving,
     * which is most of what this adapter exists to notice. They never reach Lua — {@code slot:info()} is
     * that, on demand.
     */
    private static final class StudyAdapter implements TreeAdapter {
        private LuaValue cache;   // last study-slots snapshot (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // study changes are structural / streamed, not a targeted uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            if(studyInfo() == null)
                return;           // study window not up yet — keep the cache, fire nothing
            List<GItem> its = LuaStudySlot.items();
            LuaTable snap = new LuaTable();
            int i = 0;
            for(GItem it : its)
                snap.set(++i, LuaStudySlot.snapshot(it));
            if(!studySlotsEqual(snap, cache)) {
                cache = snap;
                fireStudy(its);
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
     * in a beat after each item appears, so this is <b>poll-driven</b>: each tick it re-reads the worn set
     * and fires {@code EquipChanged} when it differs. Change-detection compares what each item is, how many
     * and which slots it fills — not {@code wear} or quality (a durability drifting down and a tooltip
     * resolving are not equipment changes; both are read live off the items themselves).
     *
     * <p>The payload is an array of <b>Item objects</b> ({@link AddonManager#fireEquip}), the same objects
     * {@code hafen.ui():equipment():items()} hands back, so a handler reads it with the item verbs and a
     * stashed payload goes on answering after the gear comes off.
     */
    private static final class EquipAdapter implements TreeAdapter {
        private String cache;     // last worn-set key (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return false;         // equip/unequip is a widget create/cdestroy, not a uimsg — see poll()
        }

        public void refresh() {}

        public void poll() {
            Equipory eq = equipory();
            if(eq == null)
                return;           // equipory not up yet — keep the cache, fire nothing
            String key = equipKey(eq);
            if(!key.equals(cache)) {
                cache = key;
                fireEquip(LuaItem.items(eq));
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
     * quest history doesn't spam events.
     *
     * <p>The payload is the <b>Quest object</b> ({@link AddonManager#fireQuest}), so a handler reads it with
     * the same verbs as {@code hafen.quest():get(id)}. That matters more here than anywhere else in the API:
     * {@code QuestDone} fires <i>as</i> the status changes, and a snapshot froze the very field the event is
     * about — a stashed Quest goes on answering {@code :status()} afterwards. The {@code id -> done} cache
     * stays, purely as the diff KEY: an interned object compares by identity and so cannot detect a status
     * advancing, which is the whole of what this adapter exists to notice.
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
            for(QuestWnd.Quest q : all)
                fresh.put(q.id, q.done);
            for(Object[] ev : questDiff(cache, fresh))            // pure diff (also updates the cache)
                fireQuest((String)ev[0], ((Integer)ev[1]).intValue());
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
     * is skipped (the cache is kept), so nothing fires before there is anything to read.
     *
     * <p>The payload is an array of <b>Wound objects</b> ({@link AddonManager#fireWounds}), so a handler
     * reads it with the same verbs as {@code hafen.wound():list()} and can key a table by one. The snapshots
     * stay as the diff KEY only — an interned object compares by identity, and a severity resolving or a
     * wound worsening is precisely the change identity cannot see. Read the initial state with
     * {@code hafen.wound():list()}; listen for deltas after.
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
            LuaValue snap = LuaWound.snapshotList();
            if(!woundListEqual(snap, cache)) {
                cache = snap;
                fireWounds(LuaWound.ids());
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

    /** The character sheet's Base-Attributes widget ({@code CharWnd.battr}), or {@code null}. The one
     *  resolve funnel {@link LuaFood} re-reads through, every call (D-012). */
    static BAttrWnd battrwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.battr;
    }

    /**
     * Build {@code hafen.player()} for {@code owner}. From installHafen. The section contains exactly one thing,
     * so the <b>section object IS that thing</b> (§2.1): {@code hafen.player()} hands back the addon's single
     * <b>Player object</b>, and {@code hafen.player():gob()} is the composition anchor for every per-gob read of
     * the player (position/health/moving/facing/…). Player forwards <b>nothing</b> — a {@code player:pos()}
     * living beside {@code player:gob():position()} is exactly the dual style D-013 forbids — and
     * {@code exists}/{@code id} are dropped: {@code player:gob()} (nil before entering the world) and
     * {@code gob:id()} already answer both. It is a per-addon singleton (cached on {@link Addon#playerObj}), so
     * {@code hafen.player() == hafen.player()}; it is userdata with a per-addon metatable, immutable from Lua,
     * like a {@link LuaGob}.
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
        // worldToScreen(p) — project a PLACE IN THE WORLD to a MAP-VIEW pixel. It takes a Position (§2.7) and
        // answers a plain {x, y} in px, which is deliberately NOT one: the two spaces have the same shape and
        // used to be the same type, so a widget's pixel position walked the character somewhere wrong instead
        // of failing. Now only the direction that has an answer type-checks.
        methods.set("worldToScreen", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Coord2d rc = LuaPosition.worldArg(a, 2, "hafen.player():worldToScreen", "p");
                MapView m = view;
                if(m == null)
                    return LuaValue.NIL;
                try {
                    Coord3f sc = m.screenxf(rc);
                    return (sc == null) ? LuaValue.NIL : xy(sc.x, sc.y);
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        final LuaTable pmt = new LuaTable();
        // A section object's vocabulary is CLOSED: an unknown verb throws naming what does exist, exactly as
        // Section.meta and LuaCollection do for every other section. Pointing __index straight at the methods
        // table would make hafen.player():nosuchverb() read plain nil and fail one character later as "attempt
        // to call a nil value" — the failure the whole grammar exists to delete, and the one Player would have
        // been alone in keeping, since the section object here IS the one thing the section contains (§2.1).
        pmt.set(LuaValue.INDEX, Retired.closedIndex("hafen.player()", methods,
            "the section object is the character itself: :gob() :name() :worldToScreen(p)"));
        pmt.set("__name", LuaValue.valueOf("Player"));
        pmt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Player");
            }
        });
        owner.playerObj = LuaValue.userdataOf(new PlayerMark(), pmt);
        Section.mount(hafen, "player", owner.playerObj, null);
    }

    /** The opaque instance behind a Player userdata (facade-safe: no Java object of the engine's crosses). */
    private static final class PlayerMark {
        public String toString() { return "Player"; }
    }

    // hafen.items is a HARD CUT (029.3, D-013). In Hafen there is no inventory model outside the widget tree —
    // GameUI.maininv is an Inventory exactly like a chest's — so a section of its own only preserved the
    // player-inventory privilege the Widget entity removes. Items are now a RELATION on their container:
    // hafen.ui():inventory():items() / hafen.ui():equipment():items() / hafen.ui():hand(), and :items() answers on ANY
    // container widget (a chest, a cupboard, another player's equipory) with nothing hidden. `find` had no
    // replacement built for it: it was a name/res substring filter over one array, which is a Lua one-liner over
    // :items(). What a container hands back is the Item entity ({@link LuaItem}), keyed on the item widget.

    /**
     * Build {@code hafen.char()} for {@code owner}. From installHafen.
     *
     * <p><b>Four collections and three scalars</b> (spec {@code 039-uniform-api} §2.3/§4.1). The flat
     * singular/plural pairs — {@code attr}/{@code attrs}, {@code skills}/{@code skill},
     * {@code credos}, {@code experiences} — become the collections {@code :attr()}, {@code :skill()},
     * {@code :credo()} and {@code :experience()}: the noun names the kind and the verb says how many, so
     * {@code :attr():get("str")} is one and {@code :attr():list()} is all. {@code skillsAvailable()} was a
     * second accessor for a sub-list and is now {@code :skill():available()}, a verb on the collection it
     * belongs to. Each collection is minted <b>once</b> and handed back by identity, as the section object
     * is — a panel that reads the sheet every frame must allocate nothing to do it.
     *
     * <p>{@code :lp()} and {@code :weight()} stay plain scalar reads: they are one number each and there is
     * nothing to address into. {@code :food()} hands back the interned {@link LuaFood}, or {@code nil}
     * until the base-attributes tab exists.
     */
    static void installChar(LuaTable hafen, final Addon owner) {
        final LuaValue attrs = LuaAttr.collection(owner);
        final LuaValue skills = LuaSkill.collection(owner);
        final LuaValue credos = LuaCredo.collection(owner);
        final LuaValue exps = LuaExperience.collection(owner);
        LuaTable chr = new LuaTable();
        chr.set("attr", collection("attr", attrs));
        chr.set("skill", collection("skill", skills));
        chr.set("credo", collection("credo", credos));
        chr.set("experience", collection("experience", exps));
        // lp() — the learning points the character has banked (CharWnd.exp), nil before the sheet exists.
        chr.set("lp", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "lp");
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.exp);
            }
        });
        // weight() — what the character is carrying (CharWnd.enc), nil before the sheet exists.
        chr.set("weight", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "weight");
                CharWnd c = charwnd();
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.enc);
            }
        });
        // food() — the Food object: FEP and hunger, the one place absolute character numbers exist. nil
        // until the base-attributes tab streams in. Subscribe to FepChanged for updates.
        chr.set("food", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "food");
                return LuaFood.of(owner, battrwnd());
            }
        });
        Section.install(hafen, "char", chr);
    }

    /** One collection accessor on a section object: a colon call, no arguments, the collection back. */
    private static LuaValue collection(final String nm, final LuaValue coll) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), sectionOf(nm), nm);
                if(Args.passed(a, 2))
                    throw new LuaError("hafen." + sectionOf(nm) + "():" + nm + "() takes no arguments — it"
                        + " IS the collection, and :list(filter) / :find(filter) search it");
                return coll;
            }
        };
    }

    /** Which section a collection accessor lives on ({@code slot} is study's; everything else is char's). */
    private static String sectionOf(String nm) {
        return "slot".equals(nm) ? "study" : "char";
    }

    /**
     * Build {@code hafen.study()} for {@code owner}. From installHafen. The window's contents become the
     * {@link LuaStudySlot} collection {@code :slot()} (§4.2); {@code :summary()} stays a scalar read,
     * because the three totals are one value and there is nothing to address into.
     */
    static void installStudy(LuaTable hafen, final Addon owner) {
        final LuaValue slots = LuaStudySlot.collection(owner);
        LuaTable study = new LuaTable();
        study.set("slot", collection("slot", slots));
        // summary() — the live totals {lp, attention, cost} across the slots, nil before the window is up.
        study.set("summary", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "study", "summary");
                return studySummary();
            }
        });
        Section.install(hafen, "study", study);
    }

    /**
     * Install {@code hafen.party} for owner. From installHafen. <b>The section object IS the roster</b> (uniform
     * grammar §2.1): {@code hafen.party()} is the {@link LuaPartyMember} collection, one member is
     * {@code hafen.party():get(gobId)} and {@code :leader()} is the distinguished member (R8) rather than a
     * second accessor. Every member hands back a live Gob through {@code member:gob()}, which is the read the
     * roster never had.
     */
    static void installParty(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "party", LuaPartyMember.collection(owner), null);
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

    /**
     * Install {@code hafen.quest} for owner. From installHafen. <b>The section object IS the log</b> (uniform
     * grammar §2.1): {@code hafen.quest()} is the {@link LuaQuest} collection over both tabs, one quest is
     * {@code hafen.quest():get(id)} and {@code :selected()} is the distinguished member (R8) — the quest the
     * player has open, and the only one whose objectives the client is sent. The section is the SINGULAR name
     * (§2.3): the noun says the kind and the verb says how many.
     */
    static void installQuest(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "quest", LuaQuest.collection(owner), null);
    }

    /**
     * Install {@code hafen.wound} for owner. From installHafen. <b>The section object IS the wound list</b>
     * (uniform grammar §2.1): {@code hafen.wound()} is the {@link LuaWound} collection in the window's own
     * tree order, and the old presence test is {@code hafen.wound():find(needle)} — which hands back the
     * Wound rather than a boolean, and is still truthy where the boolean was. Singular, like every other
     * section (§2.3).
     */
    static void installWound(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "wound", LuaWound.collection(owner), null);
    }

    /**
     * Build {@code hafen.fight()} for {@code owner}. From installHafen. Three projections of the combat-schools
     * tab plus one read of the live combat view: {@code :maneuver()} is the collection of what you know,
     * {@code :deck()} the loaded school's layout as a plain array (§2.3 — a layout is addressed by its own
     * order), {@code :summary()} the scalars around it, and {@code :target()} who you are fighting.
     */
    static void installFight(LuaTable hafen, final Addon owner) {
        final LuaValue maneuvers = LuaManeuver.collection(owner);
        LuaTable fight = new LuaTable();
        // maneuver() — every maneuver and attack you know, minted once and handed back by identity.
        fight.set("maneuver", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "maneuver");
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.fight():maneuver() takes no arguments — it IS the collection,"
                        + " and :list(filter) / :find(filter) search it");
                return maneuvers;
            }
        });
        // deck() — the filled hotkey slots of the loaded school, in key order. A plain array, never nil.
        fight.set("deck", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "deck");
                return LuaDeckCard.deck(owner);
            }
        });
        // summary() — the action-point budget and the saved-school slots, nil before the tab is built.
        fight.set("summary", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "summary");
                return LuaFightSummary.of(owner, fightwnd());
            }
        });
        // target() — who you are fighting, nil out of combat. An Opponent, whose :gob() is the creature.
        fight.set("target", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "target");
                return LuaOpponent.target(owner);
            }
        });
        Section.install(hafen, "fight", fight);
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


    /** The human-readable equipment slot name for an ep index, or nil. */
    static LuaValue slotName(int ep) {
        if((ep >= 0) && (ep < Equipory.etts.length) && (Equipory.etts[ep] != null))
            return LuaValue.valueOf(Equipory.etts[ep].text);
        return LuaValue.NIL;
    }

    /** {@code ItemInfo.Name} display text for an item, or {@code null} (Loading-guarded). */
    static String itemNameOf(GItem it) {
        try {
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, it.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** Resource name (stable identity) for an item, or {@code null} (Loading-guarded). */
    static String itemResOf(GItem it) {
        try {
            Resource r = it.res.get();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }


    // -- study / curiosity + skills (1d-3): all-public reads off the character sheet (no haven edit) --

    /**
     * The character sheet's study widget ({@link SAttrWnd}, the "Abilities / Study Report" tab) → its
     * {@link SAttrWnd.StudyInfo} (which references the study inventory and holds the live totals), or
     * {@code null} until the sattr tab streams in (a beat after enter-world, like {@code battr}). There
     * is exactly one StudyInfo per study inventory, so the first hit is it.
     */
    static SAttrWnd.StudyInfo studyInfo() {
        CharWnd c = charwnd();
        if((c == null) || (c.sattr == null))
            return null;
        for(SAttrWnd.StudyInfo si : c.sattr.children(SAttrWnd.StudyInfo.class))
            return si;
        return null;
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
    static SkillWnd skillwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.skill;
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
     * The {@link Equipory}'s worn items as ONE change-detection key — what each item is, how many, and which
     * slots it fills, joined in the window's own order. Not a snapshot array: with the items interned on their
     * widgets, what the event hands over is objects ({@link LuaItem}), and comparing objects by identity cannot
     * see a slot's numbers changing. So the diff keeps a string and the payload keeps the objects.
     */
    static String equipKey(Equipory eq) {
        if(eq == null)
            return null;
        StringBuilder sb = new StringBuilder();
        for(GItem it : LuaItem.items(eq))
            sb.append(LuaItem.equipKey(it)).append("\n--\n");
        return sb.toString();
    }

    /** The live {@link Party}, or {@code null} before a session is up. Read by {@link LuaPartyMember}. */
    static Party party() {
        Glob g = glob();
        return (g == null) ? null : g.party;
    }

    /**
     * Party members ordered by {@link Party.Member#seq} — the roster order {@code hafen.party():list()} hands
     * out. {@code party.memb} is replaced wholesale off-thread, so a {@code values()} copy is snapshot-safe
     * (defensive catch for the rare in-flight swap).
     */
    static List<Party.Member> partyMembers() {
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

    // ---- quest log (A9: hafen.quest) -------------------------------------------------------------
    // The quest log is a QuestWnd (@RName("quests")) — the character sheet's "Quest Log" tab, held by the
    // public CharWnd.quest field (created hidden at login but live, so quests read without opening it). It
    // keeps two lists: cqst (the "Current" tab: pending/disabled quests) and dqst (the "Completed" tab:
    // done/failed). Each Quest carries {id, res (Indir<Resource>), title (may be null), done (a status
    // int), mtime}. The conditions/objectives of a quest are loaded only for the one the player has SELECTED
    // (QuestWnd.quest, a Quest.Box with a Condition[]) — a faithful client limitation (like A8's no per-item
    // countdown), so the selected quest is the only one whose conditions() is non-empty. All backings are
    // public → zero haven edit. The status ints (QST_PEND/DONE/FAIL/DISABLED) are compile-time constants →
    // inlined, so the status helpers do NOT load Quest (whose <clinit> renders text and would fail headless)
    // — they stay headless-testable.
    //
    // The READS all moved onto LuaQuest / LuaCondition with 039.13 (the entities own them, and both key on
    // what the engine itself keys on: a quest by its id, an objective by its quest plus its own final text).
    // What stays here is the window accessor and the pure add/complete DIFF that drives the events.

    /** The Quest Log window (the character sheet's "Quest Log" tab — created hidden at login but live),
     *  or {@code null} before it exists. Via the public {@code CharWnd.quest} field (no tree-walk). */
    static QuestWnd questwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.quest;
    }

    /** Is a quest status "active" (shown in the Quest Log's Current tab)? — pending or disabled. */
    private static boolean questActive(int done) {
        return (done == QuestWnd.Quest.QST_PEND) || (done == QuestWnd.Quest.QST_DISABLED);
    }


    // ---- wounds (A9-2: hafen.wound) --------------------------------------------------------------
    // Wounds are a WoundWnd (@RName("wounds")) — the character sheet's "Health & Wounds" tab, held by the
    // public CharWnd.wound field (created hidden at login but live, so wounds read without opening it). The
    // window keeps a WoundList whose public List<Wound> is the flat set of wounds; the client renders it as
    // a TREE (Wound.parentid links a complication to its parent wound, -1 = a root; Wound.level = the depth
    // the WoundList's treesort computes for indentation). Each Wound carries {id, parentid (public final
    // int), res (Indir<Resource>), level (public int)} and, from its resource-published ItemInfo, a display
    // name (ItemInfo.Name) and a severity indicator (the highest-priority WoundWnd.QuickInfo's qstr() — the
    // magnitude the client shows beside the wound; content-defined, usually a number, NOT seconds). All
    // backings are public → zero haven edit, like A9-1/A8/A7/A6/A4/A2.
    //
    // The READS moved onto LuaWound with 039.13 (the entity owns them, keyed by the wound id — which is
    // what decwound itself looks a wound up by before mutating it in place). WoundChanged is still fired by
    // the poll-driven WoundAdapter (severity streams in a beat after the wound row, like study's Curiosity
    // info, so a per-tick snapshot diff catches it) — not a targeted uimsg, since a uimsg refresh would see
    // severity still Loading and miss it. What stays here is that change detection.

    /** The Health &amp; Wounds window (the character sheet's "Health & Wounds" tab — created hidden at login
     *  but live), or {@code null} before it exists. Via the public {@code CharWnd.wound} field (no tree-walk). */
    static WoundWnd woundwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.wound;
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
     *  at login but live), or {@code null} before it exists. Via the public {@code CharWnd.fight} field. It is
     *  the one funnel {@link LuaManeuver}, {@link LuaDeckCard} and {@link LuaFightSummary} resolve through. */
    static FightWnd fightwnd() {
        CharWnd c = charwnd();
        return (c == null) ? null : c.fight;
    }
}
