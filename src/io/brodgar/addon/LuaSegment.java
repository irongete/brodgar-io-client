package io.brodgar.addon;

import haven.Coord;
import haven.MapFile;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
 * stores is a {@link LuaPosition}, whose grid id comes from the server and means the same thing to every
 * player forever.
 *
 * <p><b>You ask a segment for an AREA, never for a list.</b> {@code Segment.map} (coord&nbsp;&rarr;&nbsp;grid
 * id) is private to the engine, and this is not a gap to work around: {@code MiniMap} never enumerates
 * either — it walks the grid coords of the rectangle it is drawing. {@code seg:grid():get(sc)} and
 * {@code seg:grid():list(area)} are the whole addressing surface, and they are what a minimap panel actually
 * wants.
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
        mt.set(LuaValue.INDEX, Refusal.closedIndex("segment", methods(owner),
            "one continuous piece of mapped ground"));
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
        // grid() — this segment's grids, as a collection. :get(sc) is the recorded grid at a segment grid
        // coord, nil for BOTH "there is no grid there" and "it has not come off the disk yet" (the call
        // kicks the load and the next one answers), and :list(area) walks a RECTANGLE of them.
        m.set("grid", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSegment h = handle(a.arg1(), "grid");
                if(Args.passed(a, 2))
                    throw new LuaError("seg:grid(sc) is now a COLLECTION: seg:grid():get(sc) is the grid at"
                        + " one segment coord and seg:grid():list(area) walks a rectangle of them");
                return gridCollection(owner, h.id);
            }
        });
        // markers(filter) — the markers recorded in THIS segment (D-066: a relation, not a namespace). The
        // filter is the canonical one: nil = all, a string = a substring of the name, a function = a
        // predicate called with the Marker object.
        m.set("markers", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final Long seg = Long.valueOf(handle(self, "markers").id);
                return LuaCollection.create("segment:markers()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        return LuaMarker.members(owner, seg);   // the same Source hafen.map():marker() reads
                    }

                    public String needle(LuaValue member) {
                        return LuaMarker.name(member);
                    }

                    /** These have a name, so a string filter is a substring test over {@link #needle}. */
                    public boolean named() {
                        return true;
                    }

                    public String noGet() {
                        return "a marker's only id is a per-session ref this client mints, which is not a key"
                            + " anything could hold on to: segment:markers():find(filter) is the search";
                    }
                }, null);
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

    /**
     * {@code seg:grid()} — the grids of one segment, as a collection of {@link LuaMapGrid}. A VIEW: it
     * re-derives from the segment id on every call and holds nothing between them.
     *
     * <p><b>You ask a segment for an AREA, never for a list, and the collection says so out loud.</b> A
     * segment is every grid the character ever walked in one contiguous area — tens of thousands — and
     * the client's own minimap does not enumerate either: it walks the grid coords of the rectangle it is
     * drawing. So {@code :list(area)} takes a rectangle instead of the canonical filter, and
     * {@code :count()}/{@code :find()} refuse naming the two verbs that do work. The refusal IS the answer:
     * an empty list would be a lie, and a complete one a thousand disk reads nobody asked for.
     */
    private static LuaValue gridCollection(final Addon owner, final long id) {
        LuaTable extra = new LuaTable();
        // list(area) — every LOADED grid in {x=,y=,w=,h=} of segment grid coords. The ones still loading are
        // simply absent, and present next call: re-walking the rectangle each frame is the intended usage.
        extra.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "seg:grid()", "list");
                Args.only(a, 1, "seg:grid():list");
                LuaValue area = Args.required(a, 2, "seg:grid():list", "area");
                MapFile file = MapApi.mapfile();
                MapFile.Segment seg = MapApi.segIn(file, id);
                LuaTable out = new LuaTable();
                Coord ul = areaUL(area), sz = areaSz(area);
                int n = 0;
                for(int y = 0; y < sz.y; y++) {
                    for(int x = 0; x < sz.x; x++) {
                        MapFile.Grid g = MapApi.gridAtIn(file, seg, ul.add(x, y));
                        if(g != null)
                            out.set(++n, LuaMapGrid.of(owner, AddonManager.drawnUser(), g.id));
                    }
                }
                return out;
            }
        });
        return LuaCollection.create("seg:grid()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                throw new LuaError("seg:grid() does not enumerate: a segment is every grid you ever walked"
                    + " in one explored area. seg:grid():get(sc) addresses one, and seg:grid():list(area)"
                    + " walks the rectangle you are drawing.");
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                MapFile file = MapApi.mapfile();
                MapFile.Grid g = MapApi.gridAtIn(file, MapApi.segIn(file, id), coordArg(key));
                return (g == null) ? LuaValue.NIL : LuaMapGrid.of(owner, AddonManager.drawnUser(), g.id);
            }

            /** The key is a segment grid coord, which grid:segmentCoord() hands you. */
            public String keyName() {
                return "sc";
            }
        }, extra);
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaSegment handle(LuaValue self, String method) {
        LuaSegment h = resolve(self);
        if(h == null)
            throw new LuaError("seg:" + method + "() — use a COLON call on a Segment object"
                + " (hafen.map():segment():current(), hafen.map():segment():list()[n])");
        return h;
    }

    /** A {@code {x,y}} segment grid coord argument. Any integer is legal — a segment has no bounds. */
    private static Coord coordArg(LuaValue c) {
        if((c == null) || !c.istable() || c.get("x").isnil() || c.get("y").isnil())
            throw new LuaError("seg:grid():get(sc): sc is a segment grid coord {x=,y=}"
                + " (grid:segmentCoord() hands you one)");
        String w = "seg:grid():get(sc)", g = "a segment grid coordinate";
        return Coord.of(Args.integer(c.get("x"), w, "sc.x", g), Args.integer(c.get("y"), w, "sc.y", g));
    }

    /** How many grids one {@code :list(area)} may walk — each miss is a disk read the caller pays for. */
    private static final int MAX_AREA_GRIDS = 1024;

    private static Coord areaUL(LuaValue area) {
        if((area == null) || !area.istable() || area.get("x").isnil() || area.get("y").isnil())
            throw new LuaError("seg:grid():list(area): area is {x=,y=,w=,h=} in segment grid coords");
        String w = "seg:grid():list(area)", g = "a segment grid coordinate";
        return Coord.of(Args.integer(area.get("x"), w, "area.x", g),
                        Args.integer(area.get("y"), w, "area.y", g));
    }

    private static Coord areaSz(LuaValue area) {
        LuaValue w = area.get("w"), h = area.get("h");
        if(w.isnil() || h.isnil())
            throw new LuaError("seg:grid():list(area): area is {x=,y=,w=,h=} in segment grid coords");
        int iw = Args.integer(w, "seg:grid():list(area)", "area.w", "a count of grids");
        int ih = Args.integer(h, "seg:grid():list(area)", "area.h", "a count of grids");
        if((iw < 1) || (ih < 1))
            throw new LuaError("seg:grid():list(area): w and h are counts of GRIDS and must be at least 1");
        // The product in `long`: both sides are any int the caller passed, so an int multiply here wraps —
        // 65536x65536 is 0 and 46341x46341 is negative, and both slid under the cap into the walk it exists
        // to refuse. The area is also what the refusal prints, so it has to be the true one.
        long grids = (long)iw * (long)ih;
        if(grids > MAX_AREA_GRIDS)
            throw new LuaError("seg:grid():list(area): " + iw + "x" + ih + " is " + grids + " grids, over"
                + " the "
                + MAX_AREA_GRIDS + " a single call may walk — ask for the rectangle you are drawing");
        return Coord.of(iw, ih);
    }
}
