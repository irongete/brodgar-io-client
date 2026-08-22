package io.brodgar.addon;

import haven.GItem;
import haven.ItemInfo;
import haven.SAttrWnd;
import haven.Widget;
import haven.resutil.Curiosity;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>StudySlot object</b> — one curiosity in the study window ({@code s:study():slot()}), with the
 * study profile the item's resource publishes: learning points, mental weight, experience cost and the
 * total study time.
 *
 * <p><b>The intern key is the item widget's identity</b> (§2.4). It has to be: the same curiosity can sit
 * in two slots at once, so neither its resource nor its name identifies it, and the study inventory has no
 * index the server addresses. This is {@link LuaBuff}'s key one subsystem along, for the same reason.
 *
 * <p><b>A slot keeps answering after it leaves the window.</b> {@code Widget.destroy()} unlinks the item
 * without clearing it, so a slot taken out of study still reads its own numbers and reports
 * {@code :exists()} false — which is what makes a stashed {@code StudyChanged} payload worth holding.
 *
 * <p><b>The profile streams in a beat after the item.</b> {@code GItem.info()} throws {@code Loading} until
 * the resource resolves, so a slot is routinely resource-only for a moment and every number reads
 * {@code nil}; the next read has them, and that resolution is itself a {@code StudyChanged}.
 */
public final class LuaStudySlot {
    /** The curiosity item this handle addresses — the whole state of a handle. */
    public final GItem wdg;

    private LuaStudySlot(GItem wdg) {
        this.wdg = wdg;
    }

    /** {@code tostring(slot)}: {@code StudySlot(<resname>)}. */
    public String toString() {
        String r = CharApi.itemResOf(wdg);
        return "StudySlot(" + ((r == null) ? "?" : r) + ")";
    }

    /** An interned StudySlot object for {@code it} in {@code owner}'s env. */
    static LuaValue of(Addon owner, GItem it) {
        return owner.studySlots.of(it);
    }

    /** The {@code LuaStudySlot} behind a Lua value, or {@code null} for anything else. */
    static LuaStudySlot resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaStudySlot) ? (LuaStudySlot)o : null;
    }

    // ---- the per-addon intern cache + metatable ---------------------------------------------------

    /** One addon's StudySlot cache and metatable (its {@link Addon#studySlots}), keyed by widget identity. */
    static final class Cache {
        /** The addon these handles belong to — what mints the Widget :widget() crosses to (094). */
        private final Addon owner;
        private final Map<GItem, Ref> live = new IdentityHashMap<GItem, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(GItem it) {
            drain();
            if(it == null)
                return LuaValue.NIL;
            Ref r = live.get(it);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(it);
            }
            LuaValue v = LuaValue.userdataOf(new LuaStudySlot(it), meta());
            live.put(it, new Ref(v, it, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref br = (Ref)r;
                if(live.get(br.key) == br)
                    live.remove(br.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final GItem key;

        Ref(LuaValue v, GItem key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the StudySlot metatable --------------------------------------------------------------------

    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("studyslot", methods(owner),
            "one curiosity in the study window answers :res() :name() :lp() :attention() :cost() :time() "
            + ":progress() :exists() :widget() and :info()"));
        mt.set("__name", LuaValue.valueOf("StudySlot"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaStudySlot h = resolve(self);
                return LuaValue.valueOf((h == null) ? "StudySlot(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the curiosity's resource name, its stable identity, or nil while it resolves.
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String r = CharApi.itemResOf(handle(self, "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the curiosity's display name, or nil until its item info lands.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = CharApi.itemNameOf(handle(self, "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        m.set("lp", number("lp", 0));
        m.set("attention", number("attention", 1));
        m.set("cost", number("cost", 2));
        m.set("time", number("time", 3));
        // progress() — how far through the study this curiosity is, 0..1. Best-effort: the client tracks it
        // for some items and not others, so it is often nil.
        m.set("progress", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                GItem it = handle(self, "progress").wdg;
                return (it.meter > 0) ? LuaValue.valueOf(it.meter / 100.0) : LuaValue.NIL;
            }
        });
        // exists() — is this curiosity still in the study window?
        // widget() — 094 (A-104): THE CROSSING BACK. The widget tree and the domain objects are two address
        // spaces, and until now they met in one place and in one direction (widget:items()). So "draw a badge
        // over the buff that is about to expire" needed the buff's widget and there was none: s:ui():match()
        // reaches the WINDOW by role, and the thing inside it is a domain object the selector language cannot
        // address. The bridge was already holding the widget -- it IS the handle -- so the crossing is one
        // closure. nil once the thing is gone, like every other read here.
        m.set("widget", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaStudySlot h = handle(self, "widget");
                return LuaWidget.of(owner, h.wdg);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(inStudy(handle(self, "exists").wdg));
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").wdg);
            }
        });
        return m;
    }

    /** One of the four {@link Curiosity} numbers, all nil together while the item's info is still Loading. */
    private static OneArgFunction number(final String verb, final int which) {
        return new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Curiosity ci = curiosity(handle(self, verb).wdg);
                if(ci == null)
                    return LuaValue.NIL;
                switch(which) {
                case 0: return LuaValue.valueOf(ci.exp);
                case 1: return LuaValue.valueOf(ci.mw);
                case 2: return LuaValue.valueOf(ci.enc);
                default: return LuaValue.valueOf(ci.time);
                }
            }
        };
    }

    private static LuaStudySlot handle(LuaValue self, String method) {
        LuaStudySlot h = resolve(self);
        if(h == null)
            throw new LuaError("slot:" + method + "() — use a COLON call on a StudySlot object"
                + " (s:study():slot():list()[i])");
        return h;
    }

    // ---- the reads (all Loading-guarded) -------------------------------------------------------------

    /** The item's {@link Curiosity} study profile, or {@code null} while its info is still resolving. */
    static Curiosity curiosity(GItem it) {
        if(it == null)
            return null;
        try {
            return ItemInfo.find(Curiosity.class, it.info());
        } catch(RuntimeException e) {   // info() still Loading
            return null;
        }
    }

    /** The curiosities in that character's study window, in the order the window holds them. */
    static List<GItem> items(String user) {
        return under(CharApi.studyWidget(user));
    }

    /** The curiosities under one study inventory widget, in its own child order. */
    private static List<GItem> under(Widget study) {
        List<GItem> out = new ArrayList<GItem>();
        if(study != null) {
            for(GItem it : study.children(GItem.class))
                out.add(it);
        }
        return out;
    }

    /**
     * Is {@code it} still in a study window? The predicate {@code :exists()} answers — asked of the item
     * itself rather than of a named character, so a slot handle reports about the window it was taken from
     * however many sessions are live. It is the study inventory the item has to be a child of: an item
     * dragged into an ordinary inventory has left study, and its own {@link SAttrWnd.StudyInfo} is what says
     * which container counts.
     */
    static boolean inStudy(GItem it) {
        if((it == null) || (it.parent == null))
            return false;
        // The study inventory and its StudyInfo are SIBLINGS under SAttrWnd -- the info panel holds the
        // inventory rather than containing it -- so the walk goes up to the tab and back down, exactly as
        // CharApi.studyInfo does. Walking up to StudyInfo itself would never find one.
        SAttrWnd w = it.getparent(SAttrWnd.class);
        if(w == null)
            return false;
        for(SAttrWnd.StudyInfo si : w.children(SAttrWnd.StudyInfo.class)) {
            if(si.study == it.parent)
                return true;
        }
        return false;
    }

    /** The documented {@code StudySlot} snapshot — every field optional while the item resolves. */
    static LuaValue snapshot(GItem it) {
        if(it == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String res = CharApi.itemResOf(it);
        if(res != null)
            t.set("res", LuaValue.valueOf(res));
        String name = CharApi.itemNameOf(it);
        if(name != null)
            t.set("name", LuaValue.valueOf(name));
        Curiosity ci = curiosity(it);
        if(ci != null) {
            t.set("lp", LuaValue.valueOf(ci.exp));
            t.set("attention", LuaValue.valueOf(ci.mw));
            t.set("cost", LuaValue.valueOf(ci.enc));
            t.set("time", LuaValue.valueOf(ci.time));
        }
        if(it.meter > 0)
            t.set("progress", LuaValue.valueOf(it.meter / 100.0));
        return t;
    }

    // ---- the collection ------------------------------------------------------------------------------

    /**
     * {@code s:study():slot()} — the curiosities in the window. <b>There is no {@code :get}</b>: a slot
     * has no key, since the same curiosity can occupy two of them, so a string is a search
     * ({@code :find(needle)} over the resource and the display name) and a position is
     * {@code :list()[n]}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.ST + ":slot()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<GItem> its = items(user);
                List<LuaValue> out = new ArrayList<LuaValue>(its.size());
                for(int i = 0; i < its.size(); i++)
                    out.add(of(owner, its.get(i)));
                return out;
            }

            public String needle(LuaValue member) {
                LuaStudySlot h = resolve(member);
                GItem it = (h == null) ? null : h.wdg;
                String res = CharApi.itemResOf(it), nm = CharApi.itemNameOf(it);
                return ((res == null) ? "" : res) + "\n" + ((nm == null) ? "" : nm);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public String noGet() {
                return "a slot has no key, since the same curiosity can sit in two of them: " + CharApi.ST
                    + ":slot():find(needle) is the search and " + CharApi.ST
                    + ":slot():list()[n] takes a position";
            }
        }, null);
    }
}
