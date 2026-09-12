package io.brodgar.voice.msg;

import io.brodgar.voice.MovementIntent;
import io.brodgar.voice.VisibleGob;

import java.util.Collections;
import java.util.List;

/**
 * Periodic presence report (~every 500 ms): which gob the local player is,
 * which player gobs it sees (relative vectors only) and which movement orders
 * it issued since the previous report.
 */
public final class Report implements Message {

    public static final String TYPE = "report";

    /** Value of {@link #selfGobId()} when the player has no character in the world. */
    public static final long NO_GOB = -1;

    private final long seq;
    private final long tMillis;
    private final long selfGobId;
    private final List<VisibleGob> visible;
    private final List<MovementIntent> intents;

    public Report(long seq, long tMillis, long selfGobId,
                  List<VisibleGob> visible, List<MovementIntent> intents) {
        this.seq = seq;
        this.tMillis = tMillis;
        this.selfGobId = selfGobId;
        this.visible = Collections.unmodifiableList(visible);
        this.intents = Collections.unmodifiableList(intents);
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** Monotonic per-session counter; the server drops reordered reports. */
    public long seq() {
        return seq;
    }

    /** Client wall-clock (informational; the server trusts its own arrival clock). */
    public long tMillis() {
        return tMillis;
    }

    /** Gob id claimed by this session, or {@link #NO_GOB}. */
    public long selfGobId() {
        return selfGobId;
    }

    /** Player gobs currently visible, as vectors relative to the local player. */
    public List<VisibleGob> visible() {
        return visible;
    }

    /** Movement orders issued since the previous report. */
    public List<MovementIntent> intents() {
        return intents;
    }
}
