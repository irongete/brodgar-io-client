package io.brodgar.addon;

import haven.FastMesh;
import haven.GAttrib;
import haven.Gob;
import haven.Indir;
import haven.Loading;
import haven.Material;
import haven.ModSprite;
import haven.Resource;
import haven.render.NodeWrap;
import haven.res.lib.vmat.VarWrap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * <b>The materials an addon dressed a game object's slots in</b> — {@code slot:material(name[, id])} (spec
 * {@code 152-gob-materials}, task 152.2). A {@link GAttrib} that is also a {@link ModSprite.Mod}, so the
 * sprite of the gob it sits on collects it with no registration beyond {@code Gob.setattr}
 * ({@code docs/client/gob-sprites.md}). {@link GobTint}'s footing otherwise: client-local, purely visual,
 * every live copy of the object, last write wins per slot, ends with the loaded object — and, per owner,
 * with {@code slot:release()} and {@code gob:materials():release()} ({@link #release}, {@link #revert}), and
 * with the addon itself at teardown.
 *
 * <p><b>An attribute of its own, never the server's.</b> It is keyed on this class, not on
 * {@code VarMats.class}: the server re-sends its {@code lib/vmat} attribute as a fresh {@code AttrMats} under
 * its own key, which replaces the server's dressing and touches nothing here — and the {@code updated()} that
 * delta ends with re-runs both mods. {@link #order()} is <b>101</b>, one past {@code VarMats}' 100, so
 * {@link #operate} sees the server's {@code VarWrap.Applier} on each part and can take it off the slots it
 * overrides: a wrap added <i>inside</i> the server's would win only the states it sets and let the rest
 * (a texture, a light) leak through from outside.
 *
 * <p><b>Immutable; every write mints a new instance.</b> {@code ModSprite.attrupdate} rebuilds the parts
 * only when its {@code Mod[]} differs <i>by identity</i>, so an entry map mutated in place would draw nothing
 * new. {@link #apply} copies the map, puts the entry, {@code setattr}s the copy and calls
 * {@code gob.updated()}. What the previous instance drew is carried over into the new one, because it is
 * still what is on screen until the new one's {@link #operate} runs.
 *
 * <p><b>What {@code operate} may throw.</b> {@code ModSprite.tick} catches {@code Loading} only and leaves
 * {@code lastupd} behind, so the next tick tries again: a resource the client is still fetching is left to
 * that retry — the server's wrap stays on until the fetch lands. Anything else escapes into the UI thread,
 * so a {@code BadResourceException} (a name the server has not got) and a loaded resource with no
 * {@code mat2} layer at {@code id} are caught here: that slot keeps the server's wrap, and {@link #drawn}
 * records the server's for it.
 */
public final class GobMaterials extends GAttrib implements ModSprite.Mod {
    /** One written slot: who, which name, which {@code mat2} layer ({@code null} for the resource's first), and the fetch. */
    static final class Entry {
        final Addon owner;
        final String name;
        /** The layer id the write named, or {@code null} for the resource's first {@code Material.Res}. */
        final Integer id;
        final Indir<Resource> res;

        Entry(Addon owner, String name, Integer id) {
            this.owner = owner;
            this.name = name;
            this.id = id;
            this.res = Resource.remote().load(name);
        }

        /**
         * The material this entry names, or {@code null} when the client's verdict on the name or the layer is
         * that there is none — a failed fetch, no {@code Material.Res} at {@code id}. Throws {@code Loading}
         * while the fetch (or a texture the material needs) is in flight.
         */
        Material.ResMaterial resolve() {
            try {
                Resource r = res.get();
                Material.Res layer = (id == null) ? r.layer(Material.Res.class) : r.layer(Material.Res.class, id);
                if(layer == null)
                    return null;
                Material m = layer.get();
                return (m instanceof Material.ResMaterial) ? (Material.ResMaterial)m : null;
            } catch(Resource.BadResourceException e) {
                return null;
            }
        }

        /**
         * The {@code mat2} layer's id: the one the write named, else the resource's first once the client holds
         * it, else {@code null}. {@code slot:info().id}'s answer for a written slot.
         */
        Integer layerId() {
            if(id != null)
                return id;
            try {
                Material.Res layer = res.get().layer(Material.Res.class);
                return (layer == null) ? null : Integer.valueOf(layer.id);
            } catch(Loading l) {
                return null;
            } catch(Resource.BadResourceException e) {
                return null;
            }
        }
    }

    /** What one {@link #operate} put on a slot: the resource and layer the part is drawn with. */
    static final class Drawn {
        final String name;
        final int id;

        Drawn(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    private static final Map<Integer, Entry> NONE = Collections.emptyMap();
    private static final Map<Integer, Drawn> NOTHING = Collections.emptyMap();

    /** The writes, by wire number. Unmodifiable: a write mints a new instance. */
    private final Map<Integer, Entry> entries;
    /**
     * What the last {@link #operate} that had the slot's part applied, by wire; a wire absent here is drawn in
     * the server's material — not written, still fetching, or failed. Carried over from the instance this one
     * replaced until this one's own {@code operate} runs, and merged per wire across the gob's sprites.
     */
    private volatile Map<Integer, Drawn> drawn;

    private GobMaterials(Gob gob, Map<Integer, Entry> entries, Map<Integer, Drawn> drawn) {
        super(gob);
        this.entries = Collections.unmodifiableMap(entries);
        this.drawn = drawn;
    }

    /** One past {@code VarMats}' 100: the server's wraps are on the parts when this runs. */
    public int order() {
        return 101;
    }

    /**
     * Runs once per {@code ModSprite} on the gob per rebuild — the model's own and every render-linked
     * sub-sprite, which resolves the same gob through its owner context — each seeing only its own parts. So
     * the record is <b>merged per wire</b>, never replaced: a sprite with no part tagged for a written slot
     * says nothing about that slot, and one that has the part says what it put there.
     */
    public void operate(ModSprite.Cons cons) {
        Map<Integer, Drawn> out = new HashMap<Integer, Drawn>(drawn);
        for(ModSprite.Part part : cons.parts) {
            if(!(part.obj instanceof FastMesh.ResourceMesh))
                continue;
            String sid = ((FastMesh.ResourceMesh)part.obj).info.rdat.get("vm");
            int mid = (sid == null) ? -1 : Integer.parseInt(sid);
            if(mid < 0)
                continue;
            Entry e = entries.get(mid);
            if(e == null) {                            // nobody's, or released: the server's wrap is what is drawn
                out.remove(mid);
                continue;
            }
            Material.ResMaterial m = e.resolve();     // Loading propagates: the sprite retries next tick
            if(m == null) {                            // the client's verdict: the server's wrap stays
                out.remove(mid);
                continue;
            }
            for(Iterator<NodeWrap> it = part.wraps.iterator(); it.hasNext();) {
                NodeWrap w = it.next();
                if((w instanceof VarWrap.Applier) && (((VarWrap.Applier)w).mid == mid))
                    it.remove();
            }
            part.wraps.addFirst(new VarWrap.Applier(m, mid));
            out.put(mid, new Drawn(m.res.name, m.id));
        }
        drawn = out;
    }

    // ---- the per-gob store ------------------------------------------------------------------------

    /** The attrib on {@code g}, or {@code null} — nothing is created and no slot is written. */
    static GobMaterials on(Gob g) {
        return (g == null) ? null : g.getattr(GobMaterials.class);
    }

    /** The write in force on slot {@code wire} of {@code g}, or {@code null} for a slot nobody wrote. */
    static Entry entry(Gob g, int wire) {
        GobMaterials gm = on(g);
        return (gm == null) ? null : gm.entries.get(wire);
    }

    /** What the last {@code operate} on {@code g} put on slot {@code wire}, or {@code null} for the server's. */
    static Drawn drawn(Gob g, int wire) {
        GobMaterials gm = on(g);
        return (gm == null) ? null : gm.drawn.get(wire);
    }

    /**
     * Dress slot {@code wire} of {@code g} in {@code e}. A new instance every time — {@code setattr} replaces
     * and {@code updated()} bumps the sequence the sprite compares — and the drawn record carried over, since
     * the old wraps stay on screen until the rebuild. Nothing here consults the server's attribute: a copy
     * whose {@code lib/vmat} has not arrived takes the write blind, and the rebuild its arrival triggers
     * applies it ({@link GobIntent#applyTo}'s case).
     *
     * <p>Called on the UI thread — a Lua verb, or the gob drain — under the gob's own monitor for the
     * read-modify-write; {@code setattr} is reentrant into it.
     */
    static void apply(Gob g, int wire, Entry e) {
        if(g == null)
            return;
        synchronized(g) {
            GobMaterials prev = on(g);
            Map<Integer, Entry> next = new HashMap<Integer, Entry>((prev == null) ? NONE : prev.entries);
            next.put(wire, e);
            g.setattr(new GobMaterials(g, next, (prev == null) ? NOTHING : prev.drawn));
            g.updated();
        }
    }

    // ---- the endings ------------------------------------------------------------------------------

    /**
     * Hand slot {@code wire} of {@code g} back to the server's material, if {@code a} is who dressed it
     * ({@code slot:release()}, 152.3), and answer whether anything changed. A slot nobody wrote, or one
     * another addon wrote, is left alone: the write is per owner, so the ending is too.
     */
    static boolean release(Gob g, Addon a, int wire) {
        if(g == null)
            return false;
        synchronized(g) {
            GobMaterials prev = on(g);
            Entry e = (prev == null) ? null : prev.entries.get(wire);
            if((e == null) || (e.owner != a))
                return false;
            Map<Integer, Entry> next = new HashMap<Integer, Entry>(prev.entries);
            next.remove(wire);
            replace(g, prev, next);
            return true;
        }
    }

    /**
     * Drop every slot {@code a} dressed on {@code g} ({@code gob:materials():release()}, and the teardown
     * sweep's per-gob half, {@link UiApi#teardownGobScales}, in the same loop as the size and the colour),
     * and answer whether anything was dropped. Other addons' slots stay dressed.
     */
    static boolean revert(Gob g, Addon a) {
        if(g == null)
            return false;
        synchronized(g) {
            GobMaterials prev = on(g);
            if(prev == null)
                return false;
            Map<Integer, Entry> next = new HashMap<Integer, Entry>(prev.entries);
            for(Iterator<Entry> it = next.values().iterator(); it.hasNext();) {
                if(it.next().owner == a)
                    it.remove();
            }
            if(next.size() == prev.entries.size())
                return false;
            replace(g, prev, next);
            return true;
        }
    }

    /**
     * The ending's half of {@link #apply}: a new instance with the surviving entries, so the sprite rebuilds,
     * and the drawn record carried over, because the released wraps stay on screen until it does — the rebuild's
     * {@link #operate} takes a released wire out of the record as it finds the server's wrap back on its part,
     * which is what keeps {@code slot:drawn()} true to the frame. An instance left with no entries stays: it
     * is still the record of what is drawn until then, and after that one no-op {@code operate} per rebuild.
     */
    private static void replace(Gob g, GobMaterials prev, Map<Integer, Entry> next) {
        g.setattr(new GobMaterials(g, next, prev.drawn));
        g.updated();
    }
}
