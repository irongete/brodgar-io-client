package io.brodgar.addon;

import haven.ItemInfo;
import haven.UI;
import haven.WoundWnd;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Wound object</b> — one of the character's wounds ({@code s:wound()}). <b>The section object IS
 * the wound list</b> (uniform grammar §2.1): {@code s:wound()} is the collection,
 * {@code s:wound():find(needle)} the first whose name or resource contains it, and
 * {@code s:wound():get(id)} one wound by its id.
 *
 * <p><b>Wounds are a TREE and the object is what makes that readable.</b> The client keeps a flat list in
 * tree order, each entry carrying the id of its parent and the depth the window indents it by; a complication
 * hangs off the wound that caused it. The parent id was a number an addon had to resolve for itself, and
 * {@code w:parent()} is now that resolution — the Wound above this one, or {@code nil} at a root.
 *
 * <p><b>The intern key is the wound id</b> (§2.4's <i>publishes a stable id</i> row): the {@code "wounds"}
 * message looks a wound up by id and mutates the record in place, so a wound worsening is the same wound and
 * a stashed handle is the right way to watch one. Healing removes it, which is what {@code :exists()} reads.
 *
 * <p><b>Severity is a magnitude, not a countdown.</b> It is the string the client paints beside the wound —
 * content-defined, usually a number and <b>never</b> seconds — and it arrives a beat after the wound itself,
 * so it is {@code nil} for that beat.
 *
 * <p><b>Threading.</b> The list is mutated on a loader thread under the UI monitor and reassigned by the
 * window's own tree sort on the UI thread, so every read copies the list inside the monitor and resolves
 * names and severities outside it (both may still be Loading).
 */
public final class LuaWound {
    /** The account this wound is on — half the address, and what makes the id mean one thing. */
    public final String user;
    /** The wound's id, on that character. */
    public final int id;

    private LuaWound(String user, int id) {
        this.user = user;
        this.id = id;
    }

    /** {@code tostring(w)}: {@code Wound(<id>)}. */
    public String toString() {
        return "Wound(" + id + ")";
    }

    /** An interned Wound object for wound {@code id} <b>of session {@code user}</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int id) {
        return owner.wounds.of(user, id);
    }

    /** The {@code LuaWound} behind a Lua value, or {@code null} for anything else. */
    static LuaWound resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaWound) ? (LuaWound)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Wound cache and metatable (its {@link Addon#wounds}), keyed by the <b>account plus</b> the
     * wound id: a wound id counts within one character's own list, so the same number on two characters is
     * two different wounds and must be two handles. Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int wid) {
            drain();
            Map<Integer, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(wid);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaWound(user, wid), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref wr = (Ref)r;
                Map<Integer, Ref> byid = live.get(wr.user);
                if(byid == null)
                    continue;
                if(byid.get(wr.key) == wr)
                    byid.remove(wr.key);
                if(byid.isEmpty())
                    live.remove(wr.user);
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

    // ---- the Wound metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("wound", methods(owner),
            "a wound"));
        mt.set("__name", LuaValue.valueOf("Wound"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Wound(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the wound's own id, which is what the tree is linked by.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        // name() — the wound's display name, nil for the beat before its data resolves.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "name");
                WoundWnd.Wound w = wound(h.user, h.id);
                String nm = (w == null) ? null : nameOf(w);
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // res() — the wound's stable resource name.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "res");
                WoundWnd.Wound w = wound(h.user, h.id);
                String r = (w == null) ? null : AddonManager.resIdent(w.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // severity() — the magnitude as a number, nil where the label is not one. NOT seconds.
        m.set("severity", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "severity");
                WoundWnd.Wound w = wound(h.user, h.id);
                String s = (w == null) ? null : severityOf(w);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s).tonumber();
            }
        });
        // label() — the very string the client paints beside the wound, whether or not it is a number.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "label");
                WoundWnd.Wound w = wound(h.user, h.id);
                String s = (w == null) ? null : severityOf(w);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // parent() — the wound this one is a complication of, or nil at a root of the tree.
        m.set("parent", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "parent");
                WoundWnd.Wound w = wound(h.user, h.id);
                if((w == null) || (w.parentid < 0) || (wound(h.user, w.parentid) == null))
                    return LuaValue.NIL;
                return of(owner, h.user, w.parentid);
            }
        });
        // children() — 094 (A-109): the complications OF this wound, as a collection. The tree could be
        // walked UP and not down: :parent() resolved one and there was nothing coming back, so "show me this
        // wound and everything under it" -- the natural reading of a wound tree -- was a manual scan of the
        // whole list per wound, comparing :parent() against a held object, which is exactly the work an API
        // is supposed to have done. The API's two other tree-shaped types expose both directions
        // (pag:parent()/pag:children(), widget:parent()/widget:children()); this one exposed one.
        m.set("children", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "children");
                return subtree(owner, h.user, h.id);
            }
        });
        // depth() — how deep in the tree this wound hangs; 0 at a root.
        //   audit2 B10 (cq-20): counted UP THE PARENT CHAIN, which is the one authority :parent(),
        // :children() and :roots() already read. It used to answer the window's own `level` field, which is
        // written only by WoundWnd.treesort and only while the window is loading -- so a complication whose
        // parent healed out was a root by every other verb here and still reported its old indent, breaking
        // wound.md's "nil at a root, which is where :depth() is 0" for as long as the window went unsorted.
        // The walk is bounded by the roster: a chain longer than the list of wounds is a cycle, not a tree.
        m.set("depth", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "depth");
                Map<Integer, WoundWnd.Wound> ws = byId(all(h.user));   // one copy of the roster, then a hash
                WoundWnd.Wound w = ws.get(Integer.valueOf(h.id));
                if(w == null)
                    return LuaValue.NIL;
                int depth = 0;
                for(WoundWnd.Wound up = w; (up.parentid >= 0) && (depth < ws.size()); depth++) {
                    up = ws.get(Integer.valueOf(up.parentid));
                    if(up == null)          // the parent healed out: this one is a root, as :parent() says
                        return LuaValue.valueOf(depth);
                }
                return LuaValue.valueOf(depth);
            }
        });
        // exists() — is this wound still on the character? (Healing takes it off the list.)
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "exists");
                return LuaValue.valueOf(wound(h.user, h.id) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "info");
                return snapshot(wound(h.user, h.id));
            }
        });
        return m;
    }

    private static LuaWound handle(LuaValue self, String method) {
        LuaWound h = resolve(self);
        if(h == null)
            throw new LuaError("wound:" + method + "() — use a COLON call on a Wound object"
                + " (" + CharApi.WD + ":find(needle), :get(id) or :list()[i])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** The Health &amp; Wounds window (created hidden at login but live), or {@code null} before it exists. */
    static WoundWnd wnd(String user) {
        return CharApi.woundwnd(user);
    }

    /** The live wound list, copied under the {@code ui} monitor, in the window's own tree order. */
    static List<WoundWnd.Wound> all(String user) {
        List<WoundWnd.Wound> out = new ArrayList<WoundWnd.Wound>();
        WoundWnd ww = wnd(user);
        if(ww == null)
            return out;
        synchronized(LuaWidget.monitor(ww)) {
            out.addAll(ww.wounds.wounds);
        }
        return out;
    }

    /** {@link #all} keyed by wound id: one copy of the list, and every lookup of one call is a hash. */
    static Map<Integer, WoundWnd.Wound> byId(List<WoundWnd.Wound> ws) {
        Map<Integer, WoundWnd.Wound> out = new HashMap<Integer, WoundWnd.Wound>(ws.size() * 2);
        for(int i = 0; i < ws.size(); i++)
            out.put(Integer.valueOf(ws.get(i).id), ws.get(i));
        return out;
    }

    /** The needle of one wound: res OR name, as one string the substring test runs over once. */
    private static String needleOf(WoundWnd.Wound w) {
        if(w == null)
            return "";
        String r = AddonManager.resIdent(w.res), nm = nameOf(w);
        return ((r == null) ? "" : r) + "\n" + ((nm == null) ? "" : nm);
    }

    /** The live record for {@code wid}, or {@code null} once the wound has healed. */
    static WoundWnd.Wound wound(String user, int wid) {
        for(WoundWnd.Wound w : all(user)) {
            if(w.id == wid)
                return w;
        }
        return null;
    }

    /** Display name of a wound: the resource tooltip, else the server-pushed name. Loading-guarded. */
    static String nameOf(WoundWnd.Wound w) {
        String tip = AddonManager.resTipName(w.res, null);
        if(tip != null)
            return tip;
        try {
            // addon: (102.6) the name row's SOURCE, not the raster it drew -- CharApi.nameStr is the one read.
            return CharApi.nameStr(ItemInfo.find(ItemInfo.Name.class, w.info()));
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /**
     * The severity indicator the client shows beside a wound — its highest-priority {@link
     * WoundWnd.QuickInfo}'s {@code qstr()} (a content-defined string, usually the wound's magnitude number;
     * <b>not</b> seconds), or {@code null} while it is still Loading. Mirrors the client's own pick.
     *
     * <p><b>Two verbs read it</b> (085.4), because there are two facts here and one string was standing for
     * both: {@code w:label()} is this string unchanged, and {@code w:severity()} is Lua's own parse of it —
     * a number, or {@code nil} where the content chose a word. The pair is what {@code food:label()} and its
     * numbers already do; one verb answering "usually a number" made {@code (tonumber(...) or 0)} the only
     * correct thing to write.
     */
    static String severityOf(WoundWnd.Wound w) {
        try {
            List<ItemInfo> info = w.info();           // may throw Loading
            WoundWnd.QuickInfo best = null;
            for(ItemInfo inf : info) {
                if(inf instanceof WoundWnd.QuickInfo) {
                    WoundWnd.QuickInfo qi = (WoundWnd.QuickInfo)inf;
                    if((best == null) || (best.qprio() < qi.qprio()))
                        best = qi;
                }
            }
            return (best == null) ? null : best.qstr();   // qstr() itself may be null (no quick string)
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /** The documented {@code Wound} snapshot, or {@code nil} once the wound has healed. */
    static LuaValue snapshot(WoundWnd.Wound w) {
        if(w == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf(w.id));
        String nm = nameOf(w);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        String r = AddonManager.resIdent(w.res);
        if(r != null)
            t.set("res", LuaValue.valueOf(r));
        String sev = severityOf(w);
        if(sev != null)
            t.set("severity", LuaValue.valueOf(sev));
        t.set("parentid", LuaValue.valueOf(w.parentid));
        t.set("level", LuaValue.valueOf(w.level));
        return t;
    }

    /** Every wound as a snapshot, in tree order — the change-detection input of {@code WoundChanged}. */
    static LuaTable snapshotList(String user) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(WoundWnd.Wound w : all(user))
            out.set(++i, snapshot(w));
        return out;
    }

    /** Every wound id, in tree order — the {@code WoundChanged} payload, before it is interned per owner. */
    static int[] ids(String user) {
        List<WoundWnd.Wound> ws = all(user);
        int[] out = new int[ws.size()];
        for(int i = 0; i < ws.size(); i++)
            out[i] = ws.get(i).id;
        return out;
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:wound()} — every wound, flat and in the window's own tree order, so indenting by
     * {@code w:level()} prints the shape. Addressable by wound id. There is no {@code :add}/{@code :remove}:
     * wounds heal by playing and by tending, and no client can add or take one away.
     */
    /**
     * <b>The wounds whose {@code parentid} is {@code parent}</b>, as a collection (094, A-109) — the
     * complications of one wound for {@code wound:children()}, and the top of the tree for
     * {@code s:wound():roots()} when {@code parent} is {@code -1}.
     *
     * <p>One pass over the very list {@link #collection} already builds, so both directions cost what the
     * list costs and neither holds anything: {@code WoundWnd.wounds} is re-read on every call, exactly as
     * every other read here does.
     */
    static LuaValue subtree(final Addon owner, final String user, final int parent) {
        final String verb = (parent < 0) ? (CharApi.WD + ":roots()") : "wound:children()";
        return LuaCollection.create(verb, new LuaCollection.Source() {
            /** The list of the last {@link #members()} by id: rootness and every needle of one call read it. */
            private Map<Integer, WoundWnd.Wound> seen = Collections.emptyMap();

            public List<LuaValue> members() {
                List<WoundWnd.Wound> ws = all(user);
                seen = byId(ws);
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(int i = 0; i < ws.size(); i++) {
                    WoundWnd.Wound w = ws.get(i);
                    // A root is one whose parent is not on the roster -- the server's own -1, and also a
                    // complication whose parent has healed out from under it. The window does not draw that
                    // one flat: treesort collects only what hangs off a wound it has already walked, so it
                    // drops it from the drawn list until the server sends the roster again. This says what
                    // the tree IS, and :depth() counts the same chain (audit2 B10, cq-20).
                    boolean root = (w.parentid < 0) || !seen.containsKey(Integer.valueOf(w.parentid));
                    if(root ? (parent < 0) : (w.parentid == parent))
                        out.add(of(owner, user, w.id));
                }
                return out;
            }

            public String needle(LuaValue member) {
                LuaWound h = resolve(member);
                if(h == null)
                    return "";
                WoundWnd.Wound w = seen.get(Integer.valueOf(h.id));
                return needleOf((w != null) ? w : wound(user, h.id));
            }

            public boolean named() {
                return true;
            }

            public String noGet() {
                return "a wound is addressed by its id on the whole list, not inside a branch of it: "
                    + CharApi.WD + ":get(id) reads one wherever it hangs, and :find(\"<name>\") searches";
            }
        }, null);
    }

    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.WD, new LuaCollection.Source() {
            /** The list of the last {@link #members()} by id, so every needle of one call is a hash lookup. */
            private Map<Integer, WoundWnd.Wound> seen = Collections.emptyMap();

            public List<LuaValue> members() {
                List<WoundWnd.Wound> ws = all(user);
                seen = byId(ws);
                List<LuaValue> out = new ArrayList<LuaValue>(ws.size());
                for(int i = 0; i < ws.size(); i++)
                    out.add(of(owner, user, ws.get(i).id));
                return out;
            }

            // res OR name, as one string the substring test runs over once — the same two halves the old
            // presence test matched. The separator is a newline, which neither half ever contains.
            public String needle(LuaValue member) {
                LuaWound h = resolve(member);
                if(h == null)
                    return "";
                WoundWnd.Wound w = seen.get(Integer.valueOf(h.id));
                return needleOf((w != null) ? w : wound(user, h.id));
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                int wid = Args.integer(key, CharApi.WD + ":get", "id", "a wound's id — "
                                       + CharApi.WD + ":find(\"<name>\") is the search by name or resource");
                return (wound(user, wid) == null) ? LuaValue.NIL : of(owner, user, wid);
            }

            /** The key is the wound's server id. */
            public String keyName() {
                return "id";
            }
        }, roots(owner, user));
    }

    /** {@code s:wound():roots()} — the wounds nothing is a complication of, mirroring
     *  {@code s:menugrid():roots()} (094, A-109). */
    private static LuaTable roots(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        extra.set("roots", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.WD, "roots");
                Args.only(a, 0, CharApi.WD + ":roots");
                return subtree(owner, user, -1);
            }
        });
        return extra;
    }
}
