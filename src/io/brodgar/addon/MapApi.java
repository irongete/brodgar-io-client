package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.GobIcon;
import haven.Indir;
import haven.MapFile;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static io.brodgar.addon.AddonManager.*;

/**
 * {@code hafen.map} — the <b>RECORDED</b> map: the client's on-disk map database ({@link MapFile}), the map the
 * player has <i>explored</i>, as opposed to the live terrain streamed around them ({@link haven.MCache}, which is
 * {@code hafen.world}). Spec {@code 037-map-database}.
 *
 * <p><b>037.1 is the restructure and nothing else.</b> The thirteen terrain/coordinate functions left for
 * {@code hafen.world} ({@link WorldApi#installWorld}) and this namespace took the two surfaces that were always
 * reading the map database while sitting beside it as namespaces of their own — as <b>relations</b>, D-066:
 *
 * <ul>
 *   <li>{@code hafen.map.markers} — the marker DB (the old {@code hafen.markers}, same surface), plus the
 *       {@code MarkersChanged} poll. Its fields and {@link #pollMarkers}/{@link #resetMarkers} moved here with it.</li>
 *   <li>{@code hafen.map.icons} — the minimap icon registry (the old {@code hafen.radar}; the engine has no
 *       "radar", it has {@link GobIcon.Settings} — D-061), re-shaped from two filter-mutators into a callable
 *       namespace over {@link LuaIconCat} entities (D-056).</li>
 * </ul>
 *
 * <p><b>037.2 opened the database itself</b>: {@code hafen.map.segment()}/{@code segments()}/{@code grid(id)},
 * the {@link LuaSegment} and {@link LuaMapGrid} entities, and the <b>anchor bridge</b> both ways — a
 * {@code {gridId, x, y}} anchor resolves into the recorded map through {@link #gridInfoIn}, and a marker
 * converts out of it through {@code marker:anchor()} ({@link LuaMarker}). The overlays and imagery are 037.3–037.4.
 *
 * <p><b>One load model, everywhere: kick the load, answer nil.</b> The database is on disk and resolves
 * through {@link haven.Defer}/{@link Indir}, so a read that needs a grid the client has not loaded yet
 * <b>starts the load and answers {@code nil}</b> — the caller reads again next tick. Nothing blocks and
 * nothing throws {@link haven.Loading} into Lua. The lock is taken the way {@code MiniMap.resolve} takes it,
 * with a {@code tryLock}: the map file's write lock is held across disk I/O on the {@code MapFile} processor
 * thread, and waiting for it would stall the UI thread for a read that is allowed to answer nil anyway.
 */
final class MapApi {
    private MapApi() {}

    /** Build {@code hafen.map} for {@code owner}. From installHafen. */
    static void installMap(LuaTable hafen, final Addon owner) {
        LuaTable map = new LuaTable();
        map.set("markers", markers(owner));
        map.set("icons", LuaIconCat.factory(owner));
        installDatabase(map, owner);      // 037.2: segments and grids
        hafen.set("map", map);
    }

    /**
     * The database half of {@code hafen.map} (037.2): the three doors into {@link MapFile}'s own structure.
     *
     * <ul>
     *   <li>{@code segment()} — the segment the player is standing in (the {@code MiniMap} session location),
     *       {@code nil} until the map has streamed in; {@code segment(id)} — one segment by its decimal-string
     *       id, {@code nil} when the database does not carry it.</li>
     *   <li>{@code segments()} — every segment the character has explored, in id order.</li>
     *   <li>{@code grid(gridId)} — the <b>anchor bridge</b>: the recorded grid for a <i>server</i> grid id, the
     *       one in a {@code hafen.world.gridPos()} anchor. This is the only door that crosses from the live
     *       world into the database, because the grid id is the only thing the two halves share.</li>
     * </ul>
     */
    private static void installDatabase(LuaTable map, final Addon owner) {
        map.set("segment", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                if(id.isnil()) {
                    MiniMap.Location sl = sessloc();
                    return (sl == null) ? LuaValue.NIL : LuaSegment.of(owner, sl.seg.id);
                }
                long sid = idArg(id, "hafen.map.segment(id)", "segment");
                return (segIn(mapfile(), sid) == null) ? LuaValue.NIL : LuaSegment.of(owner, sid);
            }
        });
        map.set("segments", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 0;
                for(Long id : knownSegs())
                    out.set(++i, LuaSegment.of(owner, id.longValue()));
                return out;
            }
        });
        map.set("grid", new OneArgFunction() {
            public LuaValue call(LuaValue id) {
                long gid = idArg(id, "hafen.map.grid(gridId)", "grid");
                return (gridInfoIn(mapfile(), gid) == null) ? LuaValue.NIL : LuaMapGrid.of(owner, gid);
            }
        });
    }

    /**
     * Parse a 64-bit id argument — a segment id or a grid id — from Lua. They are <b>decimal strings</b>
     * because they do not fit in a double (&gt;2⁵³), so a <i>number</i> is refused loudly rather than
     * silently rounded to a neighbouring id: that is the one mistake here that would answer plausibly and
     * be wrong. Note the {@code type()} test — in LuaJ a numeric <i>string</i> also answers
     * {@code isnumber()}, so {@code "1234"} must still be accepted.
     */
    static long idArg(LuaValue v, String where, String what) {
        if(v.type() == LuaValue.TNUMBER)
            throw new LuaError(where + ": a " + what + " id is a decimal STRING, not a number"
                + " — a 64-bit id does not survive a Lua number");
        if(v.type() != LuaValue.TSTRING)
            throw new LuaError(where + ": expected a " + what + " id string, got " + v.typename());
        try {
            return Long.parseLong(v.tojstring());
        } catch(NumberFormatException e) {
            throw new LuaError(where + ": \"" + v.tojstring() + "\" is not a decimal " + what + " id");
        }
    }

    /** {@code hafen.map.markers} — the marker half of the DB (the surface 037.1 moved; entities since 037.2). */
    private static LuaTable markers(final Addon owner) {
        LuaTable markers = new LuaTable();
        markers.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return LuaMarker.collection(owner, null, filter);
            }
        });
        markers.set("nearest", new OneArgFunction() {
            public LuaValue call(LuaValue filter) {
                return LuaMarker.nearest(owner, filter);
            }
        });
        markers.set("add", new VarArgFunction() {
            // add(name, x, y [, opts{color={r,g,b[,a]}, onmap=bool}]) -> Marker | nil  (world coords; player marker)
            public Varargs invoke(Varargs a) {
                String nm = a.optjstring(1, null);
                if((nm == null) || !a.arg(2).isnumber() || !a.arg(3).isnumber())
                    return LuaValue.NIL;
                return addMarker(owner, nm, a.arg(2).todouble(), a.arg(3).todouble(), a.arg(4));
            }
        });
        markers.set("remove", new OneArgFunction() {
            public LuaValue call(LuaValue marker) {
                return LuaValue.valueOf(removeMarker(marker));
            }
        });
        return markers;
    }

    /** Session init: drop the per-session marker-ref maps + re-prime MarkersChanged (from AddonManager.init). */
    static void resetMarkers() {
        synchronized(markerById) {
            markerIds.clear();
            markerById.clear();
        }
        markersPrimed = false;
    }

    // ---- markers (hafen.map.markers) --------------------------------------------------------------
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
    static MapFile mapfile() {
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
    static MiniMap.Location sessloc() {
        GameUI g = gui();
        MiniMap mm = (g == null) ? null : g.mmap;
        return (mm == null) ? null : mm.sessloc;
    }

    /** Assign (or look up) a stable per-session ref id for a marker. Touched from UI + REPL threads → guarded. */
    static long markerId(MapFile.Marker m) {
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
    static MapFile.Marker markerByRef(long id) {
        synchronized(markerById) {
            return markerById.get(Long.valueOf(id));
        }
    }

    /**
     * The markers in the DB — the whole list, or only those in one segment ({@code seg != null}) — copied
     * under the read lock, which is where the copy has to happen: a segment merge rewrites markers in place
     * on a loader thread. Unlike the segment/grid reads this one <b>waits</b> for the lock rather than
     * answering nil: an empty marker list and "the lock was busy" are indistinguishable to a caller, so
     * answering nil here would be a lie where answering nil about a grid is the documented load model.
     */
    static List<MapFile.Marker> markerList(Long seg) {
        List<MapFile.Marker> out = new ArrayList<MapFile.Marker>();
        MapFile file = mapfile();
        if(file == null)
            return out;
        file.lock.readLock().lock();
        try {
            for(MapFile.Marker m : file.markers) {
                if((seg == null) || (m.seg == seg.longValue()))
                    out.add(m);
            }
        } finally {
            file.lock.readLock().unlock();
        }
        return out;
    }

    /** Is this marker still in the DB? (An entity outlives the marker it names — a removal is not a destroy.) */
    static boolean markerLives(MapFile.Marker m) {
        MapFile file = mapfile();
        if((file == null) || (m == null))
            return false;
        file.lock.readLock().lock();
        try {
            for(MapFile.Marker o : file.markers) {
                if(o == m)
                    return true;
            }
            return false;
        } finally {
            file.lock.readLock().unlock();
        }
    }

    /** add(name, worldX, worldY, opts) — create a PLAYER marker at a world position; returns it, or nil. */
    private static LuaValue addMarker(Addon owner, String nm, double wx, double wy, LuaValue opts) {
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
        return LuaMarker.of(owner, markerId(pm));
    }

    /** remove(marker) — remove a marker the API handed out. Returns whether one was removed. */
    private static boolean removeMarker(LuaValue marker) {
        LuaMarker h = LuaMarker.resolve(marker);
        MapFile file = mapfile();
        if((h == null) || (file == null))
            return false;
        MapFile.Marker m = markerByRef(h.ref);
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

    // ---- icons (hafen.map.icons) ------------------------------------------------------------------
    // The minimap icon registry (GobIcon.Settings, GameUI.iconconf) — one "category" per gob-icon kind
    // (a boar, a fir tree, a player, …), each with a show flag (draw it on the minimap) and a notify flag
    // (sound + chat msg when one appears). This is the same registry the in-client "Icon settings" window
    // edits, so our reads/writes are the exact ones it uses (its checkboxes flip set.show/set.notify and
    // call dsave(); we do the same). settings is a Map the loader thread swaps WHOLESALE (it builds a fresh
    // map and assigns it), so a local reference is a stable snapshot to iterate; individual boolean flags
    // may be written on the UI thread (as the checkboxes already do) with no torn read. All access is on the
    // UI thread (addon tick / REPL), matching the settings window. No *Changed event — categories change
    // only as new icon types are seen (rare); read on demand (the A4 precedent for rarely-changing data).
    // The entity half is LuaIconCat; this is the one accessor it (and nothing else) resolves through.

    /** The character's minimap icon registry (GameUI.iconconf), or null before the HUD is up. */
    static GobIcon.Settings iconconf() {
        GameUI g = gui();
        return (g == null) ? null : g.iconconf;
    }

    // ---- segments and grids (hafen.map.segment / segments / grid) ---------------------------------
    // The database's own structure, 037.2. A SEGMENT is one contiguous explored area (the map window's
    // "map"); a GRID is one 100x100-tile square inside it, keyed by the id the SERVER gave it. The two
    // coordinate spaces are therefore different in kind: a grid id is the same number for every player and
    // no merge ever moves it (it is the anchor an addon saves or sends), while a segment id is
    // rnd.nextLong() minted by this client and a merge re-bases the loser's grids and rewrites every
    // marker in it — so segment + tile coord is a READ-ONLY VIEW, never a stored position (spec 037).
    //
    // Every read below is under the map file's READ lock, taken with tryLock and never waited on: the
    // MapFile processor thread holds the WRITE lock across disk I/O (segment saves, the index), and the UI
    // thread must not stall on it for a read whose whole contract is that it may answer nil. A grid's DATA
    // resolves through Defer (Segment.grid(...) hands back an Indir that throws Loading until the disk read
    // lands), so gridDataIn kicks the load and answers null until the next call — the one load model, and
    // the reason not one read here takes a callback (a minimap panel walks a dozen grids per frame; that
    // would be a callback tree, plan.md).

    /** The recorded {@link MapFile.Segment} for an id — null when unknown, busy, or the DB is not up. */
    static MapFile.Segment segIn(MapFile file, long id) {
        if((file == null) || !file.lock.readLock().tryLock())
            return null;
        try {
            return file.segments.get(Long.valueOf(id));
        } catch(RuntimeException e) {      // a torn/absent segment file warns and answers null
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
    }

    /** Where a grid id sits: its segment and its grid coord inside it. The whole live→recorded bridge. */
    static MapFile.GridInfo gridInfoIn(MapFile file, long id) {
        if((file == null) || !file.lock.readLock().tryLock())
            return null;
        try {
            return file.gridinfo.get(Long.valueOf(id));
        } catch(RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
    }

    /** Every segment the character has explored, in id order (a copy — {@code knownsegs} is mutated live). */
    static List<Long> knownSegs() {
        List<Long> out = new ArrayList<Long>();
        MapFile file = mapfile();
        if((file == null) || !file.lock.readLock().tryLock())
            return out;
        try {
            out.addAll(file.knownsegs);
        } finally {
            file.lock.readLock().unlock();
        }
        Collections.sort(out);
        return out;
    }

    /**
     * The loaded grid DATA for a grid id, or null — <b>kicking the load</b> on the way. The {@link Indir} is
     * taken under the lock ({@code Segment.grid} asserts the caller holds it) and read <i>outside</i> it:
     * the loader task itself takes the read lock, so holding ours across the wait would be pointless, and
     * {@code get()} throws {@link haven.Loading} until {@link haven.Defer} has the grid off the disk.
     */
    static MapFile.Grid gridDataIn(MapFile file, long id) {
        MapFile.GridInfo gi = gridInfoIn(file, id);
        if(gi == null)
            return null;
        MapFile.Segment seg = segIn(file, gi.seg);
        if(seg == null)
            return null;
        Indir<MapFile.Grid> ind;
        if(!file.lock.readLock().tryLock())
            return null;
        try {
            ind = seg.grid(id);
        } catch(RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
        return read(ind);
    }

    /** The loaded grid at a segment grid coord, or null (kicking the load). Null coord = null grid. */
    static MapFile.Grid gridAtIn(MapFile file, MapFile.Segment seg, Coord sc) {
        if((file == null) || (seg == null) || (sc == null) || !file.lock.readLock().tryLock())
            return null;
        Indir<MapFile.Grid> ind;
        try {
            ind = seg.grid(sc);
        } catch(RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
        return read(ind);
    }

    /** {@code Indir.get()} with the load model applied: Loading (or a broken grid file) is null, never a throw. */
    private static MapFile.Grid read(Indir<MapFile.Grid> ind) {
        if(ind == null)
            return null;
        try {
            return ind.get();
        } catch(RuntimeException e) {   // Loading until Defer lands it; an IOError if the file went away
            return null;
        }
    }

    /**
     * The world coordinate of a recorded grid's upper-left corner <b>in this session</b>, or nil — the
     * recorded→live direction of the bridge. It is pure {@code sessloc} arithmetic (segment tile =
     * session tile + {@code sessloc.tc}, the conversion a marker's {@code x,y} already uses), so it answers
     * for any grid in the player's current segment whether or not that ground is streamed in right now;
     * a grid in a segment the player is not standing in has no world coordinate this session at all.
     */
    static LuaValue gridWorldUL(MapFile.GridInfo gi) {
        MiniMap.Location sl = sessloc();
        if((gi == null) || (sl == null) || (gi.seg != sl.seg.id))
            return LuaValue.NIL;
        return xy(((gi.sc.x * MCache.cmaps.x) - sl.tc.x) * MCache.tilesz.x,
                  ((gi.sc.y * MCache.cmaps.y) - sl.tc.y) * MCache.tilesz.y);
    }

    /**
     * A {@code {gridId, x, y}} anchor for the tile at within-grid tile coord {@code (gtx, gty)} of grid
     * {@code id} — the shape {@code hafen.world.gridPos} returns and {@code fromGridPos} accepts, so an
     * anchor read out of the map database goes straight back into the live world. {@code x,y} are the tile's
     * CENTRE in world units, which is the same point a marker's session {@code x,y} reports (so the round
     * trip lands on the tile it came from rather than on its corner).
     */
    static LuaValue anchor(long id, int gtx, int gty) {
        LuaTable t = new LuaTable();
        t.set("gridId", LuaValue.valueOf(Long.toString(id)));
        t.set("x", LuaValue.valueOf((gtx * MCache.tilesz.x) + (MCache.tilesz.x / 2)));
        t.set("y", LuaValue.valueOf((gty * MCache.tilesz.y) + (MCache.tilesz.y / 2)));
        return t;
    }

    /**
     * A within-grid tile coord argument ({@code grid:tile(c)}, {@code grid:height(c)}): a {@code {x,y}}
     * table, both integral and both inside the grid. Out of range is an <b>error</b>, not nil — nil already
     * means "not loaded yet" here, and a caller who confused a segment tile coord for a within-grid one
     * would read that as a load that never lands (D-072: refuse what can never mean anything).
     */
    static Coord tileArg(LuaValue c, String method) {
        if((c == null) || !c.istable() || !c.get("x").isnumber() || !c.get("y").isnumber())
            throw new LuaError("grid:" + method + "(c): c is a within-grid tile coord {x=,y=}, 0.."
                + (MCache.cmaps.x - 1));
        int ix = c.get("x").toint(), iy = c.get("y").toint();
        if((ix < 0) || (iy < 0) || (ix >= MCache.cmaps.x) || (iy >= MCache.cmaps.y))
            throw new LuaError("grid:" + method + "(c): " + ix + "," + iy + " is outside the grid — c is a"
                + " WITHIN-grid tile coord (0.." + (MCache.cmaps.x - 1) + "), not a segment tile coord");
        return Coord.of(ix, iy);
    }
}
