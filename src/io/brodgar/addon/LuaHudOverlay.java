package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import io.brodgar.addon.AddonManager.HudOverlay;

/**
 * The Lua object behind {@code hafen.ui():overlay()} (039.6) — a painter over the whole HUD, built bare and
 * completed by {@code :onDraw(fn)}.
 *
 * <p><b>Why this section's {@code overlay} MINTS where the other two collect.</b> {@code gob:overlay()} and
 * {@code hafen.map():overlay()} are collections because their members have keys — an overlay key, a display
 * tag — so {@code :get(k)} is a question with an answer. A HUD painter has none: it is anonymous, and a
 * collection of anonymous members offers {@code :list()} and nothing else, which is a handle list wearing a
 * collection's name. So {@code hafen.ui():overlay()} is a <b>builder</b>, like {@code :window()} and
 * {@code :widget()} beside it, and the three things this section builds all end the same way: {@code
 * :destroy()} (R7 — you hold the handle of something you created).
 *
 * <p>Userdata with one shared metatable: the verbs need the addon, and the addon is on the
 * {@link HudOverlay} itself, so nothing has to be captured per owner. It is not interned — each
 * {@code :overlay()} call is a new painter and the object handed back <i>is</i> its identity.
 */
final class LuaHudOverlay {
    private final HudOverlay ov;

    private LuaHudOverlay(HudOverlay ov) {
        this.ov = ov;
    }

    public String toString() {
        return ov.active ? "Overlay()" : "Overlay(destroyed)";
    }

    /** The Lua object for a freshly built overlay. Minted once, at {@code hafen.ui():overlay()}. */
    static LuaValue of(HudOverlay ov) {
        return LuaValue.userdataOf(new LuaHudOverlay(ov), META);
    }

    /** The receiver of a colon call, or a guiding error (a dot call passes the wrong self). */
    private static LuaHudOverlay handle(LuaValue v, String method) {
        if((v != null) && v.isuserdata()) {
            Object o = v.touserdata();
            if(o instanceof LuaHudOverlay)
                return (LuaHudOverlay)o;
        }
        throw new LuaError("overlay:" + method + "() — use a COLON call on the overlay object"
            + " (hafen.ui():overlay():" + method + "(…))");
    }

    private static final LuaValue META = buildMeta();

    private static LuaValue buildMeta() {
        LuaTable m = new LuaTable();
        // onDraw() / onDraw(fn) — arity is the verb. fn(g, w, h) runs every frame with the shared GOut wrapper
        // and the screen size, in ABSOLUTE screen coords. Until one is set the overlay paints nothing.
        m.set("onDraw", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaHudOverlay h = handle(a.arg1(), "onDraw");
                LuaValue fn = Args.written(a, 2, "overlay:onDraw", "fn");
                if(fn == null) {
                    LuaValue cur = h.ov.fn;
                    return (cur == null) ? LuaValue.NIL : cur;
                }
                if(!fn.isfunction())
                    throw new LuaError("overlay:onDraw(fn) expects a function fn(g, w, h), got " + fn.typename());
                h.ov.fn = fn;
                return a.arg1();
            }
        });
        // destroy() — stop painting and drop it from the addon's registry (R7). Inert on one already destroyed:
        // it already happened, which is D-084's rule and the same answer 039.3 gave a removal on a departed gob.
        m.set("destroy", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHudOverlay h = handle(self, "destroy");
                h.ov.active = false;
                h.ov.owner.hudOverlays.remove(h.ov);
                return LuaValue.NIL;
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "exists").ov.active);
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("uioverlay", m));
        mt.set("__name", LuaValue.valueOf("Overlay"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue v) {
                return LuaValue.valueOf(v.isuserdata() ? v.touserdata().toString() : "Overlay(?)");
            }
        });
        return mt;
    }
}
