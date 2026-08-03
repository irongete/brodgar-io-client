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

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.List;


import static io.brodgar.addon.AddonManager.*;

/**
 * The LIVE half of the world: {@code hafen.world} — gob enumeration (the per-gob reads are the Gob class,
 * {@link LuaGob}) plus, since 037.1, the terrain and coordinate functions that used to be {@code hafen.map.*}
 * — and {@code hafen.time}. The low-level gob-read substrate (getgob/gobMatches/gobSnapshot/allGobs/oc/
 * mcache/astro) lives in {@link AddonManager}.
 *
 * <p><b>The axis is LIVE vs RECORDED</b> (037): everything here answers against {@link haven.MCache}, the
 * terrain streamed around the player — {@code nil} off-stream, gone at logout. The <i>explored</i> map, the
 * on-disk {@link MapFile} database (segments, grids, overlays, images, markers and the minimap icon registry),
 * is {@code hafen.map} and lives in {@link MapApi}. {@code hafen.markers} and {@code hafen.radar} are hard cuts
 * — they became {@code hafen.map.markers} and {@code hafen.map.icons}.
 */
final class WorldApi {
    private WorldApi() {}

    /** Build {@code hafen.world} for {@code owner}. From installHafen. */
    static void installWorld(LuaTable hafen, final Addon owner) {
        LuaTable world = new LuaTable();
        // gobs/nearest/within hand out Gob OBJECTS (D-044) — live handles, not snapshots; call :info() on one
        // for the old snapshot table. The gob list is copied under the OCache lock by allGobs(); the filters are
        // evaluated OUT here, because a function filter re-enters Lua (world-reads.md).
        world.set("gobs", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Gob g : allGobs()) {
                    if(gobMatches(filter, owner, g))
                        out.set(++i, LuaGob.of(owner, g.id));
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
                    if(gobMatches(filter, owner, g))
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
        installTerrain(world, owner);   // 037.1: the live terrain + coordinate half moved here from hafen.map
        hafen.set("world", world);
    }

    /**
     * The LIVE terrain + coordinate half of {@code hafen.world} (037.1): the thirteen functions that used to be
     * {@code hafen.map.*} and answer against {@link MCache} — the terrain streamed around the player, {@code nil}
     * off-stream and gone at logout. They moved here <b>unchanged</b>, because the axis the area is on is LIVE vs
     * RECORDED: {@code hafen.map} is now the on-disk map database ({@link MapFile}), and none of these ever read it.
     */
    private static void installTerrain(LuaTable world, final Addon owner) {
        world.set("tile", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                    int id = mc.gettile(tc);
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
        world.set("height", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    return LuaValue.valueOf(mc.getcz(x.todouble(), y.todouble()));
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        world.set("grid", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if((mc == null) || !x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                try {
                    Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                    MCache.Grid g = mc.getgrid(tc.div(MCache.cmaps));
                    LuaTable t = new LuaTable();
                    t.set("id", LuaValue.valueOf(Long.toString(g.id)));   // 64-bit → string (exact anchor)
                    t.set("gc", xy(g.gc.x, g.gc.y));
                    return t;
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // gridPos([x,y]) — the shareable/persistent position: stable grid id + within-grid WORLD offset
        // (0..1100). No args = the player. Use this, not raw rc, across sessions/players.
        world.set("gridPos", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                MCache mc = mcache();
                if(mc == null)
                    return LuaValue.NIL;
                Coord2d wc = (x.isnumber() && y.isnumber())
                    ? Coord2d.of(x.todouble(), y.todouble())
                    : playerPos();   // no args = the player
                if(wc == null)
                    return LuaValue.NIL;
                try {
                    MCache.Grid g = mc.getgrid(wc.floor(MCache.tilesz).div(MCache.cmaps));
                    LuaTable t = new LuaTable();
                    t.set("gridId", LuaValue.valueOf(Long.toString(g.id)));
                    t.set("x", LuaValue.valueOf(wc.x - (g.ul.x * MCache.tilesz.x)));
                    t.set("y", LuaValue.valueOf(wc.y - (g.ul.y * MCache.tilesz.y)));
                    return t;
                } catch(RuntimeException e) {
                    return LuaValue.NIL;
                }
            }
        });
        // fromGridPos({gridId, x, y}) — the INVERSE of gridPos: resolve a saved grid-anchored position back to
        // a login-relative WORLD coord in THIS session, or nil if that grid is not currently loaded here (the
        // caller retries as the map streams in). Persist a layout by storing gridPos(...) verbatim and reloading
        // through this — grid ids are the stable cross-session anchor, raw world coords are login-relative and do
        // not survive a relog (the marker/ghost rule). Accepts the exact {gridId=<string>, x=, y=} table gridPos
        // returns, so fromGridPos(gridPos(x,y)) round-trips to (x,y) whenever the grid is loaded.
        world.set("fromGridPos", new OneArgFunction() {
            public LuaValue call(LuaValue anchor) {
                MCache mc = mcache();
                if((mc == null) || !anchor.istable())
                    return LuaValue.NIL;
                LuaValue idv = anchor.get("gridId"), xv = anchor.get("x"), yv = anchor.get("y");
                if(!idv.isstring() || !xv.isnumber() || !yv.isnumber())
                    return LuaValue.NIL;
                long id;
                try {
                    id = Long.parseLong(idv.tojstring());
                } catch(NumberFormatException e) {   // gridId is not a valid 64-bit id string
                    return LuaValue.NIL;
                }
                Coord2d ul = AddonWidgets.gridWorldUL(mc, id);   // that grid's current UL, or null if not loaded
                if(ul == null)
                    return LuaValue.NIL;
                return xy(ul.x + xv.todouble(), ul.y + yv.todouble());
            }
        });
        // Pure coordinate conversions (no map data needed). worldToTile floors; tileToWorld returns the
        // tile's upper-left world corner; tileToGrid floor-divides into grid coords.
        world.set("worldToTile", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                if(!x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                return xy(tc.x, tc.y);
            }
        });
        world.set("tileToWorld", new TwoArgFunction() {
            public LuaValue call(LuaValue tx, LuaValue ty) {
                if(!tx.isnumber() || !ty.isnumber())
                    return LuaValue.NIL;
                return xy(tx.todouble() * MCache.tilesz.x, ty.todouble() * MCache.tilesz.y);
            }
        });
        world.set("tileToGrid", new TwoArgFunction() {
            public LuaValue call(LuaValue tx, LuaValue ty) {
                if(!tx.isnumber() || !ty.isnumber())
                    return LuaValue.NIL;
                Coord gc = Coord.of((int)tx.todouble(), (int)ty.todouble()).div(MCache.cmaps);
                return xy(gc.x, gc.y);
            }
        });
        // screenToWorld(sx, sy, fn) — the RAYCAST INVERSE of hafen.player():worldToScreen (spec 16 §3, V5): fn({x,y})
        // is called with the WORLD ground coord under game-window pixel (sx,sy), or fn(nil) if the pixel hit no
        // terrain (sky/off-map). It is ASYNCHRONOUS by necessity — the engine reads the true terrain point from the
        // GPU (MapView.Maptest, the same pass the client's own building placement uses), so a synchronous return
        // would stall the UI thread on a GPU fence; instead the result arrives a frame later via fn (exactly the
        // one-frame lag a placement ghost has). (sx,sy) are the same pixel space worldToScreen returns (top-left
        // origin; for the standard fullscreen MapView these are screen pixels). Requires being in the world.
        world.set("screenToWorld", new ThreeArgFunction() {
            public LuaValue call(LuaValue sx, LuaValue sy, LuaValue fn) {
                MapView m = view;
                if((m == null) || !sx.isnumber() || !sy.isnumber() || !fn.isfunction())
                    return LuaValue.NIL;
                screenToWorld(owner, m, (int)Math.round(sx.todouble()), (int)Math.round(sy.todouble()), fn);
                return LuaValue.NIL;   // async — the answer arrives through fn
            }
        });
        // snapPlace(x, y [, fine]) — snap a WORLD coord to the client's PLACEMENT grid, IDENTICALLY to placing a
        // building (spec 16 §4.1, D-033): no fine -> the tile centre; fine=true -> the sub-tile :placegrid
        // (MapView.plobpgran divisions, or free when placegrid is 0). Returns {x,y}. Pure static math shared with
        // the engine's StdPlace (MapView.placeSnap), so it always honours the live :placegrid; no map data needed.
        world.set("snapPlace", new ThreeArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y, LuaValue fine) {
                if(!x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                int modflags = fine.toboolean() ? UI.MOD_SHIFT : 0;
                Coord2d s = MapView.placeSnap(new Coord2d(x.todouble(), y.todouble()), modflags);
                return xy(s.x, s.y);
            }
        });
        // placeGrid() — the current :placegrid setting (MapView.plobpgran, the sub-tile divisions snapPlace(...,true)
        // uses; default 8, 0 = free). Read it to label a gizmo / mirror the user's placement preference (V5).
        world.set("placeGrid", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobpgran);
            }
        });
        // snapAngle(a [, fine]) — snap a facing angle (RADIANS) to the client's placement-ANGLE grid, so a gizmo
        // rotate feels IDENTICAL to rotating a building (spec 16 §4.1, D-033): no fine -> 45° (π/4) steps; fine=true
        // -> the finer :placeangle grid (π/MapView.plobagran). Returns the snapped angle in radians, normalized to
        // (-π, π]. The absolute-angle analog of the client's (wheel-relative) StdPlace.rotate — see snapPlaceAngle;
        // reads the live public plobagran so it always honours :placeangle.
        world.set("snapAngle", new TwoArgFunction() {
            public LuaValue call(LuaValue a, LuaValue fine) {
                if(!a.isnumber())
                    return LuaValue.NIL;
                return LuaValue.valueOf(snapPlaceAngle(a.todouble(), fine.toboolean()));
            }
        });
        // placeAngle() — the current :placeangle setting (MapView.plobagran, the FINE rotation divisions
        // snapAngle(...,true) uses; default 12). The coarse 45° default is independent of it; this is the fine grain.
        world.set("placeAngle", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobagran);
            }
        });
    }
    /** Build {@code hafen.time} for {@code owner}. From installHafen. */
    static void installTime(LuaTable hafen, final Addon owner) {
        LuaTable time = new LuaTable();
        time.set("clock", new ZeroArgFunction() {
            public LuaValue call() {
                Glob g = glob();
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.globtime());
            }
        });
        time.set("dayFraction", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.dt);
            }
        });
        time.set("isNight", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.night);
            }
        });
        time.set("season", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.is);
            }
        });
        time.set("moon", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.mp);
            }
        });
        time.set("yearFraction", new ZeroArgFunction() {
            public LuaValue call() {
                Astronomy a = astro();
                return (a == null) ? LuaValue.NIL : LuaValue.valueOf(a.yt);
            }
        });
        hafen.set("time", time);
    }

    // ---- hafen.map raycast/snap helpers (screenToWorld / snapAngle, V5/V6) ----
    /**
     * {@code hafen.map.screenToWorld} (V5): raycast the terrain under game-window pixel {@code (px,py)} via the
     * engine's own {@link haven.MapView.Maptest} (the pass the client's building placement uses), then call {@code fn}
     * with the world {@code {x,y}} (or nil for no terrain). Asynchronous: {@code Maptest.run()} submits a GPU readback
     * and its callback fires later under {@code synchronized(ui)} (so {@link #callLua} is safe there, serialized with
     * every other addon Lua — same as the V2 ghost-click dispatch). Errors installing the test are swallowed (nil).
     */
    static void screenToWorld(final Addon owner, MapView mv, int px, int py, final LuaValue fn) {
        final Coord pc = new Coord(px, py);
        try {
            mv.new Maptest(pc) {
                protected void hit(Coord pc, Coord2d mc) {
                    callLua(owner, Addon.C_EVENT, fn, xy(mc.x, mc.y));
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
     * {@code hafen.map.snapAngle} (V6): snap a facing angle (radians) to the client's placement-angle grid — the
     * <b>absolute</b> analog of {@code MapView.StdPlace.rotate} (which is wheel-<i>relative</i>, so there is no
     * verbatim engine code to share, unlike position's {@link haven.MapView#placeSnap}). Coarse (no {@code fine}) =
     * 45° (π/4) steps; {@code fine} = the {@code :placeangle} grid (π/{@code MapView.plobagran}). Normalized to
     * (-π, π] via {@link haven.Utils#cangle}. Reads the live public {@code MapView.plobagran} so it honours
     * {@code :placeangle} with no drift — the §4.1 zero-{@code haven}-edit mirror.
     */
    static double snapPlaceAngle(double a, boolean fine) {
        double step = fine ? (Math.PI / MapView.plobagran) : (Math.PI / 4);
        if(step <= 0)
            return Utils.cangle(a);                 // guard a pathological :placeangle (console clamps it >= 2)
        return Utils.cangle(Math.round(a / step) * step);
    }
}
