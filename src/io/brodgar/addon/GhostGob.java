package io.brodgar.addon;

import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.render.Pipe;

/**
 * The {@link Gob} behind a client-only world ghost ({@code hafen.ghost}, spec {@code 16-virtual-entities.md}) —
 * a plain virtual gob (id {@code -1} ⇒ {@code Gob.virtual}: never in {@code OCache}, invisible to the server and
 * every read API) with <b>one</b> extra behaviour: it can be made <b>pick-selectable</b> (V2, {@link D-032}).
 *
 * <p><b>Why a subclass is needed.</b> The engine makes a gob clickable by prepping a {@link Gob.GobClick} in its
 * render state ({@code Gob.GobState.apply}) — but <b>only for non-virtual gobs</b> ({@code if(!virtual)}). A ghost
 * is virtual, so a plain {@code Gob} ghost carries no {@code GobClick} and the MapView pick pass never returns it.
 * {@code GobState.apply} does, however, call the {@code protected} extension hook {@code obstate(Pipe)} for every
 * gob, virtual or not — so this subclass overrides it to add the {@code GobClick} when {@link #clickable}, giving
 * a virtual ghost a click surface <b>without</b> flipping {@code virtual} (which would break its OCache/server
 * invisibility and the {@code cg.virtual} fast-path the {@code Click.hit} intercept uses to detect ghosts).
 *
 * <p><b>Toggling.</b> The click-list decides membership <b>at slot-add time</b> (a later ancestor-state change
 * does not re-run its {@code Clickable} filter), so {@link AddonManager#setGhostClickable} toggles a live ghost by
 * removing and re-adding it to the scene — on the re-add, {@code obstate} is applied fresh and reads the current
 * {@link #clickable}. {@code obstate} itself is evaluated at render-apply time, so the flag is read live.
 *
 * <p>The pick resolves in {@code MapView.Click.hit}; because {@code GobClick.gob} is this gob, the engine's own
 * {@code clickedgob(inf)} returns it, and {@link AddonManager#onGhostClick} finds the owning ghost, fires
 * {@code GhostClicked} + its {@code onClick}, and consumes the click — <b>no {@code wdgmsg}</b>, so nothing reaches
 * the server and the whole thing stays SAFE-tier (D-029/D-032).
 */
public final class GhostGob extends Gob {
    /**
     * Whether this ghost currently has a pick surface. Read by {@link #obstate} at render-apply time (so a toggle
     * takes effect on the next scene re-add — see {@link AddonManager#setGhostClickable}). {@code volatile} because
     * it is set on the UI/stdin thread and read on the render thread when the gob's state is (re)applied.
     */
    public volatile boolean clickable;

    public GhostGob(Glob glob, Coord2d c) {
        super(glob, c);   // id -1 ⇒ virtual (Gob.virtual): no server id, not in OCache, invisible to reads/server
    }

    /**
     * Extension hook called from {@code Gob.GobState.apply} for every gob (the one path {@code virtual} does not
     * gate). When this ghost is {@link #clickable} we prep a {@link Gob.GobClick} — the exact state a real gob gets
     * — so the mesh inherits it and the MapView pick pass ({@code Clicklist}) returns this gob. Nothing is prepped
     * when not clickable, so a decorative ghost never wins a pick (normal game clicks pass straight through it).
     */
    protected void obstate(Pipe buf) {
        if(clickable)
            buf.prep(new Gob.GobClick(this));
    }
}
