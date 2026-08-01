package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Astronomy;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Glob;
import haven.Gob;
import haven.GobIcon;
import haven.Loading;
import haven.MapFile;
import haven.MapView;
import haven.MCache;
import haven.MiniMap;
import haven.Music;
import haven.OCache;
import haven.Resource;
import haven.UI;
import haven.Utils;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;


import static io.brodgar.addon.AddonManager.*;

/**
 * The world-read + map subsystem: {@code hafen.world} (gob enumeration — the per-gob reads are the Gob class,
 * {@link LuaGob}), {@code map} (grid/marker geometry), {@code markers} (the MapFile marker DB + MarkersChanged
 * poll), {@code radar} (GobIcon settings), and {@code time} / {@code sound} / {@code music}. The low-level
 * gob-read substrate (getgob/gobMatches/gobSnapshot/allGobs/oc/mcache/astro) lives in {@link AddonManager}. The marker DB fields +
 * poll live here; {@link AddonManager} calls {@link #pollMarkers} on the tick and {@link #resetMarkers} on init.
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
        hafen.set("world", world);
    }

    /** Build {@code hafen.map} for {@code owner}. From installHafen. */
    static void installMap(LuaTable hafen, final Addon owner) {
        LuaTable map = new LuaTable();
        map.set("tile", new TwoArgFunction() {
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
        map.set("height", new TwoArgFunction() {
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
        map.set("grid", new TwoArgFunction() {
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
        map.set("gridPos", new TwoArgFunction() {
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
        map.set("fromGridPos", new OneArgFunction() {
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
        map.set("worldToTile", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y) {
                if(!x.isnumber() || !y.isnumber())
                    return LuaValue.NIL;
                Coord tc = Coord2d.of(x.todouble(), y.todouble()).floor(MCache.tilesz);
                return xy(tc.x, tc.y);
            }
        });
        map.set("tileToWorld", new TwoArgFunction() {
            public LuaValue call(LuaValue tx, LuaValue ty) {
                if(!tx.isnumber() || !ty.isnumber())
                    return LuaValue.NIL;
                return xy(tx.todouble() * MCache.tilesz.x, ty.todouble() * MCache.tilesz.y);
            }
        });
        map.set("tileToGrid", new TwoArgFunction() {
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
        map.set("screenToWorld", new ThreeArgFunction() {
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
        map.set("snapPlace", new ThreeArgFunction() {
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
        map.set("placeGrid", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobpgran);
            }
        });
        // snapAngle(a [, fine]) — snap a facing angle (RADIANS) to the client's placement-ANGLE grid, so a gizmo
        // rotate feels IDENTICAL to rotating a building (spec 16 §4.1, D-033): no fine -> 45° (π/4) steps; fine=true
        // -> the finer :placeangle grid (π/MapView.plobagran). Returns the snapped angle in radians, normalized to
        // (-π, π]. The absolute-angle analog of the client's (wheel-relative) StdPlace.rotate — see snapPlaceAngle;
        // reads the live public plobagran so it always honours :placeangle.
        map.set("snapAngle", new TwoArgFunction() {
            public LuaValue call(LuaValue a, LuaValue fine) {
                if(!a.isnumber())
                    return LuaValue.NIL;
                return LuaValue.valueOf(snapPlaceAngle(a.todouble(), fine.toboolean()));
            }
        });
        // placeAngle() — the current :placeangle setting (MapView.plobagran, the FINE rotation divisions
        // snapAngle(...,true) uses; default 12). The coarse 45° default is independent of it; this is the fine grain.
        map.set("placeAngle", new ZeroArgFunction() {
            public LuaValue call() {
                return LuaValue.valueOf(MapView.plobagran);
            }
        });
        hafen.set("map", map);
    }

    /** Build {@code hafen.markers} for {@code owner}. From installHafen. */
    static void installMarkers(LuaTable hafen, final Addon owner) {
        LuaTable markers = new LuaTable();
        markers.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(LuaValue snap : markerSnapshots()) {
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        markers.set("nearest", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaValue best = LuaValue.NIL;
                double bestd = Double.POSITIVE_INFINITY;
                for(LuaValue snap : markerSnapshots()) {
                    if(!matches(filter, snap))
                        continue;
                    LuaValue d = snap.get("dist");
                    if(!d.isnumber())
                        continue;                        // cross-segment marker → no world distance
                    double dd = d.todouble();
                    if(dd < bestd) { bestd = dd; best = snap; }
                }
                return best;
            }
        });
        markers.set("add", new VarArgFunction() {
            // add(name, x, y [, opts{color={r,g,b[,a]}, onmap=bool}]) -> ref | nil  (world coords; player marker)
            public Varargs invoke(Varargs a) {
                String nm = a.optjstring(1, null);
                if((nm == null) || !a.arg(2).isnumber() || !a.arg(3).isnumber())
                    return LuaValue.NIL;
                return addMarker(nm, a.arg(2).todouble(), a.arg(3).todouble(), a.arg(4));
            }
        });
        markers.set("remove", new OneArgFunction() {
            public LuaValue call(LuaValue ref) {
                return LuaValue.valueOf(removeMarker(ref));
            }
        });
        hafen.set("markers", markers);
    }

    /** Build {@code hafen.radar} for {@code owner}. From installHafen. */
    static void installRadar(LuaTable hafen, final Addon owner) {
        LuaTable radar = new LuaTable();
        radar.set("categories", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                LuaTable out = new LuaTable();
                int i = 0;
                for(LuaValue snap : radarSnapshots()) {
                    if(matches(filter, snap))
                        out.set(++i, snap);
                }
                return out;
            }
        });
        radar.set("setVisible", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue on) {
                return LuaValue.valueOf(radarSet(filter, on.toboolean(), false));
            }
        });
        radar.set("setNotify", new TwoArgFunction() {
            public LuaValue call(LuaValue filter, LuaValue on) {
                return LuaValue.valueOf(radarSet(filter, on.toboolean(), true));
            }
        });
        hafen.set("radar", radar);
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

    /** Build {@code hafen.music} for {@code owner}. From installHafen. */
    static void installMusic(LuaTable hafen, final Addon owner) {
        LuaTable music = new LuaTable();
        music.set("play", new TwoArgFunction() {
            public LuaValue call(LuaValue resname, LuaValue loop) {
                if(!resname.isstring() || resname.tojstring().isEmpty()) {
                    Music.play(null, false);            // stop
                } else {
                    Music.play(Resource.remote().load(resname.tojstring()), loop.optboolean(false));
                }
                return LuaValue.NIL;
            }
        });
        hafen.set("music", music);
    }

    /** Session init: drop the per-session marker-ref maps + re-prime MarkersChanged (from AddonManager.init). */
    static void resetMarkers() {
        synchronized(markerById) {
            markerIds.clear();
            markerById.clear();
        }
        markersPrimed = false;
    }

    // ---- markers (A1: hafen.markers) -------------------------------------------------------------
    // Client-side map markers live in the on-disk map DB (MapFile), owned by the map window / corner
    // minimap (both hold the same MapFile). A marker's PERSISTENT identity is its segment id + segment
    // tile coord (survives a relog — coverage-gaps C4); the world x,y/dist a snapshot also carries are
    // SESSION-LOCAL conveniences, present only when the marker is in the player's current segment. Reads
    // copy the marker list under the MapFile read lock (it is mutated on loader threads — server markobj
    // adds, segment merges), then build snapshots outside the lock (the OCache gob-read discipline). Adds/
    // removes go straight to the shared DB and persist. MarkersChanged is fired by pollMarkers() when
    // MapFile.markerseq changes (a marker add/remove is not a uimsg — poll it, like buffs/study).

    private static final java.awt.Color DEFAULT_MARKER_COLOR = new java.awt.Color(255, 215, 0);  // gold pin

    /** Facade-safe marker refs (P1: no Java Marker crosses to Lua). Per-session (Marker identity is per-session). */
    private static final IdentityHashMap<MapFile.Marker, Long> markerIds = new IdentityHashMap<MapFile.Marker, Long>();
    private static final Map<Long, MapFile.Marker> markerById = new HashMap<Long, MapFile.Marker>();
    private static long markerIdSeq = 0;

    /** MarkersChanged is primed (not fired) the first time the map DB is seen, then fired on each markerseq change. */
    private static boolean markersPrimed = false;
    private static int lastMarkerSeq = 0;

    /** The client's on-disk map DB (markers/segments), or null before the HUD/map is up. */
    private static MapFile mapfile() {
        GameUI g = gui();
        if(g == null)
            return null;
        if(g.mapfile != null)          // the big Map window (MapWnd.file)
            return g.mapfile.file;
        MiniMap mm = g.mmap;            // fall back to the corner minimap (same MapFile instance)
        return (mm == null) ? null : mm.file;
    }

    /**
     * The session location — the segment plus the segment-tile-coord of session tile (0,0): the bridge
     * between session-local WORLD coords and the persistent segment coords markers store (world→segment =
     * sessloc.tc + floor(world/tilesz), mirroring {@code MapWnd.FindMark.hit}). Resolved live by the
     * corner minimap; null until the map grid-info has streamed in (a beat after enter-world).
     */
    private static MiniMap.Location sessloc() {
        GameUI g = gui();
        MiniMap mm = (g == null) ? null : g.mmap;
        return (mm == null) ? null : mm.sessloc;
    }

    /** Assign (or look up) a stable per-session ref id for a marker. Touched from UI + REPL threads → guarded. */
    private static long markerId(MapFile.Marker m) {
        synchronized(markerById) {
            Long id = markerIds.get(m);
            if(id == null) {
                id = Long.valueOf(++markerIdSeq);
                markerIds.put(m, id);
                markerById.put(id, m);
            }
            return id.longValue();
        }
    }
    private static MapFile.Marker markerByRef(long id) {
        synchronized(markerById) {
            return markerById.get(Long.valueOf(id));
        }
    }

    /** Snapshots of every marker in the DB (list copied under the read lock, snapshots built outside it). */
    private static List<LuaValue> markerSnapshots() {
        List<LuaValue> out = new ArrayList<LuaValue>();
        MapFile file = mapfile();
        if(file == null)
            return out;
        List<MapFile.Marker> copy = new ArrayList<MapFile.Marker>();
        file.lock.readLock().lock();
        try {
            copy.addAll(file.markers);
        } finally {
            file.lock.readLock().unlock();
        }
        MiniMap.Location sl = sessloc();
        Coord2d prc = playerPos();   // player world pos (may be null before the player gob is up)
        for(MapFile.Marker m : copy)
            out.add(markerSnapshot(m, sl, prc));
        return out;
    }

    /**
     * One marker → a Lua snapshot: {@code {id, name, type, seg, tc}} plus type-specific fields
     * ({@code color}/{@code onmap} for a player marker, {@code icon} for a system marker) and, when the
     * marker shares the player's current segment, the session-local {@code x,y} (world, tile centre) +
     * {@code dist} (from the player). {@code seg} is a decimal string (64-bit id); {@code tc} the segment
     * tile coord — those two are the persistent anchor, {@code x,y}/{@code dist} the session convenience.
     */
    private static LuaValue markerSnapshot(MapFile.Marker m, MiniMap.Location sl, Coord2d prc) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)markerId(m)));
        if(m.nm != null)
            t.set("name", LuaValue.valueOf(m.nm));
        t.set("seg", LuaValue.valueOf(Long.toString(m.seg)));   // 64-bit segment id (local anchor) → string
        t.set("tc", xy(m.tc.x, m.tc.y));                        // segment tile coord (the persistent position)
        if(m instanceof MapFile.PMarker) {
            MapFile.PMarker pm = (MapFile.PMarker)m;
            t.set("type", LuaValue.valueOf("player"));
            if(pm.color != null)
                t.set("color", color(pm.color));
            t.set("onmap", LuaValue.valueOf(pm.onmap));
        } else if(m instanceof MapFile.SMarker) {
            MapFile.SMarker sm = (MapFile.SMarker)m;
            t.set("type", LuaValue.valueOf("system"));
            if((sm.res != null) && (sm.res.name != null))
                t.set("icon", LuaValue.valueOf(sm.res.name));
        }
        // Session-local WORLD position (tile centre) + distance — only when the marker shares the player's
        // segment (a marker in another explored area has no valid world coord this session).
        if((sl != null) && (m.seg == sl.seg.id)) {
            double wx = ((m.tc.x - sl.tc.x) * MCache.tilesz.x) + (MCache.tilesz.x / 2);
            double wy = ((m.tc.y - sl.tc.y) * MCache.tilesz.y) + (MCache.tilesz.y / 2);
            t.set("x", LuaValue.valueOf(wx));
            t.set("y", LuaValue.valueOf(wy));
            if(prc != null)
                t.set("dist", LuaValue.valueOf(Math.hypot(wx - prc.x, wy - prc.y)));
        }
        return t;
    }

    /** add(name, worldX, worldY, opts) — create a PLAYER marker at a world position; returns its ref or nil. */
    private static LuaValue addMarker(String nm, double wx, double wy, LuaValue opts) {
        MapFile file = mapfile();
        MiniMap.Location sl = sessloc();
        if((file == null) || (sl == null))
            return LuaValue.NIL;                  // map / session location not up yet
        // world → segment tile coord (mirrors MapWnd.FindMark.hit: sessloc.tc + floor(world / tilesz)).
        Coord segTc = sl.tc.add(Coord2d.of(wx, wy).floor(MCache.tilesz));
        java.awt.Color col = DEFAULT_MARKER_COLOR;
        boolean onmap = false;
        if((opts != null) && opts.istable()) {
            LuaValue c = opts.get("color");
            if(c.istable())
                col = luaColor(c, col);
            LuaValue om = opts.get("onmap");
            if(!om.isnil())
                onmap = om.toboolean();
        }
        MapFile.PMarker pm = new MapFile.PMarker(file, sl.seg.id, segTc, nm, col, onmap);
        file.add(pm);                             // takes the write lock, persists (defersave), bumps markerseq
        return LuaValue.valueOf((double)markerId(pm));
    }

    /** remove(ref) — remove a marker by the ref id list()/add() handed out. Returns whether it was removed. */
    private static boolean removeMarker(LuaValue ref) {
        if(!ref.isnumber())
            return false;
        MapFile file = mapfile();
        if(file == null)
            return false;
        MapFile.Marker m = markerByRef((long)ref.todouble());
        if(m == null)
            return false;
        file.remove(m);                           // no-ops if already gone; bumps markerseq if it removed one
        return true;
    }

    /** Fire MarkersChanged when the DB's markerseq changes (a marker add/remove is not a uimsg — poll it). */
    static void pollMarkers() {
        MapFile file = mapfile();
        if(file == null)
            return;
        int seq = file.markerseq;
        if(!markersPrimed) {
            markersPrimed = true;
            lastMarkerSeq = seq;                  // prime silently; the initial set is read via markers.list()
            return;
        }
        if(seq != lastMarkerSeq) {
            lastMarkerSeq = seq;
            int count;
            file.lock.readLock().lock();
            try { count = file.markers.size(); }
            finally { file.lock.readLock().unlock(); }
            LuaTable ev = new LuaTable();
            ev.set("count", LuaValue.valueOf(count));
            fire("MarkersChanged", ev);
        }
    }

    // ---- radar (A2: hafen.radar) -----------------------------------------------------------------
    // The minimap icon registry (GobIcon.Settings, GameUI.iconconf) — one "category" per gob-icon kind
    // (a boar, a fir tree, a player, …), each with a show flag (draw it on the minimap) and a notify flag
    // (sound + chat msg when one appears). This is the same registry the in-client "Icon settings" window
    // edits, so our reads/writes are the exact ones it uses (its checkboxes flip set.show/set.notify and
    // call dsave(); we do the same). settings is a Map the loader thread swaps WHOLESALE (it builds a fresh
    // map and assigns it), so a local reference is a stable snapshot to iterate; individual boolean flags
    // may be written on the UI thread (as the checkboxes already do) with no torn read. All access is on the
    // UI thread (addon tick / REPL), matching the settings window. No *Changed event — categories change
    // only as new icon types are seen (rare); read on demand (the A4 precedent for rarely-changing data).

    /** The character's minimap icon registry (GameUI.iconconf), or null before the HUD is up. */
    private static GobIcon.Settings iconconf() {
        GameUI g = gui();
        return (g == null) ? null : g.iconconf;
    }

    /** Snapshots of every radar category (icon setting) in the registry. */
    private static List<LuaValue> radarSnapshots() {
        List<LuaValue> out = new ArrayList<LuaValue>();
        GobIcon.Settings conf = iconconf();
        if(conf == null)
            return out;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;   // swapped wholesale by the loader → a stable ref
        if(m == null)
            return out;
        for(GobIcon.Setting set : m.values())
            out.add(radarSnapshot(set));
        return out;
    }

    /** One category snapshot: { name, res, show, notify }. */
    private static LuaValue radarSnapshot(GobIcon.Setting set) {
        LuaTable t = new LuaTable();
        t.set("name", LuaValue.valueOf(radarName(set)));
        t.set("res", LuaValue.valueOf(set.id.res));
        t.set("show", LuaValue.valueOf(set.show));
        t.set("notify", LuaValue.valueOf(set.notify));
        return t;
    }

    /** The category's display name (the icon tooltip), falling back to the resource name; never throws. */
    private static String radarName(GobIcon.Setting set) {
        try {
            if(set.icon != null) {
                String nm = set.icon.name();
                if(nm != null)
                    return nm;
            }
        } catch(RuntimeException e) {   // Loading, or a custom mapicon name() that blows up → fall back to res
        }
        return set.id.res;
    }

    /** Flip show (notifyFlag=false) or notify (true) on every category the filter matches; persist if any
     *  actually changed. Returns the number of categories matched. UI thread (like the settings checkboxes). */
    private static int radarSet(LuaValue filter, boolean value, boolean notifyFlag) {
        return radarSetIn(iconconf(), filter, value, notifyFlag);
    }

    /** Testable core of {@link #radarSet}: operates on a given Settings (null-safe), so it can be exercised
     *  headlessly without a live GameUI. */
    static int radarSetIn(GobIcon.Settings conf, LuaValue filter, boolean value, boolean notifyFlag) {
        if(conf == null)
            return 0;
        Map<GobIcon.Setting.ID, GobIcon.Setting> m = conf.settings;
        if(m == null)
            return 0;
        int matched = 0;
        boolean changed = false;
        for(GobIcon.Setting set : m.values()) {
            if(!matches(filter, radarSnapshot(set)))
                continue;
            matched++;
            if(notifyFlag) {
                if(set.notify != value) { set.notify = value; changed = true; }
            } else {
                if(set.show != value) { set.show = value; changed = true; }
            }
        }
        if(changed)
            conf.dsave();   // debounced persist, exactly like the icon-settings checkboxes (andsave -> dsave)
        return matched;
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
