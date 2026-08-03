package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.MapFile;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A <b>Marker object</b> — one pin in the recorded map database ({@link MapFile.Marker}): either a
 * <b>player</b> marker (your own, with a colour) or a <b>system</b> marker (a server or quest pin, with an
 * icon). The same markers the map window shows. Spec {@code 037-map-database}, task 037.2.
 *
 * <p><b>Why it is an entity now.</b> Through 037.1 a marker was a snapshot table, which was enough while
 * reading it was all one could do. 037.2 gives it a verb — {@code marker:anchor()}, the conversion that
 * makes a marker <i>shareable</i> — and a verb belongs on the thing, not on a copy of it (D-044/D-066).
 * {@code hafen.map.markers.list/nearest/add/remove} keep the shape they had; what they hand back (and take)
 * is the Marker object, and {@code :info()} is the old snapshot, verbatim, as the escape hatch.
 *
 * <p><b>{@code marker:anchor()} is the feature's point.</b> A marker's own position is
 * {@code seg}&nbsp;+&nbsp;{@code tc} — a segment id this client invented and a tile coord inside it — and a
 * segment merge <b>rewrites both in place</b>. So that position cannot be saved or sent: after a merge it
 * would not go nil, it would point at the wrong place. {@code :anchor()} converts it to
 * {@code {gridId, x, y}}, the same shape {@code hafen.world.gridPos()} returns, whose grid id comes from the
 * server. Its two halves differ, and the cheap one covers the common case: a marker in the player's
 * <b>current segment</b> converts through {@code sessloc} arithmetic to a world coord and reads the live
 * grid there — synchronous, no reverse lookup; a marker <b>anywhere else</b> has no world coord this
 * session, so it goes through the database ({@code tc / cmaps} &rarr; the segment's grid) and is
 * <b>asynchronous</b>: nil until that grid comes off the disk, and the call is what starts it.
 *
 * <p><b>Interned on the per-session marker ref</b>, which is the identity the engine actually has: a
 * {@link MapFile.Marker} is loaded once and then mutated in place (a merge rewrites its fields; it is never
 * re-minted), so object identity is stable for the session and {@link MapApi}'s ref map — the one 037.1
 * already handed to Lua as a number — is exactly the right key. It is dropped on relog with the rest of the
 * session state ({@code MapApi.resetMarkers}).
 */
public final class LuaMarker {
    /** The per-session marker ref — the whole state of a handle, and its identity ({@link MapApi#markerId}). */
    public final long ref;

    private LuaMarker(long ref) {
        this.ref = ref;
    }

    /** {@code tostring(marker)}: {@code Marker(<name>)}. */
    public String toString() {
        MapFile.Marker m = MapApi.markerByRef(ref);
        return "Marker(" + (((m == null) || (m.nm == null)) ? "?" : m.nm) + ")";
    }

    /** An interned Marker object for a ref in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long ref) {
        return owner.mapMarkers.of(ref);
    }

    /** The {@code LuaMarker} behind a Lua value, or {@code null} for anything that is not a Marker object. */
    static LuaMarker resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMarker) ? (LuaMarker)o : null;
    }

    // ---- the collection (hafen.map.markers.list / nearest, seg:markers) ----------------------------

    /**
     * The markers of the whole database ({@code seg == null}) or of one segment, as interned Marker
     * objects, filtered by the canonical filter: {@code nil} = all, a <b>string</b> = a substring of the
     * marker's name (matched Java-side, no object built), a <b>function</b> = a predicate called with the
     * <b>Marker object</b> (an error in it drops the entry, the {@code hafen.world} rule).
     */
    static LuaValue collection(Addon owner, Long seg, LuaValue filter) {
        LuaTable out = new LuaTable();
        int i = 0;
        for(MapFile.Marker m : MapApi.markerList(seg)) {
            if(!matches(filter, owner, m))
                continue;
            out.set(++i, of(owner, MapApi.markerId(m)));
        }
        return out;
    }

    /** The closest matching marker to the player, or nil — only markers in the player's own segment have a distance. */
    static LuaValue nearest(Addon owner, LuaValue filter) {
        MiniMap.Location sl = MapApi.sessloc();
        Coord2d prc = AddonManager.playerPos();
        if((sl == null) || (prc == null))
            return LuaValue.NIL;
        MapFile.Marker best = null;
        double bestd = Double.POSITIVE_INFINITY;
        for(MapFile.Marker m : MapApi.markerList(Long.valueOf(sl.seg.id))) {
            if(!matches(filter, owner, m))
                continue;
            double d = Math.hypot(worldX(m, sl) - prc.x, worldY(m, sl) - prc.y);
            if(d < bestd) { bestd = d; best = m; }
        }
        return (best == null) ? LuaValue.NIL : of(owner, MapApi.markerId(best));
    }

    /** Does a marker pass a collection filter? A function filter is called with the interned Marker object. */
    private static boolean matches(LuaValue filter, Addon owner, MapFile.Marker m) {
        if((filter == null) || filter.isnil())
            return true;
        if(filter.isfunction()) {
            try {
                return filter.call(of(owner, MapApi.markerId(m))).toboolean();
            } catch(RuntimeException e) {   // LuaError is a RuntimeException
                return false;
            }
        }
        if(filter.isstring())
            return (m.nm != null) && m.nm.contains(filter.tojstring());
        return true;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Marker interning cache and metatable ({@link Addon#mapMarkers}); the {@link LuaGob} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<Long, Ref> live = new HashMap<Long, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for a ref — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long ref) {
            drain();
            Long key = Long.valueOf(ref);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMarker(ref), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref mr = (Ref)r;
                if(live.get(mr.key) == mr)
                    live.remove(mr.key);
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

    // ---- the Marker metatable ----------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Marker"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMarker h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Marker(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // name() — the marker's label, as the map window shows it.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "name");
                return ((mk == null) || (mk.nm == null)) ? LuaValue.NIL : LuaValue.valueOf(mk.nm);
            }
        });
        // type() — "player" (yours, coloured) or "system" (the server's, with an icon).
        m.set("type", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "type");
                if(mk instanceof MapFile.PMarker)
                    return LuaValue.valueOf("player");
                if(mk instanceof MapFile.SMarker)
                    return LuaValue.valueOf("system");
                return LuaValue.NIL;
            }
        });
        // tc() — the marker's SEGMENT tile coord, {x,y}: where it really lives in the database. Client-local
        // (a merge rewrites it), so this is what you look at and :anchor() is what you save.
        m.set("tc", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "tc");
                return (mk == null) ? LuaValue.NIL : AddonManager.xy(mk.tc.x, mk.tc.y);
            }
        });
        // pos() — the marker's WORLD position this session (the tile's centre), or nil for a marker outside
        // the player's current segment: an explored area you are not standing in has no world coord today.
        m.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "pos");
                MiniMap.Location sl = MapApi.sessloc();
                if((mk == null) || (sl == null) || (mk.seg != sl.seg.id))
                    return LuaValue.NIL;
                return AddonManager.xy(worldX(mk, sl), worldY(mk, sl));
            }
        });
        // dist() — how far the player is from it, in world units; nil whenever pos() is nil.
        m.set("dist", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "dist");
                MiniMap.Location sl = MapApi.sessloc();
                Coord2d prc = AddonManager.playerPos();
                if((mk == null) || (sl == null) || (prc == null) || (mk.seg != sl.seg.id))
                    return LuaValue.NIL;
                return LuaValue.valueOf(Math.hypot(worldX(mk, sl) - prc.x, worldY(mk, sl) - prc.y));
            }
        });
        // color() — a player marker's pin colour, {r,g,b,a}; nil on a system marker.
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "color");
                if(!(mk instanceof MapFile.PMarker) || (((MapFile.PMarker)mk).color == null))
                    return LuaValue.NIL;
                return AddonManager.color(((MapFile.PMarker)mk).color);
            }
        });
        // onmap() — is a player marker also drawn on the main map? nil on a system marker.
        m.set("onmap", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "onmap");
                if(!(mk instanceof MapFile.PMarker))
                    return LuaValue.NIL;
                return LuaValue.valueOf(((MapFile.PMarker)mk).onmap);
            }
        });
        // icon() — a system marker's icon resource name; nil on a player marker.
        m.set("icon", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "icon");
                if(!(mk instanceof MapFile.SMarker))
                    return LuaValue.NIL;
                MapFile.SMarker sm = (MapFile.SMarker)mk;
                return ((sm.res == null) || (sm.res.name == null)) ? LuaValue.NIL : LuaValue.valueOf(sm.res.name);
            }
        });
        // segment() — the Segment this marker is recorded in (D-066: the relation, not a stored id).
        m.set("segment", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "segment");
                return (mk == null) ? LuaValue.NIL : LuaSegment.of(owner, mk.seg);
            }
        });
        // anchor() — {gridId, x, y}: the position you may SAVE or SEND. See the class note; nil while the
        // grid it needs is not there (the call kicks the load, so ask again next tick).
        m.set("anchor", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return anchorOf(marker(self, "anchor"));
            }
        });
        // exists() — is the marker still in the database? A removal does not destroy the handle.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(MapApi.markerLives(marker(self, "exists")));
            }
        });
        // info() — the snapshot escape hatch: exactly what markers.list() used to hand back.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "info");
                if(mk == null)
                    return LuaValue.NIL;
                MiniMap.Location sl = MapApi.sessloc();
                Coord2d prc = AddonManager.playerPos();
                LuaTable t = new LuaTable();
                if(mk.nm != null)
                    t.set("name", LuaValue.valueOf(mk.nm));
                t.set("seg", LuaValue.valueOf(Long.toString(mk.seg)));   // 64-bit id → decimal string
                t.set("tc", AddonManager.xy(mk.tc.x, mk.tc.y));
                if(mk instanceof MapFile.PMarker) {
                    MapFile.PMarker pm = (MapFile.PMarker)mk;
                    t.set("type", LuaValue.valueOf("player"));
                    if(pm.color != null)
                        t.set("color", AddonManager.color(pm.color));
                    t.set("onmap", LuaValue.valueOf(pm.onmap));
                } else if(mk instanceof MapFile.SMarker) {
                    MapFile.SMarker sm = (MapFile.SMarker)mk;
                    t.set("type", LuaValue.valueOf("system"));
                    if((sm.res != null) && (sm.res.name != null))
                        t.set("icon", LuaValue.valueOf(sm.res.name));
                }
                if((sl != null) && (mk.seg == sl.seg.id)) {
                    double wx = worldX(mk, sl), wy = worldY(mk, sl);
                    t.set("x", LuaValue.valueOf(wx));
                    t.set("y", LuaValue.valueOf(wy));
                    if(prc != null)
                        t.set("dist", LuaValue.valueOf(Math.hypot(wx - prc.x, wy - prc.y)));
                }
                return t;
            }
        });
        return m;
    }

    // ---- resolution + the anchor conversion --------------------------------------------------------

    /** The live {@link MapFile.Marker} behind a method's {@code self} (or null — a removed marker resolves to none). */
    private static MapFile.Marker marker(LuaValue self, String method) {
        LuaMarker h = resolve(self);
        if(h == null)
            throw new LuaError("marker:" + method + "() — use a COLON call on a Marker object"
                + " (hafen.map.markers.list()[n], seg:markers()[n], markers.add(...))");
        return MapApi.markerByRef(h.ref);
    }

    /** The world X of a marker's tile centre in this session (the caller has checked the segment matches). */
    static double worldX(MapFile.Marker m, MiniMap.Location sl) {
        return ((m.tc.x - sl.tc.x) * MCache.tilesz.x) + (MCache.tilesz.x / 2);
    }

    /** The world Y of a marker's tile centre in this session (the caller has checked the segment matches). */
    static double worldY(MapFile.Marker m, MiniMap.Location sl) {
        return ((m.tc.y - sl.tc.y) * MCache.tilesz.y) + (MCache.tilesz.y / 2);
    }

    /**
     * The {@code {gridId, x, y}} anchor of a marker — the whole point of the entity (see the class note).
     * The live path first (synchronous, and the common case), the database path second (asynchronous, and
     * the only one that can answer for another segment); nil when neither can answer <i>yet</i>.
     */
    static LuaValue anchorOf(MapFile.Marker m) {
        MapFile file = MapApi.mapfile();
        if((m == null) || (file == null))
            return LuaValue.NIL;
        MiniMap.Location sl = MapApi.sessloc();
        if((sl != null) && (m.seg == sl.seg.id)) {
            MCache mc = AddonManager.mcache();
            if(mc != null) {
                Coord wt = m.tc.sub(sl.tc);                    // the marker's tile, in SESSION tile coords
                try {
                    MCache.Grid g = mc.getgrid(wt.div(MCache.cmaps));
                    if(g != null)
                        return MapApi.anchor(g.id, wt.x - g.ul.x, wt.y - g.ul.y);
                } catch(RuntimeException e) {
                    /* that ground is not streamed in — the database still knows it; fall through */
                }
            }
        }
        Coord sc = m.tc.div(MCache.cmaps);                     // floor-division: the segment GRID coord
        MapFile.Grid g = MapApi.gridAtIn(file, MapApi.segIn(file, m.seg), sc);
        if(g == null)
            return LuaValue.NIL;                               // loading, or ground the DB never recorded
        return MapApi.anchor(g.id, m.tc.x - (sc.x * MCache.cmaps.x), m.tc.y - (sc.y * MCache.cmaps.y));
    }
}
