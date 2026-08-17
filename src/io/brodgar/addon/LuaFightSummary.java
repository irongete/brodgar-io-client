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
 * A <b>FightSummary object</b> — the scalars around the deck ({@code hafen.fight():summary()}): the action-point
 * budget and what the loaded school spends of it, how many hotkey slots the deck has, and how many saved schools
 * you keep against which one is loaded.
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
        mt.set(LuaValue.INDEX, Retired.methodIndex("fightsummary", methods()));
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
        // exists() — is the combat-schools tab still the one this summary was read from?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "exists").wnd == CharApi.fightwnd());
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
                + " (hafen.fight():summary())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The five counts in one pass — {@code maxActions, used, deckSize, saveCount, activeSave} — or
     * {@code null} once the window is gone. {@code used} is the total spend across every maneuver, which is
     * the same number the window paints beside the cap.
     */
    private static int[] read(FightWnd fw) {
        if((fw == null) || (fw != CharApi.fightwnd()))
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
}
