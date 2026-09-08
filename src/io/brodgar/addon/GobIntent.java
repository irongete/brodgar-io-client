package io.brodgar.addon;

import haven.Gob;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <b>What an addon has asked to be drawn at one game object</b> (092.7, A-087) &mdash; the intent behind
 * {@code gob:scale(k)}, {@code gob:visible(b)}, {@code gob:tint(c)} and {@code gob:overlay():add(key)}, held per <b>gob id</b> so a
 * session that loads the object <i>afterwards</i> draws it the same.
 *
 * <p><b>Why it has to exist.</b> A gob id is the server's and names one object; a {@link Gob} is per
 * {@link haven.OCache}, so one object is as many Gobs as there are characters that can see it. 080.1 made a
 * visual write land on every copy &mdash; but on every copy <i>at the moment of the write</i>. A character
 * that walked up afterwards got a Gob with none of the attribs on it and drew the object bare, and nothing
 * raised: an addon that scaled every boulder had the ones two characters could see at the time, and the third
 * character found an unscaled boulder. There was no per-session edge to re-apply on, because {@code GobAdded}
 * fires once for the client &mdash; into the FIRST session to see the object &mdash; which is right for the
 * event and useless for this.
 *
 * <p><b>The edge was already there, one level down.</b> {@code AddonManager.drainGobEvents} walks every
 * session's own {@code OCache} queue, and each entry names <i>that</i> session's copy arriving. It settles
 * them into one client-wide event and threw the per-session halves away; now they re-apply on the way past.
 * So this class holds no callback, registers nothing, and sweeps nothing: it is a walk over the addons, and
 * the walk that already runs consults it.
 *
 * <p><b>The record is the WRITER'S</b> (audit2 B01), not the client's. It was one map for the whole client,
 * each entry naming the addon that had written it, and the only thing that ever forgot an addon's wishes was
 * one call late inside {@link UiApi#teardownGobScales} &mdash; so a throw anywhere above it left a torn-down
 * addon's size, colour, hiding and overlays re-applied to every copy that arrived afterwards, for the life of
 * the client. Held on the {@link Addon} ({@link Addon#gobIntents}) there is nothing to sweep: the wishes go
 * with the addon, and {@link #applyTo} reads the live addons and can reach no other.
 *
 * <p><b>The KEY is still the gob id</b>, and it is not a session's: a gob id is the server's naming of one
 * object that two characters observe, which is the whole premise above.
 *
 * <p><b>One size, one colour, one answer to "is it drawn"</b>, last write wins &mdash; the rule the attribs
 * themselves keep, and it is kept here by the write: taking the size at an object takes it <i>away</i> from
 * whatever other addon had asked for one there, exactly as writing the attrib does. So at most one addon
 * holds each of the three at a given id, and the order {@link #applyTo} walks the addons in cannot decide
 * anything. Overlays compose across addons, as they do on the object.
 *
 * <p><b>It ends with the OBJECT, not with a copy.</b> {@code GobScale}'s contract is that a size ends with the
 * loaded object &mdash; walk far enough away and back and the boulder is its own size again &mdash; and this
 * keeps it exactly: the record is dropped when the object leaves its <b>last</b> session, the same moment
 * {@code GobRemoved} fires and an overlay is reported gone. While any character can still see the object, the
 * object is still loaded, and one object drawn two sizes in two windows is the defect rather than the rule.
 *
 * <p>Every entry point is {@code synchronized} on this class, which is the one monitor over every addon's
 * map alike, and that is the whole of what the writes from Lua and the drain need. The maps are
 * plain {@link java.util.HashMap}s keyed by {@code Long}, a gob id being a server id rather than an object
 * identity.
 */
final class GobIntent {
    private GobIntent() {}

    /** What ONE ADDON has asked for at one object. Any part of it may be empty; the record goes when all are. */
    static final class Record {
        /** Is a size asked for here, and which &mdash; {@code scaled} false for an object this addon left alone.
         *  Writing exactly {@code 1} is putting the object back, so it forgets rather than recording "no
         *  change": the original size is the absence of this state on the attrib, and the absence of it here. */
        boolean scaled;
        float scale;
        /** Is the object held out of the scene by this addon? A gob nobody hid is drawn, so there is no second
         *  field: this being set IS "hidden", and showing it again forgets rather than recording "drawn". */
        boolean hidden;
        /** The colour this addon laid over the object, or {@code null} (135.1): no tint is the absence of the
         *  state on the attrib, and the absence of it here too. */
        Color tint;
        /** The overlay records this addon stood on the object, in attach order &mdash; the very objects the
         *  copies share. */
        final List<LuaGobOverlay.Attach> overlays = new ArrayList<LuaGobOverlay.Attach>();

        boolean empty() {
            return !scaled && !hidden && (tint == null) && overlays.isEmpty();
        }
    }

    /**
     * Every addon that may be holding an intent &mdash; the loaded ones and the {@code :lua} REPL owner, which
     * writes gob verbs like any other owner and is in no registry. Read fresh at each walk: a list built once
     * is a list that disagrees with the registry after the next {@code :reload}.
     */
    private static List<Addon> owners() {
        List<Addon> out = new ArrayList<Addon>(AddonManager.addons);
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            out.add(c);
        return out;
    }

    private static Record record(Addon owner, long id, boolean create) {
        if(owner == null)
            return null;
        Long key = Long.valueOf(id);
        Record r = owner.gobIntents.get(key);
        if((r == null) && create)
            owner.gobIntents.put(key, r = new Record());
        return r;
    }

    // ---- what the writes record -------------------------------------------------------------------

    /**
     * {@code gob:scale(k)} was written. <b>One size, last write wins</b>, which is {@link GobScale}'s own rule
     * rather than a second one &mdash; so this takes the size at {@code id} away from every other addon that
     * had asked for one there, exactly as writing the attrib does. Writing exactly {@code 1} is putting the
     * object back, so it forgets rather than recording "no change".
     */
    static synchronized void scale(long id, Addon owner, float k) {
        for(Addon a : owners()) {
            Record r = record(a, id, false);
            if(r != null) {
                r.scaled = false;
                prune(a, id, r);
            }
        }
        if(k == GobScale.NONE)
            return;
        Record r = record(owner, id, true);
        if(r != null) {
            r.scaled = true;
            r.scale = k;
        }
    }

    /**
     * {@code gob:visible(b)} was written. <b>One object, drawn or not, last write wins</b> &mdash; the same
     * rule the size above keeps, and for the same reason: an object has one answer, so the write takes the
     * answer over rather than layering two addons' opinions. Writing {@code true} is putting the object back,
     * so it forgets: being drawn is the absence of this state on the copy, and the absence of it here too.
     */
    static synchronized void visible(long id, Addon owner, boolean vis) {
        for(Addon a : owners()) {
            Record r = record(a, id, false);
            if(r != null) {
                r.hidden = false;
                prune(a, id, r);
            }
        }
        if(vis)
            return;
        Record r = record(owner, id, true);
        if(r != null)
            r.hidden = true;
    }

    /**
     * {@code gob:tint(c)} was written (135.1). <b>One colour, last write wins</b>, {@link GobTint}'s own rule
     * &mdash; and {@code null} is {@code gob:tint(nil)}, putting the object back, so it forgets rather than
     * recording "no colour".
     */
    static synchronized void tint(long id, Addon owner, Color c) {
        for(Addon a : owners()) {
            Record r = record(a, id, false);
            if(r != null) {
                r.tint = null;
                prune(a, id, r);
            }
        }
        if(c == null)
            return;
        Record r = record(owner, id, true);
        if(r != null)
            r.tint = c;
    }

    /**
     * <b>Every object {@code a} is holding hidden</b>, for the teardown sweep that has to put them back in
     * every session that holds a copy. Read before {@link #dropOwner} forgets what this addon wrote, because
     * after it there is nothing left to say which objects those were.
     */
    static synchronized List<Long> hiddenBy(Addon a) {
        List<Long> out = new ArrayList<Long>();
        if(a == null)
            return out;
        for(Map.Entry<Long, Record> e : a.gobIntents.entrySet()) {
            if(e.getValue().hidden)
                out.add(e.getKey());
        }
        return out;
    }

    /**
     * {@code gob:overlay():add(key)} attached {@code rec}. The record is the one the copies share, so nothing
     * is copied here and a later {@code ov:text("…")} relabels what an arriving session will be handed too.
     * A key attached twice replaces, exactly as the attrib's own {@code put} does.
     */
    static synchronized void overlay(long id, LuaGobOverlay.Attach rec) {
        Record r = record(rec.owner, id, true);
        if(r == null)
            return;
        drop(r, rec.key);
        r.overlays.add(rec);
    }

    /** {@code gob:overlay():remove(key)}. Inert for a key that was never attached, like the removal itself. */
    static synchronized void dropOverlay(long id, Addon owner, String key) {
        Record r = record(owner, id, false);
        if(r == null)
            return;
        drop(r, key);
        prune(owner, id, r);
    }

    /** Take {@code key} out of one addon's record &mdash; the owner is the map the record is in. */
    private static void drop(Record r, String key) {
        for(Iterator<LuaGobOverlay.Attach> it = r.overlays.iterator(); it.hasNext(); ) {
            if(it.next().key.equals(key))
                it.remove();
        }
    }

    // ---- what forgets it --------------------------------------------------------------------------

    /**
     * <b>An addon went away</b> ({@code :reload} or disable) &mdash; the third half of the teardown sweep that
     * already reverts the scales and detaches the overlays. It is one drop rather than a walk now, and the
     * teardown could skip it entirely without leaving anything behind: {@link #applyTo} reads the live addons,
     * so an addon that has left the registry is one no arriving copy can be drawn for.
     */
    static synchronized void dropOwner(Addon a) {
        if(a != null)
            a.gobIntents.clear();
    }

    /**
     * <b>The object left its last session</b> ({@code AddonManager.gobLeft}, the moment {@code GobRemoved}
     * fires). What was asked for at it goes with it: a felled tree never comes back, and an object that
     * unloads and streams in again is a new object as far as this API has ever been concerned.
     */
    static synchronized List<LuaGobOverlay.Attach> forget(long id) {
        List<LuaGobOverlay.Attach> out = new ArrayList<LuaGobOverlay.Attach>();
        Long key = Long.valueOf(id);
        for(Addon a : owners()) {
            Record r = a.gobIntents.remove(key);
            // What was attached there, handed back for the rescan to REPORT: on that path no copy of the
            // object is left for LuaGobOverlay.gobGone to fire GobOverlayRemoved from, and these records are
            // the only other place that knows which keys stood on it.
            if(r != null)
                out.addAll(r.overlays);
        }
        return out;
    }

    private static void prune(Addon owner, long id, Record r) {
        if(r.empty())
            owner.gobIntents.remove(Long.valueOf(id));
    }

    // ---- and what re-applies it -------------------------------------------------------------------

    /**
     * <b>A session's copy of an object just arrived</b>: draw it the way this object is already being drawn.
     * Called from the gob drain for every {@code added} entry, before the client-wide edge is settled, so a
     * {@code GobAdded} handler that reads {@code gob:scale()} back already sees the truth.
     *
     * <p>Free for the client that asks for nothing: one {@code HashMap} miss per live addon per object
     * arriving, over maps that stay empty unless something was written into them. Never throws into the drain
     * &mdash; a copy whose own drawing is still resolving simply does not carry the overlay, which is the same
     * answer {@code LuaGobOverlay.attach} gives for such a copy at write time.
     */
    static synchronized void applyTo(Gob g) {
        if(g == null)
            return;
        Long key = Long.valueOf(g.id);
        Addon scaleOwner = null, tintOwner = null;
        float scale = GobScale.NONE;
        Color tint = null;
        boolean hidden = false;
        List<LuaGobOverlay.Attach> overlays = null;
        for(Addon a : owners()) {
            Record r = a.gobIntents.get(key);
            if(r == null)
                continue;
            if(r.scaled) {
                scaleOwner = a;
                scale = r.scale;
            }
            if(r.tint != null) {
                tintOwner = a;
                tint = r.tint;
            }
            hidden |= r.hidden;
            if(!r.overlays.isEmpty()) {
                if(overlays == null)
                    overlays = new ArrayList<LuaGobOverlay.Attach>();
                overlays.addAll(r.overlays);
            }
        }
        if(scaleOwner != null) {
            try {
                GobScale.apply(g, scaleOwner, scale);
            } catch(RuntimeException e) {
                /* a copy that cannot take it draws its own size: a state, not a fault */
            }
        }
        if(tintOwner != null) {
            try {
                GobTint.apply(g, tintOwner, tint);
            } catch(RuntimeException e) {
                /* a copy that cannot take it draws plain: a state, not a fault */
            }
        }
        /* Before the copy is let into a render tree at all (the drain releases the 114.1 hold below this
         * walk), so an object arriving hidden is never drawn once and then taken away. */
        if(hidden)
            g.addonvisible(false);
        if(overlays == null)
            return;
        for(int i = 0, n = overlays.size(); i < n; i++) {
            try {
                LuaGobOverlay.ensure(g).put(overlays.get(i));
            } catch(RuntimeException e) {
                /* its own drawing is still resolving (Loading): it simply does not draw this one */
            }
        }
    }
}
