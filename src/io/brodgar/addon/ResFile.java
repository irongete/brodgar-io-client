package io.brodgar.addon;

import haven.MessageBuf;
import haven.Resource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.luaj.vm2.LuaError;

/**
 * <b>A whole {@code .res} file as a write</b> (151.4) — the data asset {@code resource:layers(file)} takes,
 * read by the record grammar {@code Resource.load} reads ({@code "Haven Resource 1"}, a {@code uint16}
 * version, then records of {@code string type, int32 len, bytes}) into the plain list of records a
 * {@link ResourceWrites.Record} of kind {@code FILE} carries.
 *
 * <p><b>The version is the one thing the file says that is ignored.</b> A file write replaces the layers of
 * whatever version the server names; the resource keeps {@code Resource.ver}. That is the difference from
 * upstream's {@code HAFEN_RESDIR}, which takes a file only when its version equals the server's.
 *
 * <p><b>{@code code} and {@code codeentry} are refused by name before any constructor runs</b>: an addon
 * ships no Java, and {@code CodeEntry}'s class loader would define whatever the file carries with the
 * client's privileges, outside the LuaJ sandbox and every permission key. A type the client does not parse
 * is skipped by length, as {@code load} skips it.
 *
 * <p><b>The set is validated whole at write time</b>, on a scratch {@code Resource.Virtual}: every record is
 * constructed through the wire factory and every layer {@code init()}ed over the scratch's list, so a
 * self-contained file proves itself and one whose layer needs another that is not there ({@code mesh}
 * without its {@code vbuf2}) is refused naming the layer. What registers cannot fail when applied.
 */
final class ResFile {
    private ResFile() {
    }

    /** One record of the file: its wire type and the bytes after {@code string type, int32 len}. */
    static final class Entry {
        final String type;
        final byte[] bytes;

        Entry(String type, byte[] bytes) {
            this.type = type;
            this.bytes = bytes;
        }
    }

    private static final byte[] SIG = "Haven Resource 1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Read {@code bytes} as a {@code .res} file: the records of the types the client parses, in file order.
     * Refuses bytes that do not open with the signature, a record cut short, and a {@code code} /
     * {@code codeentry} record — before any layer is constructed.
     */
    static List<Entry> read(byte[] bytes, String verb) {
        if((bytes.length < SIG.length) || !Arrays.equals(SIG, Arrays.copyOf(bytes, SIG.length)))
            throw new LuaError(verb + ": file is not a resource file — a .res opens with the \"Haven Resource 1\""
                + " signature, and this one does not");
        MessageBuf in = new MessageBuf(bytes);
        in.skip(SIG.length);
        in.uint16();   // the file's version: ignored, the resource keeps the server's
        List<Entry> out = new ArrayList<Entry>();
        try {
            while(!in.eom()) {
                String type = in.string();
                int len = in.int32();
                if(type.equals("code") || type.equals("codeentry"))
                    throw new LuaError(verb + ": file carries a \"" + type + "\" layer, the client's published"
                        + " code — an addon ships no Java, so a .res with code in it is refused whole");
                if(len < 0)
                    throw new LuaError(verb + ": file is not a resource file — a \"" + type + "\" record claims "
                        + len + " bytes");
                byte[] rec = in.bytes(len);
                if(Resource.knownLayer(type))
                    out.add(new Entry(type, rec));
            }
        } catch(LuaError e) {
            throw e;
        } catch(RuntimeException e) {   // a record cut short: MessageBuf's own underflow
            throw new LuaError(verb + ": file is not a resource file — its records end before their length says"
                + " (" + Refusal.reason(e) + ")");
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The wire factory's verdict on the whole set: construct every entry bound to a scratch resource of
     * {@code name} at {@code ver}, then {@code init()} each over that list. A layer that fails either step
     * is a refusal naming its type.
     */
    static void validate(List<Entry> entries, String name, int ver, String verb) {
        Resource.Virtual scratch = new Resource.Virtual(Resource.remote(), name, ver);
        List<Resource.Layer> built = new ArrayList<Resource.Layer>(entries.size());
        for(Entry e : entries) {
            try {
                Resource.Layer l = Resource.newLayer(scratch, e.type, new MessageBuf(e.bytes));
                if(l != null) {
                    scratch.add(l);
                    built.add(l);
                }
            } catch(RuntimeException x) {
                throw new LuaError(verb + ": the client refused the file's " + e.type + " layer — " + Refusal.reason(x));
            }
        }
        for(Resource.Layer l : built) {
            try {
                l.init();
            } catch(RuntimeException x) {
                throw new LuaError(verb + ": the file's " + LayerCodec.type(l) + " layer needs something the file"
                    + " does not carry — " + Refusal.reason(x));
            }
        }
    }

    /** The layers of {@code entries}, built bound to {@code res} — the apply side. */
    static List<Resource.Layer> build(Resource res, List<Entry> entries) {
        List<Resource.Layer> out = new ArrayList<Resource.Layer>(entries.size());
        for(Entry e : entries) {
            Resource.Layer l = Resource.newLayer(res, e.type, new MessageBuf(e.bytes));
            if(l != null)
                out.add(l);
        }
        return out;
    }
}
