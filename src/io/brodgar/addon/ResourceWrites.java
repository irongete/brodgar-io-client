package io.brodgar.addon;

import haven.Audio;
import haven.MessageBuf;
import haven.Resource;
import haven.Tex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.luaj.vm2.LuaError;

/**
 * <b>The layer writes</b> (151.2) — every {@code resource:layers():add(spec)} and {@code :remove(key)} an
 * addon has made, as a static registry keyed by resource name, and the one function that applies them.
 *
 * <p><b>One seam, one direction.</b> A write is a {@link Record}: who made it, when (a global sequence), what
 * kind, at which address, with which fields. {@link #apply} folds the records of a name over a freshly
 * parsed layer list in sequence order, and it is called from one {@code // addon:} line in
 * {@code Resource.load}, between the parse loop and the {@code init()} loop — so it covers both pools,
 * every source and every version, runs on the loader thread with no Lua in reach, and never throws: a
 * record that fails to apply is skipped and logged under its owner. A write on a resource the client
 * already holds is applied by <b>re-parsing that same object from its own sources</b>
 * ({@code Pool.reload}), so the live path and the load path are one code path and the object the client
 * holds keeps its identity — only its layer list is swapped.
 *
 * <p><b>Validation happens at write time</b>, in {@link #add}: the spec is encoded over the layer that
 * stands at its address right now (a field left out keeps the original's; with no original the spec must
 * be whole), and the bytes are constructed through the wire factory on a scratch {@code Resource.Virtual},
 * so what registers is what the client's own constructor accepted. At apply time the same encoding runs
 * over whatever the fresh parse produced at the address, which can only fill more fields in, never fewer.
 *
 * <p><b>A record's payload is plain Java</b> — a {@code String}, a {@code Double}, a {@code byte[]} — never
 * a {@code LuaValue} or an asset handle: it is read on loader threads for as long as the addon runs.
 *
 * <p><b>Release</b> ({@link #release(Addon, String)}, {@link #teardown}) removes the owner's records and
 * re-parses every touched name still cached; every applied live write invalidates what caches a layer of
 * the name outside the resource: {@code Audio.resclips} and each addon's {@code g:resource} texture copy.
 */
final class ResourceWrites {
    private ResourceWrites() {
    }

    enum Kind {
        SPEC, REMOVE
    }

    /** One write: immutable, plain Java. */
    static final class Record {
        final Addon owner;
        final long seq;
        final Kind kind;
        /** The wire type of a SPEC ({@code "tooltip"}); {@code null} for a REMOVE. */
        final String type;
        /** The address the record touches: {@code "type"} or {@code "type:id"}. */
        final String address;
        /** A SPEC's fields as plain values ({@code text}, {@code clip}, {@code volume}, {@code id}); empty for a REMOVE. */
        final Map<String, Object> fields;

        Record(Addon owner, Kind kind, String type, String address, Map<String, Object> fields) {
            this.owner = owner;
            this.seq = SEQ.incrementAndGet();
            this.kind = kind;
            this.type = type;
            this.address = address;
            this.fields = Collections.unmodifiableMap(fields);
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();
    /** Resource name → its records in sequence order; the lists are immutable and replaced whole. */
    private static final ConcurrentHashMap<String, List<Record>> RECORDS = new ConcurrentHashMap<String, List<Record>>();

    // ---- the registry ------------------------------------------------------------------------------

    private static synchronized void register(String name, Record r) {
        List<Record> cur = RECORDS.get(name);
        List<Record> next = (cur == null) ? new ArrayList<Record>() : new ArrayList<Record>(cur);
        next.add(r);
        RECORDS.put(name, Collections.unmodifiableList(next));
    }

    /** Drop every record {@code owner} made on {@code name}; true when there was one. */
    private static synchronized boolean unregister(Addon owner, String name) {
        List<Record> cur = RECORDS.get(name);
        if(cur == null)
            return false;
        List<Record> next = new ArrayList<Record>();
        for(Record r : cur) {
            if(r.owner != owner)
                next.add(r);
        }
        if(next.size() == cur.size())
            return false;
        if(next.isEmpty())
            RECORDS.remove(name);
        else
            RECORDS.put(name, Collections.unmodifiableList(next));
        return true;
    }

    /** The records of {@code name} in sequence order, or an empty list. */
    static List<Record> records(String name) {
        List<Record> cur = RECORDS.get(name);
        return (cur == null) ? Collections.<Record>emptyList() : cur;
    }

    // ---- the Lua writes ----------------------------------------------------------------------------

    /**
     * {@code resource:layers():add(spec)}: validate {@code spec} over what stands at its address now, register
     * it, and re-parse the resource if the client holds it. Hands back the address the record touches.
     */
    static String add(Addon owner, LuaResource h, LayerCodec.Spec spec, String verb) {
        Resource res = h.res();
        Resource.Layer first = (res == null) ? null : first(res.layers(Resource.Layer.class), spec.address);
        byte[] wire = LayerCodec.encode(spec.type, spec.fields, first, verb);
        // The wire factory's own verdict, on a scratch resource nothing holds: what registers is what the
        // client's constructor accepted.
        Resource.Virtual scratch = new Resource.Virtual(Resource.remote(), h.name, (res == null) ? -1 : res.ver);
        try {
            Resource.newLayer(scratch, spec.type, new MessageBuf(wire));
        } catch(RuntimeException e) {
            throw new LuaError(verb + ": the client refused the " + spec.type + " layer — " + Refusal.reason(e));
        }
        register(h.name, new Record(owner, Kind.SPEC, spec.type, spec.address, spec.fields));
        live(h, verb);
        return spec.address;
    }

    /** {@code resource:layers():remove(key)}: register the removal and re-parse the resource if held. */
    static void remove(Addon owner, LuaResource h, String key, String verb) {
        String type = LayerCodec.typeOf(key);
        if(type.equals("code") || type.equals("codeentry"))
            throw new LuaError(verb + ": \"" + key + "\" is the client's published code, which an addon"
                + " neither writes nor removes — an addon ships no Java");
        if(!Resource.knownLayer(type))
            throw new LuaError(verb + ": \"" + type + "\" is not a layer type the client parses — the key is"
                + " \"<type>\" or \"<type>:<id>\" (\"tooltip\", \"image:-1\", \"audio2:cl\")");
        register(h.name, new Record(owner, Kind.REMOVE, null, key, Collections.<String, Object>emptyMap()));
        live(h, verb);
    }

    /** {@code resource:release()}: drop the owner's records on the name and re-parse it if held. */
    static void release(Addon owner, LuaResource h, String verb) {
        if(unregister(owner, h.name))
            live(h, verb);
    }

    /** The teardown step: every name this owner wrote to, released; a re-parse that fails is logged, not thrown. */
    static void teardown(Addon owner) {
        for(String name : new ArrayList<String>(RECORDS.keySet())) {
            if(unregister(owner, name)) {
                try {
                    reparse(name);
                } catch(RuntimeException e) {
                    AddonManager.log(owner, "resource " + name + " keeps its written layers: " + Refusal.reason(e));
                }
            }
        }
    }

    /**
     * Apply the current records to a resource the client holds, by re-parsing it in place; a resource nobody
     * has fetched waits for its load. A re-parse that fails (a cache file bumped to a newer version since the
     * object loaded) raises into Lua; the record stays registered and applies on the next load.
     */
    private static void live(LuaResource h, String verb) {
        try {
            reparse(h.name);
        } catch(RuntimeException e) {
            throw new LuaError(verb + ": the write is registered, but " + h.name + " could not be re-read"
                + " from its source so the loaded copy still shows the old layers — " + Refusal.reason(e));
        }
    }

    private static void reparse(String name) {
        Resource res = Resource.remote().peek(name);
        if(res == null)
            return;
        res.pool.reload(res);
        invalidate(res);
    }

    /** What caches a layer of {@code res} outside the resource: the combined clip, each addon's texture copy. */
    private static void invalidate(Resource res) {
        Audio.forget(res);
        for(Addon a : AddonManager.profOwners()) {
            Tex t = a.resTexCache.remove(res.name);
            if(t != null) {
                try {
                    t.dispose();
                } catch(RuntimeException e) {
                    /* a texture already released is still one we are done with */
                }
            }
        }
    }

    // ---- the seam ----------------------------------------------------------------------------------

    /**
     * Fold the records of {@code res.name} over {@code parsed}, in sequence order. Called from
     * {@code Resource.load} on whatever thread parses, before {@code init()}; never throws. A SPEC replaces
     * every layer at its address with one built over the first of them (or is appended when none is there);
     * a REMOVE drops every layer at its address.
     */
    static List<Resource.Layer> apply(Resource res, List<Resource.Layer> parsed) {
        List<Record> recs = records(res.name);
        if(recs.isEmpty())
            return parsed;
        List<Resource.Layer> out = new ArrayList<Resource.Layer>(parsed);
        for(Record r : recs) {
            try {
                if(r.kind == Kind.REMOVE) {
                    List<Resource.Layer> kept = new ArrayList<Resource.Layer>(out.size());
                    for(Resource.Layer l : out) {
                        if(!LayerCodec.at(l, r.address))
                            kept.add(l);
                    }
                    out = kept;
                } else {
                    Resource.Layer first = first(out, r.address);
                    byte[] wire = LayerCodec.encode(r.type, r.fields, first, null);
                    Resource.Layer built = Resource.newLayer(res, r.type, new MessageBuf(wire));
                    Set<Resource.Layer> gone = Collections.newSetFromMap(new IdentityHashMap<Resource.Layer, Boolean>());
                    for(Resource.Layer l : out) {
                        if(LayerCodec.at(l, r.address))
                            gone.add(l);
                    }
                    List<Resource.Layer> next = new ArrayList<Resource.Layer>(out.size() + 1);
                    boolean placed = false;
                    for(Resource.Layer l : out) {
                        if(gone.contains(l)) {
                            if(!placed) {
                                next.add(built);
                                placed = true;
                            }
                        } else {
                            next.add(l);
                        }
                    }
                    if(!placed)
                        next.add(built);
                    out = next;
                }
            } catch(RuntimeException e) {
                AddonManager.log(r.owner, "layer write on " + res.name + " at \"" + r.address + "\" skipped: "
                                 + Refusal.reason(e));
            }
        }
        return out;
    }

    /** The first layer of {@code layers} at {@code address}, or {@code null}. */
    static Resource.Layer first(Iterable<Resource.Layer> layers, String address) {
        for(Resource.Layer l : layers) {
            if(LayerCodec.at(l, address))
                return l;
        }
        return null;
    }
}
