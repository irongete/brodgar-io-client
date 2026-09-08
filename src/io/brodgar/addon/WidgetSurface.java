package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.FColor;
import haven.GOut;
import haven.Loading;
import haven.SIWidget;
import haven.TexRender;
import haven.UI;
import haven.Widget;
import haven.Window;
import haven.render.BlendMode;
import haven.render.BufPipe;
import haven.render.DataBuffer;
import haven.render.FragColor;
import haven.render.FrameInfo;
import haven.render.NumberFormat;
import haven.render.Ortho2D;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.States;
import haven.render.Texture;
import haven.render.Texture2D;
import haven.render.VectorFormat;



/**
 * <b>The surface a standing widget is drawn on</b> ({@code hafen.virtual():widget()}, 044.1) — an offscreen colour
 * target, and the widget the target is drawn from. It is a {@link Widget} of its own, attached to
 * {@code ui.root} and <b>invisible</b>, with the standing widget reparented into it: that one arrangement is
 * what the whole feature's transparency rule rests on, because everything the client resolves against a
 * widget's place in the tree — liveness, ticking, focus, hover, popups — goes on resolving unchanged, while
 * the two things that must NOT happen on the flat UI stop by construction. An invisible widget is skipped by
 * the flat draw traversal ({@code Widget.draw} steps over {@code !visible} children) and by every hit test
 * (the mouse dispatch and {@code hafen.ui():hit()} skip it the same way) — but it is still <b>ticked</b>
 * ({@code TickEvent} carries visibility as a flag rather than a filter), and it is still under
 * {@code ui.root}, so {@code widget:exists()} stays true and no handle goes stale.
 *
 * <p><b>The offscreen pass</b> ({@link #renderAll}) is the {@code Streamer}/{@code HeadlessClient} recipe
 * narrowed to one subtree: a {@link Texture2D} colour image prepped as the {@link FragColor} of a fresh
 * {@link BufPipe}, an {@link Ortho2D} + {@link States.Viewport} over the surface's own pixel size, and a
 * {@link GOut} over that pipe — the very constructor the client's own UI loop uses. Those two loops redirect
 * the ENTIRE client by overriding {@code UILoop.basestate()}; this is the per-surface version, so the widget
 * traversal that runs on it is the ordinary one and nothing inside the widget can tell the difference.
 *
 * <p><b>Ordered before the world, never one frame stale</b> (044.1's first open question). The pass is issued
 * from {@code UILoop.display} <i>before</i> {@code ui.draw(g)}, and the 3D scene is drawn inside that
 * traversal (the MapView is a widget) — so the commands that write the texture are in the same frame's
 * {@link Render} ahead of the commands that sample it. One command stream, in order: same frame, no staleness,
 * and no second buffer to reconcile.
 *
 * <p><b>Blending, not clipping alone</b> (the second open question). A widget's own draw produces real alpha —
 * a window background is translucent, text is antialiased — so the quad samples with
 * {@link TexRender.TexDraw} <i>and</i> {@link FragColor#blend} rather than the alpha-cut-only recipe a sprite
 * uses ({@code learnings/rendering.md} R2a). {@link TexRender.TexClip} stays in the material beside them: it
 * discards the fully transparent border, so the quad is not a transparent rectangle writing depth over
 * whatever is behind it, while everything that survives the cut composites properly. See {@link SurfaceQuad},
 * which keeps the choice on one line.
 *
 * <p><b>It does not redraw every frame.</b> {@link #needsDraw} answers the only two questions there are about
 * what a surface shows. <i>What the client's own controls paint</i> is visible in their state, so a signature
 * over the subtree — structure, place, size, visibility, and the caption of every text-bearing widget — says
 * when it changed; that is the whole cost of a static panel, one walk of a handful of widgets. <i>What your
 * own {@code Draw} handler paints</i> is a Lua function of anything at all, and the only way to know what it
 * would paint is to run it — so a surface with a {@code Draw} subscriber anywhere in it, or with one of the
 * client's own transitions still running, redraws every frame ({@link #changing}), which is not a concession
 * but the correct answer for a panel whose text is the smelter's fuel level. Everything a control setter
 * changes that a signature cannot see (a value, a row list, a picture) marks the surface through
 * {@link #touch}, and a surface whose content has not been armed yet is skipped outright ({@link #unarmed}),
 * so the first upload is the first one that draws anything.
 *
 * <p><b>And it does not draw at all when nothing is looking</b> (044.7, {@link #culled}). That is the cheapest
 * of the questions and it is asked first, because a panel the camera is not pointing at costs nothing however
 * busy its content is: the {@code Draw} handler that would otherwise repaint it every frame is not run, which is
 * the whole reason to cull the surface rather than merely let the GPU throw its quad away. {@code Tick} goes on
 * firing while it is culled — ticking is logic and it happens in the widget tree, which a culled surface never
 * leaves — so nothing inside a panel drifts out of date while the player is facing the other way.
 */
final class WidgetSurface extends Widget {
    // 073.2: every live surface of ONE SESSION ({@code SessionState.surfaces}) — the render pass walks that
    // list and nothing else. A standing panel is a widget re-homed under one session's ui.root, drawn into a
    // texture the quad of a gob in THAT session's scene samples, so a list for the client would have the
    // drawn session's offscreen pass rendering another session's widget tree.


    /**
     * <b>The render clock of one session</b> (audit2 B01) — the frames its offscreen pass was offered, which
     * is what {@link #culled} and {@link SurfaceInput#gesturing} measure their two-frame slack against, and
     * the passes actually issued beside it.
     *
     * <p>Both were one counter for the client, incremented once per SESSION per frame: two logins consumed
     * the slack at twice the rate and raced a non-atomic read-modify-write on it. They belong to the session
     * because the increment always did, and the surfaces they clock stand in one tree apiece.
     */
    private long clock() {
        AddonManager.SessionState st = state();
        return (st == null) ? 0 : st.surfaceFrames;
    }

    /** <b>The state of the session this panel stands in</b>, or {@code null} once that tree is gone — the
     *  render clock, the held panel and the surface list are all in it. */
    AddonManager.SessionState state() {
        return AddonManager.state(sui);
    }

    /**
     * The offscreen blend: colour composites the ordinary way, but the ALPHA channel accumulates
     * {@code ONE / INV_SRC_ALPHA} rather than {@code SRC_ALPHA / INV_SRC_ALPHA}. The client's own 2D pass
     * blends onto an opaque frame buffer, where the destination alpha is never read again; ours is a texture
     * the world pass samples, so squaring the alpha (what the default mode would do onto a cleared target)
     * would make every translucent widget twice as see-through in the world as it is on screen.
     */
    private static final BlendMode BLEND =
        new BlendMode(BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                      BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA);

    /** The surface starts fully transparent, so anything the widget does not paint shows the world through. */
    private static final FColor CLEAR = new FColor(0f, 0f, 0f, 0f);

    /** A surface is a panel, not a wall: past this the texture is the cost, and nothing is readable anyway. */
    private static final int MAXDIM = 2048;

    final Addon owner;
    /**
     * <b>The session whose tree this panel stands in</b> (073.2). Held rather than read off {@link Widget#ui}
     * because a surface joins the render list at construction, one line before it is added to that tree — and
     * because {@link #free} must reach the very list {@link #WidgetSurface} joined even if the add never
     * happened.
     */
    private final UI sui;
    /** The entity standing this surface, set the moment it is built — see {@link #takesPointer}. */
    LuaWidgetEntity ent;
    private Texture2D tex;
    private TexRender tr;
    private Coord tsz;                 // the texture's size, which is this widget's size at stand time
    private boolean dirty = true;      // never drawn, or something the signature cannot see has changed
    private long sig;                  // the last content signature (see needsDraw)
    private boolean sigged;
    private boolean freed;

    /** 044.4: the quad's four corners in the map view's pixels — BL, BR, TL, TR — as of {@link #qframe}. */
    private volatile float[] quad;
    /** ...the depth its centre projected to, so the frontmost of two overlapping panels takes the pointer. */
    private volatile float qdepth;
    /** ...and which frame recorded them, so a surface that has stopped being drawn stops being clickable. */
    private volatile long qframe = Long.MIN_VALUE;
    /** ...and 044.7: whether the rectangle those corners span met the view at all — see {@link #culled}. */
    private volatile boolean onscreen;

    /**
     * 044.4: where this surface's <b>widget-local origin currently is on screen</b>, kept following the
     * pointer ({@link SurfaceInput#refreshOrigin}). It is what {@link #parentpos} answers with, and therefore
     * what {@code rootpos()} means for everything inside a standing panel — see that method.
     */
    private volatile Coord origin = Coord.z;

    WidgetSurface(UI ui, Addon owner, Coord sz) {
        super(clamp(sz));
        this.sui = ui;
        this.owner = owner;
        this.visible = false;          // not drawn by the flat pass, not hit-tested, still ticked
        this.tsz = this.sz;
        this.tex = new Texture2D(this.sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
        this.tr = new TexRender(this.tex.sampler()) {
            /**
             * The 2D blit, used by the {@code "screen"} facing alone (044.3) — the world quad samples through
             * {@code draw}/{@code clip} and never comes here. It is the engine's own blit with the texture
             * coordinates flipped in {@code t}, for the reason {@link SurfaceQuad#quadVerts} spells out: a
             * render target's first row is its BOTTOM, while every 2D caller hands texel coordinates counted
             * from the top. Without the flip the panel blits upside down.
             */
            public void render(GOut g, float[] gc, float[] tc) {
                float h = this.sz().y;             // the TEXTURE's height (TexRender.sz), not the widget's
                float[] f = new float[tc.length];
                for(int i = 0; i < tc.length; i += 2) {
                    f[i] = tc[i];
                    f[i + 1] = h - tc[i + 1];
                }
                super.render(g, gc, f);
            }
        };
        AddonManager.SessionState st = AddonManager.state(ui);
        if(st != null)
            st.surfaces.add(this);
    }

    /** A surface is at least one pixel and at most {@link #MAXDIM} on a side. */
    private static Coord clamp(Coord sz) {
        int w = (sz == null) ? 1 : sz.x, h = (sz == null) ? 1 : sz.y;
        return new Coord(Math.max(1, Math.min(MAXDIM, w)), Math.max(1, Math.min(MAXDIM, h)));
    }

    /** The texture wrapper the world quad samples ({@link SurfaceQuad}). Never disposed by the quad. */
    TexRender texture() {
        return tr;
    }

    // ---------------------------------------------------------------- input (044.4)

    /** Every live surface of one session — the pointer walk reads this, exactly as the render pass does. */
    static java.util.List<WidgetSurface> all(UI u) {
        AddonManager.SessionState st = AddonManager.state(u);
        return (st == null) ? java.util.Collections.<WidgetSurface>emptyList() : st.surfaces;
    }

    /**
     * The visual just projected this surface's four corners ({@link SurfaceDrawable}, or the blit rectangle in
     * {@code "screen"} mode). Recording it here rather than on the entity is what lets one hit test serve all
     * three facing modes: they differ in what reads the texture, never in the fact that the result is a flat
     * quad somewhere on screen.
     */
    void corners(float[] q, float depth, Area view) {
        this.quad = q;
        this.qdepth = depth;
        this.onscreen = (q != null) && (view != null) && meets(q, view);
        this.qframe = clock();
    }

    /**
     * Does the rectangle spanned by the four projected corners meet {@code view} at all? The bounding box rather
     * than the quad itself: a projected quad is convex, so the box is an over-estimate and never the other way
     * about — a surface can be kept for a frame it did not actually need, and can never be dropped for one it
     * did, which is the only asymmetry a culling test is allowed to have.
     */
    private static boolean meets(float[] q, Area view) {
        float minx = q[0], maxx = q[0], miny = q[1], maxy = q[1];
        for(int i = 2; i < 8; i += 2) {
            minx = Math.min(minx, q[i]);     maxx = Math.max(maxx, q[i]);
            miny = Math.min(miny, q[i + 1]); maxy = Math.max(maxy, q[i + 1]);
        }
        return (maxx >= view.ul.x) && (minx <= view.br.x) && (maxy >= view.ul.y) && (miny <= view.br.y);
    }

    /**
     * <b>Is this surface's picture reaching the screen at all right now?</b> (044.7) — the whole of the feature's
     * culling, and it costs two field reads.
     *
     * <p><b>There is no engine culling to reuse.</b> The plan assumed one; the client has none. Nothing walks the
     * gobs testing them against a frustum — geometry is handed to the GPU and clipped there, which is the right
     * answer for a mesh and the wrong one for us, because our cost is not the quad. Drawing a surface means
     * running a whole widget subtree, and, where the panel has a {@code Draw} subscriber, a Lua function, every
     * frame. Clipping the quad after the fact would save none of that.
     *
     * <p><b>But the test was already being computed.</b> Every frame the visual projects the quad's four corners
     * into the map view's own pixels ({@link SurfaceDrawable}, or the blit rectangle in {@code "screen"} mode) so
     * that a click can be resolved on it (044.4) — and those four points, against the view they were projected
     * into, ARE the frustum test, arrived at by the very transform stack that draws the thing. So this asks two
     * questions and neither of them is new work: was the quad drawn recently at all (the {@link #qframe} clock
     * that already ends a stale panel's claim on the pointer — it covers {@code :hide()}, a gob that has left the
     * scene, and a client with no map view up), and did what it drew meet the view.
     *
     * <p><b>It is one frame late, and it has to be.</b> The offscreen pass runs before the world draw that
     * records the corners, so what it reads is last frame's answer. Being late is only ever an extra upload on
     * the frame a panel swings into view — the texture itself is never stale, because a culled surface keeps its
     * dirty flag and its last signature untouched and so redraws on the first frame it is looked at again if
     * anything changed while it was not.
     */
    boolean culled() {
        return ((clock() - qframe) > 2) || !onscreen;
    }

    /**
     * The corners, or {@code null} when they are stale — a surface that has stopped being drawn (hidden, its
     * gob gone, off in a scene that is not rendering) must stop taking the pointer, and the frame counter the
     * offscreen pass already keeps is the cheapest clock there is. Two frames of slack, because the pass runs
     * before the world draw that records them.
     */
    float[] corners() {
        return ((clock() - qframe) <= 2) ? quad : null;
    }

    float depth() {
        return qdepth;
    }

    /**
     * <b>Does the pointer reach this panel at all?</b> A standing widget takes clicks by default — a window on
     * the flat UI does, and this feature's whole rule is that the world one behaves the same — so
     * {@code panel:clickable(false)} is the opt OUT, and it means what it means for a sprite: the thing is
     * there to look at, and the pointer goes through it to the world beneath.
     */
    boolean takesPointer() {
        LuaWidgetEntity e = ent;
        if(freed || (e == null))
            return false;
        synchronized(e) {
            return !e.dead && e.clickable;
        }
    }

    /** {@link SurfaceInput} places the widget-local origin under the pointer; see {@link #parentpos}. */
    void origin(Coord o) {
        this.origin = o;
    }

    /**
     * <b>Where a widget inside this surface is, as far as the rest of the client is concerned.</b> This is the
     * one line that makes a grab work: {@code Widget.parentpos} builds every {@code rootpos()} through its
     * parents, and {@code UI.PointerGrab} translates a grabbed widget's events by exactly that — so a
     * scrollbar or a button that has taken the mouse is fed by the UI itself, never coming past
     * {@code MapView} where the corner map could catch it. Answering with {@link #origin}, the screen point
     * this surface's own {@code (0, 0)} currently sits at, makes that translation land on the same pixel the
     * corner map would have produced.
     *
     * <p>It is deliberately NOT {@code xlate}: the draw traversal positions children through that one, and the
     * offscreen pass must go on drawing this panel at its own origin whatever the pointer is doing.
     */
    public Coord parentpos(Widget in) {
        return (in == this) ? Coord.z : origin;
    }

    /**
     * Keep {@link #origin} following the pointer while a gesture is in flight (a grab bypasses MapView) — and
     * <b>pin the standing widget at the surface's own origin</b>.
     *
     * <p>The pin is what keeps the spec's word once input arrives (044.4). A {@code Window}'s title bar drags it
     * by writing its own {@code c}, and the feature's rule is that a widget standing in the world has no place
     * of its own to drag — its place is the anchor's, and the title bar is a flat-UI gesture. Until this task
     * the drag was inert because no pointer event ever reached it; now that one does, the drag has to be
     * answered rather than merely unreachable. Undoing the write each tick, before the frame is drawn, is the
     * smallest answer that leaves the gesture itself (the grab, the cursor, the release) exactly as the client
     * built it — and it costs one comparison per surface per frame.
     */
    public void tick(double dt) {
        super.tick(dt);
        LuaWidgetEntity e = ent;
        if((e != null) && (e.content != null) && (e.content.parent == this) && !Coord.z.equals(e.content.c))
            e.content.c = Coord.z;
        SurfaceInput.refreshOrigin(this);
    }

    // ---------------------------------------------------------------- the offscreen pass

    /**
     * Draw every surface that needs it into its own texture — called from {@code UILoop.display} with the
     * frame's {@link Render}, <b>before</b> the widget traversal that draws the world. Takes the {@code ui}
     * monitor for the same reason {@code ui.draw} does: this walks widgets.
     */
    static void renderAll(UI u, Render out) {
        if((u == null) || (out == null))
            return;
        AddonManager.SessionState st = AddonManager.state(u);
        if(st == null)
            return;
        java.util.List<WidgetSurface> mine = st.surfaces;   // 073.2: the panels of the tree being drawn
        if(mine.isEmpty())
            return;
        st.surfaceFrames++;
        synchronized(u) {
            for(WidgetSurface s : mine)                // copy-on-write: a draw callback may stand or end one
                s.render(out);
        }
    }

    /** One surface's pass: clear to transparent, then the ordinary widget draw over an offscreen {@link GOut}. */
    private void render(Render out) {
        if(freed || (tex == null))
            return;
        if(!needsDraw())
            return;
        Pipe base = new BufPipe();
        base.prep(new FragColor<Texture.Image<Texture2D>>(tex.image(0)));
        base.prep(FragColor.blend(BLEND));
        Area a = Area.sized(tsz);
        base.prep(new States.Viewport(a)).prep(new Ortho2D(a));
        base.prep(new FrameInfo());
        try {
            out.clear(base, FragColor.fragcol, CLEAR);
            draw(new GOut(out, base, tsz), true);      // the standing widget's root is this surface's one child
        } catch(Loading l) {
            return;                                    // a resource is still streaming: stay dirty, try next frame
        } catch(RuntimeException e) {
            dirty = false;                             // a broken surface must not spin the frame loop
            AddonManager.log("surface draw error: " + e);
            return;
        }
        dirty = false;
        AddonManager.SessionState st = state();
        if(st != null)
            st.surfaceUploads++;
    }

    /**
     * Does this surface's picture need to be produced again? Reads the signature <b>every</b> frame, whatever
     * the answer, so the frame that redraws is also the frame that records what it drew — otherwise a single
     * change would cost two passes, one for the flag and one for the signature catching up.
     */
    private boolean needsDraw() {
        if(culled())
            return false;                              // 044.7: nothing is looking, so nothing is painted --
                                                       // and the signature is deliberately NOT read, so whatever
                                                       // changes out of sight is still a change when it is seen
        if(unarmed())
            return false;                              // still being built: it paints nothing, so it costs nothing
        long s = signature();
        boolean changed = !sigged || (s != sig);
        sig = s;
        sigged = true;
        // ...and a panel with a button HELD DOWN on it repaints every frame while the gesture lasts (044.4).
        // A cached face says when it changed; a scrollbar or a slider being dragged has none and simply draws
        // its new position, so the one thing that covers every control mid-drag is the drag itself.
        return dirty || changed || changing() || SurfaceInput.gesturing(this);
    }

    /**
     * <b>Is anything in here still being built?</b> A widget is attached inert and paints nothing until the
     * tick after the statement that built it (D-112, {@link Owned#pending}) — so a pass over it would clear a
     * blank texture and charge an upload for a picture nobody asked for. Skipping the frame instead makes the
     * FIRST upload the first one that draws something, which is what "a static panel costs one upload" has to
     * mean to be worth measuring.
     */
    private boolean unarmed() {
        return unarmed(this);
    }

    private static boolean unarmed(Widget w) {
        for(Widget c = w.child; c != null; c = c.next) {
            if((c instanceof Owned) && ((Owned)c).pending())
                return true;
            if(unarmed(c))
                return true;
        }
        return false;
    }

    /**
     * What the client's own widgets show, as one number: each widget's class, place, size, visibility and
     * caption, depth-first. It sees a label's new text, a control appearing or leaving, anything moving or
     * resizing, and anything hidden or shown — the changes a panel built from controls actually makes.
     */
    private long signature() {
        return sig(this, 0xcbf29ce484222325L);
    }

    private static long sig(Widget w, long h) {
        for(Widget c = w.child; c != null; c = c.next) {
            h = mix(h, c.getClass().hashCode());
            h = mix(h, (c.c == null) ? 0 : ((c.c.x * 31) + c.c.y));
            h = mix(h, (c.sz == null) ? 0 : ((c.sz.x * 31) + c.sz.y));
            h = mix(h, c.visible() ? 1 : 0);
            String t = LuaWidget.text(c);
            h = mix(h, (t == null) ? 0 : t.hashCode());
            h = sig(c, h);
        }
        return h;
    }

    private static long mix(long h, int v) {
        return (h ^ (v & 0xffffffffL)) * 0x100000001b3L;
    }

    /**
     * <b>Is anything in here changing on its own?</b> Two things are, and neither leaves a trace a signature
     * could read:
     * <ul>
     *   <li>a {@code widget:on("Draw", fn)} handler — a Lua function of whatever it likes, so the only way to
     *       know what it would paint is to run it, and running it <i>is</i> the draw. This is not a concession:
     *       a panel whose text is the smelter's fuel level must redraw when the fuel changes, and nothing but
     *       the handler knows that it did;</li>
     *   <li>a widget that has <b>thrown away its cached face</b> ({@link SIWidget#redrawing}) — a button
     *       depressing under a click, arming as the pointer re-enters it, being disabled. On the flat UI this
     *       needs no signal because the screen is redrawn every frame; here it is the difference between a
     *       button that visibly presses and one that does not, which is the whole of 044.4's rule. Found the
     *       moment input arrived (044.4), for the same reason the {@code Window} fade was (044.3): what a
     *       widget looks like is not always in its place, its size or its caption;</li>
     *   <li>a running transition, so a window reparented into a surface animates its way in exactly as it does
     *       on screen instead of freezing on the first frame of it. That is <b>two</b> lists, not one, and
     *       044.1 checked only the first: a {@link Widget.Anim} lives in {@code anims}/{@code nanims}, while a
     *       {@link haven.Window}'s own show/hide fade lives in a private field of its own and is visible only
     *       through {@link haven.Window#animating()}. Missing the second froze every standing window on the
     *       first frame of its fade — nearly transparent, and therefore discarded outright by the world quad's
     *       alpha clip, so the panel did not merely look faint: it was not there at all (044.3).</li>
     * </ul>
     */
    private boolean changing() {
        return changing(this);
    }

    private boolean changing(Widget w) {
        for(Widget c = w.child; c != null; c = c.next) {
            if(!c.anims.isEmpty() || !c.nanims.isEmpty())
                return true;
            if((c instanceof Window) && ((Window)c).animating())
                return true;
            if((c instanceof SIWidget) && ((SIWidget)c).redrawing())
                return true;
            WidgetSubs s = owner.widgetSubsOrNull(c);
            if((s != null) && s.subs.has("Draw"))
                return true;
            if(changing(c))
                return true;
        }
        return false;
    }

    /** Mark this surface's picture out of date (see {@link #touch}). */
    void invalidate() {
        dirty = true;
    }

    /**
     * <b>Something changed inside {@code w} that a signature cannot see</b> — a control's value, its rows, a
     * picture it draws. Walks up to the surface {@code w} stands on, if it stands on one, and marks it; the
     * walk is O(depth) and runs on a setter, never on a frame. A widget on the flat UI finds no surface and
     * this costs the walk and nothing else.
     */
    static void touch(Widget w) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p instanceof WidgetSurface) {
                ((WidgetSurface)p).dirty = true;
                return;
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Move {@code w} from wherever it is into {@code np} at {@code at}, <b>without</b> going through
     * {@link Widget#remove()}. The engine's removal is the right call for a widget that is going away, and the
     * wrong one for a widget that is only changing address: it runs the {@code onWidgetRemoved} seam, which
     * would fire {@code widget:on("Removed")}, a selector's {@code "Removed"}, and the end of a
     * {@code widget:replace()} substitution — three notifications about a death that is not happening. So this
     * does the two things a re-home genuinely is: unlink from the old sibling chain (telling the old parent its
     * children changed, and dropping the focusable if it held one) and add to the new one.
     */
    static void reparent(UI u, Widget w, Widget np, Coord at) {
        synchronized(u) {
            Widget op = w.parent;
            if(op != null) {
                if(w.canfocus)
                    op.delfocusable(w);
                w.unlink();
                op.cdestroy(w);
                w.parent = null;
            }
            np.add(w, (at == null) ? Coord.z : at);
        }
    }

    /** Free the texture and leave the render pass. Idempotent; the widget itself is removed by its entity. */
    void free() {
        if(freed)
            return;
        freed = true;
        AddonManager.SessionState st = AddonManager.state(sui);
        if(st != null)
            st.surfaces.remove(this);
        SurfaceInput.forget(this);         // no half-finished gesture goes on being delivered to a dead panel
        TexRender t = tr;
        tr = null;
        tex = null;
        if(t != null) {
            try { t.dispose(); }                       // disposes the sampler, and with it the texture we own
            catch(RuntimeException e) { /* best-effort GPU free */ }
        }
    }

    /** The engine's own teardown hook (a {@code destroy()} on this widget), so nothing can leak the texture. */
    public void dispose() {
        free();
    }

    // ---------------------------------------------------------------- the counters (p:surfaces())

    /** How many panels stand in ONE session's world — the fast path a per-widget seam takes (073.2). */
    static int liveCount(UI u) {
        return all(u).size();
    }

    /**
     * The same figure rolled up for the whole client, which is what {@code p:surfaces()} has always reported
     * and goes on reporting: the profiler describes the client's frame, not one login's (073.2).
     */
    static int liveCount() {
        int n = 0;
        for(AddonManager.SessionState s : AddonManager.allStates())
            n += s.surfaces.size();
        return n;
    }

    /** How many of those are being skipped right now because nothing is looking at them (044.7). */
    static int culledCount() {
        int n = 0;
        for(AddonManager.SessionState st : AddonManager.allStates()) {
            for(WidgetSurface s : st.surfaces) {
                if(s.culled())
                    n++;
            }
        }
        return n;
    }

    /**
     * The {@code p:surfaces()} counters, rolled up for the client (audit2 B01) — the sum over the live
     * sessions, which is exactly the number one shared counter carried: it was incremented once per session
     * per frame, so the client-wide figure is the sum and neither reader changes what it reports.
     */
    static long uploads() {
        long n = 0;
        for(AddonManager.SessionState st : AddonManager.allStates())
            n += st.surfaceUploads;
        return n;
    }

    static long frames() {
        long n = 0;
        for(AddonManager.SessionState st : AddonManager.allStates())
            n += st.surfaceFrames;
        return n;
    }
}
