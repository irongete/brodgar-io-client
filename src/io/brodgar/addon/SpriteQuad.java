package io.brodgar.addon;

import haven.GOut;
import haven.Material;
import haven.Sprite;
import haven.TexI;
import haven.TexRender;
import haven.render.DataBuffer;
import haven.render.Homo3D;
import haven.render.Model;
import haven.render.NumberFormat;
import haven.render.RenderTree;
import haven.render.Tex2D;
import haven.render.VectorFormat;
import haven.render.VertexArray;

/**
 * The <b>fixed world-quad</b> visual behind a {@link LuaSprite} ({@code hafen.virtual():sprite() with :facing("fixed")},
 * spec {@code 17-custom-rendering.md} §5, R2a) — a resource-free {@link Sprite} that stands a custom PNG upright
 * in the 3D world. It builds the substrate the engine's own {@code .res} art already runs on, directly from a
 * {@link TexI}, skipping the {@code .res} container:
 * <ul>
 *   <li>a 4-vertex {@code TRIANGLE_STRIP} {@link Model} — {@link Homo3D#vertex} (VEC3 world position) +
 *       {@link Tex2D#texc} (VEC2 texture coord) — standing in the gob's local {@code x=0} plane, {@code z} up
 *       (0 at the feet → {@code h} at the top), {@code y} across the width, so it renders <b>upright</b> and,
 *       because the gob's {@code Placed} slot rotates it about the vertical by the entity's facing, faces
 *       {@code a};</li>
 *   <li>a {@link Material} of the engine's own <b>textured-surface states</b> {@link TexRender.TexDraw} (sample
 *       the texture) + {@link TexRender.TexClip} (<b>alpha-clip</b>: discard fragments whose texel alpha
 *       {@code < 0.5}) — i.e. exactly the {@code @tex}/{@code clip=true} matpart every {@code .res} textured
 *       object uses ([`TexRender.$tex`](src/haven/TexRender.java:138)). This is a <b>solid</b> cut-out: the
 *       opaque parts of the PNG are drawn fully opaque and the transparent background is discarded — <b>not</b>
 *       alpha-blended (an early draft blended and the whole sprite read as a ~1% ghost). Plus
 *       {@link Material#nofacecull} (double-sided). Depth is written normally, so the sprite is a solid occluder.</li>
 * </ul>
 * The quad is placed on a virtual {@link GhostGob} (via {@code SprDrawable}), which supplies the world transform,
 * the V3 look states ({@code tint}/{@code alpha}/{@code scale}), and — inheriting the shared core — the gizmo. The
 * opt-in {@code alpha < 1} translucency rides {@code GhostGob.obstate} (a separate blend state at the gob level);
 * the default sprite carries no blend at all. The world size is aspect-preserved from the pixel size (see
 * {@link AddonManager}'s {@code spriteWorldDims}); the uniform {@code :scale} then adjusts it.
 *
 * <p><b>Ownership.</b> The {@link TexI} belongs to the {@link LuaImage} handle ({@link Addon#images}), so
 * {@link #dispose()} frees only the quad's own GPU buffer ({@link Model#dispose()} → the {@code VertexArray});
 * the shared texture is freed by {@code teardownImages}. The {@link TexRender} wrapper only <i>references</i> that
 * texture's sampler (it is never disposed here — its {@code dispose()} would free the shared sampler), so one
 * image can back several sprites and the screen-draw ({@code g:image}) at once.
 */
final class SpriteQuad extends Sprite {
    private final RenderTree.Node part;   // the material-wrapped quad model (added to the slot)
    private final Model model;            // kept so dispose() frees the VertexArray (the TexI is the image's)

    private SpriteQuad(Owner owner, RenderTree.Node part, Model model) {
        super(owner, null);               // resource-free: no backing .res (like SprDrawable.getres()==null)
        this.part = part;
        this.model = model;
    }

    public void added(RenderTree.Slot slot) {
        slot.add(part);
    }

    public void dispose() {
        try {
            model.dispose();              // frees the quad's VertexArray; NOT the TexI (owned by the LuaImage)
        } catch(RuntimeException e) {
            /* best-effort geometry free */
        }
    }

    /**
     * A {@link Sprite.Mill} that builds the textured quad for {@code tex} at world size {@code w}&times;{@code h}.
     * The {@code SprDrawable} ctor calls {@code create(owner)} with itself as the owner, resolving the
     * sprite&harr;owner cycle (the {@code SprDrawable.apply}/{@code StaticSprite} pattern).
     */
    static Sprite.Mill<SpriteQuad> mill(final TexI tex, final float w, final float h) {
        return new Sprite.Mill<SpriteQuad>() {
            public SpriteQuad create(Sprite.Owner owner) {
                Model model = quad(w, h);
                // The engine's own textured-surface states (TexRender.$tex, clip=true): sample + alpha-DISCARD,
                // NOT blend — a SOLID cut-out. Wrap the TexI's live sampler (tex.st().data); never dispose this
                // TexRender (its dispose() would free the shared sampler the LuaImage owns).
                TexRender tr = new TexRender(tex.st().data) {
                    public void render(GOut g, float[] gc, float[] tc) { /* unused: we draw the quad in 3D, not via 2D render() */ }
                };
                Material mat = new Material(tr.draw, tr.clip, Material.nofacecull);   // textured, alpha-cut, double-sided, opaque
                return new SpriteQuad(owner, mat.apply(model), model);
            }
        };
    }

    /** Build the 4-vertex upright textured quad {@link Model} at world size {@code w}&times;{@code h}. */
    static Model quad(float w, float h) {
        return model(quadVerts(w, h));
    }

    /**
     * The {@link Model} behind a 4-vertex interleaved {@code x,y,z, s,t} strip — the geometry plumbing on its
     * own, so a quad that differs only in its texture coordinates ({@link SurfaceQuad}, whose source is a
     * render target rather than an uploaded image) reuses the layout instead of copying it.
     */
    static Model model(float[] vert) {
        VertexArray.Layout fmt = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32),  0, 0, 20),
            new VertexArray.Layout.Input(Tex2D.texc,    new VectorFormat(2, NumberFormat.FLOAT32),  0, 12, 20));
        VertexArray vao = new VertexArray(fmt, new VertexArray.Buffer(vert.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(vert)));
        return new Model(Model.Mode.TRIANGLE_STRIP, vao, null, 0, 4);
    }

    /**
     * The 4 vertices (interleaved {@code x,y,z, s,t}) of the upright quad standing at the gob's feet: {@code x=0}
     * (faces &plusmn;local-x), {@code y} across {@code [-w/2, +w/2]} (width), {@code z} up {@code [0, h]} (height);
     * texture {@code t} inverted so the image top ({@code t=0}) is at {@code z=h}. Strip order BL, BR, TL, TR
     * (the {@code Rendered.ScreenQuad} order). Pure — no GL — so it is headless-testable.
     */
    static float[] quadVerts(float w, float h) {
        float hw = w * 0.5f;
        return new float[] {
            0f, -hw, 0f,  0f, 1f,   // BL  bottom-left
            0f,  hw, 0f,  1f, 1f,   // BR  bottom-right
            0f, -hw, h,   0f, 0f,   // TL  top-left
            0f,  hw, h,   1f, 0f,   // TR  top-right
        };
    }
}
