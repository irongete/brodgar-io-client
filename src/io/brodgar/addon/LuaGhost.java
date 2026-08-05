package io.brodgar.addon;

import haven.Coord2d;
import haven.Indir;
import haven.MessageBuf;
import haven.Resource;

/**
 * A client-only world <b>ghost</b> (spec {@code 16-virtual-entities.md}, V1) — the Java half of
 * {@code hafen.ghost.new{...}}. A {@link LuaWorldEntity} whose visual is a {@code .res} <b>game model</b>
 * ({@code ResDrawable}), added to the MapView's {@code basic} scene via {@link haven.MapView#addClientGob} —
 * exactly what the engine's own placement preview ({@code MapView.Plob}) does. Because it never reaches the
 * server (no {@code wdgmsg}) and is invisible to {@code OCache} and every read API, it is <b>SAFE-tier, NOT
 * gated</b> (D-029): a pure visualization, like a HUD overlay. The motivating use is city/base planning — lay
 * out ghost buildings over the real terrain.
 *
 * <p>The transform ({@code :move}/{@code :rotate}/{@code :scale}), look ({@code :alpha}/{@code :tint}),
 * pick-selectability ({@code :clickable}), scene lifecycle, and gizmo all live in {@link LuaWorldEntity} +
 * {@link AddonManager}'s shared {@code *Entity} helpers; this subclass adds only the {@code .res}-model
 * specifics.
 *
 * <p><b>Res-model specifics.</b> {@link #res}/{@link #resName}/{@link #sdt} are the ghost's <i>desired</i>
 * resource visual (guarded by {@code this}); they are non-final because {@code :setRes} swaps the model, and are
 * read by the deferred create at publish time so a {@code :setRes} that lands before the prop streams in is
 * honoured. {@link #resName} backs {@link #visualName()} (the {@code list} string-filter + {@code :res()}).
 *
 * <p><b>Deferred create (the {@code Plob} precedent).</b> Building the {@code ResDrawable} calls
 * {@code res.get()}, which throws {@code Loading} until the resource is cached — so, exactly like {@code Plob}
 * and {@code hafen.sound():get(name):play()}, the create is handed to {@code glob.loader.defer}: the loader re-runs the task
 * when the resource lands, then constructs the gob + {@code addClientGob}s it. The {@link #gob}/{@link #slot}
 * (inherited) stay {@code null} until that completes; the handle methods work meanwhile off the desired
 * transform. (A {@link LuaSprite}, by contrast, has an already-decoded {@code TexI} and so creates synchronously.)
 */
public final class LuaGhost extends LuaWorldEntity {
    Indir<Resource> res;           // the ghost's visual resource (resolved on a loader thread); swapped by :setRes (V3)
    String resName;                // the resource name, for :res(), the string filter, and error text; swapped by :setRes
    MessageBuf sdt;                // V3: optional spawn-data bytes for the drawable (null ⇒ MessageBuf.nil); guarded by this
    boolean failed;                // the resource could not be resolved (bad name) — create abandoned

    LuaGhost(Addon owner, Indir<Resource> res, String resName, Coord2d rc, double a) {
        super(owner, rc, a);
        this.res = res;
        this.resName = resName;
    }

    void unregister() { owner.ghosts.remove(this); }

    String visualName() { return resName; }

    String clickEvent() { return "GhostClicked"; }   // V2: the owner-scoped click event (unchanged)
    String clickKey()   { return "ghost"; }
}
