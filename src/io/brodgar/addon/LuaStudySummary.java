package io.brodgar.addon;

import haven.SAttrWnd;
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
 * A <b>StudySummary object</b> — the totals across one character's study window
 * ({@code s:study():summary()}): the learning points the curiosities in it will yield, the attention they
 * spend, and what they cost in experience.
 *
 * <p><b>Why an object and not the three numbers in a table</b> (085.4). One verb must answer one kind of
 * thing: {@code s:fight():summary()} is a live object with {@code :exists()} and {@code :info()}, and a
 * reader who has learned the fight summary should not have to learn a second answer for the same word. The
 * three totals also have a real lifetime behind them — before the character sheet's study tab is built there
 * is no window and no totals at all, which is what {@code :exists()} asks and what {@code nil} means before
 * it.
 *
 * <p><b>The intern key is the window</b>, exactly as for {@link LuaFightSummary}: the study tab is one
 * character's, it lives as long as that sheet does, and it is the only thing that identifies the totals it
 * holds. Keying on the <i>user</i> instead would make the verb answer for a character whose sheet has not
 * built, which is the {@code nil} this row exists to keep.
 *
 * <p><b>The window is the whole address</b>, so no account is added to it (077.4): a widget stands in one
 * session's tree and names it. {@code :exists()} <b>walks up from the window</b> to its own tree's root
 * rather than comparing against the sheet on screen, which would have called every background character's
 * totals gone.
 *
 * <p><b>The verb names expand the engine's</b>: {@code texp}, {@code tw} and {@code tenc} are spelled for a
 * client programmer and are unreadable as an API. {@code :info()} keeps the API's own spelling here, because
 * {@code lp}/{@code attention}/{@code cost} is what {@code slot:info()} beside it already carries and a
 * snapshot of the totals reads against the snapshot of the parts.
 *
 * <p><b>Threading.</b> The three totals are recomputed together in {@code StudyInfo.tick} on the UI thread,
 * so all three are read inside its monitor in one go — a total and its parts read a frame apart could not be
 * added up.
 */
public final class LuaStudySummary {
    /** The study-report widget this summarises — the whole state of a handle. */
    public final SAttrWnd.StudyInfo si;

    private LuaStudySummary(SAttrWnd.StudyInfo si) {
        this.si = si;
    }

    /** {@code tostring(sum)}: {@code StudySummary()}. */
    public String toString() {
        return "StudySummary()";
    }

    /** An interned StudySummary object for {@code si} in {@code owner}'s env, or {@code NIL} for no window. */
    static LuaValue of(Addon owner, SAttrWnd.StudyInfo si) {
        return owner.studySummaries.of(si);
    }

    /** The {@code LuaStudySummary} behind a Lua value, or {@code null} for anything else. */
    static LuaStudySummary resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaStudySummary) ? (LuaStudySummary)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's StudySummary cache and metatable (its {@link Addon#studySummaries}), keyed by the window. */
    static final class Cache {
        private final Map<SAttrWnd.StudyInfo, Ref> live = new IdentityHashMap<SAttrWnd.StudyInfo, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
        }

        synchronized LuaValue of(SAttrWnd.StudyInfo si) {
            drain();
            if(si == null)
                return LuaValue.NIL;
            Ref r = live.get(si);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(si);
            }
            LuaValue v = LuaValue.userdataOf(new LuaStudySummary(si), meta());
            live.put(si, new Ref(v, si, dead));
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
        final SAttrWnd.StudyInfo key;

        Ref(LuaValue v, SAttrWnd.StudyInfo key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the StudySummary metatable ------------------------------------------------------------------

    private static LuaValue buildMeta() {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("studysummary", methods(),
            "the study summary"));
        mt.set("__name", LuaValue.valueOf("StudySummary"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("StudySummary()");
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        m.set("lp", number("lp", 0));
        m.set("attention", number("attention", 1));
        m.set("cost", number("cost", 2));
        // exists() — is that character's study tab still standing in its own tree?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists").si) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, in the spelling slot:info() beside it already uses.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int[] v = read(handle(self, "info").si);
                if(v == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("lp", LuaValue.valueOf(v[0]));
                t.set("attention", LuaValue.valueOf(v[1]));
                t.set("cost", LuaValue.valueOf(v[2]));
                return t;
            }
        });
        return m;
    }

    /** One of the three totals, all read together under the UI monitor so they cannot disagree. */
    private static OneArgFunction number(final String verb, final int which) {
        return new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int[] v = read(handle(self, verb).si);
                return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v[which]);
            }
        };
    }

    private static LuaStudySummary handle(LuaValue self, String method) {
        LuaStudySummary h = resolve(self);
        if(h == null)
            throw new LuaError("sum:" + method + "() — use a COLON call on a StudySummary object"
                + " (" + CharApi.ST + ":summary())");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * The three totals in one pass — {@code lp, attention, cost} — or {@code null} once the window is gone.
     * They are recomputed together each tick from the curiosities in the window, so they are read together
     * too. {@code attention} is what to compare against {@code s:char():attr():get("int"):composite()}, the cap.
     */
    private static int[] read(SAttrWnd.StudyInfo si) {
        if(live(si) == null)
            return null;
        int[] out = new int[3];
        synchronized(LuaWidget.monitor(si)) {
            out[0] = si.texp;
            out[1] = si.tw;
            out[2] = si.tenc;
        }
        return out;
    }

    /**
     * The study tab this handle reads, or {@code null} once it is no longer up — <b>asked of the window
     * itself</b> (077.4): a widget still parented to its own tree's root is still there, whichever session
     * that tree belongs to and whoever is looking at it.
     */
    private static SAttrWnd.StudyInfo live(SAttrWnd.StudyInfo si) {
        if(si == null)
            return null;
        UI u = si.ui;
        if((u == null) || (u.root == null))
            return null;
        return (!u.destroyed && si.hasparent(u.root)) ? si : null;
    }
}
