package io.brodgar.addon;

import haven.Coord;
import haven.TexI;

import org.luaj.vm2.LuaValue;

/**
 * A loaded, bridge-owned custom <b>image</b> (spec {@code 17-custom-rendering.md}, R1) — the Java half of
 * {@code hafen.render.image(path)}. A PNG (or any {@code ImageIO}-decodable image) read from the addon's own
 * folder and wrapped in a {@link TexI} — the very substrate the engine's {@code .res} images already run on
 * ({@code Resource.Image} does {@code new TexI(ImageIO.read(...))}), exposed directly without the {@code .res}
 * container. Because it is a client-side texture that never reaches the server and grants no gameplay
 * advantage, it is <b>SAFE-tier, NOT gated</b> (D-034) — like a HUD overlay or a world ghost.
 *
 * <p><b>Handle, not a ref.</b> An image has no server identity, so it is addressed by a bridge-owned
 * <b>handle</b> ({@link AddonManager#imageHandle}) exposing {@code :size()} &rarr; {@code {w,h}} and
 * {@code :dispose()}. The handle table carries this {@code LuaImage} as an <b>opaque userdata</b> (the
 * {@link #KEY} field) so the {@code g} draw wrapper ({@link LuaGOut}'s {@code g:image}/{@code g:aimage}) can
 * {@link #resolve} it back to its {@link #tex}. This is the same facade-safe opaque round-trip
 * {@link LuaMarshal} uses for hook arguments (principle P1): the userdata has no metatable, so <b>no Java
 * method is reachable from Lua</b>, and it cannot be forged (the sandbox omits {@code luajava}) — the only
 * such userdata in existence are the ones the bridge created.
 *
 * <p><b>Ownership (P2).</b> Bridge-owned: it lives only in the addon's owned-resource registry
 * ({@link Addon#images}). {@code :dispose()} / {@code OnDisable} / {@code :reload} / relogin teardown
 * ({@link AddonManager#teardownImages}) frees its GPU texture ({@link TexI#dispose()}) and drops it from the
 * registry, leaking no GL resource — the same guarantee as windows, overlays, and ghosts. The {@link #dead}
 * flag makes any later {@code g:image} a clean no-op, so a disposed image never resurrects its texture via
 * {@code TexI.st()}'s lazy re-upload.
 *
 * <p><b>Threading.</b> Loading, {@code :dispose}, and teardown run on the UI thread (P5); {@link #resolve} and
 * the {@link #tex}/{@link #dead} reads happen inside a draw callback (the render thread). {@code TexI} is
 * itself draw-thread-safe (its GL upload is lazy + synchronized); {@link #dead} is {@code volatile}; the
 * {@link Addon#images} list is copy-on-write — so no extra locking is needed.
 */
public final class LuaImage {
    /** The handle-table field carrying this object as an opaque userdata (read by {@link #resolve}). */
    static final LuaValue KEY = LuaValue.valueOf("__image");

    final Addon  owner;
    final String name;      // the addon-relative path — for load-dedup, the error text, and a future filter
    final TexI   tex;       // the GPU texture (lazy upload in TexI.st()); freed by :dispose()/teardown
    final Coord  sz;        // pixel size (tex.sz()), for :size()
    volatile boolean dead;  // disposed/torn down → g:image becomes a no-op (no TexI.st() re-upload)
    LuaValue handle;        // the stable Lua handle table (so a re-load of the same path returns the same one)

    LuaImage(Addon owner, String name, TexI tex) {
        this.owner = owner;
        this.name = name;
        this.tex = tex;
        this.sz = tex.sz();
    }

    /**
     * Resolve a Lua value passed to {@code g:image}/{@code g:aimage} back to its {@link LuaImage}: the
     * {@code hafen.render.image} handle table (via its {@link #KEY} userdata field) or the raw backing userdata
     * itself; returns {@code null} for anything else (a nil/typo/foreign value &rarr; the draw verb no-ops).
     */
    static LuaImage resolve(LuaValue v) {
        if(v == null)
            return null;
        LuaValue u = v.istable() ? v.get(KEY) : v;
        if(u.isuserdata()) {
            Object o = u.touserdata();
            if(o instanceof LuaImage)
                return (LuaImage)o;
        }
        return null;
    }
}
