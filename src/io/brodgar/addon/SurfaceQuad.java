package io.brodgar.addon;

import haven.Material;
import haven.Sprite;
import haven.TexRender;
import haven.render.BlendMode;
import haven.render.FragColor;
import haven.render.Model;
import haven.render.RenderTree;

/**
 * The <b>world quad a standing widget is drawn on</b> ({@code hafen.vr():widget()}, 044.1) — the visual half of
 * a {@link LuaWidgetEntity}, and {@link SpriteQuad}'s twin: the same resource-free upright 4-vertex
 * {@code TRIANGLE_STRIP} (it reuses that class's own geometry, so the two cannot drift apart in orientation or
 * winding), textured from a {@link WidgetSurface}'s offscreen colour target instead of from a PNG.
 *
 * <p><b>The material is where the two differ, and it is one line.</b> A sprite draws the engine's plain
 * {@code $tex} recipe — sample plus alpha-CLIP, a solid cut-out ({@code learnings/rendering.md} R2a). A widget
 * surface is not a cut-out: a window background is translucent by design and text is antialiased, so a clip
 * alone would either punch the background out or harden every glyph edge. So this one samples ({@link
 * TexRender.TexDraw}) <i>and</i> blends ({@link FragColor#blend}), keeping {@link TexRender.TexClip} beside them
 * for the border alone — the fully transparent margin a widget never paints is discarded rather than blended,
 * which is what keeps the quad from writing depth over the world across its whole rectangle. What survives the
 * cut composites normally and writes depth, so a panel occludes and is occluded like any other world surface.
 *
 * <p>{@link Material#nofacecull} for the same reason a sprite has it: a panel read from behind is a panel, not
 * a hole. There is no lighting state — a UI surface is its own light, exactly as it is on screen.
 *
 * <p><b>Ownership.</b> The {@link TexRender} belongs to the {@link WidgetSurface} (which owns the
 * {@link haven.render.Texture2D} it wraps), so {@link #dispose()} frees only this quad's own geometry; the
 * surface frees the texture when its entity ends. Same split as {@link SpriteQuad} and its {@link LuaImage}.
 */
final class SurfaceQuad extends Sprite {
    private final RenderTree.Node part;   // the material-wrapped quad model (added to the slot)
    private final Model model;            // kept so dispose() frees the VertexArray (the texture is the surface's)

    private SurfaceQuad(Owner owner, RenderTree.Node part, Model model) {
        super(owner, null);               // resource-free: no backing .res (like SprDrawable.getres()==null)
        this.part = part;
        this.model = model;
    }

    public void added(RenderTree.Slot slot) {
        slot.add(part);
    }

    public void dispose() {
        try {
            model.dispose();              // frees the quad's VertexArray; NOT the texture (owned by the surface)
        } catch(RuntimeException e) {
            /* best-effort geometry free */
        }
    }

    /**
     * A {@link Sprite.Mill} that builds the world quad sampling {@code tr} at world size {@code w}&times;{@code h}.
     * The {@code SprDrawable} ctor calls {@code create(owner)} with itself as the owner, resolving the
     * sprite&harr;owner cycle — the {@link SpriteQuad} pattern verbatim.
     */
    static Sprite.Mill<SurfaceQuad> mill(final TexRender tr, final float w, final float h) {
        return new Sprite.Mill<SurfaceQuad>() {
            public SurfaceQuad create(Sprite.Owner owner) {
                Model model = SpriteQuad.model(quadVerts(w, h));
                Material mat = new Material(tr.draw, tr.clip, FragColor.blend(new BlendMode()),
                                            Material.nofacecull);
                return new SurfaceQuad(owner, mat.apply(model), model);
            }
        };
    }

    /**
     * The quad's 4 vertices — {@link SpriteQuad#quadVerts} in every respect but <b>one sign of {@code t}</b>,
     * and that one is the whole difference between a picture and a render target.
     *
     * <p>A sprite samples a {@link haven.TexI}, uploaded from a {@code BufferedImage} whose first row is the
     * image's TOP, so {@code t = 0} is the top and the quad inverts {@code t} to stand the picture up. A
     * surface samples a texture the client just <b>drew into</b>, and a framebuffer's first row is its BOTTOM
     * — while the {@link haven.render.Ortho2D} the offscreen pass preps maps widget {@code y = 0} (the top)
     * to the top of the viewport. So for a render target {@code t = 0} is the widget's bottom edge, and
     * inverting {@code t} as well would stand the panel on its head — which is exactly what it did before this
     * was measured in-game (044.1). Strip order BL, BR, TL, TR. Pure — headless-testable.
     */
    static float[] quadVerts(float w, float h) {
        float hw = w * 0.5f;
        return new float[] {
            0f, -hw, 0f,  0f, 0f,   // BL  bottom-left   — the widget's bottom edge is v = 0
            0f,  hw, 0f,  1f, 0f,   // BR  bottom-right
            0f, -hw, h,   0f, 1f,   // TL  top-left      — ...and its top edge is v = 1
            0f,  hw, h,   1f, 1f,   // TR  top-right
        };
    }
}
