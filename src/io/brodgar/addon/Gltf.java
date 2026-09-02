package io.brodgar.addon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import haven.Coord3f;
import haven.Matrix4f;

/**
 * A minimal, dependency-free <b>glTF 2.0 static-mesh</b> reader (spec {@code 18-custom-models-gltf.md}, R-series
 * slice R3a) — the parser half of a {@code hafen.asset("chair.glb")} mesh. It reads a {@code .glb} (single-file binary,
 * preferred) or a {@code .gltf}+buffers model and decodes it to a flat list of {@link Prim baked primitives} whose
 * geometry is already in <b>H&amp;H model-local space</b> (Z up, world-unit scale) — so a {@link LuaMesh} can build
 * engine {@link haven.render.Model}s from it directly and place them as a virtual gob on the shared world-entity
 * core, no {@code .res} container involved. Pure Java over our own {@link Json} reader (no native deps, D-035);
 * binary buffers are decoded by hand (little-endian {@code ByteBuffer}-free byte math); {@code data:}-URIs via
 * {@link Base64}.
 *
 * <h3>The R3a/R3b subset (static, unlit + textured)</h3>
 * <ul>
 *   <li><b>In (R3a):</b> {@code .glb} (12-byte header + JSON/BIN chunks) and {@code .gltf}+external/embedded buffers;
 *       {@code POSITION} + triangle {@code indices}; multiple nodes/meshes/primitives with <b>baked node
 *       transforms</b>; PBR metallic-roughness <b>{@code baseColorFactor}</b> (a flat colour multiply).</li>
 *   <li><b>In (R3b):</b> {@code TEXCOORD_0} decode + PBR <b>{@code baseColorTexture}</b> (the referenced
 *       {@code images[]} are extracted as raw PNG/JPG blobs — from a {@code bufferView}, a {@code data:} URI, or an
 *       external file — into {@link #images}, deduped per glTF image and decoded to a {@code TexI} by the caller,
 *       {@link LuaMesh}); per-material <b>alpha mode</b> ({@code OPAQUE}/{@code MASK}/{@code BLEND}) + {@code
 *       doubleSided} cull, exposed on each {@link Prim} for {@link MeshSprite} to turn into engine material states.</li>
 *   <li><b>In (R3c):</b> {@code NORMAL} decode (baked by the inverse-transpose of the {@code basis·node} matrix,
 *       or computed smooth from the geometry when absent) + {@code emissiveFactor}, so {@link MeshSprite} can add
 *       {@link haven.Light.PhongLight} and the model shades with the world lights (spec §4). sRGB baseColor is a
 *       no-op: the engine does not sRGB-convert model textures ({@code Texture.srgb} is left {@code false}
 *       everywhere in the load path), so our {@code TexI}s already match world geometry — see {@code r3c-lighting.md}.</li>
 *   <li><b>Deferred (later):</b> {@code emissiveTexture}, per-texture sampler wrap/filter, a non-zero
 *       {@code baseColorTexture.texCoord} set ({@code TEXCOORD_1}), full PBR (metallic/roughness/occlusion).</li>
 *   <li><b>Never:</b> skins/joints, morph targets, keyframe animation, Draco/meshopt, sparse accessors — a model
 *       using one fails with a clear, named error (spec §6), never a client crash.</li>
 * </ul>
 *
 * <h3>Coordinate system &amp; units (the basis conversion — spec §4)</h3>
 * glTF is <b>right-handed, +Y up, metres</b>; H&amp;H model space is <b>Z up</b> (the ghost/sprite convention —
 * see {@link SpriteQuad}) with a tile-based world scale. {@link #BASIS} bakes, once, a proper rotation (glTF +Y
 * up &rarr; H&amp;H +Z up: {@code (x,y,z) → (x, -z, y)}, a +90&deg; turn about X, determinant +1 so winding is
 * preserved) <b>and</b> a fixed model&rarr;world scale ({@link #MODEL_UNIT} world units per glTF metre, so 1 metre
 * = one tile). It composes as {@code BASIS · nodeWorld} and is applied to every vertex at parse time, so the
 * decoded positions are already H&amp;H-local; the gob's {@code Placed} slot (world translate + facing) and the
 * handle's {@code :scale} then transform the whole model as one. The glTF origin maps to the gob position, so a
 * model authored with its base at {@code Y=0} stands on the ground (like a ghost/sprite).
 *
 * <h3>Safety caps (D-018 spirit)</h3>
 * Decode is CPU-heavy and runs on the calling (UI) thread (small local assets, spec 17 §3), so a pathological
 * asset must not hang the client: {@link #MAX_PRIMS}, {@link #MAX_VERTS}, {@link #MAX_INDICES} and {@link #MAX_BYTES}
 * bound the model, and an accessor is bounded <b>before</b> its array is allocated; exceeding one throws.
 * The node walk needs no cap of its own: glTF's invariant that a node has at most one parent is enforced in
 * {@link #walk}, so the walk visits each node at most once and a document that reaches one twice is refused.
 *
 * <p><b>Purity / testability.</b> This class touches no GL and no client session — only {@link Json},
 * {@link Matrix4f}/{@link Coord3f} (pure math), and byte arrays — so it is fully headless-testable (the geometry
 * of a known {@code .glb} can be asserted numerically).
 */
public final class Gltf {
    /** World units per glTF metre (basis scale): a 1&times;1&times;1 m model is one tile ({@code MCache.tilesz.y}) — tunable via the handle's {@code :scale}. */
    public static final float MODEL_UNIT = 11f;   // = (float)MCache.tilesz.y (hard-coded so the parser needs no GL-bound class)

    /**
     * The glTF&rarr;H&amp;H basis: {@code (x,y,z)_gltf → MODEL_UNIT·(x, -z, y)_hh}. A +90&deg; rotation about X
     * (Y-up &rarr; Z-up) times a uniform model&rarr;world scale — a proper rotation (det +1), so triangle winding
     * is preserved for the (R3b) double-sided/cull work. Written row-major (the {@link Matrix4f} ctor takes
     * {@code e[row][col]}); the point transform is {@link Matrix4f#mul4(Coord3f)}.
     */
    public static final Matrix4f BASIS = new Matrix4f(
        MODEL_UNIT, 0f,          0f, 0f,   // new_x =  U * x
        0f,         0f, -MODEL_UNIT, 0f,   // new_y = -U * z
        0f,         MODEL_UNIT,  0f, 0f,   // new_z =  U * y
        0f,         0f,          0f, 1f);

    // ---- caps (a pathological asset can't hang the client) --------------------------------------------------
    static final int  MAX_PRIMS =   4096;              // total primitives across all nodes/meshes
    static final long MAX_VERTS = 4_000_000L;          // total vertices across all primitives, and any one accessor's count
    static final long MAX_INDICES = 6L * MAX_VERTS;    // any one index accessor (a closed mesh has ~6 indices per vertex)
    static final long MAX_BYTES = 128L * 1024 * 1024;  // any single decoded buffer
    static final int  MAX_IMAGES =    64;              // distinct baseColor textures referenced by the model (R3b)
    static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;  // any single texture image blob (R3b)

    // glTF material alphaMode (R3b): how the texel/base alpha is used. OPAQUE = ignore alpha; MASK = alpha-test
    // discard below alphaCutoff; BLEND = standard alpha blending. Exposed on Prim → MeshSprite material states.
    public static final int ALPHA_OPAQUE = 0, ALPHA_MASK = 1, ALPHA_BLEND = 2;

    // glTF accessor component types
    private static final int C_BYTE = 5120, C_UBYTE = 5121, C_SHORT = 5122, C_USHORT = 5123, C_UINT = 5125, C_FLOAT = 5126;

    /**
     * One baked glTF primitive: its geometry already in H&amp;H model-local space (Z up, world units) plus its
     * material description. R3a fills {@link #pos}/{@link #idx}/{@link #baseColor}/{@link #doubleSided}; R3b adds
     * {@link #tex} ({@code TEXCOORD_0}), {@link #texImage} (the {@code baseColorTexture}), and {@link #alphaMode}/
     * {@link #alphaCutoff}. {@code NORMAL} + lighting join in R3c.
     */
    public static final class Prim {
        /** Baked H&amp;H-local vertex positions, interleaved {@code x,y,z}; length = {@code nvert*3}. */
        public final float[] pos;
        /**
         * Baked, unit-length H&amp;H-local per-vertex normals, interleaved {@code x,y,z}; length = {@code nvert*3}
         * (R3c). Never {@code null}: taken from the glTF {@code NORMAL} attribute (transformed by the
         * <b>inverse-transpose</b> of the {@code basis·node} matrix so non-uniform node scale shears them correctly,
         * then re-normalized), or, when the mesh carries no {@code NORMAL}, computed as smooth area-weighted normals
         * from the baked triangle geometry. Feeds {@link haven.render.Homo3D#normal} so {@link MeshSprite} can add
         * {@link haven.Light.PhongLight} and the model shades with the world lights instead of drawing fullbright.
         */
        public final float[] nrm;
        /** Triangle indices into {@link #pos}, or {@code null} for a non-indexed (sequential) primitive. */
        public final int[]   idx;
        /**
         * {@code TEXCOORD_0} UVs, interleaved {@code u,v}; length = {@code nvert*2}; or {@code null} when the
         * primitive is untextured (no {@link #texImage}, or the material texture but no {@code TEXCOORD_0}). UVs are
         * <b>not</b> basis-baked — they are 2D texture coords, used as-is (glTF {@code v=0} = image top, which maps
         * directly to the engine's texture sampling; see {@link MeshSprite}). (R3b)
         */
        public final float[] tex;
        /** Index into {@link Gltf#images} of this primitive's {@code baseColorTexture}, or {@code -1} for untextured. (R3b) */
        public final int     texImage;
        /** Flat base colour {@code {r,g,b,a}} 0..1 (glTF {@code baseColorFactor}; default opaque white). Multiplies the texture (or is the whole colour when untextured). */
        public final float[] baseColor;
        /**
         * glTF {@code emissiveFactor} {@code {r,g,b}} 0..1 (default black — non-emissive) (R3c). Fed to the
         * {@link haven.Light.PhongLight} material's {@code emi} term, so emissive areas glow regardless of the
         * world light (they read at their full colour in shadow). {@code emissiveTexture} is deferred.
         */
        public final float[] emissive;
        /** {@code true} if the source material set {@code doubleSided} → rendered with no face cull; else back-face culled (R3b). */
        public final boolean doubleSided;
        /** glTF material {@code alphaMode}: {@link Gltf#ALPHA_OPAQUE}/{@link Gltf#ALPHA_MASK}/{@link Gltf#ALPHA_BLEND} (R3b). */
        public final int     alphaMode;
        /** {@code MASK} alpha-test threshold 0..1 (glTF {@code alphaCutoff}, default 0.5); unused for OPAQUE/BLEND (R3b). */
        public final float   alphaCutoff;

        Prim(float[] pos, float[] nrm, int[] idx, float[] tex, int texImage, float[] baseColor, float[] emissive,
             boolean doubleSided, int alphaMode, float alphaCutoff) {
            this.pos = pos;
            this.nrm = nrm;
            this.idx = idx;
            this.tex = tex;
            this.texImage = texImage;
            this.baseColor = baseColor;
            this.emissive = emissive;
            this.doubleSided = doubleSided;
            this.alphaMode = alphaMode;
            this.alphaCutoff = alphaCutoff;
        }

        /** {@code true} if this primitive has a base-colour texture ({@link #texImage} {@code >= 0} and {@link #tex} present). */
        public boolean textured() { return texImage >= 0; }
        public int nvert() { return pos.length / 3; }
        public int ntri()  { return (idx != null ? idx.length : nvert()) / 3; }
    }

    /**
     * A referenced texture image: the raw encoded bytes (PNG/JPG/…) extracted from a {@code bufferView}, a
     * {@code data:} URI, or an external file — <b>not</b> decoded here (that would touch {@code ImageIO}/GL and break
     * this class's purity). The caller ({@link LuaMesh} via {@link AddonManager}) decodes each to a shared {@code
     * TexI}. Deduped per glTF image, so a texture reused by many materials is extracted once. (R3b)
     */
    public static final class Image {
        /** The raw encoded image bytes (as embedded/downloaded); decoded to a {@code TexI} by the caller. */
        public final byte[] bytes;
        /** The glTF/data-URI MIME hint (e.g. {@code "image/png"}), or {@code null} — {@code ImageIO} sniffs the real format. */
        public final String mime;
        Image(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
    }

    /** The parsed primitives (baked, H&amp;H-local). Never empty (a model with no drawable triangle throws). */
    public final List<Prim> prims;
    /** The referenced base-colour texture image blobs, indexed by {@link Prim#texImage}; empty for an untextured model (R3b). */
    public final List<Image> images;
    /** Overall axis-aligned bounds in H&amp;H model-local units: {@code min = {x,y,z}}, {@code max = {x,y,z}}. */
    public final float[] min, max;
    /** Totals (for {@code :bounds()}, logging, and the caps). */
    public final long nvert, ntri;

    private Gltf(List<Prim> prims, List<Image> images) {
        this.prims = prims;
        this.images = images;
        float[] lo = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY };
        float[] hi = { Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        long nv = 0, nt = 0;
        for(Prim p : prims) {
            for(int i = 0; i < p.pos.length; i += 3) {
                for(int c = 0; c < 3; c++) {
                    float v = p.pos[i + c];
                    if(v < lo[c]) lo[c] = v;
                    if(v > hi[c]) hi[c] = v;
                }
            }
            nv += p.nvert();
            nt += p.ntri();
        }
        this.min = lo;
        this.max = hi;
        this.nvert = nv;
        this.ntri = nt;
    }

    /** Resolves an external buffer/image {@code uri} (a {@code .gltf} sibling file) to its bytes — provided by the caller, sandboxed to the addon folder (D-017). {@code null} for a self-contained {@code .glb}. */
    public interface Loader { byte[] read(String uri) throws Exception; }

    // ------------------------------------------------------------------------ entry point

    /**
     * Parse a {@code .glb} or {@code .gltf} model. {@code name} labels errors; {@code loader} resolves external
     * {@code .gltf} buffer/image URIs (may be {@code null} for a {@code .glb} or a data-URI-only model). Throws a
     * {@link RuntimeException} naming any unsupported feature or malformed input (the caller wraps it in a LuaError).
     */
    public static Gltf parse(byte[] bytes, String name, Loader loader) {
        if((bytes == null) || (bytes.length < 4))
            throw err(name, "empty or truncated file");
        String jsonText;
        byte[] glbBin = null;
        if((bytes[0] == 'g') && (bytes[1] == 'l') && (bytes[2] == 'T') && (bytes[3] == 'F')) {
            // .glb: [12B header][JSON chunk][BIN chunk?]
            if(bytes.length < 12)
                throw err(name, "truncated .glb header");
            long ver = u32(bytes, 4);
            if(ver != 2)
                throw err(name, "unsupported .glb version " + ver + " (need 2)");
            long total = u32(bytes, 8);
            int end = (int)Math.min(total, bytes.length);
            int off = 12;
            String js = null;
            while(off + 8 <= end) {
                long clen = u32(bytes, off);
                long ctype = u32(bytes, off + 4);
                int cstart = off + 8;
                if(cstart + clen > end)
                    throw err(name, "truncated .glb chunk");
                if(ctype == 0x4E4F534AL)          // "JSON"
                    js = new String(bytes, cstart, (int)clen, StandardCharsets.UTF_8);
                else if(ctype == 0x004E4942L)     // "BIN\0"
                    glbBin = slice(bytes, cstart, (int)clen);
                off = cstart + (int)((clen + 3) & ~3);   // chunks are 4-byte aligned
            }
            if(js == null)
                throw err(name, "no JSON chunk in .glb");
            jsonText = js;
        } else {
            // .gltf: the whole file is JSON text; buffers come from URIs (external via loader, or data:)
            jsonText = new String(bytes, StandardCharsets.UTF_8);
        }
        Object root;
        try {
            root = Json.parse(jsonText);
        } catch(RuntimeException e) {
            throw err(name, "malformed JSON (" + e.getMessage() + ")");
        }
        if(!(root instanceof Map))
            throw err(name, "top-level glTF is not a JSON object");
        return build(asMap(root), glbBin, loader, name);
    }

    // ------------------------------------------------------------------------ the build

    @SuppressWarnings("unchecked")
    private static Gltf build(Map<String, Object> g, byte[] glbBin, Loader loader, String name) {
        // asset.version sanity (some tools omit it; only reject an explicit non-2 major)
        Map<String, Object> asset = asMap(g.get("asset"));
        if(asset != null) {
            String ver = str(asset.get("version"));
            if((ver != null) && !ver.startsWith("2"))
                throw err(name, "unsupported glTF version '" + ver + "' (need 2.x)");
        }
        List<Object> buffers     = asList(g.get("buffers"));
        List<Object> bufferViews = asList(g.get("bufferViews"));
        List<Object> accessors   = asList(g.get("accessors"));
        List<Object> meshes      = asList(g.get("meshes"));
        List<Object> nodes       = asList(g.get("nodes"));
        List<Object> scenes      = asList(g.get("scenes"));
        List<Object> materials   = asList(g.get("materials"));
        List<Object> textures    = asList(g.get("textures"));   // R3b: material.baseColorTexture.index → textures[].source
        List<Object> gimages     = asList(g.get("images"));     // R3b: the encoded PNG/JPG blobs (bufferView / data-URI / external)
        if(meshes.isEmpty() || accessors.isEmpty())
            throw err(name, "no meshes/accessors (nothing to render)");

        byte[][] bufBytes = resolveBuffers(buffers, glbBin, loader, name);

        // R3b: image extraction is lazy + deduped — a texture is pulled from its buffer/URI only when a material
        // first references it, and each glTF image maps to one entry in outImages (imgRemap[glTFimage] = local idx).
        List<Image> outImages = new ArrayList<Image>();
        int[] imgRemap = new int[gimages.size()];
        java.util.Arrays.fill(imgRemap, -1);

        // Which root nodes to walk: the default scene, else scene 0, else every node nobody parents.
        int sceneIdx = intv(g.get("scene"), scenes.isEmpty() ? -1 : 0);
        int[] roots;
        if((sceneIdx >= 0) && (sceneIdx < scenes.size())) {
            List<Object> sn = asList(asMap(scenes.get(sceneIdx)).get("nodes"));
            roots = new int[sn.size()];
            for(int i = 0; i < roots.length; i++)
                roots[i] = intv(sn.get(i), 0);
        } else {
            // A document with no usable scene: its roots are the nodes no other node lists as a child.
            // Taking *every* node would enter each parented node twice -- once under its parent, once as a
            // root -- which the one-parent rule in walk() refuses; a parented document with no scene is
            // ordinary, so it must not be read as a document that reaches a node twice.
            boolean[] parented = new boolean[nodes.size()];
            for(Object n : nodes) {
                for(Object c : asList(asMap(n).get("children"))) {
                    int ci = intv(c, -1);
                    if((ci >= 0) && (ci < parented.length))
                        parented[ci] = true;
                }
            }
            int nroots = 0;
            for(boolean p : parented) {
                if(!p)
                    nroots++;
            }
            roots = new int[nroots];
            for(int i = 0, r = 0; i < parented.length; i++) {
                if(!parented[i])
                    roots[r++] = i;
            }
        }

        List<Prim> out = new ArrayList<Prim>();
        long[] totals = new long[1];   // running vertex total (for the cap)
        boolean[] visiting = new boolean[nodes.size()];   // cycle guard
        boolean[] seen = new boolean[nodes.size()];       // one-parent guard: bounds the walk to the node count
        for(int r : roots)
            walk(r, Matrix4f.id, nodes, meshes, accessors, bufferViews, bufBytes, materials,
                 textures, gimages, loader, outImages, imgRemap, out, totals, visiting, seen, name);

        if(out.isEmpty())
            throw err(name, "no triangle primitives found (only points/lines or empty meshes?)");
        return new Gltf(out, outImages);
    }

    /** Recursively bake every mesh primitive under node {@code ni}, composing its world transform down the tree. */
    private static void walk(int ni, Matrix4f parent, List<Object> nodes, List<Object> meshes, List<Object> accessors,
                             List<Object> bufferViews, byte[][] bufBytes, List<Object> materials,
                             List<Object> textures, List<Object> gimages, Loader loader, List<Image> outImages, int[] imgRemap,
                             List<Prim> out, long[] totals, boolean[] visiting, boolean[] seen, String name) {
        if((ni < 0) || (ni >= nodes.size()) || visiting[ni])
            return;                                        // out of range or a cycle → skip
        // glTF's own invariant: a node has at most one parent. A document that reaches one twice is invalid,
        // and it is refused rather than re-walked -- which bounds the whole walk to the node count. Skipping
        // the second visit instead would be silent data loss: a node under two parents is the same mesh with
        // a different baked transform, so the second instance is geometry, not a duplicate.
        if(seen[ni])
            throw err(name, "node " + ni + " is reached twice (a node has at most one parent)");
        seen[ni] = true;
        visiting[ni] = true;
        Map<String, Object> node = asMap(nodes.get(ni));
        Matrix4f world = parent.mul(localMatrix(node));
        Object meshRef = node.get("mesh");
        if(meshRef != null) {
            int mi = intv(meshRef, -1);
            if((mi >= 0) && (mi < meshes.size())) {
                Matrix4f fin = BASIS.mul(world);           // bake basis + units on top of the node world transform
                for(Object primRef : asList(asMap(meshes.get(mi)).get("primitives"))) {
                    Prim p = bakePrim(asMap(primRef), fin, accessors, bufferViews, bufBytes, materials,
                                      textures, gimages, loader, outImages, imgRemap, name);
                    if(p == null)
                        continue;                          // non-triangle mode → skipped
                    if(out.size() >= MAX_PRIMS)
                        throw err(name, "too many primitives (> " + MAX_PRIMS + ")");
                    totals[0] += p.nvert();
                    if(totals[0] > MAX_VERTS)
                        throw err(name, "too many vertices (> " + MAX_VERTS + ")");
                    out.add(p);
                }
            }
        }
        for(Object c : asList(node.get("children")))
            walk(intv(c, -1), world, nodes, meshes, accessors, bufferViews, bufBytes, materials,
                 textures, gimages, loader, outImages, imgRemap, out, totals, visiting, seen, name);
        visiting[ni] = false;
    }

    /**
     * Decode one primitive (POSITION + indices + material), baking positions by {@code fin}. Null = non-triangle mode
     * (skip). R3b also decodes {@code TEXCOORD_0} + resolves the {@code baseColorTexture} (extracting its image into
     * {@code outImages}) + the alpha mode / cull.
     */
    private static Prim bakePrim(Map<String, Object> prim, Matrix4f fin, List<Object> accessors, List<Object> bufferViews,
                                 byte[][] bufBytes, List<Object> materials, List<Object> textures, List<Object> gimages,
                                 Loader loader, List<Image> outImages, int[] imgRemap, String name) {
        int mode = intv(prim.get("mode"), 4);              // 4 = TRIANGLES (glTF default)
        if(mode != 4)
            return null;                                   // R3a: triangles only (points/lines/strips skipped)
        Map<String, Object> attrs = asMap(prim.get("attributes"));
        if(attrs == null)
            throw err(name, "primitive has no attributes");
        Object posRef = attrs.get("POSITION");
        if(posRef == null)
            throw err(name, "primitive has no POSITION attribute");
        float[] raw = readVecs(intv(posRef, -1), 3, accessors, bufferViews, bufBytes, name);   // glTF-space xyz
        // bake glTF-space → H&H-local via fin (node world · basis · units)
        float[] pos = new float[raw.length];
        Coord3f c = new Coord3f(0, 0, 0);
        for(int i = 0; i < raw.length; i += 3) {
            c.x = raw[i]; c.y = raw[i + 1]; c.z = raw[i + 2];
            Coord3f t = fin.mul4(c);
            pos[i] = t.x; pos[i + 1] = t.y; pos[i + 2] = t.z;
        }
        int nvert = pos.length / 3;
        int[] idx = null;
        Object indRef = prim.get("indices");
        if(indRef != null) {
            idx = readIndices(intv(indRef, -1), accessors, bufferViews, bufBytes, name);
            for(int i : idx) {                             // every index subscripts pos[] below (and in computeNormals)
                if((i < 0) || (i >= nvert))
                    throw err(name, "index " + i + " is outside the primitive's " + nvert + " vertices");
            }
        }
        // NORMAL (R3c): baked H&H-local, unit-length. Prefer the glTF attribute (transformed by the normal matrix =
        // inverse-transpose of `fin`, so non-uniform node scale shears it correctly); else compute smooth normals from
        // the baked triangle geometry. Never null → every primitive is lightable.
        float[] nrm = null;
        Object nrmRef = attrs.get("NORMAL");
        if(nrmRef != null) {
            float[] rawN = readVecs(intv(nrmRef, -1), 3, accessors, bufferViews, bufBytes, name);   // glTF-space normals
            if(rawN.length == nvert * 3)
                nrm = bakeNormals(rawN, fin);
        }
        if(nrm == null)
            nrm = computeNormals(pos, idx);   // no/unusable NORMAL → smooth geometric normals (H&H-space)
        // material: baseColorFactor (colour multiply) + baseColorTexture + alphaMode/cull + emissiveFactor (R3c).
        // Defaults = opaque white, single-sided, non-emissive (black).
        float[] base = { 1f, 1f, 1f, 1f };
        float[] emissive = { 0f, 0f, 0f };
        boolean dbl = false;
        int alphaMode = ALPHA_OPAQUE;
        float alphaCutoff = 0.5f;
        int texImage = -1;
        Object matRef = prim.get("material");
        if((matRef != null) && (materials != null)) {
            int mi = intv(matRef, -1);
            if((mi >= 0) && (mi < materials.size())) {
                Map<String, Object> mat = asMap(materials.get(mi));
                dbl = boolv(mat.get("doubleSided"), false);
                alphaMode = alphaMode(mat.get("alphaMode"));
                alphaCutoff = (float)dbl(mat.get("alphaCutoff"), 0.5);
                List<Object> emi = asList(mat.get("emissiveFactor"));   // R3c: emissive glow (default black)
                for(int i = 0; (i < 3) && (i < emi.size()); i++)
                    emissive[i] = (float)dbl(emi.get(i), emissive[i]);
                Map<String, Object> pbr = asMap(mat.get("pbrMetallicRoughness"));
                if(pbr != null) {
                    List<Object> bcf = asList(pbr.get("baseColorFactor"));
                    for(int i = 0; (i < 4) && (i < bcf.size()); i++)
                        base[i] = (float)dbl(bcf.get(i), base[i]);
                    Map<String, Object> bct = asMap(pbr.get("baseColorTexture"));   // R3b: the base-colour texture
                    if(bct != null) {
                        // baseColorTexture.texCoord (which TEXCOORD_n) defaults 0; a non-zero set (TEXCOORD_1) is
                        // deferred (R3c) — we always read TEXCOORD_0, so a texCoord!=0 material renders untextured.
                        int texCoord = intv(bct.get("texCoord"), 0);
                        int ti = intv(bct.get("index"), -1);
                        if((texCoord == 0) && (ti >= 0) && (ti < textures.size())) {
                            int src = intv(asMap(textures.get(ti)).get("source"), -1);
                            texImage = resolveImage(src, gimages, bufferViews, bufBytes, loader, outImages, imgRemap, name);
                        }
                    }
                }
            }
        }
        // TEXCOORD_0: decoded only when the material actually references a texture (a mesh may carry UVs it never
        // uses). If a texture is referenced but the primitive has no matching TEXCOORD_0, fall back to untextured.
        float[] tex = null;
        if(texImage >= 0) {
            Object tcRef = attrs.get("TEXCOORD_0");
            if(tcRef != null) {
                float[] uv = readVecs(intv(tcRef, -1), 2, accessors, bufferViews, bufBytes, name);   // NOT basis-baked (2D UVs)
                if(uv.length == nvert * 2)
                    tex = uv;
            }
            if(tex == null)
                texImage = -1;                             // textured material but no usable UVs → render untextured
        }
        return new Prim(pos, nrm, idx, tex, texImage, base, emissive, dbl, alphaMode, alphaCutoff);
    }

    /**
     * Bake glTF-space normals into H&amp;H-local space (R3c). Normals transform by the <b>inverse-transpose</b> of the
     * upper-left 3&times;3 of {@code fin} (= {@code basis·node}) — {@code trim3(transpose(invert(fin)))} — so a
     * non-uniform node scale shears the surface normal correctly (a plain vertex transform would skew it). Each is
     * re-normalized; a degenerate (zero) normal falls back to H&amp;H up ({@code +Z}). For a pure rotation + uniform
     * scale (the common case, and {@link #BASIS} itself) the inverse-transpose equals the rotation, so this reduces to
     * the direct transform after normalization — but doing it properly costs one 4&times;4 invert per primitive.
     */
    private static float[] bakeNormals(float[] rawN, Matrix4f fin) {
        Matrix4f nm = fin.invert().transpose();            // normal matrix; its upper 3x3 is the inverse-transpose of fin's
        float[] out = new float[rawN.length];
        for(int i = 0; i < rawN.length; i += 3) {
            float x = rawN[i], y = rawN[i + 1], z = rawN[i + 2];
            // mat3 · normal (column-major m: output row r = m[r] , m[r+4], m[r+8]); translation column ignored.
            float ox = (nm.m[0] * x) + (nm.m[4] * y) + (nm.m[8]  * z);
            float oy = (nm.m[1] * x) + (nm.m[5] * y) + (nm.m[9]  * z);
            float oz = (nm.m[2] * x) + (nm.m[6] * y) + (nm.m[10] * z);
            normInto(out, i, ox, oy, oz);
        }
        return out;
    }

    /**
     * Smooth per-vertex normals from baked H&amp;H-local geometry (R3c fallback when the mesh has no {@code NORMAL}):
     * accumulate each triangle's (un-normalized, so area-weighted) face normal into its three vertices, then normalize.
     * The {@link #BASIS} is a proper rotation (det +1), so a triangle's baked winding still yields an outward normal
     * consistent with front faces — the same reason back-face culling is correct.
     */
    private static float[] computeNormals(float[] pos, int[] idx) {
        int nvert = pos.length / 3;
        float[] acc = new float[pos.length];               // zero-initialized
        int ntri = ((idx != null) ? idx.length : nvert) / 3;
        for(int t = 0; t < ntri; t++) {
            int a = (idx != null) ? idx[(t * 3)]     : (t * 3);
            int b = (idx != null) ? idx[(t * 3) + 1] : (t * 3) + 1;
            int c = (idx != null) ? idx[(t * 3) + 2] : (t * 3) + 2;
            float ax = pos[a * 3], ay = pos[(a * 3) + 1], az = pos[(a * 3) + 2];
            float e1x = pos[b * 3] - ax, e1y = pos[(b * 3) + 1] - ay, e1z = pos[(b * 3) + 2] - az;
            float e2x = pos[c * 3] - ax, e2y = pos[(c * 3) + 1] - ay, e2z = pos[(c * 3) + 2] - az;
            float fx = (e1y * e2z) - (e1z * e2y);          // e1 × e2 (area-weighted face normal)
            float fy = (e1z * e2x) - (e1x * e2z);
            float fz = (e1x * e2y) - (e1y * e2x);
            for(int v : new int[] { a, b, c }) {
                acc[v * 3]       += fx;
                acc[(v * 3) + 1] += fy;
                acc[(v * 3) + 2] += fz;
            }
        }
        for(int i = 0; i < acc.length; i += 3)
            normInto(acc, i, acc[i], acc[i + 1], acc[i + 2]);
        return acc;
    }

    /** Write the unit-length {@code (x,y,z)} into {@code out[i..i+2]}; a zero-length vector falls back to H&amp;H up ({@code +Z}). */
    private static void normInto(float[] out, int i, float x, float y, float z) {
        float len = (float)Math.sqrt((x * x) + (y * y) + (z * z));
        if(len > 1e-8f) {
            out[i] = x / len; out[i + 1] = y / len; out[i + 2] = z / len;
        } else {
            out[i] = 0f; out[i + 1] = 0f; out[i + 2] = 1f;
        }
    }

    /**
     * Extract (once, deduped) the encoded bytes of glTF image {@code gi} into {@code outImages}, returning its local
     * index (or {@code -1} for an out-of-range reference). Source: a {@code bufferView} slice (embedded — the {@code
     * .glb} case), a {@code data:} base64 URI, or an external file via {@code loader} (re-sandboxed to the addon
     * folder by the caller). Bounded by {@link #MAX_IMAGES}/{@link #MAX_IMAGE_BYTES}; a broken reference throws a
     * named error. No {@code ImageIO}/GL here (purity) — the caller decodes the bytes.
     */
    private static int resolveImage(int gi, List<Object> gimages, List<Object> bufferViews, byte[][] bufBytes,
                                    Loader loader, List<Image> outImages, int[] imgRemap, String name) {
        if((gi < 0) || (gi >= gimages.size()))
            return -1;
        if(imgRemap[gi] >= 0)
            return imgRemap[gi];                           // already extracted for another material
        Map<String, Object> img = asMap(gimages.get(gi));
        if(img == null)
            throw err(name, "image " + gi + " is not an object");
        String mime = str(img.get("mimeType"));
        Object bvRef = img.get("bufferView");
        String uri = str(img.get("uri"));
        byte[] bytes;
        if(bvRef != null) {
            Map<String, Object> bv = bufferView(bvRef, bufferViews, name);
            int bi = intv(bv.get("buffer"), -1);
            if((bi < 0) || (bi >= bufBytes.length))
                throw err(name, "image " + gi + " bufferView buffer " + bi + " out of range");
            byte[] buf = bufBytes[bi];
            int off = intv(bv.get("byteOffset"), 0);
            int len = intv(bv.get("byteLength"), -1);
            if((len < 0) || (off < 0) || ((long)off + len > buf.length))
                throw err(name, "image " + gi + " bufferView range is out of bounds");
            bytes = slice(buf, off, len);
        } else if((uri != null) && uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if((comma < 0) || !uri.substring(0, comma).contains("base64"))
                throw err(name, "image " + gi + " data-URI is not base64");
            if(mime == null) {                              // sniff the MIME from the data: prefix (data:image/png;base64,…)
                int semi = uri.indexOf(';');
                if((semi > 5) && (semi <= comma))
                    mime = uri.substring(5, semi);
            }
            try {
                bytes = Base64.getDecoder().decode(uri.substring(comma + 1));
            } catch(IllegalArgumentException e) {
                throw err(name, "image " + gi + " has malformed base64");
            }
        } else if(uri != null) {
            if(loader == null)
                throw err(name, "image " + gi + " needs external file '" + uri + "' (use a .glb, or provide a loader)");
            try {
                bytes = loader.read(uri);
            } catch(Exception e) {
                throw err(name, "could not read external image '" + uri + "': " + e.getMessage());
            }
        } else {
            throw err(name, "image " + gi + " has neither a uri nor a bufferView");
        }
        if((bytes == null) || (bytes.length == 0))
            throw err(name, "image " + gi + " is empty");
        if(bytes.length > MAX_IMAGE_BYTES)
            throw err(name, "image " + gi + " is too large (> " + MAX_IMAGE_BYTES + " bytes)");
        if(outImages.size() >= MAX_IMAGES)
            throw err(name, "too many texture images (> " + MAX_IMAGES + ")");
        int local = outImages.size();
        outImages.add(new Image(bytes, mime));
        imgRemap[gi] = local;
        return local;
    }

    /** glTF material {@code alphaMode} string → {@link #ALPHA_OPAQUE}/{@link #ALPHA_MASK}/{@link #ALPHA_BLEND} (default OPAQUE). */
    private static int alphaMode(Object o) {
        String s = str(o);
        if(s == null)
            return ALPHA_OPAQUE;
        if(s.equals("MASK"))
            return ALPHA_MASK;
        if(s.equals("BLEND"))
            return ALPHA_BLEND;
        return ALPHA_OPAQUE;
    }

    // ------------------------------------------------------------------------ accessor decode

    /** Decode a VEC{comps} accessor to a flat float array {@code count*comps}, honouring componentType, stride, and normalization. */
    private static float[] readVecs(int ai, int comps, List<Object> accessors, List<Object> bufferViews, byte[][] bufBytes, String name) {
        Map<String, Object> acc = accessor(ai, accessors, name);
        if(acc.get("sparse") != null)
            throw err(name, "sparse accessors are not supported");
        int count = intv(acc.get("count"), 0);
        if((count < 0) || (count > MAX_VERTS))         // bound BEFORE the allocation below: a hostile count reserves nothing
            throw err(name, "accessor " + ai + " count " + count + " is past the vertex cap (> " + MAX_VERTS + ")");
        int ct = intv(acc.get("componentType"), C_FLOAT);
        int nc = typeComps(str(acc.get("type")), name);
        if(nc < comps)
            throw err(name, "accessor type has " + nc + " components, expected >= " + comps);
        boolean norm = boolv(acc.get("normalized"), false);
        int csz = compSize(ct, name);
        int accOff = intv(acc.get("byteOffset"), 0);
        Map<String, Object> bv = bufferView(acc.get("bufferView"), bufferViews, name);
        byte[] buf = bufferBytes(bv, bufBytes, name);
        int bvOff = intv(bv.get("byteOffset"), 0);
        int stride = intv(bv.get("byteStride"), csz * nc);   // 0/absent → tightly packed
        if(stride == 0)
            stride = csz * nc;
        long start = (long)bvOff + accOff;
        if((start < 0) || (stride < 0))
            throw err(name, "accessor " + ai + " has a negative byte offset or stride");
        // The last byte the stride walk below reads, computed in long BEFORE it walks: an accessor whose
        // range leaves its buffer is refused naming the model and the overrun, never followed into an
        // anonymous array-index fault. count == 0 reads nothing, so its range is the empty one at start.
        long end = (count == 0) ? start : (start + ((long)(count - 1) * stride) + ((long)comps * csz));
        if(end > buf.length)
            throw err(name, "accessor " + ai + " runs past its buffer (reads to byte " + end + " of " + buf.length + ")");
        long items = (long)count * comps;              // in long: the int product overflows for a large count
        float[] out = new float[(int)items];
        for(int e = 0; e < count; e++) {
            int base = (int)start + (e * stride);
            for(int k = 0; k < comps; k++)
                out[(e * comps) + k] = readComp(buf, base + (k * csz), ct, norm, name);
        }
        return out;
    }

    /** Decode a SCALAR index accessor to an int array (ubyte/ushort/uint). */
    private static int[] readIndices(int ai, List<Object> accessors, List<Object> bufferViews, byte[][] bufBytes, String name) {
        Map<String, Object> acc = accessor(ai, accessors, name);
        int count = intv(acc.get("count"), 0);
        if((count < 0) || (count > MAX_INDICES))       // bound BEFORE the allocation below
            throw err(name, "index accessor " + ai + " count " + count + " is past the index cap (> " + MAX_INDICES + ")");
        int ct = intv(acc.get("componentType"), C_USHORT);
        int csz = compSize(ct, name);
        int accOff = intv(acc.get("byteOffset"), 0);
        Map<String, Object> bv = bufferView(acc.get("bufferView"), bufferViews, name);
        byte[] buf = bufferBytes(bv, bufBytes, name);
        int bvOff = intv(bv.get("byteOffset"), 0);
        int stride = intv(bv.get("byteStride"), csz);
        if(stride == 0)
            stride = csz;
        long start = (long)bvOff + accOff;
        if((start < 0) || (stride < 0))
            throw err(name, "index accessor " + ai + " has a negative byte offset or stride");
        // Same bound as readVecs, one component wide: the walk's last byte in long, before it walks.
        long end = (count == 0) ? start : (start + ((long)(count - 1) * stride) + csz);
        if(end > buf.length)
            throw err(name, "index accessor " + ai + " runs past its buffer (reads to byte " + end + " of " + buf.length + ")");
        int[] out = new int[count];
        for(int e = 0; e < count; e++) {
            int at = (int)start + (e * stride);
            switch(ct) {
            case C_UBYTE:  out[e] = buf[at] & 0xff; break;
            case C_USHORT: out[e] = u16(buf, at); break;
            case C_UINT:   out[e] = (int)u32(buf, at); break;
            default: throw err(name, "index componentType " + ct + " unsupported (need ubyte/ushort/uint)");
            }
        }
        return out;
    }

    /** Read one numeric component at byte {@code at} as a float, applying glTF integer normalization when asked. */
    private static float readComp(byte[] buf, int at, int ct, boolean norm, String name) {
        switch(ct) {
        case C_FLOAT:  return Float.intBitsToFloat((int)u32(buf, at));
        case C_UBYTE:  { int v = buf[at] & 0xff;       return norm ? v / 255f       : v; }
        case C_BYTE:   { int v = buf[at];              return norm ? Math.max(v / 127f, -1f) : v; }
        case C_USHORT: { int v = u16(buf, at);         return norm ? v / 65535f     : v; }
        case C_SHORT:  { int v = (short)u16(buf, at);  return norm ? Math.max(v / 32767f, -1f) : v; }
        case C_UINT:   { long v = u32(buf, at);        return norm ? v / 4294967295f : v; }
        default: throw err(name, "componentType " + ct + " unsupported");
        }
    }

    // ------------------------------------------------------------------------ buffers

    /** Resolve every glTF buffer to its bytes: the {@code .glb} BIN for buffer 0 (no uri), a {@code data:} URI, or an external file via {@code loader}. */
    private static byte[][] resolveBuffers(List<Object> buffers, byte[] glbBin, Loader loader, String name) {
        byte[][] out = new byte[buffers.size()][];
        for(int i = 0; i < buffers.size(); i++) {
            Map<String, Object> b = asMap(buffers.get(i));
            String uri = str(b.get("uri"));
            byte[] data;
            if(uri == null) {
                if(i != 0 || glbBin == null)
                    throw err(name, "buffer " + i + " has no uri and no .glb BIN chunk");
                data = glbBin;
            } else if(uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                if((comma < 0) || !uri.substring(0, comma).contains("base64"))
                    throw err(name, "buffer " + i + " data-URI is not base64");
                try {
                    data = Base64.getDecoder().decode(uri.substring(comma + 1));
                } catch(IllegalArgumentException e) {
                    throw err(name, "buffer " + i + " has malformed base64");
                }
            } else {
                if(loader == null)
                    throw err(name, "buffer " + i + " needs external file '" + uri + "' (use a .glb, or provide a loader)");
                try {
                    data = loader.read(uri);
                } catch(Exception e) {
                    throw err(name, "could not read external buffer '" + uri + "': " + e.getMessage());
                }
            }
            if((data == null) || (data.length > MAX_BYTES))
                throw err(name, "buffer " + i + " is missing or too large (> " + MAX_BYTES + " bytes)");
            out[i] = data;
        }
        return out;
    }

    // ------------------------------------------------------------------------ transforms

    /** A node's local transform: its {@code matrix} (column-major 16) if present, else composed {@code T·R·S}. */
    private static Matrix4f localMatrix(Map<String, Object> node) {
        List<Object> m = asList(node.get("matrix"));
        if(m.size() == 16) {
            float[] a = new float[16];
            for(int i = 0; i < 16; i++)
                a[i] = (float)dbl(m.get(i), (i % 5 == 0) ? 1 : 0);   // glTF matrix is column-major = Matrix4f.m layout
            return new Matrix4f(a);
        }
        Matrix4f t = translate(vec3(node.get("translation"), 0, 0, 0));
        Matrix4f r = quat(vec4(node.get("rotation"), 0, 0, 0, 1));
        Matrix4f s = scale(vec3(node.get("scale"), 1, 1, 1));
        return t.mul(r).mul(s);
    }

    private static Matrix4f translate(float[] v) {
        return new Matrix4f(1, 0, 0, v[0],  0, 1, 0, v[1],  0, 0, 1, v[2],  0, 0, 0, 1);
    }

    private static Matrix4f scale(float[] v) {
        return new Matrix4f(v[0], 0, 0, 0,  0, v[1], 0, 0,  0, 0, v[2], 0,  0, 0, 0, 1);
    }

    /** A glTF quaternion {@code (x,y,z,w)} → a rotation {@link Matrix4f} (normalized first, for safety). */
    private static Matrix4f quat(float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float n = (float)Math.sqrt((x * x) + (y * y) + (z * z) + (w * w));
        if(n > 1e-8f) { x /= n; y /= n; z /= n; w /= n; } else { return Matrix4f.identity(); }
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        return new Matrix4f(
            1 - 2 * (yy + zz),  2 * (xy - wz),      2 * (xz + wy),      0,
            2 * (xy + wz),      1 - 2 * (xx + zz),  2 * (yz - wx),      0,
            2 * (xz - wy),      2 * (yz + wx),      1 - 2 * (xx + yy),  0,
            0,                  0,                  0,                  1);
    }

    // ------------------------------------------------------------------------ JSON accessors + byte math

    private static Map<String, Object> accessor(int ai, List<Object> accessors, String name) {
        if((ai < 0) || (ai >= accessors.size()))
            throw err(name, "accessor index " + ai + " out of range");
        return asMap(accessors.get(ai));
    }

    private static Map<String, Object> bufferView(Object ref, List<Object> bufferViews, String name) {
        if(ref == null)
            throw err(name, "accessor has no bufferView (sparse/zero accessors unsupported)");
        int bvi = intv(ref, -1);
        if((bvi < 0) || (bvi >= bufferViews.size()))
            throw err(name, "bufferView index " + bvi + " out of range");
        return asMap(bufferViews.get(bvi));
    }

    /**
     * The bytes of a {@code bufferView}'s buffer, range-checked. The glTF {@code buffer} property is required, so a
     * document without it (or with an out-of-range one) is malformed and is refused by name — never subscripted with
     * the {@code -1} an absent property reads as.
     */
    private static byte[] bufferBytes(Map<String, Object> bv, byte[][] bufBytes, String name) {
        if(bv.get("buffer") == null)
            throw err(name, "bufferView has no buffer");
        int bi = intv(bv.get("buffer"), -1);
        if((bi < 0) || (bi >= bufBytes.length))
            throw err(name, "bufferView buffer " + bi + " out of range (" + bufBytes.length + " buffers)");
        return bufBytes[bi];
    }

    private static int typeComps(String type, String name) {
        if(type == null) throw err(name, "accessor has no type");
        switch(type) {
        case "SCALAR": return 1;
        case "VEC2":   return 2;
        case "VEC3":   return 3;
        case "VEC4":   return 4;
        case "MAT2":   return 4;
        case "MAT3":   return 9;
        case "MAT4":   return 16;
        default: throw err(name, "unknown accessor type '" + type + "'");
        }
    }

    private static int compSize(int ct, String name) {
        switch(ct) {
        case C_BYTE: case C_UBYTE: return 1;
        case C_SHORT: case C_USHORT: return 2;
        case C_UINT: case C_FLOAT: return 4;
        default: throw err(name, "unknown componentType " + ct);
        }
    }

    private static int u16(byte[] b, int o)  { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
    private static byte[] slice(byte[] b, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(b, off, r, 0, len);
        return r;
    }

    // typed getters over the Json Map/List/Double/String/Boolean value model
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o)  { return (o instanceof Map) ? (Map<String, Object>)o : null; }
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o)        { return (o instanceof List) ? (List<Object>)o : java.util.Collections.<Object>emptyList(); }
    private static String str(Object o)                 { return (o instanceof String) ? (String)o : null; }
    private static double dbl(Object o, double d)       { return (o instanceof Number) ? ((Number)o).doubleValue() : d; }
    private static int intv(Object o, int d)            { return (o instanceof Number) ? (int)Math.round(((Number)o).doubleValue()) : d; }
    private static boolean boolv(Object o, boolean d)   { return (o instanceof Boolean) ? (Boolean)o : d; }

    private static float[] vec3(Object o, float a, float b, float c) {
        List<Object> l = asList(o);
        return new float[] { (float)dbl(l.size() > 0 ? l.get(0) : null, a),
                             (float)dbl(l.size() > 1 ? l.get(1) : null, b),
                             (float)dbl(l.size() > 2 ? l.get(2) : null, c) };
    }
    private static float[] vec4(Object o, float a, float b, float c, float d) {
        List<Object> l = asList(o);
        return new float[] { (float)dbl(l.size() > 0 ? l.get(0) : null, a),
                             (float)dbl(l.size() > 1 ? l.get(1) : null, b),
                             (float)dbl(l.size() > 2 ? l.get(2) : null, c),
                             (float)dbl(l.size() > 3 ? l.get(3) : null, d) };
    }

    private static RuntimeException err(String name, String msg) {
        return new RuntimeException("glTF '" + name + "': " + msg);
    }
}
