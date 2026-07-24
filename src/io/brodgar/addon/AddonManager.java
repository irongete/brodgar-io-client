package io.brodgar.addon;

import haven.Console;
import haven.Coord2d;
import haven.Gob;
import haven.MapView;
import haven.OCache;
import haven.UI;
import haven.Utils;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The AddOn engine (see {@code specs/addons/04-engine.md}).
 *
 * <p>Phase 0 proved a Lua VM (LuaJ) reads live state on the UI thread. Phase 1a added loading addons
 * from disk (per-addon Lua envs, {@code hafen.log}, the {@code :lua}/{@code :addons} console). Phase
 * 1b adds the <b>runtime</b>: a per-frame <b>tick pump</b> (via an invisible {@link AddonRoot}
 * widget), a synthesized <b>event bus</b> ({@code hafen.events}), core lifecycle/update/gob events,
 * and <b>timers</b> ({@code hafen.timer}). All of it is <b>zero core edit</b> — it reuses the
 * existing {@code RemoteUI.init} and {@code MapView} hooks plus the public {@link OCache#callback}.
 *
 * <p>All-static facade, mirroring {@code io.brodgar.voice.Voice}. Everything Lua runs on the UI
 * thread (principle P5): the {@link OCache} callback fires on network/loader threads, so it only
 * <em>enqueues</em> deltas that {@link #tick(double)} drains and dispatches on the UI thread.
 */
public final class AddonManager {

    private static volatile MapView view;   // live map view (for hafen.gob.pos)
    private static volatile UI ui;          // live UI (for output; set at RemoteUI.init)
    private static final List<Addon> addons = new CopyOnWriteArrayList<Addon>();
    private static Addon consoleOwner;      // the :lua REPL, as a resource owner (persists across sessions)

    // -- engine runtime state (all touched on the UI thread, except the gob queue) ---------------
    private static AddonRoot addonRoot;                 // the attached tick widget (per session)
    private static OCache.ChangeCallback ocCb;          // strong ref: OCache keeps callbacks weakly
    private static volatile boolean enterWorldPending;  // set off-thread (MapView attach), read on tick
    private static double clock;                        // seconds accumulated from tick dt (UI thread)
    private static final Queue<GobEvent> gobEvents = new ConcurrentLinkedQueue<GobEvent>();

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

    /** Call site — end of the MapView constructor. Captures the live view and flags "entered world". */
    public static void attach(MapView mv) {
        if(mv != null) {
            view = mv;
            enterWorldPending = true;   // OnEnterWorld is fired on the next tick (UI thread)
        }
    }

    /** Call site — first line of MapView.dispose(). */
    public static void detach(MapView mv) {
        if(view == mv)
            view = null;
    }

    /**
     * Per-session init (from RemoteUI.init, where ui.sess is bound): tear down the previous session's
     * addons, reset engine state, attach the tick pump + gob event source, then (re)load from disk.
     */
    public static synchronized void init(UI ui_) {
        ui = ui_;
        for(Addon a : addons)         // fire OnDisable + drop owned resources of the old session
            teardown(a);
        addons.clear();

        clock = 0;
        enterWorldPending = false;
        gobEvents.clear();
        addonRoot = null;
        ocCb = null;

        attachRoot(ui_);              // invisible per-frame tick widget (drives the engine)
        registerOcache(ui_);          // GobAdded/GobRemoved source (marshalled to the UI thread)
        loadAll();                    // discover + run addons, fire OnLoad for each
    }

    /** Attach the invisible tick widget to {@code ui.root} (guarded — root must exist). */
    private static void attachRoot(UI u) {
        if((u == null) || (u.root == null)) {
            log("no ui.root; tick pump not attached");
            return;
        }
        try {
            AddonRoot r = new AddonRoot();
            u.root.add(r);            // add() synchronizes on ui; the widget then ticks each frame
            addonRoot = r;
        } catch(RuntimeException e) {
            log("failed to attach tick widget: " + e);
        }
    }

    /** Register a weak-safe {@link OCache} callback that enqueues gob spawn/despawn for the tick. */
    private static void registerOcache(UI u) {
        try {
            OCache oc = u.sess.glob.oc;
            OCache.ChangeCallback cb = new OCache.ChangeCallback() {
                public void added(Gob g)   { gobEvents.add(new GobEvent(true, g)); }
                public void removed(Gob g) { gobEvents.add(new GobEvent(false, g)); }
            };
            oc.callback(cb);
            ocCb = cb;                // hold a strong ref (OCache stores callbacks in a WeakList)
        } catch(RuntimeException e) {
            log("failed to register gob callback: " + e);
        }
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
                Addon addon = new Addon(m, sub.toPath(), g);
                installHafen(g, addon);
                LuaTable ad = new LuaTable();
                ad.set("id", LuaValue.valueOf(m.id));
                ad.set("dir", LuaValue.valueOf(sub.getAbsolutePath()));
                g.set("ADDON", ad);
                addon.run();
                addons.add(addon);
                if(addon.error == null) {
                    fireTo(addon, "OnLoad");                  // the addon's file body just ran
                    log("loaded " + m.id + " v" + m.version);
                } else {
                    log("error in " + m.id + ": " + addon.error);
                }
            } catch(Exception e) {
                log("failed to load '" + sub.getName() + "': " + e.getMessage());
            }
        }
        log(addons.size() + " addon(s) loaded");
    }

    /** Fire {@code OnDisable} then drop an addon's owned resources (events + timers). */
    private static void teardown(Addon a) {
        try {
            fireTo(a, "OnDisable");
        } catch(RuntimeException e) {
            /* isolation is per-handler in callLua; this is just a backstop */
        }
        a.subs.clear();
        a.timers.clear();
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

    // ------------------------------------------------------------- the tick pump

    /**
     * One engine step, driven by {@link AddonRoot#tick(double)} on the UI thread each frame. Order
     * per {@code 04-engine.md}: drain the marshalled event queue, then {@code OnUpdate}, then timers.
     * Everything is error-isolated so an addon bug never breaks the frame or another addon.
     */
    static void tick(double dt) {
        try {
            clock += dt;

            // 1. Gob spawn/despawn captured on network/loader threads → dispatch on the UI thread.
            GobEvent ge;
            while((ge = gobEvents.poll()) != null)
                fire(ge.added ? "GobAdded" : "GobRemoved", gobSnapshot(ge.gob));

            // 2. "Entered the world" (MapView attached on a loader thread).
            if(enterWorldPending) {
                enterWorldPending = false;
                fire("OnEnterWorld");
            }

            // 3. Per-frame update.
            fire("OnUpdate", LuaValue.valueOf(dt));

            // 4. Due timers.
            runTimers();
        } catch(RuntimeException e) {
            log("tick error: " + e);
        }
    }

    private static void runTimers() {
        for(Addon a : addons)
            runTimers(a);
        Addon c = consoleOwner;
        if(c != null)
            runTimers(c);
    }

    private static void runTimers(Addon a) {
        for(Timer t : a.timers) {
            if(!t.alive) {
                a.timers.remove(t);
                continue;
            }
            if(clock >= t.due) {
                callLua(a, t.fn);
                if(t.interval > 0) {
                    t.due += t.interval;                     // repeating: reschedule (fires once/tick)
                } else {
                    t.alive = false;                         // one-shot
                    a.timers.remove(t);
                }
            }
        }
    }

    // ------------------------------------------------------------- event dispatch

    /** Fire an event to every owner (all addons + the REPL). */
    static void fire(String event, LuaValue... args) {
        for(Addon a : addons)
            fireTo(a, event, args);
        Addon c = consoleOwner;
        if(c != null)
            fireTo(c, event, args);
    }

    /** Fire an event to a single owner's matching subscriptions. */
    static void fireTo(Addon a, String event, LuaValue... args) {
        for(Sub s : a.subs) {
            if(!s.alive) {
                a.subs.remove(s);
                continue;
            }
            if(s.event.equals(event))
                callLua(a, s.fn, args);
        }
    }

    /** Call into Lua with full error isolation (a Lua error never escapes the engine step). */
    private static void callLua(Addon owner, LuaValue fn, LuaValue... args) {
        try {
            fn.invoke((args.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(args));
        } catch(LuaError e) {
            log(owner, "handler error: " + e.getMessage());
        } catch(RuntimeException e) {
            log(owner, "handler error: " + e);
        }
    }

    // ------------------------------------------------------------- the hafen facade

    /** Install the stable {@code hafen.*} facade into an owner's Lua env (addons and the REPL). */
    private static void installHafen(Globals g, final Addon owner) {
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
                log(owner, msg.isnil() ? "nil" : msg.tojstring());
                return LuaValue.NIL;
            }
        });

        // hafen.events.on(name, fn) -> handle; handle:off() unsubscribes.
        LuaTable events = new LuaTable();
        events.set("on", new TwoArgFunction() {
            public LuaValue call(LuaValue name, LuaValue fn) {
                if(!name.isstring() || !fn.isfunction())
                    throw new LuaError("hafen.events.on(name, fn) expects (string, function)");
                final Sub sub = new Sub(owner, name.tojstring(), fn);
                owner.subs.add(sub);
                LuaTable h = new LuaTable();
                h.set("off", new ZeroArgFunction() {
                    public LuaValue call() {
                        sub.alive = false;
                        owner.subs.remove(sub);
                        return LuaValue.NIL;
                    }
                });
                return h;
            }
        });
        hafen.set("events", events);

        // hafen.timer.after(sec, fn) one-shot · hafen.timer.every(sec, fn) repeating.
        // Both return a handle; handle:cancel() stops it.
        LuaTable timer = new LuaTable();
        timer.set("after", new TwoArgFunction() {
            public LuaValue call(LuaValue sec, LuaValue fn) {
                return newTimer(owner, sec, fn, false);
            }
        });
        timer.set("every", new TwoArgFunction() {
            public LuaValue call(LuaValue sec, LuaValue fn) {
                return newTimer(owner, sec, fn, true);
            }
        });
        hafen.set("timer", timer);

        g.set("hafen", hafen);
    }

    private static LuaValue newTimer(final Addon owner, LuaValue sec, LuaValue fn, boolean repeat) {
        if(!sec.isnumber() || !fn.isfunction())
            throw new LuaError("hafen.timer expects (seconds, function)");
        double s = sec.todouble();
        if(s < 0)
            s = 0;
        final Timer t = new Timer(owner, clock + s, repeat ? s : 0, fn);
        owner.timers.add(t);
        LuaTable h = new LuaTable();
        h.set("cancel", new ZeroArgFunction() {
            public LuaValue call() {
                t.alive = false;
                owner.timers.remove(t);
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /** Engine-level output: stdout (prefixed) and in-game notice when available. */
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

    /** Addon-level output ({@code hafen.log} + handler errors): tagged with the addon id. */
    static void log(Addon owner, String msg) {
        String id = ((owner != null) && (owner.manifest != null)) ? owner.manifest.id : "addon";
        System.out.println("[" + id + "] " + msg);
        UI u = ui;
        if(u != null) {
            try {
                u.msg(id + ": " + msg);
            } catch(RuntimeException e) {
                /* pre-HUD or no notice sink yet; stdout still has it */
            }
        }
    }

    // ------------------------------------------------------------- :lua REPL

    private static synchronized Globals console() {
        if(consoleOwner == null) {
            Globals g = JsePlatform.standardGlobals();
            Addon owner = new Addon(Manifest.internal("(console)"), null, g);
            installHafen(g, owner);
            consoleOwner = owner;
        }
        return consoleOwner.env;
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

    // ------------------------------------------------------------- GobRef resolution + snapshots

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

    /**
     * A minimal gob snapshot ({@code {id, x, y}}) for {@code GobAdded}/{@code GobRemoved} payloads.
     * Read on the UI thread under the gob lock; defensive against transient/{@code Loading} state.
     * The full attribute set arrives with the read API (task 1c).
     */
    private static LuaValue gobSnapshot(Gob g) {
        if(g == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        try {
            synchronized(g) {
                t.set("id", LuaValue.valueOf((double)g.id));
                Coord2d rc = g.rc;
                if(rc != null) {
                    t.set("x", LuaValue.valueOf(rc.x));
                    t.set("y", LuaValue.valueOf(rc.y));
                }
            }
        } catch(RuntimeException e) {
            /* partial snapshot is fine (e.g. world data still resolving) */
        }
        return t;
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

    // ------------------------------------------------------------- owned-resource records

    /** A live event subscription: {@code hafen.events.on(event, fn)} in addon {@code owner}. */
    public static final class Sub {
        final Addon owner;
        final String event;
        final LuaValue fn;
        boolean alive = true;

        Sub(Addon owner, String event, LuaValue fn) {
            this.owner = owner;
            this.event = event;
            this.fn = fn;
        }
    }

    /** A live timer: {@code due} is engine-clock seconds; {@code interval<=0} means one-shot. */
    public static final class Timer {
        final Addon owner;
        double due;
        final double interval;
        final LuaValue fn;
        boolean alive = true;

        Timer(Addon owner, double due, double interval, LuaValue fn) {
            this.owner = owner;
            this.due = due;
            this.interval = interval;
            this.fn = fn;
        }
    }

    /** A gob spawn/despawn captured off-thread, awaiting UI-thread dispatch. */
    private static final class GobEvent {
        final boolean added;
        final Gob gob;

        GobEvent(boolean added, Gob gob) {
            this.added = added;
            this.gob = gob;
        }
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
