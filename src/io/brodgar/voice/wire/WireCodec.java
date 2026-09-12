package io.brodgar.voice.wire;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import io.brodgar.voice.MovementIntent;
import io.brodgar.voice.Protocol;
import io.brodgar.voice.ProtocolException;
import io.brodgar.voice.VisibleGob;
import io.brodgar.voice.util.Hex;
import io.brodgar.voice.msg.Bye;
import io.brodgar.voice.msg.ClientHello;
import io.brodgar.voice.msg.EdgesUpdate;
import io.brodgar.voice.msg.ErrorMessage;
import io.brodgar.voice.msg.Message;
import io.brodgar.voice.msg.Report;
import io.brodgar.voice.msg.ServerWelcome;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON codec for all WebSocket presence messages. Decoding validates limits
 * and value ranges: anything malformed or out of bounds raises
 * {@link ProtocolException} and must be treated as a protocol violation by the
 * caller.
 */
public final class WireCodec {

    private static final int MAX_MESSAGE_CHARS = 64 * 1024;
    private static final int MAX_EDGE_LIST = 4096;

    private WireCodec() {
    }

    // ---------------------------------------------------------------- encode

    public static String encode(Message m) {
        if (m instanceof ClientHello) {
            ClientHello h = (ClientHello) m;
            return new JsonObject()
                    .add("type", ClientHello.TYPE)
                    .add("proto", h.protoVersion())
                    .add("client", h.clientInfo())
                    .add("pub", Hex.encode(h.publicKey()))
                    .toString();
        }
        if (m instanceof Report) {
            return encodeReport((Report) m);
        }
        if (m instanceof Bye) {
            return new JsonObject().add("type", Bye.TYPE).toString();
        }
        if (m instanceof ServerWelcome) {
            ServerWelcome w = (ServerWelcome) m;
            return new JsonObject()
                    .add("type", ServerWelcome.TYPE)
                    .add("proto", w.protoVersion())
                    .add("sessionId", w.sessionId())
                    .add("pub", Hex.encode(w.publicKey()))
                    .add("udp", new JsonObject()
                            .add("host", w.udpHost())
                            .add("port", w.udpPort())
                            .add("token", w.udpTokenHex()))
                    .add("audio", new JsonObject()
                            .add("sampleRate", w.sampleRate())
                            .add("channels", w.channels())
                            .add("frameMs", w.frameMs())
                            .add("maxFrameBytes", w.maxFrameBytes()))
                    .add("limits", new JsonObject()
                            .add("reportIntervalMs", w.reportIntervalMs())
                            .add("freshnessMs", w.freshnessMs())
                            .add("maxVisible", w.maxVisible()))
                    .toString();
        }
        if (m instanceof EdgesUpdate) {
            EdgesUpdate e = (EdgesUpdate) m;
            return new JsonObject()
                    .add("type", EdgesUpdate.TYPE)
                    .add("hear", longArray(e.hear()))
                    .add("heardBy", longArray(e.heardBy()))
                    .toString();
        }
        if (m instanceof ErrorMessage) {
            ErrorMessage e = (ErrorMessage) m;
            return new JsonObject()
                    .add("type", ErrorMessage.TYPE)
                    .add("code", e.code())
                    .add("message", e.message())
                    .add("fatal", e.fatal())
                    .toString();
        }
        throw new IllegalArgumentException("unknown message class: " + m.getClass());
    }

    private static String encodeReport(Report r) {
        JsonObject o = new JsonObject()
                .add("type", Report.TYPE)
                .add("seq", r.seq())
                .add("t", r.tMillis());
        if (r.selfGobId() == Report.NO_GOB) {
            o.add("self", Json.NULL);
        } else {
            o.add("self", new JsonObject().add("gobId", r.selfGobId()));
        }
        JsonArray visible = new JsonArray();
        for (VisibleGob g : r.visible()) {
            visible.add(new JsonObject()
                    .add("gobId", g.gobId())
                    .add("dx", g.dx())
                    .add("dy", g.dy()));
        }
        o.add("visible", visible);
        JsonArray intents = new JsonArray();
        for (MovementIntent i : r.intents()) {
            intents.add(new JsonObject()
                    .add("t", i.tMillis())
                    .add("dx", i.dx())
                    .add("dy", i.dy()));
        }
        o.add("intents", intents);
        return o.toString();
    }

    private static JsonArray longArray(long[] values) {
        JsonArray a = new JsonArray();
        for (long v : values) {
            a.add(v);
        }
        return a;
    }

    // ---------------------------------------------------------------- decode

    /** Decodes a message sent by a client (hello, report, bye). Server-side (the
     *  client encodes these but decodes only server messages); used by wire tests. */
    static Message decodeClientMessage(String json) throws ProtocolException {
        JsonObject o = parseObject(json);
        String type = getString(o, "type");
        switch (type) {
            case ClientHello.TYPE:
                return new ClientHello(getInt(o, "proto"), optString(o, "client", ""),
                        getKey(o, "pub", Protocol.HANDSHAKE_KEY_BYTES));
            case Report.TYPE:
                return decodeReport(o);
            case Bye.TYPE:
                return new Bye();
            default:
                throw new ProtocolException("unknown client message type: " + type);
        }
    }

    /** Decodes a message sent by the server (welcome, edges, error). */
    public static Message decodeServerMessage(String json) throws ProtocolException {
        JsonObject o = parseObject(json);
        String type = getString(o, "type");
        switch (type) {
            case ServerWelcome.TYPE: {
                JsonObject udp = getObject(o, "udp");
                JsonObject audio = getObject(o, "audio");
                JsonObject limits = getObject(o, "limits");
                return new ServerWelcome(
                        getInt(o, "proto"),
                        getLong(o, "sessionId"),
                        getString(udp, "host"),
                        getInt(udp, "port"),
                        getString(udp, "token"),
                        getKey(o, "pub", Protocol.HANDSHAKE_KEY_BYTES),
                        getInt(audio, "sampleRate"),
                        getInt(audio, "channels"),
                        getInt(audio, "frameMs"),
                        getInt(audio, "maxFrameBytes"),
                        getInt(limits, "reportIntervalMs"),
                        getInt(limits, "freshnessMs"),
                        getInt(limits, "maxVisible"));
            }
            case EdgesUpdate.TYPE:
                return new EdgesUpdate(
                        longList(o, "hear"),
                        longList(o, "heardBy"));
            case ErrorMessage.TYPE:
                return new ErrorMessage(
                        getString(o, "code"),
                        optString(o, "message", ""),
                        o.getBoolean("fatal", false));
            default:
                throw new ProtocolException("unknown server message type: " + type);
        }
    }

    private static Report decodeReport(JsonObject o) throws ProtocolException {
        long seq = getLong(o, "seq");
        long t = getLong(o, "t");
        if (seq < 0 || t < 0) {
            throw new ProtocolException("negative seq/t");
        }

        long selfGob = Report.NO_GOB;
        JsonValue self = o.get("self");
        if (self != null && !self.isNull()) {
            if (!self.isObject()) {
                throw new ProtocolException("self must be object or null");
            }
            selfGob = getLong(self.asObject(), "gobId");
            if (selfGob <= 0) {
                throw new ProtocolException("self.gobId must be positive");
            }
        }

        JsonValue visVal = o.get("visible");
        List<VisibleGob> visible = new ArrayList<>();
        if (visVal != null && !visVal.isNull()) {
            JsonArray arr = asArray(visVal, "visible");
            if (arr.size() > Protocol.MAX_VISIBLE) {
                throw new ProtocolException("visible exceeds " + Protocol.MAX_VISIBLE);
            }
            for (JsonValue v : arr) {
                JsonObject g = asObject(v, "visible entry");
                long gobId = getLong(g, "gobId");
                if (gobId <= 0) {
                    throw new ProtocolException("visible gobId must be positive");
                }
                double dx = getVectorComponent(g, "dx");
                double dy = getVectorComponent(g, "dy");
                visible.add(new VisibleGob(gobId, dx, dy));
            }
        }

        JsonValue intVal = o.get("intents");
        List<MovementIntent> intents = new ArrayList<>();
        if (intVal != null && !intVal.isNull()) {
            JsonArray arr = asArray(intVal, "intents");
            if (arr.size() > Protocol.MAX_INTENTS_PER_REPORT) {
                throw new ProtocolException("intents exceeds " + Protocol.MAX_INTENTS_PER_REPORT);
            }
            for (JsonValue v : arr) {
                JsonObject i = asObject(v, "intent entry");
                long it = getLong(i, "t");
                double dx = getVectorComponent(i, "dx");
                double dy = getVectorComponent(i, "dy");
                intents.add(new MovementIntent(it, dx, dy));
            }
        }

        return new Report(seq, t, selfGob, visible, intents);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject parseObject(String json) throws ProtocolException {
        if (json == null || json.length() > MAX_MESSAGE_CHARS) {
            throw new ProtocolException("message missing or too large");
        }
        try {
            JsonValue v = Json.parse(json);
            if (!v.isObject()) {
                throw new ProtocolException("top-level JSON must be an object");
            }
            return v.asObject();
        } catch (com.eclipsesource.json.ParseException e) {
            throw new ProtocolException("bad JSON: " + e.getMessage(), e);
        }
    }

    private static JsonObject getObject(JsonObject o, String name) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null) {
            throw new ProtocolException("missing field: " + name);
        }
        return asObject(v, name);
    }

    private static JsonObject asObject(JsonValue v, String what) throws ProtocolException {
        if (!v.isObject()) {
            throw new ProtocolException(what + " must be an object");
        }
        return v.asObject();
    }

    private static JsonArray asArray(JsonValue v, String what) throws ProtocolException {
        if (!v.isArray()) {
            throw new ProtocolException(what + " must be an array");
        }
        return v.asArray();
    }

    private static String getString(JsonObject o, String name) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null || !v.isString()) {
            throw new ProtocolException("missing/invalid string field: " + name);
        }
        return v.asString();
    }

    private static byte[] getKey(JsonObject o, String name, int expectedLen) throws ProtocolException {
        String hex = getString(o, name);
        byte[] bytes;
        try {
            bytes = Hex.decode(hex);
        } catch (RuntimeException e) {
            throw new ProtocolException("bad hex in field: " + name, e);
        }
        if (bytes.length != expectedLen) {
            throw new ProtocolException(name + " must be " + expectedLen + " bytes");
        }
        return bytes;
    }

    private static String optString(JsonObject o, String name, String dflt) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null || v.isNull()) {
            return dflt;
        }
        if (!v.isString()) {
            throw new ProtocolException("invalid string field: " + name);
        }
        return v.asString();
    }

    private static long getLong(JsonObject o, String name) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null || !v.isNumber()) {
            throw new ProtocolException("missing/invalid number field: " + name);
        }
        try {
            return v.asLong();
        } catch (NumberFormatException e) {
            throw new ProtocolException("non-integer value in field: " + name, e);
        }
    }

    private static int getInt(JsonObject o, String name) throws ProtocolException {
        long v = getLong(o, name);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new ProtocolException("field out of int range: " + name);
        }
        return (int) v;
    }

    private static double getVectorComponent(JsonObject o, String name) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null || !v.isNumber()) {
            throw new ProtocolException("missing/invalid number field: " + name);
        }
        double d;
        try {
            d = v.asDouble();
        } catch (NumberFormatException e) {
            throw new ProtocolException("bad number in field: " + name, e);
        }
        if (!Double.isFinite(d) || Math.abs(d) > Protocol.MAX_VECTOR_TILES) {
            throw new ProtocolException("vector component out of range: " + name);
        }
        return d;
    }

    private static long[] longList(JsonObject o, String name) throws ProtocolException {
        JsonValue v = o.get(name);
        if (v == null) {
            throw new ProtocolException("missing field: " + name);
        }
        JsonArray arr = asArray(v, name);
        if (arr.size() > MAX_EDGE_LIST) {
            throw new ProtocolException(name + " too long");
        }
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) {
            JsonValue e = arr.get(i);
            if (!e.isNumber()) {
                throw new ProtocolException(name + " entries must be numbers");
            }
            out[i] = e.asLong();
        }
        return out;
    }
}
