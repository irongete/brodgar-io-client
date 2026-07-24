package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Coord3f;
import haven.GAttrib;
import haven.GOut;
import haven.Gob;
import haven.PView;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;

/**
 * A <b>world-space overlay</b> pinned over one game object — the Java half of {@code hafen.ui.gobOverlay}
 * (spec {@code 07-ui-and-drawing.md}, Phase 2b). It is a {@link GAttrib} that is <i>also</i> a
 * {@link RenderTree.Node} and {@link PView.Render2D}, exactly like the voice {@code haven.SpeakerIcon}: the
 * render tree keeps it pinned above the gob in every camera and disposes it with the gob, and its 2D pass
 * runs once per frame from {@code PView.draw} → {@code list2d.draw} (on the UI thread, in the same
 * {@code UI.draw} traversal as every other widget — so a Lua draw callback here never races the tick).
 *
 * <p>One instance is attached to each gob that matches at least one registered overlay filter (by
 * {@link AddonManager}'s throttled sweep). It carries <b>no addon-specific state</b>: on each draw it
 * projects the gob's anchor to the screen and hands off to {@link AddonManager#paintGobOverlays}, which
 * re-checks every addon's filter for this gob and invokes the matching draw callbacks — so a single shared
 * attrib per gob serves all addons, and an addon whose overlay is removed simply stops being painted (the
 * idle attrib draws nothing; {@link AddonManager} detaches it once no addon wants gob overlays).
 *
 * <p>Lives in {@code io.brodgar.addon} because {@link GAttrib}'s constructor and its {@code gob} field are
 * public and nothing here needs {@code haven}-package access (unlike {@code SpeakerIcon}, which reflectively
 * reads another gob attribute). Never throws into the render pass.
 */
public final class LuaGobOverlay extends GAttrib implements RenderTree.Node, PView.Render2D {
    /**
     * Anchor height over the gob (world units) at which the overlay's screen point is computed — the same
     * height {@code SpeakerIcon} uses for the buddy name label, i.e. "just above the head". The addon's draw
     * callback receives that projected {@code (sx, sy)} and offsets from it as it likes.
     */
    private static final float ANCHOR_Z = 15f;

    /** The shared {@code g} draw wrapper, bound per draw (one per attached gob; the draw pass is single-threaded). */
    private final LuaGOut gwrap = new LuaGOut();

    LuaGobOverlay(Gob gob) {
        super(gob);
    }

    public void draw(GOut g, Pipe state) {
        Coord sc;
        try {
            Coord3f v = Homo3D.obj2view(new Coord3f(0f, 0f, ANCHOR_Z), state, Area.sized(g.sz()));
            if(v == null)
                return;   // not projectable this frame
            sc = v.round2();
        } catch(RuntimeException e) {
            return;       // never throw into the render pass (mirrors the Loading-guarded reads)
        }
        AddonManager.paintGobOverlays(gob, g, gwrap, sc);
    }
}
