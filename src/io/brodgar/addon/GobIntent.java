package io.brodgar.addon;

import haven.Gob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <b>What an addon has asked to be drawn at one game object</b> (092.7, A-087) &mdash; the intent behind
 * {@code gob:scale(k)} and {@code gob:overlay():add(key)}, held per <b>gob id</b> so a session that loads the
 * object <i>afterwards</i> draws it the same.
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
 * So this class holds no callback, registers nothing, and sweeps nothing: it is a map, and the walk that
 * already runs consults it.
 *
 * <p><b>It ends with the OBJECT, not with a copy.</b> {@code GobScale}'s contract is that a size ends with the
 * loaded object &mdash; walk far enough away and back and the boulder is its own size again &mdash; and this
 * keeps it exactly: the record is dropped when the object leaves its <b>last</b> session, the same moment
 * {@code GobRemoved} fires and an overlay is reported gone. While any character can still see the object, the
 * object is still loaded, and one object drawn two sizes in two windows is the defect rather than the rule.
 *
 * <p>UI-thread only, like the two writes that fill it and the drain that reads it (P5). {@code HashMap}, and
 * keyed by {@code Long}: a gob id is a server id, not an object identity.
 */
final class GobIntent {
    private GobIntent() {}

    /** What has been asked for at one object. Both halves may be empty; the record goes when both are. */
    private static final class Record {
        /** The addon that last wrote the size, and what it wrote &mdash; {@code null} for a gob nobody scaled. */
        Addon scaleOwner;
        float scale;
        /** The overlay records standing on this object, in attach order &mdash; the very objects the copies share. */
        final List<LuaGobOverlay.Attach> overlays = new ArrayList<LuaGobOverlay.Attach>();

        boolean empty() {
            return (scaleOwner == null) && overlays.isEmpty();
        }
    }

    /** Gob id &rarr; what is asked for there. Only ids something is actually asked for at are in it. */
    private static final Map<Long, Record> intents = new HashMap<Long, Record>();

    private static Record record(long id, boolean create) {
        Long key = Long.valueOf(id);
        Record r = intents.get(key);
        if((r == null) && create)
            intents.put(key, r = new Record());
        return r;
    }

    // ---- what the two writes record ---------------------------------------------------------------

    /**
     * {@code gob:scale(k)} was written. <b>One size, last write wins</b>, which is {@link GobScale}'s own rule
     * rather than a second one &mdash; and writing exactly {@code 1} is putting the object back, so it forgets
     * rather than recording "no change": the original size is the absence of this state on the attrib, and it
     * is the absence of it here too.
     */
    static synchronized void scale(long id, Addon owner, float k) {
        if(k == GobScale.NONE) {
            Record r = record(id, false);
            if(r != null) {
                r.scaleOwner = null;
                prune(id, r);
            }
            return;
        }
        Record r = record(id, true);
        r.scaleOwner = owner;
        r.scale = k;
    }

    /**
     * {@code gob:overlay():add(key)} attached {@code rec}. The record is the one the copies share, so nothing
     * is copied here and a later {@code ov:text("…")} relabels what an arriving session will be handed too.
     * A key attached twice replaces, exactly as the attrib's own {@code put} does.
     */
    static synchronized void overlay(long id, LuaGobOverlay.Attach rec) {
        Record r = record(id, true);
        drop(r, rec.owner, rec.key);
        r.overlays.add(rec);
    }

    /** {@code gob:overlay():remove(key)}. Inert for a key that was never attached, like the removal itself. */
    static synchronized void dropOverlay(long id, Addon owner, String key) {
        Record r = record(id, false);
        if(r == null)
            return;
        drop(r, owner, key);
        prune(id, r);
    }

    private static void drop(Record r, Addon owner, String key) {
        for(Iterator<LuaGobOverlay.Attach> it = r.overlays.iterator(); it.hasNext(); ) {
            LuaGobOverlay.Attach a = it.next();
            if((a.owner == owner) && a.key.equals(key))
                it.remove();
        }
    }

    // ---- what forgets it --------------------------------------------------------------------------

    /**
     * <b>An addon went away</b> ({@code :reload} or disable) &mdash; the third half of the teardown sweep that
     * already reverts the scales and detaches the overlays. Without it a session loading an object afterwards
     * would re-apply what an addon that is no longer running once asked for, which is the one thing teardown
     * exists to make impossible.
     */
    static synchronized void dropOwner(Addon a) {
        for(Iterator<Map.Entry<Long, Record>> it = intents.entrySet().iterator(); it.hasNext(); ) {
            Record r = it.next().getValue();
            if(r.scaleOwner == a)
                r.scaleOwner = null;
            for(Iterator<LuaGobOverlay.Attach> oi = r.overlays.iterator(); oi.hasNext(); ) {
                if(oi.next().owner == a)
                    oi.remove();
            }
            if(r.empty())
                it.remove();
        }
    }

    /**
     * <b>The object left its last session</b> ({@code AddonManager.gobLeft}, the moment {@code GobRemoved}
     * fires). What was asked for at it goes with it: a felled tree never comes back, and an object that
     * unloads and streams in again is a new object as far as this API has ever been concerned.
     */
    static synchronized void forget(long id) {
        intents.remove(Long.valueOf(id));
    }

    private static void prune(long id, Record r) {
        if(r.empty())
            intents.remove(Long.valueOf(id));
    }

    // ---- and what re-applies it -------------------------------------------------------------------

    /**
     * <b>A session's copy of an object just arrived</b>: draw it the way this object is already being drawn.
     * Called from the gob drain for every {@code added} entry, before the client-wide edge is settled, so a
     * {@code GobAdded} handler that reads {@code gob:scale()} back already sees the truth.
     *
     * <p>Free for the client that asks for nothing: one {@code HashMap} miss per object arriving. Never
     * throws into the drain &mdash; a copy whose own drawing is still resolving simply does not carry the
     * overlay, which is the same answer {@code LuaGobOverlay.attach} gives for such a copy at write time.
     */
    static synchronized void applyTo(Gob g) {
        if(g == null)
            return;
        Record r = intents.get(Long.valueOf(g.id));
        if(r == null)
            return;
        if(r.scaleOwner != null) {
            try {
                GobScale.apply(g, r.scaleOwner, r.scale);
            } catch(RuntimeException e) {
                /* a copy that cannot take it draws its own size: a state, not a fault */
            }
        }
        for(int i = 0, n = r.overlays.size(); i < n; i++) {
            try {
                LuaGobOverlay.ensure(g).put(r.overlays.get(i));
            } catch(RuntimeException e) {
                /* its own drawing is still resolving (Loading): it simply does not draw this one */
            }
        }
    }
}
