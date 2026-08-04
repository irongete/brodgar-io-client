package io.brodgar.addon;

import haven.Coord;
import haven.MapFile;
import haven.MCache;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Grid object</b> — one recorded 100&times;100-tile square of the explored map ({@link MapFile.Grid}):
 * the tiles and heights the client wrote to disk when the character walked over that ground. Spec
 * {@code 037-map-database}, task 037.2.
 *
 * <p><b>A grid id is THE anchor.</b> It comes from the server ({@code MCache.Grid.fill}, layer {@code "m"}),
 * so it is the same number for every player and no merge ever moves it — which is why
 * {@code hafen.map.grid(gridId)} is the one door that crosses from the live world into the database, and why
 * {@code {gridId, x, y}} is the shape an addon saves or shares ({@code hafen.world.gridPos}). A grid's
 * <i>segment</i> coordinate ({@link #methods sc()}) is the other half of that bridge and is client-local:
 * read it, do not store it (see {@link LuaSegment}).
 *
 * <p><b>Two kinds of read, and they answer at different times.</b> Where the grid <i>sits</i> — {@code :id},
 * {@code :sc}, {@code :pos}, {@code :segment} — comes from the small {@code gridinfo} record and answers
 * immediately. What the grid <i>contains</i> — {@code :tile}, {@code :height}, {@code :mtime} — needs the
 * grid file itself, which resolves on {@link haven.Defer}: the first call <b>kicks the load and answers
 * nil</b>, a later one answers. That is the one load model this feature applies everywhere; nothing blocks
 * the UI thread and no {@link haven.Loading} ever reaches Lua.
 *
 * <p><b>Recorded, not live.</b> These tiles are what the ground looked like when the client last saw it —
 * for the ground under the player that is now (the client re-records the 3&times;3 grids around them
 * whenever they change), and for ground explored a year ago it is a year old. The live terrain is
 * {@code hafen.world.tile}, and where both answer they agree by NAME: a recorded tile carries the tileset
 * <i>resource</i>, not the session-local tile id the live half hands out.
 *
 * <p><b>A grid also carries its OVERLAYS</b> (037.3): {@code :overlays()} is the census of the tags that
 * covered this ground — a personal claim, a village claim, a province — and {@code :overlay(tag)} is the
 * {@link LuaMask} saying which of its tiles. Those are the <i>recorded</i> masks; the client's own display
 * switches for the same features are {@code hafen.map.overlay(tag)}, and they are a different thing entirely.
 *
 * <p><b>And it can be DRAWN</b> (037.4): {@code :image(lvl)} is the recorded minimap drawing of this ground —
 * the very render the corner minimap shows — as an ordinary image handle, and {@code :overlayImage(tag)} the
 * same for one recorded mask. Both are built on {@link haven.Defer} and follow the load model above: the first
 * call kicks the render and answers nil. See {@link MapImages}.
 *
 * <p><b>Interned on the GRID ID</b> (D-063), not on the Java object: a {@code Grid} lives in a weak
 * {@code CacheMap} and is rebuilt from disk after an eviction, so identity interning would go stale
 * invisibly. Every method re-resolves through {@link MapApi}, so a stashed handle tracks the database.
 */
public final class LuaMapGrid {
    /** The server's grid id — the whole state of a handle, its identity, and the shareable anchor. */
    public final long id;

    private LuaMapGrid(long id) {
        this.id = id;
    }

    /** {@code tostring(grid)}: {@code Grid(<id>)}. */
    public String toString() {
        return "Grid(" + Long.toString(id) + ")";
    }

    /** An interned Grid object for {@code id} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long id) {
        return owner.mapGrids.of(id);
    }

    /** The {@code LuaMapGrid} behind a Lua value, or {@code null} for anything that is not a Grid object. */
    static LuaMapGrid resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMapGrid) ? (LuaMapGrid)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Grid interning cache and metatable ({@link Addon#mapGrids}); the {@link LuaGob} shape. */
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
            LuaValue v = LuaValue.userdataOf(new LuaMapGrid(id), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref gr = (Ref)r;
                if(live.get(gr.key) == gr)
                    live.remove(gr.key);
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

    // ---- the Grid metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Grid"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMapGrid h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Grid(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the SERVER's grid id as a decimal string: this handle's identity, and the anchor you save.
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(Long.toString(handle(self, "id").id));
            }
        });
        // exists() — is this grid still in the database? (Its position record is what says so.)
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(info(handle(self, "exists")) != null);
            }
        });
        // sc() — this grid's coord WITHIN ITS SEGMENT, {x,y}. Client-local (a merge re-bases it): the coord
        // to walk a neighbourhood with (seg:grid(sc.x+1 …)), never the coord to store.
        m.set("sc", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.GridInfo gi = info(handle(self, "sc"));
                return (gi == null) ? LuaValue.NIL : AddonManager.xy(gi.sc.x, gi.sc.y);
            }
        });
        // pos() — where this grid's upper-left corner is in THIS session's world coords, or nil for a grid
        // outside the player's current segment (which has no world position this session at all). The
        // recorded→live half of the bridge; hafen.world.gridPos is the live→recorded half.
        m.set("pos", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return MapApi.gridWorldUL(info(handle(self, "pos")));
            }
        });
        // segment() — the Segment this grid belongs to (D-066: the relation, not a stored id).
        m.set("segment", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.GridInfo gi = info(handle(self, "segment"));
                return (gi == null) ? LuaValue.NIL : LuaSegment.of(owner, gi.seg);
            }
        });
        // tile(c) — the RECORDED tile at a within-grid tile coord: {name = the tileset resource, prio}.
        // Nil until the grid data is off the disk (the call starts that). There is no `id` here on purpose:
        // the live tile id is a session-local tileset number, while the map file stores the resource — the
        // name is the thing the two halves can be compared on.
        m.set("tile", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue c) {
                Coord tc = MapApi.tileArg(c, "grid:tile");
                MapFile.Grid g = data(handle(self, "tile"));
                if(g == null)
                    return LuaValue.NIL;
                int ti = g.gettile(tc);
                if((ti < 0) || (ti >= g.tilesets.length) || (g.tilesets[ti] == null))
                    return LuaValue.NIL;
                MapFile.TileInfo inf = g.tilesets[ti];
                LuaTable t = new LuaTable();
                if((inf.res != null) && (inf.res.name != null))
                    t.set("name", LuaValue.valueOf(inf.res.name));
                t.set("prio", LuaValue.valueOf(inf.prio));
                return t;
            }
        });
        // height(c) — the recorded z of a within-grid tile, or nil until the grid data lands.
        m.set("height", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue c) {
                Coord tc = MapApi.tileArg(c, "grid:height");
                MapFile.Grid g = data(handle(self, "height"));
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf(g.getfz(tc));
            }
        });
        // mtime() — when this grid was last recorded (ms since the epoch), or nil until the data lands.
        // It is also the honest answer to "how old is what :tile() just told me".
        m.set("mtime", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Grid g = data(handle(self, "mtime"));
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf((double)g.mtime);
            }
        });
        // overlays() — every OVERLAY TAG this grid carries (a claim, a village claim, a province covered it
        // when the client wrote the grid down), sorted; nil until the grid data — and every overlay resource
        // on it — is resolved. It is the census that makes grid:overlay(tag)'s nil readable, because the tag
        // space belongs to the server's resources and no client-side list of it can be complete.
        m.set("overlays", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                List<String> tags = MapApi.gridTags(MapApi.mapfile(), handle(self, "overlays").id);
                if(tags == null)
                    return LuaValue.NIL;
                LuaTable out = new LuaTable();
                for(int i = 0; i < tags.size(); i++)
                    out.set(i + 1, LuaValue.valueOf(tags.get(i)));
                return out;
            }
        });
        // overlay(tag) — the Mask of which tiles that overlay covers here, or nil for a tag this grid does
        // not carry (and, as everywhere on this object, for data still coming off the disk). An unknown tag
        // is NOT an error: the tags are declared by the overlay resources, not by the client.
        m.set("overlay", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue tag) {
                LuaMapGrid h = handle(self, "overlay");
                if(tag.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
                    throw new LuaError("grid:overlay(tag): tag is an overlay tag string (\"cplot\", \"vlg\","
                        + " \"realm\", …) — grid:overlays() lists the ones this grid carries");
                String t = tag.tojstring();
                return (MapApi.maskIn(MapApi.mapfile(), h.id, t) == null)
                    ? LuaValue.NIL : LuaMask.of(owner, h.id, t);
            }
        });
        // image(lvl) — the recorded MINIMAP DRAWING of this ground, as an ordinary image handle (037.4): the
        // very render the corner minimap draws, built on Defer and answered nil until it lands. lvl defaults
        // to 0 (the ground itself); each level above covers twice as much in each direction at the same
        // 100x100 pixels, so a level changes the SCALE and never the size.
        m.set("image", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue lvl) {
                LuaMapGrid h = handle(self, "image");
                return MapImages.gridImage(owner, h.id, MapImages.levelArg(lvl, "grid:image"));
            }
        });
        // overlayImage(tag) — the same for one recorded overlay mask, drawn in the overlay's own colour
        // (DataGrid.olrender, the image the map window composites over the ground). Nil for a tag this grid
        // does not carry — grid:overlays() is the census — and nil while it renders.
        m.set("overlayImage", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue tag) {
                LuaMapGrid h = handle(self, "overlayImage");
                if(tag.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
                    throw new LuaError("grid:overlayImage(tag): tag is an overlay tag string (\"cplot\","
                        + " \"vlg\", \"realm\", …) — grid:overlays() lists the ones this grid carries");
                return MapImages.gridOverlayImage(owner, h.id, tag.tojstring());
            }
        });
        // info() — the snapshot escape hatch, including `loaded`: whether the tile data is here YET.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMapGrid h = handle(self, "info");
                MapFile.GridInfo gi = info(h);
                if(gi == null)
                    return LuaValue.NIL;
                MapFile.Grid g = data(h);
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf(Long.toString(h.id)));
                t.set("seg", LuaValue.valueOf(Long.toString(gi.seg)));
                t.set("sc", AddonManager.xy(gi.sc.x, gi.sc.y));
                LuaValue pos = MapApi.gridWorldUL(gi);
                if(!pos.isnil())
                    t.set("pos", pos);
                t.set("loaded", LuaValue.valueOf(g != null));
                if(g != null)
                    t.set("mtime", LuaValue.valueOf((double)g.mtime));
                t.set("size", AddonManager.xy(MCache.cmaps.x, MCache.cmaps.y));
                return t;
            }
        });
        return m;
    }

    /** Where this grid sits (segment + segment coord) — the small record, available without a disk read. */
    private static MapFile.GridInfo info(LuaMapGrid h) {
        return MapApi.gridInfoIn(MapApi.mapfile(), h.id);
    }

    /** The grid's tile data — kicks the load and answers null until it lands (the one load model). */
    private static MapFile.Grid data(LuaMapGrid h) {
        return MapApi.gridDataIn(MapApi.mapfile(), h.id);
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMapGrid handle(LuaValue self, String method) {
        LuaMapGrid h = resolve(self);
        if(h == null)
            throw new LuaError("grid:" + method + "() — use a COLON call on a Grid object"
                + " (hafen.map.grid(id), seg:grid(sc), seg:grids(area)[n])");
        return h;
    }
}
