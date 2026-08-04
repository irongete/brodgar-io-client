package io.brodgar.addon;

import haven.Coord;
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
import java.util.Map;

/**
 * A <b>Mask object</b> — which tiles of one recorded grid one overlay tag covers ({@link haven.MapFile.Overlay}):
 * the claim, village claim or province the client wrote down when the character walked over that ground. Spec
 * {@code 037-map-database}, task 037.3.
 *
 * <p><b>The tag is the identity, not the resource.</b> A recorded overlay is (a resource, a
 * {@code boolean[100*100]}), and what a player calls "claims" is a <i>tag</i> on that resource
 * ({@link MCache.ResOverlay#tags()}); several resources may carry the same tag, so a mask is the <b>union</b>
 * of all of them — which is exactly what {@code DataGrid.olrender(off, tag)} composites onto one image, and
 * the only thing a name can address (D-093, as the icon categories collapse their sub-ids).
 *
 * <p><b>The tag space is the resources', so it is OPEN.</b> {@code grid:overlay("nosuchthing")} is
 * {@code nil}, not an error: the client cannot know what tags a server's overlay resources declare.
 * {@code grid:overlays()} is the census that makes a {@code nil} readable. That is deliberately the opposite
 * of {@code hafen.map.overlay(tag)}, whose three <i>display switches</i> the client itself owns and which
 * therefore refuses an unknown tag (D-072 both ways).
 *
 * <p><b>Nothing crosses but the answer.</b> A mask holds its grid id and its tag and re-resolves through
 * {@link MapApi#maskIn} on every call, so a stashed handle tracks the database (D-094 — a {@code Grid} lives
 * in a weak {@code CacheMap} and is rebuilt from disk after an eviction, so it is the published id that is
 * stable, never the Java object). It also means the 10 000 booleans stay in Java: {@code :covers(c)} answers
 * one tile with the same {@code {x,y}} coord {@code grid:tile(c)} takes, and {@code :count()}/{@code :area()}
 * answer the whole-mask questions without ever building a table per tile.
 *
 * <p><b>{@code nil} keeps its one meaning here too</b> — the grid, or an overlay resource on it, has not
 * finished loading (the feature's one load model, D-095: kick the load, answer nil, ask again next tick).
 */
public final class LuaMask {
    /** The recorded grid this mask belongs to — the server's grid id. */
    public final long grid;
    /** The overlay tag ({@code "cplot"}, {@code "vlg"}, {@code "realm"}, …) — the other half of the identity. */
    public final String tag;

    private LuaMask(long grid, String tag) {
        this.grid = grid;
        this.tag = tag;
    }

    /** {@code tostring(mask)}: {@code Mask(<tag>@<gridId>)}. */
    public String toString() {
        return "Mask(" + tag + "@" + Long.toString(grid) + ")";
    }

    /** An interned Mask object for {@code (grid, tag)} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, long grid, String tag) {
        return owner.mapMasks.of(grid, tag);
    }

    /** The {@code LuaMask} behind a Lua value, or {@code null} for anything that is not a Mask object. */
    static LuaMask resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMask) ? (LuaMask)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's Mask interning cache and metatable ({@link Addon#mapMasks}); the {@link LuaMapGrid} shape. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (grid, tag)} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(long grid, String tag) {
            drain();
            String key = Long.toString(grid) + "\0" + tag;   // \0 cannot occur in a tag
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMask(grid, tag), meta());
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
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Mask metatable ------------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Mask"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMask h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Mask(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // tag() — the overlay tag this mask is for; answers from the handle alone.
        m.set("tag", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "tag").tag);
            }
        });
        // grid() — the recorded Grid it belongs to (D-066: the relation, not a stored id).
        m.set("grid", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaMapGrid.of(owner, handle(self, "grid").grid);
            }
        });
        // exists() — does that grid still carry this tag? False once the ground was re-recorded without it.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(mask(handle(self, "exists")) != null);
            }
        });
        // covers(c) — is the within-grid tile c inside the overlay? nil while the grid is still loading.
        // The same {x,y} coord grid:tile(c) takes, so the two reads compose over one loop.
        m.set("covers", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue c) {
                Coord tc = MapApi.tileArg(c, "mask:covers");
                boolean[] ol = mask(handle(self, "covers"));
                return (ol == null) ? LuaValue.NIL : LuaValue.valueOf(ol[tc.x + (tc.y * MCache.cmaps.x)]);
            }
        });
        // count() — how many of the grid's tiles it covers (0..10000), or nil while the grid is loading.
        m.set("count", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                boolean[] ol = mask(handle(self, "count"));
                return (ol == null) ? LuaValue.NIL : LuaValue.valueOf(count(ol));
            }
        });
        // area() — the bounding box of the covered tiles, {x,y,w,h} in within-grid tile coords; nil while
        // the grid is loading AND for a mask that covers nothing (there is no box around no tiles).
        m.set("area", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return area(mask(handle(self, "area")));
            }
        });
        // info() — the snapshot escape hatch, for logging: {tag, grid, count, area?}.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMask h = handle(self, "info");
                boolean[] ol = mask(h);
                if(ol == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("tag", LuaValue.valueOf(h.tag));
                t.set("grid", LuaValue.valueOf(Long.toString(h.grid)));
                t.set("count", LuaValue.valueOf(count(ol)));
                LuaValue a = area(ol);
                if(!a.isnil())
                    t.set("area", a);
                return t;
            }
        });
        return m;
    }

    /** The live union mask behind a handle, or null (the grid or an overlay resource is still loading). */
    private static boolean[] mask(LuaMask h) {
        return MapApi.maskIn(MapApi.mapfile(), h.grid, h.tag);
    }

    private static int count(boolean[] ol) {
        int n = 0;
        for(int i = 0; i < ol.length; i++) {
            if(ol[i])
                n++;
        }
        return n;
    }

    /** The bounding box of the covered tiles as {@code {x,y,w,h}}, or nil when nothing is covered. */
    private static LuaValue area(boolean[] ol) {
        if(ol == null)
            return LuaValue.NIL;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for(int i = 0; i < ol.length; i++) {
            if(!ol[i])
                continue;
            int x = i % MCache.cmaps.x, y = i / MCache.cmaps.x;
            if(x < x0) x0 = x;
            if(y < y0) y0 = y;
            if(x > x1) x1 = x;
            if(y > y1) y1 = y;
        }
        if(x1 < 0)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(x0));
        t.set("y", LuaValue.valueOf(y0));
        t.set("w", LuaValue.valueOf((x1 - x0) + 1));
        t.set("h", LuaValue.valueOf((y1 - y0) + 1));
        return t;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMask handle(LuaValue self, String method) {
        LuaMask h = resolve(self);
        if(h == null)
            throw new LuaError("mask:" + method + "() — use a COLON call on a Mask object"
                + " (grid:overlay(tag))");
        return h;
    }
}
