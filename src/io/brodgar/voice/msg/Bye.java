package io.brodgar.voice.msg;

/** Graceful client disconnect notice. */
public final class Bye implements Message {

    public static final String TYPE = "bye";

    @Override
    public String type() {
        return TYPE;
    }
}
