package io.brodgar.addon;

import haven.Gob;

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
        // res() — the resource behind it: the key itself for a native one, nil for ours (a screen-space
        // overlay draws Lua, not a .res; the world-space kinds arrive in 038.2).
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlay h = handle(self, "res");
                return h.nat ? LuaValue.valueOf(h.key) : LuaValue.NIL;
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
                    t.set("count", LuaValue.valueOf(1));
                    t.set("kind", LuaValue.valueOf(((LuaGobOverlay.Attach)r).kind()));
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

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaOverlay handle(LuaValue self, String method) {
        LuaOverlay h = resolve(self);
        if(h == null)
            throw new LuaError("overlay:" + method + "() -- use a COLON call on an Overlay object"
                + " (gob:overlay(key) / gob:overlay())");
        return h;
    }
}
