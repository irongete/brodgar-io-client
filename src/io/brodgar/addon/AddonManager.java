package io.brodgar.addon;

import haven.Console;
import haven.Coord2d;
import haven.Gob;
import haven.MapView;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Phase-0 spike of the AddOn engine (see {@code specs/addons/15-implementation-plan.md}).
 *
 * <p>Goal of this phase: prove that a Lua VM (LuaJ) runs on the UI thread and can read live
 * game state through a stable {@code hafen.*} facade. It exposes exactly one read —
 * {@code hafen.gob.pos(ref)} — and an in-game {@code :lua <expr>} REPL to exercise it.
 *
 * <p>Design, mirroring {@code io.brodgar.voice.Voice}: an all-static facade that captures the
 * live {@link MapView} on {@code attach} and never blocks the game thread. Per-addon Lua
 * environments, the sandbox, the tick pump, disk loading and the full API arrive in Phase 1+.
 */
public final class AddonManager {

    /** The live in-game map view, or {@code null}. Captured like {@code io.brodgar.voice.Voice}. */
    private static volatile MapView view;

    /** Single shared Lua env for the spike. Per-addon envs + sandbox come in Phase 1 (D-017). */
    private static Globals lua;

    private AddonManager() {
    }

    static {
        // Engine-lifetime console command. Console has no unregister, so Reload UI must never
        // re-register it (specs/addons/05-lifecycle-and-reload.md). Registered once at class load.
        Console.setscmd("lua", (cons, args) -> eval(join(args)));
    }

    // ------------------------------------------------------------- MapView call sites

    /** Call site #1 — end of the MapView constructor. Idempotent, non-blocking. */
    public static void attach(MapView mv) {
        if(mv != null)
            view = mv;
    }

    /** Call site #2 — first line of MapView.dispose(). */
    public static void detach(MapView mv) {
        if(view == mv)
            view = null;
    }

    // ------------------------------------------------------------- Lua env + REPL

    private static synchronized Globals env() {
        if(lua == null) {
            Globals g = JsePlatform.standardGlobals();  // Phase 1 tightens this to a sandbox (D-017)
            LuaTable hafen = new LuaTable();
            LuaTable gob = new LuaTable();
            // hafen.gob.pos(ref) -> {x=, y=} | nil. ref = "player"/"me", a gob id, or nil (=player).
            gob.set("pos", new OneArgFunction() {
                public LuaValue call(LuaValue ref) {
                    Coord2d rc = pos(ref);
                    if(rc == null)
                        return LuaValue.NIL;
                    LuaTable t = new LuaTable();
                    t.set("x", LuaValue.valueOf(rc.x));
                    t.set("y", LuaValue.valueOf(rc.y));
                    return t;
                }
            });
            hafen.set("gob", gob);
            g.set("hafen", hafen);
            // The in-game console strips quotes (Utils.splitwords), so string tokens can't survive
            // ":lua". Expose the common tokens as globals so `hafen.gob.pos(player)` works unquoted.
            g.set("player", LuaValue.valueOf("player"));
            g.set("me", LuaValue.valueOf("player"));
            g.set("target", LuaValue.valueOf("target"));
            lua = g;
        }
        return lua;
    }

    /** Evaluate a console Lua line; show the value (if any) in-game, errors as an error notice. */
    private static void eval(String src) {
        MapView m = view;
        if(src.isEmpty())
            return;
        try {
            LuaValue chunk;
            try {
                chunk = env().load("return " + src, "=lua");   // expression form: show its value
            } catch(LuaError e) {
                chunk = env().load(src, "=lua");                // statement form (e.g. print(...))
            }
            LuaValue r = chunk.call();
            if((m != null) && (m.ui != null) && !r.isnil())
                m.ui.msg("lua= " + json(r));
        } catch(LuaError e) {
            if((m != null) && (m.ui != null))
                m.ui.error("lua: " + e.getMessage());
        }
    }

    /** Resolve a GobRef (Phase 0: "player"/"me", a numeric id, or nil=player) to a live position. */
    private static Coord2d pos(LuaValue ref) {
        MapView m = view;
        if((m == null) || (m.ui == null) || (m.ui.sess == null))
            return null;
        Gob g;
        try {
            if(ref.isnil()) {
                g = m.player();
            } else if(ref.isnumber()) {
                g = m.ui.sess.glob.oc.getgob((long)ref.todouble());
            } else {
                String s = ref.tojstring();
                g = (s.equals("player") || s.equals("me")) ? m.player()
                    : m.ui.sess.glob.oc.getgob(Long.parseLong(s));
            }
        } catch(RuntimeException e) {
            return null;
        }
        if(g == null)
            return null;
        synchronized(g) {
            return g.rc;
        }
    }

    private static String join(String[] args) {
        StringBuilder sb = new StringBuilder();
        for(int i = 1; i < args.length; i++) {
            if(sb.length() > 0)
                sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // -------------------------------------------------- compact JSON for the REPL (copy-friendly)

    /** Serialize a Lua value to compact single-line JSON so console output is inspectable/copyable. */
    private static String json(LuaValue v) {
        StringBuilder sb = new StringBuilder();
        json(v, sb, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return sb.toString();
    }

    private static void json(LuaValue v, StringBuilder sb, java.util.Set<LuaValue> seen) {
        if(v.isnil()) {
            sb.append("null");
        } else if(v.isboolean()) {
            sb.append(v.toboolean() ? "true" : "false");
        } else if(v instanceof LuaNumber) {
            double d = v.todouble();
            if(!Double.isFinite(d))
                sb.append("null");                       // JSON has no NaN/Infinity
            else if((d == Math.rint(d)) && (Math.abs(d) < 1e15))
                sb.append(Long.toString((long)d));       // clean integers (no trailing .0)
            else
                sb.append(Double.toString(d));
        } else if(v instanceof LuaString) {
            jsonstr(v.tojstring(), sb);
        } else if(v instanceof LuaTable) {
            jsontab((LuaTable)v, sb, seen);
        } else {
            jsonstr(v.tojstring(), sb);                  // function/userdata/thread → quoted tostring
        }
    }

    private static void jsontab(LuaTable t, StringBuilder sb, java.util.Set<LuaValue> seen) {
        if(!seen.add(t)) {                               // break reference cycles
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
                    json(t.get(i), sb, seen);
                }
                sb.append(']');
            } else {
                sb.append('{');
                boolean first = true;
                for(LuaValue k : keys) {
                    if(!first)
                        sb.append(',');
                    first = false;
                    jsonstr(k.tojstring(), sb);           // JSON keys are strings
                    sb.append(':');
                    json(t.get(k), sb, seen);
                }
                sb.append('}');
            }
        } finally {
            seen.remove(t);
        }
    }

    private static void jsonstr(String s, StringBuilder sb) {
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
