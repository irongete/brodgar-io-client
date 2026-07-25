package io.brodgar.addon;

import java.awt.Color;

import haven.Coord2d;
import haven.Glob;
import haven.Gob;
import haven.render.BaseColor;
import haven.render.BlendMode;
import haven.render.FragColor;
import haven.render.Location;
import haven.render.MixColor;
import haven.render.Pipe;
import haven.render.States;

/**
 * The {@link Gob} behind a client-only world ghost ({@code hafen.ghost}, spec {@code 16-virtual-entities.md}) —
 * a plain virtual gob (id {@code -1} ⇒ {@code Gob.virtual}: never in {@code OCache}, invisible to the server and
 * every read API) with two extra behaviours, both applied in {@link #obstate}: it can be made
 * <b>pick-selectable</b> (V2, {@link D-032}) and given a <b>look</b> — a colour {@link #tint} and/or a
 * translucent {@link #alpha} (V3, the "ghost" appearance).
 *
 * <p><b>Why a subclass is needed.</b> The engine makes a gob clickable by prepping a {@link Gob.GobClick} in its
 * render state ({@code Gob.GobState.apply}) — but <b>only for non-virtual gobs</b> ({@code if(!virtual)}). A ghost
 * is virtual, so a plain {@code Gob} ghost carries no {@code GobClick} and the MapView pick pass never returns it.
 * {@code GobState.apply} does, however, call the {@code protected} extension hook {@code obstate(Pipe)} for every
 * gob, virtual or not — so this subclass overrides it to add the {@code GobClick} when {@link #clickable}, giving
 * a virtual ghost a click surface <b>without</b> flipping {@code virtual} (which would break its OCache/server
 * invisibility and the {@code cg.virtual} fast-path the {@code Click.hit} intercept uses to detect ghosts). The
 * same hook prepares the look states (below).
 *
 * <p><b>Look (V3).</b> {@code obstate} composes, in addition to the click surface, the render states that give a
 * ghost its appearance — the same primitives the engine itself uses for gob tinting and translucent overlays:
 * <ul>
 *   <li><b>tint</b> → a {@link MixColor} (the exact state {@code GobHealth} uses for the red damage tint): blends
 *       the colour into the object's fragments, the colour's alpha being the blend strength. Purely a colour
 *       overlay — it does not make the object see-through.</li>
 *   <li><b>alpha &lt; 1</b> → a {@link BaseColor} {@code (1,1,1,alpha)} (multiplies the fragment alpha) plus
 *       {@link FragColor#blend standard alpha blending} plus {@link States#maskdepth} (don't write depth) — the
 *       engine's own recipe for a translucent overlay (see the tile-grid overlay / drag-select rectangle in
 *       {@code MapView}). This is the see-through "ghost" look.</li>
 *   <li><b>scale &ne; 1</b> (V6) → a uniform {@link Location#scale(float) scaling} {@code Location}. Because
 *       {@code obstate} runs on the gob's <i>child</i> render slot — <b>below</b> the {@code Placed} slot that
 *       applies the world translate ({@code "gobx"}) + facing rotation ({@code "gob"}) — the scale composes as
 *       {@code T·R·S}, i.e. it scales the model <b>in place</b> around the gob's own origin (its feet), not the
 *       world origin, and rotates/translates correctly on top. (A sprite that does
 *       {@code Location.goback("gobx")}, e.g. {@code resutil.CSprite}, resets past both the facing and this
 *       scale — such resources already ignore ghost rotation, so they ignore scale too.)</li>
 * </ul>
 *
 * <p><b>Toggling / applying a change.</b> The click-list decides membership <b>at slot-add time</b> and
 * {@code GobState.equals} compares only the {@code SetupMod} mods (not {@code obstate}'s output), so neither a
 * {@link #clickable} flip nor a {@link #tint}/{@link #alpha} change propagates through the normal
 * {@code updated()}/{@code updstate()} path — {@link AddonManager} applies all three by removing and re-adding the
 * gob to the scene ({@code AddonManager.refreshGhostScene}), where {@code obstate} runs fresh and reads the
 * current fields. {@code obstate} is evaluated at render-apply time, so every field here is read live.
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

    /** V3: opacity 0..1 — {@code 1} = fully opaque (no extra state); {@code < 1} = translucent. Read live by {@link #obstate}. */
    public volatile float alpha = 1f;

    /** V3: colour-overlay {@link MixColor} tint, or {@code null} for none (the colour's alpha is the blend strength). Read live by {@link #obstate}. */
    public volatile Color tint = null;

    /** V6: uniform scale — {@code 1} = original size (no extra state); anything else is an in-place scaling {@link Location}. Read live by {@link #obstate}. */
    public volatile float scale = 1f;

    public GhostGob(Glob glob, Coord2d c) {
        super(glob, c);   // id -1 ⇒ virtual (Gob.virtual): no server id, not in OCache, invisible to reads/server
    }

    /**
     * Extension hook called from {@code Gob.GobState.apply} for every gob (the one path {@code virtual} does not
     * gate). Preps (a) a {@link Gob.GobClick} when {@link #clickable} — the exact state a real gob gets, so the mesh
     * inherits it and the MapView pick pass ({@code Clicklist}) returns this gob — and (b) the V3 look states:
     * a {@link MixColor} for {@link #tint} and, when {@link #alpha} {@code < 1}, {@link BaseColor} + alpha-blend +
     * {@link States#maskdepth} for translucency. Nothing is prepped when not clickable / opaque / untinted, so a
     * plain decorative ghost stays a normal opaque, click-through prop. Each field is snapshotted once (it is
     * {@code volatile}) so a concurrent change can't tear a single apply.
     */
    protected void obstate(Pipe buf) {
        if(clickable)
            buf.prep(new Gob.GobClick(this));
        Color tc = this.tint;                       // snapshot the volatile once
        if(tc != null)
            buf.prep(new MixColor(tc));             // colour overlay (blend strength = tc.getAlpha()); GobHealth's tint pattern
        float al = this.alpha;                      // snapshot the volatile once
        if(al < 1f) {
            buf.prep(new BaseColor(1f, 1f, 1f, al)); // multiply the fragment alpha
            buf.prep(FragColor.blend(new BlendMode())); // standard SRC_ALPHA / INV_SRC_ALPHA blending
            buf.prep(States.maskdepth);             // don't write depth — the engine's translucent-overlay recipe
        }
        float sc = this.scale;                      // V6: snapshot the volatile once
        if(sc != 1f)
            buf.prep(Location.scale(sc));           // uniform scale; composes under the gob's translate+rotate → local-origin scaling
    }
}
