package io.brodgar.addon;

import java.awt.Color;
import java.util.function.Consumer;

import haven.AddonWidgets;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GAttrib;
import haven.Glob;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapMesh;
import haven.Matrix4f;
import haven.Moving;
import haven.Waitable;
import haven.render.BaseColor;
import haven.render.BlendMode;
import haven.render.FragColor;
import haven.render.Location;
import haven.render.MixColor;
import haven.render.Pipe;
import haven.render.Rendered;
import haven.render.States;

/**
 * The {@link Gob} behind a client-only world ghost ({@code hafen.virtual():ghost()}, spec {@code 16-virtual-entities.md}) —
 * a plain virtual gob (id {@code -1} ⇒ {@code Gob.virtual}: never in {@code OCache}, invisible to the server and
 * every read API) with two extra behaviours, both applied in {@link #obstate}: it can be made
 * <b>pick-selectable</b> (V2, {@link D-032}) and given a <b>look</b> — a colour {@link #tint} and/or a
 * translucent {@link #alpha} (V3, the "ghost" appearance).
 *
 * <p><b>Why a subclass is needed.</b> The engine makes a gob clickable by prepping a {@link Gob.GobClick} in its
 * render state ({@code Gob.GobState.apply}) — but <b>only for non-virtual gobs</b> ({@code if(!virtual)}). A ghost
 * is virtual, so a plain {@code Gob} ghost carries no {@code GobClick} and the MapView pick pass never returns it.
 * {@code GobState.apply} does, however, call the {@code protected} extension hook {@code obstate(Pipe)} for every
 * gob, virtual or not — so this subclass overrides it to add the {@code GobClick} when {@link #clickable}, giving
 * a virtual ghost a click surface <b>without</b> flipping {@code virtual} (which would break its OCache/server
 * invisibility and the {@code cg.virtual} fast-path the {@code Click.hit} intercept uses to detect ghosts). The
 * same hook prepares the look states (below).
 *
 * <p><b>Look (V3).</b> {@code obstate} composes, in addition to the click surface, the render states that give a
 * ghost its appearance — the same primitives the engine itself uses for gob tinting and translucent overlays:
 * <ul>
 *   <li><b>tint</b> → a {@link MixColor} (the exact state {@code GobHealth} uses for the red damage tint): blends
 *       the colour into the object's fragments, the colour's alpha being the blend strength. Purely a colour
 *       overlay — it does not make the object see-through.</li>
 *   <li><b>alpha &lt; 1</b> → a {@link BaseColor} {@code (1,1,1,alpha)} (multiplies the fragment alpha) plus
 *       {@link FragColor#blend standard alpha blending} plus {@link States#maskdepth} (don't write depth) — the
 *       engine's own recipe for a translucent overlay (see the tile-grid overlay / drag-select rectangle in
 *       {@code MapView}). This is the see-through "ghost" look.</li>
 *   <li><b>scale &ne; 1</b> (V6) → a uniform {@link Location#scale(float) scaling} {@code Location}. Because
 *       {@code obstate} runs on the gob's <i>child</i> render slot — <b>below</b> the {@code Placed} slot that
 *       applies the world translate ({@code "gobx"}) + facing rotation ({@code "gob"}) — the scale composes as
 *       {@code T·R·S}, i.e. it scales the model <b>in place</b> around the gob's own origin (its feet), not the
 *       world origin, and rotates/translates correctly on top. (A sprite that does
 *       {@code Location.goback("gobx")}, e.g. {@code resutil.CSprite}, resets past both the facing and this
 *       scale — such resources already ignore ghost rotation, so they ignore scale too.)</li>
 * </ul>
 *
 * <p><b>Toggling / applying a change.</b> The click-list decides membership <b>at slot-add time</b> and
 * {@code GobState.equals} compares only the {@code SetupMod} mods (not {@code obstate}'s output), so neither a
 * {@link #clickable} flip nor a {@link #tint}/{@link #alpha} change propagates through the normal
 * {@code updated()}/{@code updstate()} path — {@link AddonManager} applies all three by removing and re-adding the
 * gob to the scene ({@code AddonManager.refreshEntityScene}), where {@code obstate} runs fresh and reads the
 * current fields. {@code obstate} is evaluated at render-apply time, so every field here is read live.
 *
 * <p>The pick resolves in {@code MapView.Click.hit}; because {@code GobClick.gob} is this gob, the engine's own
 * {@code clickedgob(inf)} returns it, and {@link AddonManager#onGhostClick} finds the owning ghost, fires
 * {@code GhostClicked} + its {@code onClick}, and consumes the click — <b>no {@code wdgmsg}</b>, so nothing reaches
 * the server and the whole thing stays SAFE-tier (D-029/D-032).
 */
public final class GhostGob extends Gob {
    /**
     * Whether this ghost currently has a pick surface. Read by {@link #obstate} at render-apply time (so a toggle
     * takes effect on the next scene re-add — see {@link AddonManager#setEntityClickable}). {@code volatile} because
     * it is set on the UI/stdin thread and read on the render thread when the gob's state is (re)applied.
     */
    public volatile boolean clickable;

    /** V3: opacity 0..1 — {@code 1} = fully opaque (no extra state); {@code < 1} = translucent. Read live by {@link #obstate}. */
    public volatile float alpha = 1f;

    /** V3: colour-overlay {@link MixColor} tint, or {@code null} for none (the colour's alpha is the blend strength). Read live by {@link #obstate}. */
    public volatile Color tint = null;

    /** V6: uniform scale — {@code 1} = original size (no extra state); anything else is an in-place scaling {@link Location}. Read live by {@link #obstate}. */
    public volatile float scale = 1f;

    public GhostGob(Glob glob, Coord2d c) {
        super(glob, c);   // id -1 ⇒ virtual (Gob.virtual): no server id, not in OCache, invisible to reads/server
    }

    // ---- the ground it stands on, and a placement made again only when something it came from changed ----

    /**
     * The ground under a ghost, looked up once and kept: a grid of the live map, or past it of the remembered
     * ground, whose cut's mesh can be built from what that cache holds -- or neither. Immutable but for
     * {@link #at}, so any thread may read the one {@link #ground} holds.
     */
    private static final class Ground {
        final Coord2d rc;           // the point it was looked up for
        final Coord gc, cc;         // that point's grid, and the cut within it
        final MCache map;           // the cache `grid` is in; null with it
        final MCache.Grid grid;     // null: neither cache holds a grid whose cut here can be built
        final int lseq;             // the live map's chseq when looked
        final MCache rmap;          // the remembered ground's cache when looked, or null
        final int rseq;             // and its chseq
        volatile double at;         // when a ghost standing nowhere last saw the remembered ground unchanged

        Ground(Coord2d rc, Coord gc, Coord cc, MCache map, MCache.Grid grid, int lseq, MCache rmap, int rseq) {
            this.rc = rc; this.gc = gc; this.cc = cc;
            this.map = map; this.grid = grid;
            this.lseq = lseq; this.rmap = rmap; this.rseq = rseq;
            this.at = haven.Utils.rtime();
        }
    }

    /** How long a ghost standing nowhere trusts that the drawn view's remembered ground is the one it looked in. */
    private static final double NOWHERE_RECHECK = 0.5;

    private volatile Ground ground;

    /** The ground under this ghost: the one looked up last, for as long as nothing says it may have changed. */
    private Ground ground() {
        Coord2d rc = this.rc;
        Ground g = this.ground;
        if((g != null) && g.rc.equals(rc) && held(g))
            return g;
        return this.ground = look(rc, g);
    }

    /**
     * Whether a ground looked up earlier is still the answer. A grid it stands on goes when its cache drops it;
     * over remembered ground, a grid arriving in the live map may be the one under it; standing nowhere, a grid
     * arriving in either cache may be. Neither map is asked anything here: chseq is what both say a grid
     * arrived with.
     */
    private boolean held(Ground g) {
        MCache live = glob.map;
        if(g.grid != null) {
            if(g.grid.removed)
                return false;
            return (g.map == live) || (live.chseq == g.lseq);
        }
        if((live.chseq != g.lseq) || ((g.rmap != null) && (g.rmap.chseq != g.rseq)))
            return false;
        /* The drawn view's remembered ground can be another cache altogether (the view distance rebuilt it, a
         * session switch): asked about now and then, not every frame. */
        double now = haven.Utils.rtime();
        if((now - g.at) < NOWHERE_RECHECK)
            return true;
        if(VirtualApi.recallMap() != g.rmap)
            return false;
        g.at = now;
        return true;
    }

    /**
     * Look the ground under {@code rc} up: the live map's, else the remembered ground's ({@link
     * VirtualApi#recallMap}). Past the live ground, asking the live map would ask the server for a grid the
     * character is nowhere near, and never draw. Over remembered ground found before, only the live map is
     * asked again -- the remembered grid is kept while it stands.
     */
    private Ground look(Coord2d rc, Ground old) {
        Coord tc = rc.floor(MCache.tilesz);
        Coord gc = tc.div(MCache.cmaps);
        Coord cc = tc.sub(gc.mul(MCache.cmaps)).div(MCache.cutsz);
        MCache live = glob.map;
        int lseq = live.chseq;                      // read before looking: an arrival during the look is looked at again
        MCache rmap = VirtualApi.recallMap();
        int rseq = (rmap == null) ? 0 : rmap.chseq;
        if(AddonWidgets.cutmissing(live, gc, cc) == null) {
            MCache.Grid g = AddonWidgets.loadedGrid(live, gc);
            if(g != null)
                return new Ground(rc, gc, cc, live, g, lseq, rmap, rseq);
        }
        if((old != null) && (old.grid != null) && (old.map == rmap) && !old.grid.removed && old.gc.equals(gc) && old.cc.equals(cc))
            return new Ground(rc, gc, cc, rmap, old.grid, lseq, rmap, rseq);
        if((rmap != null) && (AddonWidgets.cutmissing(rmap, gc, cc) == null)) {
            MCache.Grid g = AddonWidgets.loadedGrid(rmap, gc);
            if(g != null)
                return new Ground(rc, gc, cc, rmap, g, lseq, rmap, rseq);
        }
        return new Ground(rc, gc, cc, null, null, lseq, rmap, rseq);
    }

    /**
     * Stand on the ground {@link #ground()} found: the live map's through the resource's own placer, the
     * remembered ground's heights past it -- a resource's placer yields there, for it reads the live map, and
     * that ground is not in it. Standing nowhere, the placement waits for a grid to arrive ({@link NoGround}).
     */
    public Placer placer() {
        Ground g = ground();
        if(g.grid == null)
            return new Nowhere(g);
        if(g.map == glob.map)
            return super.placer();
        return g.map.mapplace;
    }

    /** The live tile's draw state (water and the like); over remembered ground or none, none. */
    protected Pipe.Op getmapstate(Coord3f pc) {
        if(ground().map != glob.map)
            return null;
        return super.getmapstate(pc);
    }

    /** The placer of a ghost standing nowhere: every answer is "not yet", and says what it waits for. */
    private final class Nowhere implements Placer {
        final Ground g;
        Nowhere(Ground g) {this.g = g;}
        public Coord3f getc(Coord2d rc, double ra) {throw(new NoGround(GhostGob.this, g));}
        public Matrix4f getr(Coord2d rc, double ra) {throw(new NoGround(GhostGob.this, g));}
    }

    /**
     * No ground under a ghost yet. Waits for the grid its cut still lacks to arrive in the live map, or in the
     * remembered ground -- the {@code LoadingMap} wait on each, whichever comes first -- so a scene add that
     * stopped here ({@code VirtualApi.addToScene}) is tried again when there may be ground, and not before.
     */
    private static final class NoGround extends Loading {
        final GhostGob gob;
        final MCache live;
        final Ground g;

        NoGround(GhostGob gob, Ground g) {
            super("Waiting for the ground under a ghost...");
            this.gob = gob;
            this.live = gob.glob.map;
            this.g = g;
        }

        public void waitfor(Runnable callback, Consumer<Waitable.Waiting> reg) {
            /* The remembered ground in the scene NOW, not the one looked in: rebuilt meanwhile (the view
             * distance moved, a session switch), the old cache is never told of a grid again. */
            MCache rmap = VirtualApi.recallMap();
            Coord lmiss = AddonWidgets.cutmissing(live, g.gc, g.cc);
            Coord rmiss = (rmap == null) ? null : AddonWidgets.cutmissing(rmap, g.gc, g.cc);
            if((lmiss == null) || ((rmap != null) && (rmiss == null))) {
                gob.ground = null;                    // it arrived meanwhile: look again, and try again now
                reg.accept(Waitable.Waiting.dummy);
                callback.run();
            } else if(rmap == null) {
                new MCache.LoadingMap(live, lmiss).waitfor(callback, reg);
            } else {
                Waitable.or(callback, reg, new MCache.LoadingMap(live, lmiss), new MCache.LoadingMap(rmap, rmiss));
            }
        }
    }

    // What the placement in the scene was made from (placestale -> placedone). Only Placed.autotick reads and
    // writes these, one frame after another.
    private static final class Made {
        final Coord2d rc;
        final double a;
        final int attrs;
        final MCache.Grid grid;
        final MapMesh cut;          // the mesh under it when made, or null when not built yet
        final int lseq;             // the live map's chseq when made

        Made(Coord2d rc, double a, int attrs, MCache.Grid grid, MapMesh cut, int lseq) {
            this.rc = rc; this.a = a; this.attrs = attrs; this.grid = grid; this.cut = cut; this.lseq = lseq;
        }

        boolean same(Coord2d rc, double a, int attrs, MCache.Grid grid) {
            return((this.a == a) && (this.attrs == attrs) && (this.grid == grid) && this.rc.equals(rc));
        }
    }
    private Made made, making, parked;
    private double retry, backoff;

    /** Bumped by every attribute written or dropped and every {@link #updated}: what the placement reads besides the ground. */
    private volatile int attrseq;
    /** Whether it has a {@link Moving} -- following a game object, or gliding: then it is placed every frame, as upstream does. */
    private volatile boolean moves;

    public void setattr(GAttrib a) {
        super.setattr(a);
        attrs();
    }

    public void delattr(Class<? extends GAttrib> c) {
        super.delattr(c);
        attrs();
    }

    public void updated() {
        super.updated();
        attrseq++;
    }

    private void attrs() {
        moves = (getattr(Moving.class) != null);
        attrseq++;
    }

    /**
     * Whether its placement is to be made again this frame ({@code Gob.placestale}). Not while it stands where
     * it stood, faces the same way, carries the same attributes, and the ground under it is the same grid with
     * the same mesh -- and, over the live map, no grid has arrived there since (the resource's own placer may
     * read beyond its own cut, and caches on that very sequence). Standing nowhere, what it has stays until
     * ground arrives. A placement that stopped at a {@link Loading} is tried again after a wait that doubles
     * each time, up to two seconds, unless something it came from changes first.
     */
    protected boolean placestale() {
        if(moves)
            return(true);
        Coord2d rc = this.rc;
        if(rc == null)
            return(true);
        double a = this.a;
        int attrs = this.attrseq;
        Ground g = ground();
        if(g.grid == null)
            return(false);
        Made p = this.parked;
        if((p != null) && p.same(rc, a, attrs, g.grid) && (haven.Utils.rtime() < retry))
            return(false);
        MapMesh cut;
        try {
            cut = g.grid.getcut(g.cc);
        } catch(Loading l) {
            cut = null;
        }
        int lseq = glob.map.chseq;
        Made m = this.made;
        if((m != null) && m.same(rc, a, attrs, g.grid) && (cut == m.cut) && ((g.map != glob.map) || (lseq == m.lseq)))
            return(false);
        making = new Made(rc, a, attrs, g.grid, cut, lseq);
        return(true);
    }

    protected void placedone(Loading l) {
        Made mk = making;
        making = null;
        if(mk == null)
            return;
        if(l == null) {
            made = mk;
            parked = null;
            backoff = 0;
        } else {
            parked = mk;
            backoff = (backoff <= 0) ? 0.05 : Math.min(backoff * 2, 2.0);
            retry = haven.Utils.rtime() + backoff;
        }
    }

    /**
     * Extension hook called from {@code Gob.GobState.apply} for every gob (the one path {@code virtual} does not
     * gate). Preps (a) a {@link Gob.GobClick} when {@link #clickable} — the exact state a real gob gets, so the mesh
     * inherits it and the MapView pick pass ({@code Clicklist}) returns this gob — and (b) the V3 look states:
     * a {@link MixColor} for {@link #tint} and, when {@link #alpha} {@code < 1}, {@link BaseColor} + alpha-blend +
     * {@link States#maskdepth} for translucency. Nothing is prepped when not clickable / opaque / untinted, so a
     * plain decorative ghost stays a normal opaque, click-through prop. Each field is snapshotted once (it is
     * {@code volatile}) so a concurrent change can't tear a single apply.
     */
    protected void obstate(Pipe buf) {
        if(clickable)
            buf.prep(new Gob.GobClick(this));
        Color tc = this.tint;                       // snapshot the volatile once
        if(tc != null)
            buf.prep(new MixColor(tc));             // colour overlay (blend strength = tc.getAlpha()); GobHealth's tint pattern
        float al = this.alpha;                      // snapshot the volatile once
        if(al < 1f) {
            buf.prep(new BaseColor(1f, 1f, 1f, al)); // multiply the fragment alpha
            buf.prep(FragColor.blend(new BlendMode())); // standard SRC_ALPHA / INV_SRC_ALPHA blending
            buf.prep(States.maskdepth);             // don't write depth — the engine's translucent-overlay recipe
            buf.prep(Rendered.eyesort);             // after everything opaque: writing no depth, it is otherwise
                                                    // painted over by whatever shares its order and draws later (the ground)
        }
        float sc = this.scale;                      // V6: snapshot the volatile once
        if(sc != 1f)
            buf.prep(Location.scale(sc));           // uniform scale; composes under the gob's translate+rotate → local-origin scaling
    }
}
