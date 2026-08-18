package io.brodgar.addon;

import haven.GameUI;
import haven.MenuGrid;

import org.luaj.vm2.LuaError;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.brodgar.addon.AddonManager.SessionState;
import static io.brodgar.addon.AddonManager.state;

/**
 * <b>A bar slot held for an addon's own menu entry</b> ({@code slot:pagina(pag)}, spec
 * {@code 059-menugrid-entries}) — the layer that lets an {@link AddonPagina} draw and fire on the action bar
 * without ever going onto it.
 *
 * <p><b>Every entry point says which character's bar it is about</b> (077.3). The maps were already that
 * character's (073.3); what moves is the address the Lua verbs reach them by — a Slot carries the account,
 * an {@link AddonPagina} carries the account, and neither resolves the drawn session any more. So an addon
 * holds a slot on a character nobody is looking at, and a {@code :reload} puts each login's buttons back on
 * its own bar.
 *
 * <p><b>The server owns the bar.</b> {@code GameUI.belt} is the server's array: it stores the hotbar, it
 * echoes every assignment back through {@code setbelt}/{@code setbelt2}, and it has never heard of an
 * {@code addon/<addon id>/<id>} identity. So a custom entry does not go <i>into</i> a slot — the client
 * <b>holds</b> that slot: a {@code GameUI.PagBeltSlot} over the entry is written into {@code belt[n]}, the
 * {@link GameUI.BeltSlot} that was there is kept, and it goes back untouched the moment the hold ends. The
 * server is told nothing, and its own view of the slot never changes.
 *
 * <p><b>Drawing and firing come for free.</b> {@code PagBeltSlot.draw} calls {@code pag.button().spr()} and
 * {@code PagBeltSlot.use} calls {@code pag.scm.use(pag.button(), …)}, so the icon in the slot and the key
 * that fires it are the very {@link AddonPagina.AddonPagButton} the menu grid draws and clicks — one button,
 * two places, and no second path that could answer differently.
 *
 * <p><b>Five ways a hold ends</b>, and every one of them puts the slot back: {@code slot:pagina(nil)}, a
 * right-click on the slot ({@link #release}, which is why the right-click is not sent), the entry being
 * {@code :remove}d ({@link #entryRemoved}), the addon being reloaded or disabled ({@link #teardownHolds}),
 * and a <b>server write</b> to that slot ({@link #serverWrote}) — the one case where nothing is put back,
 * because what the server just wrote <i>is</i> the slot's content now.
 *
 * <p><b>The bar is one shared surface.</b> Unlike an entry, a slot belongs to nobody: the player drags what
 * they like onto it and {@code slot:res(name)} overwrites whatever is there, so a hold taken over a hold is
 * allowed and the last write wins. What is preserved through the whole pile is the <b>server's</b> content,
 * carried across in {@link Hold#displaced}, so however many addons take that slot in turn, one release puts
 * back exactly what the server has.
 *
 * <p><b>A hold outlives the session it was taken in</b> (059.5). Where a hold is what the client is drawing
 * right now, a <b>placement</b> is the player's standing intent for that slot — kept per character in the
 * session's own map, persisted through {@link StoreApi#writeClientFile}, and re-applied by {@link #entryAdded}
 * the moment an entry with that identity is added again. At login that is the addon's own
 * {@code SessionEnteredWorld}
 * {@code :add}, so nothing has to guess when the bar is ready. The two endings above split here: a hold
 * <b>released by hand</b> — {@code slot:pagina(nil)}, a right-click, the server taking the slot — is
 * <b>forgotten</b>, while one whose <b>entry merely went away</b> — {@code :remove}, a {@code :reload},
 * disable, a logout — is <b>remembered</b>, because the slot is still where that entry belongs.
 *
 * <p><b>Whose bar</b> (073.3). A slot index names <b>one character's</b> action bar, so both maps live in
 * that session's {@code SessionState} and every entry point says which session it is about. Three of them are
 * handed the {@code GameUI} by the seam that reached them ({@link #release}, {@link #dropped},
 * {@link #serverWrote} — all three are inside {@code GameUI}, so {@code GameUI.this} is the answer and
 * {@code g.ui} the key); the tick's are handed the state it already holds; and each {@link Hold} carries the
 * bar it was taken on, which is what lets an ending put a slot back on the bar it was borrowed from rather
 * than on whichever one is drawn at that moment — the case that matters, because {@code init} tears the old
 * session's addons down once {@link AddonManager#host()} ALREADY answers the session being switched to. The
 * verbs an addon calls ({@code slot:pagina(pag)}, {@code s:menugrid():add(id)}) name their own session, and
 * that is the same bar the rest of {@code LuaSlot} reads.
 *
 * <p><b>Threading.</b> The Lua verbs and the mouse hooks run on the UI thread; {@link #serverWrote} runs on
 * the message thread, under {@code synchronized(ui)}, from {@code GameUI.uimsg}. The maps are therefore
 * guarded on one monitor for the whole layer — the state moved per session, the lock did not, because what
 * it guards is this layer's own invariant and there is no contention worth splitting it for — and
 * {@link #serverWrote} touches neither {@code belt} nor Lua: it drops a record and nothing else. The disk
 * write is the same shape: every mutation marks the session's dirty flag and {@link #flush} runs on the tick,
 * so no file I/O ever stands on the message thread. The placements are re-read per character
 * ({@link #restore}): a slot index means another character's bar after a relogin.
 */
public final class BeltHold {
    /**
     * One held slot: the entry drawn there, what it displaced, the slot object we actually wrote — and
     * <b>the bar it was taken on</b> (073.3), so an ending that has no {@code GameUI} in hand still puts the
     * slot back where it was borrowed from instead of into whichever HUD happens to be drawn when it arrives.
     */
    static final class Hold {
        final AddonPagina pag;
        /** The HUD whose {@code belt} this hold is written into. Goes with its session's state. */
        final GameUI gui;
        /**
         * The server's own content, kept untouched for the release. {@code null} for a slot that was empty, and
         * re-read by {@link #writeLanded} when a server write that <i>predates</i> this hold lands late.
         */
        GameUI.BeltSlot displaced;
        /** What we put in {@code belt[n]} — the identity guard: only our own write is ever taken back out. */
        final GameUI.BeltSlot drawn;

        Hold(AddonPagina pag, GameUI gui, GameUI.BeltSlot displaced, GameUI.BeltSlot drawn) {
            this.pag = pag;
            this.gui = gui;
            this.displaced = displaced;
            this.drawn = drawn;
        }
    }

    /** The layer's own per-character file, beside the addons' saved variables. */
    private static final String FILE = "actionbar-holds.json";

    private BeltHold() {
    }

    /**
     * The entry a slot is being held for, or {@code null} for every slot the server owns — the read half of
     * {@code slot:pagina()}, on the bar the Slot names, like every other read on {@code LuaSlot}.
     */
    static synchronized AddonPagina held(String user, int n) {
        GameUI g = AddonManager.gameui(user);      // 077.3: the bar the Slot names, not the drawn one
        SessionState st = (g == null) ? null : state(g.ui);
        if(st == null)
            return null;
        Hold h = st.beltHolds.get(Integer.valueOf(n));
        return (h == null) ? null : h.pag;
    }

    /**
     * <b>Hold slot {@code n} for {@code pag}</b> — the write half of {@code slot:pagina(pag)} and the whole of
     * a drag from the grid. What the slot had is remembered <i>once</i>: a second hold on the same slot
     * carries the original {@link Hold#displaced} across, so the server's content survives any number of
     * addons taking that slot in turn rather than being replaced by the previous addon's button.
     */
    static synchronized void hold(String user, int n, AddonPagina pag) {
        // 077.3: the bar the Slot NAMES, which is also the grid the entry stands in — the two are that one
        // character's pair, and the drawn session has nothing to do with either. Its ui is then the session
        // the record belongs to.
        GameUI g = AddonManager.gameui(user);
        SessionState st = (g == null) ? null : state(g.ui);
        if((g == null) || (g.belt == null) || (st == null) || (n < 0) || (n >= g.belt.length))
            throw new LuaError("slot:pagina(pagOrNil): that character has no action bar yet — hold a slot"
                + " from SessionEnteredWorld or later, not from Load");
        hold(st, g, n, pag);
    }

    /** The whole of {@link #hold}, once the bar and the session it belongs to are settled. */
    private static synchronized void hold(SessionState st, GameUI g, int n, AddonPagina pag) {
        Integer key = Integer.valueOf(n);
        Hold cur = st.beltHolds.get(key);
        if((cur != null) && (cur.pag == pag) && (g.belt[n] == cur.drawn))
            return;                             // already exactly this — a hold is a state, not an event
        GameUI.BeltSlot displaced = (cur != null) ? cur.displaced : g.belt[n];
        GameUI.BeltSlot drawn = new GameUI.PagBeltSlot(n, pag);
        g.belt[n] = drawn;
        st.beltHolds.put(key, new Hold(pag, g, displaced, drawn));
        place(st, n, pag.id);                   // 059.5: and this is where that entry belongs, from now on
        AddonManager.onBeltSet(g, n);           // ActionbarChanged for the taking edge, on the next tick
    }

    /**
     * <b>The server's write for slot {@code n} has landed</b> — from the tick's belt-write drain, on the UI
     * thread, right before the {@code ActionbarChanged} for it goes out. Two of the arms that write
     * {@code belt[n]} hand the write to a {@code glob.loader.defer} task, so it lands <i>after</i> the message
     * that carried it; a hold taken in between would be silently painted over, its {@code drawn} object dropped
     * out of {@code belt} with the record still saying it is there.
     *
     * <p><b>A hold that is still on record predates the write</b>, and that is the whole test: the message
     * itself already ended any hold it found ({@link #serverWrote}, at message time), so anything left here was
     * taken after the server spoke and stands. That is the login case exactly — the belt burst is dispatched
     * around the time the HUD is built, its resource-backed slots land over the following moments, and the
     * addon's {@code SessionEnteredWorld} re-apply falls between the two. What just landed becomes the hold's
     * {@link Hold#displaced} content, so the release still hands the slot back holding the server's own
     * current action rather than the emptiness that stood there when the hold was taken.
     */
    static synchronized void writeLanded(SessionState st, int n) {
        Hold h = st.beltHolds.get(Integer.valueOf(n));
        if(h == null)
            return;
        GameUI g = h.gui;                       // 073.3: the bar this hold was taken on, not the drawn one
        if((g.belt == null) || (n < 0) || (n >= g.belt.length) || (g.belt[n] == h.drawn))
            return;
        h.displaced = g.belt[n];
        g.belt[n] = h.drawn;
    }

    /**
     * <b>End the hold on slot {@code n}</b> and put the server's own content back, whether there was one or
     * not — {@code slot:pagina(nil)}, a right-click, a removed entry, a torn-down addon. {@code true} when
     * there was a hold to end, which is what makes the right-click hook send nothing.
     *
     * <p>The slot is restored only when it still holds <b>our</b> slot object: a server write that landed
     * meanwhile is the content now, and putting the displaced original back over it would resurrect an action
     * the server no longer has there.
     */
    public static boolean release(GameUI g, int n) {
        SessionState st = (g == null) ? null : state(g.ui);
        return (st != null) && release(st, n, true);
    }

    /**
     * {@code slot:pagina(nil)} — the addon's own way to end a hold, on the bar the Slot names like the rest
     * of {@code LuaSlot}.
     */
    static synchronized boolean release(String user, int n) {
        return release(AddonManager.gameui(user), n);
    }

    /**
     * The whole of {@link #release}, plus <b>whether the placement goes with it</b> (059.5). Ending a hold by
     * hand says the entry no longer belongs in that slot; an entry leaving the menu says nothing of the kind,
     * so it keeps its slot and takes it again the moment it is added back.
     *
     * <p>073.3: the slot is put back into the bar the {@link Hold} was taken on. That is the same bar the
     * caller named in every ordinary case, and it is a <i>different</i> one exactly when it matters — a
     * teardown running from {@code init}, at a moment when the drawn HUD is already the session being switched
     * to and writing this session's displaced content into it would be writing into another character's bar.
     */
    private static synchronized boolean release(SessionState st, int n, boolean forget) {
        Hold h = st.beltHolds.remove(Integer.valueOf(n));
        if(h == null)
            return false;
        if(forget)
            unplace(st, n);
        GameUI g = h.gui;
        if((g.belt != null) && (n >= 0) && (n < g.belt.length) && (g.belt[n] == h.drawn))
            g.belt[n] = h.displaced;
        AddonManager.onBeltSet(g, n);           // ActionbarChanged for the releasing edge too
        return true;
    }

    /**
     * A drag from the menu grid dropped on slot {@code n} ({@code GameUI.Belt.dropthing}). {@code true} when
     * this is an addon's own entry and the layer took it — the drop then <b>sends nothing</b>, where the stock
     * body would {@code wdgmsg("setbelt", …)} a name the server has never heard of and drop it silently.
     */
    public static boolean dropped(GameUI g, int n, MenuGrid.Pagina pag) {
        if(!(pag instanceof AddonPagina))
            return false;
        // 073.3: the bar the drop landed on, and no other. A bar with no session behind it can hold no
        // AddonPagina — there would be no addon to have made one — so this refusal is the unreachable
        // branch, and it hands the drop back to the stock body rather than raising from inside dropthing.
        SessionState st = (g == null) ? null : state(g.ui);
        if((st == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length))
            return false;
        hold(st, g, n, (AddonPagina)pag);
        return true;
    }

    /**
     * <b>The server has written slot {@code n}</b> ({@code setbelt}/{@code setbelt2}) — end any hold there and
     * put <b>nothing</b> back. This is the one ending that does not restore: the message being handled is the
     * server assigning or clearing that slot, so its own write is the content now, and the displaced original
     * it replaces is stale. The arm itself does the writing, synchronously or through its loader task; this
     * only drops the record, which is what makes it safe on the message thread.
     *
     * <p><b>The placement goes too</b> (059.5), and only when there was a hold to end: the slot is the player's
     * again, and an entry added later must not take it back from under the action they just put there. A
     * message for a slot nobody is holding says nothing about a placement — which is what makes the login belt
     * burst, sent before any entry exists, unable to wipe the placements read back a moment later.
     *
     * <p>No notify: the {@code ActionbarChanged} tap is already interested in both these messages.
     */
    public static void serverWrote(GameUI g, int n) {
        synchronized(BeltHold.class) {
            SessionState st = (g == null) ? null : state(g.ui);   // 073.3: the bar the message is about
            if((st != null) && (st.beltHolds.remove(Integer.valueOf(n)) != null))
                unplace(st, n);
        }
    }

    /**
     * Give back every slot held for an entry that is leaving the menu ({@code :remove}, teardown) — and
     * <b>keep its placement</b>: the entry went away, the slot did not, so adding it again puts it back there.
     */
    static void entryRemoved(AddonPagina pag) {
        for(SessionState st : AddonManager.allStates()) {
            for(Integer n : slotsOf(st, pag, null))
                release(st, n.intValue(), false);
        }
    }

    /**
     * Give back every slot this addon was holding (teardown, P2) — {@code :reload}, disable, relogin. The
     * placements stand, so a {@code :reload} puts every button back where it was as the addon re-adds it.
     */
    static void teardownHolds(Addon a) {
        // 073.3: every session's, because an addon holds slots in whichever tree it was running in and a
        // teardown is not told which that was — the shape AddonManager.allStates() names. It matters here more
        // than anywhere else in this layer: the teardown that runs from init() runs when the DRAWN bar is
        // already the session being switched to, so a release resolved from gui() would hand another
        // character's slot back the displaced content of this one's. Each Hold carries its own bar instead.
        for(SessionState st : AddonManager.allStates()) {
            for(Integer n : slotsOf(st, null, a))
                release(st, n.intValue(), false);
        }
    }

    // ---- the placements: a slot survives the entry, the addon and the session (059.5) -------------------

    /**
     * <b>An entry with this identity is in the menu again</b> ({@code s:menugrid():add(id)}) — take back
     * every slot it is placed in. This is the whole of the restore, and it is driven by the {@code :add} rather
     * than by the login: at login the addon's own {@code SessionEnteredWorld} handler is what calls it, so
     * the bar is up
     * by construction and there is no moment to wait for.
     *
     * <p>Taking the slot can still fail — an entry added by a timer before the HUD is fully up. The placement
     * stands through it: it is not the entry that was wrong, and the next {@code :add} of that id applies it.
     */
    static void entryAdded(AddonPagina pag) {
        // 077.3: the bar of the character the entry was ADDED to, which the entry itself names — an :add is
        // addressed at a session, and a button belongs on the bar of the menu it stands in. Reaching for the
        // drawn session would have put an alt's button on whichever character the player was watching.
        GameUI g = AddonManager.gameui(pag.user);
        SessionState st = (g == null) ? null : state(g.ui);
        if(st == null)
            return;
        for(Integer n : slotsPlaced(st, pag.id)) {
            try {
                hold(pag.user, n.intValue(), pag);
            } catch(RuntimeException e) {
                AddonManager.log("action-bar holds: could not restore slot " + n + ": " + e.getMessage());
            }
        }
    }

    /**
     * <b>An addon has been disabled</b> ({@code AddonRegistry.setEnabled(id, false)} — the panel checkbox, the
     * console verb) — drop every placement it owns. The reload that follows gives each held slot back to the
     * server's own content; this is what stops the button coming back, at this restart and every one after it.
     *
     * <p><b>Only a deliberate, persisted disable</b> lands here. A {@code :reload}, a logout and the
     * session-only auto-disable of a runaway addon all keep their placements, because each of them means the
     * addon comes back — and a slot the player chose is not something to lose to a restart.
     */
    public static synchronized void addonDisabled(String addonId) {
        if((addonId == null) || addonId.isEmpty())
            return;
        String mine = AddonPagina.PREFIX + addonId + "/";
        for(SessionState st : AddonManager.allStates()) {     // 073.3: in whichever session it placed them
            for(Iterator<Map.Entry<Integer, String>> it = st.beltPlaced.entrySet().iterator(); it.hasNext(); ) {
                if(it.next().getValue().startsWith(mine)) {
                    it.remove();
                    st.beltDirty = true;
                }
            }
        }
    }

    /** The slots one identity is placed in — a copy, since {@link #hold} takes the monitor this walks under. */
    private static synchronized List<Integer> slotsPlaced(SessionState st, String id) {
        List<Integer> out = new ArrayList<Integer>();
        for(Map.Entry<Integer, String> e : st.beltPlaced.entrySet()) {
            if(e.getValue().equals(id))
                out.add(e.getKey());
        }
        return out;
    }

    /** Record where an entry belongs, on this character's bar. Called under the monitor, from {@link #hold}. */
    private static void place(SessionState st, int n, String id) {
        if(!id.equals(st.beltPlaced.put(Integer.valueOf(n), id)))
            st.beltDirty = true;
    }

    /** Forget where an entry belonged. Called under the monitor, by the endings that end it for good. */
    private static void unplace(SessionState st, int n) {
        if(st.beltPlaced.remove(Integer.valueOf(n)) != null)
            st.beltDirty = true;
    }

    /**
     * <b>Read this character's placements back</b> (from the tick, once {@code <genus>_<char>} is known and
     * <b>before</b> {@code SessionEnteredWorld} fires, so the first {@code :add} an addon makes already sees
     * them).
     * Whatever the file holds is the whole state: this session's map is empty until this runs, and a
     * slot index means this character's bar and no other. A file that is missing, unreadable or malformed
     * leaves the bar as the server sent it, which is the same thing an empty file says.
     */
    static synchronized void restore(SessionState st) {
        st.beltPlaced.clear();
        String text = StoreApi.readClientFile(st, FILE);
        if(text != null) {
            try {
                Object root = Json.parse(text);
                if(root instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>)root;
                    for(Map.Entry<String, Object> e : m.entrySet()) {
                        if(!(e.getValue() instanceof List))
                            continue;
                        for(Object o : (List<?>)e.getValue()) {
                            if(!(o instanceof Number))
                                continue;
                            int n = ((Number)o).intValue();
                            if((n >= 0) && (n < LuaSlot.SLOTS))
                                st.beltPlaced.put(Integer.valueOf(n), e.getKey());
                        }
                    }
                }
            } catch(RuntimeException e) {
                AddonManager.log("action-bar holds: could not read " + FILE + ": " + e);
            }
        }
        st.beltLastJson = json(st);   // prime the write-skip cache: what we just read needs no writing back
        st.beltDirty = false;
    }

    /**
     * Write the placements if they changed (from the tick, and once more as the session is torn down). The
     * serialization is compared with the last one written, so the common tick costs one string build and no
     * disk I/O at all — and the file is left alone entirely on a character whose bar nobody has touched.
     */
    static void flush(SessionState st) {
        String out;
        synchronized(BeltHold.class) {
            if(!st.beltDirty)
                return;
            st.beltDirty = false;
            out = json(st);
            if(out.equals(st.beltLastJson))
                return;
            st.beltLastJson = out;
        }
        StoreApi.writeClientFile(st, FILE, out);   // outside the monitor: the message thread must never wait on disk
    }

    /**
     * The placements as JSON, <b>keyed by the entry's identity</b> — {@code {"addon/myaddon/dig": [11, 12]}} —
     * because that is the key the restore is driven by: an entry is added, and its own row says where it goes.
     * One entry may stand in several slots, so the value is an array; a slot holds one entry, so the map here
     * is the other way round and the two are built from each other. Sorted throughout, so one state has one
     * serialization and the write-skip comparison above is a string compare.
     */
    private static String json(SessionState st) {
        if(st.beltPlaced.isEmpty())
            return "{}";
        Map<String, List<Integer>> byId = new TreeMap<String, List<Integer>>();
        for(Map.Entry<Integer, String> e : st.beltPlaced.entrySet()) {
            List<Integer> l = byId.get(e.getValue());
            if(l == null)
                byId.put(e.getValue(), l = new ArrayList<Integer>());
            l.add(e.getKey());          // beltPlaced is sorted by slot, so each list comes out sorted too
        }
        LuaTable root = new LuaTable();
        for(Map.Entry<String, List<Integer>> e : byId.entrySet()) {
            LuaTable arr = new LuaTable();
            int i = 1;
            for(Integer n : e.getValue())
                arr.set(i++, LuaValue.valueOf(n.intValue()));
            root.set(e.getKey(), arr);
        }
        return Json.write(root);
    }

    /**
     * The slots held for one entry, or by one addon. A copy taken under the monitor: {@link #release} takes
     * the monitor itself, and it writes the very map this walks.
     */
    private static synchronized List<Integer> slotsOf(SessionState st, AddonPagina pag, Addon owner) {
        List<Integer> out = new ArrayList<Integer>();
        for(Map.Entry<Integer, Hold> e : st.beltHolds.entrySet()) {
            Hold h = e.getValue();
            if((pag != null) ? (h.pag == pag) : (h.pag.owner == owner))
                out.add(e.getKey());
        }
        return out;
    }
}
