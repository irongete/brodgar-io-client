package io.brodgar.addon;

import haven.AddonWidgets;
import haven.Coord;
import haven.MapFile;
import haven.MCache;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Grid object</b> — one 100&times;100-tile square of the map, and the one entity <b>both</b> halves of
 * the world hand back ({@link MapFile.Grid} on disk, {@link MCache.Grid} streamed in). Spec
 * {@code 037-map-database}, unified by {@code 039-uniform-api} task 039.4.
 *
 * <p><b>One entity, two doors, because both halves already keyed on the same number.</b> A grid id comes
 * from the server ({@code MCache.Grid.fill}, layer {@code "m"}), so it is the same for every player and no
 * merge ever moves it — and {@code s:world():grid()} publishes it beside {@code hafen.map():grid()},
 * which interns on it. A grid was therefore never two things: it is one thing with a live half and a
 * recorded half, and the two questions are {@link #methods :live()} (streamed right now?) and
 * {@code :exists()} (written down?). Ground under the player that has not been saved yet is live and does
 * not exist; ground explored last year is the mirror; and where both answer, {@code grid:tile(c)} and
 * {@code s:world():tile(p)} agree by NAME — which stops being a cross-check you have to remember to
 * make and becomes a property of the object.
 *
 * <p><b>Two kinds of read, and they answer at different times.</b> Where the grid <i>sits</i> —
 * {@code :id}, {@code :segmentCoord}, {@code :position}, {@code :segment} — comes from the small
 * {@code gridinfo} record and answers immediately. What the grid <i>contains</i> — {@code :tile},
 * {@code :height}, {@code :modified} — needs the grid file itself, which resolves on {@link haven.Defer}:
 * the first call <b>kicks the load and answers nil</b>, a later one answers. That is the one load model the
 * database applies everywhere; nothing blocks the UI thread and no {@link haven.Loading} reaches Lua.
 *
 * <p><b>Recorded, not live.</b> Those tiles are what the ground looked like when the client last saw it —
 * for the ground under the player that is now (it re-records the 3&times;3 grids around them whenever they
 * change), and for ground explored a year ago it is a year old, which {@code :modified()} reports.
 *
 * <p><b>A grid carries its MASKS</b>: {@code :mask():list()} is the census of the tags that covered this
 * ground — a personal claim, a village claim, a province — and {@code :mask():get(tag)} the {@link LuaMask}
 * saying which of its tiles. Those are the <i>recorded</i> masks; the client's own display switches for the
 * same features are {@code hafen.map():display()}, and they are a different thing entirely.
 *
 * <p><b>And it can be DRAWN</b>: {@code :image(level)} is the recorded minimap drawing of this ground — the
 * very render the corner minimap shows — as an ordinary image handle, and {@code :overlayImage(tag)} the
 * same for one recorded mask. Both are built on {@link haven.Defer} and follow the load model above: the
 * first call kicks the render and answers nil. See {@link MapImages}.
 *
 * <p><b>Interned on the LOGIN and the GRID ID</b> (D-063), not on the Java object: a {@code Grid} lives in a
 * weak {@code CacheMap} and is rebuilt from disk after an eviction, so identity interning would go stale
 * invisibly. Every method re-resolves, so a stashed handle tracks both halves of the world.
 *
 * <p><b>The login is half the handle</b> (audit2 B05). The id is the server's and names the same ground to
 * everybody, but the two live questions are one character's: {@code grid:live()} asks whether that character
 * has it streamed in, and the recorded half is read out of the database that character's HUD holds. So the
 * handle records the login it was minted through — the addressed one for {@code s:world():grid()}, the
 * character on screen for {@code hafen.map():grid()} — and answers about it afterwards, rather than about
 * whichever character happens to be drawn when the question is asked.
 */
public final class LuaMapGrid {
    /** The server's grid id — the identity of the ground, and the shareable anchor. */
    public final long id;
    /** The login this handle was minted through — whose streaming and whose database it answers about. */
    public final String user;

    private LuaMapGrid(String user, long id) {
        this.user = user;
        this.id = id;
    }

    /** {@code tostring(grid)}: {@code Grid(<id>)}. */
    public String toString() {
        return "Grid(" + Long.toString(id) + ")";
    }

    /** An interned Grid object for {@code id} as {@code user} sees it — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String user, long id) {
        return owner.mapGrids.of(user, id);
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
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code id} in {@code user} — a hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String user, long id) {
            drain();
            String key = user + "@" + Long.toString(id);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMapGrid(user, id), meta());
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
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Grid metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("grid", methods(owner),
            "one map grid"));
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
        // exists() — is this grid in the DATABASE? (Its position record is what says so.) Its twin is
        // :live(): a grid you are standing on that has not been written down yet is live and does not exist,
        // and one you explored last year is the mirror. One entity, two halves, one question each.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(info(handle(self, "exists")) != null);
            }
        });
        // live() — is this grid STREAMED IN right now? A plain lookup of the loaded grids, never a request:
        // asking whether ground is here must not fetch it.
        m.set("live", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(streamed(handle(self, "live")));
            }
        });
        // segmentCoord() — this grid's coord WITHIN ITS SEGMENT, {x,y}. A lattice cell, not a place, and
        // client-local (a merge re-bases it): the coord to walk a neighbourhood with, never one to store.
        m.set("segmentCoord", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.GridInfo gi = info(handle(self, "segmentCoord"));
                return (gi == null) ? LuaValue.NIL : AddonManager.xy(gi.sc.x, gi.sc.y);
            }
        });
        // position() — this grid's upper-left corner, as a Position, built from the grid's OWN id with a
        // zero within-grid offset. So it is durable wherever the grid is known at all, and :x()/:y() answer
        // for this session only where the ground can be located here — which is the Position type saying
        // once, in one shape, what the old :pos() had to say as a nil.
        m.set("position", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMapGrid h = handle(self, "position");
                if(!streamed(h) && (info(h) == null))
                    return LuaValue.NIL;
                return LuaPosition.ofAnchor(owner, h.id, 0, 0);
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
        // modified() — when this grid was last recorded (ms since the epoch), or nil until the data lands.
        // It is also the honest answer to "how old is what :tile() just told me".
        m.set("modified", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MapFile.Grid g = data(handle(self, "modified"));
                return (g == null) ? LuaValue.NIL : LuaValue.valueOf((double)g.mtime);
            }
        });
        // mask() — the recorded MASKS on this grid, as a collection: :list() is the census of the tags that
        // covered this ground (a personal claim, a village claim, a province) and :get(tag) is the Mask
        // saying which of its tiles. It is named for what the members ARE — a Mask, mask:covers(c) — so the
        // recorded half and the client's DISPLAY switches stop wearing one word. The tag space belongs to
        // the SERVER's overlay resources, so an unknown tag is plain nil and the census is what makes that
        // nil readable — deliberately the opposite of hafen.map():display(), whose four switches the client
        // itself owns and which refuses a tag it does not have. Both are empty until the grid, and every
        // overlay resource on it, has resolved.
        m.set("mask", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaMapGrid h = handle(a.arg1(), "mask");
                if(Args.passed(a, 2))
                    throw new LuaError("grid:mask(tag) is a COLLECTION: grid:mask():get(tag) is the Mask for"
                        + " one tag and grid:mask():list() is every tag this grid carries");
                return maskCollection(owner, h.user, h.id);
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
        // overlayImage(tag) — the same for one recorded mask, drawn in the overlay resource's own colour
        // (DataGrid.olrender, the image the map window composites over the ground). Nil for a tag this grid
        // does not carry — grid:mask():list() is the census — and nil while it renders.
        m.set("overlayImage", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue tag) {
                LuaMapGrid h = handle(self, "overlayImage");
                if(tag.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
                    throw new LuaError("grid:overlayImage(tag): tag is an overlay tag string (\"cplot\","
                        + " \"vlg\", \"realm\") — grid:mask():list() is the census of the ones this"
                        + " grid carries");
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
                LuaValue pos = MapApi.gridWorldUL(gi, h.user);
                if(!pos.isnil())
                    t.set("pos", pos);
                t.set("live", LuaValue.valueOf(streamed(h)));
                t.set("loaded", LuaValue.valueOf(g != null));
                // audit2 B15: has a picture of this ground been GIVEN UP ON? A grid:image()/overlayImage()
                // that answers nil while it renders and one that answers nil because the render broke were
                // the same word, so the page's one answer to "why can I not see it" could not tell them
                // apart. A failure is retried on the next ask (MapImages.RETRIES); this is true only once
                // the budget is spent and nothing more is coming.
                t.set("failed", LuaValue.valueOf(MapImages.failedFor(owner, h.id)));
                if(g != null)
                    t.set("mtime", LuaValue.valueOf((double)g.mtime));
                t.set("size", LuaWidget.whTable(MCache.cmaps));   // a span in TILES, and a size is {w=, h=}
                return t;
            }
        });
        return m;
    }

    /** Where this grid sits (segment + segment coord) — the small record, available without a disk read. */
    private static MapFile.GridInfo info(LuaMapGrid h) {
        return MapApi.gridInfoIn(MapApi.mapfile(h.user), h.id);
    }

    /** The grid's tile data — kicks the load and answers null until it lands (the one load model). */
    private static MapFile.Grid data(LuaMapGrid h) {
        return MapApi.gridDataIn(MapApi.mapfile(h.user), h.id);
    }

    /** Is this grid streamed in <b>by that character</b> right now? A plain lookup — never
     *  {@code MCache.getgrid}, which would ask the server. */
    private static boolean streamed(LuaMapGrid h) {
        return AddonWidgets.gridWorldUL(AddonManager.mcache(h.user), h.id) != null;
    }

    /**
     * {@code grid:mask()} — the recorded masks on one grid, as a collection of {@link LuaMask}. A VIEW,
     * like every collection owned by an entity: it re-derives from the grid id on every call and holds
     * nothing between them, so it cannot outlive the grid and needs no pruning of its own.
     */
    private static LuaValue maskCollection(final Addon owner, final String user, final long id) {
        return LuaCollection.create("grid:mask()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                List<String> tags = MapApi.gridTags(MapApi.mapfile(user), id);
                if(tags == null)
                    return out;             // still loading: an empty census, and it fills in next tick
                for(String t : tags)
                    out.add(LuaMask.of(owner, id, t));
                return out;
            }

            public String needle(LuaValue member) {
                LuaMask h = LuaMask.resolve(member);
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
                if(key.type() != LuaValue.TSTRING)      // in LuaJ a NUMBER also answers isstring()
                    throw new LuaError("grid:mask():get(tag): tag is an overlay tag string (\"cplot\","
                        + " \"vlg\", \"realm\") \u2014 grid:mask():list() is the census of the ones this"
                        + " grid carries");
                String t = key.tojstring();
                return (MapApi.maskIn(MapApi.mapfile(user), id, t) == null)
                    ? LuaValue.NIL : LuaMask.of(owner, id, t);
            }

            /** The key is the overlay tag the server's resources declare. */
            public String keyName() {
                return "tag";
            }
        }, null);
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMapGrid handle(LuaValue self, String method) {
        LuaMapGrid h = resolve(self);
        if(h == null)
            throw new LuaError("grid:" + method + "() — use a COLON call on a Grid object"
                + " (hafen.map():grid():get(id), session:world():grid():at(p), seg:grid():get(sc))");
        return h;
    }
}
