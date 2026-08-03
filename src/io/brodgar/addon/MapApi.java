package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.GobIcon;
import haven.MapFile;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
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
 * <p>The segments, grids, overlays and imagery that are the rest of the database arrive in 037.2–037.4.
 */
final class MapApi {
    private MapApi() {}

    /** Build {@code hafen.map} for {@code owner}. From installHafen. */
    static void installMap(LuaTable hafen, final Addon owner) {
        LuaTable map = new LuaTable();
        map.set("markers", markers(owner));
        map.set("icons", LuaIconCat.factory(owner));
        hafen.set("map", map);
    }

    /** {@code hafen.map.markers} — the marker half of the DB (the old {@code hafen.markers}, unchanged). */
    private static LuaTable markers(final Addon owner) {
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
}
