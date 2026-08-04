package io.brodgar.addon;

import haven.Gob;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * An <b>Overlay object</b> — one thing attached to a game object, whether an addon attached it
 * ({@code gob:overlay(key, spec)}) or the game itself did ({@code Gob.ols}: the fire's flame, the crop's growth
 * stage, a curiosity's sparkle). Spec {@code 038-gob-overlays}, task 038.1.
 *
 * <p><b>Two spaces, one read.</b> {@code gob:overlay()} answers every overlay on the gob and each one says which
 * it is: {@code ov:native()} is {@code true} for the game's own and {@code false} for the addon's. The native
 * ones are <b>read-only</b> — an attach onto a native key and {@code gob:overlay(nativeKey, nil)} both raise,
 * naming the key, rather than silently doing nothing to something the addon does not own.
 *
 * <p><b>The key is the identity, and a native one is the RESOURCE NAME.</b> A {@code Gob.Overlay} carries an
 * {@code id} only when the server chose to give it one, and an id is a number no other part of this API speaks —
 * so the name a Lua caller can address is the resource, exactly as an icon category collapses its sub-id
 * (D-093). Two of the game's overlays sharing one resource therefore collapse to one key, and 038.1
 * <b>measured</b> how often rather than assuming: 13 of 33 gobs carrying overlays had two of one resource.
 * So a native Overlay is the <b>UNION</b> of them, on 037.3's mask precedent, and the multiplicity that
 * would otherwise be lost is published — {@code ov:count()}. Nothing else is lost, because a native overlay
 * is read-only: there is no operation a finer identity would enable.
 *
 * <p><b>Nothing crosses but the answer.</b> A handle holds the gob id, the key and the native flag, and
 * re-resolves through the {@link LuaGobOverlay} attrib (or through {@code Gob.ols}) on every call — so a stashed
 * handle tracks the gob, and {@code ov:exists()} goes false when the overlay, or the gob under it, is gone.
 * Interned per (addon, gob id, native, key), the {@link LuaGob} shape one level down.
 */
public final class LuaOverlay {
    /** The gob this overlay is attached to. */
    public final long gob;
    /** The key: the addon's own for its overlays, the resource name for the game's. */
    public final String key;
    /** Is this one of the game's own overlays (read-only) rather than the addon's? */
    public final boolean nat;

    private LuaOverlay(long gob, String key, boolean nat) {
        this.gob = gob;
        this.key = key;
        this.nat = nat;
    }

    /** {@code tostring(ov)}: {@code Overlay(<key>@<gobId>)}, with a {@code *} on the game's own. */
    public String toString() {
        return "Overlay(" + (nat ? "*" : "") + key + "@" + Long.toString(gob) + ")";
    }

    /** An interned Overlay object in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long gob, String key, boolean nat) {
        return owner.gobOverlayObjs.of(gob, key, nat);
    }

    /** The {@code LuaOverlay} behind a Lua value, or {@code null} for anything that is not an Overlay object. */
    static LuaOverlay resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOverlay) ? (LuaOverlay)o : null;
    }

    /**
     * Every overlay on {@code g} as an array: this addon's own first, in attach order, then the game's own —
     * which is the whole of {@code gob:overlay()}. Another addon's overlays are not in it (keys are per addon,
     * so two addons' {@code "tag"} on one gob neither collide nor see each other).
     */
    static LuaTable list(Addon owner, Gob g) {
        LuaTable out = new LuaTable();
        int i = 0;
        LuaGobOverlay store = LuaGobOverlay.on(g);
        if(store != null) {
            for(String k : store.keys(owner))
                out.set(++i, of(owner, g.id, k, false));
        }
        for(String k : LuaGobOverlay.nativeKeys(g))
            out.set(++i, of(owner, g.id, k, true));
        return out;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Overlay interning cache and metatable ({@link Addon#gobOverlayObjs}); the {@link LuaMask} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for (gob, key, native) — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long gob, String key, boolean nat) {
            drain();
            String ck = Long.toString(gob) + (nat ? "\0*\0" : "\0-\0") + key;
            Ref r = live.get(ck);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(ck);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOverlay(gob, key, nat), meta());
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

    // ---- the Overlay metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Overlay"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Overlay(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // key() — what it answers to; answers from the handle alone, so it still reads after the gob is gone.
        m.set("key", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "key").key);
            }
        });
        // gob() — the Gob it is attached to (D-066: the relation, not a stored id).
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaGob.of(owner, handle(self, "gob").gob);
            }
        });
        // native() — is this one of the GAME's overlays (read-only) rather than one of ours?
        m.set("native", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "native").nat);
            }
        });
        // res() — WHAT it is drawn from: the key itself for a native one (a native key IS its resource name), the
        // .res name / asset path for a world-space one of ours, and nil for a screen-space one (it draws Lua, not
        // a resource — which is exactly what distinguishes the two spaces from the outside).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "res");
                if(h.nat)
                    return LuaValue.valueOf(h.key);
                LuaGobOverlay.Attach a = mine(owner, h);
                String nm = ((a == null) || (a.ent == null)) ? null : a.ent.visualName();
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // kind() — "draw" / "text" (screen space) or "image" / "model" / "ghost" (the 3D world) for one of ours;
        // nil for a native one (the game's overlays are read-only and say only that they are the game's).
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaGobOverlay.Attach a = mine(owner, handle(self, "kind"));
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.kind());
            }
        });
        // The COMPOSED verbs (038.2): the look and facing an entity already has, answering on the Overlay object
        // so that absorbing follow= took nothing away. They are for a WORLD-space overlay only — a screen-space
        // one is painted by the addon (or is a label with its own colour), so :tint on it is a category error and
        // is refused naming the kinds rather than quietly doing nothing. Once the overlay is gone they are clean
        // no-ops: the thing they would act on is not there, which is a moment, not a mistake.
        m.set("tint", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue c) {
                if(!c.isnil() && !c.istable())
                    throw new LuaError("overlay:tint(color) expects {r, g, b[, a]} (0..255) or nil to clear");
                LuaWorldEntity e = ent(owner, self, "tint");
                if(e != null)
                    RenderApi.overlayTint(e, c.isnil() ? null : AddonManager.luaColor(c, null));
                return self;
            }
        });
        m.set("alpha", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue a) {
                if(!a.isnumber())
                    throw new LuaError("overlay:alpha(a) expects a number 0..1 (1 = opaque)");
                LuaWorldEntity e = ent(owner, self, "alpha");
                if(e != null)
                    RenderApi.overlayAlpha(e, a.todouble());
                return self;
            }
        });
        m.set("scale", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue s) {
                if(!s.isnumber())
                    throw new LuaError("overlay:scale(s) expects a positive number (1 = original size)");
                LuaWorldEntity e = ent(owner, self, "scale");
                if(e != null)
                    RenderApi.overlayScale(e, s.todouble());
                return self;
            }
        });
        m.set("rotate", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue a) {
                if(!a.isnumber())
                    throw new LuaError("overlay:rotate(a) expects a facing angle in radians");
                LuaWorldEntity e = ent(owner, self, "rotate");
                if(e != null)
                    RenderApi.overlayRotate(e, a.todouble());
                return self;
            }
        });
        // pos() — where the thing actually IS: the gob's live interpolated position plus this overlay's offset,
        // with its own facing and scale. nil once it is gone.
        m.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWorldEntity e = ent(owner, self, "pos");
                return (e == null) ? LuaValue.NIL : RenderApi.entityPos(e);
            }
        });
        // count() — how many engine overlays this one entity stands for: 1 for ours, and for a native one the
        // number the game has of that resource on that gob. A NATIVE OVERLAY IS A UNION, because the key is
        // the resource name and a gob may carry several of one resource (038.1 measured 13 of 33 such gobs) —
        // so the multiplicity keying by name would lose is published here instead. nil once it is gone.
        m.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "count");
                Gob g = AddonManager.getgob(h.gob);
                if(g == null)
                    return LuaValue.NIL;
                if(h.nat) {
                    int n = LuaGobOverlay.countNative(g, h.key);
                    return (n == 0) ? LuaValue.NIL : LuaValue.valueOf(n);
                }
                LuaGobOverlay store = LuaGobOverlay.on(g);
                return ((store == null) || (store.get(owner, h.key) == null)) ? LuaValue.NIL : LuaValue.valueOf(1);
            }
        });
        // exists() — is it still attached? False once removed, and false once the gob itself is gone.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(rec(owner, handle(self, "exists")) != null);
            }
        });
        // info() — the snapshot escape hatch, for logging: {key, native, res?, kind?}.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "info");
                Object r = rec(owner, h);
                if(r == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("key", LuaValue.valueOf(h.key));
                t.set("native", LuaValue.valueOf(h.nat));
                if(h.nat) {
                    t.set("res", LuaValue.valueOf(h.key));
                    t.set("count", LuaValue.valueOf(LuaGobOverlay.countNative(AddonManager.getgob(h.gob), h.key)));
                } else {
                    LuaGobOverlay.Attach a = (LuaGobOverlay.Attach)r;
                    t.set("count", LuaValue.valueOf(1));
                    t.set("kind", LuaValue.valueOf(a.kind()));
                    t.set("world", LuaValue.valueOf(a.world()));   // 038.2: the 3D world, or the gob's screen point
                    if((a.ent != null) && (a.ent.visualName() != null))
                        t.set("res", LuaValue.valueOf(a.ent.visualName()));
                }
                return t;
            }
        });
        return m;
    }

    /**
     * What backs a handle right now, or {@code null} — the record for one of ours, a marker object for a native
     * one, and {@code null} for either once the overlay (or the gob) is gone. One resolution behind
     * {@code :exists()} and {@code :info()}, so the two can never disagree.
     */
    private static Object rec(Addon owner, LuaOverlay h) {
        Gob g = AddonManager.getgob(h.gob);
        if(g == null)
            return null;
        if(h.nat)
            return LuaGobOverlay.findNative(g, h.key) ? h : null;
        LuaGobOverlay store = LuaGobOverlay.on(g);
        return (store == null) ? null : store.get(owner, h.key);
    }

    /** This addon's record behind a handle, or {@code null} (a native one, a removed one, or a departed gob). */
    private static LuaGobOverlay.Attach mine(Addon owner, LuaOverlay h) {
        Object r = h.nat ? null : rec(owner, h);
        return (r instanceof LuaGobOverlay.Attach) ? (LuaGobOverlay.Attach)r : null;
    }

    /**
     * The live entity behind a WORLD-space overlay, for the composed verbs — {@code null} when the overlay (or its
     * gob) is gone, which makes those verbs clean no-ops. A <b>screen-space</b> or <b>native</b> overlay has no
     * entity and never will, so it is refused naming the kinds: a verb that can never mean anything here is D-072's
     * case, not a silent one.
     */
    private static LuaWorldEntity ent(Addon owner, LuaValue self, String method) {
        LuaOverlay h = handle(self, method);
        if(h.nat)
            throw new LuaError("overlay:" + method + "(): the game's own overlays are READ-ONLY -- '" + h.key
                + "' is one of them (gob:overlay() says which, ov:native())");
        LuaGobOverlay.Attach a = mine(owner, h);
        if(a == null)
            return null;                     // removed, or its gob is gone: nothing to act on, and that is a moment
        if(a.ent == null)
            throw new LuaError("overlay:" + method + "(): that is a SCREEN-space overlay (kind '" + a.kind()
                + "') -- :tint/:alpha/:scale/:rotate/:pos belong to the world-space kinds ('image', 'model',"
                + " 'ghost'); a 'draw' overlay paints itself and a 'text' one carries its own colour");
        return a.ent;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaOverlay handle(LuaValue self, String method) {
        LuaOverlay h = resolve(self);
        if(h == null)
            throw new LuaError("overlay:" + method + "() -- use a COLON call on an Overlay object"
                + " (gob:overlay(key) / gob:overlay())");
        return h;
    }
}
