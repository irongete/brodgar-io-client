package io.brodgar.addon;

import haven.TexI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * <b>The loader for the files an addon ships</b> (spec {@code 028-asset-loader}): the callable
 * {@code hafen.asset} namespace, the D-017 sandbox resolver, and the one per-addon intern cache behind them.
 *
 * <p>Three entry points across two namespaces used to load an addon-relative file — {@code hafen.font.load},
 * {@code hafen.render.image}, {@code hafen.render.model} — each with its own cache (and, for fonts, none at
 * all). They collapse into <b>one door</b>: {@code hafen.asset(path)} returns a typed, <b>interned</b> handle,
 * and <b>arity is the verb</b> ([D-056]): {@code hafen.asset()} is the array of this addon's live assets. The
 * three old loaders are a hard cut ([D-013]).
 *
 * <p><b>The loader takes a path and nothing else.</b> There is no per-type options table: loading a file is
 * expensive and happens once, configuring a <i>use</i> of it is cheap and happens many times — so a font's
 * size/style comes from {@code :derive{…}}, never from the load. That is AWT's own split ({@code
 * Font.createFont} returns a 1&nbsp;pt font, {@code deriveFont} makes the variants), and it is what keeps the
 * signature uniform across every type and interning unambiguous ({@code ==} never depends on an options
 * table).
 *
 * <p><b>Dispatch is by extension</b> — {@code .png}/{@code .jpg}/{@code .jpeg}/{@code .gif}/{@code .bmp} &rarr;
 * an image ({@code ImageIO} &rarr; {@link TexI}), {@code .ttf}/{@code .otf} &rarr; a font ({@code
 * Font.createFont} + one AWT {@code registerFont}), {@code .glb}/{@code .gltf} &rarr; a mesh ({@link Gltf}),
 * {@code .json}/{@code .txt} &rarr; data (UTF-8 text, 033.3 — the door a {@code theme.json} comes through).
 * Anything else is an error listing the supported ones. Decoding is <b>synchronous</b> on the UI thread, as it
 * always was (small local assets — spec 17 §3): load from setup code, never inside a draw.
 *
 * <p><b>One cache, keyed by the RESOLVED path</b> ({@link Cache}), so {@code hafen.asset("icon.png") ==
 * hafen.asset("icon.png")} for every type — including {@code .ttf}, which previously re-read the file and
 * re-{@code registerFont}ed its family on <i>every</i> call. Identity is stable <b>while the asset is
 * alive</b>: {@code :dispose()} drops the entry, so the next load of that path is a <i>new</i> object. A dead
 * entry is never served and never listed.
 *
 * <p><b>The cache is unified; the teardown is not.</b> The typed owned-resource lists ({@link Addon#images},
 * {@link Addon#meshes}) stay the teardown units because they encode an order that is load-bearing: {@code
 * teardownObjects} must run <b>before</b> the meshes whose shared {@link TexI}s those objects sample (R3b).
 * A font asset owns nothing releasable — AWT's {@code registerFont} has no counterpart and the handle's
 * {@code RichText.Foundry} cache dies with it — so it needs no list at all; {@link #teardownAssets} drops it
 * with the cache.
 *
 * <p><b>Every asset answers {@code :type()}, {@code :path()} and {@code :dispose()}</b>, on top of its own
 * verbs ({@code :size()} for an image, {@code :bounds()}/{@code :info()} for a mesh,
 * {@code :derive}/{@code :family}/{@code :size} for a font, {@code :text()} for data). Those three are the <i>asset</i> surface: a
 * built-in font ({@code hafen.font("sans")}) and a derived variant are font handles that were never loaded
 * from a file, so they carry none of them ([D-060] — no file, no path, no lifetime).
 *
 * <p><b>Sandboxed</b> ([D-017]): {@link #resolveAddonAsset} is the single containment check every load goes
 * through — an addon reads only its own folder. Not instantiable.
 */
final class AssetApi {
    private AssetApi() {}

    /** The supported extensions, as the unknown-extension error lists them. */
    private static final String EXTS =
        ".png/.jpg/.jpeg/.gif/.bmp (image), .ttf/.otf (font), .glb/.gltf (mesh), .json/.txt (data)";

    /** Build {@code hafen.asset} for {@code owner}. From installHafen. */
    static void install(LuaTable hafen, final Addon owner) {
        hafen.set("asset", factory(owner));
    }

    /**
     * {@code hafen.asset} itself: a <b>callable table</b> ({@code __call}) taking an addon-relative path, so the
     * three cut loaders ({@code hafen.font.load}, {@code hafen.render.image}, {@code hafen.render.model}) read
     * as plain {@code nil} — the hard cut ([D-013]) is visible from Lua, exactly as {@code hafen.gob},
     * {@code hafen.sound} and {@code hafen.meter} did it. <b>Arity is the verb</b> ([D-056]):
     * {@code hafen.asset(path)} is one interned asset, {@code hafen.asset()} the array of the ones this addon
     * currently holds.
     */
    static LuaValue factory(final Addon owner) {
        LuaTable asset = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);        // arg1 = the callable table itself
                if(key.isnil())                 // hafen.asset() — this addon's live assets, in load order
                    return owner.assets.array();
                if(key.isnumber())              // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.asset(path): the key is an addon-relative PATH string (e.g."
                        + " \"icon.png\"), not a number");
                if(key.isstring())
                    return AssetApi.load(owner, key.tojstring());   // qualify: LuaValue also has a load(...)
                throw new LuaError("hafen.asset(path): expected an addon-relative path string (e.g."
                    + " hafen.asset(\"icon.png\")), got " + key.typename());
            }
        });
        asset.setmetatable(mt);
        return asset;
    }

    // ---- the per-addon intern cache ------------------------------------------------------------------

    /**
     * One addon's <b>asset intern cache</b> (its {@link Addon#assets}): {@code resolved path → the loaded
     * asset}, insertion-ordered so {@code hafen.asset()} lists in load order. Keyed by the <b>resolved</b> path,
     * so {@code "icon.png"} and {@code "img/../icon.png"} are one asset and one {@link TexI}.
     *
     * <p>Values are <b>strong</b>, unlike the {@code LuaGob}/{@code LuaSound}/… intern caches: an asset is an
     * owned resource with a lifetime, not an identity map over something the engine owns — the map <i>is</i> a
     * large part of the ownership, and it is bounded by the addon's own files. It dies whole with the
     * {@link Addon} on {@code :reload}/disable ({@link #teardownAssets} clears it after the typed teardowns
     * have freed the GPU state).
     *
     * <p>UI-thread only (every Lua call and every teardown), so no locking — the copy-on-write typed lists are
     * what the draw thread reads.
     */
    static final class Cache {
        private final Map<String, Entry> live = new LinkedHashMap<String, Entry>();
        /**
         * The <b>built-in</b> fonts this addon has asked for ({@code hafen.font("sans")}), interned by name.
         * They are deliberately kept here — one home for "the things this addon loads once" — but they are
         * <b>not assets</b>: engine-owned, no file, no lifetime, so they are never listed by
         * {@code hafen.asset()} and carry no {@code :dispose()} ([D-060]).
         */
        private final Map<String, LuaValue> builtinFonts = new HashMap<String, LuaValue>();
        /**
         * This addon's own Lua view of a font <b>another addon</b> created — minted only when
         * {@code widget:style()} (034.1) reports a rule from someone else's stylesheet. Interned by handle
         * identity ({@link FontHandle} overrides neither {@code equals} nor {@code hashCode}) so the read is
         * stable across calls, and it exists at all because <b>no Lua value crosses a sandbox boundary</b>
         * (D-017): what the two addons share is the immutable {@link FontHandle}, never a table.
         */
        private final Map<FontHandle, LuaValue> fontViews = new IdentityHashMap<FontHandle, LuaValue>();

        Entry get(String key) {return live.get(key);}
        void put(String key, Entry e) {live.put(key, e);}
        void remove(String key) {live.remove(key);}

        /** {@code hafen.asset()}: this addon's live assets as a 1-based array of their handles, in load order. */
        LuaValue array() {
            LuaTable t = new LuaTable();
            int n = 0;
            for(Entry e : live.values())
                t.set(++n, e.handle);
            return t;
        }

        LuaValue builtinFont(String name) {return builtinFonts.get(name);}
        void putBuiltinFont(String name, LuaValue h) {builtinFonts.put(name, h);}

        LuaValue fontView(FontHandle fh) {return fontViews.get(fh);}
        void putFontView(FontHandle fh, LuaValue h) {fontViews.put(fh, h);}

        /** Teardown: drop every entry (the GPU state is freed by the typed teardowns that ran first). */
        void clear() {
            live.clear();
            builtinFonts.clear();
            fontViews.clear();
        }
    }

    /** One cached asset: what it is, the path it was loaded from, and the stable handle that IS its identity. */
    static final class Entry {
        final String type;        // "image" | "font" | "mesh" | "data"
        final String path;        // the addon-relative path the FIRST load spelled — what :path() answers
        final LuaValue handle;    // the Lua handle table (interned: the same object on every re-load)

        Entry(String type, String path, LuaValue handle) {
            this.type = type;
            this.path = path;
            this.handle = handle;
        }
    }

    // ---- the loader ----------------------------------------------------------------------------------

    /**
     * {@code hafen.asset(path)}: resolve + sandbox the path, serve the interned asset if it is already loaded,
     * else dispatch on the file's extension and load it. The whole door — every addon-shipped file enters here.
     * Throws a clear, distinguishable {@link LuaError} for each way it can fail (absolute path, {@code ..}
     * escape, unknown extension, missing file, undecodable file).
     */
    static LuaValue load(Addon owner, String name) {
        Path p = resolveAddonAsset(owner, name, "hafen.asset");
        String key = p.toString();
        Entry e = owner.assets.get(key);
        if(e != null)
            return e.handle;                   // interned: the same handle for the same file, always
        String ext = extension(p);
        if(ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") || ext.equals("bmp"))
            return newImage(owner, name, key, requireFile(p, name));
        if(ext.equals("ttf") || ext.equals("otf"))
            return newFont(owner, name, key, requireFile(p, name));
        if(ext.equals("glb") || ext.equals("gltf"))
            return newMesh(owner, name, key, requireFile(p, name));
        if(ext.equals("json") || ext.equals("txt"))
            return newData(owner, name, key, requireFile(p, name));
        throw new LuaError("hafen.asset: '" + name + "' has no supported extension — hafen.asset loads "
            + EXTS);
    }

    /**
     * Resolve an addon-relative asset path to a filesystem {@link Path} <b>inside</b> the addon's own folder,
     * rejecting absolute paths and {@code ..} escapes ([D-017] — an addon reads only its own assets). {@code ctx}
     * names the caller in the error text. After {@code normalize()}, both an absolute path and a {@code ..} that
     * climbs out of the folder fail the <b>one containment check</b> (they no longer start with the folder),
     * while an internal {@code a/../b} is allowed; the two messages only say which shape got caught. Never
     * returns a path outside {@link Addon#dir}.
     *
     * <p>Moved here verbatim from {@code RenderApi} (028.1): it is the loader's own boundary, and it was already
     * the single check — {@code FontApi} called it too.
     */
    static Path resolveAddonAsset(Addon owner, String name, String ctx) {
        if((name == null) || name.isEmpty())
            throw new LuaError(ctx + ": path must be a non-empty string (addon-relative, e.g. \"icon.png\")");
        if(owner.dir == null)   // the :lua REPL owns no folder, so it has no files of "its own" to load
            throw new LuaError(ctx + ": the :lua console has no addon folder — an asset path is relative to the folder of the addon loading it");
        Path base = owner.dir.toAbsolutePath().normalize();
        Path p;
        try {
            p = base.resolve(name).normalize();
        } catch(RuntimeException e) {                 // InvalidPathException — a malformed name
            throw new LuaError(ctx + ": invalid path '" + name + "'");
        }
        if(!p.startsWith(base)) {                      // absolute, or a ".." that climbs out → rejected
            throw new LuaError(ctx + ": path '" + name + "' " + (absolute(name)
                ? "is absolute — an addon loads only its own files, by a folder-relative path (e.g. \"icon.png\")"
                : "climbs out of the addon folder with '..' — an addon loads only its own files"));
        }
        return p;
    }

    /** Is this path spelled absolutely? (A Windows drive-less {@code /foo} is not {@code isAbsolute} but has a root.) */
    private static boolean absolute(String name) {
        try {
            Path q = Paths.get(name);
            return q.isAbsolute() || (q.getRoot() != null);
        } catch(RuntimeException e) {                 // InvalidPathException — not a path at all, let alone absolute
            return false;
        }
    }

    /** The lower-cased extension of a file path ({@code ""} when it has none) — what the loader dispatches on. */
    private static String extension(Path p) {
        Path fn = p.getFileName();
        String s = (fn == null) ? "" : fn.toString();
        int dot = s.lastIndexOf('.');
        return (dot < 0) ? "" : s.substring(dot + 1).toLowerCase();
    }

    /** The one "missing file" error, shared by all three types (a decode error would name the format instead). */
    private static Path requireFile(Path p, String name) {
        if(!Files.isRegularFile(p))
            throw new LuaError("hafen.asset: no such file '" + name + "' in this addon's folder");
        return p;
    }

    // ---- images (R1) ---------------------------------------------------------------------------------

    /**
     * Load an image asset: {@code ImageIO} &rarr; {@link TexI}, the very substrate the engine's {@code .res}
     * images already run on. Registered in the addon's owned-resource list ({@link Addon#images}, which
     * teardown walks) and in the intern cache (which makes the next load of the same path the same handle).
     */
    private static LuaValue newImage(Addon owner, String name, String key, Path p) {
        BufferedImage img;
        try {
            img = ImageIO.read(p.toFile());
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.asset: could not read '" + name + "': " + e.getMessage());
        }
        if(img == null)
            throw new LuaError("hafen.asset: '" + name + "' is not a decodable image (PNG/JPG/GIF/BMP)");
        LuaImage li = new LuaImage(owner, name, new TexI(img));
        owner.images.add(li);
        LuaValue handle = imageHandle(owner, key, name, li);
        li.handle = handle;
        owner.assets.put(key, new Entry("image", name, handle));
        return handle;
    }

    /**
     * The Lua handle for a {@link LuaImage}: the shared asset verbs plus {@code :size()} &rarr; {@code {w,h}}.
     * The table also carries the {@link LuaImage} as an <b>opaque userdata</b> (its {@link LuaImage#KEY} field)
     * so {@code g:image}/{@code g:aimage} and {@code hafen.render.sprite} can {@link LuaImage#resolve} it back
     * to the texture — facade-safe (no Java method is reachable from Lua; the userdata has no metatable and
     * cannot be forged without {@code luajava}).
     */
    private static LuaValue imageHandle(Addon owner, String key, String path, final LuaImage li) {
        LuaTable h = new LuaTable();
        h.set(LuaImage.KEY, LuaValue.userdataOf(li));  // opaque backing ref for g:image / render.sprite
        h.set("size", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("w", LuaValue.valueOf(li.sz.x));
                t.set("h", LuaValue.valueOf(li.sz.y));
                return t;
            }
        });
        addAssetVerbs(h, "image", path, owner, key, new Disposer() {
            public void dispose() {disposeImage(li);}
        });
        return h;
    }

    /**
     * Free one image now (its {@code :dispose()}, and teardown): flip {@link LuaImage#dead} (so an in-flight
     * {@code g:image} on the draw thread no-ops instead of re-uploading the texture via {@code TexI.st()}), drop
     * it from the addon's registry, and dispose the {@link TexI} (frees the GL texture). The {@code dead} flag is
     * what makes dispose final — {@code TexI.dispose()} only releases, it does not invalidate (R1). Idempotent.
     */
    private static void disposeImage(LuaImage li) {
        if(li.dead)
            return;
        li.dead = true;
        li.owner.images.remove(li);
        try {
            li.tex.dispose();
        } catch(RuntimeException e) { /* best-effort: free the GL texture */ }
    }

    /** Dispose every image this addon owns: frees each {@code TexI}'s GL texture. From {@link #teardownAssets}. */
    private static void teardownImages(Addon a) {
        if(a.images.isEmpty())
            return;
        for(LuaImage li : new ArrayList<LuaImage>(a.images))
            disposeImage(li);          // removes each from a.images as it goes (copy-on-write list)
    }

    // ---- fonts (F1) ----------------------------------------------------------------------------------

    /**
     * Load a font asset: {@code Font.createFont} + <b>one</b> AWT {@code registerFont} (so {@code h:family()}
     * resolves in a {@code $font[…]} rich-text tag, F2). The base font is AWT's 1&nbsp;pt original — size and
     * style are a {@code :derive{…}} away, never part of the load ([D-056] arity, and AWT's own split).
     *
     * <p>This is the one real defect the feature fixes: {@code hafen.font.load} had <b>no cache at all</b>, so
     * every call re-read the file and re-registered the family. It is also why a font asset needs no owned list:
     * it holds no releasable resource, so the cache entry is the whole of its lifetime.
     */
    private static LuaValue newFont(Addon owner, String name, String key, Path p) {
        Font f;
        try {
            f = Font.createFont(Font.TRUETYPE_FONT, p.toFile());
        } catch(java.awt.FontFormatException e) {
            throw new LuaError("hafen.asset: '" + name + "' is not a valid TrueType/OpenType font: " + e.getMessage());
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.asset: could not read font '" + name + "': " + e.getMessage());
        }
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);   // so h:family() resolves in $font (F2)
        } catch(RuntimeException e) { /* best-effort: even if registration fails the handle still draws via its Font */ }
        LuaTable handle = FontApi.fontHandle(new FontHandle(f, null, null, null));
        addAssetVerbs(handle, "font", name, owner, key, new Disposer() {
            public void dispose() { /* a font owns nothing releasable — dropping the cache entry IS the dispose */ }
        });
        owner.assets.put(key, new Entry("font", name, handle));
        return handle;
    }

    // ---- meshes (R3) ---------------------------------------------------------------------------------

    /**
     * Load a mesh asset: a glTF 2.0 <b>static</b> model ({@code .glb} preferred, or {@code .gltf} + buffers)
     * parsed by {@link Gltf} into baked, H&amp;H-local geometry (Z up; 1 glTF metre = 1 tile), plus its shared
     * base-colour textures. External {@code .gltf} buffer/image URIs resolve <b>relative to the model file</b>
     * and are re-sandboxed to the addon folder. A parse failure (malformed data, or an unsupported feature named
     * by {@link Gltf}) raises a clear {@link LuaError}.
     */
    private static LuaValue newMesh(Addon owner, String name, String key, Path p) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.asset: could not read '" + name + "': " + e.getMessage());
        }
        final Path base = owner.dir.toAbsolutePath().normalize();
        final Path parent = p.getParent();             // external URIs resolve relative to the model file...
        Gltf.Loader loader = new Gltf.Loader() {
            public byte[] read(String uri) throws Exception {
                Path q = parent.resolve(uri).normalize();
                if(!q.startsWith(base))                // ...but never escape the addon folder (D-017)
                    throw new IOException("external asset '" + uri + "' escapes the addon folder");
                return Files.readAllBytes(q);
            }
        };
        Gltf mesh;
        try {
            mesh = Gltf.parse(bytes, name, loader);
        } catch(RuntimeException e) {
            throw new LuaError("hafen.asset: " + e.getMessage());
        }
        TexI[] textures = buildMeshTextures(mesh, name);   // R3b: the shared base-colour textures (owned by the mesh)
        LuaMesh lm = new LuaMesh(owner, name, mesh, textures);
        owner.meshes.add(lm);
        LuaValue handle = meshHandle(owner, key, name, lm);
        lm.handle = handle;
        owner.assets.put(key, new Entry("mesh", name, handle));
        return handle;
    }

    /**
     * Decode a parsed model's referenced texture image blobs ({@link Gltf#images}) into shared {@link TexI}s
     * (R3b) — the same {@code ImageIO} &rarr; {@code TexI} substrate as an image asset, but built with <b>no
     * power-of-two rounding</b> ({@code new TexI(img, false)}) so glTF {@code [0,1]} UVs sample the whole image
     * regardless of its dimensions (NPOT-safe). One {@code TexI} per glTF image (already deduped by
     * {@link Gltf}); the {@link LuaMesh} owns them and frees them in {@link #disposeMesh}. A blob that fails to
     * decode raises a clear {@link LuaError} naming the image (never a crash). Empty array for an untextured model.
     */
    private static TexI[] buildMeshTextures(Gltf mesh, String name) {
        int n = mesh.images.size();
        TexI[] out = new TexI[n];
        for(int i = 0; i < n; i++) {
            Gltf.Image im = mesh.images.get(i);
            String kind = (im.mime != null) ? im.mime : "unknown type";
            BufferedImage bi;
            try {
                bi = ImageIO.read(new ByteArrayInputStream(im.bytes));
            } catch(IOException | RuntimeException e) {
                throw new LuaError("hafen.asset: could not decode texture image " + i + " (" + kind + ") in '" + name + "': " + e.getMessage());
            }
            if(bi == null)
                throw new LuaError("hafen.asset: texture image " + i + " (" + kind + ") in '" + name + "' is not a decodable image (PNG/JPG/GIF/BMP)");
            out[i] = new TexI(bi, false);   // no POT rounding → [0,1] glTF UVs map to the full image (NPOT-safe)
        }
        return out;
    }

    /**
     * The Lua handle for a {@link LuaMesh}: the shared asset verbs plus {@code :bounds()} &rarr; {@code
     * {min={x,y,z}, max={x,y,z}, size={x,y,z}}} (world units) and {@code :info()} (what the parser produced).
     * The table also carries the {@link LuaMesh} as an <b>opaque userdata</b> ({@link LuaMesh#KEY}) so
     * {@code hafen.render.object{model=…}} can {@link LuaMesh#resolve} it back to the parsed geometry —
     * facade-safe, like the image handle.
     */
    private static LuaValue meshHandle(Addon owner, String key, String path, final LuaMesh lm) {
        LuaTable h = new LuaTable();
        h.set(LuaMesh.KEY, LuaValue.userdataOf(lm));   // opaque backing ref for hafen.render.object
        h.set("bounds", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable t = new LuaTable();
                t.set("min", vec3Table(lm.mesh.min));
                t.set("max", vec3Table(lm.mesh.max));
                t.set("size", vec3Table(new float[] {
                    lm.mesh.max[0] - lm.mesh.min[0], lm.mesh.max[1] - lm.mesh.min[1], lm.mesh.max[2] - lm.mesh.min[2] }));
                return t;
            }
        });
        // :info() → a small summary of what the parser produced (R3b): primitive/texture/triangle counts. Useful for
        // an addon (or the hello harness) to confirm a model loaded textured, and for logging.
        h.set("info", new ZeroArgFunction() {
            public LuaValue call() {
                int textured = 0, lit = 0;
                for(Gltf.Prim p : lm.mesh.prims) {
                    if(p.textured()) textured++;
                    if(p.nrm != null) lit++;                         // R3c: primitives shaded by the world lights
                }
                LuaTable t = new LuaTable();
                t.set("prims", LuaValue.valueOf(lm.mesh.prims.size()));
                t.set("textured", LuaValue.valueOf(textured));       // primitives with a base-colour texture
                t.set("lit", LuaValue.valueOf(lit));                 // primitives with normals → Phong-lit (R3c)
                t.set("textures", LuaValue.valueOf(lm.textures.length));   // distinct decoded texture images
                t.set("verts", LuaValue.valueOf((double)lm.mesh.nvert));
                t.set("tris", LuaValue.valueOf((double)lm.mesh.ntri));
                return t;
            }
        });
        addAssetVerbs(h, "mesh", path, owner, key, new Disposer() {
            public void dispose() {disposeMesh(lm);}
        });
        return h;
    }

    /** A {@code {x,y,z}} Lua table from a 3-float array (mesh bounds). */
    private static LuaTable vec3Table(float[] v) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf((double)v[0]));
        t.set("y", LuaValue.valueOf((double)v[1]));
        t.set("z", LuaValue.valueOf((double)v[2]));
        return t;
    }

    /**
     * Free one model now (its {@code :dispose()}, and teardown): flip {@link LuaMesh#dead} (so a later
     * {@code render.object} refuses it), drop it from the addon's registry, and (R3b) dispose the mesh's
     * <b>shared base-colour textures</b> (the first GPU state a mesh owns). Each {@link LuaObject} owns its own
     * engine {@code Model}s (freed by {@code teardownObjects}, which runs first), so at teardown nothing still
     * holds a sampler when its {@code TexI} goes. A manual {@code mesh:dispose()} while an object draws it does
     * <b>not</b> break that object visually (it captured the sampler at mill time — measured 028.2); it only
     * forfeits the freeing until the object is destroyed. Dispose only when unused — see {@link LuaMesh}.
     * Idempotent.
     */
    private static void disposeMesh(LuaMesh lm) {
        if(lm.dead)
            return;
        lm.dead = true;
        lm.owner.meshes.remove(lm);
        for(TexI t : lm.textures) {                   // R3b: free the shared base-colour textures
            if(t != null) {
                try { t.dispose(); } catch(RuntimeException e) { /* best-effort: free the GL texture */ }
            }
        }
    }

    /** Dispose every model this addon owns. Objects are torn down first (see {@link #teardownAssets}). */
    private static void teardownMeshes(Addon a) {
        if(a.meshes.isEmpty())
            return;
        for(LuaMesh lm : new ArrayList<LuaMesh>(a.meshes))
            disposeMesh(lm);           // removes each from a.meshes as it goes (copy-on-write list)
    }

    // ---- data (033.3) --------------------------------------------------------------------------------

    /**
     * Load a <b>data</b> asset — a {@code .json}/{@code .txt} file this addon ships, read as UTF-8 and handed to
     * Lua as its {@code :text()}. It is the door a <b>theme</b> comes through ({@code 033-ui-stylesheet}, C1a):
     * {@code hafen.json.parse(hafen.asset("theme.json"):text())} is a stylesheet as <i>data</i>, its font strings
     * mapped through {@code hafen.asset} in Lua — an addon whose look is a file, not code.
     *
     * <p><b>It hands back the TEXT, not a parsed table</b>, and that is the one canonical way rule doing its job:
     * reading a file is {@code hafen.asset}, parsing JSON is {@link Json} ({@code hafen.json}), and gluing them is
     * one Lua call. Parsing here would also make the interned value <b>mutable shared state</b> — every re-load of
     * the path handing back the same table, one addon's edit visible to its next reader — where a string is
     * immutable and interning stays honest. Like a font asset it owns nothing releasable, so dropping the cache
     * entry IS its {@code :dispose()}.
     */
    private static LuaValue newData(Addon owner, String name, String key, Path p) {
        String text;
        try {
            text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch(IOException | RuntimeException e) {
            throw new LuaError("hafen.asset: could not read '" + name + "': " + e.getMessage());
        }
        if(text.startsWith("\uFEFF"))
            text = text.substring(1);      // a UTF-8 BOM is not JSON: it would fail parse() on the very first char
        final LuaValue s = LuaValue.valueOf(text);
        LuaTable h = new LuaTable();
        h.set("text", new ZeroArgFunction() {
            public LuaValue call() {return s;}
        });
        addAssetVerbs(h, "data", name, owner, key, new Disposer() {
            public void dispose() { /* data owns nothing releasable — dropping the cache entry IS the dispose */ }
        });
        owner.assets.put(key, new Entry("data", name, h));
        return h;
    }

    // ---- the shared handle surface -------------------------------------------------------------------

    /** What one asset type does when its handle is disposed (the type-specific half of {@code :dispose()}). */
    private interface Disposer {
        void dispose();
    }

    /**
     * Install the verbs <b>every</b> asset carries, whatever its type: {@code :type()} (the dispatch result —
     * {@code "image"}/{@code "font"}/{@code "mesh"}/{@code "data"}), {@code :path()} (the addon-relative path it was loaded
     * from), and {@code :dispose()} (free it now — also automatic on {@code :reload}/disable/relogin, P2).
     *
     * <p>{@code :dispose()} drops the intern entry <b>first</b>, which is what makes "identity is stable while
     * alive" honest: a later {@code hafen.asset(path)} re-loads the file into a <i>new</i> object rather than
     * handing back a corpse. It returns the handle, so it chains like every other verb here.
     */
    private static void addAssetVerbs(LuaTable h, final String type, final String path, final Addon owner,
                                      final String key, final Disposer d) {
        h.set("type", new ZeroArgFunction() {
            public LuaValue call() {return LuaValue.valueOf(type);}
        });
        h.set("path", new ZeroArgFunction() {
            public LuaValue call() {return LuaValue.valueOf(path);}
        });
        h.set("dispose", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                owner.assets.remove(key);   // a re-load after this is a NEW asset, never the disposed one
                d.dispose();
                return a.arg1();
            }
        });
    }

    // ---- teardown ------------------------------------------------------------------------------------

    /**
     * Dispose every asset this addon loaded (reload/disable/relogin, P2). <b>The cache is unified; the teardown
     * is not</b>: the typed owned-resource lists are walked in the order R3b requires — images, then meshes,
     * whose shared {@link TexI}s the addon's {@link LuaObject}s sample and which {@code RenderApi.teardownObjects}
     * (called <b>before</b> this, from {@link AddonRegistry#teardown}) has already freed their {@code Model}s
     * for. Font assets own nothing releasable, so dropping the cache is their whole teardown. Leaves no GL
     * resource behind.
     *
     * <p>Unlike sounds (024.2) and cached text (026.1) there is no {@code :reload} sweep of the {@code :lua}
     * REPL owner: it has no addon folder ({@link Addon#dir} is {@code null}), so it can hold no assets at all.
     */
    static void teardownAssets(Addon a) {
        teardownImages(a);      // R1: frees each TexI's GL texture
        teardownMeshes(a);      // R3b: frees the shared base-colour textures (after the objects that sampled them)
        a.assets.clear();       // the intern map itself — a load after a :reload is a fresh asset
    }
}
