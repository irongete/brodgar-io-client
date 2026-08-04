package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Coord3f;
import haven.GAttrib;
import haven.GOut;
import haven.Gob;
import haven.Loading;
import haven.PView;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>screen-space half of {@code gob:overlay}</b> — a thing an addon attached to one game object, painted at
 * that object's projected point (spec {@code 038-gob-overlays}, task 038.1). It is a {@link GAttrib} that is
 * <i>also</i> a {@link RenderTree.Node} and {@link PView.Render2D}, exactly like the voice
 * {@code haven.SpeakerIcon}: the render tree keeps it pinned above the gob in every camera and disposes it with
 * the gob, and its 2D pass runs once per frame from {@code PView.draw} &rarr; {@code list2d.draw} (on the UI
 * thread, in the same {@code UI.draw} traversal as every other widget — so a Lua draw callback here never races
 * the tick).
 *
 * <p><b>It is now the STORE, not a stateless painter.</b> Before 038.1 an addon registered a <i>filter</i>
 * ({@code hafen.ui.gobOverlay}) and a throttled 5&nbsp;Hz sweep attached one of these to every gob that matched,
 * after which each draw re-evaluated every addon's filter for that gob. The verb names the gob instead, so
 * nothing is searched and nothing is swept: {@code gob:overlay(key, spec)} attaches the attrib to <i>that</i> gob
 * at once and puts the record in the per-addon map held here, and the draw pass paints the records it is already
 * standing in. One shared attrib per gob still serves every addon ({@code getattr} keys by the exact class, so a
 * per-addon subclass cannot be attached) — hence the map of maps, partitioned by {@link Addon} so two addons'
 * {@code "tag"} on one gob never collide.
 *
 * <p><b>Death with the gob is therefore free.</b> {@code Gob.dispose()} disposes every {@link GAttrib} and a gob
 * dropped from {@code OCache.objs} takes its attribs with it, so there is no addon-side map keyed by gob id to
 * sweep and nothing is kept "in case it comes back" (a felled tree never does). The one caller that must still
 * find an addon's overlays across gobs is teardown ({@code :reload}/disable), which is a single sweep of the
 * object cache at a rare moment — {@link UiApi#teardownGobOverlays}.
 *
 * <p><b>The native overlays are read here too</b> ({@link #nativeKeys}/{@link #findNative}): the game's own
 * {@code Gob.ols} are keyed by their <b>resource name</b>, which is the only part of a {@code Gob.Overlay} a name
 * can address — the server gives an {@code id} only sometimes, and it is a number nothing in the API speaks.
 * Several of the game's overlays may share one resource; they collapse to one key (D-093's shape) and the
 * count they collapse by is published ({@link #countNative}).
 *
 * <p>Lives in {@code io.brodgar.addon} because {@link GAttrib}'s constructor and its {@code gob} field are
 * public and nothing here needs {@code haven}-package access. Never throws into the render pass.
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

    /**
     * The records, partitioned per addon and insertion-ordered within an addon (so {@code gob:overlay()} answers
     * in the order the addon attached them). Guarded by this attrib's own monitor: the writes come from the UI
     * thread (a Lua verb) and the reads from the draw pass, but teardown may sweep from a session-bind thread.
     */
    private final Map<Addon, Map<String, Attach>> byAddon = new LinkedHashMap<Addon, Map<String, Attach>>();

    LuaGobOverlay(Gob gob) {
        super(gob);
    }

    // ---- one attached record ----------------------------------------------------------------------

    /**
     * One thing an addon attached under one key. A spec is read ONCE, at attach — like a stylesheet rule, and
     * unlike the old filter, which was re-evaluated per gob per frame. 038.1 carries the two <b>screen-space</b>
     * kinds; the world-space ones ({@code image}/{@code model}/{@code ghost}) are 038.2 and do not live here at
     * all (they are their own client-only gob anchored to the target).
     */
    static final class Attach {
        final Addon owner;
        final String key;
        /** {@code {draw = fn}} — {@code fn(g, gob, sx, sy)}, or null when this is a {@code text} record. */
        final LuaValue draw;
        /** {@code {text = "…"}} — the label, or null when this is a {@code draw} record. */
        final String text;
        /** {@code {color = {r,g,b[,a]}}} for a text record; null = the stock white. */
        final Color color;
        /** {@code {offset = {x=,y=}}} in screen pixels from the projected anchor point; never null. */
        final Coord off;

        private Attach(Addon owner, String key, LuaValue draw, String text, Color color, Coord off) {
            this.owner = owner;
            this.key = key;
            this.draw = draw;
            this.text = text;
            this.color = color;
            this.off = off;
        }

        /** {@code "draw"} or {@code "text"} — what this record paints (the {@code kind} of {@code overlay:info()}). */
        String kind() {
            return (draw != null) ? "draw" : "text";
        }

        /**
         * Parse one spec table. <b>A spec that names neither is an error naming the field</b> — an overlay that
         * draws nothing is never what was meant, and a silent no-op is the one failure nothing else would report.
         */
        static Attach of(Addon owner, String key, LuaValue spec) {
            String where = "gob:overlay(key, spec)";
            if(!spec.istable())
                throw new LuaError(where + ": spec must be a table, got " + spec.typename()
                    + " -- gob:overlay(key, nil) is the REMOVE arity");
            LuaValue dv = spec.get("draw"), tv = spec.get("text");
            if(!dv.isnil() && !tv.isnil())
                throw new LuaError(where + ": a spec says ONE thing -- 'draw' and 'text' are two,"
                    + " so pass a draw callback that draws the text itself");
            LuaValue draw = null;
            String text = null;
            if(!dv.isnil()) {
                if(!dv.isfunction())
                    throw new LuaError(where + ": 'draw' must be a function draw(g, gob, sx, sy), got " + dv.typename());
                draw = dv;
            } else if(!tv.isnil()) {
                text = tv.tojstring();
            } else {
                throw new LuaError(where + ": the spec must name WHAT to draw -- 'draw' (a callback"
                    + " draw(g, gob, sx, sy)) or 'text' (a label at the gob's point)");
            }
            Color col = null;
            LuaValue cv = spec.get("color");
            if(!cv.isnil()) {
                if(!cv.istable())
                    throw new LuaError(where + ": 'color' must be {r, g, b[, a]}, got " + cv.typename());
                col = AddonManager.luaColor(cv, null);
            }
            Coord off = Coord.z;
            LuaValue ov = spec.get("offset");
            if(!ov.isnil()) {
                if(!ov.istable())
                    throw new LuaError(where + ": 'offset' must be {x = , y = } (screen pixels), got " + ov.typename());
                off = Coord.of(ov.get("x").toint(), ov.get("y").toint());
            }
            return new Attach(owner, key, draw, text, col, off);
        }
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The overlay attrib on {@code g}, or {@code null} — nothing is attached and nothing is created. */
    static LuaGobOverlay on(Gob g) {
        return (g == null) ? null : g.getattr(LuaGobOverlay.class);
    }

    /** The overlay attrib on {@code g}, attaching a fresh one if this is the first overlay on that gob. */
    static LuaGobOverlay ensure(Gob g) {
        LuaGobOverlay ol = g.getattr(LuaGobOverlay.class);
        if(ol != null)
            return ol;
        ol = new LuaGobOverlay(g);
        try {
            g.setattr(ol);
        } catch(Loading l) {
            throw new LuaError("gob:overlay(key, spec): that gob is not renderable yet -- attach it from"
                + " GobAdded or a timer, and read gob:exists() first");
        }
        return ol;
    }

    /** This addon's record under {@code key}, or {@code null}. */
    synchronized Attach get(Addon owner, String key) {
        Map<String, Attach> m = byAddon.get(owner);
        return (m == null) ? null : m.get(key);
    }

    /** Attach or REPLACE — the same key twice leaves one overlay, which is what makes the verb idempotent. */
    synchronized void put(Attach a) {
        Map<String, Attach> m = byAddon.get(a.owner);
        if(m == null)
            byAddon.put(a.owner, m = new LinkedHashMap<String, Attach>());
        m.put(a.key, a);
    }

    /** Drop one record; true if there was one. */
    synchronized boolean remove(Addon owner, String key) {
        Map<String, Attach> m = byAddon.get(owner);
        if(m == null)
            return false;
        boolean had = (m.remove(key) != null);
        if(m.isEmpty())
            byAddon.remove(owner);
        return had;
    }

    /** Drop every record this addon attached to this gob (teardown); true if there was one. */
    synchronized boolean removeOwner(Addon owner) {
        return byAddon.remove(owner) != null;
    }

    /** This addon's keys on this gob, in attach order. */
    synchronized List<String> keys(Addon owner) {
        Map<String, Attach> m = byAddon.get(owner);
        return (m == null) ? new ArrayList<String>() : new ArrayList<String>(m.keySet());
    }

    /** No addon has anything attached here — the attrib can go. */
    synchronized boolean idle() {
        return byAddon.isEmpty();
    }

    /** A snapshot of every record, addon by addon — what the draw pass paints. */
    private synchronized List<Attach> records() {
        List<Attach> out = new ArrayList<Attach>();
        for(Map<String, Attach> m : byAddon.values())
            out.addAll(m.values());
        return out;
    }

    /** Detach the attrib from {@code g} once nothing is attached (the caller already holds the UI monitor). */
    static void prune(Gob g) {
        LuaGobOverlay ol = on(g);
        if((ol != null) && ol.idle()) {
            try {
                g.delattr(LuaGobOverlay.class);
            } catch(RuntimeException e) {
                /* best-effort: an idle attrib draws nothing anyway */
            }
        }
    }

    // ---- the game's own overlays (read-only) ------------------------------------------------------

    /**
     * The key one of the game's own overlays answers to: its <b>resource name</b>, or {@code null} while the
     * sprite is still resolving (an overlay with no resolved resource has no name to be addressed by, so it is
     * simply not there yet — the same {@code nil}-means-loading rule the rest of the API uses).
     */
    static String nativeKey(Gob.Overlay ol) {
        try {
            return ((ol.spr != null) && (ol.spr.res != null)) ? ol.spr.res.name : null;
        } catch(RuntimeException e) {
            return null;    // still resolving
        }
    }

    /** The distinct resource names of the game's own overlays on {@code g}, in {@code Gob.ols} order. */
    static List<String> nativeKeys(Gob g) {
        List<String> out = new ArrayList<String>();
        try {
            for(Gob.Overlay ol : g.ols) {
                String k = nativeKey(ol);
                if((k != null) && !out.contains(k))
                    out.add(k);
            }
        } catch(RuntimeException e) {
            /* concurrent overlay mutation — answer what we have */
        }
        return out;
    }

    /** Does the game itself have an overlay of resource {@code key} on {@code g} right now? */
    static boolean findNative(Gob g, String key) {
        return countNative(g, key) > 0;
    }

    /**
     * How many of the game's overlays on {@code g} carry resource {@code key} — the multiplicity a native
     * Overlay object is the <b>union</b> of, and the one thing keying by name would otherwise lose.
     *
     * <p>038.1 measured it rather than assuming it: on a live world <b>13 of 33</b> gobs carrying overlays
     * had two of one resource (worst four). So the collapse is not a corner case, and the answer is not to
     * key on {@code Gob.Overlay.id} instead — that id is {@code -1} whenever the server gave none (the same
     * collisions, now unnameable), it is a number where every other key in this API is a name, and it does
     * not survive a re-add. A native overlay is read-only, so a union loses nothing an addon can act on;
     * what it loses is this count, and this count is published ({@code overlay:count()}).
     */
    static int countNative(Gob g, String key) {
        int n = 0;
        try {
            for(Gob.Overlay ol : g.ols) {
                if(key.equals(nativeKey(ol)))
                    n++;
            }
        } catch(RuntimeException e) {
            /* concurrent overlay mutation -- answer what we counted */
        }
        return n;
    }

    // ---- the draw pass ----------------------------------------------------------------------------

    public void draw(GOut g, Pipe state) {
        List<Attach> recs = records();
        if(recs.isEmpty())
            return;                                        // nothing attached (an idle attrib awaiting its prune)
        Coord sc;
        try {
            Coord3f v = Homo3D.obj2view(new Coord3f(0f, 0f, ANCHOR_Z), state, Area.sized(g.sz()));
            if(v == null)
                return;   // not projectable this frame
            sc = v.round2();
        } catch(RuntimeException e) {
            return;       // never throw into the render pass (mirrors the Loading-guarded reads)
        }
        UiApi.paintGobOverlays(gob, recs, g, gwrap, sc);
    }
}
