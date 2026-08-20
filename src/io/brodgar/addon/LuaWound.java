package io.brodgar.addon;

import haven.ItemInfo;
import haven.UI;
import haven.WoundWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
        mt.set(LuaValue.INDEX, Retired.closedIndex("wound", methods(owner),
            "a wound answers :id() :name() :res() :severity() :parent() :level() :exists() and :info()"));
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
        // severity() — the magnitude the client shows beside the wound. A string, and NOT seconds.
        m.set("severity", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "severity");
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
        // level() — how deep in the tree the window indents this wound; 0 at a root.
        m.set("level", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWound h = handle(self, "level");
                WoundWnd.Wound w = wound(h.user, h.id);
                return (w == null) ? LuaValue.NIL : LuaValue.valueOf(w.level);
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
            ItemInfo.Name n = ItemInfo.find(ItemInfo.Name.class, w.info());
            return ((n == null) || (n.str == null)) ? null : n.str.text;
        } catch(RuntimeException e) {   // info() still Loading / no rawinfo yet
            return null;
        }
    }

    /**
     * The severity indicator the client shows beside a wound — its highest-priority {@link
     * WoundWnd.QuickInfo}'s {@code qstr()} (a content-defined string, usually the wound's magnitude number;
     * <b>not</b> seconds), or {@code null} while it is still Loading. Mirrors the client's own pick.
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
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.WD, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<WoundWnd.Wound> ws = all(user);
                List<LuaValue> out = new ArrayList<LuaValue>(ws.size());
                for(int i = 0; i < ws.size(); i++)
                    out.add(of(owner, user, ws.get(i).id));
                return out;
            }

            // res OR name, as one string the substring test runs over once — the same two halves the old
            // presence test matched. The separator is a newline, which neither half ever contains.
            public String needle(LuaValue member) {
                LuaWound h = resolve(member);
                WoundWnd.Wound w = (h == null) ? null : wound(user, h.id);
                if(w == null)
                    return "";
                String r = AddonManager.resIdent(w.res), nm = nameOf(w);
                return ((r == null) ? "" : r) + "\n" + ((nm == null) ? "" : nm);
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
                    throw new LuaError(CharApi.WD + ":get(id): a wound is addressed by its ID, a number —"
                        + " " + CharApi.WD + ":find(\"<name>\") is the search by name or resource");
                int wid = key.toint();
                return (wound(user, wid) == null) ? LuaValue.NIL : of(owner, user, wid);
            }

            /** The key is the wound's server id. */
            public String keyName() {
                return "id";
            }
        }, null);
    }
}
