package io.brodgar.addon;

import haven.Coord;
import haven.Coord2d;
import haven.FColor;
import haven.MessageBuf;
import haven.Resource;
import haven.TexR;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.luaj.vm2.LuaError;
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

    // ---- the write side: a spec, and its wire bytes ------------------------------------------------

    /** The types a spec can write, in the order the refusal names them. */
    static final String[] WRITABLE = {"image", "tex", "audio2", "tooltip", "pagina", "props", "neg", "obst"};

    /** What each writable type's spec carries besides {@code type}, for the refusal that names them. */
    private static final Map<String, String> FIELDS = new HashMap<String, String>();
    static {
        FIELDS.put("tooltip", "text");
        FIELDS.put("pagina", "text");
        FIELDS.put("audio2", "id, clip, volume");
    }

    /**
     * A spec table read into plain Java (151.2): the wire type, the address it touches ({@code "type"}, or
     * {@code "type:id"} when the spec names an id), and its fields as {@code String} / {@code Double} /
     * {@code byte[]} values a loader thread can read for as long as the addon runs.
     */
    static final class Spec {
        final String type;
        final String address;
        final Map<String, Object> fields;

        Spec(String type, String address, Map<String, Object> fields) {
            this.type = type;
            this.address = address;
            this.fields = fields;
        }
    }

    /** The type half of a layer key: {@code "audio2:cl"} → {@code "audio2"}. */
    static String typeOf(String key) {
        int colon = key.indexOf(':');
        return (colon < 0) ? key : key.substring(0, colon);
    }

    /**
     * Read and check a spec table. Refuses a missing or unknown {@code type} naming the writable types, an
     * unknown field naming the type's fields, and a field of the wrong kind naming what it takes. Whether
     * the spec is <i>whole</i> is not decided here — that depends on what stands at the address, and
     * {@link #encode} decides it against the original.
     */
    static Spec spec(LuaValue v, String verb) {
        if(!v.istable())
            throw new LuaError(verb + ": spec must be a table — {type = \"tooltip\", text = \"...\"} — got "
                + v.typename());
        LuaTable t = v.checktable();
        LuaValue tv = t.get("type");
        if(tv.isnil())
            throw new LuaError(verb + ": spec.type is required — one of " + Arrays.toString(WRITABLE));
        String type = Args.str(tv, verb, "spec.type", "one of " + Arrays.toString(WRITABLE)).tojstring();
        if(Arrays.asList(WRITABLE).indexOf(type) < 0)
            throw new LuaError(verb + ": \"" + type + "\" is not a type a spec writes — the writable types are "
                + Arrays.toString(WRITABLE) + "; every other type arrives only inside a whole .res file");
        String allowed = FIELDS.get(type);
        if(allowed == null)
            throw new LuaError(verb + ": a " + type + " spec has no encoder in this build");
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        LuaValue k = LuaValue.NIL;
        while(true) {
            org.luaj.vm2.Varargs n = t.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            String field = k.tojstring();
            LuaValue fv = n.arg(2);
            if(field.equals("type"))
                continue;
            if(!k.isstring() || (("," + allowed.replace(" ", "") + ",").indexOf("," + field + ",") < 0))
                throw new LuaError(verb + ": a " + type + " spec has no field '" + field + "' — it takes {type, "
                    + allowed + "}");
            fields.put(field, field(type, field, fv, verb));
        }
        String address = fields.containsKey("id") ? (type + ":" + fields.get("id")) : type;
        return new Spec(type, address, fields);
    }

    /** One spec field as its plain value, checked by kind. */
    private static Object field(String type, String field, LuaValue v, String verb) {
        String param = "spec." + field;
        if(field.equals("text"))
            return Args.str(v, verb, param, "the " + type + "'s text").tojstring();
        if(field.equals("id"))
            return Args.str(v, verb, param, "the clip's id, a string (\"cl\")").tojstring();
        if(field.equals("volume")) {
            double d = Args.num(v, verb, param, "the clip's base loudness, 0 for silent, 1 for as served").todouble();
            if(d < 0)
                throw new LuaError(verb + ": " + param + " must be 0 or more, got " + d);
            return Double.valueOf(d);
        }
        if(field.equals("clip")) {
            String kind = AssetApi.typeOf(v);
            if(!"data".equals(kind))
                throw new LuaError(verb + ": " + param + " must be a DATA asset holding an Ogg Vorbis file"
                    + " (hafen.asset():get(\"chime.ogg\")), got " + ((kind == null) ? v.typename() : (kind + " asset")));
            byte[] bytes = ((AssetApi.Data)v.touserdata()).bytes;
            if((bytes.length < 4) || (bytes[0] != 'O') || (bytes[1] != 'g') || (bytes[2] != 'g') || (bytes[3] != 'S'))
                throw new LuaError(verb + ": " + param + " is not an Ogg Vorbis file — the client plays Ogg"
                    + " Vorbis clips only, and this one does not open with the OggS page header");
            return bytes;
        }
        throw new LuaError(verb + ": a " + type + " spec has no field '" + field + "'");
    }

    /**
     * The wire bytes of {@code type} built from {@code fields} over {@code original} — the layer standing at
     * the address, or {@code null} for an empty one. A field left out takes the original's; with no original
     * it must be present, or the refusal names it. {@code verb} prefixes a refusal, or is {@code null} at
     * apply time, where a refusal is logged rather than raised.
     */
    static byte[] encode(String type, Map<String, Object> fields, Resource.Layer original, String verb) {
        String at = (verb == null) ? "" : (verb + ": ");
        if(type.equals("tooltip") || type.equals("pagina")) {
            String text = (String)fields.get("text");
            if(text == null) {
                if(original instanceof Resource.Tooltip)
                    text = ((Resource.Tooltip)original).t;
                else if(original instanceof Resource.Pagina)
                    text = ((Resource.Pagina)original).text;
            }
            if(text == null)
                throw new LuaError(at + "no " + type + " stands at this address, so the spec must be whole:"
                    + " text is missing");
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if(type.equals("audio2")) {
            Resource.Audio orig = (original instanceof Resource.Audio) ? (Resource.Audio)original : null;
            String id = (String)fields.get("id");
            if((id == null) && (orig != null))
                id = orig.id;
            byte[] clip = (byte[])fields.get("clip");
            if((clip == null) && (orig != null))
                clip = orig.coded;
            Double vol = (Double)fields.get("volume");
            if((vol == null) && (orig != null))
                vol = Double.valueOf(orig.bvol);
            if(orig == null) {
                String missing = (id == null) ? "id" : (clip == null) ? "clip" : null;
                if(missing != null)
                    throw new LuaError(at + "no audio2 stands at this address, so the spec must be whole: "
                        + missing + " is missing");
            }
            if(clip == null)
                throw new LuaError(at + "the audio2 at this address carries no clip bytes to keep: clip is missing");
            MessageBuf buf = new MessageBuf();
            buf.adduint8(3);
            buf.addstring(id);
            if(orig != null) {
                for(Map.Entry<String, Object> e : orig.info.entrySet()) {
                    if(e.getKey().equals("vol"))
                        continue;
                    buf.addstring(e.getKey());
                    buf.addtto(e.getValue());
                }
            }
            if(vol != null) {
                buf.addstring("vol");
                buf.addtto(vol);
            }
            buf.addstring("");
            buf.addbytes(clip);
            return buf.fin();
        }
        throw new LuaError(at + "a " + type + " spec has no encoder in this build");
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
