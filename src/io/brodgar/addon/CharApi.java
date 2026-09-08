package io.brodgar.addon;

import haven.BAttrWnd;
import haven.BuddyWnd;
import haven.Buff;
import haven.Bufflist;
import haven.CharWnd;
import haven.Coord;
import haven.Coord2d;
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
import haven.MenuGrid;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import static io.brodgar.addon.AddonManager.*;

/**
 * The character-read subsystem: the sections a Session hands back — {@code s:player()}, {@code s:char()},
 * {@code s:study()}, {@code s:party()}, {@code s:kin()}, {@code s:buff()}, {@code s:meter()},
 * {@code s:quest()}, {@code s:wound()}, {@code s:actionbar()}, {@code s:speed()}, {@code s:craft()} and
 * {@code s:menugrid()} and {@code s:fight()} — plus the item
 * reads: the widget-tree reads of character state,
 * plus the change-detection {@link TreeAdapter}s that fire the semantic events (BuffAdded, FepChanged,
 * ...). {@link AddonManager} drives it via {@link #dispatchUimsg} (the onUimsg tap),
 * {@link #refreshTreeAdapters} (the tick), {@link #dispatchRemoved} (M1) and {@link #dispatchBeltSet}
 * (D-178); {@link #dispatchPlaced} (M3) comes off {@link UiApi#dispatchEntered} instead since 112.5, which is
 * the widget-entry seam's drain. Not instantiable.
 *
 * <p><b>The adapters are one session's</b> (073.3). Each of the nine caches the HUD widgets it has seen, as
 * its diff key, and those widgets are <b>one login's</b>, so the set of them lives in {@code SessionState}
 * ({@link #newAdapters}) and every seam reaches it with the {@code ui} of the widget it was handed:
 * {@code w.ui} at the uimsg tap, and the state the drain already holds everywhere else. Never
 * {@link AddonManager#screen()}, which answers the session on screen — the uimsg tap runs on a Loader thread
 * of whichever session sent the message, and the entry drain walks every tree's queue in turn.
 *
 * <p><b>What an adapter READS is the drawn HUD</b>: an adapter body asks {@link AddonManager#gui()}, which
 * answers the session on screen. Each adapter already knows whose cache it is, so the seam that must hand it
 * its own HUD instead has one caller to fix rather than nine caches to untangle first — and until it does, an
 * adapter indexed under a background session reports the drawn character's numbers.
 *
 * <p><b>Built with the state, not re-added on a switch.</b> The {@code resetSession} that used to empty the
 * list and construct nine fresh adapters on every {@code init} is gone: a session's HUD is not a different
 * HUD because the player tabbed to another character, and the caches name widgets of a tree that is still
 * standing. They are constructed once, when their session's state is, and go when it does.
 */
final class CharApi {
    private CharApi() {}

    // ---- how each of these sections is reached, and so how every one of its messages spells itself ----
    // 077.1: the six read-only sections of the character sheet hang on a Session rather than off `hafen`,
    // so a refusal quotes the call the author actually has to fix rather than a door that is not there.
    // 077.2: the two ROSTERS follow them, and with them the first protected verbs to be addressed at a
    // character nobody is looking at -- each keeping the one key it has, because a key names the action and
    // not the target (Permission, guides/permissions.md).
    // 077.3: and the four that ACT -- the action bar, the speed selector, the open recipe and the action
    // menu. Two of them report a WINDOW THE GAME PUT UP rather than a fact about a character, and a session
    // that is not drawn still has its GameUI: its recipe window is open, and its action menu answers.
    // 077.4: and the fight closes the family. A combat school is configured on one character and a fight is
    // fought by one body, so both halves of the section read the named session's own sheet and its own
    // combat view -- there is no "the" deck any more than there is "the" world.

    /** {@code s:char()} — the sheet. */
    static final String C = "session:char()";
    /** {@code s:meter()} — the HUD bars. */
    static final String M = "session:meter()";
    /** {@code s:buff()} — the buff bar. */
    static final String B = "session:buff()";
    /** {@code s:study()} — the study window. */
    static final String ST = "session:study()";
    /** {@code s:quest()} — the quest log. */
    static final String Q = "session:quest()";
    /** {@code s:wound()} — the wound list. */
    static final String WD = "session:wound()";
    /** {@code s:kin()} — the kin roster. */
    static final String KN = "session:kin()";
    /** {@code s:party()} — the party roster. */
    static final String PT = "session:party()";
    /** {@code s:actionbar()} — the hotbar. */
    static final String AB = "session:actionbar()";
    /** {@code s:speed()} — the movement-speed selector. */
    static final String SP = "session:speed()";
    /** {@code s:craft()} — the open recipe window. */
    static final String CR = "session:craft()";
    /** {@code s:menugrid()} — the action menu. */
    static final String MG = "session:menugrid()";
    /** {@code s:fight()} — the combat-schools tab, and the fight that character is in. */
    static final String FT = "session:fight()";

    /**
     * <b>The nine change-detection adapters, for one session</b> (073.3) — built when that session's
     * {@code SessionState} is and held by it, which is what makes each of them a reader of <i>that</i>
     * login's HUD rather than of "the" HUD.
     *
     * <p>Fixed once built, so there is nothing to copy on write and nothing to synchronize: the list is
     * published through a {@code final} field of the state and never mutated again. A
     * {@code CopyOnWriteArrayList} here would be paying for a rebuild on every {@code init} that no longer
     * happens.
     */
    static List<TreeAdapter> newAdapters(SessionState st) {
        List<TreeAdapter> l = new ArrayList<TreeAdapter>(9);
        l.add(new MeterAdapter());
        l.add(new BuffsAdapter());
        l.add(new FepAdapter(st));
        l.add(new StudyAdapter(st));
        l.add(new ActionbarAdapter(st));
        l.add(new EquipAdapter(st));
        l.add(new KinAdapter(st));
        l.add(new QuestAdapter(st));
        l.add(new WoundAdapter(st));
        return Collections.unmodifiableList(l);
    }

    /**
     * <b>The seven readers that reach a HUD by ACCOUNT</b> (092.4, A-089) — the base the state travels on.
     *
     * <p>The other two ({@code MeterAdapter}, {@code BuffsAdapter}) cache the widgets themselves and read
     * through them, so they were addressed already. These seven look their subject up: the character sheet,
     * the belt, the equipory, the roster, the quest log, the wound list. Each of those lookups took
     * {@link AddonManager#drawnUser()} — the character on SCREEN — while the adapter itself was held by one
     * session's state and fed that session's widgets. So eating on a background character marked its own
     * adapter dirty and fired {@code FepChanged} carrying <i>the drawn character's</i> Food, under the drawn
     * character's account: not an unlabelled payload but the wrong body's, on eight bus keys.
     *
     * <p>The state is the whole of the fix, and it is what the adapter was built with all along.
     */
    private abstract static class SessionAdapter implements TreeAdapter {
        private final SessionState st;

        SessionAdapter(SessionState st) {
            this.st = st;
        }

        /** The account this adapter reads for. {@code null} for the layer's own state, which has no HUD. */
        final String user() {
            return AddonManager.userOf(st);
        }
    }

    /**
     * The inbound-uimsg tap body (behind AddonManager.onUimsg): flag the interested adapter(s) of the
     * widget's <b>own</b> session dirty.
     *
     * <p>073.3: {@code w.ui} and never {@link AddonManager#screen()} — this runs on a Loader thread of the
     * session that sent the message, which is not the session on screen, so the drawn session's adapters
     * would be asked whether they are interested in another character's update and its own would never
     * hear about it. A session with no state (the login screen, a destroyed tree) has no adapters to mark.
     */
    static void dispatchUimsg(Widget w, String msg) {
        if(w == null)
            return;
        SessionState st = state(w.ui);
        if(st == null)
            return;
        for(TreeAdapter a : st.treeAdapters) {
            try {
                if(a.interested(w, msg))
                    st.treeDirty.add(a);
            } catch(RuntimeException e) {
                /* an adapter's recognizer must never break server message application */
            }
        }
        // 049.3: the "cap" branch that used to sit here is GONE. A caption change is announced at the caption
        // seam instead (Window.chcap -> AddonManager.onCaptionChanged), which is strictly better on three counts:
        // it carries the WINDOW (a chain's [title=] sits on an ancestor step, so its consumers need to know
        // WHICH subtree to re-ask about), it is the moment the field is actually written rather than the moment
        // a message naming it was applied, and it catches an addon's own widget:title("…") write too.
    }

    /** The tick's re-read of every adapter of <b>this</b> session that a uimsg marked (from the drain). */
    static void refreshTreeAdapters(SessionState st) {
        if(st.treeDirty.isEmpty())
            return;
        for(TreeAdapter a : st.treeAdapters) {
            if(st.treeDirty.remove(a)) {
                try {
                    a.refresh();
                } catch(RuntimeException e) {
                    log("tree adapter error: " + e);
                }
            }
        }
    }

    /**
     * The <b>widget-entry seam</b>'s body for the tree adapters (from {@link UiApi#dispatchEntered}, spec
     * {@code 042-event-driven-reads} M3): offer the just-entered widget to every adapter that has moved its
     * "did a widget appear" detection off {@code poll()} and onto a seam (042.1's {@code MeterAdapter} is the
     * first). {@code MeterAdded}, {@code BuffAdded} and the study and equipment fires all leave from here.
     *
     * <p><b>It used to hang off the placement seam, and 112.5 moved it</b> — {@code AddonManager.onWidgetPlaced}
     * is reached inside {@code UI.AddWidget.run}'s {@code synchronized(ui)}, on a Loader thread, so every one of
     * those events ran with that tree's monitor held and a handler that built a window took a second. Here it
     * runs on the layer's step holding none, so a {@code BuffAdded} handler may build a window and write any
     * tree — and {@code hafen.client():stepping()} says so.
     *
     * <p><b>And it is offered more than it was.</b> The placement seam was the server's message handler: it
     * never saw the widgets the client mints for itself, and it announced a widget the instant its parent took
     * it, which for a subtree built before it is hung is before the widget is in any tree. The entry seam has
     * neither fault. Every adapter already opens with an {@code instanceof} and dedups on its own cache, so the
     * wider offer needs nothing of them.
     *
     * <p>073.3: it is handed the state whose queue the widget came out of — the tree the widget entered, which
     * is what {@link AddonManager#screen()} would not say. {@link #dispatchRemoved} takes it the same way.
     */
    static void dispatchPlaced(SessionState st, Widget wdg) {
        if(wdg == null)
            return;
        for(TreeAdapter a : st.treeAdapters) {
            try {
                a.placed(wdg);
            } catch(RuntimeException e) {
                log("tree adapter placed error: " + e);
            }
        }
    }

    /**
     * The widget-removal seam's body for the tree adapters (behind {@link AddonManager#onWidgetRemoved}, spec
     * {@code 042-event-driven-reads} M1): offer the just-removed widget to every adapter that has moved its
     * "did a widget disappear" detection off {@code poll()} and onto this seam. Reached from {@link
     * AddonManager#tick(haven.UI)}'s drain of the removal queue, on the UI thread, so firing Lua here is safe.
     *
     * <p>073.3: it is handed the state whose queue the widget came out of, so a removal is never offered to
     * another session's adapters — {@code w.ui} says the same thing and this says it without a lookup.
     */
    static void dispatchRemoved(SessionState st, Widget wdg) {
        if(wdg == null)
            return;
        for(TreeAdapter a : st.treeAdapters) {
            try {
                a.removed(wdg);
            } catch(RuntimeException e) {
                log("tree adapter removed error: " + e);
            }
        }
    }

    /**
     * The deferred-belt-write seam's body (behind {@link AddonManager#onBeltSet}, spec {@code
     * 042-event-driven-reads} D-178): re-check the one slot whose {@code glob.loader.defer}-red write has
     * now landed — the uimsg tap already re-diffed the whole bar against the OLD value for these two
     * paths, so only {@link ActionbarAdapter} needs to hear this. Reached from {@link
     * AddonManager#tick(haven.UI)}'s drain of the belt-set queue, on the UI thread.
     *
     * <p>073.3: a slot index names one character's bar, so it is that session's own adapter that re-checks it.
     */
    static void dispatchBeltSet(SessionState st, int slot) {
        for(TreeAdapter a : st.treeAdapters) {
            if(a instanceof ActionbarAdapter) {
                try {
                    ((ActionbarAdapter)a).beltSet(slot);
                } catch(RuntimeException e) {
                    log("actionbar belt-set error: " + e);
                }
            }
        }
    }

    /**
     * A widget-tree read adapter (spec {@code 14-widget-tree-reads.md}): the one place that knows a
     * target widget tree's shape, localizing that upstream-volatile knowledge. Two update paths, both
     * event-driven (spec {@code 042-event-driven-reads} — the per-tick {@code poll()} this interface
     * used to also carry is gone, not protected):
     * <ul>
     *   <li><b>uimsg-driven</b> ({@link #interested} off-thread → dirty → {@link #refresh} on the UI
     *       thread): for state the server pushes via a targeted {@code uimsg} (meter values, FEP, buff
     *       content).</li>
     *   <li><b>seam-driven</b> ({@link #placed}/{@link #removed}, spec {@code 042-event-driven-reads}
     *       M1/M3): for structural changes the uimsg tap can't see — buff add/remove is a widget
     *       create/{@code cdestroy} on the {@code Bufflist}, not a {@code uimsg} — fired at the moment
     *       they happen instead of diffed every tick. Default is a no-op; an adapter overrides only the
     *       half(ves) it needs — a fading widget (a buff, a window) answers {@link #removed} on its own
     *       "gone" signal instead, never on this seam (D-180).</li>
     * </ul>
     */
    interface TreeAdapter {
        boolean interested(Widget w, String msg);
        void refresh();
        default void placed(Widget w) {}
        default void removed(Widget w) {}
    }

    /**
     * HUD meters — the {@link IMeter} bars in {@code GameUI}'s {@code place == "meter"} slot. The reads live
     * on {@link LuaMeter} since {@code 027-meters-oop} (the entity owns them); only change <i>detection</i> is
     * this adapter's business. The old positional {@code hp}/{@code stamina}/{@code energy} snapshot and its
     * {@code VitalsChanged} event are GONE with that spec's hard cut.
     *
     * <p><b>Event-driven since 042.1.</b> A meter appearing is the widget-entry seam ({@link #placed}, offered
     * from that seam's drain on the layer's step since 112.5, which is a whole frame after
     * {@code GameUI.addchild}'s {@code place == "meter"} branch has positioned the widget and appended it to
     * {@code meters} — so the membership test below is asked of a bar that is fully placed) and a meter being
     * destroyed is the removal seam ({@link #removed}, M1) — no more per-tick diff of the HUD's own bars
     * against the cache. Membership is still checked through {@link LuaMeter#exists} (not a bare
     * {@code instanceof IMeter}), so a widget of this type placed somewhere other than the HUD meter slot —
     * hypothetical today, since {@code GameUI} is the only {@code IMeter} placement site — could never be
     * miscounted as a bar. The bar CONTENT is pushed by the
     * server as a targeted {@code "set"} (values) or {@code "col"} (colours) {@code uimsg}, so <b>refresh</b>
     * (unchanged) re-reads the cached meters and fires {@code MeterChanged} — colour is in the key because it
     * is now in the read surface ({@code meter:color()}), which the old {@code vitalsEqual} deliberately
     * ignored.
     *
     * <p>All three carry the <b>Meter object</b> ({@link AddonManager#fireMeter}), so a handler reads the payload
     * with the same methods as {@code s:meter():list()}. The per-meter snapshot stays, purely as the diff KEY: an
     * interned object compares by identity and so cannot detect a content change (the 025.2 lesson). It is never
     * handed to Lua — {@code meter:info()} is that, on demand.
     */
    private static final class MeterAdapter implements TreeAdapter {
        // Live HUD meter -> its last segment snapshot (the change-detection key, NOT a payload). UI-thread-only
        // (placed/removed/refresh); built with its session's state (073.3). IdentityHashMap:
        // IMeter widgets are keyed by object identity, like the buffs.
        // retained: change-detection state whose drain ANNOUNCES -- removed() fires MeterRemoved -- so it stays
        //   on the removal seam: the disposal drain retires and never fires. Bounded by its SessionState, which
        //   this adapter dies with.
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

        public void placed(Widget w) {
            if(!(w instanceof IMeter))
                return;
            IMeter m = (IMeter)w;
            if(cache.containsKey(m) || !LuaMeter.exists(m))
                return;
            // Seed the diff key with the meter's current segments AT add time (027.2) — the seed, not tick
            // ordering, is what keeps a bar that arrives already filled from surfacing as MeterChanged-then-
            // MeterAdded; there is no ordering to lean on here since refresh/placed are two different seams.
            cache.put(m, LuaMeter.segments(m));
            fireMeter("MeterAdded", m);
        }

        public void removed(Widget w) {
            if(!(w instanceof IMeter))
                return;
            IMeter m = (IMeter)w;
            if(!cache.containsKey(m))
                return;
            // The widget is unlinked, not cleared: the payload still answers :res()/:value()/… and now
            // reports :exists() false. Fire BEFORE dropping the entry (025.2).
            fireMeter("MeterRemoved", m);
            cache.remove(m);
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
     * Buffs — the {@link Buff} widgets under {@link GameUI#buffs} (a {@link Bufflist}).
     *
     * <p><b>Event-driven since 042.2.</b> A buff appearing is the widget-entry seam ({@link #placed}, offered
     * from that seam's drain on the layer's step since 112.5 — so a {@code BuffAdded} handler holds no tree
     * monitor and may build a window). A buff's real unlink is a widget create/{@code cdestroy} that M1
     * ({@link #removed}) sees — but {@code Bufflist.cdestroy} is one of the
     * 9 overrides that skip {@code super} (D-179), which is why M1 (not {@code cdestroy}) is the seam at
     * all — <b>and M1 alone would still fire {@code BuffRemoved} 0.35s late</b>, because
     * {@link Buff#reqdestroy} does not unlink: it sets the protected {@code dest} flag and starts a fade,
     * so the widget stays a {@code Bufflist} child for that whole interval (025.1, D-180). The "gone"
     * signal is {@code dest}, not the unlink, so {@code Buff.reqdestroy} carries a one-line {@code // addon:}
     * tap ({@link AddonManager#onWidgetRemoved}) announcing it at the moment the server said so; {@link
     * #removed} — reached a second time, 0.35s later, when the fade finishes and M1 fires for real — is then
     * a no-op for a buff already announced, guarded by the same cache-membership check {@link MeterAdapter}
     * uses for its own removal.
     *
     * <p>The per-buff {@code "ch"}/{@code "tt"} content updates ARE {@code uimsg}s, so <b>refresh</b>
     * (unchanged) re-reads the cached buffs and fires {@code BuffChanged}.
     *
     * <p>The reads themselves live on {@link LuaBuff} since {@code 025-buffs-oop} (the entity owns them);
     * only change <i>detection</i> — the per-buff snapshot diff — is this adapter's business. Since 025.2 the
     * three events carry the <b>Buff object</b> ({@link AddonManager#fireBuff}), so a handler reads the
     * payload with the same methods as {@code s:buff():list()}. The snapshot stays, purely as the diff KEY: it
     * is the cheap value-comparable form of the buff, and it is what makes {@code BuffChanged} fire on real
     * content changes only. It is never handed to Lua any more — {@code buff:info()} is that, on demand.
     */
    private static final class BuffsAdapter implements TreeAdapter {
        // Active buff -> its last snapshot (the change-detection key, NOT a payload). UI-thread-only
        // (placed/removed/refresh); built with its session's state (073.3). IdentityHashMap:
        // Buff widgets are keyed by object identity, like the meters.
        // retained: change-detection state whose drain ANNOUNCES -- removed() fires BuffRemoved -- so it stays
        //   on the removal seam: the disposal drain retires and never fires. Bounded by its SessionState, which
        //   this adapter dies with.
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

        public void placed(Widget w) {
            if(!(w instanceof Buff))
                return;
            Buff b = (Buff)w;
            if(cache.containsKey(b))
                return;
            // Seed the diff key with the buff's current snapshot AT add time (027.2, mirrored from
            // MeterAdapter) -- there is no tick ordering to lean on here since placed/refresh are two
            // different seams.
            cache.put(b, LuaBuff.snapshot(b));
            fireBuff("BuffAdded", b);
        }

        public void removed(Widget w) {
            if(!(w instanceof Buff))
                return;
            Buff b = (Buff)w;
            if(!cache.containsKey(b))
                return;   // already announced at dest (the // addon: tap in Buff.reqdestroy) -- the late
                          // unlink M1 fires 0.35s afterwards must not produce a second BuffRemoved (D-180)
            // The widget is unlinked, not cleared: the payload still answers :res()/:name()/… and now
            // reports :exists() false. Fire BEFORE dropping the entry (025.2).
            fireBuff("BuffRemoved", b);
            cache.remove(b);
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
     * the same verbs as {@code s:char():food()} and a stashed payload goes on tracking the meal after
     * it. The reads themselves live on {@link LuaFood}; only firing is this adapter's business.
     */
    private static final class FepAdapter extends SessionAdapter {
        FepAdapter(SessionState st) {
            super(st);
        }

        public boolean interested(Widget w, String msg) {
            return (w instanceof BAttrWnd) && ("food".equals(msg) || "glut".equals(msg));
        }

        public void refresh() {
            BAttrWnd w = battrwnd(user());
            if(w != null)
                fireFood(w);
        }
    }

    /**
     * Study / curiosity — the items placed in the study window, each carrying a {@link Curiosity}
     * study profile. Located via the public {@code CharWnd.sattr} ({@link SAttrWnd}) → its
     * {@link SAttrWnd.StudyInfo} child → the study inventory it wraps ({@link #studyWidget}).
     *
     * <p><b>Event-driven since 042.4.</b> A curiosity being placed/removed is a widget create/{@code
     * cdestroy} under the study inventory — structure comes from the placement/removal seams ({@link
     * #placed}/{@link #removed}, M1/M3), exactly like {@link MeterAdapter}/{@link BuffsAdapter}/{@link
     * EquipAdapter}. A slot's {@link Curiosity} numbers are <i>derived</i> state with no queue of their
     * own — {@link GItem#info()} rebuilds from {@code rawinfo} and throws when the underlying resource is
     * still streaming — so {@link #resolveInfo} triggers that build and hands a thrown {@code Loading} to
     * {@link Resolve#on}, which retries once the resource lands and re-fires only if the slot's numbers
     * actually changed: <b>the first real proof of M2 on genuinely streaming data</b> (042.1 shipped it
     * with every read already {@code Loading}-guarded to {@code nil}).
     *
     * <p>A curiosity with no {@link Curiosity} info at all (e.g. a Hearth-Magic bond) is not an error:
     * {@code ItemInfo.find} returns {@code null} and the snapshot key carries {@code res}/{@code name}
     * only, and {@link #resolveInfo} never retries for it again, because {@code it.info()} itself did not
     * throw.
     *
     * <p>The payload is the current {@code List<GItem>} in study-window order ({@link
     * AddonManager#fireStudy}), the same items {@code s:study():slot():list()} hands back, so a
     * handler reads it with the {@link LuaStudySlot} verbs.
     */
    private static final class StudyAdapter extends SessionAdapter {
        StudyAdapter(SessionState st) {
            super(st);
        }

        // Study-slot GItem -> its last snapshot (the change-detection key, NOT a payload). UI-thread-only
        // (placed/removed/resolveInfo); built with its session's state (073.3).
        // IdentityHashMap: GItem widgets are keyed by object identity, like the buffs/meters/equip.
        // retained: change-detection state whose drain ANNOUNCES -- removed() fires the study payload -- so it
        //   stays on the removal seam: the disposal drain retires and never fires. The study slots sit inside
        //   the character window, which hides rather than closing, so a slot leaves by a removal or not at all.
        private final Map<GItem, LuaValue> cache = new IdentityHashMap<GItem, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return false;   // a curiosity's numbers resolve on the RESOURCE landing, not a targeted uimsg
        }

        public void refresh() {}

        public void placed(Widget w) {
            if(!(w instanceof GItem))
                return;
            GItem it = (GItem)w;
            String user = user();
            Widget study = studyWidget(user);
            if((study == null) || (it.parent != study) || cache.containsKey(it))
                return;
            cache.put(it, LuaStudySlot.snapshot(it));
            resolveInfo(it);
            fireStudy(user, LuaStudySlot.items(user));
        }

        public void removed(Widget w) {
            if(!(w instanceof GItem))
                return;
            GItem it = (GItem)w;
            if(cache.remove(it) == null)
                return;
            String user = user();
            fireStudy(user, LuaStudySlot.items(user));
        }

        /**
         * Trigger {@link GItem#info}'s build so a resolved {@link Curiosity} is ready by the time a
         * handler reads it. {@code info()} is derived state with no queue of its own: a thrown {@link
         * Loading} (its resource still streaming) is registered through {@link Resolve#on}, retried once
         * on the notify, and re-diffs/re-fires only if the slot's snapshot actually changed.
         */
        private void resolveInfo(final GItem it) {
            try {
                it.info();
            } catch(Loading l) {
                Resolve.on(l, null, new Resolve.Retry() {
                    public void run() throws Loading {
                        it.info();
                        LuaValue snap = LuaStudySlot.snapshot(it);
                        if(cache.containsKey(it) && !studySlotEqual(snap, cache.get(it))) {
                            cache.put(it, snap);
                            String user = user();
                            fireStudy(user, LuaStudySlot.items(user));
                        }
                    }
                });
            }
        }
    }

    /**
     * Action bar / hotbar — the 144 {@link GameUI.BeltSlot}s of {@code GameUI.belt} (the F-key /
     * number-key hotbar; the engine's own name for the action bar is the "belt"). Setting/clearing/
     * dragging a slot is a {@code setbelt}/{@code setbelt2} {@code uimsg} to {@code GameUI}.
     * Change-detection ignores {@code cooldown} (a live meter that would otherwise fire every frame while
     * an ability cools down); {@code s:actionbar():get(n)} still reads it live.
     *
     * <p><b>Event-driven since 042.6 — the 144-slot per-frame walk is gone.</b> Three of the five
     * {@code setbelt}/{@code setbelt2} paths write {@code belt[slot]} synchronously, so the existing uimsg
     * tap already sees the new value: {@link #interested} flags the two messages on {@code GameUI} itself
     * and {@link #refresh} re-diffs the whole bar — the message names a slot number but the tap only hands
     * over {@code (widget, msg)}, not the args, so which index changed isn't known here; the diff is what
     * finds out, and it only runs when a message actually arrives, not every tick. The other two paths
     * (resource/pagina) defer the write onto a {@code glob.loader.defer} task that runs AFTER the tap
     * already fired against the OLD value (D-178) — {@link #beltSet}, behind {@link
     * AddonManager#onBeltSet}, re-checks just that one slot once the write has actually landed.
     *
     * <p>The snapshots are the diff's <i>input only</i>: what reaches Lua is a per-addon <b>Slot object</b> for
     * the changed slot ({@link AddonManager#fireSlot}, 021.2), so a handler reads the payload with the same
     * methods as {@code s:actionbar():get(n)} and can key a table by it.
     */
    private static final class ActionbarAdapter extends SessionAdapter {
        ActionbarAdapter(SessionState st) {
            super(st);
        }

        // slot index -> last snapshot, occupied slots only. UI-thread-only; built with its
        // session's state (073.3). Keyed by Integer (value identity), not widget identity.
        private final Map<Integer, LuaValue> cache = new HashMap<Integer, LuaValue>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof GameUI) && ("setbelt".equals(msg) || "setbelt2".equals(msg));
        }

        public void refresh() {
            GameUI g = gui();
            if((g == null) || (g.belt == null))
                return;           // HUD not up yet — keep the cache, fire nothing
            GameUI.BeltSlot[] belt = g.belt;
            String user = user();
            for(int n = 0; n < belt.length; n++)
                checkSlot(user, belt, n);
        }

        /**
         * The deferred-write notify's body (042.6, D-178): {@code setbelt}-with-res and
         * {@code setbelt2 "r"} write {@code belt[slot]} from a Loader task that lands after {@link
         * #refresh} already ran against the old value, so re-check just this one index once the write is
         * actually there.
         */
        void beltSet(int n) {
            GameUI g = gui();
            if((g == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length))
                return;
            checkSlot(user(), g.belt, n);
        }

        /**
         * Diff one slot against the cache and fire {@code ActionbarChanged} if it changed — shared by the
         * uimsg-driven {@link #refresh} (which doesn't know which index changed, so it checks all 144) and
         * the deferred-write {@link #beltSet} (which knows exactly one), so the two paths can never
         * disagree about what "changed" means.
         */
        private void checkSlot(String user, GameUI.BeltSlot[] belt, int n) {
            GameUI.BeltSlot s = belt[n];
            LuaValue prev = cache.get(n);
            if(s == null) {
                if(prev != null) {                        // occupied -> empty (cleared)
                    cache.remove(n);
                    fireSlot(user, n);
                }
            } else {
                LuaValue snap = actionbarSnapshot(s);
                if((prev == null) || !actionbarEqual(snap, prev)) {   // empty->occupied or content changed
                    cache.put(n, snap);
                    fireSlot(user, n);
                }
            }
        }
    }

    /**
     * Equipment — the {@link WItem}s worn in the {@link Equipory}. The reads live on {@link LuaItem}; only
     * change <i>detection</i> is this adapter's business.
     *
     * <p><b>Event-driven since 042.3.</b> Equipping/removing is a widget create/{@code cdestroy} under the
     * {@link Equipory} — structure comes from the placement/removal seams ({@link #placed}/{@link #removed},
     * M1/M3), exactly like {@link MeterAdapter}/{@link BuffsAdapter}. Item <i>data</i>
     * (
     * {@code "num"}/{@code "chres"}/{@code "tt"}) DOES arrive as a targeted {@code uimsg} on the child
     * {@link GItem} itself — {@link #interested} flags exactly those three for a currently-worn item and
     * {@link #refresh} re-diffs every cached item's key. {@code "meter"} (wear) is deliberately excluded — a
     * durability drifting down is not an equipment change — which is also why quality never enters the key
     * ({@link LuaItem#equipKey}).
     *
     * <p><b>The first real {@link Resolve} (M2) consumer</b> (042.1 shipped it unproven — every meter/buff
     * read was already {@code Loading}-guarded to {@code nil}). {@link GItem#info()} is <i>derived</i> state
     * with no queue of its own: it rebuilds from {@code rawinfo} and throws a bare {@code Loading} when
     * {@code res.get()} is itself still streaming. {@link #resolveInfo} triggers that build; a thrown
     * {@code Loading} is handed to {@link Resolve#on}, which retries the build once the resource lands and
     * re-fires {@code EquipChanged} only if the key actually changed — never a delayed poll.
     *
     * <p>The payload is an array of <b>Item objects</b> ({@link AddonManager#fireEquip}), the same objects
     * {@code s:ui():equipment():items()} hands back, so a handler reads it with the item verbs and a
     * stashed payload goes on answering after the gear comes off.
     */
    private static final class EquipAdapter extends SessionAdapter {
        EquipAdapter(SessionState st) {
            super(st);
        }

        // Worn GItem -> its last equip-key (the change-detection key, NOT a payload). UI-thread-only
        // (placed/removed/refresh); built with its session's state (073.3). IdentityHashMap:
        // GItem widgets are keyed by object identity, like the meters/buffs.
        // retained: change-detection state whose drain ANNOUNCES -- removed() fires the equipment payload -- so
        //   it stays on the removal seam: the disposal drain retires and never fires. The Equipory sits inside a
        //   Hidewnd, which hides rather than closing, so worn gear leaves by a removal or not at all.
        private final Map<GItem, String> cache = new IdentityHashMap<GItem, String>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof GItem) && cache.containsKey(w)
                && ("num".equals(msg) || "chres".equals(msg) || "tt".equals(msg));
        }

        public void refresh() {
            boolean changed = false;
            for(Map.Entry<GItem, String> e : cache.entrySet()) {
                GItem it = e.getKey();
                resolveInfo(it);
                String key = LuaItem.equipKey(it);
                if(!key.equals(e.getValue())) {
                    e.setValue(key);
                    changed = true;
                }
            }
            if(changed) {
                String user = user();
                fireEquip(user, LuaItem.items(equipory(user)));
            }
        }

        public void placed(Widget w) {
            if(!(w instanceof GItem))
                return;
            GItem it = (GItem)w;
            String user = user();
            Equipory eq = equipory(user);
            if((eq == null) || (it.parent != eq) || cache.containsKey(it))
                return;
            cache.put(it, LuaItem.equipKey(it));
            resolveInfo(it);
            fireEquip(user, LuaItem.items(eq));
        }

        public void removed(Widget w) {
            if(!(w instanceof GItem))
                return;
            GItem it = (GItem)w;
            if(cache.remove(it) == null)
                return;
            // The widget is unlinked, not cleared: the payload still answers :res()/:name()/… (025.2's
            // rule, mirrored here). Equipory's own child list no longer has it, so items() below already
            // reads the post-removal set.
            String user = user();
            fireEquip(user, LuaItem.items(equipory(user)));
        }

        /**
         * Trigger {@link GItem#info}'s build so a resolved name/quality is ready by the time {@link
         * LuaItem#equipKey} reads it. {@code info()} is derived state with no queue of its own: a thrown
         * {@link Loading} (its resource still streaming) is registered through {@link Resolve#on}, retried
         * once on the notify, and re-diffs/re-fires only if the key actually changed.
         */
        private void resolveInfo(final GItem it) {
            try {
                it.info();
            } catch(Loading l) {
                Resolve.on(l, null, new Resolve.Retry() {
                    public void run() throws Loading {
                        it.info();
                        String key = LuaItem.equipKey(it);
                        if(cache.containsKey(it) && !key.equals(cache.get(it))) {
                            cache.put(it, key);
                            String user = user();
                            fireEquip(user, LuaItem.items(equipory(user)));
                        }
                    }
                });
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
     * methods as {@code s:kin():list()} and can key a table by an entry.
     */
    private static final class KinAdapter extends SessionAdapter {
        KinAdapter(SessionState st) {
            super(st);
        }

        private LuaValue cache = LuaValue.NIL;   // last kin snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return (w instanceof BuddyWnd) &&
                   ("add".equals(msg) || "rm".equals(msg) || "chst".equals(msg) || "upd".equals(msg));
        }

        public void refresh() {
            String user = user();
            LuaValue snap = kinSnapshotList(user);
            if(!kinListEqual(snap, cache)) {
                cache = snap;
                fireKin(user, kinIds(snap));
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
     * new <i>active</i> quest (pending/disabled) appears, and {@code QuestCompleted} or
     * {@code QuestFailed} — the outcome picks which (097) — when a previously-active
     * quest becomes <i>finished</i> (done/failed) — mirroring {@code QuestWnd}'s own completion trigger.
     * Completed quests already present at login are recorded silently (no {@code QuestAdded}), so the
     * quest history doesn't spam events.
     *
     * <p>The payload is the <b>Quest object</b> ({@link AddonManager#fireQuest}), so a handler reads it with
     * the same verbs as {@code s:quest():get(id)}. That matters more here than anywhere else in the API:
     * a completion fires <i>as</i> the status changes, and a snapshot froze the very field the event is
     * about — a stashed Quest goes on answering {@code :status()} afterwards. The {@code id -> done} cache
     * stays, purely as the diff KEY: an interned object compares by identity and so cannot detect a status
     * advancing, which is the whole of what this adapter exists to notice.
     */
    private static final class QuestAdapter extends SessionAdapter {
        QuestAdapter(SessionState st) {
            super(st);
        }

        // quest id -> its last-seen status int. UI-thread-only (refresh); built with its
        // session's state (073.3).
        private final Map<Integer, Integer> cache = new HashMap<Integer, Integer>();

        public boolean interested(Widget w, String msg) {
            return (w instanceof QuestWnd) && "quests".equals(msg);
        }

        public void refresh() {
            String user = user();
            QuestWnd qw = questwnd(user);
            if(qw == null)
                return;
            // Copy both quest lists under the ui monitor (QuestWnd.uimsg mutates them off-thread), then
            // build snapshots outside it (names may Loading) — the marker "copy under the lock" discipline.
            List<QuestWnd.Quest> all = new ArrayList<QuestWnd.Quest>();
            synchronized(LuaWidget.monitor(qw)) {
                all.addAll(qw.cqst.quests);          // "Current" tab (pending / disabled)
                all.addAll(qw.dqst.quests);          // "Completed" tab (done / failed)
            }
            Map<Integer, Integer> fresh = new LinkedHashMap<Integer, Integer>();   // id -> done (in order)
            for(QuestWnd.Quest q : all)
                fresh.put(q.id, q.done);
            for(Object[] ev : questDiff(cache, fresh))            // pure diff (also updates the cache)
                fireQuest(user, (String)ev[0], ((Integer)ev[1]).intValue());
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
     *   <li><b>QuestCompleted</b> — a previously-<i>active</i> id whose status is now {@code done}, and
     *       <b>QuestFailed</b> — one whose status is now {@code failed} (097). Both mirror
     *       {@code QuestWnd}'s own completion trigger; a finished status the client does not recognise
     *       fires neither.</li>
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
                // 097: the OUTCOME picks the key. One key for both outcomes was the only silent wrong
                // answer left in the bus -- a handler that read the name congratulated the player for a
                // failure -- and q:status() was the only thing that told them apart. A status the client
                // does not know is neither: it fires nothing rather than guess, which is the same call
                // LuaQuest.status() makes when it declines to name one.
                if(done == QuestWnd.Quest.QST_DONE)
                    events.add(new Object[]{"QuestCompleted", id});
                else if(done == QuestWnd.Quest.QST_FAIL)
                    events.add(new Object[]{"QuestFailed", id});
            }
        }
        cache.keySet().retainAll(fresh.keySet());    // prune ids the server dropped
        cache.putAll(fresh);                          // update to the current statuses
        return events;
    }

    /**
     * Wounds (A9-2) — the wounds under the character sheet's "Health &amp; Wounds" tab ({@link WoundWnd},
     * reached via the public {@code CharWnd.wound} field). A wound being added / healed / worsening arrives
     * as a {@code "wounds"} {@code uimsg} <b>on {@code WoundWnd} itself</b> — {@code decwound} adds, mutates
     * or removes the entry synchronously — so, unlike the buff/study/equip adapters (whose add/remove is a
     * widget create with no {@code uimsg} at all), the LIST half of this adapter is purely {@code uimsg}-
     * driven since 042.5: {@link #interested} flags {@code "wounds"} and {@link #refresh} re-reads the full
     * snapshot and fires {@code WoundChanged} when it differs from the cache — the {@link #woundListEqual}
     * change-detection, with {@code severity} in the key so a worsening fires it.
     *
     * <p>A wound's <b>severity</b> (its {@link WoundWnd.QuickInfo} magnitude) is <i>derived</i> state with no
     * queue of its own — {@code Wound.info()} rebuilds from {@code rawinfo} and throws a bare {@link Loading}
     * while {@code res.get()} is itself still streaming, exactly the {@link StudyAdapter}/{@link EquipAdapter}
     * shape. {@link #resolveSeverities} triggers that build for every wound currently on the list and hands a
     * thrown {@code Loading} to {@link Resolve#on}, which retries once the resource lands and re-diffs/re-fires
     * only if the list's severity actually changed (nil→value, or a wound getting worse).
     *
     * <p>While the Health &amp; Wounds tab has never been opened there is no {@code WoundWnd} for {@link
     * #interested} to match against, so nothing fires — the tab is created hidden at login but live (039.13),
     * so once it exists this adapter sees every {@code "wounds"} message regardless of whether the tab is on
     * screen.
     *
     * <p>The payload is an array of <b>Wound objects</b> ({@link AddonManager#fireWounds}), so a handler
     * reads it with the same verbs as {@code s:wound():list()} and can key a table by one. The snapshots
     * stay as the diff KEY only — an interned object compares by identity, and a severity resolving or a
     * wound worsening is precisely the change identity cannot see. Read the initial state with
     * {@code s:wound():list()}; listen for deltas after.
     */
    private static final class WoundAdapter extends SessionAdapter {
        WoundAdapter(SessionState st) {
            super(st);
        }

        private LuaValue cache;   // last wound snapshot list (UI thread; change-detect)

        public boolean interested(Widget w, String msg) {
            return (w instanceof WoundWnd) && "wounds".equals(msg);
        }

        public void refresh() {
            diff();
            resolveSeverities();
        }

        private void diff() {
            String user = user();
            LuaValue snap = LuaWound.snapshotList(user);
            if(!woundListEqual(snap, cache)) {
                cache = snap;
                fireWounds(user, LuaWound.ids(user));
            }
        }

        /**
         * Trigger every current wound's {@code info()} build so a resolved severity is ready by the time a
         * handler reads it. A thrown {@link Loading} (its resource still streaming) is registered through
         * {@link Resolve#on}, retried once on the notify, and re-diffed/re-fired only if the wound is still
         * on the list and its severity actually changed.
         */
        private void resolveSeverities() {
            for(final WoundWnd.Wound w : LuaWound.all(user())) {
                try {
                    w.info();
                } catch(Loading l) {
                    final int wid = w.id;
                    Resolve.on(l, null, new Resolve.Retry() {
                        public void run() throws Loading {
                            WoundWnd.Wound cur = LuaWound.wound(user(), wid);
                            if(cur == null)
                                return;   // healed before the resource landed
                            cur.info();
                            diff();
                        }
                    });
                }
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
        if(va.type() == LuaValue.TNUMBER)   // by TYPE, like every other number test in the bridge
            return (vb.type() == LuaValue.TNUMBER) && (va.todouble() == vb.todouble());
        if(va.isstring())
            return vb.isstring() && va.tojstring().equals(vb.tojstring());
        return true;
    }

    /** That character's Base-Attributes widget ({@code CharWnd.battr}), or {@code null}. The one
     *  resolve funnel {@link LuaFood} re-reads through, every call (D-012). */
    static BAttrWnd battrwnd(String user) {
        CharWnd c = charwnd(user);
        return (c == null) ? null : c.battr;
    }

    /**
     * Build the Player object for {@code (owner, user)} — <b>one character</b>, reached as {@code s:player()}
     * (076.3). Called once per pair by {@link LuaSession}, which hangs the result on the interned Session
     * handle, so {@code s:player() == s:player()} and a per-frame read allocates nothing.
     *
     * <p>The section contains exactly one thing, so the <b>section object IS that thing</b> (§2.1):
     * {@code s:player():gob()} is the composition anchor for every per-gob read of that character
     * (position/health/moving/facing/…), plus {@code :move(p)}, which walks it and is the Player's first write
     * (048.1) and {@code :hand()}, its cursor (048.2, {@link LuaHand}). Player
     * forwards <b>nothing</b> — a {@code player:pos()} living beside {@code player:gob():position()} is exactly
     * the dual style D-013 forbids — and {@code exists}/{@code id} are dropped: {@code player:gob()} (nil
     * before that session is in the world) and {@code gob:id()} already answer both.
     *
     * <p><b>{@code :name()} is gone the same way</b>, and for the same rule: under an address the character a
     * login is playing is {@code s:character()}, read off that session's own HUD, so a {@code :name()} here
     * would be a second spelling of one fact whose only difference was which door you came through. It is
     * userdata with a per-addon metatable, immutable from Lua, like a {@link LuaGob}.
     *
     * <p><b>Every verb reads the session it hangs on</b>, and {@code :move} sends, so until a background
     * session can be ordered it goes through the same door {@code s:world():click} does.
     *
     * <p><b>{@code :worldToScreen(p)} left for {@code s:world()}</b> (092.3, A-093). It is not about the
     * player: it is a conversion between that character's world and the screen, and its inverse
     * {@code s:world():screenToWorld} was already there. Two halves of one conversion on two sections, with
     * three differences between them and none derivable, is what the move deletes.
     */
    static LuaValue player(final Addon owner, final String user) {
        LuaTable methods = new LuaTable();
        // gob() — THAT character's Gob object, or nil before that session is in the world. Interning on the
        // id makes this the SAME object as s:world():gob():get(<that character's id>), read through any
        // session at all: a body is one object, and this says which one rather than whose copy.
        //   076.3: off the session's own HUD (GameUI.plid) rather than off a map view, so it needs no widget
        // walk and answers a beat earlier — the HUD arrives before its map view is parented.
        methods.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                long id = plgob(user);
                return (id < 0) ? LuaValue.NIL : LuaGob.of(owner, user, id);
            }
        });
        /* vitals() is GONE (027-meters-oop's hard cut): the HUD bars are s:meter():list(), which is every meter
         * the server puts in the slot rather than a hard-coded hp/stamina/energy triple read by position. */
        // move(p) — walk the character to a Position, and the Player's FIRST write (048.1). It is the MapView
        // "click" a left-click on that patch of ground sends; the screen coord the message carries is a DUMMY
        // (the recipient's own view centre; MiniMap.mvclick passes the mouse for the same reason when you click
        // the minimap to walk), which is what makes an off-screen destination legal. It is not a forwarded Gob
        // method and so does not bend D-046: the server accepts a walk command only for your OWN character, so
        // there is no gob:move() beside it, and gob:moving() is a property of a gob rather than an imperative
        // on the player.
        //   The verb is PROTECTED (the per-addon "player.move" permission), and the gate runs FIRST — before the
        // argument is looked at and before the session is: an addon that never declared the permission is told
        // that, rather than being told its Position is wrong (D-213).
        //   076.5: it REACHES a session that is not drawn, and it is the only write here that does — a walk is
        // the whole of what a character nobody is looking at takes. So it goes through AddonManager.order and
        // not sendView, which is what refuses every other send for a background session.
        methods.set("move", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                requirePermission(owner, Permission.PLAYER_MOVE);
                // 076.3: the Position is resolved in THAT character's frame, so the refusal it already had
                // changes subject — a place is unreachable for the character you addressed. That frame is also
                // the one the order is sent in, so nothing translates it again on the way out.
                Coord2d rc = LuaPosition.worldArg(a, 2, P + ":move", "p", user);
                order(user, rc, P + ":move");
                return self;                                     // the Player, so a move chains
            }
        });
        // hand() — the cursor, as a Hand object, or nil when nothing is on it (048.2). Like :move it is not a
        // forwarded Gob method: no other gob has a cursor, so there is nothing on Gob for this to duplicate
        // (D-046). The nil is the point — it is what makes the held-item gesture guardable, where the two
        // verbs it replaces fired blind with an empty cursor. What it hands back carries hand:item() and the
        // protected hand:use(target, mods); see LuaHand.
        methods.set("hand", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaHand.of(owner, mark(self));
            }
        });
        final LuaTable pmt = new LuaTable();
        // A section object's vocabulary is CLOSED: an unknown verb throws naming what does exist, exactly as
        // Section.meta and LuaCollection do for every other section. Pointing __index straight at the methods
        // table would make s:player():nosuchverb() read plain nil and fail one character later as "attempt
        // to call a nil value" — the failure the whole grammar exists to delete, and the one Player would have
        // been alone in keeping, since the section object here IS the one thing the section contains (§2.1).
        pmt.set(LuaValue.INDEX, Refusal.closedIndex(P, methods,
            "the section object is the character itself",
            "The character it is PLAYING is s:character(), on the Session; where a place falls on the screen "
            + "is s:world():worldToScreen(p), which is a projection rather than anything about the player"));
        pmt.set("__name", LuaValue.valueOf("Player"));
        pmt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Player");
            }
        });
        return LuaValue.userdataOf(new PlayerMark(user), pmt);
    }

    /** How the Player is reached, and so how every one of its messages spells itself. */
    static final String P = "session:player()";

    /**
     * The opaque instance behind a Player userdata (facade-safe: no Java object of the engine's crosses). It
     * carries the account it is the character of, and caches that character's {@link LuaHand} — which hangs
     * <b>here</b> rather than on the Session, so a Player an addon kept keeps its cursor's identity even if it
     * let the Session handle go: {@code pl:hand() == pl:hand()} for as long as the Player itself is alive.
     */
    static final class PlayerMark {
        final String user;
        LuaValue handObj;

        PlayerMark(String user) {
            this.user = user;
        }

        public String toString() { return "Player"; }
    }

    /** The {@code PlayerMark} behind a Player userdata, or {@code null} for anything that is not one. */
    static PlayerMark mark(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof PlayerMark) ? (PlayerMark)o : null;
    }

    // hafen.items is a HARD CUT (029.3, D-013). In Hafen there is no inventory model outside the widget tree —
    // GameUI.maininv is an Inventory exactly like a chest's — so a section of its own only preserved the
    // player-inventory privilege the Widget entity removes. Items are now a RELATION on their container:
    // s:ui():inventory():items() / s:ui():equipment():items() / s:player():hand():item(), and :items() answers on ANY
    // container widget (a chest, a cupboard, another player's equipory) with nothing hidden. `find` had no
    // replacement built for it: it was a name/res substring filter over one array, which is a Lua one-liner over
    // :items(). What a container hands back is the Item entity ({@link LuaItem}), keyed on the item widget.

    /**
     * Build the char section object for {@code (owner, user)} — <b>one character's sheet</b>, reached as
     * {@code s:char()} (077.1). Called once per pair by {@link LuaSession}.
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
     * <p><b>Every verb reads the session it hangs on.</b> {@code user} is the account, and each read
     * re-resolves that character's own {@code CharWnd} through {@link #charwnd(String)} — so a handle kept
     * across a character switch answers about that login, and goes {@code nil}-shaped when it is gone.
     *
     * <p>{@code :lp()} and {@code :weight()} stay plain scalar reads: they are one number each and there is
     * nothing to address into. {@code :food()} hands back the interned {@link LuaFood}, or {@code nil}
     * until the base-attributes tab exists.
     */
    static LuaValue chr(final Addon owner, final String user) {
        final LuaValue attrs = LuaAttr.collection(owner, user);
        final LuaValue skills = LuaSkill.collection(owner, user);
        final LuaValue credos = LuaCredo.collection(owner, user);
        final LuaValue exps = LuaExperience.collection(owner, user);
        LuaTable chr = new LuaTable();
        chr.set("attr", collection("char", C, "attr", attrs));
        chr.set("skill", collection("char", C, "skill", skills));
        chr.set("credo", collection("char", C, "credo", credos));
        chr.set("experience", collection("char", C, "experience", exps));
        // lp() — the learning points the character has banked (CharWnd.exp), nil before the sheet exists.
        chr.set("lp", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "lp", C);
                CharWnd c = charwnd(user);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.exp);
            }
        });
        // weight() — what the character is carrying (CharWnd.enc), nil before the sheet exists.
        chr.set("weight", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "weight", C);
                CharWnd c = charwnd(user);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.enc);
            }
        });
        // food() — the Food object: FEP and hunger, the one place absolute character numbers exist. nil
        // until the base-attributes tab streams in. Subscribe to FepChanged for updates.
        chr.set("food", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "char", "food", C);
                return LuaFood.of(owner, battrwnd(user));
            }
        });
        return Section.object("char", chr, C);
    }

    /** One collection accessor on a section object: a colon call, no arguments, the collection back. */
    private static LuaValue collection(final String nm, final String how, final String verb,
                                       final LuaValue coll) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), nm, verb, how);
                if(Args.passed(a, 2))
                    throw new LuaError(how + ":" + verb + "() takes no arguments — it"
                        + " IS the collection, and :list(filter) / :find(filter) search it");
                return coll;
            }
        };
    }

    /**
     * Build the study section object for {@code (owner, user)} — <b>one character's study window</b>,
     * reached as {@code s:study()} (077.1). Its contents become the {@link LuaStudySlot} collection
     * {@code :slot()} (§4.2); {@code :summary()} is a {@link LuaStudySummary}, the live object
     * {@code s:fight():summary()} already is, so one word answers one kind of thing (085.4).
     *
     * <p><b>077.1: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:study() == s:study()} and every collection under it
     * is minted once for that pair. Every read resolves through that character's own sheet, so a session
     * nobody is looking at answers about itself; one with no HUD yet answers {@code nil}-shaped rather than
     * throwing, exactly as it does before entering the world.
     */
    static LuaValue study(final Addon owner, final String user) {
        final LuaValue slots = LuaStudySlot.collection(owner, user);
        LuaTable study = new LuaTable();
        study.set("curiosity", collection("study", ST, "curiosity", slots));
        // summary() — that character's learning-point, attention and experience totals, nil before the tab
        // is built. A StudySummary object, the same kind of answer s:fight():summary() gives.
        study.set("summary", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "study", "summary", ST);
                if(Args.passed(a, 2))
                    throw new LuaError(ST + ":summary() takes no arguments — the three totals are its own"
                        + " verbs, and sum:info() is the whole table at once");
                return LuaStudySummary.of(owner, studyInfo(user));
            }
        });
        return Section.object("study", study, ST);
    }

    /**
     * Build the party section object for {@code (owner, user)} — <b>the party THAT character is in</b>,
     * reached as {@code s:party()} (077.2). <b>The section object IS the roster</b> (uniform grammar §2.1):
     * it is the {@link LuaPartyMember} collection, one member is {@code s:party():get(gobId)} and
     * {@code :leader()} is the distinguished member (R8) rather than a second accessor. Every member hands
     * back a live Gob through {@code member:gob()}, and it is <b>that session's</b> copy of the object.
     *
     * <p><b>Two characters are two parties</b>, even when both are in one: a party is read off
     * {@link Glob#party}, and a {@link Glob} is one login's. So the roster, the leader and every member's
     * position answer for the character {@code s} names — including the last-known position the server
     * sent <i>that</i> session, which is not the one it sent the other.
     */
    static LuaValue party(Addon owner, String user) {
        return LuaPartyMember.collection(owner, user);
    }

    /**
     * Build the kin section object for {@code (owner, user)} — <b>that character's roster</b>, reached as
     * {@code s:kin()} (077.2). <b>The section object IS the roster</b> (uniform grammar §2.1): it is the
     * {@link LuaCollection} {@link LuaKin#collection} builds, and one kin is {@code s:kin():get(idOrName)}.
     * Only the kin-side plumbing the event adapter still needs ({@link #buddywnd}, {@link #kinSnapshot},
     * {@link #kinListEqual}, {@link #kinIds}) stays here.
     *
     * <p><b>A buddy id counts inside one roster.</b> The Kin window is {@link GameUI#buddies}, which is one
     * login's HUD, so id 7 on two characters is two different people — which is why a Kin handle carries
     * the account beside the id and interns on the pair.
     *
     * <p><b>The five protected verbs are addressable, and keep the one key they have.</b> A key names the
     * action, not the target: {@code conventions.md} calls a verb protected when it starts an action the
     * player could have performed, and the player could have tabbed to that character and performed it. A
     * second grant per session would mean an addon the user allowed to add kin cannot add kin on an alt, a
     * distinction the user never drew — every one of those characters is theirs. The send needs no
     * anchor either: {@link BuddyWnd.Buddy}'s own methods go through the widget's own tree
     * ({@code Widget.wdgmsg} → that {@code UI}), so a write lands on the session it was addressed at.
     */
    static LuaValue kin(Addon owner, String user) {
        return LuaKin.collection(owner, user);
    }

    /**
     * Build the quest section object for {@code (owner, user)} — <b>one character's log</b>, reached as
     * {@code s:quest()} (077.1). <b>The section object IS the log</b> (uniform grammar §2.1): it is the
     * {@link LuaQuest} collection over both tabs, one quest is {@code s:quest():get(id)} and
     * {@code :selected()} is the distinguished member (R8) — the quest that character has open, and the only
     * one whose objectives the client is sent. The section is the SINGULAR name (§2.3): the noun says the
     * kind and the verb says how many.     *
     * <p><b>077.1: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:quest() == s:quest()} and every collection under it
     * is minted once for that pair. Every read resolves through that character's own sheet, so a session
     * nobody is looking at answers about itself; one with no HUD yet answers {@code nil}-shaped rather than
     * throwing, exactly as it does before entering the world.
     */
    static LuaValue quests(Addon owner, String user) {
        return LuaQuest.collection(owner, user);
    }

    /**
     * Build the wound section object for {@code (owner, user)} — <b>one character's wounds</b>, reached as
     * {@code s:wound()} (077.1). <b>The section object IS the wound list</b> (uniform grammar §2.1): it is
     * the {@link LuaWound} collection in the window's own tree order, and {@code s:wound():find(needle)} is
     * the presence test, handing back the Wound rather than a boolean. Singular, like every other section
     * (§2.3).     *
     * <p><b>077.1: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:wound() == s:wound()} and every collection under it
     * is minted once for that pair. Every read resolves through that character's own sheet, so a session
     * nobody is looking at answers about itself; one with no HUD yet answers {@code nil}-shaped rather than
     * throwing, exactly as it does before entering the world.
     */
    static LuaValue wounds(Addon owner, String user) {
        return LuaWound.collection(owner, user);
    }

    /**
     * Build the fight section object for {@code (owner, user)} — <b>one character's combat schools, and the
     * fight it is in</b>, reached as {@code s:fight()} (077.4). Three projections of that character's
     * combat-schools tab plus one read of its live combat view: {@code :maneuver()} is the collection of what
     * it knows, {@code :deck()} the loaded school's layout as a plain array (§2.3 — a layout is addressed by
     * its own order), {@code :summary()} the scalars around it, and {@code :target()} who it is fighting.
     *
     * <p><b>A school is configured on one character and a fight is fought by one body.</b> Both halves read
     * the named session's own widgets — its {@link FightWnd} through {@link #fightwnd(String)} and its
     * {@link haven.Fightview} through the HUD — so a character nobody is looking at answers about its own
     * deck and its own opponent. One with no HUD yet answers {@code nil}-shaped, exactly as it does before
     * entering the world.
     *
     * <p><b>077.4: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:fight() == s:fight()} and the maneuver
     * collection under it is minted once for that pair.
     */
    static LuaValue fight(final Addon owner, final String user) {
        final LuaValue maneuvers = LuaManeuver.collection(owner, user);
        LuaTable fight = new LuaTable();
        // maneuver() — every maneuver and attack THAT character knows, minted once and handed back by identity.
        fight.set("maneuver", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "maneuver", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":maneuver() takes no arguments — it IS the collection,"
                        + " and :list(filter) / :find(filter) search it");
                return maneuvers;
            }
        });
        // deck() — the filled hotkey slots of the school THAT character has loaded, in key order. A plain
        // array, never nil, and empty before its schools tab has built.
        fight.set("deck", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "deck", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":deck() takes no arguments — it is a layout, ordered by hotkey,"
                        + " and every card carries its own :index() and :key()");
                return LuaDeckCard.deck(owner, user);
            }
        });
        // summary() — that character's action-point budget and saved-school slots, nil before the tab is built.
        fight.set("summary", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "summary", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":summary() takes no arguments — the five counts are its own"
                        + " verbs, and sum:info() is the whole table at once");
                return LuaFightSummary.of(owner, fightwnd(user));
            }
        });
        // target() — who THAT character is fighting, nil out of combat. An Opponent, whose :gob() is the
        // creature, resolved in the session the fight is in.
        fight.set("target", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "target", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":target() takes no arguments — there is one opponent picked,"
                        + " and target:gob() is the creature it names");
                return LuaOpponent.target(owner, user);
            }
        });
        return Section.object("fight", fight, FT);
    }

    /**
     * Build the buff section object for {@code (owner, user)} — <b>one character's buff bar</b>, reached as
     * {@code s:buff()} (077.1). <b>The section object IS the bar</b> (uniform grammar §2.1): it is the
     * {@link LuaCollection} of that character's active buffs and {@code s:buff():find(needle)} the first
     * whose res or name contains it. A buff has no key, so the collection carries no {@code :get}; the reads
     * live on the {@link LuaBuff} object itself.     *
     * <p><b>077.1: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:buff() == s:buff()} and every collection under it
     * is minted once for that pair. Every read resolves through that character's own sheet, so a session
     * nobody is looking at answers about itself; one with no HUD yet answers {@code nil}-shaped rather than
     * throwing, exactly as it does before entering the world.
     */
    static LuaValue buffs(Addon owner, String user) {
        return LuaBuff.collection(owner, user);
    }

    /**
     * Build the meter section object for {@code (owner, user)} — <b>one character's HUD bars</b>, reached as
     * {@code s:meter()} (077.1). <b>The section object IS the meter slot</b> (uniform grammar §2.1, spec
     * {@code 027-meters-oop}): it is the {@link LuaCollection} of every meter in that HUD's meter slot and
     * {@code s:meter():find(needle)} the first whose server-published res name contains it. A meter has no
     * key, so the collection carries no {@code :get}; the reads live on the {@link LuaMeter} object
     * itself.     *
     * <p><b>077.1: it is built per {@code (addon, session)}</b> and hung on the interned Session handle, the
     * shape {@link WorldApi#world} established — so {@code s:meter() == s:meter()} and every collection under it
     * is minted once for that pair. Every read resolves through that character's own sheet, so a session
     * nobody is looking at answers about itself; one with no HUD yet answers {@code nil}-shaped rather than
     * throwing, exactly as it does before entering the world.
     */
    static LuaValue meters(Addon owner, String user) {
        return LuaMeter.collection(owner, user);
    }

    /**
     * Build the action-bar section object for {@code (owner, user)} — <b>one character's hotbar</b>, reached
     * as {@code s:actionbar()} (077.3). <b>The section object IS the bar</b> (uniform grammar §2.1): it is
     * the {@link LuaCollection} of all 144 slots, {@code s:actionbar():get(n)} is one {@link LuaSlot} by its
     * raw game index, and the reads and the three writes live on the Slot object itself.
     *
     * <p><b>A slot index names one character's bar.</b> {@link GameUI#belt} is one login's array, so slot 11
     * on two characters is two different buttons — which is why a Slot handle carries the account beside the
     * index and interns on the pair. The holds and the placements {@link BeltHold} keeps were already that
     * character's (073.3); what 077.3 moves is the address an addon reaches them by.
     *
     * <p><b>The two protected verbs keep the one key each has.</b> A key names the action, not the target,
     * and the sends leave from that character's own widgets — {@code GameUI.wdgmsg} for an assignment and
     * that HUD's own {@code beltwdg} for a press — so a write lands on the bar it was addressed at whether
     * or not anyone is looking at it.
     */
    static LuaValue actionbar(Addon owner, String user) {
        return LuaSlot.collection(owner, user);
    }

    /**
     * Build the speed section object for {@code (owner, user)} — <b>one character's movement speed</b>,
     * reached as {@code s:speed()} (077.3). <b>The section object IS the collection</b> of the speeds that
     * character can pick right now (uniform grammar §2.1), {@code :current()} is the one it is on and
     * {@code :set(x)} the protected verb that picks one.
     *
     * <p><b>Every character has its own selector.</b> {@link Speedget} is a widget under one login's HUD and
     * both fields read off it — {@code cur} and {@code max} — are that character's, so a Speed handle carries
     * the account beside the wire index: speed 3 unlocked on one character says nothing about the other. The
     * send is the client's own {@code Speedget.set}, which goes through that widget's own tree.
     */
    static LuaValue speed(Addon owner, String user) {
        return LuaSpeed.collection(owner, user);
    }

    /**
     * Build the craft section object for {@code (owner, user)} — <b>the recipe THAT character has open</b>,
     * reached as {@code s:craft()} (077.3). The section is one verb, {@code :current()}, and the recipe it
     * hands back carries its own reads and its own protected {@code :make(all)}; see {@link ActApi#craft}.
     *
     * <p><b>A window the game put up is open on a session nobody is looking at.</b> A background session keeps
     * its {@link GameUI}, so its recipe window stands in its tree and answers, which is the whole reason a
     * crafting addon across characters is worth writing. Where that character has no recipe open it answers
     * the same {@code nil} a drawn session with none answers — no new absence is invented.
     */
    static LuaValue craft(Addon owner, String user) {
        return ActApi.craft(owner, user);
    }

    /**
     * Build the menu section object for {@code (owner, user)} — <b>the catalogue THAT character carries</b>,
     * reached as {@code s:menugrid()} (077.3). <b>The section object IS the catalogue</b> (uniform grammar
     * §2.1): it is the {@link LuaPagina} collection over that login's {@link MenuGrid}, and one entry is
     * {@code s:menugrid():get(key)}.
     *
     * <p><b>The catalogue is one character's.</b> Two characters know different actions, and the menu is a
     * widget under one login's HUD — so a resource name that names an entry on one names nothing on the
     * other, and a Pagina handle carries the account beside the name. An entry an addon adds is added to the
     * grid it was addressed at, and the same id may stand in each character's menu.
     */
    static LuaValue menugrid(Addon owner, String user) {
        return LuaPagina.collection(owner, user);
    }

    // ------------------------------------------------------------- items / char / party reads

    /** The nine base character attributes (content-defined; not discoverable from {@link Glob}). */
    private static final String[] ATTR_NAMES =
        {"str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"};

    /**
     * <b>That character's</b> main inventory widget, or {@code null} before its HUD exists.
     *
     * <p>078.2: off {@link AddonManager#gameui(String)}, the named session's own HUD, and never off the drawn
     * one — two characters carry two backpacks, and a session nobody is looking at keeps its {@code GameUI},
     * so its backpack is open and its contents readable.
     */
    static Inventory maininv(String user) {
        GameUI g = gameui(user);
        return (g == null) ? null : g.maininv;
    }

    /**
     * <b>That character's</b> equipment widget: the {@link Equipory} under its HUD. {@code GameUI.equwnd} is a
     * private {@code Window}, so we descend to the Equipory itself — typically the only one open under that
     * HUD (a second appears only while that character is inspecting another gob's equipment). {@code null}
     * before it exists.
     */
    static Equipory equipory(String user) {
        GameUI g = gameui(user);
        if(g != null) {
            for(Equipory e : g.children(Equipory.class))
                return e;
        }
        return null;
    }

    /**
     * <b>That character's</b> sheet (created hidden at login, but live), or {@code null} before it exists —
     * the one funnel every read of the six session-addressed sections resolves through, every call (D-012).
     *
     * <p>077.1: off {@link AddonManager#gameui(String)}, the named session's own HUD, and never off the
     * drawn one. Two characters have two sheets, and a read taken through {@code s:char()} is about the
     * character {@code s} names whether or not the player is looking at it.
     */
    static CharWnd charwnd(String user) {
        GameUI g = gameui(user);
        return (g == null) ? null : g.chrwdg;
    }


    /**
     * The human-readable equipment slot name for an ep index, or nil.
     *
     * <p>(102.6) Read off {@link Equipory#ettstr}, the string the slot's background resource named it,
     * <b>not</b> {@code etts[ep].text}: that one is a raster, and a {@link io.brodgar.addon.LocaleApi
     * catalogue} installed before this class loads would make it the display string. The raster is the
     * fallback for a slot whose source is missing, which is the same best-effort this always was.
     */
    static LuaValue slotName(int ep) {
        if((ep >= 0) && (ep < Equipory.ettstr.length)) {
            if(Equipory.ettstr[ep] != null)
                return LuaValue.valueOf(Equipory.ettstr[ep]);
            if(Equipory.etts[ep] != null)
                return LuaValue.valueOf(Equipory.etts[ep].text);
        }
        return LuaValue.NIL;
    }

    /**
     * {@code ItemInfo.Name} text for an item, or {@code null} (Loading-guarded).
     *
     * <p>(102.6) The name row's <b>source</b> — what the tip was written — rather than {@code str.text},
     * which is what it drew: reading a name is itself what builds the tip, so a {@code tooltip} entry
     * naming this row would otherwise come straight back out of this verb. A {@code Name} the caller
     * handed a rendered {@link haven.Text} has no source, and its raster is the only name there is.
     */
    static String itemNameOf(GItem it) {
        try {
            return nameStr(ItemInfo.find(ItemInfo.Name.class, it.info()));
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /**
     * <b>What a name row says, in the client's own English</b> (102.6) — the one read of an
     * {@link ItemInfo.Name}, shared by an item, a buff and a wound, so the three cannot drift apart on
     * which of the row's two strings they answer.
     */
    static String nameStr(ItemInfo.Name n) {
        if(n == null)
            return null;
        String src = n.source();
        if(src != null)
            return src;
        return (n.str == null) ? null : n.str.text;
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
    static SAttrWnd.StudyInfo studyInfo(String user) {
        CharWnd c = charwnd(user);
        if((c == null) || (c.sattr == null))
            return null;
        synchronized(LuaWidget.monitor(c)) {   // audit2 B06: a live child walk, under the tree that owns it
            for(SAttrWnd.StudyInfo si : c.sattr.children(SAttrWnd.StudyInfo.class))
                return si;
        }
        return null;
    }

    /** The study inventory widget itself ({@code StudyInfo.study}), or {@code null} before the sattr tab
     *  has streamed in — {@link StudyAdapter}'s parent filter for placement/removal, the study analogue
     *  of {@link #equipory}. */
    static Widget studyWidget(String user) {
        SAttrWnd.StudyInfo si = studyInfo(user);
        return (si == null) ? null : si.study;
    }

    /** Do two study-slot snapshots carry the same res/name/lp/attention/cost/time/progress? (for
     *  change-detection.) */
    private static boolean studySlotEqual(LuaValue a, LuaValue b) {
        if((a == null) || (b == null))
            return false;
        return luaFieldEq(a, b, "res") && luaFieldEq(a, b, "name") && luaFieldEq(a, b, "lp")
            && luaFieldEq(a, b, "attention") && luaFieldEq(a, b, "cost") && luaFieldEq(a, b, "time")
            && luaFieldEq(a, b, "progress");
    }

    /** That character's "Lore &amp; Skills" widget ({@link SkillWnd}), or {@code null}. */
    static SkillWnd skillwnd(String user) {
        CharWnd c = charwnd(user);
        return (c == null) ? null : c.skill;
    }


    // ------------------------------------------------ action bar / hotbar + equipment (1d-4)
    // NB the engine calls the action bar the "belt" (GameUI.belt / BeltSlot / setbelt) — H&H's own term;
    // the addon-facing API deliberately exposes it as `s:actionbar()` (clearer, WoW-like). These helpers
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
        String rn = actionbarRes(s);
        if(rn != null)
            t.set("res", LuaValue.valueOf(rn));
        String name = actionbarName(s, r);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Double cd = actionbarCooldown(s);
        if(cd != null)
            t.set("cooldown", LuaValue.valueOf(cd));
        return t;
    }

    /**
     * The slot's resource name — the identity {@code slot:res()} answers. <b>A slot an addon is holding names
     * the entry</b> (059.4): every custom entry is constructed over one shared stand-in resource, so reading
     * that resource here would report the menu's paging arrow for every held slot in the bar. What it answers
     * instead is the {@code addon/<addon id>/<id>} identity {@code pag:res()} speaks — the same string in
     * every session, and the one {@link BeltHold} was handed.
     */
    static String actionbarRes(GameUI.BeltSlot s) {
        AddonPagina p = actionbarEntry(s);
        if(p != null)
            return p.id;
        Resource r = actionbarResObj(s);
        return (r == null) ? null : r.name;
    }

    /** The custom entry a HELD slot draws, or {@code null} for every slot whose content is the server's. */
    private static AddonPagina actionbarEntry(GameUI.BeltSlot s) {
        if(s instanceof GameUI.PagBeltSlot) {
            MenuGrid.Pagina p = ((GameUI.PagBeltSlot)s).pag;
            if(p instanceof AddonPagina)
                return (AddonPagina)p;
        }
        return null;
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
        AddonPagina custom = actionbarEntry(s);
        if(custom != null)
            return custom.name();      // the RAW string the addon set, never the tip's escaped one (059.4)
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
     * <b>That character's</b> {@link Party}, or {@code null} before its session has a world. Read by
     * {@link LuaPartyMember}, every call (D-012).
     *
     * <p>077.2: off {@link AddonManager#glob(String)}, the named session's own {@link Glob}, and never off
     * the drawn one. Two characters in one party are still two {@code Party} objects, each holding the
     * positions and colours the server sent <i>that</i> login.
     */
    static Party partyOf(String user) {
        Glob g = glob(user);
        return (g == null) ? null : g.party;
    }

    /**
     * That character's party members ordered by {@link Party.Member#seq} — the roster order
     * {@code s:party():list()} hands out. {@code party.memb} is replaced wholesale off-thread, so a
     * {@code values()} copy is snapshot-safe (defensive catch for the rare in-flight swap).
     */
    static List<Party.Member> partyMembers(String user) {
        List<Party.Member> out = new ArrayList<Party.Member>();
        Party p = partyOf(user);
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


    // ---- kin / buddy (A6: s:kin()) ---------------------------------------------------------------
    // The kin/buddy roster lives in the BuddyWnd (GameUI.buddies) — the same widget the in-client Kin tab
    // shows. Its Buddy list is mutated on the network/loader thread as the server pushes add/rm/chst/upd
    // uimsgs; BuddyWnd.iterator() copies the list under its own lock, so iterating it is snapshot-safe.
    // All our reads run on the UI thread (addon tick / REPL). online is a tri-state internally (1 online,
    // 0 offline, -1 hearth-secret-only) that we expose as a boolean (online == 1) — the common "is this
    // kin online" question; the group index maps to a fixed colour palette (BuddyWnd.gc).
    //
    // Since 020-kin-oop the Lua-facing surface is OOP and lives in LuaKin (s:kin() = the roster
    // collection, :get(idOrName) = an interned Kin object, protected verbs on the object). What stays HERE is the
    // plumbing LuaKin and the KinAdapter share: the buddywnd(user) resolve funnel, the kinSnapshot() escape
    // hatch (kin:info()) and the snapshot diff that drives KinChanged.

    /**
     * <b>That character's</b> Kin window ({@link GameUI#buddies}), or {@code null} before its HUD exists.
     * The one resolve funnel: {@link LuaKin} re-reads every Kin object through it, every call (D-012).
     *
     * <p>077.2: off {@link AddonManager#gameui(String)}, the named session's own HUD, and never off the
     * drawn one. Two characters have two rosters and two id spaces, so a kin read — or renamed — through
     * the wrong one is somebody else entirely.
     */
    static BuddyWnd buddywnd(String user) {
        GameUI g = gameui(user);
        return (g == null) ? null : g.buddies;
    }

    /** A kin snapshot: {@code {id, name, group, color={r,g,b,a}, online(bool)}} — {@code kin:info()}'s
     *  answer (the one snapshot escape hatch) and the change-detection input below. */
    static LuaValue kinSnapshot(BuddyWnd.Buddy b) {
        if(b == null)
            return LuaValue.NIL;
        // audit2 B06: the three published fields read together, under the monitor BuddyWnd writes them
        // under -- so one snapshot is one roster row rather than a name from before a rename and a group
        // from after it.
        String name;
        int group, online;
        synchronized(b) {
            name = b.name;
            group = b.group;
            online = b.online;
        }
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(b.id));
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        t.set("group", LuaValue.valueOf(group));                 // the true index, 0..254, palette or not
        // No `color` for a group above the 8-colour palette — same answer as kin:color(), and for the same
        // reason: the engine's ungrouped-colour fallback (BuddyWnd.gcolor) is a draw, not the group's colour.
        if((group >= 0) && (group < BuddyWnd.ncolors))
            t.set("color", color(BuddyWnd.gc[group]));
        t.set("online", LuaValue.valueOf(online == 1));
        return t;
    }

    /** The whole roster as snapshots, in the window's current sort order — the {@link KinAdapter}'s
     *  change-detection input (never a Lua-facing list any more: Lua sees Kin objects, {@link LuaKin}). */
    private static LuaValue kinSnapshotList(String user) {
        LuaTable out = new LuaTable();
        BuddyWnd bw = buddywnd(user);
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
    static QuestWnd questwnd(String user) {
        CharWnd c = charwnd(user);
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
    // what decwound itself looks a wound up by before mutating it in place). WoundChanged is fired by
    // WoundAdapter (see its class doc above) off the "wounds" uimsg for the list and a Resolve retry for a
    // severity streaming in a beat later (like study's Curiosity info) — event-driven since 042.5, not a
    // per-tick diff. What stays here is that change detection.

    /** The Health &amp; Wounds window (the character sheet's "Health & Wounds" tab — created hidden at login
     *  but live), or {@code null} before it exists. Via the public {@code CharWnd.wound} field (no tree-walk). */
    static WoundWnd woundwnd(String user) {
        CharWnd c = charwnd(user);
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

    // ---- combat schools (A10: s:fight()) ---------------------------------------------------------
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
    // switching schools (wdgmsg load/save/use, drag, set counts) is the protected Phase-4 tier.
    //
    // Threading: the FightWnd.uimsg handlers run on a Loader thread under synchronized(ui): "avail" REPLACES
    // acts wholesale, "used"/"max" mutate act.u / maxact / order[] entries, and Actions.tick re-sorts acts on
    // the UI thread. So — the marker discipline — we copy the acts list / order[] array and read the scalars
    // under the ui monitor, then resolve resource names OUTSIDE the lock (res.get() may Loading). The public
    // int reads (a/u/maxact/usesave) outside the lock are snapshot-atomic like A9-2's wound ints.

    /** <b>That character's</b> Combat Schools window (its own sheet's "Martial Arts &amp; Combat Schools"
     *  tab — created hidden at login but live), or {@code null} before it exists. Via the public
     *  {@code CharWnd.fight} field. It is the one funnel {@link LuaManeuver}, {@link LuaDeckCard} and
     *  {@link LuaFightSummary} resolve through, every call (D-012).
     *
     *  <p>077.4: off {@link #charwnd(String)}, the named session's own sheet, and never off the drawn one.
     *  Two characters configure two schools, and a deck read through {@code s:fight()} is that character's
     *  whether or not the player is looking at it. */
    static FightWnd fightwnd(String user) {
        CharWnd c = charwnd(user);
        return (c == null) ? null : c.fight;
    }
}
