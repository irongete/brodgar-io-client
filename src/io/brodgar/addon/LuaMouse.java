package io.brodgar.addon;

import haven.Coord;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The <b>pointer entity</b> — {@code hafen.ui():mouse()} (spec {@code 041-unified-events} §2.2), replacing a
 * bare {@code {x=, y=}} table read:
 *
 * <pre>
 *   local m = hafen.ui():mouse()
 *   m:x()  m:y()                  -- where the cursor is (was: the {x=, y=} table)
 *   m:over()                      -- the widget under it (was: hafen.ui():at(m.x, m.y))
 *   m:shift() m:ctrl() m:alt()    -- the live modifier keys (NEW — UI.modflags() reached Lua nowhere before)
 *   m:grab()                      -- take the pointer, see {@link LuaGrab}
 * </pre>
 *
 * <p><b>The section's one thing IS the object</b> — the same shape {@code hafen.player()} has (D-046): a
 * per-addon singleton, minted lazily and cached on {@link Addon#mouseObj} so
 * {@code hafen.ui():mouse() == hafen.ui():mouse()}. Unlike {@code Player} it wraps no engine object at all —
 * every verb reads live UI state ({@code UI.mc}, {@code UI.modflags()}) fresh on each call — so there is
 * nothing to go stale and nothing to tear down.
 *
 * <p><b>The old dotted read is retired by construction, not by a table.</b> {@code m.x} now finds the live
 * VERB (a function), never {@code nil} and never a number — the same outcome {@code ev:msg()} already
 * established for {@link LuaEvent} (041.2): no metamethod can tell a dot read from a colon call, so a live
 * member answers a dot read with the function it always was. What is gone is the table shape, not the name.
 *
 * <p><b>{@code :over()} is not absorbed from {@code hafen.ui():at(x, y)}</b> — that verb takes an arbitrary
 * point and stays where it is (spec §2.2); only the cursor case, which every call site used to spell out by
 * hand ({@code hafen.ui():at(m.x, m.y)}), moves onto the pointer that owns it.
 */
final class LuaMouse {
    private LuaMouse() {}

    /** The opaque instance behind a Mouse userdata (facade-safe: no Java object of the engine's crosses). */
    private static final class MouseMark {
        public String toString() { return "Mouse"; }
    }

    /** {@code hafen.ui():mouse()} — the addon's one pointer object, minted lazily and cached like {@code Player}. */
    static LuaValue of(final Addon owner) {
        if(owner.mouseObj != null)
            return owner.mouseObj;
        LuaTable m = new LuaTable();
        // :x() / :y() — the cursor in root coords, in DESIGN PIXELS (058.1): the same space :position(), :size()
        // and hafen.ui():at(x, y) speak, so the pointer can be handed straight to the hit test.
        m.set("x", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.ui;
                return ((u == null) || (u.mc == null)) ? LuaValue.NIL : LuaValue.valueOf(Px.out(u.mc).x);
            }
        });
        m.set("y", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.ui;
                return ((u == null) || (u.mc == null)) ? LuaValue.NIL : LuaValue.valueOf(Px.out(u.mc).y);
            }
        });
        m.set("over", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return over(owner);
            }
        });
        m.set("shift", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((mods() & UI.MOD_SHIFT) != 0);
            }
        });
        m.set("ctrl", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((mods() & UI.MOD_CTRL) != 0);
            }
        });
        m.set("alt", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf((mods() & UI.MOD_META) != 0);   // MOD_META = Alt in this client (UI.setmods)
            }
        });
        // grab() — take the pointer (spec §2.2). Bare: R4 of design/25 cuts the old {move=,up=} config table,
        // since a grab constructs something with a lifetime and no opts table survives on one of those.
        m.set("grab", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("mouse():grab() takes no arguments: the config table is cut — subscribe"
                        + " on the grab it hands back instead (g:on(\"Move\"/\"Up\", fn))");
                return LuaGrab.create(owner);
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.closedIndex("hafen.ui():mouse()", m,
            "the pointer answers :x() :y() :over() :shift() :ctrl() :alt() :grab()"));
        mt.set("__name", LuaValue.valueOf("Mouse"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf("Mouse");
            }
        });
        owner.mouseObj = LuaValue.userdataOf(new MouseMark(), mt);
        return owner.mouseObj;
    }

    /**
     * {@code m:over()} — the deepest widget under the cursor, or {@code nil}. It answers for <b>the point this
     * object reports</b> ({@code m:x()}, {@code m:y()}), not for the raw {@code UI.mc} behind it: the design
     * pixel the API names is put back through {@link Px#in}, exactly as {@code hafen.ui():at(x, y)} does with
     * the very same numbers.
     *
     * <p>That round-trip is deliberate, and it is the one place the design-pixel boundary is not the identity —
     * {@code out(in(n)) == n} always, while {@code in(out(d))} may land a device pixel away, since a device
     * position is not generally a whole number of design pixels. Hit-testing the raw cursor instead would make
     * {@code hafen.ui():at(m:x(), m:y()) == m:over()} true <i>almost</i> always and false on a widget edge,
     * which is worse than being an identity: the two doors are documented as one question.
     */
    private static LuaValue over(Addon owner) {
        UI u = AddonManager.ui;
        if((u == null) || (u.root == null) || (u.mc == null))
            return LuaValue.NIL;
        Widget hit;
        Coord at = Px.in(Px.out(u.mc));
        synchronized(u) { hit = LuaWidget.hitTest(u.root, at); }
        return (hit == null) ? LuaValue.NIL : LuaWidget.of(owner, hit);
    }

    private static int mods() {
        UI u = AddonManager.ui;
        return (u == null) ? 0 : u.modflags();
    }
}
