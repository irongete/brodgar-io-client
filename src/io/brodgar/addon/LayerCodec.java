package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.FColor;
import haven.Resource;
import haven.TexR;

import java.awt.Color;
import java.util.Map;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * <b>What a layer is, in Lua terms</b> (151): its wire type name, its id, the key that addresses it in
 * {@code resource:layers()}, and the plain-table snapshot {@code layer:info()} hands back.
 *
 * <p><b>The type is the wire's own word.</b> Every layer class the client knows is registered under a
 * {@link Resource.LayerName} — {@code "image"}, {@code "audio2"}, {@code "tooltip"} — and that annotation
 * is what {@link #type} reads, walking up from the object's class, so the name an addon writes is the name
 * the {@code .res} file carries. There is no second vocabulary.
 *
 * <p><b>The id is the layer's own</b> ({@link Resource.IDLayer#layerid}): an integer for an {@code image}
 * or a {@code tex}, a string for an {@code audio2} or an {@code obst} (the client's default is {@code ""}),
 * and absent for a type that carries none. The address {@code type:id} spells the id as it prints, so
 * {@code "image:-1"} and {@code "audio2:cl"} are keys and {@code "obst:"} is the unnamed collision ring.
 *
 * <p><b>The snapshot decodes the types an addon can write</b> ({@code image}, {@code tex}, {@code audio2},
 * {@code tooltip}, {@code pagina}, {@code props}, {@code neg}, {@code obst}) and the one it reads to see
 * how the images hang together ({@code anim}); every other type is {@code {type, id}} and nothing more,
 * because a mesh or a skeleton is the renderer's and arrives only inside a whole {@code .res} file.
 * A layer's key/value block ({@link Resource.Metadata#info}) is {@code meta}, spelled with the wire's
 * values through {@link #value}.
 */
final class LayerCodec {
    private LayerCodec() {
    }

    // ---- type, id, key -----------------------------------------------------------------------------

    /** The wire type name of {@code layer} — its {@link Resource.LayerName}, or its class name when it has none. */
    static String type(Resource.Layer layer) {
        for(Class<?> cl = layer.getClass(); cl != null; cl = cl.getSuperclass()) {
            Resource.LayerName nm = cl.getAnnotation(Resource.LayerName.class);
            if(nm != null)
                return nm.value();
        }
        return layer.getClass().getSimpleName().toLowerCase();
    }

    /** The layer's own id — an {@code Integer} or a {@code String} — or {@code null} for a type without one. */
    static Object id(Resource.Layer layer) {
        if(layer instanceof Resource.IDLayer)
            return ((Resource.IDLayer<?>)layer).layerid();
        return null;
    }

    /** The id as a Lua value: a number, a string, or {@code nil}. */
    static LuaValue luaId(Resource.Layer layer) {
        return value(id(layer));
    }

    /** The address of {@code layer} in its collection: {@code "type:id"}, or {@code "type"} for an id-less type. */
    static String key(Resource.Layer layer) {
        Object id = id(layer);
        return (id == null) ? type(layer) : (type(layer) + ":" + id);
    }

    /**
     * Does {@code layer} stand at {@code key}? {@code "type"} matches every layer of that type;
     * {@code "type:id"} the ones whose id prints as the text after the colon — so {@code "obst:"} is the
     * empty id and {@code "image:-1"} is the default image.
     */
    static boolean at(Resource.Layer layer, String key) {
        int colon = key.indexOf(':');
        if(colon < 0)
            return type(layer).equals(key);
        if(!type(layer).equals(key.substring(0, colon)))
            return false;
        Object id = id(layer);
        return (id != null) && String.valueOf(id).equals(key.substring(colon + 1));
    }

    // ---- the snapshot ------------------------------------------------------------------------------

    /** {@code layer:info()}: {@code {type, id}} plus the decoded fields of the types this class knows. */
    static LuaTable snapshot(Resource.Layer layer) {
        LuaTable t = new LuaTable();
        t.set("type", LuaValue.valueOf(type(layer)));
        t.set("id", luaId(layer));
        if(layer instanceof Resource.Image) {
            Resource.Image img = (Resource.Image)layer;
            t.set("z", LuaValue.valueOf(img.z));
            t.set("subz", LuaValue.valueOf(img.subz));
            t.set("nooff", LuaValue.valueOf(img.nooff));
            t.set("offset", xy(img.o));
            t.set("size", LuaWidget.whTable(img.sz));
            t.set("tsz", xy(img.tsz));
            t.set("scale", LuaValue.valueOf(img.scale));
            t.set("meta", meta(img.info()));
        } else if(layer instanceof TexR) {
            t.set("size", LuaWidget.whTable(((TexR)layer).tex().sz()));
        } else if(layer instanceof Resource.Audio) {
            Resource.Audio audio = (Resource.Audio)layer;
            t.set("volume", LuaValue.valueOf(audio.bvol));
            t.set("meta", meta(audio.info()));
        } else if(layer instanceof Resource.Tooltip) {
            t.set("text", LuaValue.valueOf(((Resource.Tooltip)layer).t));
        } else if(layer instanceof Resource.Pagina) {
            t.set("text", LuaValue.valueOf(((Resource.Pagina)layer).text));
        } else if(layer instanceof Resource.Props) {
            t.set("props", meta(((Resource.Props)layer).props));
        } else if(layer instanceof Resource.Neg) {
            Resource.Neg neg = (Resource.Neg)layer;
            t.set("hotspot", xy(neg.cc));
            LuaTable box = new LuaTable();
            box.set("x", LuaValue.valueOf(neg.ac.x));
            box.set("y", LuaValue.valueOf(neg.ac.y));
            box.set("w", LuaValue.valueOf(neg.bc.x - neg.ac.x));
            box.set("h", LuaValue.valueOf(neg.bc.y - neg.ac.y));
            t.set("box", box);
            LuaTable ep = new LuaTable();
            for(int i = 0; i < neg.ep.length; i++) {
                LuaTable ring = new LuaTable();
                for(int o = 0; o < neg.ep[i].length; o++)
                    ring.set(o + 1, xy(neg.ep[i][o]));
                ep.set(i + 1, ring);
            }
            t.set("ep", ep);
        } else if(layer instanceof Resource.Obstacle) {
            Resource.Obstacle obst = (Resource.Obstacle)layer;
            LuaTable rings = new LuaTable();
            for(int i = 0; i < obst.p.length; i++) {
                LuaTable ring = new LuaTable();
                for(int o = 0; o < obst.p[i].length; o++)
                    ring.set(o + 1, xy(obst.p[i][o]));
                rings.set(i + 1, ring);
            }
            t.set("rings", rings);
        } else if(layer instanceof Resource.Anim) {
            Resource.Anim anim = (Resource.Anim)layer;
            t.set("duration", LuaValue.valueOf(anim.d));
            LuaTable frames = new LuaTable();
            // Anim.init binds each frame to the images sharing its id, so a frame's id is read off its
            // first image; f is null only before init, which no cached resource is.
            if(anim.f != null) {
                for(int i = 0; i < anim.f.length; i++)
                    frames.set(i + 1, (anim.f[i].length > 0) ? LuaValue.valueOf(anim.f[i][0].id) : LuaValue.FALSE);
            }
            t.set("frames", frames);
        }
        return t;
    }

    // ---- values ------------------------------------------------------------------------------------

    /** A key/value block ({@code Image.info}, {@code Audio.info}, {@code Props.props}) as a string-keyed table. */
    static LuaTable meta(Map<?, ?> info) {
        LuaTable t = new LuaTable();
        if(info != null) {
            for(Map.Entry<?, ?> e : info.entrySet())
                t.set(String.valueOf(e.getKey()), value(e.getValue()));
        }
        return t;
    }

    /**
     * One wire value ({@code Message.tto}) as Lua: a number, a string, a boolean, a {@code {x=,y=}} for a
     * coordinate, {@code {r,g,b,a}} for a colour, a 1-based array for a list, a string-keyed table for a
     * map, the name of a resource reference, raw bytes as a string, and {@code nil} for nothing.
     * Anything else prints as the client prints it.
     */
    static LuaValue value(Object o) {
        if(o == null)
            return LuaValue.NIL;
        if(o instanceof Boolean)
            return LuaValue.valueOf(((Boolean)o).booleanValue());
        if((o instanceof Integer) || (o instanceof Short) || (o instanceof Byte))
            return LuaValue.valueOf(((Number)o).intValue());
        if(o instanceof Number)
            return LuaValue.valueOf(((Number)o).doubleValue());
        if(o instanceof String)
            return LuaValue.valueOf((String)o);
        if(o instanceof Coord)
            return xy((Coord)o);
        if(o instanceof Coord2d)
            return xy((Coord2d)o);
        if(o instanceof Color) {
            Color c = (Color)o;
            LuaTable t = new LuaTable();
            t.set(1, LuaValue.valueOf(c.getRed()));
            t.set(2, LuaValue.valueOf(c.getGreen()));
            t.set(3, LuaValue.valueOf(c.getBlue()));
            t.set(4, LuaValue.valueOf(c.getAlpha()));
            return t;
        }
        if(o instanceof FColor) {
            FColor c = (FColor)o;
            LuaTable t = new LuaTable();
            t.set(1, LuaValue.valueOf(Math.round(c.r * 255)));
            t.set(2, LuaValue.valueOf(Math.round(c.g * 255)));
            t.set(3, LuaValue.valueOf(Math.round(c.b * 255)));
            t.set(4, LuaValue.valueOf(Math.round(c.a * 255)));
            return t;
        }
        if(o instanceof byte[])
            return LuaValue.valueOf((byte[])o);
        if(o instanceof Object[]) {
            Object[] list = (Object[])o;
            LuaTable t = new LuaTable();
            for(int i = 0; i < list.length; i++)
                t.set(i + 1, value(list[i]));
            return t;
        }
        if(o instanceof Map)
            return meta((Map<?, ?>)o);
        if(o instanceof Resource.Named)
            return LuaValue.valueOf(((Resource.Named)o).name);
        return LuaValue.valueOf(String.valueOf(o));
    }

    private static LuaValue xy(Coord c) {
        return (c == null) ? LuaValue.NIL : LuaWidget.xyTable(c);
    }

    private static LuaValue xy(Coord2d c) {
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        t.set("x", LuaValue.valueOf(c.x));
        t.set("y", LuaValue.valueOf(c.y));
        return t;
    }
}
