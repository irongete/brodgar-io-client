package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.GobIcon;
import haven.Indir;
import haven.Loading;
import haven.MapFile;
import haven.MapView;
import haven.MapWnd;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
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
 * converts out of it through {@code marker:anchor()} ({@link LuaMarker}). The imagery is 037.4.
 *
 * <p><b>037.3 added the overlays</b>, and they come in two halves that share only a word:
 *
 * <ul>
 *   <li>The <b>recorded masks</b> — {@code grid:overlays()} / {@code grid:overlay(tag)} ({@link LuaMask}),
 *       which tiles of a recorded grid a claim / village / province covered when the client wrote it down.
 *       The tag space here is <b>open</b>: an overlay's tags come from its own resource, so an unknown tag
 *       is simply not carried.</li>
 *   <li>The <b>display toggles</b> — {@code hafen.map.overlay(tag[, on])} / {@code hafen.map.overlays()},
 *       the three switches the client's own menu owns. That set is <b>closed</b> (D-072: refuse what can
 *       never mean anything), and a write is a <b>HOLD</b> rather than a switch — see {@link #take}.</li>
 * </ul>
 *
 * <p><b>037.4 added the imagery</b> — {@code grid:image(lvl)} and {@code grid:overlayImage(tag)}, the map
 * database's minimap drawings as ordinary image handles, rendered on {@link haven.Defer} and cached per addon.
 * That code lives in {@link MapImages}; nothing about it is a new load model, it is the same one at last
 * applied to something that takes milliseconds rather than microseconds to produce.
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
        installOverlays(map, owner);      // 037.3: the client's own display toggles
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
     * A within-grid tile coord argument ({@code grid:tile(c)}, {@code grid:height(c)}, {@code mask:covers(c)}):
     * a {@code {x,y}} table, both integral and both inside the grid. Out of range is an <b>error</b>, not nil
     * — nil already means "not loaded yet" here, and a caller who confused a segment tile coord for a
     * within-grid one would read that as a load that never lands (D-072: refuse what can never mean anything).
     * {@code method} is the whole receiver-and-verb label ({@code "grid:tile"}, {@code "mask:covers"}).
     */
    static Coord tileArg(LuaValue c, String method) {
        if((c == null) || !c.istable() || !c.get("x").isnumber() || !c.get("y").isnumber())
            throw new LuaError(method + "(c): c is a within-grid tile coord {x=,y=}, 0.."
                + (MCache.cmaps.x - 1));
        int ix = c.get("x").toint(), iy = c.get("y").toint();
        if((ix < 0) || (iy < 0) || (ix >= MCache.cmaps.x) || (iy >= MCache.cmaps.y))
            throw new LuaError(method + "(c): " + ix + "," + iy + " is outside the grid — c is a"
                + " WITHIN-grid tile coord (0.." + (MCache.cmaps.x - 1) + "), not a segment tile coord");
        return Coord.of(ix, iy);
    }

    // ---- recorded overlay masks (grid:overlays / grid:overlay -> LuaMask) --------------------------
    // A recorded grid carries, beside its tiles and heights, a MASK per overlay that covered it when the
    // client wrote it down: MapFile.Overlay = (the overlay's own resource, a boolean[100*100]). What a
    // player calls "claims" is a TAG on that resource (MCache.ResOverlay.tags()), and several resources may
    // share one tag — which is why a read here is the UNION of every overlay carrying the tag, exactly what
    // DataGrid.olrender(off, tag) composites onto one image. The tag space is the RESOURCES', not ours: a
    // tag a grid does not carry is nil, never an error (grid:overlays() is the census that makes the nil
    // readable). Resolving an overlay resource may throw Loading and must never happen under the map file's
    // lock — gridDataIn hands the grid back outside it, and every read below runs on the UI thread from there.

    /**
     * The tags of one recorded overlay — {@code null} while its resource is still coming (the one load
     * model: the {@code get()} kicks the load and the next call answers), an empty list for a resource that
     * is broken or carries no overlay layer at all.
     */
    private static Collection<String> olTags(MapFile.Overlay ol) {
        try {
            return ol.olid.get().flayer(MCache.ResOverlay.class).tags();
        } catch(Loading l) {
            return null;
        } catch(RuntimeException e) {   // NoSuchLayerException, a load error — it carries no tags for us
            return Collections.<String>emptyList();
        }
    }

    /**
     * Every overlay tag a recorded grid carries, sorted — or {@code null} until the grid (and every overlay
     * resource on it) is resolved, because a census that is short by one is worse than no census.
     */
    static List<String> gridTags(MapFile file, long id) {
        return tagsOf(gridDataIn(file, id));
    }

    /** {@link #gridTags} over a grid already in hand — the whole decision, with no map file in it. */
    static List<String> tagsOf(MapFile.DataGrid g) {
        if(g == null)
            return null;
        List<String> out = new ArrayList<String>();
        for(MapFile.Overlay ol : g.ols) {
            Collection<String> tags = olTags(ol);
            if(tags == null)
                return null;
            for(String t : tags) {
                if((t != null) && !out.contains(t))
                    out.add(t);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * The mask for one tag on one recorded grid — the <b>union</b> of every overlay on it whose resource
     * carries that tag — or {@code null} for a tag the grid does not carry, a grid still coming off the
     * disk, or an overlay resource still resolving. All three are the same {@code nil} to Lua by design:
     * ask again next tick, and {@code grid:overlays()} says which tags are there.
     */
    static boolean[] maskIn(MapFile file, long id, String tag) {
        return maskOf(gridDataIn(file, id), tag);
    }

    /** {@link #maskIn} over a grid already in hand — the union rule itself, testable without a map file. */
    static boolean[] maskOf(MapFile.DataGrid g, String tag) {
        if((g == null) || (tag == null))
            return null;
        boolean[] out = null;
        for(MapFile.Overlay ol : g.ols) {
            Collection<String> tags = olTags(ol);
            if(tags == null)
                return null;                  // still resolving: a partial union would under-report
            if(!tags.contains(tag) || (ol.ol == null))
                continue;
            if(out == null)
                out = new boolean[MCache.cmaps.x * MCache.cmaps.y];
            for(int i = 0, n = Math.min(out.length, ol.ol.length); i < n; i++) {
                if(ol.ol[i])
                    out[i] = true;
            }
        }
        return out;
    }

    // ---- the client's display toggles (hafen.map.overlay / overlays) -------------------------------
    // The three switches the client's own map menu owns, and they live on TWO sides with TWO vocabularies:
    // the 3D world draws cplot/vlg/prov through MapView's ref-counted oltags (enol/disol/visol), while the
    // map window draws provinces from the RECORDED masks under the tag "realm" (MapWnd.overlays, a set).
    // Same feature to a player, different tag in the engine — the one gotcha this half exists to name.
    //
    // D-097: A WRITE IS A HOLD, NOT A SWITCH. MapView.oltags is a MULTISET shared with the client's own
    // checkbox and with the server's flashol, so "off" is not a state an addon can express: it can only
    // stop asking. hafen.map.overlay(tag, true) takes this addon's hold (idempotent — one per addon per
    // tag, or a single release would leave the count standing), false releases it, and teardown releases
    // every hold exactly once. The READ is the client's own answer — is this displayed at all — never
    // "do I hold it"; that is what hafen.map.overlays()'s `held` field is for.

    /** The toggles the client itself owns: {tag, side, what it shows}. The set is closed (D-072). */
    static final String[][] TOGGLES = {
        {"cplot", "world", "personal claims"},
        {"vlg",   "world", "village claims"},
        {"prov",  "world", "provinces, in the world"},
        {"realm", "map",   "provinces, on the map"},
    };

    /** One addon's hold on one display toggle — the {@code hiddenNative} shape, one subsystem along. */
    static final class Hold {
        final String tag;
        /** {@code realm}: the map window's tag SET (so the stock value matters); otherwise MapView's refcount. */
        final boolean recorded;
        /** Recorded side only: was the tag already in the set when this hold was taken? */
        final boolean stock;
        /** The {@code MapView} / {@code MapWnd} the hold was taken on — weak, so a relog cannot pin the scene. */
        final WeakReference<Object> on;

        Hold(String tag, boolean recorded, boolean stock, Object on) {
            this.tag = tag;
            this.recorded = recorded;
            this.stock = stock;
            this.on = new WeakReference<Object>(on);
        }
    }

    /** The toggle row for a tag, or null if the client has no such switch. */
    static String[] toggle(String tag) {
        for(String[] t : TOGGLES) {
            if(t[0].equals(tag))
                return t;
        }
        return null;
    }

    /** Is this tag the RECORDED (map-window) one? Only {@code realm} is; the rest are the live world's. */
    private static boolean recorded(String tag) {
        String[] t = toggle(tag);
        return (t != null) && "map".equals(t[1]);
    }

    /** The live 3D map view (the {@code cplot}/{@code vlg}/{@code prov} side), or null before the HUD is up. */
    static MapView mapview() {
        GameUI g = gui();
        return (g == null) ? null : g.map;
    }

    /** The map window (the {@code realm} side), or null before the HUD is up. */
    static MapWnd mapwnd() {
        GameUI g = gui();
        return (g == null) ? null : g.mapfile;
    }

    /** The side that owns a tag, or null when it is not up yet — the one place the two are told apart. */
    private static Object sideOf(String tag) {
        return recorded(tag) ? (Object)mapwnd() : (Object)mapview();
    }

    /** Is this overlay displayed right now — by anyone? {@code null} when its side is not up. */
    static Boolean displayed(String tag) {
        if(recorded(tag)) {
            MapWnd w = mapwnd();
            return (w == null) ? null : Boolean.valueOf(w.overlays.contains(tag));
        }
        MapView m = mapview();
        return (m == null) ? null : Boolean.valueOf(m.visol(tag));
    }

    /** Does {@code owner} hold this tag right now? (The record, not the screen.) */
    static boolean held(Addon owner, String tag) {
        return holdIn(owner, tag) != null;
    }

    private static Hold holdIn(Addon owner, String tag) {
        if(owner == null)
            return null;
        List<Hold> hs = owner.overlayHolds;
        for(int i = 0, n = hs.size(); i < n; i++) {
            if(hs.get(i).tag.equals(tag))
                return hs.get(i);
        }
        return null;
    }

    /**
     * Take {@code owner}'s hold on a display toggle. <b>Idempotent</b>: an addon holds a tag once or not at
     * all, because the release is a single {@code disol} and a second {@code enol} would leave the refcount
     * standing forever. On the recorded side the hold also remembers whether the tag was <i>already</i> in
     * the map window's set, so releasing it cannot switch off what the user's own checkbox turned on.
     */
    static void take(Addon owner, String tag) {
        if((owner == null) || (holdIn(owner, tag) != null))
            return;
        Object side = sideOf(tag);
        if(side == null)
            return;                       // the HUD is not up: nothing to hold, and nothing recorded
        if(side instanceof MapWnd) {
            MapWnd w = (MapWnd)side;
            boolean stock = w.overlays.contains(tag);
            owner.overlayHolds.add(new Hold(tag, true, stock, w));
            if(!stock)
                w.overlays.add(tag);
        } else {
            MapView m = (MapView)side;
            owner.overlayHolds.add(new Hold(tag, false, false, m));
            m.enol(tag);                  // +1 on the multiset — never an assignment
        }
    }

    /** Release {@code owner}'s hold, if it has one. A release without a hold is a no-op, never someone else's -1. */
    static void release(Addon owner, String tag) {
        Hold h = holdIn(owner, tag);
        if(h == null)
            return;
        owner.overlayHolds.remove(h);
        apply(h);
    }

    /**
     * The undo of one hold, guarded on the side still being the <b>same live one</b> — a relog builds a new
     * {@code MapView}/{@code MapWnd} whose overlay state was never ours, exactly as {@code teardownHidden}
     * guards on the widget still being live.
     */
    private static void apply(Hold h) {
        Object was = h.on.get(), now = sideOf(h.tag);
        if((was == null) || (was != now))
            return;
        try {
            if(h.recorded) {
                if(!h.stock)
                    ((MapWnd)now).overlays.remove(h.tag);
            } else {
                ((MapView)now).disol(h.tag);
            }
        } catch(RuntimeException e) {      // best-effort: never abort a teardown
        }
    }

    /** Give back every display toggle this addon was holding — {@code :reload}/disable. */
    static void teardownOverlays(Addon a) {
        if((a == null) || a.overlayHolds.isEmpty())
            return;
        List<Hold> hs = new ArrayList<Hold>(a.overlayHolds);
        a.overlayHolds.clear();
        for(Hold h : hs)
            apply(h);
    }

    /**
     * Session init: drop the {@code :lua} REPL owner's holds <b>without</b> applying them. Its records
     * outlive a relog (the addons' do not — they are torn down), and the {@code MapView} they name is gone,
     * so the only thing left to do with them is forget them.
     */
    static void resetOverlays() {
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            c.overlayHolds.clear();
    }

    /**
     * {@code hafen.map.overlay(tag)} / {@code overlay(tag, on)} and {@code hafen.map.overlays()} — the
     * client's own display switches. Arity is the verb; the write answers the resulting <i>displayed</i>
     * state so a take is self-reporting.
     */
    private static void installOverlays(LuaTable map, final Addon owner) {
        map.set("overlay", new TwoArgFunction() {
            public LuaValue call(LuaValue tag, LuaValue on) {
                String t = toggleArg(tag);
                if(!on.isnil()) {
                    if(!on.isboolean())
                        throw new LuaError("hafen.map.overlay(tag, on): on must be true or false — true takes"
                            + " this addon's HOLD on the overlay, false releases it (the client's own"
                            + " checkbox and the server both hold it too, so nothing can force it off)");
                    if(on.toboolean())
                        take(owner, t);
                    else
                        release(owner, t);
                }
                Boolean d = displayed(t);
                return (d == null) ? LuaValue.NIL : LuaValue.valueOf(d.booleanValue());
            }
        });
        map.set("overlays", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable out = new LuaTable();
                int i = 0;
                for(String[] t : TOGGLES) {
                    LuaTable e = new LuaTable();
                    e.set("tag", LuaValue.valueOf(t[0]));
                    e.set("where", LuaValue.valueOf(t[1]));
                    e.set("what", LuaValue.valueOf(t[2]));
                    Boolean d = displayed(t[0]);
                    if(d != null)
                        e.set("on", LuaValue.valueOf(d.booleanValue()));
                    e.set("held", LuaValue.valueOf(held(owner, t[0])));
                    out.set(++i, e);
                }
                return out;
            }
        });
    }

    /**
     * A display-toggle tag argument. The set is <b>closed</b> and an unknown tag is refused (D-072): the
     * client owns exactly these three switches, and a typo that silently did nothing forever is the one
     * failure here nothing else would ever report.
     */
    private static String toggleArg(LuaValue tag) {
        if(tag.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
            throw new LuaError("hafen.map.overlay(tag): tag is one of " + toggleList());
        String t = tag.tojstring();
        if(toggle(t) == null)
            throw new LuaError("hafen.map.overlay(\"" + t + "\"): the client displays no such overlay — the"
                + " toggles it owns are " + toggleList() + " (note prov = provinces in the WORLD and realm ="
                + " provinces on the MAP: same feature, two tags)");
        return t;
    }

    private static String toggleList() {
        StringBuilder sb = new StringBuilder();
        for(String[] t : TOGGLES)
            sb.append((sb.length() == 0) ? "" : ", ").append('"').append(t[0]).append('"');
        return sb.toString();
    }
}
