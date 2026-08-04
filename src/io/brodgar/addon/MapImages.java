package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Defer;
import haven.Locked;
import haven.MapFile;
import haven.MapSource;
import haven.MCache;
import haven.TexI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>minimap drawings</b> of the recorded map — {@code grid:image(lvl)} and {@code grid:overlayImage(tag)},
 * spec {@code 037-map-database}, task 037.4. The one place the map database produces a <i>picture</i> rather
 * than a number, and the picture is an ordinary {@link LuaImage} handle: whatever draws
 * {@code hafen.asset("icon.png")} draws a grid ({@code g:image}, {@code hafen.render.sprite}, and a
 * stylesheet's {@code bg = {image = …}} — which is the one way to put a map on the screen with <b>no Lua
 * running at the draw at all</b>).
 *
 * <p><b>The render is the client's own, on the client's own thread.</b> {@code DataGrid.render} /
 * {@code MapSource.drawmap} build a 100&times;100 {@code BufferedImage} out of the tileset resources — tens of
 * thousands of {@code getRGB}s and a resource load per tileset — so it runs on {@link Defer}, exactly as
 * {@code MiniMap.DisplayGrid.img()} runs it, and the {@link TexI} is built off-thread too (its GL upload is
 * lazy). The UI thread never renders: <b>the first call kicks the render and answers nil</b>, a later one
 * answers the handle. That is the feature's one load model (D-095) reaching its last surface — a read of a
 * stored world kicks the load and answers nil.
 *
 * <p><b>Level 0 is drawn the way the client draws it.</b> A level-0 image goes through {@code MapFile.View}
 * over the 3&times;3 grids around the target, because {@code MapSource.drawmap} blends tile <i>transitions</i>
 * across grid borders and a grid rendered alone would carry a seam the corner minimap does not have. Levels
 * above 0 are {@code ZoomGrid}s and render themselves ({@code DataGrid.render}), which is again what
 * {@code DisplayGrid} does.
 *
 * <p><b>Every image is 100&times;100 pixels, at every level</b> — {@code cmaps}, one pixel per tile at level
 * 0 and one pixel per {@code 2^lvl} tiles above it. So the level does not change the image's <i>size</i>, it
 * changes its <i>scale</i>: level 1 is the same 100&times;100 picture of four times the ground.
 * A caller therefore sizes the picture from {@code image:size()} (always {@code cmaps}) and knows how much
 * ground is in it from the level it asked for — {@code cmaps << lvl} tiles across. There is deliberately no
 * {@code tiles} field on {@code :info()}: it would be that one multiplication, phrased as state.
 *
 * <p><b>Bounded, interned, owned.</b> The cache is <b>per addon</b> and keyed by what was rendered — (segment,
 * level, level-coord) for a map image, (grid id, tag) for an overlay image — so two grids under one level-1
 * zoom grid share the one handle and the same call twice hands back the same object. It is an LRU bounded at
 * {@link #MAX} entries: a panel re-asks for the grids it is drawing every frame, so the visible ones stay and
 * the ones that scrolled away are disposed. Every handle is registered in {@link Addon#images} like any other
 * image, so {@code :dispose()}, {@code :reload}, disable and relogin all free the {@code TexI} — an
 * undisposed texture per grid is a GL leak by a new door.
 */
final class MapImages {
    private MapImages() {}

    /** The deepest zoom level a handle can ask for. Level 8 already composites 65 536 base grids. */
    static final int MAXLVL = 8;

    /**
     * How many rendered grid images one addon keeps. An 11&times;11 panel at two levels fits. Not {@code final}
     * only so the headless probe can narrow it and watch an eviction actually dispose; nothing at runtime
     * writes it.
     */
    static int MAX = 96;

    // ---- the per-addon bounded cache ---------------------------------------------------------------

    /** One rendered image: the pending render, the handle it becomes, and nothing else. */
    private static final class Entry {
        final String label;                 // what this picture is, for the handle's :path()/error text
        Defer.Future<TexI> future;          // in flight; null once it landed (or failed)
        LuaImage image;                     // the owned image, once the render landed
        LuaValue handle;                    // the stable Lua handle — the interning this cache exists for
        boolean failed;                     // the render threw, or there was nothing to draw: answer nil, do not retry

        Entry(String label) {
            this.label = label;
        }
    }

    /**
     * One addon's rendered-map-image cache ({@link Addon#mapImages}) — access-ordered and bounded, so the
     * grids a panel is currently drawing stay and the ones it scrolled past are disposed.
     */
    static final class Cache {
        private final Addon owner;
        private final LinkedHashMap<String, Entry> live =
            new LinkedHashMap<String, Entry>(16, 0.75f, true);

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized Entry get(String key) {
            return live.get(key);
        }

        /** Insert a fresh entry, evicting (and disposing) the least recently asked-for one past {@link #MAX}. */
        synchronized void put(String key, Entry e) {
            live.put(key, e);
            while(live.size() > MAX) {
                Iterator<Map.Entry<String, Entry>> it = live.entrySet().iterator();
                if(!it.hasNext())
                    break;
                Entry old = it.next().getValue();
                it.remove();
                drop(old);
            }
        }

        /** Forget one entry — the handle's own {@code :dispose()}, so a later read renders it anew. */
        synchronized void remove(String key) {
            Entry e = live.remove(key);
            if(e != null)
                drop(e);
        }

        /** Free every rendered image this addon holds (teardown). */
        synchronized void clear() {
            List<Entry> all = new ArrayList<Entry>(live.values());
            live.clear();
            for(int i = 0; i < all.size(); i++)
                drop(all.get(i));
        }

        private void drop(Entry e) {
            if(e.future != null) {
                try { e.future.cancel(); } catch(RuntimeException x) { /* best-effort */ }
                e.future = null;
            }
            if(e.image != null)
                AssetApi.disposeImage(e.image);
        }
    }

    /** Give back every rendered map image {@code a} owns. From the teardown, before the asset teardown. */
    static void teardown(Addon a) {
        if(a != null)
            a.mapImages.clear();
    }

    // ---- the two doors -----------------------------------------------------------------------------

    /**
     * {@code grid:image(lvl)} — the recorded minimap drawing of this grid's ground at a zoom level, or
     * {@code nil} while it renders (the call starts that). Level 0 is the ground itself; each level above
     * covers twice as much in each direction at the same 100&times;100 pixels.
     */
    static LuaValue gridImage(Addon owner, long gid, int lvl) {
        MapFile file = MapApi.mapfile();
        MapFile.GridInfo gi = MapApi.gridInfoIn(file, gid);
        if(gi == null)
            return LuaValue.NIL;
        final MapFile.Segment seg = MapApi.segIn(file, gi.seg);
        if(seg == null)
            return LuaValue.NIL;
        // The level-coord: which zoom grid of this level covers our grid. An arithmetic shift is the floor
        // division the engine's own alignment test (gc & ((1<<lvl)-1)) demands, negatives included.
        final Coord zc = Coord.of(gi.sc.x >> lvl, gi.sc.y >> lvl);
        String key = "g\0" + Long.toString(gi.seg) + "\0" + lvl + "\0" + zc.x + "\0" + zc.y;
        Entry e = owner.mapImages.get(key);
        if(e != null)
            return answer(owner, key, e);
        final Coord sc = gi.sc;
        Defer.Future<TexI> f;
        if(lvl == 0) {
            // The client's own level-0 render: a 3x3 View, so tile transitions blend across the grid border
            // exactly as the corner minimap's do. Wait for the grid's own data first — a View built around a
            // grid that is not there yet would render a hole and cache it.
            if(MapApi.gridDataIn(file, gid) == null)
                return LuaValue.NIL;
            final MapFile mf = file;
            f = Defer.later(new Defer.Callable<TexI>() {
                public TexI call() {
                    MapFile.View view = new MapFile.View(seg);
                    try(Locked lk = new Locked(mf.lock.readLock())) {
                        for(int y = -1; y <= 1; y++) {
                            for(int x = -1; x <= 1; x++)
                                view.addgrid(sc.add(x, y));
                        }
                        view.fin();
                        return new TexI(MapSource.drawmap(view, Area.sized(sc.mul(MCache.cmaps), MCache.cmaps)));
                    }
                }
            });
        } else {
            final haven.Indir<? extends MapFile.DataGrid> ind = zoomIndir(file, seg, zc, lvl);
            if(ind == null)
                return LuaValue.NIL;
            f = Defer.later(new Defer.Callable<TexI>() {
                public TexI call() {
                    MapFile.DataGrid g = ind.get();        // Loading until Defer has it — reschedules
                    if(g == null)
                        return null;                       // nothing recorded at this level here
                    return new TexI(g.render(zc.mul(MCache.cmaps)));
                }
            });
        }
        e = new Entry("map:" + Long.toString(gid) + "@" + lvl);
        e.future = f;
        owner.mapImages.put(key, e);
        return LuaValue.NIL;                               // kicked the render; the next call answers
    }

    /** The {@code Indir} for the zoom grid of {@code lvl} covering level-coord {@code zc}, under the read lock. */
    private static haven.Indir<? extends MapFile.DataGrid> zoomIndir(MapFile file, MapFile.Segment seg,
                                                                     Coord zc, int lvl) {
        if((file == null) || !file.lock.readLock().tryLock())
            return null;
        try {
            return seg.grid(lvl, zc.mul(1 << lvl));
        } catch(RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
    }

    /**
     * {@code grid:overlayImage(tag)} — the recorded overlay mask of this grid drawn in the overlay's own
     * colour ({@code DataGrid.olrender}, the very image the map window composites over the ground), or
     * {@code nil} for a tag the grid does not carry and while it renders. Level 0 only: an overlay is
     * recorded per grid, and a zoom grid carries none.
     */
    static LuaValue gridOverlayImage(Addon owner, long gid, String tag) {
        MapFile file = MapApi.mapfile();
        MapFile.GridInfo gi = MapApi.gridInfoIn(file, gid);
        if(gi == null)
            return LuaValue.NIL;
        String key = "o\0" + Long.toString(gid) + "\0" + tag;
        Entry e = owner.mapImages.get(key);
        if(e != null)
            return answer(owner, key, e);
        if(MapApi.maskIn(file, gid, tag) == null)
            return LuaValue.NIL;              // the grid is still loading, or it never carried this tag
        final MapFile.Grid g = MapApi.gridDataIn(file, gid);
        if(g == null)
            return LuaValue.NIL;
        final Coord sc = gi.sc;
        final String t = tag;
        e = new Entry("overlay:" + tag + "@" + Long.toString(gid));
        e.future = Defer.later(new Defer.Callable<TexI>() {
            public TexI call() {
                return new TexI(g.olrender(sc.mul(MCache.cmaps), t));   // Loading on an overlay res reschedules
            }
        });
        owner.mapImages.put(key, e);
        return LuaValue.NIL;
    }

    /**
     * What a second (and later) call answers: the handle if the render landed, {@code nil} while it is still
     * on {@link Defer}. A render that threw, or that had nothing to draw, is remembered as failed and keeps
     * answering nil — retrying it every frame would be a render loop, and the caller has {@code grid:exists()}
     * and {@code grid:overlays()} to tell it why nothing is coming.
     */
    private static LuaValue answer(Addon owner, String key, Entry e) {
        if(e.handle != null)
            return e.handle;
        if(e.failed || (e.future == null))
            return LuaValue.NIL;
        if(!e.future.done())
            return LuaValue.NIL;
        TexI tex;
        try {
            tex = e.future.get();
        } catch(RuntimeException x) {          // a broken tileset resource, a cancelled render
            e.failed = true;
            e.future = null;
            return LuaValue.NIL;
        }
        e.future = null;
        if(tex == null) {                      // nothing recorded at that level/tag: not an error, just no picture
            e.failed = true;
            return LuaValue.NIL;
        }
        LuaImage li = new LuaImage(owner, e.label, tex);
        owner.images.add(li);                  // the teardown unit every other image already rides
        e.image = li;
        e.handle = handleFor(owner, key, li);
        li.handle = e.handle;
        return e.handle;
    }

    /**
     * The Lua handle for a rendered map image. It is an <b>image handle</b> — it carries the same opaque
     * {@link LuaImage#KEY} userdata {@code g:image}, {@code hafen.render.sprite} and the stylesheet's
     * {@code bg = {image = …}} resolve — plus {@code :size()}, {@code :type()}, {@code :path()},
     * {@code :info()} and {@code :dispose()}.
     *
     * <p>It is deliberately <b>not</b> an {@code hafen.asset} entry: an asset is a file this addon shipped,
     * and this is a picture the client drew of the database. It never appears in {@code hafen.asset()}'s list
     * and its {@code :path()} is a description ({@code "map:<gridId>@<lvl>"}), not something a loader accepts.
     */
    private static LuaValue handleFor(final Addon owner, final String key, final LuaImage li) {
        LuaTable h = new LuaTable();
        h.set(LuaImage.KEY, LuaValue.userdataOf(li));      // the opaque backing ref every draw verb resolves
        h.set("size", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("w", LuaValue.valueOf(li.sz.x));
                t.set("h", LuaValue.valueOf(li.sz.y));
                return t;
            }
        });
        h.set("type", new ZeroArgFunction() {
            public LuaValue call() {return LuaValue.valueOf("image");}
        });
        h.set("path", new ZeroArgFunction() {
            public LuaValue call() {return LuaValue.valueOf(li.name);}
        });
        h.set("info", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("source", LuaValue.valueOf("map"));
                t.set("what", LuaValue.valueOf(li.name));
                t.set("size", AddonManager.xy(li.sz.x, li.sz.y));
                t.set("disposed", LuaValue.valueOf(li.dead));
                return t;
            }
        });
        h.set("dispose", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                owner.mapImages.remove(key);               // drop the entry FIRST: a re-read renders anew
                return a.arg1();                           // chains, like every asset verb
            }
        });
        return h;
    }

    // ---- argument parsing --------------------------------------------------------------------------

    /** A zoom-level argument: absent or 0 is the ground itself, up to {@link #MAXLVL}. */
    static int levelArg(LuaValue v, String where) {
        if(v.isnil())
            return 0;
        if(v.type() != LuaValue.TNUMBER)
            throw new LuaError(where + ": lvl is a zoom level, 0.." + MAXLVL
                + " — 0 is the ground itself and each level covers twice as much in each direction");
        double d = v.todouble();
        int lvl = (int)d;
        if((lvl != d) || (lvl < 0) || (lvl > MAXLVL))
            throw new LuaError(where + ": " + v.tojstring() + " is not a zoom level — a whole number 0.."
                + MAXLVL + " (level " + MAXLVL + " already composites " + (1 << (MAXLVL * 2)) + " grids)");
        return lvl;
    }
}
