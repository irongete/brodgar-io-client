package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Coord;
import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.MapFile;
import haven.MapView;
import haven.MCache;
import haven.Resource;
import haven.UI;
import haven.Utils;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;


import static io.brodgar.addon.AddonManager.*;

/**
 * The LIVE half of the world — {@code hafen.world()} — and {@code hafen.time()}. Gobs (the per-gob reads are
 * the Gob class, {@link LuaGob}), the terrain under them, and the coordinate spaces they live in. The
 * low-level gob-read substrate (getgob/gobMatches/gobSnapshot/allGobs/oc/mcache/astro) lives in
 * {@link AddonManager}.
 *
 * <p><b>The axis is LIVE vs RECORDED</b> (037): everything here answers against {@link haven.MCache}, the
 * terrain streamed around the player — {@code nil} off-stream, gone at logout. The <i>explored</i> map, the
 * on-disk {@link MapFile} database (segments, grids, overlays, images, markers and the minimap icon registry),
 * is {@code hafen.map} and lives in {@link MapApi}.
 *
 * <p><b>{@code hafen.gob} is gone into this section</b> (spec {@code 039-uniform-api} §3.3, D-066): a gob lives
 * in the world, so {@code hafen.world():gob()} is the one read-only Gob collection and absorbs all five entry
 * points, {@code :get(id)} included. Keeping {@code :gob(id)} beside {@code :gobs(filter)} would have left
 * standing the exact singular/plural pair the grammar removes everywhere else.
 *
 * <p><b>Every spatial verb takes a {@link LuaPosition}</b> rather than a pair of numbers, and the four position
 * verbs the API used to have — {@code gridPos}, {@code fromGridPos}, {@code marker:anchor()} and the plain
 * {@code {x, y}} table — collapse into it. {@code placeGrid()}/{@code placeAngle()} are cut outright: they read
 * the same two {@link MapView} fields as {@code options():interface():posGran()}/{@code :angGran()}, which also
 * write them, and the two doors did not even agree on units.
 */
final class WorldApi {
    private WorldApi() {}

    /** Build {@code hafen.world()} for {@code owner}. From installHafen. */
    static void installWorld(LuaTable hafen, final Addon owner) {
        // Both collections are minted ONCE and handed back by identity, like the section object itself: a
        // draw callback writing hafen.world():gob():nearest(...) runs 60x/s and must allocate nothing.
        final LuaValue gobs = gobCollection(owner);
        final LuaValue grids = gridCollection(owner);
        LuaTable m = new LuaTable();
        // gob() — the read-only Gob collection. :get(id) is NEVER nil (it is what lets you anchor to a gob
        // before it streams in; :exists() is the liveness test), while :find/:nearest answer nil and :list
        // answers an empty array. A collection needs no :add/:remove to be one.
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "world", "gob");
                return gobs;
            }
        });
        // grid() — the grids streamed in right now, addressed by the point they cover.
        m.set("grid", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "world", "grid");
                return grids;
            }
        });
        // position(x, y) — a Position from session world components; position(saved) — one rebuilt from the
        // {gridId, x, y} durable form. A Position read back out of hafen.store is already a Position, so the
        // second form is for a shape that arrived some other way (a message, a file, another player).
        m.set("position", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "position");
                LuaValue first = Args.required(a, 2, "hafen.world():position", "x (or a saved position)");
                if(first.istable())
                    return savedPosition(owner, first);
                if(!first.isnumber())
                    throw new LuaError("hafen.world():position(x, y): x and y are session world components,"
                        + " or pass the one table p:info() gave you");
                LuaValue yv = Args.required(a, 3, "hafen.world():position", "y");
                if(!yv.isnumber())
                    throw new LuaError("hafen.world():position(x, y): y must be a number");
                return LuaPosition.ofWorld(owner, first.todouble(), yv.todouble());
            }
        });
        // tile(p) — the tileset id + resource name under a Position; nil off-stream.
        m.set("tile", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tile");
                Coord2d rc = here(a, 2, "hafen.world():tile");
                MCache mc = mcache();
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                try {
                    int id = mc.gettile(rc.floor(MCache.tilesz));
                    LuaTable t = new LuaTable();
                    t.set("id", LuaValue.valueOf(id));
                    Resource r = mc.tilesetr(id);
                    if(r != null)
                        t.set("name", LuaValue.valueOf(r.name));
                    return t;
                } catch(RuntimeException e) {   // Loading etc.
                    return LuaValue.NIL;
                }
            }
        });
        // height(p) — terrain height under a Position; nil off-stream.
        m.set("height", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "height");
                Coord2d rc = here(a, 2, "hafen.world():height");
                MCache mc = mcache();
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mc.getcz(rc.x, rc.y));
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // Pure coordinate conversions between the two lattice spaces (no map data needed). The world<->tile
        // direction lives on the Position itself (p:tileCoord()), because that one is about a place.
        m.set("tileToWorld", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tileToWorld");
                double tx = number(a, 2, "hafen.world():tileToWorld", "tx");
                double ty = number(a, 3, "hafen.world():tileToWorld", "ty");
                return xy(tx * MCache.tilesz.x, ty * MCache.tilesz.y);
            }
        });
        m.set("tileToGrid", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "tileToGrid");
                double tx = number(a, 2, "hafen.world():tileToGrid", "tx");
                double ty = number(a, 3, "hafen.world():tileToGrid", "ty");
                Coord gc = Coord.of((int)tx, (int)ty).div(MCache.cmaps);
                return xy(gc.x, gc.y);
            }
        });
        // screenToWorld(sx, sy, fn) — the RAYCAST INVERSE of hafen.player():worldToScreen: fn(p) is called with
        // a Position on the ground under game-window pixel (sx,sy), or fn(nil) if the pixel hit no terrain
        // (sky/off-map). ASYNCHRONOUS by necessity — the engine reads the true terrain point from the GPU
        // (MapView.Maptest, the pass the client's own building placement uses), so a synchronous return would
        // stall the UI thread on a GPU fence; the answer arrives a frame later, exactly the lag a placement
        // ghost has. (sx,sy) is the same pixel space worldToScreen returns. Requires being in the world.
        m.set("screenToWorld", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "screenToWorld");
                double sx = number(a, 2, "hafen.world():screenToWorld", "sx");
                double sy = number(a, 3, "hafen.world():screenToWorld", "sy");
                LuaValue fn = Args.required(a, 4, "hafen.world():screenToWorld", "fn");
                if(!fn.isfunction())
                    throw new LuaError("hafen.world():screenToWorld(sx, sy, fn): fn must be a function — the"
                        + " answer comes back a frame later, so there is nothing to return here");
                MapView mv = view;
                if(mv != null)
                    screenToWorld(owner, mv, (int)Math.round(sx), (int)Math.round(sy), fn);
                return LuaValue.NIL;   // async — the answer arrives through fn
            }
        });
        // snapPlace(p [, fine]) — snap a Position to the client's PLACEMENT grid, IDENTICALLY to placing a
        // building (D-033): no fine -> the tile centre; fine=true -> the sub-tile grid the interface option
        // posGran() sets (free when it is 0). Pure static math shared with the engine's own StdPlace, so it
        // always honours the live setting; no map data needed.
        m.set("snapPlace", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "snapPlace");
                Coord2d rc = LuaPosition.worldArg(a, 2, "hafen.world():snapPlace", "p");
                int modflags = a.arg(3).toboolean() ? UI.MOD_SHIFT : 0;
                Coord2d s = MapView.placeSnap(new Coord2d(rc.x, rc.y), modflags);
                return LuaPosition.ofWorld(owner, s.x, s.y);
            }
        });
        // snapAngle(a [, fine]) — snap a facing angle (RADIANS) to the client's placement-ANGLE grid, so a
        // gizmo rotate feels IDENTICAL to rotating a building: no fine -> 45 degree steps; fine=true -> the
        // finer grid the interface option angGran() sets. Normalized to (-pi, pi].
        m.set("snapAngle", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "world", "snapAngle");
                double ang = number(a, 2, "hafen.world():snapAngle", "a");
                return LuaValue.valueOf(snapPlaceAngle(ang, a.arg(3).toboolean()));
            }
        });
        Section.install(hafen, "world", m);
    }

    /**
     * {@code hafen.world():gob()} — every loaded game object, as a read-only collection of Gob objects (D-044:
     * live handles, never snapshots). The gob list is copied under the {@code OCache} lock by {@link
     * AddonManager#allGobs}; the filters run outside it, because a function filter re-enters Lua.
     */
    private static LuaValue gobCollection(final Addon owner) {
        LuaTable extra = new LuaTable();
        // nearest(filter) / within(r, filter) measure from the player and skip the player's own gob — the one
        // thing that makes them different from :find()/:list() over the same members.
        extra.set("nearest", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "nearest");
                LuaValue filter = a.arg(2);
                Gob pl = playerGob();
                if(pl == null)
                    return LuaValue.NIL;
                Coord2d prc;
                synchronized(pl) { prc = pl.rc; }
                if(prc == null)
                    return LuaValue.NIL;
                long self = pl.id;
                Gob best = null;
                double bestd = Double.POSITIVE_INFINITY;
                for(Gob g : allGobs()) {
                    if(g.id == self)
                        continue;
                    if(!gobMatches(filter, owner, g))
                        continue;
                    double d = distTo(g, prc);
                    if(Double.isNaN(d) || (d >= bestd))
                        continue;
                    bestd = d;
                    best = g;
                }
                return (best == null) ? LuaValue.NIL : LuaGob.of(owner, best.id);
            }
        });
        extra.set("within", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "within");
                double r = number(a, 2, "hafen.world():gob():within", "radius");
                LuaValue filter = a.arg(3);
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
                    if(!gobMatches(filter, owner, g))
                        continue;
                    double d = distTo(g, prc);
                    if(Double.isNaN(d) || (d > r))
                        continue;
                    out.set(++i, LuaGob.of(owner, g.id));
                }
                return out;
            }
        });
        return LuaCollection.create("hafen.world():gob()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Gob g : allGobs())
                    out.add(LuaGob.of(owner, g.id));
                return out;
            }

            public String needle(LuaValue member) {
                LuaGob h = LuaGob.resolve(member);
                Gob g = (h == null) ? null : getgob(h.id);
                return (g == null) ? null : gobName(g);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(!key.isnumber())
                    throw new LuaError("hafen.world():gob():get(id) expects a gob id (a number) — the"
                        + " \"player\"/\"me\"/\"partyN\" tokens are gone; your own gob is hafen.player():gob()");
                return LuaGob.of(owner, (long)key.todouble());
            }
        }, extra);
    }

    /**
     * {@code hafen.world():grid()} — the map grids <b>streamed in right now</b>, as {@link LuaMapGrid}
     * objects. {@code :at(p)} addresses one by the point it covers and {@code :get(id)} by the id the server
     * published; a grid has no name, so a string filter is refused rather than quietly matching nothing.
     *
     * <p><b>This is the same entity {@code hafen.map():grid()} hands back</b>, and that is not a tidy-up: the
     * live half publishes {@code MCache.Grid.id} and the recorded half interns on the very same {@code long},
     * so a grid was never two things. Each door answers {@code nil} for what its own half does not have —
     * ground you are standing on that has not been written down yet is {@code :live()} true and
     * {@code :exists()} false, and ground explored a year ago is the mirror.
     */
    private static LuaValue gridCollection(final Addon owner) {
        LuaTable extra = new LuaTable();
        // at(p) — the grid covering a Position. A plain lookup of what is streamed, never MCache.getgrid:
        // asking which grid a place is in must not send a map request for ground you only asked about.
        extra.set("at", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "at");
                Coord2d rc = here(a, 2, "hafen.world():grid():at");
                MCache mc = mcache();
                if((mc == null) || (rc == null))
                    return LuaValue.NIL;
                MCache.Grid g = AddonWidgets.loadedGrid(mc, rc.floor(MCache.tilesz).div(MCache.cmaps));
                return (g == null) ? LuaValue.NIL : LuaMapGrid.of(owner, g.id);
            }
        });
        return LuaCollection.create("hafen.world():grid()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(MCache.Grid g : AddonWidgets.loadedGrids(mcache()))
                    out.add(LuaMapGrid.of(owner, g.id));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long id = MapApi.idArg(key, "hafen.world():grid():get(id)", "grid");
                return (AddonWidgets.gridWorldUL(mcache(), id) == null)
                    ? LuaValue.NIL : LuaMapGrid.of(owner, id);
            }
        }, extra);
    }

    /** Rebuild a Position from the {@code {gridId, x, y}} durable form, refusing a shape that is not one. */
    private static LuaValue savedPosition(Addon owner, LuaValue saved) {
        LuaValue idv = saved.get("gridId"), xv = saved.get("x"), yv = saved.get("y");
        if((idv.type() != LuaValue.TSTRING) || !xv.isnumber() || !yv.isnumber())
            throw new LuaError("hafen.world():position(saved): expected the table p:info() gives you —"
                + " {gridId = \"<decimal string>\", x = <number>, y = <number>}");
        long id;
        try {
            id = Long.parseLong(idv.tojstring());
        } catch(NumberFormatException e) {
            throw new LuaError("hafen.world():position(saved): \"" + idv.tojstring() + "\" is not a decimal"
                + " grid id");
        }
        return LuaPosition.ofAnchor(owner, id, xv.todouble(), yv.todouble());
    }

    /**
     * A Position argument for a <b>read</b>: the world coordinate it names, or {@code null} when this session
     * cannot locate it — which for a terrain read is the same {@code nil} as off-stream. The act verbs use
     * {@link LuaPosition#worldArg} instead and refuse, because there is no acting on a place that is not here.
     */
    private static Coord2d here(Varargs a, int i, String verb) {
        return LuaPosition.posArg(a, i, verb, "p").world();
    }

    /** A required number argument, refusing an explicit nil like every other write does (§2.9). */
    static double number(Varargs a, int i, String verb, String param) {
        LuaValue v = Args.required(a, i, verb, param);
        if(!v.isnumber())
            throw new LuaError(verb + ": " + param + " must be a number");
        return v.todouble();
    }

    /**
     * Build {@code hafen.time} for {@code owner}. From installHafen. A plain section object: {@code
     * hafen.time()} is the per-addon singleton and every reader is a colon call on it. {@code clock()} always
     * answers; the astronomy readers are nil until the first "astro" update lands.
     */
    static void installTime(LuaTable hafen, final Addon owner) {
        LuaTable m = new LuaTable();
        // clock() — the game clock, in game-world seconds (Glob.globtime). Nil before the session exists.
        m.set("clock", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "clock");
                Glob g = glob();
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.globtime());
            }
        });
        // dayFraction() — 0..1 through the game day.
        m.set("dayFraction", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "dayFraction");
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.dt);
            }
        });
        // isNight() — is it night right now?
        m.set("isNight", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "isNight");
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.night);
            }
        });
        // season() — the season index the server publishes.
        m.set("season", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "season");
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.is);
            }
        });
        // moon() — the moon phase, 0..1.
        m.set("moon", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "moon");
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.mp);
            }
        });
        // yearFraction() — 0..1 through the game year.
        m.set("yearFraction", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "time", "yearFraction");
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.yt);
            }
        });
        Section.install(hafen, "time", m);
    }

    // ---- raycast/snap helpers (screenToWorld / snapAngle) ----
    /**
     * {@code hafen.world():screenToWorld}: raycast the terrain under game-window pixel {@code (px,py)} via the
     * engine's own {@link haven.MapView.Maptest} (the pass the client's building placement uses), then call
     * {@code fn} with the ground {@link LuaPosition} (or nil for no terrain). Asynchronous: {@code Maptest.run()}
     * submits a GPU readback and its callback fires later under {@code synchronized(ui)} (so {@link #callLua} is
     * safe there, serialized with every other addon Lua). Errors installing the test are swallowed (no callback).
     */
    static void screenToWorld(final Addon owner, MapView mv, int px, int py, final LuaValue fn) {
        final Coord pc = new Coord(px, py);
        try {
            mv.new Maptest(pc) {
                protected void hit(Coord pc, Coord2d mc) {
                    callLua(owner, Addon.C_EVENT, fn, LuaPosition.ofWorld(owner, mc.x, mc.y));
                }
                protected void nohit(Coord pc) {
                    callLua(owner, Addon.C_EVENT, fn, LuaValue.NIL);
                }
            }.run();
        } catch(RuntimeException e) {
            /* couldn't submit the readback (e.g. no render env yet) — the caller simply gets no callback */
        }
    }

    /**
     * {@code hafen.world():snapAngle}: snap a facing angle (radians) to the client's placement-angle grid — the
     * <b>absolute</b> analog of {@code MapView.StdPlace.rotate} (which is wheel-<i>relative</i>, so there is no
     * verbatim engine code to share, unlike position's {@link haven.MapView#placeSnap}). Coarse (no {@code fine})
     * = 45° (π/4) steps; {@code fine} = the finer grid ({@code MapView.plobagran}). Normalized to (-π, π] via
     * {@link haven.Utils#cangle}. Reads the live public field so it honours the setting with no drift.
     */
    static double snapPlaceAngle(double a, boolean fine) {
        double step = fine ? (Math.PI / MapView.plobagran) : (Math.PI / 4);
        if(step <= 0)
            return Utils.cangle(a);                 // guard a pathological setting (the console clamps it >= 2)
        return Utils.cangle(Math.round(a / step) * step);
    }
}
