package io.brodgar.voice;

import io.brodgar.voice.MovementIntent;
import io.brodgar.voice.VisibleGob;

import java.util.List;
import java.util.function.Consumer;

/**
 * The bridge each Haven &amp; Hearth client implements to feed the library with
 * game state. All positions are vectors relative to the local player, in
 * tiles; absolute world coordinates must never cross this interface.
 *
 * <p>Implementations must be cheap and thread-safe: {@link #localGobId()} and
 * {@link #visiblePlayers()} are polled every ~500 ms from a library thread.
 */
public interface BrodgarVoiceHost {

    /** Gob id of the local player's character, or {@code -1} if not in the world. */
    long localGobId();

    /**
     * Player gobs currently visible to this client (other players only, no
     * NPCs/animals), each with its vector relative to the local player.
     * May return an empty list, never {@code null}.
     */
    List<VisibleGob> visiblePlayers();

    /**
     * The library registers a sink here at connect time. The client must push a
     * {@link MovementIntent} the moment the local player issues a movement order
     * (click, pathfinding leg), before the game server has applied it.
     */
    void setMovementIntentSink(Consumer<MovementIntent> sink);
}
