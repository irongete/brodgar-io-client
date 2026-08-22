package io.brodgar.addon;

import haven.BAttrWnd;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * The <b>hunger meter</b> of one character (091, A-077) &mdash; how full it is, what the client calls that,
 * and what food is worth at it.
 *
 * <p>Its three reads sat flat on {@code food} as {@code :hunger()}, {@code :label()} and
 * {@code :efficacy()}, while {@code food:info()} nested them under {@code hunger}. One fact, two shapes,
 * disagreeing about their own structure. Now {@code food:hunger():level()}, {@code :label()} and
 * {@code :efficacy()} mirror the snapshot.
 *
 * <p>It re-resolves: the Hunger holds the tab widget, never a number.
 */
final class LuaHunger {
    private final BAttrWnd wdg;

    private LuaHunger(BAttrWnd wdg) {
        this.wdg = wdg;
    }

    public String toString() {
        return "Hunger()";
    }

    static LuaValue of(Addon owner, BAttrWnd wdg) {
        return (wdg == null) ? LuaValue.NIL : LuaValue.userdataOf(new LuaHunger(wdg), meta(owner));
    }

    static LuaHunger resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaHunger) ? (LuaHunger)o : null;
    }

    private static LuaHunger handle(LuaValue self, String method) {
        LuaHunger h = resolve(self);
        if(h == null)
            throw new LuaError("hunger:" + method + "() — use a COLON call on the Hunger"
                + " session:char():food():hunger() hands back");
        return h;
    }

    private static LuaValue meta(Addon owner) {
        if(owner.hungerMeta != null)
            return owner.hungerMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("hunger", methods(),
            "the hunger meter answers :level() :label() :efficacy() :exists() and :info()"));
        mt.set("__name", LuaValue.valueOf("Hunger"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaHunger h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Hunger(?)" : h.toString());
            }
        });
        owner.hungerMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // level() — how full the hunger meter is.
        m.set("level", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = LuaFood.glut(handle(self, "level").wdg);
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.glut);
            }
        });
        // label() — what the client calls that level ("Well fed"), as it paints it.
        m.set("label", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = LuaFood.glut(handle(self, "label").wdg);
                return ((g == null) || (g.lbl == null)) ? LuaValue.NIL : LuaValue.valueOf(g.lbl);
            }
        });
        // efficacy() — how much food is worth at this hunger: the multiplier on what you eat next.
        m.set("efficacy", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = LuaFood.glut(handle(self, "efficacy").wdg);
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.gmod);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(LuaFood.glut(handle(self, "exists").wdg) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch, the same shape food:info().hunger carries.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BAttrWnd.GlutMeter g = LuaFood.glut(handle(self, "info").wdg);
                if(g == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("level", LuaValue.valueOf(g.glut));
                if(g.lbl != null)
                    t.set("label", LuaValue.valueOf(g.lbl));
                t.set("efficacy", LuaValue.valueOf(g.gmod));
                return t;
            }
        });
        return m;
    }
}
