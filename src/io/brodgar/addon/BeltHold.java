package io.brodgar.addon;

import haven.GameUI;
import haven.MenuGrid;

import org.luaj.vm2.LuaError;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>A bar slot held for an addon's own menu entry</b> ({@code slot:pagina(pag)}, spec
 * {@code 059-menugrid-entries}) — the layer that lets an {@link AddonPagina} draw and fire on the action bar
 * without ever going onto it.
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
 * right now, a <b>placement</b> is the player's standing intent for that slot — kept per character in
 * {@link #placed}, persisted through {@link StoreApi#writeClientFile}, and re-applied by {@link #entryAdded}
 * the moment an entry with that identity is added again. At login that is the addon's own {@code EnterWorld}
 * {@code :add}, so nothing has to guess when the bar is ready. The two endings above split here: a hold
 * <b>released by hand</b> — {@code slot:pagina(nil)}, a right-click, the server taking the slot — is
 * <b>forgotten</b>, while one whose <b>entry merely went away</b> — {@code :remove}, a {@code :reload},
 * disable, a logout — is <b>remembered</b>, because the slot is still where that entry belongs.
 *
 * <p><b>Threading.</b> The Lua verbs and the mouse hooks run on the UI thread; {@link #serverWrote} runs on
 * the message thread, under {@code synchronized(ui)}, from {@code GameUI.uimsg}. The map is therefore guarded
 * on its own monitor, and {@link #serverWrote} touches neither {@code belt} nor Lua — it drops a record and
 * nothing else. The disk write is the same shape: every mutation marks {@link #dirty} and {@link #flush} runs
 * on the tick, so no file I/O ever stands on the message thread. The hold state is <b>per session</b>
 * ({@link #resetSession}) and the placements are re-read per character ({@link #restore}): a slot index means
 * another character's bar after a relogin.
 */
public final class BeltHold {
    /** One held slot: the entry drawn there, what it displaced, and the slot object we actually wrote. */
    private static final class Hold {
        final AddonPagina pag;
        /**
         * The server's own content, kept untouched for the release. {@code null} for a slot that was empty, and
         * re-read by {@link #writeLanded} when a server write that <i>predates</i> this hold lands late.
         */
        GameUI.BeltSlot displaced;
        /** What we put in {@code belt[n]} — the identity guard: only our own write is ever taken back out. */
        final GameUI.BeltSlot drawn;

        Hold(AddonPagina pag, GameUI.BeltSlot displaced, GameUI.BeltSlot drawn) {
            this.pag = pag;
            this.displaced = displaced;
            this.drawn = drawn;
        }
    }

    /** Slot index &rarr; the hold on it. Guarded on its own monitor; cleared per session. */
    private static final Map<Integer, Hold> holds = new HashMap<Integer, Hold>();

    /**
     * Slot index &rarr; the identity of the entry that <b>belongs</b> in it — the placements, which survive the
     * entry, the addon and the session. Sorted, so the serialization of one state is one string and an
     * unchanged map costs no disk write.
     */
    private static final Map<Integer, String> placed = new TreeMap<Integer, String>();

    /** The layer's own per-character file, beside the addons' saved variables. */
    private static final String FILE = "actionbar-holds.json";

    /** Has {@link #placed} changed since the last write? Set on the message thread too; flushed on the tick. */
    private static boolean dirty;
    /** The last serialization written (or read), so an unchanged map writes nothing. */
    private static String lastJson;

    private BeltHold() {
    }

    /**
     * Forget every hold <b>and every placement</b> — the bar the indices name is the previous character's, and
     * the next character's own placements are read back by {@link #restore} once the world is entered.
     */
    static synchronized void resetSession() {
        holds.clear();
        placed.clear();
        lastJson = null;
        dirty = false;
    }

    /** The entry a slot is being held for, or {@code null} for every slot the server owns. */
    static synchronized AddonPagina held(int n) {
        Hold h = holds.get(Integer.valueOf(n));
        return (h == null) ? null : h.pag;
    }

    /**
     * <b>Hold slot {@code n} for {@code pag}</b> — the write half of {@code slot:pagina(pag)} and the whole of
     * a drag from the grid. What the slot had is remembered <i>once</i>: a second hold on the same slot
     * carries the original {@link Hold#displaced} across, so the server's content survives any number of
     * addons taking that slot in turn rather than being replaced by the previous addon's button.
     */
    static synchronized void hold(int n, AddonPagina pag) {
        GameUI g = AddonManager.gui();
        if((g == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length))
            throw new LuaError("slot:pagina(pagOrNil): there is no action bar yet — hold a slot from"
                + " EnterWorld or later, not from Load");
        Integer key = Integer.valueOf(n);
        Hold cur = holds.get(key);
        if((cur != null) && (cur.pag == pag) && (g.belt[n] == cur.drawn))
            return;                             // already exactly this — a hold is a state, not an event
        GameUI.BeltSlot displaced = (cur != null) ? cur.displaced : g.belt[n];
        GameUI.BeltSlot drawn = new GameUI.PagBeltSlot(n, pag);
        g.belt[n] = drawn;
        holds.put(key, new Hold(pag, displaced, drawn));
        place(n, pag.id);                       // 059.5: and this is where that entry belongs, from now on
        AddonManager.onBeltSet(n);              // ActionbarChanged for the taking edge, on the next tick
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
     * addon's {@code EnterWorld} re-apply falls between the two. What just landed becomes the hold's
     * {@link Hold#displaced} content, so the release still hands the slot back holding the server's own
     * current action rather than the emptiness that stood there when the hold was taken.
     */
    static synchronized void writeLanded(int n) {
        Hold h = holds.get(Integer.valueOf(n));
        if(h == null)
            return;
        GameUI g = AddonManager.gui();
        if((g == null) || (g.belt == null) || (n < 0) || (n >= g.belt.length) || (g.belt[n] == h.drawn))
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
    public static boolean release(int n) {
        return release(n, true);
    }

    /**
     * The whole of {@link #release}, plus <b>whether the placement goes with it</b> (059.5). Ending a hold by
     * hand says the entry no longer belongs in that slot; an entry leaving the menu says nothing of the kind,
     * so it keeps its slot and takes it again the moment it is added back.
     */
    private static synchronized boolean release(int n, boolean forget) {
        Hold h = holds.remove(Integer.valueOf(n));
        if(h == null)
            return false;
        if(forget)
            unplace(n);
        GameUI g = AddonManager.gui();
        if((g != null) && (g.belt != null) && (n >= 0) && (n < g.belt.length) && (g.belt[n] == h.drawn))
            g.belt[n] = h.displaced;
        AddonManager.onBeltSet(n);              // ActionbarChanged for the releasing edge too
        return true;
    }

    /**
     * A drag from the menu grid dropped on slot {@code n} ({@code GameUI.Belt.dropthing}). {@code true} when
     * this is an addon's own entry and the layer took it — the drop then <b>sends nothing</b>, where the stock
     * body would {@code wdgmsg("setbelt", …)} a name the server has never heard of and drop it silently.
     */
    public static boolean dropped(int n, MenuGrid.Pagina pag) {
        if(!(pag instanceof AddonPagina))
            return false;
        hold(n, (AddonPagina)pag);
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
    public static void serverWrote(int n) {
        synchronized(BeltHold.class) {
            if(holds.remove(Integer.valueOf(n)) != null)
                unplace(n);
        }
    }

    /**
     * Give back every slot held for an entry that is leaving the menu ({@code :remove}, teardown) — and
     * <b>keep its placement</b>: the entry went away, the slot did not, so adding it again puts it back there.
     */
    static void entryRemoved(AddonPagina pag) {
        for(Integer n : slotsOf(pag, null))
            release(n.intValue(), false);
    }

    /**
     * Give back every slot this addon was holding (teardown, P2) — {@code :reload}, disable, relogin. The
     * placements stand, so a {@code :reload} puts every button back where it was as the addon re-adds it.
     */
    static void teardownHolds(Addon a) {
        for(Integer n : slotsOf(null, a))
            release(n.intValue(), false);
    }

    // ---- the placements: a slot survives the entry, the addon and the session (059.5) -------------------

    /**
     * <b>An entry with this identity is in the menu again</b> ({@code hafen.menugrid():add(id)}) — take back
     * every slot it is placed in. This is the whole of the restore, and it is driven by the {@code :add} rather
     * than by the login: at login the addon's own {@code EnterWorld} handler is what calls it, so the bar is up
     * by construction and there is no moment to wait for.
     *
     * <p>Taking the slot can still fail — an entry added by a timer before the HUD is fully up. The placement
     * stands through it: it is not the entry that was wrong, and the next {@code :add} of that id applies it.
     */
    static void entryAdded(AddonPagina pag) {
        for(Integer n : slotsPlaced(pag.id)) {
            try {
                hold(n.intValue(), pag);
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
        for(Iterator<Map.Entry<Integer, String>> it = placed.entrySet().iterator(); it.hasNext(); ) {
            if(it.next().getValue().startsWith(mine)) {
                it.remove();
                dirty = true;
            }
        }
    }

    /** The slots one identity is placed in — a copy, since {@link #hold} takes the monitor this walks under. */
    private static synchronized List<Integer> slotsPlaced(String id) {
        List<Integer> out = new ArrayList<Integer>();
        for(Map.Entry<Integer, String> e : placed.entrySet()) {
            if(e.getValue().equals(id))
                out.add(e.getKey());
        }
        return out;
    }

    /** Record where an entry belongs. Called under the monitor, from {@link #hold}. */
    private static void place(int n, String id) {
        if(!id.equals(placed.put(Integer.valueOf(n), id)))
            dirty = true;
    }

    /** Forget where an entry belonged. Called under the monitor, by the endings that end it for good. */
    private static void unplace(int n) {
        if(placed.remove(Integer.valueOf(n)) != null)
            dirty = true;
    }

    /**
     * <b>Read this character's placements back</b> (from the tick, once {@code <genus>_<char>} is known and
     * <b>before</b> {@code EnterWorld} fires, so the first {@code :add} an addon makes already sees them).
     * Whatever the file holds is the whole state: {@link #resetSession} emptied the map a moment ago, and a
     * slot index means this character's bar and no other. A file that is missing, unreadable or malformed
     * leaves the bar as the server sent it, which is the same thing an empty file says.
     */
    static synchronized void restore() {
        placed.clear();
        String text = StoreApi.readClientFile(FILE);
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
                                placed.put(Integer.valueOf(n), e.getKey());
                        }
                    }
                }
            } catch(RuntimeException e) {
                AddonManager.log("action-bar holds: could not read " + FILE + ": " + e);
            }
        }
        lastJson = json();          // prime the write-skip cache: what we just read needs no writing back
        dirty = false;
    }

    /**
     * Write the placements if they changed (from the tick, and once more as the session is torn down). The
     * serialization is compared with the last one written, so the common tick costs one string build and no
     * disk I/O at all — and the file is left alone entirely on a character whose bar nobody has touched.
     */
    static void flush() {
        String out;
        synchronized(BeltHold.class) {
            if(!dirty)
                return;
            dirty = false;
            out = json();
            if(out.equals(lastJson))
                return;
            lastJson = out;
        }
        StoreApi.writeClientFile(FILE, out);     // outside the monitor: the message thread must never wait on disk
    }

    /**
     * The placements as JSON, <b>keyed by the entry's identity</b> — {@code {"addon/myaddon/dig": [11, 12]}} —
     * because that is the key the restore is driven by: an entry is added, and its own row says where it goes.
     * One entry may stand in several slots, so the value is an array; a slot holds one entry, so the map here
     * is the other way round and the two are built from each other. Sorted throughout, so one state has one
     * serialization and the write-skip comparison above is a string compare.
     */
    private static String json() {
        if(placed.isEmpty())
            return "{}";
        Map<String, List<Integer>> byId = new TreeMap<String, List<Integer>>();
        for(Map.Entry<Integer, String> e : placed.entrySet()) {
            List<Integer> l = byId.get(e.getValue());
            if(l == null)
                byId.put(e.getValue(), l = new ArrayList<Integer>());
            l.add(e.getKey());          // placed is sorted by slot, so each list comes out sorted too
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
    private static synchronized List<Integer> slotsOf(AddonPagina pag, Addon owner) {
        List<Integer> out = new ArrayList<Integer>();
        for(Map.Entry<Integer, Hold> e : holds.entrySet()) {
            Hold h = e.getValue();
            if((pag != null) ? (h.pag == pag) : (h.pag.owner == owner))
                out.add(e.getKey());
        }
        return out;
    }
}
