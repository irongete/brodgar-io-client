package io.brodgar.addon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * Minimal, dependency-free JSON <b>reader + writer</b> for the addon layer (D-016/D-013). The reader
 * ({@link #parse}) backs manifests, saved variables, and {@code hafen.json():parse}; the writer
 * ({@link #write}) backs the {@code :lua} REPL echo, {@code hafen.store} persistence, and
 * {@code hafen.json():encode}. Reader and writer live together so there is <b>one canonical serializer</b>
 * (no drift) — the shared-{@code LuaGOut}/{@code LuaMarshal} pattern. The writer
 * moved here from {@code AddonManager} in task N1 (spec {@code 19-data-and-network.md} §2.3).
 *
 * <p><b>Reader.</b> {@link #parse} returns plain Java values: {@link Map} (object, insertion-ordered),
 * {@link List} (array), {@link String}, {@link Double} (any number), {@link Boolean}, or {@code null}.
 * Malformed input throws a {@link RuntimeException} with an offset. Because the grammar is recursive
 * (object&rarr;value&rarr;object&hellip;), a hostile deeply-nested document could {@code StackOverflow};
 * a <b>max-depth</b> guard ({@link #DEFAULT_MAX_DEPTH}, {@code -Dhaven.addon.json.maxdepth}) fails with a
 * clear error first ({@link Json.parse(String,int)}). The <b>input-length</b> cap
 * ({@code hafen.json.MAX_INPUT}, {@code -Dhaven.addon.json.maxlen}) is enforced by the {@code hafen.json}
 * bridge before parsing.
 *
 * <p><b>Writer.</b> {@link #write(LuaValue)} is <i>forgiving</i> (a function/userdata/thread &rarr; a
 * quoted {@code tostring}, a cycle &rarr; {@code "<cycle>"}, a table past the depth cap &rarr;
 * {@code "<too deep>"}) — the REPL/store behaviour; {@link
 * #write(LuaValue,boolean) write(v,true)} is <i>strict</i> — those cases throw a {@link LuaError} so the
 * public {@code hafen.json():encode} only ever yields valid JSON. The writer answers to the <b>same</b>
 * {@link #DEFAULT_MAX_DEPTH} the reader does: nesting is recursive on this side too, so an acyclic but very
 * deep table would {@code StackOverflow} the writer exactly as a deep document would the reader.
 */
public final class Json {
    /** Input-length cap for {@code hafen.json():parse} (bytes of the string), {@code -Dhaven.addon.json.maxlen}. */
    public static final int MAX_INPUT = (int)propLong("haven.addon.json.maxlen", 8L * 1024 * 1024);
    /**
     * Nesting-depth cap, one cap in both directions: {@code hafen.json():parse} and the default
     * {@link #parse(String)} read no deeper, and {@link #write(LuaValue,boolean)} writes no deeper.
     */
    public static final int DEFAULT_MAX_DEPTH = (int)propLong("haven.addon.json.maxdepth", 256L);

    private final String s;
    private final int maxDepth;
    private int i;
    private int depth;

    private Json(String s, int maxDepth) {
        this.s = s;
        this.maxDepth = maxDepth;
    }

    /** Parse with the default depth cap ({@link #DEFAULT_MAX_DEPTH}) — used by manifests/saved vars. */
    public static Object parse(String text) {
        return parse(text, DEFAULT_MAX_DEPTH);
    }

    /** Parse, failing with a clear error past {@code maxDepth} levels of nesting (hostile-input guard). */
    public static Object parse(String text, int maxDepth) {
        Json j = new Json(text, maxDepth);
        j.ws();
        Object v = j.value();
        j.ws();
        if(j.i < j.s.length())
            throw j.err("unexpected trailing content");
        return v;
    }

    private Object value() {
        char c = peek();
        switch(c) {
        case '{': return object();
        case '[': return array();
        case '"': return string();
        case 't': return literal("true", Boolean.TRUE);
        case 'f': return literal("false", Boolean.FALSE);
        case 'n': return literal("null", null);
        default:
            if((c == '-') || ((c >= '0') && (c <= '9')))
                return number();
            throw err("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        if(++depth > maxDepth) throw err("nesting too deep (> " + maxDepth + ")");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();                                  // consume '{'
        if(peek() == '}') { i++; depth--; return m; }
        while(true) {
            ws();
            if(peek() != '"') throw err("expected string key");
            String k = string();
            ws();
            if(peek() != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value());
            ws();
            char c = peek();
            if(c == ',') { i++; continue; }
            if(c == '}') { i++; depth--; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        if(++depth > maxDepth) throw err("nesting too deep (> " + maxDepth + ")");
        List<Object> a = new ArrayList<Object>();
        i++; ws();                                  // consume '['
        if(peek() == ']') { i++; depth--; return a; }
        while(true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if(c == ',') { i++; continue; }
            if(c == ']') { i++; depth--; return a; }
            throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;                                        // consume opening quote
        while(true) {
            if(i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if(c == '"') return b.toString();
            if(c == '\\') {
                if(i >= s.length()) throw err("unterminated escape");
                char e = s.charAt(i++);
                switch(e) {
                case '"':  b.append('"');  break;
                case '\\': b.append('\\'); break;
                case '/':  b.append('/');  break;
                case 'b':  b.append('\b'); break;
                case 'f':  b.append('\f'); break;
                case 'n':  b.append('\n'); break;
                case 'r':  b.append('\r'); break;
                case 't':  b.append('\t'); break;
                case 'u':
                    if(i + 4 > s.length()) throw err("bad \\u escape");
                    b.append((char)Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("bad escape '\\" + e + "'");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        if(peek() == '-') i++;
        while((i < s.length()) && ("0123456789+-.eE".indexOf(s.charAt(i)) >= 0))
            i++;
        try {
            return Double.valueOf(Double.parseDouble(s.substring(start, i)));
        } catch(NumberFormatException e) {
            throw err("bad number");
        }
    }

    private Object literal(String word, Object val) {
        if(!s.regionMatches(i, word, 0, word.length()))
            throw err("expected '" + word + "'");
        i += word.length();
        return val;
    }

    private char peek() {
        if(i >= s.length()) throw err("unexpected end of input");
        return s.charAt(i);
    }

    private void ws() {
        while(i < s.length()) {
            char c = s.charAt(i);
            if((c == ' ') || (c == '\t') || (c == '\n') || (c == '\r'))
                i++;
            else
                break;
        }
    }

    private RuntimeException err(String msg) {
        return new RuntimeException("JSON: " + msg + " at offset " + i);
    }

    private static long propLong(String name, long def) {
        try {
            String v = haven.Utils.getprop(name, null);
            return (v == null) ? def : Long.parseLong(v.trim());
        } catch(RuntimeException e) {
            return def;
        }
    }

    // -------------------------------------------------- writer (N1/D-013)

    /** Serialize a Lua value to compact single-line JSON, <b>forgiving</b> (REPL/store echo). */
    public static String write(LuaValue v) {
        return write(v, false);
    }

    /**
     * Serialize a Lua value to compact single-line JSON. When {@code strict}, a non-serializable value
     * (function/userdata/thread), a reference cycle, a table nested past {@link #DEFAULT_MAX_DEPTH}, or a
     * non-finite number throws a {@link LuaError} (so {@code hafen.json():encode} only emits valid JSON);
     * when not strict, those degrade to a quoted {@code tostring} / {@code "<cycle>"} / {@code "<too deep>"}
     * / {@code null} (the REPL's copy-friendly echo).
     */
    public static String write(LuaValue v, boolean strict) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LuaValue, Boolean>()), strict, 0);
        return sb.toString();
    }

    /** {@code depth} is the number of tables already open around {@code v} — 0 at the root. */
    private static void write(LuaValue v, StringBuilder sb, java.util.Set<LuaValue> seen, boolean strict, int depth) {
        LuaPosition pos = LuaPosition.resolve(v);
        if(pos != null) {
            writePos(pos, sb, strict);
        } else if(v.isnil()) {
            sb.append("null");
        } else if(v.isboolean()) {
            sb.append(v.toboolean() ? "true" : "false");
        } else if(v instanceof LuaNumber) {
            double d = v.todouble();
            if(!Double.isFinite(d)) {
                if(strict)
                    throw new LuaError("hafen.json():encode: cannot encode a non-finite number");
                sb.append("null");                       // JSON has no NaN/Infinity
            } else {
                writeNum(d, sb);
            }
        } else if(v instanceof LuaString) {
            writeStr(v.tojstring(), sb);
        } else if(v instanceof LuaTable) {
            writeTab((LuaTable)v, sb, seen, strict, depth);
        } else if(strict) {
            throw new LuaError("hafen.json():encode: cannot encode a " + v.typename());
        } else {
            writeStr(v.tojstring(), sb);                 // function/userdata/thread → quoted tostring
        }
    }

    /**
     * A <b>Position</b> as its durable form, {@code {"gridId": "<decimal>", "x": …, "y": …}} — the one
     * userdata this writer understands, and it has to: without this a saved place would go through the
     * forgiving path below and persist as a quoted {@code tostring}, which is garbage that looks like data.
     * {@link LuaMarshal#jsonToLua} reads the shape back as a Position, so the round trip is closed.
     *
     * <p>A position on ground the character has <b>never visited</b> has no grid to anchor to and therefore no
     * durable form. Strict ({@code hafen.json():encode}) refuses it naming {@code :durable()}; forgiving (the
     * store) writes {@code null}, because one unsaveable value must not cost an addon the rest of its file.
     */
    private static void writePos(LuaPosition pos, StringBuilder sb, boolean strict) {
        LuaPosition.Anchor an = pos.anchor();
        if(an == null) {
            if(strict)
                throw new LuaError("hafen.json():encode: this position cannot be saved — it is on ground you"
                    + " have never visited, so there is no grid to anchor it to (p:durable() reports it)");
            sb.append("null");
            return;
        }
        sb.append("{\"gridId\":");
        writeStr(Long.toString(an.id), sb);
        sb.append(",\"x\":");
        writeNum(an.x, sb);
        sb.append(",\"y\":");
        writeNum(an.y, sb);
        sb.append('}');
    }

    /** A number, with clean integers (no trailing {@code .0}) — the one place numbers are formatted. */
    private static void writeNum(double d, StringBuilder sb) {
        if((d == Math.rint(d)) && (Math.abs(d) < 1e15))
            sb.append(Long.toString((long)d));
        else
            sb.append(Double.toString(d));
    }

    /**
     * A table, as an array or an object. Two guards stand in front of the recursion, and both follow the
     * <b>same rule</b>: strict refuses, forgiving writes a placeholder. A <b>cycle</b> is the walk meeting a
     * table it is already inside; <b>depth</b> is the walk going deeper than {@link #DEFAULT_MAX_DEPTH}
     * without one — an acyclic chain of tables, which the cycle guard cannot see and which would
     * otherwise take the Java stack down with it. A saved-variables flush must not be lost to either, which
     * is why the forgiving path keeps writing.
     */
    private static void writeTab(LuaTable t, StringBuilder sb, java.util.Set<LuaValue> seen, boolean strict, int depth) {
        if(depth >= DEFAULT_MAX_DEPTH) {                 // this table would be level DEFAULT_MAX_DEPTH + 1
            if(strict)
                throw new LuaError("hafen.json():encode: table nesting too deep (> " + DEFAULT_MAX_DEPTH + ")");
            sb.append("\"<too deep>\"");
            return;
        }
        if(!seen.add(t)) {                               // break reference cycles
            if(strict)
                throw new LuaError("hafen.json():encode: cannot encode a table cycle");
            sb.append("\"<cycle>\"");
            return;
        }
        try {
            LuaValue[] keys = t.keys();
            int len = t.length();
            boolean array = (keys.length == len);
            if(array) {
                for(LuaValue k : keys) {
                    if(!k.isint() || (k.toint() < 1) || (k.toint() > len)) {
                        array = false;
                        break;
                    }
                }
            }
            if(array) {
                sb.append('[');
                for(int i = 1; i <= len; i++) {
                    if(i > 1)
                        sb.append(',');
                    write(t.get(i), sb, seen, strict, depth + 1);
                }
                sb.append(']');
            } else {
                sb.append('{');
                boolean first = true;
                for(LuaValue k : keys) {
                    if(!first)
                        sb.append(',');
                    first = false;
                    writeStr(k.tojstring(), sb);          // JSON keys are strings
                    sb.append(':');
                    write(t.get(k), sb, seen, strict, depth + 1);
                }
                sb.append('}');
            }
        } finally {
            seen.remove(t);
        }
    }

    private static void writeStr(String s, StringBuilder sb) {
        sb.append('"');
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
            case '"':  sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n"); break;
            case '\r': sb.append("\\r"); break;
            case '\t': sb.append("\\t"); break;
            case '\b': sb.append("\\b"); break;
            case '\f': sb.append("\\f"); break;
            default:
                if(c < 0x20)
                    sb.append(String.format("\\u%04x", (int)c));
                else
                    sb.append(c);
            }
        }
        sb.append('"');
    }
}
