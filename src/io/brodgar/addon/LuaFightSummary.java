package io.brodgar.addon;

import haven.FightWnd;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>FightSummary object</b> — the scalars around one character's deck ({@code s:fight():summary()}): the
 * action-point budget and what the loaded school spends of it. The saved schools are a collection of their own,
 * {@code s:fight():school()} ({@link LuaSchool}, 172.1), and so are the deck's places, {@code s:fight():deck()}
 * ({@link LuaDeckCard}, 172.2), whose {@code :count()} is the layout's size.
 *
 * <p><b>Why an object where the study window's totals are a plain read.</b> Study's summary is <i>derived</i> —
 * it is the sum over the slots, and there is nothing behind it to exist or not exist. These numbers are
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
 * <p><b>Threading.</b> The counts are written from a loader thread under the UI monitor, so all of them are read
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

    /**
     * One addon's FightSummary cache and metatable (its {@link Addon#fightSummaries}), keyed by the window.
     *
     * <p><b>Weak on BOTH axes</b> ({@code WeakHashMap<FightWnd, WeakReference<LuaValue>>}, audit2 B01) — the
     * shape {@link LuaWidget.Cache} states the reason for, and the reason applies here too. It keyed the
     * window STRONGLY, and its queue entry held the key a second time, so a destroyed character window and
     * its whole subtree stayed reachable from this map until something minted another summary in the same
     * addon — which, for an addon that asked once and kept the handle, is never. {@code haven.Widget}
     * overrides neither {@code equals} nor {@code hashCode}, so the weak map is identity-keyed exactly as the
     * {@code IdentityHashMap} was; the value is a {@link WeakReference} so it never strongly reaches its own
     * key, and a value cleared while the window still stands is simply re-minted on the next look-up.
     */
    static final class Cache {
        // retained: weak on both axes -- the value is a WeakReference, so nothing here reaches the window.
        private final Map<FightWnd, WeakReference<LuaValue>> live =
            new WeakHashMap<FightWnd, WeakReference<LuaValue>>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(FightWnd wnd) {
            if(wnd == null)
                return LuaValue.NIL;
            WeakReference<LuaValue> r = live.get(wnd);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
            }
            LuaValue v = LuaValue.userdataOf(new LuaFightSummary(wnd), meta());
            live.put(wnd, new WeakReference<LuaValue>(v));
            return v;
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta();
            return mt;
        }
    }

    // ---- the FightSummary metatable -----------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("fightsummary", methods(),
            "the fight summary"));
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
        // exists() — is that character's combat-schools tab still standing in its own tree?
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(live(handle(Args.only(a, 0, "summary:exists"), "exists").wnd) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, in the window's own spelling.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                int[] v = read(handle(Args.only(a, 0, "summary:info"), "info").wnd);
                if(v == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("maxact", LuaValue.valueOf(v[0]));
                t.set("used", LuaValue.valueOf(v[1]));
                return t;
            }
        });
        return m;
    }

    /** One of the counts, all read together under the UI monitor so they cannot disagree. */
    private static VarArgFunction number(final String verb, final int which) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                int[] v = read(handle(Args.only(a, 0, "summary:" + verb), verb).wnd);
                return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v[which]);
            }
        };
    }

    private static LuaFightSummary handle(LuaValue self, String method) {
        LuaFightSummary h = resolve(self);
        if(h == null)
            throw new LuaError("summary:" + method + "() — use a COLON call on a FightSummary object"
                + " (" + CharApi.FT + ":summary())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The counts in one pass — {@code maxActions, used} — or {@code null} once the window is gone.
     * {@code used} is the total spend across every maneuver, which is the same number the window paints beside
     * the cap.
     */
    private static int[] read(FightWnd fw) {
        if(live(fw) == null)
            return null;
        int[] out = new int[2];
        synchronized(LuaWidget.monitor(fw)) {
            out[0] = fw.maxact;
            int used = 0;
            for(FightWnd.Action a : fw.acts)
                used += a.u;
            out[1] = used;
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
