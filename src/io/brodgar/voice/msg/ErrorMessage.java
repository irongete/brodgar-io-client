package io.brodgar.voice.msg;

/** Server-side error report. Fatal errors are followed by a WebSocket close. */
public final class ErrorMessage implements Message {

    public static final String TYPE = "error";

    private final String code;
    private final String message;
    private final boolean fatal;

    public ErrorMessage(String code, String message, boolean fatal) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.fatal = fatal;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** Stable machine-readable code, one of {@code Protocol.ERR_*}. */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public boolean fatal() {
        return fatal;
    }
}
