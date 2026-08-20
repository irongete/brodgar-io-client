package io.brodgar.addon;

import haven.FightWnd;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A <b>FightSummary object</b> — the scalars around one character's deck ({@code s:fight():summary()}): the
 * action-point budget and what the loaded school spends of it, how many hotkey slots the deck has, and how
 * many saved schools that character keeps against which one is loaded.
 *
 * <p><b>Why an object where the study window's totals are a plain read.</b> Study's summary is <i>derived</i> —
 * it is the sum over the slots, and there is nothing behind it to exist or not exist. These five numbers are
 * fields of a window with a real lifetime: before the character sheet is built there is no budget at all, and
 * after a relog it is a different window. So the summary is an entity, {@code :exists()} means something, and a
 * panel can hold it across frames instead of re-reading a table.
 *
 * <p><b>The intern key is the window</b> (§2.4's <i>exposes only a widget</i> row) — the combat-schools tab is
 * created hidden at login and lives as long as the character sheet does, and nothing else identifies the budget
 * it holds.
 *
 * <p><b>The window is the whole address, so no account is added to it</b> (077.4), exactly as for
 * {@link LuaCraft}: a widget stands in one session's tree and names it, where an id or an index would count
 * inside one character alone. That is also what {@code :exists()} asks — it <b>walks up from the window</b>
 * to its own tree's root rather than comparing against the tab of whoever is on screen, which would have
 * called every background character's budget gone.
 *
 * <p><b>The verb names expand the engine's</b> (§2.6's N1): the fields are spelled for a client programmer and
 * are unreadable as an API. {@code :info()} keeps the engine's own spelling, so nothing a reader had is lost.
 *
 * <p><b>Threading.</b> The counts are written from a loader thread under the UI monitor, so all five are read
 * inside it in one go — a budget and its spend read a frame apart could not be added up.
 */
public final class LuaFightSummary {
    /** The combat-schools window this summarises — the whole state of a handle. */
    public final FightWnd wnd;

    private LuaFightSummary(FightWnd wnd) {
        this.wnd = wnd;
    }

    /** {@code tostring(sum)}: {@code FightSummary()}. */
    public String toString() {
        return "FightSummary()";
    }

    /** An interned FightSummary object for {@code wnd} in {@code owner}'s env, or {@code NIL} for no window. */
    static LuaValue of(Addon owner, FightWnd wnd) {
        return owner.fightSummaries.of(wnd);
    }

    /** The {@code LuaFightSummary} behind a Lua value, or {@code null} for anything else. */
    static LuaFightSummary resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaFightSummary) ? (LuaFightSummary)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's FightSummary cache and metatable (its {@link Addon#fightSummaries}), keyed by the window. */
    static final class Cache {
        private final Map<FightWnd, Ref> live = new IdentityHashMap<FightWnd, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(FightWnd wnd) {
            drain();
            if(wnd == null)
                return LuaValue.NIL;
            Ref r = live.get(wnd);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(wnd);
            }
            LuaValue v = LuaValue.userdataOf(new LuaFightSummary(wnd), meta());
            live.put(wnd, new Ref(v, wnd, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final FightWnd key;

        Ref(LuaValue v, FightWnd key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the FightSummary metatable -----------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("fightsummary", methods(),
            "the fight summary answers :maxActions() :used() :deckSize() :saveCount() :activeSave() "
            + ":exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("FightSummary"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("FightSummary()");
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        m.set("maxActions", number("maxActions", 0));
        m.set("used", number("used", 1));
        m.set("deckSize", number("deckSize", 2));
        m.set("saveCount", number("saveCount", 3));
        m.set("activeSave", number("activeSave", 4));
        // exists() — is that character's combat-schools tab still standing in its own tree?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists").wnd) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, in the window's own spelling.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int[] v = read(handle(self, "info").wnd);
                if(v == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("maxact", LuaValue.valueOf(v[0]));
                t.set("used", LuaValue.valueOf(v[1]));
                t.set("nact", LuaValue.valueOf(v[2]));
                t.set("nsave", LuaValue.valueOf(v[3]));
                t.set("usesave", LuaValue.valueOf(v[4]));
                return t;
            }
        });
        return m;
    }

    /** One of the five counts, all read together under the UI monitor so they cannot disagree. */
    private static OneArgFunction number(final String verb, final int which) {
        return new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int[] v = read(handle(self, verb).wnd);
                return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v[which]);
            }
        };
    }

    private static LuaFightSummary handle(LuaValue self, String method) {
        LuaFightSummary h = resolve(self);
        if(h == null)
            throw new LuaError("sum:" + method + "() — use a COLON call on a FightSummary object"
                + " (" + CharApi.FT + ":summary())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The five counts in one pass — {@code maxActions, used, deckSize, saveCount, activeSave} — or
     * {@code null} once the window is gone. {@code used} is the total spend across every maneuver, which is
     * the same number the window paints beside the cap.
     */
    private static int[] read(FightWnd fw) {
        if(live(fw) == null)
            return null;
        int[] out = new int[5];
        synchronized(LuaWidget.monitor(fw)) {
            out[0] = fw.maxact;
            int used = 0;
            for(FightWnd.Action a : fw.acts)
                used += a.u;
            out[1] = used;
            out[2] = fw.order.length;
            out[3] = fw.nsave;
            out[4] = fw.usesave;
        }
        return out;
    }

    /**
     * The schools tab this handle reads, or {@code null} once it is no longer up — <b>asked of the window
     * itself</b> (077.4): a widget still parented to its own tree's root is still there, whichever session
     * that tree belongs to and whoever is looking at it. Comparing against the tab on screen would have
     * called every background character's summary gone the moment the player tabbed away.
     */
    private static FightWnd live(FightWnd fw) {
        if(fw == null)
            return null;
        UI u = fw.ui;
        if((u == null) || (u.root == null))
            return null;
        return (!u.destroyed && fw.hasparent(u.root)) ? fw : null;
    }
}
