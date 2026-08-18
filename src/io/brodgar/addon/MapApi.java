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
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.brodgar.addon.AddonManager.SessionState;

import static io.brodgar.addon.AddonManager.*;

/**
 * {@code hafen.map()} — the <b>RECORDED</b> map: the client's on-disk map database ({@link MapFile}), the map
 * the player has <i>explored</i>, as opposed to the live terrain streamed around them ({@link haven.MCache},
 * which is {@code hafen.world()}). Spec {@code 037-map-database}, re-shaped by {@code 039-uniform-api} §2.3.
 *
 * <p><b>The section is five collections</b>, one per kind of thing the database holds, and the verb on each
 * says how many:
 *
 * <ul>
 *   <li>{@code :segment()} — the contiguous explored areas ({@link LuaSegment}), with {@code :current()} for
 *       the one the player is standing in. A <b>distinguished member is a verb on the collection</b>, never a
 *       second spelling of the accessor, which is what the old zero-argument {@code segment()} had made it.</li>
 *   <li>{@code :grid()} — the recorded 100&times;100-tile squares ({@link LuaMapGrid}), addressed by the id
 *       the <b>server</b> published. That id is the only thing the live and recorded halves share, so this
 *       door and {@code hafen.world():grid()} hand back the <b>same interned object</b>: one Grid entity, two
 *       doors, each answering {@code nil} for what its own half does not have.</li>
 *   <li>{@code :marker()} — the pins ({@link LuaMarker}), the old {@code hafen.markers}, with the two unprotected
 *       writes and the {@code MarkersChanged} notify (event-driven since 042.11) that reports them.</li>
 *   <li>{@code :icon()} — the minimap icon registry ({@link LuaIconCat}; the engine has no "radar", it has
 *       {@link GobIcon.Settings} — D-061). {@code :get(res)} beside {@code :list(filter)} <b>deletes</b> the
 *       old split-the-argument-by-shape heuristic: the verb says which you meant, so nothing has to.</li>
 *   <li>{@code :overlay()} — the client's own display switches ({@link LuaOverlayToggle}).</li>
 * </ul>
 *
 * <p><b>"Overlay" names two things that share only a word</b>, and telling them apart is most of what the
 * overlay half is for. The <b>recorded masks</b> hang off a grid — {@code grid:overlay():get(tag)}
 * ({@link LuaMask}) — and their tag space is <b>open</b>, declared by the server's own overlay resources, so
 * an unknown tag is plain {@code nil} and {@code grid:overlay():list()} is the census that makes it readable.
 * The <b>display toggles</b> are {@code hafen.map():overlay()}, and that set is <b>closed</b> (D-072: refuse
 * what can never mean anything) while a write is a <b>HOLD</b> rather than a switch — see {@link #take}.
 *
 * <p><b>A grid can also draw itself</b> — {@code grid:image(level)} and {@code grid:overlayImage(tag)}, the
 * database's minimap drawings as ordinary image handles, rendered on {@link haven.Defer} and cached per
 * addon. That code lives in {@link MapImages}; nothing about it is a new load model, it is the same one
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

    /**
     * Build {@code hafen.map()} for {@code owner}. From installHafen.
     *
     * <p><b>Five collections and nothing else</b> (spec {@code 039-uniform-api} §2.3, task 039.4): the noun
     * names the kind and the verb says how many. Each one is minted <b>once</b> and handed back by identity,
     * exactly as the section object is — a panel that walks the map writes
     * {@code hafen.map():grid():get(id)} every frame and must allocate nothing to do it.
     */
    static void installMap(LuaTable hafen, final Addon owner) {
        final LuaValue segments = segmentCollection(owner);
        final LuaValue grids = gridCollection(owner);
        final LuaValue markers = markerCollection(owner);
        final LuaValue icons = LuaIconCat.collection(owner);
        final LuaValue toggles = toggleCollection(owner);
        LuaTable m = new LuaTable();
        m.set("segment", section(owner, "segment", segments));
        m.set("grid", section(owner, "grid", grids));
        m.set("marker", section(owner, "marker", markers));
        m.set("icon", section(owner, "icon", icons));
        m.set("overlay", section(owner, "overlay", toggles));
        Section.install(hafen, "map", m);
    }

    /** One of the five accessors: a colon call on the section object, no arguments, the collection back. */
    private static LuaValue section(final Addon owner, final String nm, final LuaValue coll) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "map", nm);
                if(Args.passed(a, 2))
                    throw new LuaError("hafen.map():" + nm + "() takes no arguments — it IS the collection,"
                        + " and hafen.map():" + nm + "():get(...) addresses one member of it");
                return coll;
            }
        };
    }

    /**
     * {@code hafen.map():segment()} — every contiguous explored area the character has walked, as
     * {@link LuaSegment} objects. {@code :current()} is the one they are standing in: a <b>distinguished
     * member is a verb on the collection</b> (§2.3), never a second spelling of the accessor, which is what
     * the old zero-argument {@code hafen.map.segment()} had made it.
     */
    private static LuaValue segmentCollection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "current");
                MiniMap.Location sl = sessloc();
                return (sl == null) ? LuaValue.NIL : LuaSegment.of(owner, sl.seg.id);
            }
        });
        return LuaCollection.create("hafen.map():segment()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Long id : knownSegs())
                    out.add(LuaSegment.of(owner, id.longValue()));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long sid = idArg(key, "hafen.map():segment():get(id)", "segment");
                return (segIn(mapfile(), sid) == null) ? LuaValue.NIL : LuaSegment.of(owner, sid);
            }
        }, extra);
    }

    /**
     * {@code hafen.map():grid()} — the recorded grids, addressed by the id the <b>server</b> published. That
     * id is the only thing the live and recorded halves share, so this and {@code hafen.world():grid()} hand
     * back the <b>same interned object</b> and each answers {@code nil} for what its own half does not have:
     * a grid you are standing on that has not been written down yet is {@code :live()} true and
     * {@code :exists()} false, and one explored last year is the mirror.
     *
     * <p><b>It does not enumerate, and that is a decision.</b> The database holds every grid the character
     * has ever walked over — tens of thousands — and the client's own minimap never lists them either: it
     * walks the grid coords of the rectangle it is drawing. So {@code :list()}/{@code :count()}/{@code :find()}
     * refuse, naming the two things that do work.
     */
    private static LuaValue gridCollection(final Addon owner) {
        return LuaCollection.create("hafen.map():grid()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                throw new LuaError("hafen.map():grid() does not enumerate: the database holds every grid you"
                    + " have ever walked over. Address one by its id with :get(id), or walk a rectangle of"
                    + " one segment with seg:grid():list(area).");
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long gid = idArg(key, "hafen.map():grid():get(gridId)", "grid");
                return (gridInfoIn(mapfile(), gid) == null) ? LuaValue.NIL : LuaMapGrid.of(owner, gid);
            }
        }, null);
    }

    /**
     * {@code hafen.map():marker()} — the pins in the database, as {@link LuaMarker} objects: {@code :list} /
     * {@code :count} / {@code :find} over the canonical filter, {@code :nearest(filter)} measured from the
     * player, and the two writes. There is no {@code :get}: a marker's only id is a per-session ref this
     * bridge mints, which is not a key anything outside the session could hold.
     */
    private static LuaValue markerCollection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("nearest", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "nearest");
                return LuaMarker.nearest(owner, a.arg(2));
            }
        });
        return LuaCollection.create("hafen.map():marker()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return LuaMarker.members(owner, null);
            }

            public String needle(LuaValue member) {
                return LuaMarker.name(member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean creatable() {
                return true;
            }

            // add(name, p) — drop a PLAYER pin at a Position, and hand it back for its setters (:color,
            // :onMap). It goes into the user's own on-disk database, so it is a real write with no gate.
            public LuaValue addMember(Varargs a) {
                LuaValue nm = Args.required(a, 2, "hafen.map():marker():add", "name");
                if(nm.type() != LuaValue.TSTRING)
                    throw new LuaError("hafen.map():marker():add(name, p): name is the label the map shows");
                Coord2d rc = LuaPosition.worldArg(a, 3, "hafen.map():marker():add", "p");
                return addMarker(owner, nm.tojstring(), rc.x, rc.y);
            }

            public boolean destroyable() {
                return true;
            }

            // remove(marker) — take a pin out of the database. Removing one that is already gone is INERT
            // (D-084): it is a moment, not a mistake, and marker:exists() is the question if you want it.
            public void removeMember(LuaValue x) {
                if(LuaMarker.resolve(x) == null)
                    throw new LuaError("hafen.map():marker():remove(m): m is a Marker object — the one"
                        + " :list(), :find(), :nearest() or :add() handed you");
                removeMarker(x);
            }
        }, extra);
    }

    /**
     * {@code hafen.map():overlay()} — the client's own display switches, as {@link LuaOverlayToggle} objects.
     * The set is <b>closed</b> and an unknown tag is refused (D-072), deliberately the opposite of the
     * recorded masks a grid carries, whose tags are declared by the server's resources.
     */
    private static LuaValue toggleCollection(final Addon owner) {
        return LuaCollection.create("hafen.map():overlay()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String[] t : TOGGLES)
                    out.add(LuaOverlayToggle.of(owner, t[0]));
                return out;
            }

            public String needle(LuaValue member) {
                LuaOverlayToggle h = LuaOverlayToggle.resolve(member);
                return (h == null) ? null : h.tag;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                return LuaOverlayToggle.of(owner, toggleArg(key));
            }
        }, null);
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

    /** Session init: drop the per-session marker-ref maps + re-prime MarkersChanged (from AddonManager.init).
     *  Every session's, since 073.4: {@code init} is not told which one ended. */
    static void resetMarkers() {
        for(SessionState st : AddonManager.allStates()) {
            synchronized(st.markerById) {
                st.markerIds.clear();
                st.markerById.clear();
            }
            st.markersPrimed = false;
            st.mapFile = null;                    // the file it named is being replaced with the session
        }
    }

    // ---- markers (hafen.map():marker()) ------------------------------------------------------------
    // Client-side map markers live in the on-disk map DB (MapFile), owned by the map window / corner
    // minimap (both hold the same MapFile). A marker's PERSISTENT identity is its segment id + segment
    // tile coord (survives a relog — coverage-gaps C4); the world x,y/dist a snapshot also carries are
    // SESSION-LOCAL conveniences, present only when the marker is in the player's current segment. Reads
    // copy the marker list under the MapFile read lock (it is mutated on loader threads — server markobj
    // adds, segment merges), then build snapshots outside the lock (the OCache gob-read discipline). Adds/
    // removes go straight to the shared DB and persist. MarkersChanged is fired by a notify at the
    // MapFile.markerseq bump itself (042.11, event-driven — a marker add/remove is not a uimsg, so the
    // bump is caught at its source and marshalled onto the tick rather than diffed every frame).

    private static final java.awt.Color DEFAULT_MARKER_COLOR = new java.awt.Color(255, 215, 0);  // gold pin

    // 073.4: THE MARKER REFS ARE ONE SESSION'S (SessionState.markerIds / .markerById / .markersPrimed /
    // .lastMarkerSeq). A ref names a MapFile.Marker by OBJECT IDENTITY, and those objects are read out of one
    // login's map database — the next session reads the same file again into different objects, so a ref that
    // outlived the session would resolve onto nothing or, worse, onto a merge's replacement. The session is the
    // one whose GameUI holds the file (see mapState), which is also what lets a marker-change notify name a
    // session at all: the seam is handed a MapFile and nothing else.

    /** The id sequence the refs are minted from. Process-wide: two sessions minting from one counter collide
     *  with nobody, and a ref that is unique for the client's life is one that can never be misread. */
    private static long markerIdSeq = 0;

    /** The client's on-disk map DB (markers/segments), or null before the HUD/map is up. */
    static MapFile mapfile() {
        return mapfileOf(gui());
    }

    /** The map database one HUD holds — the map window's, or the corner minimap's, which are the same instance. */
    private static MapFile mapfileOf(GameUI g) {
        if(g == null)
            return null;
        if(g.mapfile != null)          // the big Map window (MapWnd.file)
            return g.mapfile.file;
        MiniMap mm = g.mmap;            // fall back to the corner minimap (same MapFile instance)
        return (mm == null) ? null : mm.file;
    }

    /**
     * <b>The state a map read is about, claiming the file it reads from</b> (073.4). Both halves come out of the
     * SAME {@code GameUI} — the session is that HUD's, and the file is the one that HUD holds — so a session can
     * only ever claim its own map, however many are live. {@code null} before the HUD is up, which is what every
     * caller here already had to handle.
     */
    private static SessionState mapState() {
        GameUI g = gui();
        if(g == null)
            return null;
        SessionState st = state(g.ui);
        if(st != null) {
            MapFile f = mapfileOf(g);
            if(f != null)
                st.mapFile = f;
        }
        return st;
    }

    /**
     * Refresh the claim, from the tick (073.4) — {@link AddonManager#onMarkersChanged} matches a bump against
     * {@code SessionState.mapFile} and has nothing else to go on, so the claim cannot wait for an addon to call
     * a map verb: an addon that only subscribes to {@code MarkersChanged} would then never hear one.
     */
    static void claimMapFile() {
        mapState();
    }

    /**
     * The session location — the segment plus the segment-tile-coord of session tile (0,0): the bridge
     * between session-local WORLD coords and the persistent segment coords markers store (world→segment =
     * sessloc.tc + floor(world/tilesz), mirroring {@code MapWnd.FindMark.hit}). Resolved live by the
     * corner minimap; null until the map grid-info has streamed in (a beat after enter-world).
     */
    static MiniMap.Location sessloc() {
        MiniMap mm = minimap();
        return (mm == null) ? null : mm.sessloc;
    }

    /**
     * <b>The one minimap the session location is read from</b> — the corner one. Named because the 045.2 tap
     * has to listen to exactly this instance and no other: the map window carries a second {@link MiniMap},
     * ticking the same locator against the same file, and a change announced by that one would be about a
     * {@code sessloc} nothing here ever reads.
     */
    static MiniMap minimap() {
        GameUI g = gui();
        return (g == null) ? null : g.mmap;
    }

    /** Assign (or look up) a stable per-session ref id for a marker. Touched from UI + REPL threads → guarded.
     *  {@code 0} when there is no session to intern it in, which is a ref that resolves to nothing. */
    static long markerId(MapFile.Marker m) {
        SessionState st = mapState();
        if(st == null)
            return 0;
        synchronized(st.markerById) {
            Long id = st.markerIds.get(m);
            if(id == null) {
                id = Long.valueOf(nextMarkerId());
                st.markerIds.put(m, id);
                st.markerById.put(id, m);
            }
            return id.longValue();
        }
    }

    /** The next ref id. Synchronized on the class: the counter is the client's, the maps it feeds are not. */
    private static synchronized long nextMarkerId() {
        return ++markerIdSeq;
    }

    static MapFile.Marker markerByRef(long id) {
        SessionState st = mapState();
        if(st == null)
            return null;
        synchronized(st.markerById) {
            return st.markerById.get(Long.valueOf(id));
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

    /**
     * {@code :add(name, p)} — create a PLAYER marker at a world position and hand it back, <b>bare</b>: the
     * client's own defaults (a gold pin, not on the main map) and the two setters {@code m:color(...)} /
     * {@code m:onMap(b)} say the rest. The old {@code opts} table is R4's config-table-as-arguments, and a
     * marker takes the deferred-completion problem D-112 answered for an overlay the easy way: it is a
     * <i>record in a database</i>, so a half-configured one is a gold pin that is visibly there and one
     * setter away from right, never something that silently fails to appear.
     *
     * <p>{@code nil} when the map or the session location is not up yet, which is also when a Position could
     * not have been located.
     */
    private static LuaValue addMarker(Addon owner, String nm, double wx, double wy) {
        MapFile file = mapfile();
        MiniMap.Location sl = sessloc();
        if((file == null) || (sl == null))
            return LuaValue.NIL;                  // map / session location not up yet
        // world → segment tile coord (mirrors MapWnd.FindMark.hit: sessloc.tc + floor(world / tilesz)).
        Coord segTc = sl.tc.add(Coord2d.of(wx, wy).floor(MCache.tilesz));
        MapFile.PMarker pm = new MapFile.PMarker(file, sl.seg.id, segTc, nm, DEFAULT_MARKER_COLOR, false);
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

    /**
     * Fire MarkersChanged when the DB's markerseq changes — called from the marshalling queue when a marker
     * add/remove/update bump seq happens (042.11, event-driven). The initial set that is loaded from disk
     * does not cause seqchanges and does not arrive as an event; only user/server-initiated changes do.
     * On the first notify after session init, prime to capture the post-change state, and fire to report
     * the change.
     *
     * <p><b>It reads the file the notify was about</b> (073.4) — {@code st.mapFile}, which is how the bump found
     * this session in the first place — rather than re-asking which map is drawn, and the prime state is that
     * session's own, because "has this one been told its count once" is a question about one login.
     */
    static void fireMarkersChanged(SessionState st, int seq) {
        MapFile file = (st == null) ? null : st.mapFile;
        if(file == null)
            return;
        if(!st.markersPrimed) {
            // First notify after login: prime with current state and fire to report the first real change
            st.markersPrimed = true;
            st.lastMarkerSeq = seq;
            // The COUNT itself, not a { count = n } wrapper (041.1): one thing to say is said directly, and
            // the wrapper was the only field this payload ever had.
            fire("MarkersChanged", LuaValue.valueOf(markerCount(file)));
            return;
        }
        if(seq != st.lastMarkerSeq) {
            st.lastMarkerSeq = seq;
            fire("MarkersChanged", LuaValue.valueOf(markerCount(file)));
        }
    }

    /** How many markers that database holds right now, under its read lock. */
    private static int markerCount(MapFile file) {
        file.lock.readLock().lock();
        try { return file.markers.size(); }
        finally { file.lock.readLock().unlock(); }
    }

    // ---- icons (hafen.map():icon()) ----------------------------------------------------------------
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

    // ---- segments and grids (hafen.map():segment() / :grid()) --------------------------------------
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
        Coord2d ul = gridUL(gi);
        return (ul == null) ? LuaValue.NIL : xy(ul.x, ul.y);
    }

    /**
     * {@link #gridWorldUL} as a coordinate rather than a Lua table — the recorded→live half of the Position
     * lookup ({@link LuaPosition}), which needs to do arithmetic on it rather than hand it out.
     */
    static Coord2d gridUL(MapFile.GridInfo gi) {
        MiniMap.Location sl = sessloc();
        if((gi == null) || (sl == null) || (gi.seg != sl.seg.id))
            return null;
        return segGridUL(sl, gi.sc);
    }

    /** The world upper-left of a segment grid coord in this session — the same arithmetic, without a lookup. */
    static Coord2d segGridUL(MiniMap.Location sl, Coord sc) {
        return Coord2d.of(((sc.x * MCache.cmaps.x) - sl.tc.x) * MCache.tilesz.x,
                          ((sc.y * MCache.cmaps.y) - sl.tc.y) * MCache.tilesz.y);
    }

    /**
     * The grid id the database recorded at a segment coord, or {@code null} — the live→recorded half of the
     * Position lookup, and the one step {@code Segment.grid(sc)} cannot take without a disk load. A durability
     * check that had to wait for {@link haven.Defer} would report ground the character has walked as not
     * durable until the tiles landed; the id is already in memory with the segment, so this answers now.
     */
    static Long recordedGridId(MapFile file, long segId, Coord sc) {
        MapFile.Segment seg = segIn(file, segId);
        if((seg == null) || !file.lock.readLock().tryLock())
            return null;
        try {
            return seg.gridid(sc);
        } catch(RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
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
    // tag a grid does not carry is nil, never an error (grid:overlay():list() is the census that makes it
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

    // ---- the client's display toggles (hafen.map():overlay()) --------------------------------------
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
     * The undo of one hold, applied to <b>the very side it was taken on</b> (073.4) and guarded on that side
     * still being live — exactly as {@code teardownHidden} guards on the widget still being live. A hold names
     * one session's {@code MapView}/{@code MapWnd}: the {@code +1} it left is on that multiset and no other, so
     * asking which map is DRAWN now (what this used to do) would hand the displaced state of one session's
     * overlays to another's the moment two are alive, and does the same thing today for a teardown that runs
     * from {@code init}, when the anchor has already moved on. A side whose session is gone is simply forgotten:
     * its overlay state went with the tree.
     */
    private static void apply(Hold h) {
        Object was = h.on.get();
        if(!live(was))
            return;
        try {
            if(h.recorded) {
                if(!h.stock)
                    ((MapWnd)was).overlays.remove(h.tag);
            } else {
                ((MapView)was).disol(h.tag);
            }
        } catch(RuntimeException e) {      // best-effort: never abort a teardown
        }
    }

    /** Is that side still standing in its own session's tree? (A relog leaves the old one behind, unreachable.) */
    private static boolean live(Object side) {
        if(!(side instanceof Widget))
            return false;
        Widget w = (Widget)side;
        UI u = w.ui;
        return (u != null) && !u.destroyed && (u.root != null) && w.hasparent(u.root);
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
     * A display-toggle tag argument. The set is <b>closed</b> and an unknown tag is refused (D-072): the
     * client owns exactly these four switches, and a typo that silently did nothing forever is the one
     * failure here nothing else would ever report.
     */
    private static String toggleArg(LuaValue tag) {
        if(tag.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
            throw new LuaError("hafen.map():overlay():get(tag): tag is one of " + toggleList());
        String t = tag.tojstring();
        if(toggle(t) == null)
            throw new LuaError("hafen.map():overlay():get(\"" + t + "\"): the client displays no such overlay"
                + " — the toggles it owns are " + toggleList() + " (note prov = provinces in the WORLD and"
                + " realm = provinces on the MAP: same feature, two tags)");
        return t;
    }

    private static String toggleList() {
        StringBuilder sb = new StringBuilder();
        for(String[] t : TOGGLES)
            sb.append((sb.length() == 0) ? "" : ", ").append('"').append(t[0]).append('"');
        return sb.toString();
    }
}
