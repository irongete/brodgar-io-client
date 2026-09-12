package io.brodgar.voice.msg;

/** Marker for all WebSocket presence messages. */
public interface Message {

    /** Wire value of the {@code type} field. */
    String type();
}
