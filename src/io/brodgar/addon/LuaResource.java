package io.brodgar.addon;

import haven.Indir;
import haven.Loading;
import haven.Resource;

import java.util.ArrayList;
import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>Resource object</b> (151) — one of the client's resources, addressed by <b>name</b>
 * ({@code hafen.resource():get("gfx/hud/chr/agi")}). The name is the whole state of a handle, exactly as
 * it is for a {@link LuaSound}: any well-formed name is addressable, whether or not the client has ever
 * fetched it, so the collection mints one for every key and there is no {@code :exists()}.
 *
 * <p><b>Holding the handle fetches nothing; a content read does.</b> The {@link Indir} is taken from the
 * remote pool on the first {@code :loaded()}, {@code :version()}, {@code :error()}, {@code :info()} or
 * {@code :layers()} — {@code Resource.remote().load(name)} enqueues the fetch and hands back a reference
 * whose {@code get()} throws {@code Loading} until the resource is cached. That is caught here, never
 * raised into Lua: the reads answer {@code false}/{@code nil}/an empty collection until the resource
 * lands, and an addon that wants the moment polls {@code :loaded()} on a timer. A name the server has no
 * resource for ends the fetch with the client's own {@code LoadFailedException}, kept as {@code :error()}.
 *
 * <p>The remote pool is the local one plus everything the server publishes, so a name bundled in the
 * client's jar ({@code sfx/msg}) and a content resource resolve through the same door.
 *
 * <p><b>The writes</b> (151.2) hang off the same handle: {@code :layers():add(spec)} and {@code :remove(key)}
 * register a {@link ResourceWrites} record by name and re-parse a loaded copy at once, {@code :release()}
 * drops this addon's records. A write on a name nobody has fetched fetches nothing — it is a declaration,
 * applied when the resource loads — which is why {@code add} answers {@code nil} then.
 *
 * <p>Interned per addon by name ({@link Addon#resources}, weak values), so {@code get(name) == get(name)}
 * and a handle works as a table key; a handle Lua has dropped takes its entry with it.
 */
public final class LuaResource {
    private final Addon owner;
    /** The resource name this handle addresses. */
    public final String name;
    /** The pool's reference, taken on the first content read; {@code null} until then. */
    private volatile Indir<Resource> indir;
    /** The client's own message for a fetch that failed, or {@code null}. */
    private volatile String error;

    private LuaResource(Addon owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    public String toString() {
        return "Resource(" + name + ")";
    }

    /** The interned handle for {@code name} in {@code owner}'s env. */
    static LuaValue of(final Addon owner, final String name) {
        return owner.resources.of(name, () -> LuaValue.userdataOf(new LuaResource(owner, name), meta(owner)));
    }

    /** The {@code LuaResource} behind a Lua value, or {@code null} for anything that is not a Resource object. */
    static LuaResource resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaResource) ? (LuaResource)o : null;
    }

    // ---- the content read --------------------------------------------------------------------------

    /**
     * The loaded resource, or {@code null} while the client is still fetching it or after the fetch failed
     * — the one content read, and the only place the pool is asked. {@code Loading} is control flow and
     * answers {@code null}; a {@code BadResourceException} is the client's verdict on the name and is kept
     * as {@link #error}.
     */
    Resource res() {
        Indir<Resource> i = indir;
        if(i == null) {
            synchronized(this) {
                if(indir == null)
                    indir = Resource.remote().load(name);
                i = indir;
            }
        }
        try {
            Resource r = i.get();
            error = null;
            return r;
        } catch(Loading l) {
            return null;
        } catch(Resource.BadResourceException e) {
            String why = Refusal.reason(e);
            Throwable cause = e.getCause();
            error = (cause == null) ? why : (why + " — " + Refusal.reason(cause));
            return null;
        }
    }

    /** The resource's layers right now — empty until it is loaded. */
    List<Resource.Layer> layers() {
        List<Resource.Layer> out = new ArrayList<Resource.Layer>();
        Resource r = res();
        if(r != null) {
            for(Resource.Layer l : r.layers(Resource.Layer.class))
                out.add(l);
        }
        return out;
    }

    // ---- the metatable -----------------------------------------------------------------------------

    private static LuaValue meta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("resource", methods(owner),
            "a resource is one of the client's .res files, addressed by name",
            "a content read makes the client fetch it; holding the handle does not"));
        mt.set("__name", LuaValue.valueOf("Resource"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaResource h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Resource(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // name() — the resource name this handle addresses. Always answers: it IS the handle.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:name");
                return LuaValue.valueOf(handle(self, "name").name);
            }
        });
        // loaded() — has the client got it? False while fetching and after a failed fetch; a content read.
        m.set("loaded", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:loaded");
                return LuaValue.valueOf(handle(self, "loaded").res() != null);
            }
        });
        // version() — the server's version number, nil until loaded.
        m.set("version", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:version");
                Resource r = handle(self, "version").res();
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r.ver);
            }
        });
        // error() — the client's own message for a fetch that failed, nil while fetching or once loaded.
        m.set("error", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:error");
                LuaResource h = handle(self, "error");
                h.res();
                String why = h.error;
                return (why == null) ? LuaValue.NIL : LuaValue.valueOf(why);
            }
        });
        // info() — the snapshot: {name, version, layers = {keys...}}; nil until loaded.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:info");
                LuaResource h = handle(self, "info");
                Resource r = h.res();
                if(r == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                t.set("name", LuaValue.valueOf(r.name));
                t.set("version", LuaValue.valueOf(r.ver));
                LuaTable keys = new LuaTable();
                int n = 0;
                for(Resource.Layer l : r.layers(Resource.Layer.class))
                    keys.set(++n, LuaValue.valueOf(LayerCodec.key(l)));
                t.set("layers", keys);
                return t;
            }
        });
        // layers() — the collection of its layers, one Layer per wire layer; empty until loaded.
        m.set("layers", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:layers");
                return layerCollection(owner, handle(self, "layers"));
            }
        });
        // release() — give the client's own layers back: every write this addon made on the name is dropped
        // and a loaded copy re-read at once. The ending of a hold over what the client owns; hands back the
        // receiver.
        m.set("release", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "resource:release");
                ResourceWrites.release(owner, handle(self, "release"), "resource:release");
                return self;
            }
        });
        return m;
    }

    private static LuaResource handle(LuaValue self, String method) {
        LuaResource h = resolve(self);
        if(h == null)
            throw new LuaError("resource:" + method + "() — use a COLON call on a Resource object"
                + " (hafen.resource():get(name))");
        return h;
    }

    // ---- the layer collection ----------------------------------------------------------------------

    /**
     * {@code resource:layers()} — the resource's layers as a {@link LuaCollection}, keyed by
     * {@code "type"} (the first of that type) or {@code "type:id"} (the one with that id), which is also
     * what a string filter matches against. A key that names no layer answers {@code nil}: a layer either
     * is on the resource or is not.
     */
    private static LuaValue layerCollection(final Addon owner, final LuaResource h) {
        final String how = "resource:layers()";
        return LuaCollection.create(how, new LuaCollection.Source() {
            public List<LuaValue> members() {
                Resource r = h.res();
                List<LuaValue> out = new ArrayList<LuaValue>();
                if(r != null) {
                    for(Resource.Layer l : r.layers(Resource.Layer.class))
                        out.add(LuaLayer.of(owner, r, l));
                }
                return out;
            }

            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaLayer l = LuaLayer.resolve(member);
                return (l == null) ? null : LayerCodec.key(l.layer);
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                String k = Args.str(key, how + ":get", "key",
                                    "\"<type>\" for the first layer of a type, \"<type>:<id>\" for one by id")
                    .tojstring();
                Resource r = h.res();
                if(r == null)
                    return LuaValue.NIL;
                for(Resource.Layer l : r.layers(Resource.Layer.class)) {
                    if(LayerCodec.at(l, k))
                        return LuaLayer.of(owner, r, l);
                }
                return LuaValue.NIL;
            }

            // add(spec) — a write (151.2): replace every layer at the spec's address with one built from
            // the spec over the first of them, on every load of the name and, when the client holds it, at
            // once. Checked here, on a scratch resource, so a registered write cannot fail when applied.
            // Hands back the new Layer, or nil while the resource is not loaded — the write is registered
            // either way and is there when it loads.
            public boolean creatable() {
                return true;
            }

            public String addName() {
                return "spec";
            }

            public LuaValue addMember(Varargs a) {
                String verb = how + ":add";
                Args.only(a, 1, verb);
                LayerCodec.Spec spec = LayerCodec.spec(a.arg(2), verb);
                String address = ResourceWrites.add(owner, h, spec, verb);
                Resource r = h.res();
                if(r == null)
                    return LuaValue.NIL;
                Resource.Layer l = ResourceWrites.first(r.layers(Resource.Layer.class), address);
                return (l == null) ? LuaValue.NIL : LuaLayer.of(owner, r, l);
            }

            // remove(keyOrLayer) — drop every layer at the address, on every load and at once when held.
            public boolean destroyable() {
                return true;
            }

            public void removeMember(LuaValue x) {
                String verb = how + ":remove";
                LuaLayer l = LuaLayer.resolve(x);
                String key = (l != null) ? LayerCodec.key(l.layer)
                    : Args.str(x, verb, "key", "\"<type>\" for every layer of a type, \"<type>:<id>\" for"
                               + " the ones with that id, or a Layer object").tojstring();
                ResourceWrites.remove(owner, h, key, verb);
            }
        }, null);
    }
}
