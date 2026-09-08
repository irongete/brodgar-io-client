package io.brodgar.addon;

import haven.TexI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

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
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * <b>The loader for the files an addon ships</b> (spec {@code 028-asset-loader}): the {@code hafen.asset()}
 * collection, the D-017 sandbox resolver, and the one per-addon intern cache behind them.
 *
 * <p><b>One door.</b> {@code hafen.asset():get(path)} returns a typed, <b>interned</b> handle and {@code
 * hafen.asset():list()} is this addon's live assets. There is no second loader and no per-type cache: one
 * sandbox resolver and one intern table serve every kind the dispatch below names.
 *
 * <p><b>The loader takes a path and nothing else.</b> There is no per-type options table: loading a file is
 * expensive and happens once, configuring a <i>use</i> of it is cheap and happens many times — so a font's
 * size/style comes from {@code :derive()} and its setters, never from the load. That is AWT's own split ({@code
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
 * alive</b>: {@code hafen.asset():remove(a)} drops the entry, so the next load of that path is a <i>new</i>
 * object. A dead entry is never served and never listed.
 *
 * <p><b>The cache is unified; the teardown is not.</b> The typed owned-resource lists ({@link Addon#images},
 * {@link Addon#meshes}) stay the teardown units because they encode an order that is load-bearing: {@code
 * teardownObjects} must run <b>before</b> the meshes whose shared {@link TexI}s those objects sample (R3b).
 * A font asset owns nothing releasable — AWT's {@code registerFont} has no counterpart and the handle's
 * {@code RichText.Foundry} cache dies with it — so it needs no list at all; {@link #teardownAssets} drops it
 * with the cache.
 *
 * <p><b>Every asset answers {@code :type()} and {@code :path()}</b>, on top of its own verbs
 * ({@code :size()} for an image, {@code :bounds()}/{@code :info()} for a mesh,
 * {@code :derive}/{@code :family}/{@code :size} for a font, {@code :text()} for data). Those two are the
 * <i>asset</i> surface: a built-in font ({@code hafen.font("sans")}) and a derived variant are font handles
 * that were never loaded from a file, so they carry neither ([D-060] — no file, no path, no lifetime).
 *
 * <p><b>Freeing one is the COLLECTION's verb</b> ({@code hafen.asset():remove(a)}): the asset collection
 * exists and owns its members, and where a collection exists the destroy verb is on it. So the handle carries
 * no ending of its own — which is also the sandbox guarantee stated the other way round, since a
 * {@link #imageFor view} of a file another addon loaded is reached through <i>your</i> {@code hafen.asset()}
 * and refused there, naming the owner.
 *
 * <p><b>Sandboxed</b> ([D-017]): every load goes through {@link Inside}, the client's one containment check
 * — {@link #resolveAddonAsset} for the file the addon named, and the same call again for each external URI
 * a {@code .gltf} reaches for. An addon reads only its own folder, and a link inside that folder pointing
 * out of it is refused like any other path out of it. Not instantiable.
 */
final class AssetApi {
    private AssetApi() {}

    /** The supported extensions, as the unknown-extension error lists them. */
    private static final String EXTS =
        ".png/.jpg/.jpeg/.gif/.bmp (image), .ttf/.otf (font), .glb/.gltf (mesh), .json/.txt (data)";

    /**
     * Build {@code hafen.asset} for {@code owner}: <b>the section object IS the collection</b> of the files this
     * addon ships (spec §2.1). {@code hafen.asset():get(path)} loads and interns one, {@code :list(filter)} reads
     * the ones it currently holds, {@code :find} answers by path substring, and {@code :remove(a)} frees one
     * <b>now</b> rather than waiting for teardown. There is no {@code :add} — an asset is a file the addon
     * shipped, not something it creates here.
     */
    static void install(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "asset", collection(owner),
                      "hafen.asset(path) is now hafen.asset():get(path), and hafen.asset() is"
                      + " hafen.asset():list()");
    }

    /** {@code hafen.asset()} — the addon's own loaded files, addressed by their addon-relative path. */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.asset()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                return owner.assets.members();
            }

            public String needle(LuaValue member) {
                return owner.assets.pathOf(member);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.isnumber())              // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.asset():get(path): the key is an addon-relative PATH string (e.g."
                        + " \"icon.png\"), not a number");
                if(!key.isstring())
                    throw new LuaError("hafen.asset():get(path): expected an addon-relative path string (e.g."
                        + " hafen.asset():get(\"icon.png\")), got " + key.typename());
                return AssetApi.load(owner, key.tojstring());   // qualify: LuaValue also has a load(...)
            }

            /** A file this addon does not ship is a mistake in the addon, not a miss. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.RAISE;
            }

            /** The key is the addon-relative PATH of a file this addon ships. */
            public String keyName() {
                return "path";
            }

            /** The collection owns the files this addon loaded, so freeing one is <b>its</b> verb (D2). */
            public boolean destroyable() {
                return true;
            }

            /**
             * {@code hafen.asset():remove(a)} — free one loaded file <b>now</b> rather than at teardown, and
             * drop its intern entry, so the next {@code :get(path)} re-reads the file into a NEW object.
             *
             * <p><b>It compares the owner before it frees anything.</b> The ending is the collection's, and a
             * caller reaches it through their OWN {@code hafen.asset()} — so the guarantee that an addon
             * cannot free a file it never loaded is a refusal here rather than a verb the {@link #imageFor
             * view} does not carry, and a refusal has to name whose job it is.
             */
            public void removeMember(LuaValue x) {
                if(x.isstring())         // a number IS a string in LuaJ, and both are the same mistake
                    throw new LuaError("hafen.asset():remove(a): pass the HANDLE, not a path —"
                        + " hafen.asset():remove(hafen.asset():get(\"icon.png\")). Every use site of an asset"
                        + " takes the handle, and this one is no different");
                Object o = x.isuserdata() ? x.touserdata() : null;
                if(!(o instanceof Loaded))
                    throw new LuaError("hafen.asset():remove(a): expected an asset handle, got "
                        + x.typename() + " — hafen.asset():get(path) is what hands one back");
                Asset a = ((Loaded)o).asset();
                if(a == null)
                    throw new LuaError("hafen.asset():remove(a): a built-in font and a :derive()d variant were"
                        + " never loaded from a file, so hafen.asset() does not hold them and there is nothing"
                        + " to free");
                if(a.owner != owner)
                    throw new LuaError("hafen.asset():remove(a): that " + typeOf(x) + " was loaded by addon '"
                        + AddonManager.ownerName(a.owner) + "' — freeing a file is the job of the addon that"
                        + " loaded it, and hafen.asset() holds your own files only");
                if(!a.member())
                    throw new LuaError("hafen.asset():remove(a): a map drawing is not one of this addon's"
                        + " files — grid:image(lvl) renders it and img:dispose() frees the one it handed back");
                a.dispose();
            }
        }, null);
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
         * {@code hafen.asset()} and are not members it can remove ([D-060]).
         */
        private final Map<String, LuaValue> builtinFonts = new LinkedHashMap<String, LuaValue>();
        /**
         * This addon's own Lua view of a font <b>another addon</b> created — minted only when
         * {@code widget:style()} (034.1) reports a rule from someone else's stylesheet. Interned by handle
         * identity ({@link FontHandle} overrides neither {@code equals} nor {@code hashCode}) so the read is
         * stable across calls, and it exists at all because <b>no Lua value crosses a sandbox boundary</b>
         * (D-017): what the two addons share is the immutable {@link FontHandle}, never a table.
         */
        private final Map<FontHandle, LuaValue> fontViews = new IdentityHashMap<FontHandle, LuaValue>();
        /**
         * The same thing for an <b>image</b> another addon loaded (035.1) — minted only when
         * {@code widget:style()} reports a {@code bg}/{@code border} from someone else's rule. Interned by
         * {@link LuaImage} identity, and for the same D-017 reason: the two addons share the loaded image, never
         * a table.
         */
        private final Map<LuaImage, LuaValue> imageViews = new IdentityHashMap<LuaImage, LuaValue>();

        Entry get(String key) {return live.get(key);}
        void put(String key, Entry e) {live.put(key, e);}
        void remove(String key) {live.remove(key);}

        /** {@code hafen.asset():list()}: this addon's live assets, in load order — the collection's members. */
        List<LuaValue> members() {
            List<LuaValue> out = new ArrayList<LuaValue>();
            for(Entry e : live.values())
                out.add(e.handle);
            return out;
        }

        /** The addon-relative path a member was loaded from — what a string filter matches on. */
        String pathOf(LuaValue handle) {
            for(Entry e : live.values()) {
                if(e.handle == handle)
                    return e.path;
            }
            return null;
        }

        LuaValue builtinFont(String name) {return builtinFonts.get(name);}
        void putBuiltinFont(String name, LuaValue h) {builtinFonts.put(name, h);}

        /** {@code hafen.font():list()}: the built-in fonts this addon has named so far. */
        List<LuaValue> builtinFonts() {
            return new ArrayList<LuaValue>(builtinFonts.values());
        }

        /** The built-in NAME a member was interned under — what a string filter on that collection matches. */
        String builtinFontName(LuaValue handle) {
            for(Map.Entry<String, LuaValue> e : builtinFonts.entrySet()) {
                if(e.getValue() == handle)
                    return e.getKey();
            }
            return null;
        }

        LuaValue fontView(FontHandle fh) {return fontViews.get(fh);}
        void putFontView(FontHandle fh, LuaValue h) {fontViews.put(fh, h);}

        LuaValue imageView(LuaImage li) {return imageViews.get(li);}
        void putImageView(LuaImage li, LuaValue h) {imageViews.put(li, h);}

        /** Teardown: drop every entry (the GPU state is freed by the typed teardowns that ran first). */
        void clear() {
            live.clear();
            builtinFonts.clear();
            fontViews.clear();
            imageViews.clear();
        }
    }

    /** One cached asset: what it is, the path it was loaded from, and the stable handle that IS its identity. */
    static final class Entry {
        final String type;        // "image" | "font" | "mesh" | "data"
        final String path;        // the addon-relative path the FIRST load spelled — what :path() answers
        final LuaValue handle;    // the Lua handle (interned: the same object on every re-load)

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
     * Resolve an addon-relative asset path to a filesystem {@link Path} <b>inside</b> the addon's own folder
     * ([D-017] — an addon reads only its own assets), through {@link Inside}, the one containment check this
     * client has. {@code ctx} names the caller in the error text. Never returns a path outside
     * {@link Addon#dir} — not for an absolute name, not for a {@code ..} that climbs out, and not for a link
     * inside the folder that points anywhere else. An internal {@code a/../b} is allowed and comes back
     * resolved.
     *
     * <p>The one thing it asks before {@link Inside} does is whether this owner has a folder at all: the
     * {@code :lua} console has none, which is a different answer from "outside it".
     */
    static Path resolveAddonAsset(Addon owner, String name, String ctx) {
        if(owner.dir == null)   // the :lua REPL owns no folder, so it has no files of "its own" to load
            throw new LuaError(ctx + ": the :lua console has no addon folder — an asset path is relative to the folder of the addon loading it");
        return Inside.inside(owner.dir, name, ctx);
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
     *
     * <p>The handle is <b>userdata over the {@link LuaImage} itself</b>, wearing this addon's {@link Kind#IMAGE}
     * metatable — so {@code g:image}, {@code hafen.virtual():sprite()} and a rule's {@code bg} resolve the record
     * straight off the value ({@link LuaImage#resolve}), and there is no table around it to scribble on, to
     * delete {@code dispose} from, or to copy into a look-alike that lies about its size.
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
        final LuaImage li = new LuaImage(owner, name, new TexI(img));
        li.asset = new Asset(owner, name) {
            void dispose() {
                owner.assets.remove(key);  // a re-load after this is a NEW asset, never the disposed one
                disposeImage(li);
            }
        };
        owner.images.add(li);
        LuaValue handle = LuaValue.userdataOf(li, meta(owner, Kind.IMAGE));
        li.handle = handle;
        owner.assets.put(key, new Entry("image", name, handle));
        return handle;
    }

    /**
     * The image behind a resolved {@code bg}/{@code border}, as {@code reader} may hold it ({@code widget:style()},
     * 035.1) — the mirror of {@link FontApi#handleFor}. Its own rule hands back the very handle it loaded, so
     * {@code w:style().bg.image == panel} holds; a rule from <b>another addon's</b> sheet arrives as
     * {@code reader}'s own interned view, because no Lua value crosses a sandbox boundary (D-017).
     *
     * <p>The view reads and it draws, and it cannot be freed: an addon that could free a file it never loaded
     * would be able to blank another addon's UI. Since the ending moved onto the collection that is a
     * <b>refusal</b> rather than a missing verb — {@code hafen.asset():remove(a)} is reached through the
     * caller's own {@code hafen.asset()}, so {@link Source#removeMember} compares {@link Asset#owner} and says
     * whose job it is. The {@link Kind#IMAGE_VIEW} metatable stays, because the two sides still answer a typo
     * with different sentences.
     */
    static LuaValue imageFor(Addon reader, final LuaImage li) {
        if((li.owner == reader) && (li.handle != null))
            return li.handle;
        LuaValue v = reader.assets.imageView(li);
        if(v == null)
            reader.assets.putImageView(li, v = LuaValue.userdataOf(li, meta(reader, Kind.IMAGE_VIEW)));
        return v;
    }

    /**
     * Free one image now ({@code hafen.asset():remove(img)}, a map drawing's {@code :dispose()}, and
     * teardown): flip {@link LuaImage#dead} (so an in-flight
     * {@code g:image} on the draw thread no-ops instead of re-uploading the texture via {@code TexI.st()}), drop
     * it from the addon's registry, and dispose the {@link TexI} (frees the GL texture). The {@code dead} flag is
     * what makes dispose final — {@code TexI.dispose()} only releases, it does not invalidate (R1). Idempotent.
     */
    static void disposeImage(LuaImage li) {
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
            // The AWT font registry is the JVM's ONE namespace of family names, and it has no counterpart:
            // nothing unregisters a family, so a family this addon loads stays resolvable in $font[…] for
            // every addon after this one is disabled. That half cannot be fixed here, and runtime.md says so.
            // What CAN be said is the other half the return value carries (audit2 B01): false means the family
            // name was already taken — by the OS, by the client, or by another addon's file — and $font[…]
            // will therefore draw THAT face rather than this file's. The handle itself is unaffected: it holds
            // the Font this load created and draws it whoever else owns the name.
            if(!GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f))
                AddonManager.log("font family '" + f.getFamily() + "' (" + owner.manifest.id + ": " + name + ")"
                                 + " is already registered in this client — $font[" + f.getFamily() + "] draws"
                                 + " the face that took the name first; the handle itself draws this file");
        } catch(RuntimeException e) { /* best-effort: even if registration fails the handle still draws via its Font */ }
        FontHandle fh = new FontHandle(f, null, null, null);
        fh.asset = new Asset(owner, name) {
            void dispose() {
                owner.assets.remove(key);  // a font owns nothing releasable — dropping the entry IS the dispose
            }
        };
        LuaValue handle = FontApi.fontHandle(owner, fh, Kind.FONT_ASSET);
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
        final Path beside = Paths.get(name).getParent();   // the model's own folder, addon-relative
        Gltf.Loader loader = new Gltf.Loader() {
            public byte[] read(String uri) throws Exception {
                // An external URI resolves relative to the model file, and then goes through the SAME check
                // the model itself came through (D-017) — one door, in one wording, with no second hole.
                Path rel = (beside == null) ? Paths.get(uri) : beside.resolve(uri);
                return Files.readAllBytes(Inside.inside(owner.dir, rel.toString(), "hafen.asset"));
            }
        };
        Gltf mesh;
        try {
            mesh = Gltf.parse(bytes, name, loader);
        } catch(RuntimeException e) {
            throw new LuaError("hafen.asset: " + e.getMessage());
        }
        TexI[] textures = buildMeshTextures(mesh, name);   // R3b: the shared base-colour textures (owned by the mesh)
        final LuaMesh lm = new LuaMesh(owner, name, mesh, textures);
        lm.asset = new Asset(owner, name) {
            void dispose() {
                owner.assets.remove(key);  // a re-load after this is a NEW asset, never the disposed one
                disposeMesh(lm);
            }
        };
        owner.meshes.add(lm);
        LuaValue handle = LuaValue.userdataOf(lm, meta(owner, Kind.MESH));
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
     * {@code mdl:bounds()} &rarr; {@code {min={x,y,z}, max={x,y,z}, extent={x,y,z}}}, the parsed geometry's
     * axis-aligned bounds in world units.
     */
    private static LuaValue bounds(LuaMesh lm) {
        LuaTable t = new LuaTable();
        t.set("min", vec3Table(lm.mesh.min));
        t.set("max", vec3Table(lm.mesh.max));
        // 085.3: the span is `extent`, because `size` is the two-number shape everywhere else in the
        // API and a three-number span wearing that word is the same collision a {x=,y=} size was.
        t.set("extent", vec3Table(new float[] {
            lm.mesh.max[0] - lm.mesh.min[0], lm.mesh.max[1] - lm.mesh.min[1], lm.mesh.max[2] - lm.mesh.min[2] }));
        t.setmetatable(BOUNDS_META);
        return t;
    }

    /**
     * {@code mdl:info()} &rarr; a small summary of what the parser produced (R3b): primitive/texture/triangle
     * counts. Useful for an addon to confirm a model loaded textured, and for logging.
     */
    private static LuaValue meshInfo(LuaMesh lm) {
        int textured = 0, lit = 0;
        for(Gltf.Prim p : lm.mesh.prims) {
            if(p.textured()) textured++;
            if(p.nrm != null) lit++;                             // R3c: primitives shaded by the world lights
        }
        LuaTable t = new LuaTable();
        t.set("prims", LuaValue.valueOf(lm.mesh.prims.size()));
        t.set("textured", LuaValue.valueOf(textured));           // primitives with a base-colour texture
        t.set("lit", LuaValue.valueOf(lit));                     // primitives with normals → Phong-lit (R3c)
        t.set("textures", LuaValue.valueOf(lm.textures.length)); // distinct decoded texture images
        t.set("verts", LuaValue.valueOf((double)lm.mesh.nvert));
        t.set("tris", LuaValue.valueOf((double)lm.mesh.ntri));
        return t;
    }

    /**
     * The metatable {@code mdl:bounds()} wears (085.3), built once and shared: {@code .size} raises naming
     * {@code .extent} rather than reading {@code nil} under code that was already written.
     */
    private static final LuaTable BOUNDS_META =
        LuaWidget.shapeMeta("bounds");

    /** A {@code {x,y,z}} Lua table from a 3-float array (mesh bounds). */
    private static LuaTable vec3Table(float[] v) {
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf((double)v[0]));
        t.set("y", LuaValue.valueOf((double)v[1]));
        t.set("z", LuaValue.valueOf((double)v[2]));
        return t;
    }

    /**
     * Free one model now ({@code hafen.asset():remove(mdl)} and teardown): flip {@link LuaMesh#dead} (so a later
     * {@code render.object} refuses it), drop it from the addon's registry, and (R3b) dispose the mesh's
     * <b>shared base-colour textures</b> (the first GPU state a mesh owns). Each {@link LuaObject} owns its own
     * engine {@code Model}s (freed by {@code teardownObjects}, which runs first), so at teardown nothing still
     * holds a sampler when its {@code TexI} goes. Removing a mesh by hand while an object draws it does
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
     * {@code hafen.json():parse(hafen.asset("theme.json"):text())} is a stylesheet as <i>data</i>, its font strings
     * mapped through {@code hafen.asset} in Lua — an addon whose look is a file, not code.
     *
     * <p><b>It hands back the TEXT, not a parsed table</b>, and that is the one canonical way rule doing its job:
     * reading a file is {@code hafen.asset}, parsing JSON is {@link Json} ({@code hafen.json}), and gluing them is
     * one Lua call. Parsing here would also make the interned value <b>mutable shared state</b> — every re-load of
     * the path handing back the same table, one addon's edit visible to its next reader — where a string is
     * immutable and interning stays honest. Like a font asset it owns nothing releasable, so dropping the cache
     * entry IS the whole of its freeing.
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
        Data d = new Data(LuaValue.valueOf(text));
        d.asset = new Asset(owner, name) {
            void dispose() {
                owner.assets.remove(key);  // data owns nothing releasable — dropping the entry IS the dispose
            }
        };
        LuaValue h = LuaValue.userdataOf(d, meta(owner, Kind.DATA));
        owner.assets.put(key, new Entry("data", name, h));
        return h;
    }

    /**
     * The record behind a data asset's handle: the file's text, read once, and the asset facet the shared verbs
     * read off it. It exists for the same reason {@link LuaImage} and {@link LuaMesh} do — a handle is userdata
     * over a record, and the record is what a shared metatable resolves. It owns nothing releasable.
     */
    static final class Data implements Loaded {
        /** The file's contents as an immutable Lua string, which is the whole of what {@code d:text()} is. */
        final LuaValue text;
        private Asset asset;

        Data(LuaValue text) {
            this.text = text;
        }

        public Asset asset() {
            return asset;
        }
    }

    // ---- the shared handle surface -------------------------------------------------------------------

    /**
     * The <b>shape</b> a loaded file's handle wears, and therefore which of this addon's metatables it is
     * minted with ({@link Addon#assetMeta}). A kind is a vocabulary, not a file type: the two image kinds are
     * one picture seen from two sides — the handle its <b>owner</b> holds, and the reduced <b>view</b> another
     * addon reads off a rule ({@link #imageFor}, {@link FontApi#handleFor}), which carries no
     * {@code :dispose()} because freeing an asset is the owner's to do. That distinction was "which verbs were
     * set on this table" and is now "which metatable it was minted with".
     */
    enum Kind {IMAGE, IMAGE_VIEW, MAP_IMAGE, MESH, DATA, FONT, FONT_ASSET}

    /**
     * A <b>record</b> a handle is minted over — {@link LuaImage}, {@link LuaMesh}, {@link FontHandle},
     * {@link Data} — asked for the asset facet the shared verbs read. {@code null} where the record is not a
     * loaded file at all (a built-in font, a {@code :derive()}d variant), which is exactly the set of handles
     * whose kind carries none of those verbs.
     */
    interface Loaded {
        Asset asset();
    }

    /**
     * <b>What the shared asset verbs answer for one loaded file, and how it frees itself.</b> It hangs off the
     * RECORD rather than off the handle, because the metatable that reads it is shared by every asset of its
     * kind — where a per-handle table could close over the path and the disposer, a shared one has to find
     * them on {@code self}.
     *
     * <p>{@link #dispose()} drops the intern entry <b>first</b>, which is what makes "identity is stable while
     * alive" honest: a later {@code hafen.asset():get(path)} re-loads the file into a <i>new</i> object rather
     * than handing back a corpse.
     */
    static abstract class Asset {
        /**
         * The addon that loaded it — what {@code hafen.asset():remove(a)} compares before it frees anything,
         * since the ending is reached through the CALLER's collection and a view of another addon's file
         * resolves to this same record.
         */
        final Addon owner;
        /** The addon-relative path of the FIRST load — what {@code a:path()} answers. */
        final String path;

        Asset(Addon owner, String path) {
            this.owner = owner;
            this.path = path;
        }

        /**
         * Is it a <b>member of {@code hafen.asset()}</b>? False for a {@link MapImages map drawing}, which
         * wears this same facet but is a picture the client drew rather than a file the addon shipped: it is
         * reached through {@code grid:image(lvl)}, it is listed by no collection, and it keeps its own
         * {@code :dispose()}.
         */
        boolean member() {
            return true;
        }

        abstract void dispose();
    }

    /**
     * The verbs <b>every</b> loaded file answers, contributed to one kind's methods table: {@code :type()} (the
     * dispatch result — {@code "image"}/{@code "font"}/{@code "mesh"}/{@code "data"}) and {@code :path()} (the
     * addon-relative path it was loaded from). <b>There is no ending here</b>: freeing one is
     * {@code hafen.asset():remove(a)} on the collection that owns it, and it happens on
     * {@code :reload}/disable/relogin either way (P2).
     */
    static void addAssetVerbs(LuaTable m, final String type) {
        m.set("type", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                asset(self, "type");
                return LuaValue.valueOf(type);
            }
        });
        m.set("path", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(asset(self, "path").path);
            }
        });
    }

    /**
     * {@code img:dispose()} — the one ending that stays on a handle, for the one asset facet that is not the
     * member of a collection: a {@link MapImages map drawing}, which {@code grid:image(lvl)} hands back and
     * which appears in no {@code hafen.asset()} list. It returns the handle, like every other verb here.
     */
    static void addDisposeVerb(LuaTable m) {
        m.set("dispose", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                asset(self, "dispose").dispose();
                return self;
            }
        });
    }

    /** The asset facet behind a shared verb's {@code self}, or the error that says what a colon call needs. */
    static Asset asset(LuaValue self, String method) {
        Object o = self.isuserdata() ? self.touserdata() : null;
        Asset a = (o instanceof Loaded) ? ((Loaded)o).asset() : null;
        if(a == null)
            throw new LuaError("a:" + method + "() — use a COLON call on a file this addon loaded"
                + " (hafen.asset():get(\"icon.png\"))");
        return a;
    }

    /**
     * The per-addon metatable one {@link Kind} of handle wears, built on the first handle of that kind. Per
     * addon for the reason every metatable in the bridge is: no Lua value crosses a sandbox boundary (D-017).
     * Unlocked, like every other lazy metatable here — two threads racing build two equal ones and one wins.
     */
    static LuaValue meta(Addon owner, Kind k) {
        LuaValue mt = owner.assetMeta[k.ordinal()];
        if(mt != null)
            return mt;
        switch(k) {
        case FONT:
        case FONT_ASSET:
            mt = FontApi.fontMeta(owner, k);
            break;
        case MAP_IMAGE:
            mt = MapImages.imageMeta();
            break;
        default:
            mt = buildMeta(k);
            break;
        }
        owner.assetMeta[k.ordinal()] = mt;
        return mt;
    }

    /** The metatables this file owns: an image, the reduced view of one, a mesh and a data file. */
    private static LuaValue buildMeta(Kind k) {
        LuaTable m = new LuaTable();
        switch(k) {
        case IMAGE:
        case IMAGE_VIEW:
            m.set("size", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return LuaWidget.whTable(image(self, "size").sz);
                }
            });
            addAssetVerbs(m, "image");
            return fileMeta("image", "image", m,
                (k == Kind.IMAGE) ? "an image asset" : "an image another addon loaded",
                (k == Kind.IMAGE) ? "hafen.asset():remove(img) frees it"
                                  : "freeing a file is the job of the addon that loaded it");
        case MESH:
            m.set("bounds", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return bounds(mesh(self, "bounds"));
                }
            });
            m.set("info", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return meshInfo(mesh(self, "info"));
                }
            });
            addAssetVerbs(m, "mesh");
            return fileMeta("mesh", "mesh", m, "a mesh asset", "hafen.asset():remove(mdl) frees it");
        default:
            m.set("text", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return data(self, "text").text;
                }
            });
            addAssetVerbs(m, "data");
            return fileMeta("data", "data", m, "a data asset", "hafen.asset():remove(d) frees it");
        }
    }

    /**
     * The metatable a loaded file's handle wears: its closed vocabulary ({@link Refusal#closedIndex}, so a
     * typo raises naming what this kind does answer) and a {@code __tostring} of {@code Asset(image,
     * icon.png)} — which is the whole reason a log line of an addon's own handles says anything.
     */
    static LuaValue fileMeta(String entity, final String type, LuaTable methods, String blurb,
                             String note) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex(entity, methods, blurb, note));
        mt.set("__name", LuaValue.valueOf("Asset"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Object o = self.isuserdata() ? self.touserdata() : null;
                Asset a = (o instanceof Loaded) ? ((Loaded)o).asset() : null;
                return LuaValue.valueOf("Asset(" + type + ", " + ((a == null) ? "?" : a.path) + ")");
            }
        });
        return mt;
    }

    /**
     * <b>Which kind of loaded file a value is</b> — {@code "image"} / {@code "font"} / {@code "mesh"} /
     * {@code "data"} — or {@code null} for anything that is not one. It reads the RECORD behind the handle
     * rather than calling its {@code :type()}, because {@code type} is a verb four unrelated objects answer
     * (a marker's kind, a widget's class) and a probe would report one of those as an asset kind. A built-in
     * font answers {@code "font"}: it is a face that was never a file, which is exactly what a verb refusing
     * the wrong kind of handle wants to say.
     */
    static String typeOf(LuaValue v) {
        Object o = ((v == null) || !v.isuserdata()) ? null : v.touserdata();
        if(o instanceof LuaImage)
            return "image";
        if(o instanceof LuaMesh)
            return "mesh";
        if(o instanceof FontHandle)
            return "font";
        if(o instanceof Data)
            return "data";
        return null;
    }

    /** The receiver of an image verb, or the error that says a colon call on an image handle is what it takes. */
    static LuaImage image(LuaValue self, String method) {
        Object o = self.isuserdata() ? self.touserdata() : null;
        if(!(o instanceof LuaImage))
            throw new LuaError("img:" + method + "() — use a COLON call on an image handle"
                + " (hafen.asset():get(\"icon.png\"))");
        return (LuaImage)o;
    }

    /** The receiver of a mesh verb. */
    private static LuaMesh mesh(LuaValue self, String method) {
        Object o = self.isuserdata() ? self.touserdata() : null;
        if(!(o instanceof LuaMesh))
            throw new LuaError("mdl:" + method + "() — use a COLON call on a mesh handle"
                + " (hafen.asset():get(\"chair.glb\"))");
        return (LuaMesh)o;
    }

    /** The receiver of a data verb. */
    private static Data data(LuaValue self, String method) {
        Object o = self.isuserdata() ? self.touserdata() : null;
        if(!(o instanceof Data))
            throw new LuaError("d:" + method + "() — use a COLON call on a data handle"
                + " (hafen.asset():get(\"theme.json\"))");
        return (Data)o;
    }

    // ---- teardown ------------------------------------------------------------------------------------

    /**
     * Dispose every asset this addon loaded (reload/disable/relogin, P2). <b>The cache is unified; the teardown
     * is not</b>: the typed owned-resource lists are walked in the order R3b requires — images, then meshes,
     * whose shared {@link TexI}s the addon's {@link LuaObject}s sample and which {@code VirtualApi.teardownObjects}
     * (called <b>before</b> this, from {@link AddonRegistry#teardown}) has already freed their {@code Model}s
     * for. Font assets own nothing releasable, so dropping the cache is their whole teardown. Leaves no GL
     * resource behind.
     *
     * <p>The {@code :lua} REPL owner walks this step like any other owner and it is always a no-op for it: it
     * has no addon folder ({@link Addon#dir} is {@code null}), so it can hold no assets at all.
     */
    static void teardownAssets(Addon a) {
        teardownImages(a);      // R1: frees each TexI's GL texture
        teardownMeshes(a);      // R3b: frees the shared base-colour textures (after the objects that sampled them)
        a.assets.clear();       // the intern map itself — a load after a :reload is a fresh asset
    }
}
