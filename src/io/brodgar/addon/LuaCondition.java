package io.brodgar.addon;

import haven.QuestWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Condition object</b> — one objective of a quest ({@code quest:conditions()}). The client is sent the
 * objectives of the quest the player has <b>open in the log</b> and of no other, so every quest but that one
 * answers an empty array; that is the game's limit rather than a gap here.
 *
 * <p><b>The intern key is the quest id plus the objective's own text</b>, and the engine chose it: an
 * objective's description is a {@code final} field and the {@code "conds"} message looks the existing
 * objective up <i>by that text</i>, carrying the record over and rewriting only its state. So the record's
 * Java identity is meaningless — the array around it is rebuilt every time — while <i>this</i> objective of
 * <i>that</i> quest is exactly what survives, which is what a handle has to be.
 *
 * <p><b>That is what makes a stashed objective worth holding.</b> Its {@code :status()} flips from
 * {@code "pending"} to {@code "done"} under the same handle, and watching one objective of a quest is the
 * whole reason to reach past the quest at all.
 *
 * <p><b>It exists only while its quest is the selected one.</b> Deselecting the quest destroys the box the
 * objectives live in, so {@code :exists()} goes false and comes back true when the player opens that quest
 * again — the same lifetime a buff or a meter has, one level down.
 *
 * <p><b>Threading.</b> The objective array is swapped wholesale on a loader thread under the UI monitor, so
 * every read takes it inside the monitor and works off that reference.
 */
public final class LuaCondition {
    /** The account whose log this objective's quest is in. */
    public final String user;
    /** The id of the quest this objective belongs to, in that character's log. */
    public final int quest;
    /** The objective's description — the engine's own key for it, and the last third of ours. */
    public final String desc;

    private LuaCondition(String user, int quest, String desc) {
        this.user = user;
        this.quest = quest;
        this.desc = desc;
    }

    /** {@code tostring(c)}: {@code Condition(<quest id>, "<desc>")}. */
    public String toString() {
        return "Condition(" + quest + ", \"" + desc + "\")";
    }

    /**
     * An interned Condition object for objective {@code desc} of quest {@code quest} <b>of session
     * {@code user}</b>, in {@code owner}'s env.
     */
    static LuaValue of(Addon owner, String user, int quest, String desc) {
        return owner.conditions.of(user, quest, desc);
    }

    /** The {@code LuaCondition} behind a Lua value, or {@code null} for anything else. */
    static LuaCondition resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaCondition) ? (LuaCondition)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Condition cache and metatable (its {@link Addon#conditions}), keyed by the account, the
     * quest id and the objective's own text: the id counts within one character's log, so the account is
     * what stops two characters' quest 7 sharing an objective.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int quest, String desc) {
            drain();
            // The triple IS the identity; neither an account name nor an objective's text carries a newline.
            String key = user + "\n" + quest + "\n" + desc;
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaCondition(user, quest, desc), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref cr = (Ref)r;
                if(live.get(cr.key) == cr)
                    live.remove(cr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Condition metatable --------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("condition", methods(owner),
            "a quest condition answers :description() :status() :text() :quest() :exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("Condition"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCondition h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Condition(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // description() — what the objective asks for. It is also the engine's key for it, so it never
        // changes under a handle: an objective whose text changes is a different objective.
        m.set("description", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String d = handle(self, "description").desc;
                return (d == null) ? LuaValue.NIL : LuaValue.valueOf(d);
            }
        });
        // status() — "pending" / "done" / "failed". This is what flips under a stashed handle.
        m.set("status", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                QuestWnd.Quest.Condition c = live(handle(self, "status"));
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(status(c.done));
            }
        });
        // text() — the objective's extra progress string, when the content publishes one.
        m.set("text", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                QuestWnd.Quest.Condition c = live(handle(self, "text"));
                return ((c == null) || (c.status == null)) ? LuaValue.NIL : LuaValue.valueOf(c.status);
            }
        });
        // quest() — the quest this objective belongs to. NEVER nil: a quest that has left the log answers a
        // Quest whose :exists() is false, exactly as every id-keyed handle in the API does.
        m.set("quest", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCondition h = handle(self, "quest");
                return LuaQuest.of(owner, h.user, h.quest);
            }
        });
        // exists() — is this still an objective of the quest open in the log?
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(live(handle(self, "info")));
            }
        });
        return m;
    }

    private static LuaCondition handle(LuaValue self, String method) {
        LuaCondition h = resolve(self);
        if(h == null)
            throw new LuaError("condition:" + method + "() — use a COLON call on a Condition object"
                + " (" + CharApi.Q + ":selected():conditions()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The live objective record, or {@code null} once its quest is no longer the one open in the log. */
    private static QuestWnd.Quest.Condition live(LuaCondition h) {
        for(QuestWnd.Quest.Condition c : LuaQuest.conditions(h.user, h.quest)) {
            if((c.desc == null) ? (h.desc == null) : c.desc.equals(h.desc))
                return c;
        }
        return null;
    }

    /** The API status word for an objective's {@code done} code (pending / done / failed). */
    static String status(int done) {
        if(done == QuestWnd.Quest.QST_DONE) return "done";
        if(done == QuestWnd.Quest.QST_FAIL) return "failed";
        return "pending";
    }

    /** The documented {@code Condition} snapshot, or {@code nil} once the objective is out of reach. */
    private static LuaValue snapshot(QuestWnd.Quest.Condition c) {
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        if(c.desc != null)
            t.set("desc", LuaValue.valueOf(c.desc));
        t.set("status", LuaValue.valueOf(status(c.done)));
        if(c.status != null)
            t.set("text", LuaValue.valueOf(c.status));
        return t;
    }
}
