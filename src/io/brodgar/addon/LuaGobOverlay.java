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
import org.luaj.vm2.LuaTable;
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
 * nothing is searched and nothing is swept: {@code gob:overlay():add(key)} attaches the attrib to <i>that</i> gob
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
     * One thing an addon attached under one key. Since 039.3 the record is <b>built bare and configured by the
     * setters on the Overlay object</b> {@code gob:overlay():add(key)} hands back — the spec table is gone, and
     * with it the one-shot parse: a record's own state IS the configuration, so {@code ov:text("…")} relabels a
     * live overlay instead of replacing it.
     *
     * <p><b>A bare record draws nothing.</b> It has no {@link #kind} until one of the five kind setters names
     * one, which is what makes "a half-configured overlay never paints" a property of the shape rather than a
     * rule to remember: there is nothing to paint until the overlay says what it is. A second, different kind is
     * refused — an overlay says ONE thing, and picking a winner is how one of them silently stops meaning
     * anything.
     *
     * <p><b>Two spaces</b> (038.2, unchanged). {@code :draw(fn)} and {@code :text(s)} paint in <b>screen
     * space</b> at the gob's projected point, from this attrib's own draw pass. {@code :image(a)},
     * {@code :model(a)} and {@code :ghost(res)} stand in the <b>3D world</b> anchored to the gob: the record
     * holds a client-only {@link LuaWorldEntity} carrying a {@link FollowMoving}, which is not painted here at
     * all — the render tree draws it as it draws any other world entity, and this record is what OWNS it (a
     * replace, a remove and the gob's own death each end it).
     *
     * <p><b>Mutable, and read from the draw pass</b>, so every configured field is {@code volatile}: the writes
     * come from a Lua verb on the UI thread and the reads from {@link #paintRecords} one frame later, and a
     * field is a whole value in either.
     */
    static final class Attach {
        final Addon owner;
        final String key;
        /** The gob this record is attached to — what a rebuilt world entity anchors itself to again. */
        final long gobId;

        /** {@code "draw"}, {@code "text"}, {@code "image"}, {@code "model"} or {@code "ghost"}, or null while bare. */
        volatile String kind;
        /** {@code :draw(fn)} — {@code fn(g, gob, sx, sy)}, or null for every other kind. */
        volatile LuaValue draw;
        /** {@code :text(s)} — the label, or null for every other kind. */
        volatile String text;
        /** {@code :image(a)} / {@code :model(a)} — the asset handle the visual is milled from. */
        volatile LuaValue visual;
        /** {@code :ghost(res)} — the {@code .res} name of a game model. */
        volatile String res;
        /** {@code :spawnData(sdt)} — spawn-data bytes picking a resource variant (a ghost only). */
        volatile LuaValue spawnData;
        /** {@code :billboard(b)} — a camera-facing blit rather than an upright quad (an image only). */
        volatile boolean billboard;
        /** {@code :color(…)} for a text record; null = the stock white. */
        volatile Color color;
        /** {@code :offset(…)} — screen pixels on a screen kind, world units ({@code z} up) on a world one. */
        volatile double offX, offY, offZ;
        /** The look, kept here as well as on the entity so a rebuilt visual comes back looking the same. */
        volatile double scale = 1.0, alpha = 1.0, rotate = 0.0;
        volatile Color tint;
        /** The client-only entity a WORLD-space record owns, or null while it is bare or screen-space. */
        volatile LuaWorldEntity ent;

        Attach(Addon owner, String key, long gobId) {
            this.owner = owner;
            this.key = key;
            this.gobId = gobId;
        }

        /** What this record paints, or null while it is bare (the {@code kind} of {@code overlay:info()}). */
        String kind() {
            return kind;
        }

        /** Is this one painted in the 3D world (rather than at the gob's projected screen point)? */
        boolean world() {
            return worldKind(kind);
        }

        /** Is it configured enough to paint at all? A bare record is not, and that is its whole guarantee. */
        boolean drawn() {
            return kind != null;
        }

        /** The screen-space offset from the projected anchor point (the screen kinds). */
        Coord screenOffset() {
            return Coord.of((int)Math.round(offX), (int)Math.round(offY));
        }

        /** The world-space offset from the gob ({@code z} up) — what a {@link FollowMoving} adds every frame. */
        Coord3f worldOffset() {
            return new Coord3f((float)offX, (float)offY, (float)offZ);
        }

        /** End this record: a world-space one destroys the entity it owns; a screen-space one has nothing to free. */
        void dispose() {
            LuaWorldEntity e = ent;
            ent = null;
            if(e != null)
                VrApi.destroyOverlayEntity(e);
        }

        /**
         * Build (or REBUILD) the world entity from the record's current configuration, and hand the look back to
         * it. The new one is built <b>before</b> the old one is destroyed, so a bad asset handle or a missing map
         * view raises with the overlay left exactly as it was — the 038 property that a failed attach changes
         * nothing, kept now that the configuration arrives one setter at a time.
         *
         * <p>Only the two <b>construction</b> properties force a rebuild after the fact ({@code :billboard},
         * {@code :spawnData}): they pick which visual is milled, and nothing can change that in place. Everything
         * else — scale, alpha, tint, facing, offset — is set on the live entity and merely remembered here.
         */
        void materialise() {
            if(!world())
                return;
            LuaTable spec = new LuaTable();
            if(kind.equals("ghost")) {
                spec.set("ghost", LuaValue.valueOf(res));
                if(spawnData != null)
                    spec.set("sdt", spawnData);
            } else {
                spec.set(kind, visual);
                if(billboard)
                    spec.set("billboard", LuaValue.TRUE);
            }
            LuaWorldEntity built = VrApi.overlayEntity(owner, gobId, spec, kind, worldOffset());
            LuaWorldEntity old = ent;
            ent = built;
            if(old != null)
                VrApi.destroyOverlayEntity(old);
            if(scale != 1.0)
                VrApi.overlayScale(built, scale);
            if(alpha != 1.0)
                VrApi.overlayAlpha(built, alpha);
            if(tint != null)
                VrApi.overlayTint(built, tint);
            if(rotate != 0.0)
                VrApi.overlayRotate(built, rotate);
        }
    }

    /** The five kinds, in the order an error lists them: the two screen-space ones, then the three world ones. */
    static final String[] KINDS = { "draw", "text", "image", "model", "ghost" };

    /** Does {@code kind} stand in the 3D world (rather than at the gob's projected screen point)? */
    static boolean worldKind(String kind) {
        return "image".equals(kind) || "model".equals(kind) || "ghost".equals(kind);
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
            throw new LuaError("gob:overlay():add(key): that gob is not renderable yet -- attach it from"
                + " GobAdded or a timer, and read gob:exists() first");
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
     * the record it displaced (or null), which the caller must {@link Attach#dispose} : a replaced world-space
     * record still owns a live entity in the scene, and dropping it from the map is not what ends it.
     */
    synchronized Attach put(Attach a) {
        Map<String, Attach> m = byAddon.get(a.owner);
        if(m == null)
            byAddon.put(a.owner, m = new LinkedHashMap<String, Attach>());
        return m.put(a.key, a);
    }

    /** Drop one record and answer it (or null) — the caller disposes it outside this monitor. */
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
     * <p>For a screen-space overlay this is bookkeeping: the attrib goes when the {@link Gob} does. For a
     * <b>world-space</b> one it is the whole of plan §2b, and it is the one thing "death with the gob" actually
     * costs — the entity is its own client-only gob in the scene, and nothing disposes it just because the target
     * left {@code OCache}. Today's {@code follow=} is exactly that bug: {@link FollowMoving#getc} holds at the
     * last position, so a sprite following a felled tree floats there forever with no owner. The store being ON
     * the gob is what makes the fix O(1): the removal hands us the records, and nothing is ever searched for.
     */
    static void gobGone(Gob g) {
        LuaGobOverlay ol = on(g);
        if(ol == null)
            return;
        for(Attach a : ol.removeAll()) {
            try {
                a.dispose();
            } catch(RuntimeException e) {
                /* best-effort: one bad record never stops the rest from being freed */
            }
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
     * A snapshot of the SCREEN-space records, addon by addon — what the draw pass paints. A world-space record is
     * not in it: its entity is drawn by the render tree like any other world thing, so a gob carrying only world
     * overlays costs this attrib's draw one empty list and no projection at all. Nor is a <b>bare</b> one, which
     * has not yet said what it draws — that is how a half-configured overlay never paints.
     */
    private synchronized List<Attach> paintRecords() {
        List<Attach> out = new ArrayList<Attach>();
        for(Map<String, Attach> m : byAddon.values()) {
            for(Attach a : m.values()) {
                if(a.drawn() && !a.world())
                    out.add(a);
            }
        }
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
        List<Attach> recs = paintRecords();
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
