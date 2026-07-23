package io.brodgar.addon;

import haven.Console;
import haven.Coord2d;
import haven.Gob;
import haven.MapView;
import haven.UI;
import haven.Utils;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The AddOn engine (see {@code specs/addons/15-implementation-plan.md}).
 *
 * <p>Phase 0 proved a Lua VM (LuaJ) reads live state on the UI thread. Phase 1a adds **loading
 * addons from disk**: it discovers {@code <client>/addons/<name>/manifest.json}, gives each addon
 * its own Lua environment, runs its files, and exposes {@code hafen.log}. A {@code :lua} REPL and
 * {@code :addons} console command aid inspection.
 *
 * <p>All-static facade, mirroring {@code io.brodgar.voice.Voice}. Per-addon sandbox, the tick pump,
 * events, the full read API, saved-vars and the options panel arrive in later Phase-1 steps.
 */
public final class AddonManager {

    private static volatile MapView view;   // live map view (for hafen.gob.pos)
    private static volatile UI ui;          // live UI (for output; set at RemoteUI.init)
    private static Globals console;         // shared env for the :lua REPL
    private static final List<Addon> addons = new ArrayList<Addon>();

    private AddonManager() {
    }

    static {
        // Use the RAW console line (quotes intact) so string literals survive; args are pre-split
        // by Utils.splitwords, which strips quotes. Fall back to joined args if the raw line is absent.
        Console.setscmd("lua", (cons, args) -> {
            String raw = cons.rawcmd();
            eval((raw != null) ? stripCmd(raw) : join(args));
        });
        Console.setscmd("addons", (cons, args) -> listAddons());
    }

    // ------------------------------------------------------------- lifecycle

    /** Call site — end of the MapView constructor. Captures the live view for state reads. */
    public static void attach(MapView mv) {
        if(mv != null)
            view = mv;
    }

    /** Call site — first line of MapView.dispose(). */
    public static void detach(MapView mv) {
        if(view == mv)
            view = null;
    }

    /** Per-session init (from RemoteUI.init, where ui.sess is bound): (re)load addons from disk. */
    public static synchronized void init(UI ui_) {
        ui = ui_;
        addons.clear();
        loadAll();
    }

    // ------------------------------------------------------------- discovery + loading

    /** Default: the {@code addons/} folder beside the client jar. {@code -Dhaven.addondir} overrides. */
    static File addonDir() {
        String override = System.getProperty("haven.addondir");
        if((override != null) && !override.isEmpty())
            return new File(override);
        try {
            return Utils.srcpath(AddonManager.class).resolveSibling("addons").toFile();
        } catch(RuntimeException e) {
            return new File("addons");
        }
    }

    private static void loadAll() {
        File dir = addonDir();
        log("addons dir: " + dir);
        File[] subs = dir.listFiles(File::isDirectory);
        if(subs == null) {
            log("no addons/ directory");
            return;
        }
        for(File sub : subs) {
            if(!new File(sub, "manifest.json").isFile())
                continue;
            try {
                Manifest m = Manifest.load(sub.toPath());
                Globals g = JsePlatform.standardGlobals();   // sandbox hardening = later phase
                installHafen(g);
                LuaTable ad = new LuaTable();
                ad.set("id", LuaValue.valueOf(m.id));
                ad.set("dir", LuaValue.valueOf(sub.getAbsolutePath()));
                g.set("ADDON", ad);
                Addon addon = new Addon(m, sub.toPath(), g);
                addon.run();
                addons.add(addon);
                log((addon.error == null) ? ("loaded " + m.id + " v" + m.version)
                                          : ("error in " + m.id + ": " + addon.error));
            } catch(Exception e) {
                log("failed to load '" + sub.getName() + "': " + e.getMessage());
            }
        }
        log(addons.size() + " addon(s) loaded");
    }

    private static void listAddons() {
        if(addons.isEmpty()) {
            log("no addons loaded");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for(Addon a : addons) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(a.manifest.id).append((a.error == null) ? "" : " (error)");
        }
        log("addons: " + sb);
    }

    // ------------------------------------------------------------- the hafen facade

    /** Install the stable {@code hafen.*} facade into a Lua env (shared by addons and the REPL). */
    private static void installHafen(Globals g) {
        LuaTable hafen = new LuaTable();

        LuaTable gob = new LuaTable();
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

        hafen.set("log", new OneArgFunction() {
            public LuaValue call(LuaValue msg) {
                log(msg.isnil() ? "nil" : msg.tojstring());
                return LuaValue.NIL;
            }
        });

        g.set("hafen", hafen);
    }

    /** Output: always to stdout; and in-game via the UI notice system when it's available. */
    static void log(String msg) {
        System.out.println("[addon] " + msg);
        UI u = ui;
        if(u != null) {
            try {
                u.msg(msg);
            } catch(RuntimeException e) {
                /* pre-HUD or no notice sink yet; stdout still has it */
            }
        }
    }

    // ------------------------------------------------------------- :lua REPL

    private static synchronized Globals console() {
        if(console == null) {
            Globals g = JsePlatform.standardGlobals();
            installHafen(g);
            console = g;
        }
        return console;
    }

    private static void eval(String src) {
        if(src.isEmpty())
            return;
        UI u = ui;
        try {
            LuaValue chunk;
            try {
                chunk = console().load("return " + src, "=lua");   // expression form: show its value
            } catch(LuaError e) {
                chunk = console().load(src, "=lua");                // statement form (e.g. print(...))
            }
            LuaValue r = chunk.call();
            if((u != null) && !r.isnil())
                u.msg("lua= " + json(r));
        } catch(LuaError e) {
            if(u != null)
                u.error("lua: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- GobRef resolution

    /** Resolve a GobRef ("player"/"me", a numeric id, or nil=player) to a live position. */
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

    /** Drop the leading command word (and following whitespace) from a raw console line. */
    private static String stripCmd(String line) {
        int i = 0;
        while((i < line.length()) && !Character.isWhitespace(line.charAt(i)))
            i++;
        while((i < line.length()) && Character.isWhitespace(line.charAt(i)))
            i++;
        return line.substring(i);
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
