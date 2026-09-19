package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.FColor;
import haven.Fonts;
import haven.GOut;
import haven.Loading;
import haven.TexRender;
import haven.UI;
import haven.Widget;
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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.luaj.vm2.LuaValue;

/**
 * <b>A surface whose picture is another widget's</b> ({@code hafen.ui():mirror()}, 157.1). {@code :source(w)}
 * names any widget of any tree — the client's or an addon's, drawn or not — and every frame this mirror is
 * shown, that widget is drawn again into a texture of this mirror's own and the texture is blitted into this
 * mirror's box. The source is never moved, hidden or re-parented: it goes on being exactly what it was, and this
 * is a second picture of it, wherever a surface of the addon's can stand.
 *
 * <p><b>Why a picture, and not the widget.</b> A widget the client composes at runtime — the HUD portrait's 3D
 * avatar, the corner minimap, a meter's fill — reads the session it stands in ({@code ui.sess}), so it can be
 * re-homed into a surface of the same tree alone ({@link LuaWidget#rehomeNative}'s refusal) and is drawn only
 * while that session holds the screen. The picture has neither limit: the offscreen pass draws the widget in its
 * own tree, session and all, and the addon layer draws the texture over whatever is on screen. So a dock in the
 * layer can show every logged-in character's portrait, the ones nobody is looking at included.
 *
 * <p><b>The pass is {@link WidgetSurface#render}'s, per mirror</b>: a {@link Texture2D} colour image as the
 * {@link FragColor} of a fresh {@link BufPipe}, {@link Ortho2D} + {@link States.Viewport} over the SOURCE's own
 * pixel box, a clear to transparent, then the source drawn as its parent's loop would draw it — inside its
 * {@link Fonts#frame} and with {@link LuaWidgetOverlay#paint} after it. Issued from {@code UILoop.display} beside
 * {@code AddonManager.drawSurfaces}, BEFORE the widget traversal that draws the screen, so the commands that write
 * the texture precede the commands that sample it in the same {@link Render}; and issued holding no tree monitor,
 * so each mirror can take the monitor of its SOURCE's tree — which is not the tree the mirror stands in — one at
 * a time and nested in nothing, the one lock shape {@code docs/client/multi-session.md} allows.
 *
 * <p><b>A tree the frame did not tick is ticked here.</b> {@code UILoop.Frame.tick} hands a {@code GTickEvent} to
 * the drawn session's tree and to the layer's; {@code Sessions.gtick} feeds a background member's {@code Glob}
 * alone. A {@code PView}'s {@code TickList} — a composited avatar's equipment sprites — is reached only through
 * the widget tree, so a source in any other tree gets the event from this pass, and never twice.
 *
 * <p><b>A {@code PView} draws into it</b> because {@code PView.draw} renders its scene into its own colour target
 * and then {@code resolve}s that into whatever {@link GOut} it was handed: the mechanism the virtual-widget
 * feature already stands a portrait in the world on. <b>And it renders that scene once per frame</b>, whoever
 * asks: the pass draws the on-screen session's portrait ahead of the HUD, and the HUD's own draw then composes
 * what the pass rendered ({@code PView.lastout}, the fork's seam). A {@code GLDrawList} is drawn once per frame
 * by construction -- its double buffer waits for the previous submission to run -- and a second draw in one
 * frame waits for ever holding the tree's monitor, which is the freeze this seam exists to prevent.
 *
 * <p><b>Every frame, no signature.</b> {@link WidgetSurface#needsDraw}'s walk saves a pass for a panel nothing
 * changes; a mirror exists for the pictures that move every frame, and at a portrait's size the pass is one
 * small scene render. A mirror that is hidden, or stands under something hidden, costs nothing ({@link #shown}).
 *
 * <p><b>Owned, not a control adapter and not an {@code AddonWidget}.</b> {@link LuaWidget#typeName} climbs past
 * an {@link Owned.Control} to the client class it adapts, and there is none here — {@code :type()} reads
 * {@code MirrorWidget}, as a bare surface reads {@code AddonWidget}. {@link AddonWidget} is {@code final} and
 * carries what an addon PAINTS ({@code Draw}, {@code Update}, packing, the column axis), none of which a picture
 * has. The stock and the rules that name it still dress it ({@link Sheet#chromeOf}: the {@code bg} under the
 * picture, the {@code border} over it), and every universal subscription answers on it — a press on a mirror is
 * the mirror's own, which is what a dock switching sessions needs.
 */
final class MirrorWidget extends Widget implements Owned, Controls.Source {
    /** A picture past this is the texture's cost and nothing anybody can read: the ceiling a standing panel has. */
    static final int MAX_SIDE = 2048;

    /** Straight alpha in, premultiplied-looking out — {@link WidgetSurface}'s choice, for the same reason. */
    private static final BlendMode BLEND =
        new BlendMode(BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                      BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA);

    /** The texture starts fully transparent, so what the source does not paint shows the mirror's own bg through. */
    private static final FColor CLEAR = new FColor(0f, 0f, 0f, 0f);

    /**
     * Every mirror alive, whatever tree it stands in — the pass walks it holding no monitor. Copy-on-write: a
     * draw callback may build or destroy one while the pass is walking.
     */
    private static final List<MirrorWidget> live = new CopyOnWriteArrayList<MirrorWidget>();

    private final Owned.State own;
    /** The userdata {@code :source(w)} was given — the read hands it back while its widget is live. */
    private volatile LuaValue sourcev = LuaValue.NIL;
    /** ...and the handle behind it, the reference {@link LuaWidget#live} resolves every time it is asked. */
    private volatile LuaWidget sourceh;
    /** The picture: sized to the SOURCE's device box, re-made when that box changes, freed with the mirror. */
    private volatile Texture2D tex;
    /** The blit over {@link #tex}, v-flipped — the layer's draw reads it, the pass writes it, one thread. */
    private volatile TexRender tr;
    private Coord texsz;
    /** The box {@code :size(w, h)} wrote, in device pixels, or {@code null} while the box follows the source's. */
    private Coord pinned;
    /** Inside {@link #fit}: the resize is the source's box, not a pin. */
    private boolean sourcing;
    private volatile boolean freed;
    private boolean logged;

    MirrorWidget(Addon owner) {
        super(Px.in(Coord.of(UiApi.DEF_W, UiApi.DEF_H)));
        this.own = new Owned.State(owner, this);
        live.add(this);
    }

    // ---------------------------------------------------------------- Owned, over one State

    public Addon profOwner() {
        return own.owner;
    }

    public Widget rootw() {
        return own.root();
    }

    public boolean dead() {
        return own.dead();
    }

    public boolean pending() {
        return own.pending();
    }

    public void armed() {
        own.armed();
    }

    public void kill() {
        own.kill();
    }

    public Widget widget() {
        return this;
    }

    public boolean enabled() {
        return own.enabled();
    }

    public void enabled(boolean b) {
        own.enabled(b);
    }

    // ---------------------------------------------------------------- the source

    /** {@code mirror:source()} — the userdata last given, while the widget it names is still in its tree. */
    public LuaValue source() {
        LuaWidget h = sourceh;
        if((h == null) || (LuaWidget.live(h) == null))
            return LuaValue.NIL;
        return sourcev;
    }

    /**
     * Bridge-only; {@link Controls#source} has resolved the widget, asked the refusals and takes the box after
     * this ({@link #pinned}, {@link #fit}) — the box is written under the MIRROR's tree monitor, and resolving
     * the source takes the SOURCE's, so the two never nest.
     */
    public void source(LuaValue v) {
        this.sourceh = LuaWidget.resolve(v);
        this.sourcev = (sourceh == null) ? LuaValue.NIL : v;
    }

    /** Did {@code :size(w, h)} pin the box? Then a new source keeps it. */
    boolean pinned() {
        return pinned != null;
    }

    /** The box follows the source's: a resize that is not a pin. Caller holds this mirror's tree monitor. */
    void fit(Coord box) {
        sourcing = true;
        try {
            resize(box);
        } finally {
            sourcing = false;
        }
    }

    /** Every resize but {@link #fit}'s own is {@code :size(w, h)}'s, and pins the box. */
    public void resize(Coord sz) {
        super.resize(sz);
        if(!sourcing)
            pinned = sz;
    }

    /**
     * {@code :size(nil)}: the box follows the source's again. Drops the pin and answers the source's box, read
     * in the source's own tree, or {@code null} without one — the caller {@link #fit}s under the mirror's.
     */
    Coord unpin() {
        pinned = null;
        Widget w = LuaWidget.live(sourceh);
        return ((w != null) && (w.sz != null)) ? w.sz : null;
    }

    // ---------------------------------------------------------------- the pass

    /**
     * Draw every shown mirror's source into that mirror's texture — called from {@code UILoop.display} with the
     * frame's {@link Render}, before the traversal that draws the screen, holding no tree monitor. {@code drawn}
     * and {@code layer} are the two trees the frame has already ticked.
     */
    static void renderAll(UI drawn, UI layer, Render out) {
        if((out == null) || live.isEmpty())
            return;
        Set<Widget> ticked = null;             // the sources this pass already ticked: two mirrors, one tick
        for(MirrorWidget m : live) {
            if(ticked == null)
                ticked = Collections.newSetFromMap(new IdentityHashMap<Widget, Boolean>());
            m.render(drawn, layer, out, ticked);
        }
    }

    /** Is this mirror drawn at all: in a tree, and visible at every level up to its root? */
    private boolean shown() {
        if(ui == null)
            return false;
        for(Widget w = this; w != null; w = w.parent) {
            if(!w.visible)
                return false;
        }
        return true;
    }

    private void render(UI drawn, UI layer, Render out, Set<Widget> ticked) {
        if(freed || own.dead() || own.pending() || !shown())
            return;
        Widget src = LuaWidget.live(sourceh);   // takes the source tree's monitor briefly, and holds it no longer
        if(src == null)
            return;
        UI u = src.ui;
        if(u == null)
            return;
        synchronized(LuaWidget.monitorOf(u)) {  // 112.2: the source's tree, nested in nothing
            if((src.ui != u) || (u.root == null) || u.destroyed || !src.hasparent(u.root))
                return;                         // it left between the two reads: nothing to draw this frame
            Coord ssz = src.sz;
            if((ssz == null) || (ssz.x <= 0) || (ssz.y <= 0))
                return;
            ssz = Coord.of(Math.min(ssz.x, MAX_SIDE), Math.min(ssz.y, MAX_SIDE));
            Texture2D tx = texture(ssz);
            Pipe base = new BufPipe();
            base.prep(new FragColor<Texture.Image<Texture2D>>(tx.image(0)));
            base.prep(FragColor.blend(BLEND));
            Area a = Area.sized(ssz);
            base.prep(new States.Viewport(a)).prep(new Ortho2D(a));
            base.prep(new FrameInfo());
            try {
                out.clear(base, FragColor.fragcol, CLEAR);
                if((u != drawn) && (u != layer) && ticked.add(src))
                    u.dispatch(src, new Widget.GTickEvent(out));   // a tree the frame did not tick: its render nodes, once
                GOut g = new GOut(out, base, ssz);
                try(Fonts.Frame ff = Fonts.frame(src)) {           // the parent loop's two brackets around a child
                    src.draw(g);
                }
                if(src.addonovs != null)
                    LuaWidgetOverlay.paint(src, g);
            } catch(Loading l) {
                return;                         // a resource still streaming: the last picture stands
            } catch(Throwable e) {
                // THROWABLE, as WidgetSurface: a Lua painter over the source can raise anything, and an Error
                // out of here would take the frame with it. Logged once until the mirror draws again.
                if(!logged) {
                    logged = true;
                    AddonManager.log("mirror draw error (logged once until it draws): " + e);
                }
                return;
            }
            logged = false;
        }
    }

    /** The texture at the source's box — the one there is, or a fresh one when the box changed. */
    private Texture2D texture(Coord ssz) {
        Texture2D tx = tex;
        if((tx != null) && ssz.equals(texsz))
            return tx;
        freeTexture();
        tx = new Texture2D(ssz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
        TexRender r = new TexRender(tx.sampler()) {
            /**
             * The engine's own 2D blit with the texture coordinates flipped in {@code t}: a render target's
             * first row is its BOTTOM, while every 2D caller hands texel coordinates counted from the top — the
             * flip {@link WidgetSurface}'s {@code "screen"} facing makes, for the reason {@code SurfaceQuad}
             * spells out.
             */
            public void render(GOut g, float[] gc, float[] tc) {
                float h = this.sz().y;
                float[] f = new float[tc.length];
                for(int i = 0; i < tc.length; i += 2) {
                    f[i] = tc[i];
                    f[i + 1] = h - tc[i + 1];
                }
                super.render(g, gc, f);
            }
        };
        texsz = ssz;
        tex = tx;
        tr = r;
        return tx;
    }

    private void freeTexture() {
        TexRender r = tr;
        tr = null;
        tex = null;
        texsz = null;
        if(r != null) {
            try {
                r.dispose();                    // the sampler, and with it the texture
            } catch(RuntimeException e) {
                /* best-effort GPU free */
            }
        }
    }

    // ---------------------------------------------------------------- the blit, in the mirror's own tree

    public void draw(GOut g) {
        if(own.pending())                       // built this statement and not armed yet: paints NOTHING
            return;
        g = Owned.dim(this, g);                 // 139.3: disabled? the whole mirror paints dimmed
        Fonts.Chrome ch = Sheet.chromeOf(this, null);   // 107: the stock, under every rule that names it
        if((ch != null) && ch.bg())
            ch.drawbg(g, Coord.z, sz);
        TexRender r = tr;
        if(r != null)
            g.image(r, Coord.z, sz);            // the source's picture, scaled into this box
        super.draw(g);
        if((ch != null) && ch.border())
            ch.drawborder(g, Coord.z, sz);
    }

    // ---------------------------------------------------------------- teardown

    /** The engine's own teardown hook (a {@code destroy()} on this widget): the texture goes, the source stays. */
    public void dispose() {
        free();
        super.dispose();
    }

    private void free() {
        if(freed)
            return;
        freed = true;                           // published FIRST: a pass that reads it stops here
        live.remove(this);
        freeTexture();
        sourceh = null;
        sourcev = LuaValue.NIL;
    }
}
