package io.brodgar.addon;

import haven.Gob;
import haven.Material;
import haven.res.lib.vmat.AttrMats;
import haven.res.lib.vmat.VarMats;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>MaterialSlot object</b> — one variable-material slot of a game object (spec {@code 152-gob-materials},
 * task 152.1). Many objects are one model drawn in variable materials: the server sends one material
 * resource per slot (the {@code lib/vmat} attribute) and the client wraps each mesh tagged {@code vm=<n>} in
 * it. This handle is one of those slots, read through the resource's own attribute — the adopted
 * {@link AttrMats} under {@code src/haven/res/lib/vmat/} — rather than through any decoding of ours.
 *
 * <p><b>Nothing crosses but the answer.</b> A handle holds the login it was read in, the gob id and the
 * slot's wire number, and re-resolves {@code gob.getattr(VarMats.class)} on every call — so a stashed
 * handle tracks the gob, and every read answers {@code nil} once the gob is gone or the server has re-sent
 * the object with fewer slots. {@code :index()} and {@code :wire()} answer from the handle alone. Interned
 * per (addon, login, gob id, wire), the {@link LuaOverlay} shape one collection over.
 *
 * <p><b>Two numbers, two verbs.</b> {@code :index()} is the 1-based position {@code gob:materials():get(n)}
 * takes and {@code :list()[n]} holds; {@code :wire()} is the server's own number — the {@code vm} tag on the
 * mesh, {@code index - 1} — under the name every wire-side number in this API carries ({@link LuaSlot}).
 *
 * <p><b>Three resource reads, one answer today.</b> {@code :native()} is the material the server dressed the
 * slot in, {@code :material()} the one in force, {@code :drawn()} the one the model is drawn with on the copy
 * this handle reads through. Each hands back the interned {@link LuaResource} for the name, so
 * {@code slot:material() == slot:native()} compares handles. Until an addon can write a slot, the three
 * are the server's resource.
 */
public final class LuaMaterialSlot {
    /** The login this handle was minted through — the copy every read resolves in. */
    public final String user;
    /** The gob id. */
    public final long gob;
    /** The server's number for the slot: the {@code vm} tag on the mesh, {@code 0..}. */
    public final int wire;

    private LuaMaterialSlot(String user, long gob, int wire) {
        this.user = user;
        this.gob = gob;
        this.wire = wire;
    }

    public String toString() {
        return "MaterialSlot(" + (wire + 1) + "@" + Long.toString(gob) + ")";
    }

    /** An interned MaterialSlot object in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String user, long gob, int wire) {
        return owner.materialSlots.of(user, gob, wire);
    }

    /** The {@code LuaMaterialSlot} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaMaterialSlot resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMaterialSlot) ? (LuaMaterialSlot)o : null;
    }

    // ---- the server's dressing ---------------------------------------------------------------------

    /**
     * The server's variable materials on {@code g}, or {@code null} for an object that has none — a composed
     * body, a tree, a gob whose {@code lib/vmat} attribute has not arrived. The attribute sits under
     * {@code VarMats.class} ({@code Gob.attrclass} keys on the class directly under {@code GAttrib}), and the
     * {@code instanceof} misses when the served {@code lib/vmat} has moved past the adopted version — which
     * reads as "no object has slots", the degradation the adoption promises.
     */
    static AttrMats served(Gob g) {
        if(g == null)
            return null;
        VarMats vm = g.getattr(VarMats.class);
        return (vm instanceof AttrMats) ? (AttrMats)vm : null;
    }

    /**
     * How many slots the server dressed on {@code g}: {@code 0} for an object with none. Counted by walking
     * the entries, never by {@code mats.size()}: {@code AttrMats.decode} builds a {@code haven.IntMap}, whose
     * {@code put} never bumps the counter its {@code size()} answers, so {@code size()} is {@code 0} on every
     * dressed object ({@code docs/client/gob-sprites.md}).
     */
    static int count(Gob g) {
        AttrMats am = served(g);
        if(am == null)
            return 0;
        int n = 0;
        for(Map.Entry<Integer, Material> e : am.mats.entrySet()) {
            if(e.getValue() != null)
                n++;
        }
        return n;
    }

    /**
     * The server's material for slot {@code wire} on {@code g}, or {@code null} — a {@link Material.ResMaterial}
     * every time {@code AttrMats.decode} built it, which is the only builder there is.
     */
    static Material.ResMaterial nativeOf(Gob g, int wire) {
        AttrMats am = served(g);
        Material m = (am == null) ? null : am.mats.get(wire);
        return (m instanceof Material.ResMaterial) ? (Material.ResMaterial)m : null;
    }

    /** The name of the resource in force on slot {@code wire} of {@code g}, or {@code null}. */
    static String inForce(Gob g, int wire) {
        Material.ResMaterial rm = nativeOf(g, wire);
        return (rm == null) ? null : rm.res.name;
    }

    /** The name of the resource slot {@code wire} of {@code g} is drawn with, or {@code null}. */
    static String drawnOn(Gob g, int wire) {
        return inForce(g, wire);
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's MaterialSlot interning cache and metatable ({@link Addon#materialSlots}); {@link LuaOverlay.Cache}'s shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for (login, gob, wire) — a hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String user, long gob, int wire) {
            drain();
            String ck = user + "\0" + Long.toString(gob) + "\0" + wire;
            Ref r = live.get(ck);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(ck);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMaterialSlot(user, gob, wire), meta());
            live.put(ck, new Ref(v, ck, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                if(live.get(or.key) == or)
                    live.remove(or.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the MaterialSlot metatable ----------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("materialslot", methods(owner),
            "one variable-material slot of a game object"));
        mt.set("__name", LuaValue.valueOf("MaterialSlot"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMaterialSlot h = resolve(self);
                return LuaValue.valueOf((h == null) ? "MaterialSlot(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // index() — the 1-based position gob:materials():get(n) takes, so :list()[n]:index() == n. Answers from
        // the handle alone, so it still reads after the gob is gone.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "slot:index"), "index").wire + 1);
            }
        });
        // wire() — the server's own number for the slot: the `vm` tag on the mesh, index - 1. The server
        // owns the object and 0 is its number, which is why the raw one is reachable; it is just not the
        // thing a verb named `index` answers while :list() counts from one.
        m.set("wire", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "slot:wire"), "wire").wire);
            }
        });
        // native() — the material the SERVER dressed the slot in, as the interned Resource handle for its name;
        // nil once the gob is gone or the slot is no longer one the server sends.
        m.set("native", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaMaterialSlot h = handle(Args.only(a, 0, "slot:native"), "native");
                Material.ResMaterial rm = nativeOf(AddonManager.getgob(h.user, h.gob), h.wire);
                return (rm == null) ? LuaValue.NIL : LuaResource.of(owner, rm.res.name);
            }
        });
        // material() — the material IN FORCE on the slot: the server's, until an addon writes one.
        m.set("material", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaMaterialSlot h = handle(Args.only(a, 0, "slot:material"), "material");
                String nm = inForce(AddonManager.getgob(h.user, h.gob), h.wire);
                return (nm == null) ? LuaValue.NIL : LuaResource.of(owner, nm);
            }
        });
        // drawn() — the material the model is DRAWN with right now, on the copy this handle reads through.
        m.set("drawn", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaMaterialSlot h = handle(Args.only(a, 0, "slot:drawn"), "drawn");
                String nm = drawnOn(AddonManager.getgob(h.user, h.gob), h.wire);
                return (nm == null) ? LuaValue.NIL : LuaResource.of(owner, nm);
            }
        });
        // info() — the one SNAPSHOT: {index, wire, native, material, drawn, id}, the three resources as
        // names and `id` the mat2 layer the server's material is; nil once the gob is gone.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaMaterialSlot h = handle(Args.only(a, 0, "slot:info"), "info");
                Gob g = AddonManager.getgob(h.user, h.gob);
                Material.ResMaterial rm = nativeOf(g, h.wire);
                if(rm == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("index", LuaValue.valueOf(h.wire + 1));
                t.set("wire", LuaValue.valueOf(h.wire));
                t.set("native", LuaValue.valueOf(rm.res.name));
                t.set("material", LuaValue.valueOf(inForce(g, h.wire)));
                t.set("drawn", LuaValue.valueOf(drawnOn(g, h.wire)));
                t.set("id", LuaValue.valueOf(rm.id));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMaterialSlot handle(LuaValue self, String method) {
        LuaMaterialSlot h = resolve(self);
        if(h == null)
            throw new LuaError("slot:" + method + "() -- use a COLON call on a MaterialSlot object"
                + " (gob:materials():get(n), or one out of gob:materials():list())");
        return h;
    }
}
