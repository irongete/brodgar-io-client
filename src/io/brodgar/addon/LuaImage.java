package io.brodgar.addon;

import haven.Coord;
import haven.ScaledTex;
import haven.TexI;

import org.luaj.vm2.LuaValue;

/**
 * A loaded, bridge-owned custom <b>image</b> (spec {@code 17-custom-rendering.md}, R1) — the Java half of
 * {@code hafen.asset("icon.png")} (028.1; was {@code hafen.render.image}). A PNG (or any {@code ImageIO}-decodable image) read from the addon's own
 * folder and wrapped in a {@link TexI} — the very substrate the engine's {@code .res} images already run on
 * ({@code Resource.Image} does {@code new TexI(ImageIO.read(...))}), exposed directly without the {@code .res}
 * container. Because it is a client-side texture that never reaches the server and grants no gameplay
 * advantage, it is <b>SAFE-tier, NOT protected</b> (D-034) — like a HUD overlay or a world ghost.
 *
 * <p><b>Handle, not a ref.</b> An image has no server identity, so it is addressed by a bridge-owned
 * <b>handle</b>: this object itself, crossing into Lua as {@code LuaValue.userdataOf(this, mt)} with one of
 * {@link AssetApi.Kind}'s per-addon metatables ({@code __index} = the shared methods table, so the handle
 * costs a userdata and no closures), exposing the shared asset verbs and {@code :size()} &rarr;
 * {@code {w,h}}. The {@code g} draw wrapper ({@link LuaGOut}'s {@code g:image}/{@code g:aimage}) and
 * {@code hafen.vr():sprite()} {@link #resolve} the value straight back to its {@link #tex}. It is
 * facade-safe (principle P1): no Java method is reachable through the vocabulary, the value cannot be
 * written to from Lua, and it cannot be forged — the sandbox omits {@code luajava}, so the only such
 * userdata in existence are the ones the bridge created.
 *
 * <p><b>Ownership (P2).</b> Bridge-owned: it lives only in the addon's owned-resource registry
 * ({@link Addon#images}). {@code :dispose()} / {@code Disable} / {@code :reload} / relogin teardown
 * ({@link AssetApi#teardownAssets}) frees its GPU texture ({@link TexI#dispose()}) and drops it from the
 * registry, leaking no GL resource — the same guarantee as windows, overlays, and ghosts. The {@link #dead}
 * flag makes any later {@code g:image} a clean no-op, so a disposed image never resurrects its texture via
 * {@code TexI.st()}'s lazy re-upload.
 *
 * <p><b>Threading.</b> Loading, {@code :dispose}, and teardown run on the UI thread (P5); {@link #resolve} and
 * the {@link #tex}/{@link #dead} reads happen inside a draw callback (the render thread). {@code TexI} is
 * itself draw-thread-safe (its GL upload is lazy + synchronized); {@link #dead} is {@code volatile}; the
 * {@link Addon#images} list is copy-on-write — so no extra locking is needed.
 */
public final class LuaImage implements AssetApi.Loaded {
    final Addon  owner;
    final String name;      // the addon-relative path — for load-dedup, the error text, and a future filter
    final TexI   tex;       // the GPU texture (lazy upload in TexI.st()); freed by :dispose()/teardown
    final Coord  sz;        // DESIGN pixel size (tex.sz()), for :size() — see stex
    /**
     * <b>The same texture, at the size it is drawn</b> (058.2) — a {@link ScaledTex} view wrapping {@link #tex}
     * at {@code UI.scale(sz)}. An addon's PNG is authored for the client's own design pixels, so {@link #sz} —
     * the raster's own size — <i>is</i> the size {@code img:size()} answers and the size {@code g:image} covers,
     * and the UI scale is applied on the way to the screen exactly as it is for the client's own art — the same
     * rule {@code hafen.vr}'s {@code "screen"} sprites are drawn by ({@link LuaSpriteBillboard}).
     *
     * <p>Built once, here, rather than per draw: {@code UI.scalef} is read once at class init and never moves,
     * so the wrapper is as constant as the texture it wraps — and a {@code g:image} in a draw callback runs
     * every frame. It is a <b>view</b>, not a resource: {@link #tex} is the only thing disposed, and disposing
     * this one would dispose that one twice.
     */
    final ScaledTex<TexI> stex;
    volatile boolean dead;  // disposed/torn down → g:image becomes a no-op (no TexI.st() re-upload)
    LuaValue handle;        // the stable Lua handle (so a re-load of the same path returns the same one)
    /**
     * What the shared asset verbs answer for this picture, and how it frees itself — the addon-relative path
     * for a loaded file, the description of the ground for a {@link MapImages rendered map image}. On the
     * record because the metatable that reads it is shared by every image this addon holds.
     */
    AssetApi.Asset asset;

    LuaImage(Addon owner, String name, TexI tex) {
        this.owner = owner;
        this.name = name;
        this.tex = tex;
        this.sz = tex.sz();
        this.stex = Px.in(tex);
    }

    public AssetApi.Asset asset() {
        return asset;
    }

    /**
     * Resolve a Lua value passed to {@code g:image}/{@code g:aimage} back to its {@link LuaImage}: an image
     * handle, whether the one its owner holds or the reduced view another addon reads off a rule. {@code null}
     * for anything else (a nil, a typo, a foreign value &rarr; the draw verb no-ops) — including a
     * hand-built table, which is what stops a look-alike lying about its size wherever it is passed.
     */
    static LuaImage resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaImage) ? (LuaImage)o : null;
    }
}
