package io.brodgar.addon;

import haven.Resource;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>Layer object</b> (151) — one wire layer of one resource, as {@code resource:layers():get(key)} hands
 * it out. <b>Bound to the layer object, never to its address</b>: several layers share one address (the
 * clip variants of an {@code audio2}, the frames of a layered animation), so an address names a set and a
 * handle names a member. {@code :exists()} is identity membership in the resource's current layer list —
 * the list is swapped by reference on every load, so a handle taken before a write answers {@code false}
 * after it and the new layer is a new object under the same key.
 *
 * <p>Interned per addon on the layer object ({@link Addon#layerObjs}, weak on both axes), so
 * {@code get("tooltip") == get("tooltip")} and a handle works as a table key; nothing here pins a layer
 * the pool has let go.
 */
public final class LuaLayer {
    /** The resource this layer was read from — the list {@code :exists()} looks in. */
    private final Resource res;
    /** The layer object itself: the whole state of a handle. */
    final Resource.Layer layer;

    private LuaLayer(Resource res, Resource.Layer layer) {
        this.res = res;
        this.layer = layer;
    }

    public String toString() {
        return "Layer(" + res.name + " " + LayerCodec.key(layer) + ")";
    }

    /** The interned handle for {@code layer} of {@code res} in {@code owner}'s env. */
    static LuaValue of(final Addon owner, final Resource res, final Resource.Layer layer) {
        return owner.layerObjs.of(layer, () -> LuaValue.userdataOf(new LuaLayer(res, layer), meta(owner)));
    }

    /** The {@code LuaLayer} behind a Lua value, or {@code null} for anything that is not a Layer object. */
    static LuaLayer resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaLayer) ? (LuaLayer)o : null;
    }

    /** Is this layer still one of its resource's? Identity, over the list the resource holds right now. */
    boolean exists() {
        for(Resource.Layer l : res.layers(Resource.Layer.class)) {
            if(l == layer)
                return true;
        }
        return false;
    }

    // ---- the metatable -----------------------------------------------------------------------------

    private static LuaValue meta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("layer", methods(),
            "a layer is one typed block of a resource, read through resource:layers()"));
        mt.set("__name", LuaValue.valueOf("Layer"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaLayer h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Layer(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        // type() — the wire's own type name: "image", "audio2", "tooltip".
        m.set("type", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "layer:type");
                return LuaValue.valueOf(LayerCodec.type(handle(self, "type").layer));
            }
        });
        // id() — the layer's own id (a number or a string), nil for a type that carries none.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "layer:id");
                return LayerCodec.luaId(handle(self, "id").layer);
            }
        });
        // exists() — is this layer still one of its resource's? False once a write replaced it.
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "layer:exists");
                return LuaValue.valueOf(handle(self, "exists").exists());
            }
        });
        // info() — the snapshot: {type, id} and the decoded fields of the types the codec knows.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "layer:info");
                return LayerCodec.snapshot(handle(self, "info").layer);
            }
        });
        return m;
    }

    private static LuaLayer handle(LuaValue self, String method) {
        LuaLayer h = resolve(self);
        if(h == null)
            throw new LuaError("layer:" + method + "() — use a COLON call on a Layer object"
                + " (resource:layers():get(key))");
        return h;
    }
}
