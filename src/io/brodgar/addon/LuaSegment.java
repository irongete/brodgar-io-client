package io.brodgar.addon;

import haven.Coord;
import haven.MapFile;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Segment object</b> — one contiguous explored area of the recorded map ({@link MapFile.Segment}): what
 * the map window draws as one "map", made of the 100&times;100-tile grids the character has walked over.
 * Spec {@code 037-map-database}, task 037.2.
 *
 * <p><b>A segment id is client-local bookkeeping, and that is the feature's load-bearing rule.</b>
 * {@link MapFile} mints it with {@code rnd.nextLong()}, and when two explored areas turn out to touch, the
 * merge re-bases the loser's grids and rewrites every marker inside it <i>in place</i>. So a segment id plus
 * a segment tile coord is a <b>read-only view</b>: perfectly good to look at this session, and wrong to save
 * or send, because after a merge it would not go nil — it would point at the WRONG PLACE. What an addon
 * stores is the {@code {gridId, x, y}} anchor ({@code marker:anchor()}, {@code hafen.world.gridPos()}), whose
 * grid id comes from the server and means the same thing to every player forever.
 *
 * <p><b>You ask a segment for an AREA, never for a list.</b> {@code Segment.map} (coord&nbsp;&rarr;&nbsp;grid
 * id) is private to the engine, and this is not a gap to work around: {@code MiniMap} never enumerates
 * either — it walks the grid coords of the rectangle it is drawing. {@code seg:grid(sc)} and
 * {@code seg:grids(area)} are the whole addressing surface, and they are what a minimap panel actually wants.
 *
 * <p><b>Interned on the SEGMENT ID, not on the Java object</b> (D-063). A {@code Segment} lives in a
 * {@code BackCache(5)}: the same segment comes back as a <i>different</i> object after an eviction, so a
 * {@code WeakHashMap} keyed on identity would be a bug that only appears once the cache turns over. Every
 * method re-resolves through {@link MapApi#segIn}, so a stashed handle tracks the live database and answers
 * {@code :exists() == false} if the segment is ever merged away.
 */
public final class LuaSegment {
    /** The segment id — the whole state of a handle, and its identity. */
    public final long id;

    private LuaSegment(long id) {
        this.id = id;
    }

    /** {@code tostring(seg)}: {@code Segment(<id>)}. */
    public String toString() {
        return "Segment(" + Long.toString(id) + ")";
    }

    /** An interned Segment object for {@code id} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long id) {
        return owner.mapSegments.of(id);
    }

    /** The {@code LuaSegment} behind a Lua value, or {@code null} for anything that is not a Segment object. */
    static LuaSegment resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaSegment) ? (LuaSegment)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Segment interning cache and metatable ({@link Addon#mapSegments}); the {@link LuaGob} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<Long, Ref> live = new HashMap<Long, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code id} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long id) {
            drain();
            Long key = Long.valueOf(id);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaSegment(id), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref sr = (Ref)r;
                if(live.get(sr.key) == sr)
                    live.remove(sr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Long key;

        Ref(LuaValue v, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Segment metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Segment"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSegment h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Segment(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the segment id as a DECIMAL STRING (a 64-bit id does not survive a Lua number), and this
        // handle's identity. Read it, print it, compare it — but do not store it (see the class note).
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(Long.toString(handle(self, "id").id));
            }
        });
        // exists() — does the database still carry this segment? False after a merge folded it into another.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(MapApi.segIn(MapApi.mapfile(), handle(self, "exists").id) != null);
            }
        });
        // grid(sc) — the recorded grid at a segment grid coord, or nil. Nil means BOTH "there is no grid
        // there" and "it has not come off the disk yet": the call kicks the load and the next one answers.
        // (A grid's id is what this object is interned on, and that id lives in the grid data itself, so
        // there is nothing to hand back before the load lands.)
        m.set("grid", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue sc) {
                LuaSegment h = handle(self, "grid");
                MapFile file = MapApi.mapfile();
                MapFile.Grid g = MapApi.gridAtIn(file, MapApi.segIn(file, h.id), coordArg(sc, "grid"));
                return (g == null) ? LuaValue.NIL : LuaMapGrid.of(owner, g.id);
            }
        });
        // grids(area) — every LOADED grid in a rectangle of segment grid coords, {x=,y=,w=,h=}. The ones
        // still loading are simply absent, and present next call: an area walk is how the client's own
        // minimap addresses the map, and re-walking it each frame is the intended usage.
        m.set("grids", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue area) {
                LuaSegment h = handle(self, "grids");
                MapFile file = MapApi.mapfile();
                MapFile.Segment seg = MapApi.segIn(file, h.id);
                LuaTable out = new LuaTable();
                Coord ul = areaUL(area), sz = areaSz(area);
                int n = 0;
                for(int y = 0; y < sz.y; y++) {
                    for(int x = 0; x < sz.x; x++) {
                        MapFile.Grid g = MapApi.gridAtIn(file, seg, ul.add(x, y));
                        if(g != null)
                            out.set(++n, LuaMapGrid.of(owner, g.id));
                    }
                }
                return out;
            }
        });
        // markers(filter) — the markers recorded in THIS segment (D-066: a relation, not a namespace). The
        // filter is the canonical one: nil = all, a string = a substring of the name, a function = a
        // predicate called with the Marker object.
        m.set("markers", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue filter) {
                return LuaMarker.collection(owner, Long.valueOf(handle(self, "markers").id), filter);
            }
        });
        // info() — the snapshot escape hatch (logging/serialising), never the way to read one field.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaSegment h = handle(self, "info");
                if(MapApi.segIn(MapApi.mapfile(), h.id) == null)
                    return LuaValue.NIL;
                MiniMap.Location sl = MapApi.sessloc();
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf(Long.toString(h.id)));
                t.set("current", LuaValue.valueOf((sl != null) && (sl.seg.id == h.id)));
                t.set("markers", LuaValue.valueOf(MapApi.markerList(Long.valueOf(h.id)).size()));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSegment handle(LuaValue self, String method) {
        LuaSegment h = resolve(self);
        if(h == null)
            throw new LuaError("seg:" + method + "() — use a COLON call on a Segment object"
                + " (hafen.map.segment(), hafen.map.segments()[n])");
        return h;
    }

    /** A {@code {x,y}} segment grid coord argument. Any integer is legal — a segment has no bounds. */
    private static Coord coordArg(LuaValue c, String method) {
        if((c == null) || !c.istable() || !c.get("x").isnumber() || !c.get("y").isnumber())
            throw new LuaError("seg:" + method + "(sc): sc is a segment grid coord {x=,y=}"
                + " (grid:sc() hands you one)");
        return Coord.of(c.get("x").toint(), c.get("y").toint());
    }

    /** How many grids one {@code grids(area)} call may walk — each miss is a disk read the caller pays for. */
    private static final int MAX_AREA_GRIDS = 1024;

    private static Coord areaUL(LuaValue area) {
        if((area == null) || !area.istable() || !area.get("x").isnumber() || !area.get("y").isnumber())
            throw new LuaError("seg:grids(area): area is {x=,y=,w=,h=} in segment grid coords");
        return Coord.of(area.get("x").toint(), area.get("y").toint());
    }

    private static Coord areaSz(LuaValue area) {
        LuaValue w = area.get("w"), h = area.get("h");
        if(!w.isnumber() || !h.isnumber())
            throw new LuaError("seg:grids(area): area is {x=,y=,w=,h=} in segment grid coords");
        int iw = w.toint(), ih = h.toint();
        if((iw < 1) || (ih < 1))
            throw new LuaError("seg:grids(area): w and h are counts of GRIDS and must be at least 1");
        if((iw * ih) > MAX_AREA_GRIDS)
            throw new LuaError("seg:grids(area): " + iw + "x" + ih + " is " + (iw * ih) + " grids, over the "
                + MAX_AREA_GRIDS + " a single call may walk — ask for the rectangle you are drawing");
        return Coord.of(iw, ih);
    }
}
