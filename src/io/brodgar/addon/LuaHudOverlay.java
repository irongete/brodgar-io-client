package io.brodgar.addon;

import java.util.ArrayList;
import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import io.brodgar.addon.AddonManager.HudOverlay;

/**
 * The Lua object behind one member of {@code hafen.ui():overlay()} — a <b>keyed</b> painter over the whole
 * HUD, attached bare and completed by {@code :draw(fn)}.
 *
 * <p><b>{@code overlay} means one thing: keyed decorations bound to a thing.</b> This section's is bound to
 * the screen, {@code gob:overlay()}'s to a game object, and the two answer the <b>same</b> vocabulary —
 * {@code :add(key)}, {@code :get(key)}, {@code :remove(key)}, {@code :list/count/find(filter)}, with
 * {@code :draw(fn)} on the member. A reader who learned one already knows the other, which is the whole
 * reason the word was kept rather than replaced.
 *
 * <p><b>{@code :list()} is the DRAW ORDER.</b> The source below walks {@link Addon#hudOverlays} as it
 * stands, and that list is exactly what {@link UiApi#paintHudOverlays} iterates — so the census and the
 * painting agree by construction rather than by a rule someone has to keep. A {@code :get(key)} is a scan of
 * the same list, which is right for a set this size and is what keeps insertion order the only order.
 *
 * <p>Userdata over the {@link HudOverlay} record, with one shared metatable: the verbs need no addon, since
 * the addon is on the record. The handle is cached <b>on the record</b>, so {@code :get(k)} twice is the
 * same value and {@code ==} is the identity test; {@code :add(k)} over a key that already names one ends the
 * old record, so the key survives and the thing under it is a different one.
 */
final class LuaHudOverlay {
    private final HudOverlay ov;

    private LuaHudOverlay(HudOverlay ov) {
        this.ov = ov;
    }

    public String toString() {
        return "Overlay(\"" + ov.key + "\")" + (ov.active ? "" : " removed");
    }

    /** The interned Lua object for one record — cached on the record, so two lookups are the same value. */
    static LuaValue of(HudOverlay ov) {
        LuaValue v = ov.lua;
        if(v == null) {
            v = LuaValue.userdataOf(new LuaHudOverlay(ov), META);
            ov.lua = v;
        }
        return v;
    }

    /** The {@code LuaHudOverlay} behind a Lua value, or {@code null} for anything that is not one. */
    private static LuaHudOverlay resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaHudOverlay) ? (LuaHudOverlay)o : null;
    }

    /** The receiver of a colon call, or a guiding error (a dot call passes the wrong self). */
    private static LuaHudOverlay handle(LuaValue v, String method) {
        LuaHudOverlay h = resolve(v);
        if(h == null)
            throw new LuaError("overlay:" + method + "() — use a COLON call on the overlay object"
                + " (hafen.ui():overlay():get(key), hafen.ui():overlay():add(key))");
        return h;
    }

    // ---- the collection --------------------------------------------------------------------------

    /**
     * {@code hafen.ui():overlay()} — this addon's HUD painters, keyed. A <b>view</b>: it reads
     * {@link Addon#hudOverlays} on every call and holds nothing between them, so a teardown that clears that
     * list empties the collection with it.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.ui():overlay()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(HudOverlay o : owner.hudOverlays)
                    out.add(of(o));
                return out;
            }

            public String needle(LuaValue member) {
                LuaHudOverlay h = resolve(member);
                return (h == null) ? null : h.ov.key;
            }

            /** These have a name — their key — so a string filter is a substring test over it. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                HudOverlay o = find(owner, keyArg(key, "hafen.ui():overlay():get"));
                return (o == null) ? LuaValue.NIL : of(o);
            }

            public boolean creatable() {
                return true;
            }

            // add(key) — attach a BARE painter and hand it back, for :draw(fn) to say what it paints. The
            // same key twice is a REPLACE: the old record stops painting and the new one takes its place at
            // the END of the draw order, which is where a painter you just installed belongs.
            public LuaValue addMember(Varargs a) {
                String k = keyArg(a.arg(2),
                                  "hafen.ui():overlay():add");
                drop(owner, k);
                HudOverlay o = new HudOverlay(owner, k);
                owner.hudOverlays.add(o);
                return of(o);
            }

            public boolean destroyable() {
                return true;
            }

            // remove(keyOrOverlay) — stop painting and drop it from the addon's registry. Removing what is
            // not there is INERT: a key you never attached, or one already removed, is a moment and not a
            // mistake — the same answer gob:overlay():remove gives for a departed gob.
            public void removeMember(LuaValue x) {
                LuaHudOverlay h = resolve(x);
                drop(owner, (h != null) ? h.ov.key : keyArg(x, "hafen.ui():overlay():remove"));
            }

            /** The key is YOUR name for this painter. */
            public String keyName() {
                return "key";
            }
        }, null);
    }

    /** The record under {@code key} in this addon's list, or {@code null}. A scan: the list is short. */
    private static HudOverlay find(Addon owner, String key) {
        for(HudOverlay o : owner.hudOverlays) {
            if(o.key.equals(key))
                return o;
        }
        return null;
    }

    /** End the record under {@code key}, if there is one. Inert otherwise. */
    private static void drop(Addon owner, String key) {
        HudOverlay o = find(owner, key);
        if(o == null)
            return;
        o.active = false;
        owner.hudOverlays.remove(o);
    }

    /** A key argument: a string, and yours — keys are per addon, so two addons' {@code "hud"} never collide. */
    private static String keyArg(LuaValue kv, String verb) {
        if(kv.type() != LuaValue.TSTRING)
            throw new LuaError(verb + "(key): the key must be a string — it is YOUR name for this painter,"
                + " and keys are per addon");
        return kv.tojstring();
    }

    // ---- the member's metatable ------------------------------------------------------------------

    private static final LuaValue META = buildMeta();

    private static LuaValue buildMeta() {
        LuaTable m = new LuaTable();
        // key() — what it answers to. From the handle alone, so it still reads after the painter is removed.
        m.set("key", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "key").ov.key);
            }
        });
        // draw() / draw(fn) — arity is the verb. fn(g, w, h) runs every frame with the shared GOut wrapper
        // and the screen size, in ABSOLUTE screen coords. Until one is set the painter paints nothing, which
        // is the same "incomplete draws nothing" rule the widget builder gets from not being in the tree.
        m.set("draw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaHudOverlay h = handle(a.arg1(), "draw");
                LuaValue fn = Args.written(a, 2, "overlay:draw", "fn");
                if(fn == null) {
                    LuaValue cur = h.ov.fn;
                    return (cur == null) ? LuaValue.NIL : cur;
                }
                if(!fn.isfunction())
                    throw new LuaError("overlay:draw(fn) expects a function fn(g, w, h), got " + fn.typename());
                h.ov.fn = fn;
                return a.arg1();
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "exists").ov.active);
            }
        });
        // info() — the snapshot every live interned object in this API answers with (audit2 B15). A HUD
        // painter is one: not a builder, not a snapshot already, and not a carrier of an ending — so it owed
        // one, and its widget and gob siblings both had theirs. `drawn` is the half `exists` cannot say: a
        // painter that is registered but has not been given its function paints nothing.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                HudOverlay o = handle(self, "info").ov;
                LuaTable t = new LuaTable();
                t.set("key", LuaValue.valueOf(o.key));
                t.set("exists", LuaValue.valueOf(o.active));
                t.set("drawn", LuaValue.valueOf(o.active && (o.fn != null)));
                return t;
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("uioverlay", m,
            "one HUD painter",
            "hafen.ui():overlay():remove(key) ends it"));
        mt.set("__name", LuaValue.valueOf("Overlay"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                return LuaValue.valueOf(v.isuserdata() ? v.touserdata().toString() : "Overlay(?)");
            }
        });
        return mt;
    }
}
