package io.brodgar.addon;

import java.awt.Color;

import haven.Coord2d;
import haven.Gob;
import haven.Indir;
import haven.MapView;
import haven.MessageBuf;
import haven.Resource;
import haven.render.RenderTree;

import org.luaj.vm2.LuaValue;

/**
 * A client-only world <b>ghost</b> (spec {@code 16-virtual-entities.md}, V1) — the Java half of
 * {@code hafen.ghost.new{...}}. A ghost is a virtual {@link Gob} with <b>no server id</b> (id {@code -1} ⇒
 * {@code Gob.virtual}), given a visual ({@code ResDrawable}) and rendered in the MapView's {@code basic}
 * scene via {@link haven.MapView#addClientGob} — exactly what the engine's own placement preview
 * ({@code MapView.Plob}) does. Because it never reaches the server (no {@code wdgmsg}) and is invisible to
 * {@code OCache} and every read API, it is <b>SAFE-tier, NOT gated</b> (D-029): a pure visualization, like a
 * HUD overlay. The motivating use is city/base planning — lay out ghost buildings over the real terrain.
 *
 * <p><b>Handle, not a GobRef.</b> A ghost has no server id, so re-resolution is meaningless; it is addressed
 * by a bridge-owned <b>handle</b> (D-030), like a {@code hafen.ui.window}. The Lua handle
 * ({@link AddonManager#ghostHandle}) exposes {@code :move(x,y[,a])} / {@code :pos()} / {@code :destroy()} /
 * {@code :res()} (V1), {@code :clickable(bool)} (V2 — opt-in pick-selectability; see {@link #clickable} /
 * {@link #onClick} and {@link GhostGob}), and the V3 look/orientation verbs {@code :rotate(a)} /
 * {@code :setRes(res[,sdt])} / {@code :show()} / {@code :hide()} / {@code :alpha(a)} / {@code :tint(color)}.
 *
 * <p><b>Desired-state fields (V3).</b> {@link #alpha}, {@link #tint}, {@link #hidden}, and the current
 * {@link #res}/{@link #resName}/{@link #sdt} are the ghost's <i>desired</i> state, guarded by {@code this}. They
 * are applied to the {@link #gob} when it exists and, crucially, are read by the deferred create at publish time —
 * so an {@code :alpha}/{@code :setRes}/{@code :hide} that lands <i>before</i> the prop streams in still takes
 * effect. {@link #res}/{@link #resName}/{@link #sdt} are non-final because {@code :setRes} swaps the visual.
 *
 * <p><b>Deferred create (the {@code Plob} precedent).</b> Building the {@code ResDrawable} calls
 * {@code res.get()}, which throws {@code Loading} until the resource is cached — so, exactly like {@code Plob}
 * and {@code hafen.sound.play}, the create is handed to {@code glob.loader.defer}: the loader re-runs the task
 * when the resource lands, then constructs the gob + {@code addClientGob}s it (a loader thread; RenderTree slot
 * mutation is tree-locked, so it is safe there). {@link #gob}/{@link #slot} stay {@code null} until that
 * completes; the handle methods work meanwhile off {@link #rc}/{@link #a} (a {@code :move} before the gob
 * exists just updates the target the deferred create applies).
 *
 * <p><b>Ownership (P2).</b> The ghost is bridge-owned: it lives only in the addon's owned-resource registry
 * ({@link Addon#ghosts}) — there is no global tick/poll list, because a ghost is a passive render node driven
 * by the render tree's own tick, not the addon tick loop. {@code OnDisable} / {@code :reload} / relogin
 * teardown ({@link AddonManager#teardownGhosts}) destroys each (removes its scene slot + disposes the sprite),
 * leaking nothing — the same guarantee as windows and overlays. The {@link #dead} flag makes any late handle
 * call, or the deferred create landing after a destroy, a clean no-op.
 *
 * <p><b>Threading.</b> The handle methods, {@code newGhost}, and teardown run on the UI thread (P5); only the
 * one-shot deferred create runs on a loader thread. {@code synchronized(this)} guards the {@link #rc}/{@link #a}
 * target and the {@link #gob}/{@link #slot}/{@link #dead} publish/read so the deferred create never races a
 * concurrent {@code :move}/{@code :destroy}.
 */
public final class LuaGhost {
    final Addon owner;
    Indir<Resource> res;           // the ghost's visual resource (resolved on a loader thread); swapped by :setRes (V3)
    String resName;                // the resource name, for :res(), the string filter, and error text; swapped by :setRes
    MessageBuf sdt;                // V3: optional spawn-data bytes for the drawable (null ⇒ MessageBuf.nil); guarded by this

    Coord2d rc;                    // target/current world position (login-relative), guarded by this
    double  a;                     // target/current facing (radians), guarded by this
    boolean clickable;             // V2: opt-in pick-selectability (mirrored onto the GhostGob's flag); guarded by this
    float   alpha = 1f;            // V3: desired opacity 0..1 (1 = opaque); mirrored onto the GhostGob; guarded by this
    Color   tint;                  // V3: desired colour-overlay tint, or null; mirrored onto the GhostGob; guarded by this
    boolean hidden;                // V3: :hide() removed the scene slot (gob kept); :show() re-adds it; guarded by this
    LuaValue onClick;              // V2: per-ghost click callback fn(g, button, x, y), or null; set at create, read-only after

    Gob gob;                       // the client-only Gob, or null until the deferred create publishes it
    RenderTree.Slot slot;          // its scene slot, or null until added / while hidden; removed on destroy/teardown
    MapView mv;                    // the MapView the gob was added to (so destroy removes it from THAT tick list)
    boolean dead;                  // destroyed (or torn down): every op becomes a no-op, deferred create undoes
    boolean failed;                // the resource could not be resolved (bad name) — create abandoned

    LuaValue handle;               // the stable Lua handle (so hafen.ghost.list returns the same object)

    LuaGhost(Addon owner, Indir<Resource> res, String resName, Coord2d rc, double a) {
        this.owner = owner;
        this.res = res;
        this.resName = resName;
        this.rc = rc;
        this.a = a;
    }
}
