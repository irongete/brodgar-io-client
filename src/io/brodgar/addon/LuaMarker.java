package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.MapFile;
import haven.MCache;
import haven.MiniMap;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Marker object</b> — one pin in the recorded map database ({@link MapFile.Marker}): either a
 * <b>player</b> marker (your own, with a colour) or a <b>system</b> marker (a server or quest pin, with an
 * icon). The same markers the map window shows. Spec {@code 037-map-database}, task 037.2.
 *
 * <p><b>{@code marker:position()} is the whole point of the entity, and it is now just the type.</b> A
 * marker's own coordinates are {@code seg}&nbsp;+&nbsp;{@code tc} — a segment id this client invented and a
 * tile coord inside it — and a segment merge <b>rewrites both in place</b>. So that pair cannot be saved or
 * sent: after a merge it would not go nil, it would point at the wrong place. What comes back instead is a
 * {@link LuaPosition}, which is durable by construction because it holds the server's grid id. The
 * conversion that used to be a second verb beside {@code :pos()} is therefore gone: one place type, one
 * position verb, and nothing to convert.
 *
 * <p><b>The collection is {@code hafen.map():marker()}</b> — {@code :list} / {@code :count} / {@code :find}
 * over the canonical filter, {@code :nearest(filter)}, and the two unprotected writes {@code :add(name, p)} /
 * {@code :remove(m)}. A pin is created <b>bare</b> and configured with {@code m:color(...)} /
 * {@code m:onMap(b)}; those two are also reads, arity being the verb, and both persist immediately.
 *
 * <p><b>Interned on the marker ref</b>, which is the identity the engine actually has: a
 * {@link MapFile.Marker} is loaded once and then mutated in place (a merge rewrites its fields; it is never
 * re-minted), so object identity is stable and {@link MapApi}'s ref map — the one 037.1 already handed to
 * Lua as a number — is exactly the right key. The ref is the <b>client's</b>: there is one database per
 * {@code (store, filename)}, so two characters on one server read the same {@code Marker} objects and a
 * handle minted while one of them is drawn names the same pin when the other is.
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

    // ---- the members (hafen.map():marker(), seg:markers) -------------------------------------------

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

    /**
     * The same markers as {@link #collection}, unfiltered, as the member list {@code hafen.map():marker()}
     * hands to {@link LuaCollection} — which owns the filtering there, so the canonical filter has one
     * implementation across every collection in the API rather than one per section.
     */
    static List<LuaValue> members(Addon owner, Long seg) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        for(MapFile.Marker m : MapApi.markerList(seg))
            out.add(of(owner, MapApi.markerId(m)));
        return out;
    }

    /** What a <b>string</b> filter matches on a Marker member: its label. Null for a marker with none. */
    static String name(LuaValue member) {
        LuaMarker h = resolve(member);
        MapFile.Marker m = (h == null) ? null : MapApi.markerByRef(h.ref);
        return (m == null) ? null : m.nm;
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
        mt.set(LuaValue.INDEX, Retired.methodIndex("marker", methods(owner)));
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
        // segmentTile() — the marker's SEGMENT tile coord, {x,y}: where it really lives in the database. A
        // lattice cell, not a place — client-local, since a merge rewrites it — so this is what you look at
        // and :position() is what you save.
        m.set("segmentTile", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "segmentTile");
                return (mk == null) ? LuaValue.NIL : AddonManager.xy(mk.tc.x, mk.tc.y);
            }
        });
        // position() — the marker's place, as a Position, at the CENTRE of the tile it names. It IS the old
        // :anchor(): a Position is durable by construction, so the conversion that used to be a second verb
        // is now the type. A marker in the player's own segment answers from sessloc arithmetic; one in
        // another explored area has no world coord this session, so it comes back holding its anchor — it
        // still saves, still names a tile, and :x() reports nil. Nil only for ground the database has no
        // grid id for at all.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return positionOf(owner, marker(self, "position"));
            }
        });
        // distance() — how far the player is from it, in world units; nil when it is not in this segment.
        m.set("distance", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Marker mk = marker(self, "distance");
                MiniMap.Location sl = MapApi.sessloc();
                Coord2d prc = AddonManager.playerPos();
                if((mk == null) || (sl == null) || (prc == null) || (mk.seg != sl.seg.id))
                    return LuaValue.NIL;
                return LuaValue.valueOf(Math.hypot(worldX(mk, sl) - prc.x, worldY(mk, sl) - prc.y));
            }
        });
        // color() / color(r, g, b [, a]) / color(c) — a player marker's pin colour. Arity is the verb: no
        // argument reads {r,g,b,a} (nil on a system marker), an argument writes and returns self so it
        // chains off :add(). A colour VALUE passes straight back through (§2.8), so m:color(other:color())
        // is the one canonical setter rather than a second style.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                MapFile.Marker mk = marker(self, "color");
                if(!Args.passed(a, 2)) {
                    if(!(mk instanceof MapFile.PMarker) || (((MapFile.PMarker)mk).color == null))
                        return LuaValue.NIL;
                    return AddonManager.color(((MapFile.PMarker)mk).color);
                }
                MapFile.PMarker pm = player(mk, "color");
                pm.color = colorArg(a);
                pm.update(true);                 // persists (defersave) and bumps markerseq -> MarkersChanged
                return self;
            }
        });
        // onMap() / onMap(b) — is this player marker also drawn on the main map? Read, or write and chain.
        m.set("onMap", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                MapFile.Marker mk = marker(self, "onMap");
                LuaValue v = Args.written(a, 2, "marker:onMap", "on");
                if(v == null) {
                    if(!(mk instanceof MapFile.PMarker))
                        return LuaValue.NIL;
                    return LuaValue.valueOf(((MapFile.PMarker)mk).onmap);
                }
                if(!v.isboolean())
                    throw new LuaError("marker:onMap(on): on must be true or false");
                MapFile.PMarker pm = player(mk, "onMap");
                pm.onmap = v.toboolean();
                pm.update(true);
                return self;
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
                + " (hafen.map():marker():list()[n], seg:markers()[n],"
                + " hafen.map():marker():add(name, p))");
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
     * The <b>Position</b> of a marker — the centre of the tile it names, which is the point its own session
     * {@code x,y} always reported, so the round trip lands on that tile and not on its corner.
     *
     * <p>Two paths, and which one runs is a fact about the player rather than about the marker. In the
     * player's <b>own</b> segment it is {@code sessloc} arithmetic to a world coordinate, and the Position
     * derives its durable form from there like any other. <b>Anywhere else</b> there is no world coordinate
     * this session at all, so the Position is built holding its anchor: the segment grid coord it sits in,
     * the grid id the database recorded there, and the offset within that grid. That second path reads the
     * segment's own coord&rarr;id map and therefore answers <i>now</i> — where reading the grid's tiles off
     * the disk would have reported an explored place as unlocatable until the file landed.
     */
    static LuaValue positionOf(Addon owner, MapFile.Marker m) {
        MapFile file = MapApi.mapfile();
        if((m == null) || (file == null))
            return LuaValue.NIL;
        MiniMap.Location sl = MapApi.sessloc();
        if((sl != null) && (m.seg == sl.seg.id))
            return LuaPosition.ofWorld(owner, worldX(m, sl), worldY(m, sl));
        Coord sc = m.tc.div(MCache.cmaps);                     // floor-division: the segment GRID coord
        Long id = MapApi.recordedGridId(file, m.seg, sc);
        if(id == null)
            return LuaValue.NIL;                               // ground the database has no grid id for
        Coord gt = m.tc.sub(sc.mul(MCache.cmaps));             // the tile WITHIN that grid
        return LuaPosition.ofAnchor(owner, id.longValue(),
                                    (gt.x * MCache.tilesz.x) + (MCache.tilesz.x / 2),
                                    (gt.y * MCache.tilesz.y) + (MCache.tilesz.y / 2));
    }

    /** The player marker behind a write, or a refusal: a system marker's colour and flag are the server's. */
    private static MapFile.PMarker player(MapFile.Marker m, String verb) {
        if(!(m instanceof MapFile.PMarker))
            throw new LuaError("marker:" + verb + "(...): only a PLAYER marker can be written — a system"
                + " marker is the server's own pin (marker:type() says which)");
        return (MapFile.PMarker)m;
    }

    /** A colour write: positional components, or a colour value straight back out of a read (§2.8). */
    private static java.awt.Color colorArg(Varargs a) {
        LuaValue first = a.arg(2);
        if(first.istable())
            return AddonManager.luaColor(first, null);
        if(!first.isnumber())
            throw new LuaError("marker:color(r, g, b [, a]): the components are 0..255 — or pass a colour"
                + " value straight back, as marker:color(other:color())");
        int r = a.arg(2).toint(), g = a.arg(3).toint(), b = a.arg(4).toint();
        int al = Args.passed(a, 5) ? a.arg(5).toint() : 255;
        return new java.awt.Color(clamp(r), clamp(g), clamp(b), clamp(al));
    }

    private static int clamp(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }
}
