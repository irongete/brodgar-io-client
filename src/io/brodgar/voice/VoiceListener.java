package io.brodgar.voice;

import java.util.Set;

/**
 * Event callbacks for client UIs. Invoked on a dedicated library event thread —
 * never block in them; hop to your UI thread as needed.
 */
public interface VoiceListener {

    /** The set of gobs this client can currently hear changed. */
    default void onAudibleSetChanged(Set<Long> gobIds) {
    }

    /**
     * The set of gobs whose owners can currently hear this client changed.
     * UIs should surface this ("who can hear me") at all times.
     */
    default void onHeardByChanged(Set<Long> gobIds) {
    }

    /** A remote player started ({@code speaking=true}) or stopped talking. */
    default void onSpeaking(long gobId, boolean speaking) {
    }

    /**
     * The presence connection went up or down. With auto-reconnect enabled the
     * The library drops to {@code false} on a lost socket and returns to {@code true}
     * once it re-establishes; audio pauses in between.
     */
    default void onConnectionState(boolean connected) {
    }

    /**
     * A non-fatal or fatal library error. After a fatal error the instance is
     * dead: close it and reconnect.
     */
    default void onError(String code, String message, boolean fatal) {
    }
}
