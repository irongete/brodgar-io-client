package io.brodgar.addon;

import haven.Console;
import haven.Coord2d;
import haven.Drawable;
import haven.Gob;
import haven.GobHealth;
import haven.GobIcon;
import haven.MapView;
import haven.Moving;
import haven.OCache;
import haven.Resource;
import haven.Speaking;
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
import java.util.ArrayList;
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

        // hafen.gob.*(ref) — the canonical per-gob accessor. ref = gob id, "player"/"me", or nil
        // (=player). Each call re-resolves the gob → always fresh; returns nil if it's gone. Unknown
        // tokens ("target"/"partyN"/…) resolve to nil for now (added with their subsystems).
        LuaTable gob = new LuaTable();
        gob.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return LuaValue.valueOf(resolve(ref) != null);
            }
        });
        gob.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return gobSnapshot(resolve(ref));
            }
        });
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
        gob.set("facing", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                synchronized(g) {
                    return LuaValue.valueOf(g.a);
                }
            }
        });
        gob.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String n = (g == null) ? null : gobName(g);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        gob.set("health", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                GobHealth h = g.getattr(GobHealth.class);
                return (h == null) ? LuaValue.NIL : LuaValue.valueOf(h.hp);
            }
        });
        gob.set("moving", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                return LuaValue.valueOf(g.getattr(Moving.class) != null);
            }
        });
        gob.set("speed", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                if(g == null)
                    return LuaValue.NIL;
                Moving mv = g.getattr(Moving.class);
                if(mv == null)
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mv.getv());
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        gob.set("speech", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String s = (g == null) ? null : gobSpeech(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        gob.set("icon", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                Gob g = resolve(ref);
                String s = (g == null) ? null : gobIcon(g);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // distance(ref [, ref2]); ref2 defaults to "player".
        gob.set("distance", new TwoArgFunction() {
            public LuaValue call(LuaValue ref, LuaValue ref2) {
                Gob a = resolve(ref);
                Gob b = resolve(ref2.isnil() ? LuaValue.valueOf("player") : ref2);
                if((a == null) || (b == null))
                    return LuaValue.NIL;
                Coord2d ra, rb;
                synchronized(a) { ra = a.rc; }
                synchronized(b) { rb = b.rc; }
                if((ra == null) || (rb == null))
                    return LuaValue.NIL;
                return LuaValue.valueOf(ra.dist(rb));
            }
        });
        hafen.set("gob", gob);

        // hafen.world.* — enumerate gobs as snapshots. nearest/within measure from the player and skip
        // the player's own gob. Prefer the GobAdded/GobRemoved events over per-frame scanning.
        LuaTable world = new LuaTable();
        world.set("gobs", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Gob g : allGobs()) {
                    LuaValue snap = gobSnapshot(g);
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        world.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                List<Gob> all = allGobs();
                if(filter.isnil())
                    return LuaValue.valueOf(all.size());
                int n = 0;
                for(Gob g : all)
                    if(matches(filter, gobSnapshot(g)))
                        n++;
                return LuaValue.valueOf(n);
            }
        });
        world.set("nearest", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                Gob pl = playerGob();
                if(pl == null)
                    return LuaValue.NIL;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return LuaValue.NIL;
                long self = pl.id;
                LuaValue best = LuaValue.NIL;
                double bestd = Double.POSITIVE_INFINITY;
                for(Gob g : allGobs()) {
                    if(g.id == self)
                        continue;
                    LuaValue snap = gobSnapshot(g);
                    if(!matches(filter, snap))
                        continue;
                    double d = distTo(snap, prc);
                    if(Double.isNaN(d) || (d >= bestd))
                        continue;
                    bestd = d;
                    best = snap;
                }
                return best;
            }
        });
        world.set("within", new TwoArgFunction() {
            public LuaValue call(LuaValue radius, LuaValue filter) {
                double r = radius.optdouble(0);
                LuaTable out = new LuaTable();
                Gob pl = playerGob();
                if(pl == null)
                    return out;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return out;
                long self = pl.id;
                int i = 0;
                for(Gob g : allGobs()) {
                    if(g.id == self)
                        continue;
                    LuaValue snap = gobSnapshot(g);
                    if(!matches(filter, snap))
                        continue;
                    double d = distTo(snap, prc);
                    if(Double.isNaN(d) || (d > r))
                        continue;
                    out.set(++i, snap);
                }
                return out;
            }
        });
        hafen.set("world", world);

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
        System.out.println("[console] :lua " + src);               // echo the input to the terminal
        try {
            LuaValue chunk;
            try {
                chunk = console().load("return " + src, "=lua");   // expression form: show its value
            } catch(LuaError e) {
                chunk = console().load(src, "=lua");                // statement form (e.g. print(...))
            }
            LuaValue r = chunk.call();
            if(!r.isnil()) {
                String out = "lua= " + json(r);
                System.out.println("[console] " + out);            // ...and mirror the result there
                if(u != null)
                    u.msg(out);
            }
        } catch(LuaError e) {
            String err = "lua: " + e.getMessage();
            System.out.println("[console] " + err);
            if(u != null)
                u.error(err);
        }
    }

    // ------------------------------------------------------------- GobRef resolution + snapshots

    /** The player body resource — identity test for the {@code isplayer} snapshot field. */
    private static final String PLAYER_RES = "gfx/borka/body";

    /** The live object cache, or {@code null} before a session/world is up. */
    private static OCache oc() {
        MapView m = view;
        if((m == null) || (m.ui == null) || (m.ui.sess == null))
            return null;
        return m.ui.sess.glob.oc;
    }

    private static Gob getgob(long id) {
        OCache oc = oc();
        return (oc == null) ? null : oc.getgob(id);
    }

    private static Gob playerGob() {
        MapView m = view;
        return (m == null) ? null : m.player();
    }

    /**
     * Resolve a GobRef to a live {@link Gob}: {@code nil}/"player"/"me" = the player, a number (or
     * numeric string) = that gob id. Unknown string tokens ("target"/"partyN"/…) return {@code null}
     * for now — they are wired up when their subsystems land. Never throws into Lua.
     */
    private static Gob resolve(LuaValue ref) {
        MapView m = view;
        if(m == null)
            return null;
        try {
            if((ref == null) || ref.isnil())
                return m.player();
            if(ref.isnumber())
                return getgob((long)ref.todouble());
            String s = ref.tojstring();
            if(s.equals("player") || s.equals("me"))
                return m.player();
            return getgob(Long.parseLong(s));   // numeric string; unknown token → NumberFormatException
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Resolve a GobRef to a live position (backs {@code hafen.gob.pos}). */
    private static Coord2d pos(LuaValue ref) {
        Gob g = resolve(ref);
        if(g == null)
            return null;
        synchronized(g) {
            return g.rc;
        }
    }

    /** A copy of the live gob list (taken under the OCache lock; snapshots built by the caller). */
    private static List<Gob> allGobs() {
        List<Gob> out = new ArrayList<Gob>();
        OCache oc = oc();
        if(oc == null)
            return out;
        synchronized(oc) {
            for(Gob g : oc)
                out.add(g);
        }
        return out;
    }

    /**
     * Does {@code snap} pass {@code filter}? {@code nil} → all; a string → substring match on the
     * gob's {@code name}; a function → called with the snapshot, truthy keeps it (errors drop it).
     */
    private static boolean matches(LuaValue filter, LuaValue snap) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(snap).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring()) {
            LuaValue name = snap.get("name");
            return name.isstring() && name.tojstring().contains(filter.tojstring());
        }
        return true;
    }

    /** Distance from {@code from} to a snapshot's {@code {x,y}}, or NaN if it has no position. */
    private static double distTo(LuaValue snap, Coord2d from) {
        LuaValue x = snap.get("x"), y = snap.get("y");
        if(!x.isnumber() || !y.isnumber())
            return Double.NaN;
        return from.dist(Coord2d.of(x.todouble(), y.todouble()));
    }

    // -- per-attribute readers (each Loading-guarded: resource-backed reads can throw before load) --

    private static String gobName(Gob g) {
        try {
            Drawable d = g.getattr(Drawable.class);
            if(d == null)
                return null;
            Resource r = d.getres();   // may throw Loading, or be null before it resolves
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {
            return null;
        }
    }

    private static String gobSpeech(Gob g) {
        try {
            Speaking sp = g.getattr(Speaking.class);
            return ((sp == null) || (sp.text == null)) ? null : sp.text.text;
        } catch(RuntimeException e) {
            return null;
        }
    }

    private static String gobIcon(Gob g) {
        try {
            GobIcon ic = g.getattr(GobIcon.class);
            if(ic == null)
                return null;
            GobIcon.Icon icon = ic.icon();   // resolves the icon resource; may throw Loading
            return (icon == null) ? null : icon.name();
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** Best-effort active-overlay resource names ({@code Gob.ols}); unresolved ones are skipped. */
    private static LuaTable overlayNames(Gob g) {
        LuaTable out = new LuaTable();
        int i = 0;
        try {
            for(Gob.Overlay ol : g.ols) {
                try {
                    if((ol.spr != null) && (ol.spr.res != null))
                        out.set(++i, LuaValue.valueOf(ol.spr.res.name));
                } catch(RuntimeException e) {
                    /* skip an overlay still resolving */
                }
            }
        } catch(RuntimeException e) {
            /* concurrent overlay mutation etc. — return what we have */
        }
        return out;
    }

    /**
     * A full gob snapshot (the {@code Gob} shape in api-reference.md), used by {@code hafen.gob.info},
     * {@code hafen.world.*}, and the {@code GobAdded}/{@code GobRemoved} payloads. Read on the UI
     * thread under the gob lock; every field is optional and defensive against transient/{@code
     * Loading} state (a partial snapshot is fine while world data is still resolving).
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
                t.set("angle", LuaValue.valueOf(g.a));
                String name = gobName(g);
                if(name != null) {
                    t.set("name", LuaValue.valueOf(name));
                    t.set("isplayer", LuaValue.valueOf(name.equals(PLAYER_RES)));
                }
                GobHealth h = g.getattr(GobHealth.class);
                if(h != null)
                    t.set("hp", LuaValue.valueOf(h.hp));
                Moving mv = g.getattr(Moving.class);
                t.set("moving", LuaValue.valueOf(mv != null));
                if(mv != null) {
                    try {
                        t.set("speed", LuaValue.valueOf(mv.getv()));
                    } catch(RuntimeException e) {
                        /* speed unavailable this frame */
                    }
                }
                String speech = gobSpeech(g);
                if(speech != null)
                    t.set("speech", LuaValue.valueOf(speech));
                String icon = gobIcon(g);
                if(icon != null)
                    t.set("icon", LuaValue.valueOf(icon));
                LuaTable ols = overlayNames(g);
                if(ols.length() > 0)
                    t.set("overlays", ols);
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
