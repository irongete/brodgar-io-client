package io.brodgar.addon;

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
 * An <b>OverlayToggle object</b> — one of the client's own display switches for claims, village claims and
 * provinces ({@code hafen.map():display():get(tag)}). Spec {@code 039-uniform-api} §2.2, task 039.4.
 *
 * <p><b>It exists because a hold is not a boolean, and the old shape said it was.</b> {@code
 * hafen.map.overlay(tag, on)} read as a property write — arity is the verb, so {@code (tag)} looked like the
 * read of what {@code (tag, true)} wrote. It never was: what the engine keeps is a <b>ref count</b> shared
 * with the user's own checkbox and with the server's claim flash, so an addon can take a hold and give it
 * back, and can never turn an overlay <i>off</i>. Two different questions were being spelled as one verb —
 * <i>is it displayed</i> and <i>do I hold it</i> — which is why the honest shape is three verbs on the thing:
 * {@link #methods :shown()}, {@code :hold()} and {@code :release()}.
 *
 * <p><b>The hold is idempotent by arithmetic, not by politeness.</b> Taking it twice is taking it once,
 * because a release is a single {@code -1} and a second {@code +1} would leave the count standing forever —
 * an overlay drawn with no owner. A release with nothing held is inert rather than someone else's {@code -1}.
 * A {@code :reload}, a disable or a logout releases every hold exactly once ({@link MapApi#teardownOverlays}).
 *
 * <p><b>The tag space here is CLOSED</b>, deliberately unlike the recorded masks' ({@link LuaMask}): the
 * client owns exactly these four switches, so an unknown tag is refused naming them rather than read as a
 * mask a grid simply does not carry. A typo that silently does nothing forever is the one failure here that
 * nothing else would ever report.
 *
 * <p><b>Interned on the tag</b>, per addon: the toggle is a name for a switch that outlives every
 * {@code MapView} the session builds, and the hold record lives on the {@link Addon} rather than here, so a
 * stashed handle keeps working across a relog exactly as the switch does.
 */
public final class LuaOverlayToggle {
    /** The overlay tag ({@code "cplot"}, {@code "vlg"}, {@code "prov"}, {@code "realm"}) — the identity. */
    public final String tag;

    private LuaOverlayToggle(String tag) {
        this.tag = tag;
    }

    /** {@code tostring(t)}: {@code OverlayToggle(<tag>)}. */
    public String toString() {
        return "OverlayToggle(" + tag + ")";
    }

    /** An interned OverlayToggle for {@code tag} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String tag) {
        return owner.overlayToggles.of(tag);
    }

    /** The {@code LuaOverlayToggle} behind a Lua value, or {@code null} for anything that is not one. */
    static LuaOverlayToggle resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOverlayToggle) ? (LuaOverlayToggle)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's OverlayToggle cache and metatable ({@link Addon#overlayToggles}); the {@link LuaMask} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code tag} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String tag) {
            drain();
            Ref r = live.get(tag);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(tag);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOverlayToggle(tag), meta());
            live.put(tag, new Ref(v, tag, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref tr = (Ref)r;
                if(live.get(tr.key) == tr)
                    live.remove(tr.key);
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

    // ---- the OverlayToggle metatable ---------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("toggle", methods(owner),
            "a map display switch",
            ":hold() and :release() take the hold and give it back"));
        mt.set("__name", LuaValue.valueOf("OverlayToggle"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOverlayToggle h = resolve(self);
                return LuaValue.valueOf((h == null) ? "OverlayToggle(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // tag() — the engine's own tag for this switch; answers from the handle alone.
        m.set("tag", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "tag").tag);
            }
        });
        // where() — "world" (drawn on the ground in the 3D scene) or "map" (drawn in the map window).
        m.set("where", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(MapApi.toggle(handle(self, "where").tag)[1]);
            }
        });
        // what() — a sentence naming what this switch draws, for a settings UI that lists them.
        m.set("what", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(MapApi.toggle(handle(self, "what").tag)[2]);
            }
        });
        // shown() — is this overlay displayed right now, BY ANYONE? The client's own answer, never "do I
        // hold it": the user's checkbox and the server hold it too. nil before that side of the HUD is up.
        m.set("shown", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Boolean d = MapApi.displayed(handle(self, "shown").tag);
                return (d == null) ? LuaValue.NIL : LuaValue.valueOf(d.booleanValue());
            }
        });
        // held() — do YOU hold it? The other question, and the only one that is yours to answer.
        m.set("held", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(MapApi.held(owner, handle(self, "held").tag));
            }
        });
        // hold() — ask for this overlay to be drawn, and keep asking until you release. Idempotent, and self
        // so it chains. Inert before the side that owns the tag is up: there is nothing to hold yet.
        m.set("hold", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapApi.take(owner, handle(self, "hold").tag);
                return self;
            }
        });
        // release() — stop asking. Never "turn it off": if the user's checkbox wants it, it stays. A release
        // with nothing held is inert, not someone else's -1.
        m.set("release", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapApi.release(owner, handle(self, "release").tag);
                return self;
            }
        });
        // info() — the snapshot escape hatch: {tag, where, what, shown?, held}.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String tag = handle(self, "info").tag;
                String[] row = MapApi.toggle(tag);
                LuaTable t = new LuaTable();
                t.set("tag", LuaValue.valueOf(tag));
                t.set("where", LuaValue.valueOf(row[1]));
                t.set("what", LuaValue.valueOf(row[2]));
                Boolean d = MapApi.displayed(tag);
                if(d != null)
                    t.set("shown", LuaValue.valueOf(d.booleanValue()));
                t.set("held", LuaValue.valueOf(MapApi.held(owner, tag)));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot call passes the wrong self). */
    private static LuaOverlayToggle handle(LuaValue self, String method) {
        LuaOverlayToggle h = resolve(self);
        if(h == null)
            throw new LuaError("toggle:" + method + "() — use a COLON call on an overlay toggle"
                + " (hafen.map():display():get(tag), hafen.map():display():list()[n])");
        return h;
    }
}
