package io.brodgar.addon;

import java.util.ArrayList;
import java.util.List;

import haven.FColor;
import haven.GOut;
import haven.Light;
import haven.Material;
import haven.Sprite;
import haven.TexI;
import haven.TexRender;
import haven.render.BaseColor;
import haven.render.BlendMode;
import haven.render.DataBuffer;
import haven.render.FragColor;
import haven.render.Homo3D;
import haven.render.Model;
import haven.render.NumberFormat;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.States;
import haven.render.Tex2D;
import haven.render.VectorFormat;
import haven.render.VertexArray;

/**
 * The <b>custom glTF-model</b> visual behind a {@link LuaObject} ({@code hafen.virtual():object()}, spec
 * {@code 18-custom-models-gltf.md} §3, R3a/R3b) — a resource-free {@link Sprite} that renders a parsed {@link Gltf}
 * model in the 3D world. The non-{@code .res}, mesh-data sibling of {@link SpriteQuad}: instead of a single textured
 * quad it builds <b>one engine {@link Model} per glTF primitive</b> and wraps each in its own {@link Material},
 * exactly the {@link haven.StaticSprite} multi-part pattern ({@code added(slot){ for(part) slot.add(part); }}).
 *
 * <p><b>R3a = flat unlit; R3b = textured, multi-material, alpha modes.</b> Each part is drawn as {@code TRIANGLES}
 * and coloured per its glTF material:
 * <ul>
 *   <li><b>Untextured</b> (no {@code baseColorTexture}): a {@code POSITION}-only {@link VertexArray} + a single
 *       {@link BaseColor} (the material's {@code baseColorFactor}) — a solid flat colour (R3a).</li>
 *   <li><b>Textured</b> (R3b): a {@code POSITION}+{@code TEXCOORD_0} interleaved {@link VertexArray} + the engine's
 *       texture-sample state {@link TexRender.TexDraw} (from the mesh's <b>shared</b> {@link TexI}) <b>×</b> a
 *       {@link BaseColor} of the {@code baseColorFactor}. Both {@code TexDraw} and {@code BaseColor} multiply into
 *       the fragment colour at the same priority, so the result is <b>{@code texture × baseColorFactor}</b> — the
 *       glTF base-colour semantics (the albedo the lighting then modulates).</li>
 * </ul>
 *
 * <p><b>R3c = lit.</b> Each primitive carries baked, unit-length {@code NORMAL}s (from {@link Gltf}), fed to
 * {@link Homo3D#normal}, and its material adds a {@link Light.PhongLight} state — so the engine's Phong shader
 * multiplies the <b>world lights</b> (the ones the MapView scene applies to every gob) into the fragment and the
 * model shades like world geometry instead of drawing fullbright. Reflectance uses the engine's neutral defaults
 * (amb 0.2, dif 0.8, matte); the glTF {@code emissiveFactor} becomes the Phong {@code emi} term, so emissive areas
 * glow at full colour even in shadow. sRGB is a no-op: the engine leaves {@code Texture.srgb} false for model
 * textures, so our {@code TexI}s already match.
 * The glTF <b>alpha mode</b> picks how alpha is used: {@code OPAQUE} → none (alpha ignored); {@code MASK} → add
 * {@link TexRender.TexClip} (alpha-discard below the engine's fixed 0.5 cutoff, matching glTF's default {@code
 * alphaCutoff}); {@code BLEND} → {@link FragColor#blend standard alpha blending} + {@link States#maskdepth} (the
 * engine's translucent-overlay recipe, as {@link GhostGob} uses for a ghost's {@code alpha}). {@code doubleSided} →
 * {@link Material#nofacecull} (no cull); single-sided → an explicit back-face {@link States.Facecull} (the det-+1
 * basis preserves glTF's CCW winding, so back-face culling is correct).
 *
 * <p>The mesh's {@link TexI}s are <b>shared</b> and owned by the {@link LuaMesh} ({@link LuaMesh#textures}); this
 * sprite only <i>references</i> their samplers (one {@link TexRender} per image, reused across the primitives that
 * use it), so {@link #dispose()} frees only this object's own {@code Model}s (the {@link VertexArray}s / index
 * buffers) — never the shared textures ({@code teardownMeshes} frees those, after all objects are gone). Two objects
 * sharing one mesh therefore never share a GPU geometry buffer, yet do share the decoded textures.
 *
 * <p>The geometry arrives already baked into H&amp;H model-local space by {@link Gltf} (Z up, world-unit scale), so
 * the gob's {@code Placed} slot (world translate + facing) and the V3/V6 look states ({@code tint}/{@code alpha}/
 * {@code scale}) on the {@link GhostGob} transform the whole model as one — the same shared world-entity core as a
 * ghost or a sprite (spec 17 §2), gizmo included.
 */
final class MeshSprite extends Sprite {
    /**
     * glTF {@code TEXCOORD_0} {@code v} handling: glTF UV {@code v=0} is the image top, and the engine uploads a
     * {@code TexI}'s {@code BufferedImage} row 0 (its top) as texture row 0 (sampled at {@code v=0}), so glTF UVs map
     * <b>directly</b> (no flip). Flipped to {@code 1-v} here as a single switch if a texture ever appears upside-down.
     */
    static final boolean TEXV_FLIP = false;

    private final RenderTree.Node[] parts;   // the material-wrapped models (added to the slot)
    private final Model[] models;            // kept so dispose() frees each VertexArray / index buffer

    private MeshSprite(Owner owner, RenderTree.Node[] parts, Model[] models) {
        super(owner, null);                  // resource-free: no backing .res (like SprDrawable.getres()==null)
        this.parts = parts;
        this.models = models;
    }

    public void added(RenderTree.Slot slot) {
        for(RenderTree.Node p : parts)
            slot.add(p);
    }

    public void dispose() {
        for(Model m : models) {
            try {
                m.dispose();                 // frees this object's own VertexArray + index buffer (NOT the shared TexIs)
            } catch(RuntimeException e) {
                /* best-effort geometry free */
            }
        }
    }

    /**
     * <b>How many glTF objects one addon may have standing at once</b> (audit2 B15). Every object mills its
     * OWN geometry inside {@code :add} — a fresh interleaved {@code float[]}, a {@link haven.render.VertexArray}
     * and an index buffer per primitive, on whichever thread called it — and nothing shared it between two
     * objects of one mesh or counted them. At the parser's own vertex ceiling that is 128 MB of GPU geometry
     * per object, charged as the single instruction a bridge call costs, so the count is what has to be bound:
     * sixty-four models standing in the world is a scene, and past it the client is milling rather than
     * drawing. Per addon, so one addon cannot spend another's room.
     */
    static final int MAXLIVE = 64;

    /**
     * A {@link Sprite.Mill} that builds every primitive of {@code lm}'s mesh into an engine {@link Model} + a glTF
     * {@link Material} (texture × baseColorFactor + alpha mode + cull), sharing the mesh's decoded {@link TexI}s. The
     * {@code SprDrawable} ctor calls {@code create(owner)} with itself as the owner (the {@link SpriteQuad}/{@link
     * haven.StaticSprite} pattern).
     */
    static Sprite.Mill<MeshSprite> mill(final LuaMesh lm) {
        return new Sprite.Mill<MeshSprite>() {
            public MeshSprite create(Sprite.Owner owner) {
                final Gltf mesh = lm.mesh;
                final TexI[] texs = lm.textures;
                final TexRender[] trCache = new TexRender[texs != null ? texs.length : 0];   // one TexRender per shared image
                List<RenderTree.Node> parts = new ArrayList<RenderTree.Node>();
                List<Model> models = new ArrayList<Model>();
                for(Gltf.Prim p : mesh.prims) {
                    boolean textured = p.textured() && (texs != null) && (p.texImage < texs.length) && (texs[p.texImage] != null);
                    boolean lit = p.nrm != null;             // R3c: every prim carries normals now, but keep the gate
                    Model model = buildModel(p, textured, lit);
                    models.add(model);

                    List<Pipe.Op> states = new ArrayList<Pipe.Op>();
                    TexRender tr = null;
                    if(textured) {
                        tr = trCache[p.texImage];
                        if(tr == null)
                            tr = trCache[p.texImage] = texRender(texs[p.texImage]);
                        states.add(tr.draw);                 // sample the texture (multiplies into the fragment)
                    }
                    // baseColorFactor multiply — on a textured prim this modulates the texel (texture × factor); on an
                    // untextured prim it IS the whole colour (the R3a flat-colour path).
                    float[] bc = p.baseColor;
                    states.add(new BaseColor(bc[0], bc[1], bc[2], bc[3]));
                    // R3c lighting: a PhongLight material makes the Phong shader multiply the world lights (the ones
                    // the MapView scene applies to every gob) into the fragment — so the model shades like world
                    // geometry instead of drawing fullbright. Reflectance uses the engine's own neutral defaults
                    // (amb 0.2, dif 0.8, no specular, shine 0 → matte), matching how a default-lit .res material reads;
                    // the base colour (texture × factor) is the albedo the light modulates. emissiveFactor → emi, so
                    // emissive areas glow at full colour even in shadow. Without normals we skip it (stays unlit).
                    if(lit) {
                        float[] e = p.emissive;
                        states.add(new Light.PhongLight(true,
                            Light.PhongLight.defamb, Light.PhongLight.defdif, Light.PhongLight.defspc,
                            new FColor(e[0], e[1], e[2]), 0f));
                    }
                    // alpha mode: MASK = alpha-test discard (needs the texture's alpha); BLEND = translucent blend.
                    if((p.alphaMode == Gltf.ALPHA_MASK) && (tr != null)) {
                        states.add(tr.clip);
                    } else if(p.alphaMode == Gltf.ALPHA_BLEND) {
                        states.add(FragColor.blend(new BlendMode()));   // standard SRC_ALPHA / INV_SRC_ALPHA
                        states.add(States.maskdepth);                   // don't write depth (translucent)
                    }
                    // cull: double-sided = no cull; single-sided = back-face cull (winding preserved by the +det basis).
                    states.add(p.doubleSided ? Material.nofacecull : new States.Facecull());

                    Material mat = new Material(states.toArray(new Pipe.Op[0]));
                    parts.add(mat.apply(model));
                }
                return new MeshSprite(owner, parts.toArray(new RenderTree.Node[0]), models.toArray(new Model[0]));
            }
        };
    }

    /**
     * Wrap a shared mesh {@link TexI}'s live sampler in a {@link TexRender} (its {@code draw}/{@code clip} states). We
     * never call {@code dispose()} on this wrapper — that would free the shared sampler the {@link LuaMesh} owns; the
     * {@code TexI} is freed by {@code teardownMeshes}. Same discipline as {@link SpriteQuad}.
     */
    static TexRender texRender(final TexI tex) {
        return new TexRender(tex.st().data) {
            public void render(GOut g, float[] gc, float[] tc) { /* unused: we draw the mesh in 3D, not via 2D render() */ }
        };
    }

    /**
     * Build the engine {@link Model} for a baked primitive as one interleaved {@link VertexArray}. The layout is
     * chosen per-primitive from the two flags: {@code POSITION}(VEC3, always) + {@code NORMAL}(VEC3, when {@code lit}
     * — R3c) + {@code TEXCOORD_0}(VEC2, when {@code textured} — R3b), tightly packed in that order. So the stride is
     * 12 (pos only), 20 (pos+uv), 24 (pos+normal), or 32 (pos+normal+uv) bytes. Indexed if the primitive has indices,
     * else a sequential draw. Drawn as {@code TRIANGLES}.
     */
    static Model buildModel(Gltf.Prim p, boolean textured, boolean lit) {
        float[] pos = p.pos;
        float[] nr  = p.nrm;                                 // length == nvert*3 (guaranteed by Gltf.bakePrim) when lit
        float[] uv  = p.tex;                                 // length == nvert*2 (guaranteed by Gltf.bakePrim) when textured
        int nvert = pos.length / 3;
        int fpv = 3 + (lit ? 3 : 0) + (textured ? 2 : 0);    // floats per vertex
        int stride = fpv * 4;                                // bytes per vertex
        float[] vert = new float[nvert * fpv];
        for(int i = 0; i < nvert; i++) {
            int o = i * fpv;
            vert[o]     = pos[(i * 3)];
            vert[o + 1] = pos[(i * 3) + 1];
            vert[o + 2] = pos[(i * 3) + 2];
            int k = 3;
            if(lit) {
                vert[o + k]     = nr[(i * 3)];
                vert[o + k + 1] = nr[(i * 3) + 1];
                vert[o + k + 2] = nr[(i * 3) + 2];
                k += 3;
            }
            if(textured) {
                vert[o + k]     = uv[(i * 2)];
                float v = uv[(i * 2) + 1];
                vert[o + k + 1] = TEXV_FLIP ? (1f - v) : v;
            }
        }
        List<VertexArray.Layout.Input> inputs = new ArrayList<VertexArray.Layout.Input>(3);
        int off = 0;
        inputs.add(new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, off, stride)); off += 12;
        if(lit)      { inputs.add(new VertexArray.Layout.Input(Homo3D.normal, new VectorFormat(3, NumberFormat.FLOAT32), 0, off, stride)); off += 12; }
        if(textured) { inputs.add(new VertexArray.Layout.Input(Tex2D.texc,    new VectorFormat(2, NumberFormat.FLOAT32), 0, off, stride)); off += 8; }
        VertexArray.Layout fmt = new VertexArray.Layout(inputs.toArray(new VertexArray.Layout.Input[0]));
        VertexArray vao = new VertexArray(fmt, new VertexArray.Buffer(vert.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(vert)));
        if(p.idx == null)
            return new Model(Model.Mode.TRIANGLES, vao, null, 0, nvert);
        return new Model(Model.Mode.TRIANGLES, vao, indices(p.idx, nvert), 0, p.idx.length);
    }

    /** A UINT16 index buffer when the primitive fits (&lt; 65536 verts), else UINT32 — both are engine-native index paths. */
    static Model.Indices indices(int[] idx, int nvert) {
        if(nvert < 65536) {
            short[] s = new short[idx.length];
            for(int i = 0; i < idx.length; i++)
                s[i] = (short)idx[i];
            return new Model.Indices(s.length, NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(s));
        }
        return new Model.Indices(idx.length, NumberFormat.UINT32, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(idx));
    }
}
