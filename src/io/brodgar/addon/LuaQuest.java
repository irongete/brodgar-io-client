package io.brodgar.addon;

import haven.QuestWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Quest object</b> — one entry of the quest log ({@code s:quest()}). <b>The section object IS the
 * log</b> (uniform grammar §2.1): {@code s:quest()} is the collection over both tabs, the Current one
 * and the Completed one, {@code s:quest():get(id)} is one quest and {@code s:quest():selected()}
 * the distinguished member (R8) — the one the player has open, and the only one whose objectives the
 * client is sent at all.
 *
 * <p><b>The intern key is the quest id</b> (§2.4's <i>publishes a stable id</i> row), and the engine agrees
 * with that choice rather than merely permitting it: the {@code "quests"} message looks a quest up by id and
 * <b>mutates the record in place</b>, moving it between the two tab lists as its status changes. So a stashed
 * Quest goes on reading through a completion — {@code q:status()} flips from {@code "pending"} to
 * {@code "done"} under the same handle — which is exactly the moment an addon cares about, and it is
 * precisely what a snapshot could not show.
 *
 * <p><b>A quest can leave the log entirely</b> (the server sends its id with no resource), so
 * {@code :exists()} is a real question and every other read answers {@code nil} once it is false.
 *
 * <p><b>Only the selected quest has conditions.</b> The client is sent objectives for the one quest that is
 * open in the log and for no other, so {@code q:conditions()} is an empty array on every quest but that one.
 * That is the game's own limit, not a gap here, and {@code q:selected()} is how a reader tells the two
 * cases apart without comparing objects.
 *
 * <p><b>Threading.</b> Both tab lists are mutated on a loader thread under the UI monitor, so every read
 * copies what it needs inside it and resolves names outside it ({@code res.get()} may still be Loading).
 */
public final class LuaQuest {
    /** The account whose log this quest is in — half the address, and what makes the id mean one thing. */
    public final String user;
    /** The quest's server id, in that character's own log. */
    public final int id;

    private LuaQuest(String user, int id) {
        this.user = user;
        this.id = id;
    }

    /** {@code tostring(q)}: {@code Quest(<id>)}. */
    public String toString() {
        return "Quest(" + id + ")";
    }

    /** An interned Quest object for quest {@code id} <b>of session {@code user}</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int id) {
        return owner.quests.of(user, id);
    }

    /** The {@code LuaQuest} behind a Lua value, or {@code null} for anything else. */
    static LuaQuest resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaQuest) ? (LuaQuest)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Quest cache and metatable (its {@link Addon#quests}), keyed by the <b>account plus</b> the
     * server's quest id: a quest id is one character's own, so two characters both carrying quest 7 carry two
     * different quests and must have two handles. Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int qid) {
            drain();
            Map<Integer, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(qid);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaQuest(user, qid), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref qr = (Ref)r;
                Map<Integer, Ref> byid = live.get(qr.user);
                if(byid == null)
                    continue;
                if(byid.get(qr.key) == qr)
                    byid.remove(qr.key);
                if(byid.isEmpty())
                    live.remove(qr.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Quest metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("quest", methods(owner),
            "a quest answers :id() :title() :res() :status() :modified() :conditions() "
            + ":exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("Quest"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Quest(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the server's own quest id, the only thing about a quest that never changes.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        // title() — the quest's name: the explicit title the server sent, else the resource tooltip.
        m.set("title", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "title");
                QuestWnd.Quest q = quest(h.user, h.id);
                String t = (q == null) ? null : title(q);
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
            }
        });
        // res() — the quest's stable resource name.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "res");
                QuestWnd.Quest q = quest(h.user, h.id);
                String r = (q == null) ? null : AddonManager.resIdent(q.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // status() — "pending" / "done" / "failed" / "disabled". It changes under a stashed handle.
        m.set("status", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "status");
                QuestWnd.Quest q = quest(h.user, h.id);
                return (q == null) ? LuaValue.NIL : LuaValue.valueOf(status(q.done));
            }
        });
        // modified() — the server's change stamp; higher is more recent, and it is what the log sorts on.
        m.set("modified", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "modified");
                QuestWnd.Quest q = quest(h.user, h.id);
                return (q == null) ? LuaValue.NIL : LuaValue.valueOf(q.mtime);
            }
        });
        // conditions() — the objectives, a plain array (a layout, not a set to address into). EMPTY on
        // every quest but the selected one: the client is sent conditions for that one alone.
        m.set("conditions", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaQuest h = handle(self, "conditions");
                return LuaCollection.create("quest:conditions()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        for(QuestWnd.Quest.Condition c : conditions(h.user, h.id))
                            out.add(LuaCondition.of(owner, h.user, h.id, c.desc));
                        return out;
                    }

                    public String noGet() {
                        return "an objective's only key is the description text it was minted from:"
                            + " quest:conditions():find(filter) is the search and"
                            + " quest:conditions():list()[n] takes a position";
                    }
                }, null);
            }
        });
        // exists() — is this quest still in the log? (The server drops one by sending it with no resource.)
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "exists");
                return LuaValue.valueOf(quest(h.user, h.id) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaQuest h = handle(self, "info");
                return snapshot(quest(h.user, h.id));
            }
        });
        return m;
    }

    private static LuaQuest handle(LuaValue self, String method) {
        LuaQuest h = resolve(self);
        if(h == null)
            throw new LuaError("quest:" + method + "() — use a COLON call on a Quest object"
                + " (" + CharApi.Q + ":get(id), :selected() or :list()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** That character's Quest Log window (hidden at login but live), or {@code null} before it exists. */
    static QuestWnd wnd(String user) {
        return CharApi.questwnd(user);
    }

    /** Every quest, Current tab then Completed tab, copied under the {@code ui} monitor. */
    static List<QuestWnd.Quest> all(String user) {
        List<QuestWnd.Quest> out = new ArrayList<QuestWnd.Quest>();
        QuestWnd qw = wnd(user);
        if(qw == null)
            return out;
        synchronized(LuaWidget.monitor(qw)) {    // both lists mutate off-thread (QuestWnd.uimsg)
            out.addAll(qw.cqst.quests);          // "Current" (pending / disabled)
            out.addAll(qw.dqst.quests);          // "Completed" (done / failed)
        }
        return out;
    }

    /** The live record for {@code qid}, or {@code null} once the quest has left the log. */
    static QuestWnd.Quest quest(String user, int qid) {
        QuestWnd qw = wnd(user);
        if(qw == null)
            return null;
        synchronized(LuaWidget.monitor(qw)) {
            QuestWnd.Quest q = qw.cqst.get(qid);
            return (q != null) ? q : qw.dqst.get(qid);
        }
    }

    /** The selected quest's {@code Box}, or {@code null} when nothing is open in the log. */
    static QuestWnd.Quest.Box box(String user) {
        QuestWnd qw = wnd(user);
        if(qw == null)
            return null;
        synchronized(LuaWidget.monitor(qw)) {    // qw.quest is swapped off-thread (addchild / cdestroy)
            QuestWnd.Quest.Info info = qw.quest;
            return (info instanceof QuestWnd.Quest.Box) ? (QuestWnd.Quest.Box)info : null;
        }
    }

    /** The selected {@code Box} <i>if it is quest {@code qid}'s</i>, else {@code null}. */
    static QuestWnd.Quest.Box selected(String user, int qid) {
        QuestWnd.Quest.Box b = box(user);
        return ((b != null) && (b.id == qid)) ? b : null;
    }

    /** Quest {@code qid}'s objectives, or empty — which is every quest but the selected one. */
    static List<QuestWnd.Quest.Condition> conditions(String user, int qid) {
        List<QuestWnd.Quest.Condition> out = new ArrayList<QuestWnd.Quest.Condition>();
        QuestWnd.Quest.Box b = selected(user, qid);
        if(b == null)
            return out;
        QuestWnd.Quest.Condition[] cond;
        synchronized(LuaWidget.monitor(b)) {     // cond[] is swapped wholesale on the "conds" uimsg
            cond = b.cond;
        }
        if(cond != null) {
            for(QuestWnd.Quest.Condition c : cond)
                out.add(c);
        }
        return out;
    }

    /** The quest's display name: the explicit title, else the resource tooltip. Loading-guarded. */
    static String title(QuestWnd.Quest q) {
        if(q.title != null)
            return q.title;
        return AddonManager.resTipName(q.res, null);
    }

    /** The API status word for a {@code Quest.done} code (QST_PEND/DONE/FAIL/DISABLED). */
    static String status(int done) {
        if(done == QuestWnd.Quest.QST_DONE)     return "done";
        if(done == QuestWnd.Quest.QST_FAIL)     return "failed";
        if(done == QuestWnd.Quest.QST_DISABLED) return "disabled";
        return "pending";                            // QST_PEND (and any unexpected code)
    }

    /** The documented {@code Quest} snapshot, or {@code nil} once the quest has left the log. */
    private static LuaValue snapshot(QuestWnd.Quest q) {
        if(q == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(q.id));
        String tit = title(q);
        if(tit != null)
            t.set("title", LuaValue.valueOf(tit));
        String r = AddonManager.resIdent(q.res);
        if(r != null)
            t.set("res", LuaValue.valueOf(r));
        t.set("status", LuaValue.valueOf(status(q.done)));
        t.set("mtime", LuaValue.valueOf(q.mtime));
        return t;
    }

    /** {@code res} and {@code title} as one string a substring filter runs over (newline-separated). */
    private static String needleOf(QuestWnd.Quest q) {
        if(q == null)
            return "";
        String r = AddonManager.resIdent(q.res), tit = title(q);
        return ((r == null) ? "" : r) + "\n" + ((tit == null) ? "" : tit);
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:quest()} — the log, Current tab first and Completed second, each list in the order the
     * window itself holds it. Addressable by quest id; {@code :selected()} is the distinguished member
     * (§2.2's R8). There is no {@code :add}/{@code :remove}: accepting and abandoning a quest is the
     * server's business and no client can do either.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        extra.set("selected", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "selected");
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.Q + ":selected() takes no arguments — it reads which quest"
                        + " is open in the log, and which one that is is the player's choice");
                QuestWnd.Quest.Box b = box(user);
                return (b == null) ? LuaValue.NIL : of(owner, user, b.id);
            }
        });
        return LuaCollection.create(CharApi.Q, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<QuestWnd.Quest> qs = all(user);
                List<LuaValue> out = new ArrayList<LuaValue>(qs.size());
                for(int i = 0; i < qs.size(); i++)
                    out.add(of(owner, user, qs.get(i).id));
                return out;
            }

            public String needle(LuaValue member) {
                LuaQuest h = resolve(member);
                return needleOf((h == null) ? null : quest(user, h.id));
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isnumber())
                    throw new LuaError(CharApi.Q + ":get(id): a quest is addressed by its server ID, a"
                        + " number — " + CharApi.Q + ":find(\"<title>\") is the search by name");
                int qid = key.toint();
                return (quest(user, qid) == null) ? LuaValue.NIL : of(owner, user, qid);
            }

            /** The key is the quest's server id. */
            public String keyName() {
                return "id";
            }
        }, extra);
    }
}
