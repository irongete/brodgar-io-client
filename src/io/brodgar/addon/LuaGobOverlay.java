package io.brodgar.addon;

import haven.Area;
import haven.Coord;
import haven.Coord3f;
import haven.GAttrib;
import haven.GOut;
import haven.Gob;
import haven.Loading;
import haven.PView;
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
 * <b>What an addon draws at one game object</b> — a thing it attached to that gob, painted at that object's
 * projected point (spec {@code 038-gob-overlays}, task 038.1; the world kinds left in 043.3, so this is no
 * longer "the screen-space half" of anything — it is the whole of it). It is a {@link GAttrib} that is
 * <i>also</i> a {@link RenderTree.Node} and {@link PView.Render2D}, exactly like the voice
 * {@code haven.SpeakerIcon}: the render tree keeps it pinned above the gob in every camera and disposes it with
 * the gob, and its 2D pass runs once per frame from {@code PView.draw} &rarr; {@code list2d.draw} (on the UI
 * thread, in the same {@code UI.draw} traversal as every other widget — so a Lua draw callback here never races
 * the tick).
 *
 * <p><b>It is now the STORE, not a stateless painter.</b> Before 038.1 an addon registered a <i>filter</i>
 * ({@code hafen.ui.gobOverlay}) and a throttled 5&nbsp;Hz sweep attached one of these to every gob that matched,
 * after which each draw re-evaluated every addon's filter for that gob. The verb names the gob instead, so
 * nothing is searched and nothing is swept: {@code gob:overlay():add(key)} attaches the attrib to <i>that</i> gob
 * at once and puts the record in the per-addon map held here, and the draw pass paints the records it is already
 * standing in. One shared attrib per gob still serves every addon ({@code getattr} keys by the exact class, so a
 * per-addon subclass cannot be attached) — hence the map of maps, partitioned by {@link Addon} so two addons'
 * {@code "tag"} on one gob never collide.
 *
 * <p><b>Death with the gob is therefore free.</b> {@code Gob.dispose()} disposes every {@link GAttrib} and a gob
 * dropped from {@code OCache.objs} takes its attribs with it, so there is no addon-side map keyed by gob id to
 * sweep and nothing is kept "in case it comes back" (a felled tree never does). The one caller that must still
 * find an addon's overlays across gobs is teardown ({@code :reload}/disable), which is a single sweep of every
 * live session's object cache at a rare moment — {@link UiApi#teardownGobOverlays}.
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
     * One thing an addon attached under one key. Since 039.3 the record is <b>built bare and configured by the
     * setters on the Overlay object</b> {@code gob:overlay():add(key)} hands back — the spec table is gone, and
     * with it the one-shot parse: a record's own state IS the configuration, so {@code ov:text("…")} relabels a
     * live overlay instead of replacing it.
     *
     * <p><b>A bare record draws nothing.</b> It has no {@link #kind} until one of the two kind setters names
     * one, which is what makes "a half-configured overlay never paints" a property of the shape rather than a
     * rule to remember: there is nothing to paint until the overlay says what it is. A second, different kind is
     * refused — an overlay says ONE thing, and picking a winner is how one of them silently stops meaning
     * anything.
     *
     * <p><b>ONE space, since 043.3.</b> A record paints in <b>screen space</b> at the gob's projected point,
     * from this attrib's own draw pass — {@code :draw(fn)} or {@code :text(s)}, and nothing else. The three
     * world kinds ({@code :image}/{@code :model}/{@code :ghost}) are gone: what they built was never an engine
     * overlay but a client gob of its own standing in the scene, so it is created, listed and ended in
     * {@code hafen.vr()}, which is where such a thing lives. A record therefore owns no entity, which is why
     * there is no {@code dispose} here any more and why {@code :offset} means exactly one thing (pixels).
     *
     * <p><b>Mutable, and read from the draw pass</b>, so every configured field is {@code volatile}: the writes
     * come from a Lua verb on the UI thread and the reads from {@link #paintRecords} one frame later, and a
     * field is a whole value in either.
     */
    static final class Attach {
        final Addon owner;
        final String key;

        /** {@code "draw"} or {@code "text"}, or null while the record is still bare. */
        volatile String kind;
        /** {@code :draw(fn)} — {@code fn(g, gob, sx, sy)}, or null for a text record. */
        volatile LuaValue draw;
        /** {@code :text(s)} — the label, or null for a draw record. */
        volatile String text;
        /** {@code :color(…)} for a text record; null = the stock white. */
        volatile Color color;
        /**
         * {@code :offset(x, y)} — screen pixels from the gob's projected point, and only ever that. <b>Design</b>
         * pixels since 058.2, like every other length this API takes: it is written beside the {@code sx, sy} a
         * draw callback is handed, and a pair the addon writes in one unit and reads in another is not a pair.
         * The one conversion is at the label blit ({@code UiApi.paintGobOverlays}).
         */
        volatile double offX, offY;

        Attach(Addon owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        /** What this record paints, or null while it is bare (the {@code kind} of {@code overlay:info()}). */
        String kind() {
            return kind;
        }

        /** Is it configured enough to paint at all? A bare record is not, and that is its whole guarantee. */
        boolean drawn() {
            return kind != null;
        }

        /** The screen-space offset from the projected anchor point. */
        Coord screenOffset() {
            return Coord.of((int)Math.round(offX), (int)Math.round(offY));
        }
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The overlay attrib on {@code g}, or {@code null} — nothing is attached and nothing is created. */
    static LuaGobOverlay on(Gob g) {
        return (g == null) ? null : g.getattr(LuaGobOverlay.class);
    }

    /**
     * The overlay attrib on {@code g}, attaching a fresh one if this is the first overlay on that gob.
     *
     * <p>{@code setattr} throws {@code Loading} only while the gob already holds slots one of which cannot
     * take the attrib yet, so the refusal is about the gob's OWN drawing still resolving and a timer is the
     * retry. 114.2: it names no other escape, because a gob whose {@code GobAdded} is still owed holds no
     * slots at all -- 114.1's gate keeps it out of every tree until the event has fired, so an attach made
     * in that handler has nothing to fail against.
     */
    static LuaGobOverlay ensure(Gob g) {
        LuaGobOverlay ol = g.getattr(LuaGobOverlay.class);
        if(ol != null)
            return ol;
        ol = new LuaGobOverlay(g);
        try {
            g.setattr(ol);
        } catch(Loading l) {
            throw new LuaError("gob:overlay():add(key): that gob's own drawing is still resolving, so"
                + " nothing attaches to it this instant -- retry from a timer");
        }
        return ol;
    }

    /** This addon's record under {@code key}, or {@code null}. */
    synchronized Attach get(Addon owner, String key) {
        Map<String, Attach> m = byAddon.get(owner);
        return (m == null) ? null : m.get(key);
    }

    /**
     * Attach or REPLACE — the same key twice leaves one overlay, which is what makes the verb idempotent. Answers
     * the record it displaced (or null), so the caller can report the removal beside the add.
     */
    synchronized Attach put(Attach a) {
        Map<String, Attach> m = byAddon.get(a.owner);
        if(m == null)
            byAddon.put(a.owner, m = new LinkedHashMap<String, Attach>());
        return m.put(a.key, a);
    }

    /** Drop one record and answer it (or null) — the caller reports it outside this monitor. */
    synchronized Attach remove(Addon owner, String key) {
        Map<String, Attach> m = byAddon.get(owner);
        if(m == null)
            return null;
        Attach had = m.remove(key);
        if(m.isEmpty())
            byAddon.remove(owner);
        return had;
    }

    /** Drop every record this addon attached to this gob (teardown) and answer them, for the caller to dispose. */
    synchronized List<Attach> removeOwner(Addon owner) {
        Map<String, Attach> m = byAddon.remove(owner);
        return (m == null) ? new ArrayList<Attach>() : new ArrayList<Attach>(m.values());
    }

    /** Drop EVERY record here, whoever owns it, and answer them all — the gob is going (see {@link #gobGone}). */
    private synchronized List<Attach> removeAll() {
        List<Attach> out = new ArrayList<Attach>();
        for(Map<String, Attach> m : byAddon.values())
            out.addAll(m.values());
        byAddon.clear();
        return out;
    }

    /**
     * <b>An overlay dies with its gob</b> — called from the tick where the client's own {@code OCache} removal is
     * dispatched ({@code GobRemoved}), before the event reaches Lua, so a handler already reads the truth.
     *
     * <p>Since 043.3 that is bookkeeping and a report, nothing more: the attrib goes when the {@link Gob} does,
     * and a record no longer owns a client-only entity in the scene. The thing that DOES have to be ended with
     * the gob — an entity {@code hafen.vr():sprite():add(img, gob)} anchored there — is ended by
     * {@code VrApi.anchorGone}, off the same drain, through its own by-target index (D-185). Two mechanisms, one
     * moment, and neither of them a sweep.
     */
    static void gobGone(Gob g) {
        LuaGobOverlay ol = on(g);
        if(ol == null)
            return;
        // 080.1: one copy of the object left, and a record stands on every copy -- so this copy's records go
        // with it, and the REPORT waits for the last one. An overlay another character can still see has not
        // been removed, and saying it was is the removal an addon's own set would never get back. Same shape
        // and same moment as VrApi.anchorGone's "another character still has that object in view".
        boolean last = AddonManager.gobUsers(g.id).isEmpty();
        for(Attach a : ol.removeAll()) {
            if(!last)
                continue;
            // 038.3: and it is REPORTED. Fired straight, not queued: this already runs on the UI thread from the
            // tick's GobRemoved drain, and firing here is what puts GobOverlayRemoved BEFORE the gob's own
            // GobRemoved — an overlay is never reported dying after the thing it was attached to. The record is
            // already out of the map, so a handler that reads gob:overlay(key) back sees the truth (nil).
            try {
                AddonManager.fireGobOverlay("GobOverlayRemoved", g.id, a.key, false, a.owner);
            } catch(RuntimeException e) {
                /* an addon's handler is isolated by fireTo; this guards only the dispatch itself */
            }
        }
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

    /**
     * A snapshot of the records that paint, addon by addon — what the draw pass paints. A <b>bare</b> one is not
     * in it, having not yet said what it draws; that is how a half-configured overlay never paints.
     */
    private synchronized List<Attach> paintRecords() {
        List<Attach> out = new ArrayList<Attach>();
        for(Map<String, Attach> m : byAddon.values()) {
            for(Attach a : m.values()) {
                if(a.drawn())
                    out.add(a);
            }
        }
        return out;
    }

    // ---- the write, addressed at the OBJECT (080.1) ------------------------------------------------
    //
    // A record is attached to the object, and a Gob is per object cache -- so one record goes into EVERY live
    // session's copy, and the SAME Attach instance goes into each. It holds no Gob and nothing else about a
    // session, so one instance in several attribs is one record with several painters: ov:text("...") on the
    // handle relabels what every character sees, because there is one thing to relabel.
    //   A session that does not hold the object is not walked at all, and one whose copy cannot take the
    // attrib yet is skipped -- the refusal that reaches the caller is the resolved copy's, where the call was
    // aimed. The EVENT is fired once, off that same copy: one attach is one attach however many draw it.

    /**
     * Attach {@code rec} to every live session's copy of the object, and answer what it displaced on
     * {@code primary} — the copy the call resolved through, whose {@code ensure} refusal is the call's own.
     */
    static Attach attach(Gob primary, long id, Attach rec) {
        Attach old = ensure(primary).put(rec);
        for(Gob g : AddonManager.gobCopies(id)) {
            if(g == primary)
                continue;
            try {
                ensure(g).put(rec);
            } catch(RuntimeException e) {
                /* that copy's own drawing is still resolving: it simply does not draw this one */
            }
        }
        return old;
    }

    /**
     * Drop {@code owner}'s record under {@code key} from every live session's copy, pruning each attrib left
     * with nothing, and answer what {@code primary} held — so the removal is reported once, or not at all.
     */
    static Attach detach(Gob primary, long id, Addon owner, String key) {
        Attach had = drop(primary, owner, key);
        for(Gob g : AddonManager.gobCopies(id)) {
            if(g != primary)
                drop(g, owner, key);
        }
        return had;
    }

    /** One copy's half of {@link #detach}: remove the record if that copy carries one, then prune. */
    private static Attach drop(Gob g, Addon owner, String key) {
        LuaGobOverlay store = on(g);
        if(store == null)
            return null;
        Attach had = store.remove(owner, key);
        prune(g);
        return had;
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
        List<Attach> recs = paintRecords();
        if(recs.isEmpty())
            return;                                        // nothing attached (an idle attrib awaiting its prune)
        Coord sc;
        try {
            Coord3f v = Eye.view(new Coord3f(0f, 0f, ANCHOR_Z), state, Area.sized(g.sz()));
            if(v == null)
                return;   // behind the eye, or not projectable this frame -- see Eye
            sc = v.round2();
        } catch(RuntimeException e) {
            return;       // never throw into the render pass (mirrors the Loading-guarded reads)
        }
        UiApi.paintGobOverlays(gob, recs, g, gwrap, sc);
    }
}
