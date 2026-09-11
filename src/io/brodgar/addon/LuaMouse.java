package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
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
 *   m:over()                      -- the widget under it (was: hafen.ui():hit(m.x, m.y))
 *   m:shift() m:ctrl() m:alt()    -- the live modifier keys (NEW — UI.modflags() reached Lua nowhere before)
 *   m:grab()                      -- take the pointer, see {@link LuaGrab}
 * </pre>
 *
 * <p><b>The section's one thing IS the object</b> — the same shape {@code s:player()} has (D-046): a
 * per-addon singleton, minted lazily and cached on {@link Addon#mouseObj} so
 * {@code hafen.ui():mouse() == hafen.ui():mouse()}. Unlike {@code Player} it wraps no engine object at all —
 * every verb reads live UI state ({@code UI.mc}, {@code UI.modflags()}) fresh on each call — so there is
 * nothing to go stale and nothing to tear down.
 *
 * <p><b>The dotted read is answered by construction, not by a table.</b> {@code m.x} finds the live
 * VERB (a function), never {@code nil} and never a number — the same shape {@link LuaEvent} has: no
 * metamethod can tell a dot read from a colon call, so a live
 * member answers a dot read with the function it always was. What is gone is the table shape, not the name.
 *
 * <p><b>{@code :over()} is not absorbed from {@code hafen.ui():hit(x, y)}</b> — that verb takes an arbitrary
 * point and stays where it is (spec §2.2); only the cursor case, which every call site used to spell out by
 * hand ({@code hafen.ui():hit(m.x, m.y)}), moves onto the pointer that owns it.
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
        // and hafen.ui():hit(x, y) speak, so the pointer can be handed straight to the hit test.
        m.set("x", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.screen();
                return ((u == null) || (u.mc == null)) ? LuaValue.NIL : LuaValue.valueOf(Px.out(u.mc).x);
            }
        });
        m.set("y", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.screen();
                return ((u == null) || (u.mc == null)) ? LuaValue.NIL : LuaValue.valueOf(Px.out(u.mc).y);
            }
        });
        m.set("over", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return over(owner);
            }
        });
        // :pick() — the OBJECT under the pointer, where :over() is the WIDGET under it. The two halves of one
        // question, and the two words the industry already uses for them: a HIT TEST walks a widget tree
        // (hafen.ui():hit, :over), PICKING resolves a 3D pixel to the thing drawn there. It is the client's
        // own pick, the one a right-click goes through, so it can never disagree with what a click reaches.
        //   nil while nobody holds a PickChanged subscription: the pass is not run at all then, and naming an
        // object out of a pass nobody armed would be naming a stale one.
        m.set("pick", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.screen();
                if(u == null)
                    return LuaValue.NIL;
                long id = AddonManager.pickGobId(u);
                return (id < 0) ? LuaValue.NIL : LuaGob.of(owner, AddonManager.userOf(u), id);
            }
        });
        // :ground() — THE SAME PASS'S OTHER HALF. MapView.Hittest resolves the ground point and the object
        // together (checkmapclick + checkgobclick, one submission), so this costs nothing beyond :pick() and
        // -- the part that matters -- the two come out of the SAME instant: the tile under the pointer can
        // never be one frame's while the object over it is another's. A Position, which is what a place is
        // everywhere else in this API, so it goes straight into s:world():tile(p), :height(p), :grid():at(p).
        //   nil where there is no ground: the pointer off the map view, off the world's edge, and while
        // nobody holds a PickChanged subscription, since the pass is not run at all then.
        m.set("ground", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                UI u = AddonManager.screen();
                if(u == null)
                    return LuaValue.NIL;
                Coord2d g = AddonManager.pickGround(u);
                return (g == null) ? LuaValue.NIL
                    : LuaPosition.ofWorld(owner, AddonManager.userOf(u), g.x, g.y);
            }
        });
        // :on(key, fn) — the ordinary subscription door (D-125 shape: a closed key set, listed in the
        // refusal). HOLDING ONE IS THE OPT-IN: a pick is a render pass and a GPU readback, which is why the
        // client itself only picks on a click, so PointerPick runs nothing until somebody is listening and
        // stops again on the last sub:off().
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue keyArg = Args.required(a, 2, "mouse():on", "key");
                LuaValue fnArg = Args.required(a, 3, "mouse():on", "fn");
                if((keyArg.type() != LuaValue.TSTRING) || !fnArg.isfunction())   // the TYPE: 42 answers isstring()
                    throw new LuaError("mouse():on(key, fn) expects (string, function)");
                String key = keyArg.tojstring();
                if(!PointerPick.KEY.equals(key))
                    throw new LuaError("mouse():on(key, fn): the pointer has no event '" + key
                        + "' — it has: " + PointerPick.KEY);
                return owner.pickSubs.on(key, fnArg);
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
        // cursor() / cursor(name) / cursor(nil) — the pointer's PICTURE. Arity is the verb, and the explicit
        // nil has a meaning here rather than raising: putting a forced cursor back is the other half of
        // forcing one, and it is the half a mode that ends has to be able to say.
        //   There is ONE pointer, so there is one override and the last writer holds it. A read answers what
        // YOU forced -- nil while you are forcing nothing, whoever else may be -- because the only thing a
        // caller can act on is its own.
        //   Unprotected: the picture on the pointer is drawing, and changes nothing the server or another
        // addon owns. Your own teardown drops it, so a mode left open by a :reload does not strand it.
        m.set("cursor", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(!Args.passed(a, 2)) {
                    String nm = UiApi.cursorOf(owner);
                    return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
                }
                LuaValue v = a.arg(2);
                if(v.isnil()) {
                    UiApi.setCursor(owner, null);
                    return a.arg1();
                }
                // The TYPE, through the one door that asks it. isstring() is LuaJ's coercing predicate and
                // a number is a string by it, so mouse():cursor(7) scanned as the name "7", resolved to
                // nothing and put the pointer back with a line in the log — where this page promises a
                // refusal naming both spellings.
                Args.str(v, "mouse():cursor", "name",
                         "one of the game's own under gfx/hud/curs (\"arw\", \"hand\", \"study\", \"dig\","
                         + " …), or a resource path with a slash in it; mouse():cursor(nil) puts the pointer"
                         + " back");
                UiApi.setCursor(owner, v.tojstring());
                return a.arg1();
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("hafen.ui():mouse()", m,
            "the pointer"));
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
     * pixel the API names is put back through {@link Px#in}, exactly as {@code hafen.ui():hit(x, y)} does with
     * the very same numbers.
     *
     * <p>That round-trip is deliberate, and it is the one place the design-pixel boundary is not the identity —
     * {@code out(in(n)) == n} always, while {@code in(out(d))} may land a device pixel away, since a device
     * position is not generally a whole number of design pixels. Hit-testing the raw cursor instead would make
     * {@code hafen.ui():hit(m:x(), m:y()) == m:over()} true <i>almost</i> always and false on a widget edge,
     * which is worse than being an identity: the two doors are documented as one question.
     */
    private static LuaValue over(Addon owner) {
        UI u = AddonManager.screen();
        if((u == null) || (u.mc == null))
            return LuaValue.NIL;
        // BOTH trees, the addon layer first, exactly as hafen.ui():hit(x, y) walks them (audit2 B05) — the
        // two doors are documented as one question, so they answer out of the same walk.
        Coord at = Px.in(Px.out(u.mc));
        Widget hit = UiApi.deepest(at);
        return (hit == null) ? LuaValue.NIL : LuaWidget.of(owner, hit);
    }

    private static int mods() {
        UI u = AddonManager.screen();
        return (u == null) ? 0 : u.modflags();
    }
}
