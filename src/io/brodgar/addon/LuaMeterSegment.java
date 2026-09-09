package io.brodgar.addon;

import haven.IMeter;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

/**
 * One <b>segment</b> of a HUD meter (091, A-075) &mdash; a fill fraction and a colour, at a position in the
 * bar its meter draws.
 *
 * <p><b>Why it is an object.</b> The bar was a plain array of plain tables, and beside it sat
 * {@code meter:value()} and {@code meter:color()}, which read <i>segment one</i> under a whole-bar name. A
 * user writing a health readout got the right number on every meter the client ships and the wrong one the
 * first time a server published a split bar &mdash; silently. Both verbs are gone;
 * {@code meter:segment():list()[1]:value()} is the fill, and it says which segment it is.
 *
 * <p><b>It re-resolves.</b> A Segment holds its meter and its position, never a value, so one kept across
 * ticks reads what the bar says now. It is not interned: two reads of the same segment are two Lua values,
 * because there is nothing here to address by &mdash; a segment has no key, and {@code :remove} is not a
 * verb any meter carries. Named {@code LuaMeterSegment} because {@code LuaSegment} is the map's.
 *
 * <p><b>Its position is a position in {@code meter:segment():list()}</b>, which is what
 * {@link LuaMeter#bar} makes it: a meter's raw slots may be empty and an empty slot is not a segment, so
 * the bar is read compacted and the place a Segment holds counts the segments that are there.
 */
final class LuaMeterSegment {
    /** The meter this segment is one of. */
    private final IMeter wdg;
    /** Its 0-based place in {@link LuaMeter#bar}; {@code :index()} answers the 1-based position (090). */
    private final int i;

    private LuaMeterSegment(IMeter wdg, int i) {
        this.wdg = wdg;
        this.i = i;
    }

    /** {@code tostring(seg)}: {@code Segment(2)}. */
    public String toString() {
        return "Segment(" + (i + 1) + ")";
    }

    static LuaValue of(Addon owner, IMeter wdg, int i) {
        return LuaValue.userdataOf(new LuaMeterSegment(wdg, i), meta(owner));
    }

    static LuaMeterSegment resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMeterSegment) ? (LuaMeterSegment)o : null;
    }

    private static LuaMeterSegment handle(LuaValue self, String method) {
        LuaMeterSegment h = resolve(self);
        if(h == null)
            throw new LuaError("segment:" + method + "() — use a COLON call on a Segment"
                + " (session:meter():list()[n]:segment():list()[i])");
        return h;
    }

    private static LuaValue meta(Addon owner) {
        if(owner.meterSegMeta != null)
            return owner.meterSegMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("segment", methods(),
            "one segment of a meter's bar"));
        mt.set("__name", LuaValue.valueOf("Segment"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMeterSegment h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Segment(?)" : h.toString());
            }
        });
        owner.meterSegMeta = mt;
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // index() — its 1-based position in meter:segment():list(), the base A-071 made the rule.
        m.set("index", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "index").i + 1);
            }
        });
        // value() — this segment's fill, 0..1. nil once the segment is gone from the bar.
        m.set("value", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMeterSegment h = handle(self, "value");
                haven.LayerMeter.Meter s = LuaMeter.segmentAt(h.wdg, h.i);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s.a);
            }
        });
        // color() — this segment's colour, keyed {r,g,b,a} 0..255. The server changes it on its own.
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMeterSegment h = handle(self, "color");
                haven.LayerMeter.Meter s = LuaMeter.segmentAt(h.wdg, h.i);
                return ((s == null) || (s.c == null)) ? LuaValue.NIL : AddonManager.color(s.c);
            }
        });
        // info() — the one SNAPSHOT escape hatch.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMeterSegment h = handle(self, "info");
                haven.LayerMeter.Meter s = LuaMeter.segmentAt(h.wdg, h.i);
                if(s == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("index", LuaValue.valueOf(h.i + 1));
                t.set("value", LuaValue.valueOf(s.a));
                if(s.c != null)
                    t.set("color", AddonManager.color(s.c));
                return t;
            }
        });
        return m;
    }
}
