package io.brodgar.voice.msg;

import java.util.Arrays;

/**
 * Pushed by the server whenever this session's active audio edges change.
 *
 * <p>{@code hear}: gobs whose audio this session currently receives.
 * {@code heardBy}: gobs whose owners currently receive this session's audio —
 * the set every client UI should be able to show ("who can hear me").
 */
public final class EdgesUpdate implements Message {

    public static final String TYPE = "edges";

    private final long[] hear;
    private final long[] heardBy;

    public EdgesUpdate(long[] hear, long[] heardBy) {
        this.hear = hear.clone();
        this.heardBy = heardBy.clone();
        Arrays.sort(this.hear);
        Arrays.sort(this.heardBy);
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** Sorted gob ids this session can hear. */
    public long[] hear() {
        return hear.clone();
    }

    /** Sorted gob ids that can hear this session. */
    public long[] heardBy() {
        return heardBy.clone();
    }
}
