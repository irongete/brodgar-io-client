package io.brodgar.addon;

import haven.GameUI;
import haven.MenuGrid;

import org.luaj.vm2.LuaError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Threading.</b> The Lua verbs and the mouse hooks run on the UI thread; {@link #serverWrote} runs on
 * the message thread, under {@code synchronized(ui)}, from {@code GameUI.uimsg}. The map is therefore guarded
 * on its own monitor, and {@link #serverWrote} touches neither {@code belt} nor Lua — it drops a record and
 * nothing else. The state is <b>per session</b> ({@link #resetSession}): a slot index means another
 * character's bar after a relogin.
 */
public final class BeltHold {
    /** One held slot: the entry drawn there, what it displaced, and the slot object we actually wrote. */
    private static final class Hold {
        final AddonPagina pag;
        /** The server's own content, kept untouched for the release. {@code null} for a slot that was empty. */
        final GameUI.BeltSlot displaced;
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

    private BeltHold() {
    }

    /** Forget every hold — the bar the indices name is the previous character's (per-session reset). */
    static synchronized void resetSession() {
        holds.clear();
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
        AddonManager.onBeltSet(n);              // ActionbarChanged for the taking edge, on the next tick
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
    public static synchronized boolean release(int n) {
        Hold h = holds.remove(Integer.valueOf(n));
        if(h == null)
            return false;
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
     * <p>No notify: the {@code ActionbarChanged} tap is already interested in both these messages.
     */
    public static void serverWrote(int n) {
        synchronized(BeltHold.class) {
            holds.remove(Integer.valueOf(n));
        }
    }

    /** Give back every slot held for an entry that is leaving the menu ({@code :remove}, teardown). */
    static void entryRemoved(AddonPagina pag) {
        for(Integer n : slotsOf(pag, null))
            release(n.intValue());
    }

    /** Give back every slot this addon was holding (teardown, P2) — {@code :reload}, disable, relogin. */
    static void teardownHolds(Addon a) {
        for(Integer n : slotsOf(null, a))
            release(n.intValue());
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
