package haven;

import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import io.brodgar.voice.Voice;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A small speaker icon floating above a player's head, colour-coded by their
 * Brodgar voice state:
 * <ul>
 *   <li><b>grey</b> — voice on, idle</li>
 *   <li><b>green</b> — speaking</li>
 *   <li><b>red</b> — muted (by you)</li>
 *   <li><b>yellow</b> — muted but speaking</li>
 * </ul>
 *
 * <p>The icon is placed <i>just to the left of the player's name</i> when the game
 * is drawing one (the {@code haven.res.ui.obj.buddy.Info} name label, at {@code zo=15}
 * centred over the head), and centred over the head when there is no name. Since
 * {@code Info} is a server-resource class (no compile-time handle), its rendered-name
 * texture is read reflectively to find the name's width; any failure falls back to
 * centring — never throws into the render pass.
 *
 * <p>Pure client presentation: the {@link Voice} library exposes the per-player state
 * through its public API; this class only decides how to draw it and never touches the
 * shared {@code Voice} adapter. Modelled on {@code haven.Speaking}: a {@link GAttrib}
 * that is also {@link RenderTree.Node} and {@link PView.Render2D}, so the render tree
 * keeps it pinned above the head in every camera and disposes it with the gob. Lives in
 * {@code haven} to read the gob's package-private attribute map. Attach one to every
 * player gob with {@link #sweep(MapView)} once per frame.
 */
public final class SpeakerIcon extends GAttrib implements RenderTree.Node, PView.Render2D {

    /** Same anchor height the buddy name label uses (its {@code obj2view((0,0,15))}). */
    private static final float NAME_Z = 15f;
    /** On-screen footprint of the icon. */
    private static final Coord ISZ = UI.scale(new Coord(28, 28));
    private static final int GAP = UI.scale(3);
    private static final Tex GLYPH = mkGlyph();

    /** The resource class that draws player names over their heads. */
    private static final String INFO_CLASS = "haven.res.ui.obj.buddy.Info";
    private static volatile Field rendFld;

    private static final Color GREY   = new Color(190, 190, 190);
    private static final Color GREEN  = new Color(80, 220, 90);
    private static final Color RED    = new Color(230, 70, 70);
    private static final Color YELLOW = new Color(240, 210, 60);

    private final MapView mv;

    private SpeakerIcon(Gob gob, MapView mv) {
        super(gob);
        this.mv = mv;
    }

    @Override
    public void draw(GOut g, Pipe state) {
        Color col = colorFor(gob.id, mv.plgob);
        if (col == null) {
            return; // not a voice participant right now: draw nothing
        }
        Coord sc = Homo3D.obj2view(new Coord3f(0, 0, NAME_Z), state, Area.sized(g.sz())).round2();
        Tex name = nameTex(gob);
        Coord pos;
        if (name != null) {
            Coord nsz = name.sz();
            int nameLeft = sc.x - nsz.x / 2;               // name is centred on sc.x
            int x = nameLeft - GAP - ISZ.x;                // icon sits just before it
            int y = sc.y - nsz.y / 2 - ISZ.y / 2;          // vertically centred on the name
            pos = new Coord(x, y);
        } else {
            pos = new Coord(sc.x - ISZ.x / 2, sc.y - ISZ.y); // centred over the head
        }
        g.chcolor(col);
        g.image(GLYPH, pos, ISZ);
        g.chcolor();
    }

    /**
     * The rendered-name texture of the buddy name label attached to {@code gob}, or
     * {@code null} if the gob has no name (or it isn't drawable yet). Read reflectively
     * from the {@code Info} resource class; any hiccup (concurrent attr change, class
     * reload, missing field) yields {@code null} and the icon simply centres.
     */
    private static Tex nameTex(Gob gob) {
        try {
            for (GAttrib a : gob.attr.values()) {
                if (a.getClass().getName().equals(INFO_CLASS)) {
                    Field f = rendFld;
                    if (f == null || f.getDeclaringClass() != a.getClass()) {
                        f = a.getClass().getDeclaredField("rend");
                        f.setAccessible(true);
                        rendFld = f;
                    }
                    return (Tex) f.get(a);
                }
            }
        } catch (Exception ignored) {
            // concurrent modification, class reload, access, etc.: fall back to centring
        }
        return null;
    }

    /**
     * The colour for a gob's current voice state, or {@code null} if it should show
     * no icon (not a live voice participant). Reads only the public {@link Voice} API.
     */
    private static Color colorFor(long gob, long me) {
        boolean self = (gob == me);
        boolean participant = self
                ? Voice.isConnected()
                : (Voice.audible().contains(gob) || Voice.heardBy().contains(gob));
        if (!participant) {
            return null;
        }
        boolean muted = self ? Voice.isMicMuted() : Voice.isPlayerMuted(gob);
        boolean speaking = Voice.isSpeaking(gob);
        if (muted && speaking) {
            return YELLOW;
        }
        if (muted) {
            return RED;
        }
        if (speaking) {
            return GREEN;
        }
        return GREY;
    }

    /**
     * Ensure every visible player gob carries a {@code SpeakerIcon}. Cheap: a gob only
     * ever gets one attached, and {@link #draw} hides it when the player is not a voice
     * participant. Call once per frame from {@code MapView.tick} (game thread).
     */
    public static void sweep(MapView mv) {
        Gob me = mv.player();
        if (me == null || me.glob == null) {
            return;
        }
        OCache oc = me.glob.oc;
        List<Gob> players = new ArrayList<>();
        synchronized (oc) {
            for (Gob g : oc) {
                if (isPlayer(g)) {
                    players.add(g);
                }
            }
        }
        for (Gob g : players) {
            if (g.getattr(SpeakerIcon.class) == null) {
                g.setattr(new SpeakerIcon(g, mv));
            }
        }
    }

    /** A gob is a human player when its base sprite is the borka body. */
    private static boolean isPlayer(Gob g) {
        if (g.rc == null) {
            return false;
        }
        Drawable d = g.getattr(Drawable.class);
        if (d == null) {
            return false;
        }
        Resource res;
        try {
            res = d.getres();
        } catch (Loading stillStreaming) {
            return false;
        }
        return res != null && "gfx/borka/body".equals(res.name);
    }

    /** White speaker silhouette, built once; {@code chcolor} tints it per state. */
    private static Tex mkGlyph() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        // Speaker body + cone.
        Path2D p = new Path2D.Double();
        p.moveTo(14, 26);
        p.lineTo(26, 26);
        p.lineTo(38, 14);
        p.lineTo(38, 50);
        p.lineTo(26, 38);
        p.lineTo(14, 38);
        p.closePath();
        g.fill(p);
        // Two sound waves.
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(38, 20, 14, 24, -40, 80, Arc2D.OPEN));
        g.draw(new Arc2D.Double(38, 12, 24, 40, -40, 80, Arc2D.OPEN));
        g.dispose();
        return new TexI(img);
    }
}
