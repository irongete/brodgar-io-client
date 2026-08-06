package io.brodgar.addon;

import haven.Coord;

import java.util.List;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Shared Java&harr;Lua marshalling for the {@code wdgmsg}/{@code uimsg} argument arrays the two message
 * streams expose to addons. Extracted (Phase 2d) so both the outbound stream
 * ({@code hafen.event():action()}) and the inbound one ({@code hafen.event():message()}) convert arguments
 * the one canonical way (D-013) and cannot drift — the same reasoning as the shared {@link LuaGOut} (2b).
 *
 * <p><b>A {@code nil} argument is a hole, and {@link #luaToArgs} is sized to survive one</b> — see its doc.
 * It matters constantly on the inbound side: a chat line arrives with a null sender, so the identity round
 * trip {@code ev:rewrite(ev:args())} has to mean what it says.
 *
 * <p>The mapping (see {@link #toLua}/{@link #toJava}):
 * <ul>
 *   <li>{@link Coord} &harr; a {@code {x=,y=}} table;</li>
 *   <li>{@code Integer}/{@code Short}/{@code Byte} &rarr; a Lua int; {@code Long}/{@code Double}/
 *       {@code Float} &rarr; a Lua double (the usual {@code >2^53} precision caveat);</li>
 *   <li>{@code String}/{@code Boolean} map directly;</li>
 *   <li>anything else &rarr; an opaque userdata that {@link #toJava} maps back to the <b>identical</b>
 *       Java object, so exotic args (e.g. a clicked gob's click-data) round-trip untouched.</li>
 * </ul>
 * All members are static; the class is not instantiable.
 */
final class LuaMarshal {
    private LuaMarshal() {}

    /** A 1-based Lua snapshot of a Java argument array (each element via {@link #toLua}). */
    static LuaTable argsToLua(Object[] args) {
        LuaTable t = new LuaTable();
        if(args != null) {
            for(int i = 0; i < args.length; i++)
                t.set(i + 1, toLua(args[i]));
        }
        return t;
    }

    /** Convert one message argument to Lua (see the class doc for the mapping). */
    static LuaValue toLua(Object o) {
        if(o == null)
            return LuaValue.NIL;
        if(o instanceof Boolean)
            return LuaValue.valueOf(((Boolean)o).booleanValue());
        if((o instanceof Integer) || (o instanceof Short) || (o instanceof Byte))
            return LuaValue.valueOf(((Number)o).intValue());
        if(o instanceof Number)                       // Long/Double/Float — Lua numbers are doubles
            return LuaValue.valueOf(((Number)o).doubleValue());
        if(o instanceof String)
            return LuaValue.valueOf((String)o);
        if(o instanceof Coord) {
            Coord c = (Coord)o;
            LuaTable t = new LuaTable();
            t.set("x", LuaValue.valueOf(c.x));
            t.set("y", LuaValue.valueOf(c.y));
            return t;
        }
        return LuaValue.userdataOf(o);                // opaque round-trip
    }

    /**
     * Convert a parsed-JSON value ({@link Json#parse} shapes: {@link Map}/{@link List}/{@link String}/
     * {@link Double}/{@link Boolean}/{@code null}) to a Lua value — the one canonical JSON&rarr;Lua
     * marshal (D-013), shared by {@code hafen.store} restore and {@code hafen.json():parse}. Objects
     * become string-keyed tables; arrays become 1-based tables; an integral number within Lua's int
     * range is returned as an int (so {@code {"n":5}} &rarr; {@code 5}, not {@code 5.0}). A JSON
     * {@code null} maps to {@code nil}: inside an object it yields an <b>absent key</b>, inside an array
     * a <b>hole</b> (the index still advances) — {@code nil} cannot be a live table value in Lua, so this
     * is the standard, documented Lua-JSON trade-off (no {@code json.null} sentinel).
     */
    static LuaValue jsonToLua(Object o) {
        return jsonToLua(o, null);
    }

    /**
     * {@link #jsonToLua(Object)} that also rebuilds a <b>Position</b> for {@code owner}: an object carrying
     * exactly {@code gridId} (a string), {@code x} and {@code y} (numbers) is the durable form {@link Json}
     * writes for one, and reading it back as a plain table would make a saved place come out as data that
     * merely looks right. It is a shape test rather than a tag, deliberately: that shape <i>is</i> the wire
     * form of a place, so a document that carries one carries a place, wherever it came from.
     */
    static LuaValue jsonToLua(Object o, Addon owner) {
        if(o == null)
            return LuaValue.NIL;
        if(o instanceof Boolean)
            return LuaValue.valueOf(((Boolean)o).booleanValue());
        if(o instanceof Number) {
            double d = ((Number)o).doubleValue();
            if((d == Math.rint(d)) && (d >= Integer.MIN_VALUE) && (d <= Integer.MAX_VALUE))
                return LuaValue.valueOf((int)d);           // integral → Lua int (clean round-trip / value semantics)
            return LuaValue.valueOf(d);
        }
        if(o instanceof String)
            return LuaValue.valueOf((String)o);
        if(o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>)o;
            LuaValue pos = position(m, owner);
            if(pos != null)
                return pos;
            LuaTable t = new LuaTable();
            for(Map.Entry<String, Object> e : m.entrySet())
                t.set(e.getKey(), jsonToLua(e.getValue(), owner));  // null value → set(k, NIL) = absent key
            return t;
        }
        if(o instanceof List) {
            LuaTable t = new LuaTable();
            int i = 1;
            for(Object e : (List<?>)o)
                t.set(i++, jsonToLua(e, owner));             // null → set(i, NIL) = a hole; index still advances
            return t;
        }
        return LuaValue.NIL;                                  // unknown type → drop
    }

    /** The Position a parsed object describes, or {@code null} when it is an ordinary object. */
    private static LuaValue position(Map<String, Object> m, Addon owner) {
        if((owner == null) || (m.size() != 3))
            return null;
        Object id = m.get("gridId"), x = m.get("x"), y = m.get("y");
        if(!(id instanceof String) || !(x instanceof Number) || !(y instanceof Number))
            return null;
        try {
            return LuaPosition.ofAnchor(owner, Long.parseLong((String)id),
                                        ((Number)x).doubleValue(), ((Number)y).doubleValue());
        } catch(NumberFormatException e) {   // "gridId" that is not an id — an ordinary object after all
            return null;
        }
    }

    /**
     * Convert a Lua argument table (from {@code ev:send}/{@code ev:rewrite}) back to a Java
     * {@code Object[]}. {@code ctx} names the caller for error messages (e.g. {@code "ev:send"}).
     *
     * <p><b>Sized by the highest index, not by {@code #t}</b> (041.2). A {@code null} argument marshals to a
     * Lua {@code nil}, which in a table is a <i>hole</i> — and {@code #t} stops at a hole, so sizing by it
     * silently DROPPED arguments: a chat {@code "msg"} arrives as {@code (nil, line)} (a null sender is how
     * the client knows the line is yours), whose {@code #} is 0, so {@code ev:rewrite(ev:args())} — the
     * identity round trip — would have applied an EMPTY argument list to the widget. Scanning the keys
     * instead makes a leading or middle hole survive, which is what the message actually had.
     *
     * <p><b>What still cannot be expressed is a TRAILING nil</b>, and that is inherent rather than a defect
     * to route around: in Lua {@code {x, nil}} and {@code {x}} are the same table, so a final {@code null}
     * argument is indistinguishable from an absent one. Read {@code ev:args()} and pass it back if you need
     * the exact shape; build a table by hand only for messages you know the arity of.
     */
    static Object[] luaToArgs(LuaValue t, String ctx) {
        if(!t.istable())
            throw new LuaError(ctx + "(args) expects a table");
        int n = argCount(t, ctx);
        Object[] out = new Object[n];
        for(int i = 0; i < n; i++)
            out[i] = toJava(t.get(i + 1), ctx);
        return out;
    }

    /**
     * How many arguments {@code t} describes: the largest positive integer key in it, which is {@code #t}
     * for a hole-free table and more than it for one carrying a {@code nil}. Bounded at {@link #MAXARGS} so
     * a stray key ({@code {[1000000] = 1}}) is a refusal rather than a million-element array — no protocol
     * message in the client comes near it.
     */
    private static int argCount(LuaValue t, String ctx) {
        int n = t.length();
        LuaValue k = LuaValue.NIL;
        while(true) {
            LuaValue next = t.next(k).arg1();
            if(next.isnil())
                break;
            k = next;
            if(k.isint()) {
                int i = k.toint();
                if(i > n)
                    n = i;
            }
        }
        if(n > MAXARGS)
            throw new LuaError(ctx + "(args): " + n + " arguments — no client message has more than "
                + MAXARGS + ", so this is an index typo rather than an argument list");
        return n;
    }

    /** The ceiling on an argument list built from Lua (see {@link #argCount}). */
    private static final int MAXARGS = 256;

    /** Inverse of {@link #toLua}: a {@code {x=,y=}} table &rarr; {@link Coord}, userdata &rarr; its object. */
    static Object toJava(LuaValue v, String ctx) {
        switch(v.type()) {
        case LuaValue.TNIL:
            return null;
        case LuaValue.TBOOLEAN:
            return Boolean.valueOf(v.toboolean());
        case LuaValue.TNUMBER:
            return (v instanceof LuaInteger) ? (Object)Integer.valueOf(v.toint())
                                             : (Object)Double.valueOf(v.todouble());
        case LuaValue.TSTRING:
            return v.tojstring();
        case LuaValue.TUSERDATA:
            return v.touserdata();
        case LuaValue.TTABLE: {
            LuaValue x = v.get("x"), y = v.get("y");
            if(x.isnumber() && y.isnumber())
                return new Coord(x.toint(), y.toint());
            throw new LuaError(ctx + ": a table argument must be a coord {x=,y=}");
        }
        default:
            throw new LuaError(ctx + ": unsupported argument type " + v.typename());
        }
    }
}
