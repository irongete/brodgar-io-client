package io.brodgar.voice;

import haven.Coord2d;
import haven.Drawable;
import haven.Gob;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.OCache;
import haven.Resource;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Self-contained Brodgar.io Proximity Voice Chat integration for the Haven &amp; Hearth client.
 * Drop this file into {@code src/io/brodgar/voice/}, put {@code brodgar-voice-all.jar}
 * on the classpath, and add the three call sites in {@code MapView} (see docs/).
 * Everything else — connection, mic capture, encryption, spatialized playback —
 * is handled by the library.
 *
 * <p>This class is the whole API: every control and every piece of state is a
 * {@code Voice.*} static. It owns the desired settings, applies them to the live
 * session, and re-applies them on every reconnect — so nothing is lost when the
 * connection drops, and callers never handle a session object or a null.
 *
 * <p>Uses only public {@code haven.*} APIs, needs no changes to any existing class
 * beyond the call sites, and never blocks the game thread.
 */
public final class Voice {

    private static final Logger log = Logger.getLogger("brodgar.voice");

    /**
     * Brodgar.io voice server.
     */
    private static final String SERVER = "wss://voice.brodgar.io";

    // --- desired settings: the single source of truth, re-applied on every connect ---
    private static volatile boolean enabled = true;      // master on/off
    private static volatile boolean openMic = true;      // voice activation vs push-to-talk
    private static volatile boolean pttHeld = false;     // push-to-talk key held right now
    private static volatile boolean micMuted = false;    // hard mic mute (others stop hearing you)
    private static volatile boolean deafened = false;    // silence all incoming audio
    private static volatile float masterVolume = 1f;     // 0..4, 1 = unity
    private static volatile double micSensitivity = 350; // open-mic RMS threshold (library default)
    private static final Set<Long> localMuted = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Float> playerVolumes = new ConcurrentHashMap<>();
    private static final List<VoiceListener> listeners = new CopyOnWriteArrayList<>();

    /** Push-to-talk key, reconfigurable in the client's Keybindings panel. */
    public static final KeyBinding kb_ptt = KeyBinding.get("brodgar/ptt", KeyMatch.forcode(KeyEvent.VK_V, 0));

    // --- runtime state ---
    private static volatile MapView view;          // the active in-game map view, or null
    private static volatile Bridge session;        // the live voice session, or null

    private Voice() {
    }

    // ----------------------------------------------------------- MapView call sites

    /**
     * Enter the game: remember the map view and, when voice is enabled, start a
     * session. Idempotent and non-blocking. Call site #1 (MapView constructor).
     */
    public static synchronized void attach(MapView mv) {
        if (mv == null) {
            return;
        }
        if (view != mv) {
            stop();
            view = mv;
        }
        start();
    }

    /**
     * Leave the game (logout / dispose): stop voice instantly. Call site #3
     * (MapView.dispose).
     */
    public static synchronized void detach(MapView mv) {
        if (view == mv) {
            view = null;
            stop();
        }
    }

    /**
     * Report a local movement order the instant it is issued, in map units. Call
     * site #2 (ground-click path in MapView). Does nothing when no session is live.
     */
    public static void onMove(MapView mv, Coord2d dest) {
        Bridge s = session;
        if (s != null && s.mv == mv) {
            s.onMove(dest);
        }
    }

    // ----------------------------------------------------------- master switch

    /**
     * Master switch, e.g. for an "enable voice chat" option. Off closes the session
     * completely (no connection, nothing sent or received) and stays off; on
     * (re)starts it for the current game session.
     */
    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (on) {
            start();
        } else {
            stop();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** True while a voice session is connected and live. */
    public static boolean isConnected() {
        return instance() != null;
    }

    // ----------------------------------------------------------- your microphone

    /**
     * Microphone mode. Open-mic (voice activation) transmits automatically when you
     * speak; when off, the mic stays closed and only push-to-talk opens it.
     */
    public static void setOpenMic(boolean on) {
        openMic = on;
        applyMic();
    }

    public static boolean isOpenMic() {
        return openMic;
    }

    /** Hold-to-talk: open the mic while the push-to-talk key is held. Wire to
     *  {@link #kb_ptt} at the client's key-event call sites. */
    public static void setPushToTalk(boolean down) {
        pttHeld = down;
        applyMic();
    }

    /** Hard-mute your microphone (others stop hearing you), independent of the mic mode. */
    public static void setMicMuted(boolean muted) {
        micMuted = muted;
        apply(v -> v.setMicMuted(muted));
    }

    public static boolean isMicMuted() {
        return micMuted;
    }

    /** Open-mic sensitivity as an RMS threshold; lower = more sensitive. */
    public static void setMicSensitivity(double rms) {
        micSensitivity = rms;
        apply(v -> v.setVadThresholdRms(rms));
    }

    public static double micSensitivity() {
        return micSensitivity;
    }

    // ----------------------------------------------------------- what you hear

    /** Silence all incoming audio while staying connected. */
    public static void setDeafened(boolean on) {
        deafened = on;
        apply(v -> v.setDeafened(on));
    }

    public static boolean isDeafened() {
        return deafened;
    }

    /** Master playback volume over everyone, 0..4 (1 = unity). */
    public static void setMasterVolume(float volume) {
        masterVolume = clampGain(volume);
        apply(v -> v.setMasterGain(masterVolume));
    }

    public static float masterVolume() {
        return masterVolume;
    }

    // ----------------------------------------------------------- per player

    /** Whether this player's voice is muted for you locally. */
    public static boolean isPlayerMuted(long gob) {
        return localMuted.contains(gob);
    }

    /** Mute/unmute one player's voice locally. */
    public static void setPlayerMuted(long gob, boolean muted) {
        if (muted) {
            localMuted.add(gob);
        } else {
            localMuted.remove(gob);
        }
        apply(v -> v.setLocalMute(gob, muted));
    }

    public static void togglePlayerMuted(long gob) {
        setPlayerMuted(gob, !isPlayerMuted(gob));
    }

    /** Per-player playback volume, 0..4 (1 = unity). */
    public static void setPlayerVolume(long gob, float volume) {
        float g = clampGain(volume);
        playerVolumes.put(gob, g);
        apply(v -> v.setVolume(gob, g));
    }

    public static float playerVolume(long gob) {
        Float g = playerVolumes.get(gob);
        return (g == null) ? 1f : g;
    }

    // ----------------------------------------------------------- who hears whom

    /** Gobs whose voice you receive right now (proximity + live). */
    public static Set<Long> audible() {
        BrodgarVoice v = instance();
        return (v == null) ? Collections.emptySet() : v.audibleGobs();
    }

    /** Gobs the server delivers your voice to right now — i.e. who can hear you. */
    public static Set<Long> heardBy() {
        BrodgarVoice v = instance();
        return (v == null) ? Collections.emptySet() : v.heardByGobs();
    }

    /** Whether this player is currently receiving your voice. */
    public static boolean canHearMe(long gob) {
        return heardBy().contains(gob);
    }

    /**
     * Whether the given gob is speaking right now — works for every player,
     * including the local one. Cheap enough to call per gob every frame for a
     * "talking" overlay. Returns {@code false} when there is no live session.
     */
    public static boolean isSpeaking(long gob) {
        Bridge s = session;
        BrodgarVoice v = (s == null) ? null : s.voice;
        if (v == null) {
            return false;
        }
        return (gob == s.mv.plgob) ? v.isLocalSpeaking() : v.isSpeaking(gob);
    }

    /** Every gob speaking right now, including the local player. For list-style UIs. */
    public static Set<Long> speakingGobs() {
        Bridge s = session;
        BrodgarVoice v = (s == null) ? null : s.voice;
        if (v == null) {
            return Collections.emptySet();
        }
        Set<Long> out = new HashSet<>(v.speakingGobs());
        long me = s.mv.plgob;
        if (me != -1 && v.isLocalSpeaking()) {
            out.add(me);
        }
        return out;
    }

    // ----------------------------------------------------------- events

    /** Register a listener for speaking / audible-set / heard-by / connection / error events. */
    public static void addListener(VoiceListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            apply(v -> v.addListener(l));
        }
    }

    public static void removeListener(VoiceListener l) {
        listeners.remove(l);
        apply(v -> v.removeListener(l));
    }

    // ----------------------------------------------------------- internals

    private static BrodgarVoice instance() {
        Bridge s = session;
        return (s == null) ? null : s.voice;
    }

    /** Run an action against the live session, if any. */
    private static void apply(Consumer<BrodgarVoice> action) {
        BrodgarVoice v = instance();
        if (v != null) {
            action.accept(v);
        }
    }

    private static void applyMic() {
        apply(v -> {
            v.setVadEnabled(openMic);
            v.setTransmitting(openMic || pttHeld);
        });
    }

    /** Push every desired setting onto a freshly connected session. */
    private static void applyAll(BrodgarVoice v) {
        v.setVadEnabled(openMic);
        v.setTransmitting(openMic || pttHeld);
        v.setMicMuted(micMuted);
        v.setVadThresholdRms(micSensitivity);
        v.setDeafened(deafened);
        v.setMasterGain(masterVolume);
        for (long g : localMuted) {
            v.setLocalMute(g, true);
        }
        for (Map.Entry<Long, Float> e : playerVolumes.entrySet()) {
            v.setVolume(e.getKey(), e.getValue());
        }
        for (VoiceListener l : listeners) {
            v.addListener(l);
        }
    }

    private static float clampGain(float v) {
        return Math.max(0f, Math.min(4f, v));
    }

    private static void start() { // caller holds the class monitor
        if (enabled && view != null && session == null) {
            session = new Bridge(view);
        }
    }

    private static void stop() { // caller holds the class monitor
        Bridge s = session;
        session = null;
        if (s != null) {
            closeAsync(s);
        }
    }

    private static void closeAsync(Bridge b) {
        // Never block the game thread on teardown (WS close + thread joins).
        Thread t = new Thread(b::close, "brodgar-close");
        t.setDaemon(true);
        t.start();
    }

    private static final class Bridge implements BrodgarVoiceHost {
        final MapView mv;
        volatile BrodgarVoice voice;
        private volatile boolean closed;
        private volatile Consumer<MovementIntent> intentSink;

        Bridge(MapView mv) {
            this.mv = mv;
            Thread t = new Thread(this::connect, "brodgar-connect");
            t.setDaemon(true);
            t.start();
        }

        private void connect() {
            BrodgarVoice v;
            try {
                VoiceConfig.Builder cfg = VoiceConfig.builder().serverUri(SERVER).clientInfo("hafen+brodgar");
                v = BrodgarVoice.connect(cfg.build(), this);
            } catch (Exception e) {
                log.log(Level.WARNING, "brodgar voice could not connect (game unaffected): " + e.getMessage());
                return;
            }
            synchronized (this) {
                if (closed) {
                    v.close(); // detached while still connecting — close it, don't leak
                    return;
                }
                this.voice = v;
            }
            applyAll(v); // push every remembered setting onto the new session
            log.info("brodgar voice connected to " + SERVER);
        }

        void close() {
            BrodgarVoice v;
            synchronized (this) {
                closed = true;
                v = this.voice;
                this.voice = null;
            }
            if (v != null) {
                v.close();
            }
        }

        void onMove(Coord2d dest) {
            Consumer<MovementIntent> sink = intentSink;
            if (sink == null || dest == null) {
                return;
            }
            Gob me = mv.player();
            if (me == null || me.rc == null) {
                return;
            }
            Coord2d v = dest.sub(me.rc).div(MCache.tilesz); // relative destination, tiles
            sink.accept(new MovementIntent(System.currentTimeMillis(), v.x, v.y));
        }

        // ------------------------------------------------------- BrodgarVoiceHost

        @Override
        public long localGobId() {
            return mv.plgob; // already -1 when there is no character
        }

        @Override
        public List<VisibleGob> visiblePlayers() {
            Gob me = mv.player();
            if (me == null || me.rc == null) {
                return Collections.emptyList();
            }
            Coord2d origin = me.rc;
            long meId = me.id;
            OCache oc = me.glob.oc;

            // Snapshot each gob's id + position while holding the OCache lock (its
            // iterator is not synchronized); classify and project afterwards, outside
            // the lock, which the render thread also takes.
            List<Snap> snaps = new ArrayList<>();
            synchronized (oc) {
                for (Gob g : oc) {
                    if (g.id == meId || g.rc == null) {
                        continue;
                    }
                    snaps.add(new Snap(g, g.rc));
                }
            }

            // Then classify + project without the lock.
            List<VisibleGob> out = new ArrayList<>();
            Map<Long, Vec> spatial = new HashMap<>();
            for (Snap s : snaps) {
                if (!isPlayer(s.g)) {
                    continue;
                }
                // WORLD vector for the server (axis-aligned, shared by all clients);
                // never rotate this.
                Coord2d v = s.rc.sub(origin).div(MCache.tilesz);
                out.add(new VisibleGob(s.g.id, v.x, v.y));
                // Screen-relative vector for LOCAL panning, so audio matches what you
                // see even when the camera is rotated. v.abs() = distance in tiles
                // (Haven's Coord2d.norm() returns a unit vector, not length).
                spatial.put(s.g.id, screenVector(s.rc, v.abs()));
            }
            BrodgarVoice voice = this.voice;
            if (voice != null) {
                voice.setSpatialVectors(spatial);
            }
            return out;
        }

        /** Minimal snapshot of a gob (id + position) taken under the OCache lock. */
        private static final class Snap {
            final Gob g;
            final Coord2d rc;

            Snap(Gob g, Coord2d rc) {
                this.g = g;
                this.rc = rc;
            }
        }

        /**
         * Listener-relative spatial vector: the gob's on-screen direction (from the
         * camera projection, so it accounts for camera rotation) scaled by the true
         * world distance. {@code screenangle} returns {@code atan2(screenUp,
         * screenRight)}, so {@code cos} is the right-ward component.
         */
        private Vec screenVector(Coord2d rc, double distTiles) {
            double sa;
            try {
                sa = mv.screenangle(rc, false);
            } catch (RuntimeException notReadyYet) {
                sa = Double.NaN;
            }
            if (Double.isNaN(sa)) {
                return new Vec(0, distTiles); // unknown heading → centered, keep distance
            }
            return new Vec(distTiles * Math.cos(sa), distTiles * Math.sin(sa)); // +x = screen right
        }

        @Override
        public void setMovementIntentSink(Consumer<MovementIntent> sink) {
            this.intentSink = sink;
        }
    }

    /** A gob is a human player when its base sprite is the borka body. */
    private static boolean isPlayer(Gob g) {
        Drawable d = g.getattr(Drawable.class);
        if (d == null) {
            return false;
        }
        Resource res;
        try {
            res = d.getres();
        } catch (Loading stillStreaming) {
            return false;
        }
        return res != null && "gfx/borka/body".equals(res.name);
    }
}
