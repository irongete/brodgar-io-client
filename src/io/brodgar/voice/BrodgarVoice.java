package io.brodgar.voice;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.msg.Bye;
import io.brodgar.voice.msg.EdgesUpdate;
import io.brodgar.voice.msg.ErrorMessage;
import io.brodgar.voice.msg.ServerWelcome;
import io.brodgar.voice.util.Hex;
import io.brodgar.voice.audio.AudioSink;
import io.brodgar.voice.audio.AudioSource;
import io.brodgar.voice.audio.MicSource;
import io.brodgar.voice.audio.SpeakerSink;
import io.brodgar.voice.internal.PresenceClient;
import io.brodgar.voice.internal.ReportLoop;
import io.brodgar.voice.internal.RxMixer;
import io.brodgar.voice.internal.TxPipeline;
import io.brodgar.voice.internal.UdpClient;
import io.brodgar.voice.Vec;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point of the Brodgar voice library. One instance = one voice session.
 *
 * <pre>
 * BrodgarVoice voice = BrodgarVoice.connect(config, host);
 * voice.addListener(...);
 * voice.setTransmitting(true);   // push-to-talk down
 * ...
 * voice.close();
 * </pre>
 *
 * <p>Nothing about world positions ever leaves this process except relative
 * vectors inside presence reports; spatialization happens purely
 * client-side.
 */
public final class BrodgarVoice implements AutoCloseable {

    private final VoiceConfig cfg;
    private final BrodgarVoiceHost host;
    private final CopyOnWriteArrayList<VoiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService events;
    private final AtomicBoolean closed = new AtomicBoolean();

    // Network session (rebuilt on every (re)connect).
    private volatile PresenceClient presence;
    private volatile UdpClient udp;
    private volatile ReportLoop reportLoop;
    private URI serverUri;

    // Audio pipeline (opened once, kept alive across reconnects).
    private RxMixer mixer;
    private TxPipeline tx;
    private AudioSource source;

    // Reconnect state, all touched only via the single-thread reconnect executor
    // or guarded by the session generation.
    private final java.util.concurrent.ScheduledExecutorService reconnectExec;
    private volatile int sessionGen = 0;
    private volatile boolean permanentFailure = false;
    private volatile boolean reconnectScheduled = false;
    private volatile boolean sessionUp = false;
    private volatile long backoffMs;

    private volatile Set<Long> audible = Collections.emptySet();
    private volatile Set<Long> heardBy = Collections.emptySet();
    /** Set once the host supplies its own listener-relative spatial vectors. */
    private volatile boolean clientSpatial = false;

    private BrodgarVoice(VoiceConfig cfg, BrodgarVoiceHost host) {
        this.cfg = cfg;
        this.host = host;
        this.backoffMs = cfg.reconnectMinBackoffMs();
        this.events = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bv-events");
            t.setDaemon(true);
            return t;
        });
        this.reconnectExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bv-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /** Connects, performs the handshake and starts the audio pipelines. Blocking. */
    public static BrodgarVoice connect(VoiceConfig cfg, BrodgarVoiceHost host) throws VoiceException {
        BrodgarVoice v = new BrodgarVoice(cfg, host);
        try {
            v.doConnect();
            return v;
        } catch (VoiceException | RuntimeException e) {
            v.close();
            throw e;
        }
    }

    private void doConnect() throws VoiceException {
        this.serverUri = PresenceClient.resolveUri(cfg.serverUri());
        openAudio();
        openSession(); // first attempt; throws on failure
    }

    /** Opens the local audio pipeline once; it stays alive across reconnects. */
    private void openAudio() {
        AudioSink sink = cfg.audioSink() != null ? cfg.audioSink() : new SpeakerSink();
        source = cfg.audioSource() != null ? cfg.audioSource() : new MicSource();

        mixer = new RxMixer(sink, cfg.jitterPrefillFrames(), cfg.jitterMaxFrames(), cfg.debugHooks(),
                cfg.spatialAudio(), cfg.spatialNearTiles(), cfg.spatialMinGain(),
                (gob, speaking) -> fire(l -> l.onSpeaking(gob, speaking)),
                this::fireErrorNonFatal);
        mixer.start();

        tx = new TxPipeline(source, cfg.bitrate(), cfg.complexity(), cfg.debugHooks(),
                cfg.vad(), cfg.vadThresholdRms(), cfg.vadHangoverMs(), cfg.agc(),
                this::fireErrorNonFatal);
        tx.start();

        // Registered once; forwards to whichever report loop is current.
        host.setMovementIntentSink(this::offerIntent);
    }

    /** (Re)establish the presence + relay session and wire it to the audio. */
    private void openSession() throws VoiceException {
        final int gen = sessionGen;
        PresenceClient p = PresenceClient.connect(serverUri, cfg.clientInfo(),
                cfg.connectTimeoutMs(), new PresenceClient.Events() {
                    @Override
                    public void onEdges(EdgesUpdate edges) {
                        if (gen == sessionGen) {
                            handleEdges(edges);
                        }
                    }

                    @Override
                    public void onServerError(ErrorMessage error) {
                        if (gen != sessionGen) {
                            return;
                        }
                        if (isPermanent(error.code())) {
                            permanentFailure = true;
                        }
                        fireError(error.code(), error.message(),
                                error.fatal() || isPermanent(error.code()));
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        handleDisconnect(gen, reason);
                    }
                });

        ServerWelcome welcome = p.welcome();
        if (welcome.sampleRate() != Protocol.SAMPLE_RATE
                || welcome.channels() != Protocol.CHANNELS
                || welcome.frameMs() != Protocol.FRAME_MS) {
            p.close();
            throw new VoiceException("server audio profile not supported: "
                    + welcome.sampleRate() + "Hz/" + welcome.channels() + "ch/" + welcome.frameMs() + "ms");
        }
        InetSocketAddress relayAddr = new InetSocketAddress(welcome.udpHost(), welcome.udpPort());
        if (relayAddr.isUnresolved()) {
            p.close();
            throw new VoiceException("cannot resolve relay host: " + welcome.udpHost());
        }
        UdpClient u = new UdpClient(relayAddr, Hex.decode(welcome.udpTokenHex()),
                p.sessionKeys().clientChannel(),
                pkt -> mixer.enqueue(pkt),
                rtt -> cfg.debugHooks().onUdpRtt(rtt));

        this.presence = p;
        this.udp = u;
        tx.setUdp(u);
        this.reportLoop = new ReportLoop(host, p, u, cfg.reportIntervalMs(), this::fireErrorNonFatal,
                this::onWorldVectors);

        if (closed.get()) {
            // close() ran concurrently before these fields were set; tear the new
            // session down here.
            closeSession();
            return;
        }
        sessionUp = true;
        fire(l -> l.onConnectionState(true));
    }

    private void offerIntent(io.brodgar.voice.MovementIntent intent) {
        ReportLoop rl = reportLoop;
        if (rl != null) {
            rl.offerIntent(intent);
        }
    }

    /** Server errors that must not trigger a reconnect. */
    private static boolean isPermanent(String code) {
        return Protocol.ERR_PROTO_MISMATCH.equals(code)
                || Protocol.ERR_SERVER_FULL.equals(code);
    }

    private void handleDisconnect(int gen, String reason) {
        if (gen != sessionGen || closed.get()) {
            return; // stale session, or a user-initiated close
        }
        if (!cfg.autoReconnect() || permanentFailure) {
            fireError("disconnected", reason, true);
            return;
        }
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        if (sessionUp) {
            sessionUp = false;
            audible = Collections.emptySet();
            heardBy = Collections.emptySet();
            fire(l -> l.onConnectionState(false));
            fire(l -> l.onAudibleSetChanged(Collections.emptySet()));
            fire(l -> l.onHeardByChanged(Collections.emptySet()));
        }
        if (reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        fire(l -> l.onError("reconnecting", "connection lost, retrying in " + backoffMs + "ms", false));
        try {
            reconnectExec.schedule(this::reconnectAttempt, backoffMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void reconnectAttempt() {
        reconnectScheduled = false;
        if (closed.get()) {
            return;
        }
        closeSession(); // drop the dead session (bumps the generation)
        try {
            openSession();
            backoffMs = cfg.reconnectMinBackoffMs();
        } catch (VoiceException | RuntimeException e) {
            backoffMs = Math.min(backoffMs * 2, cfg.reconnectMaxBackoffMs());
            scheduleReconnect();
        }
    }

    /**
     * Tear down the network session (presence + relay + report loop) while
     * leaving the audio pipeline running. Bumps the generation first so the
     * closing session's late callbacks are ignored.
     */
    private void closeSession() {
        sessionGen++;
        if (tx != null) {
            tx.setUdp(null);
        }
        ReportLoop rl = reportLoop;
        reportLoop = null;
        if (rl != null) {
            rl.close();
        }
        PresenceClient p = presence;
        presence = null;
        if (p != null) {
            p.close();
        }
        UdpClient u = udp;
        udp = null;
        if (u != null) {
            u.close();
        }
    }

    // ------------------------------------------------------------- public API

    /** Push-to-talk: while {@code true} the microphone is encoded and sent. */
    public void setTransmitting(boolean ptt) {
        if (tx != null) {
            tx.setTransmitting(ptt);
        }
    }

    public boolean isTransmitting() {
        return tx != null && tx.isTransmitting();
    }

    /** Hard mic mute, independent of push-to-talk: while muted, nothing is sent. */
    public void setMicMuted(boolean muted) {
        if (tx != null) {
            tx.setMicMuted(muted);
        }
    }

    public boolean isMicMuted() {
        return tx != null && tx.isMicMuted();
    }

    /** Client-side mute of one remote player (their audio is discarded locally). */
    public void setLocalMute(long gobId, boolean m) {
        if (mixer != null) {
            mixer.setMuted(gobId, m);
        }
    }

    /** Per-player playback gain, 0..4 (1 = unity). */
    public void setVolume(long gobId, float gain) {
        if (mixer != null) {
            mixer.setGain(gobId, gain);
        }
    }

    /** Master playback gain over every remote player, 0..4 (1 = unity). */
    public void setMasterGain(float gain) {
        if (mixer != null) {
            mixer.setMasterGain(gain);
        }
    }

    /** Silence all incoming audio ("deafen"); the local mic is unaffected. */
    public void setDeafened(boolean deafened) {
        if (mixer != null) {
            mixer.setDeafened(deafened);
        }
    }

    public boolean isDeafened() {
        return mixer != null && mixer.isDeafened();
    }

    /**
     * Switch voice mode. With VAD on, {@link #setTransmitting(boolean)}(true) is
     * open-mic gated by voice activity. With VAD off, only {@code setTransmitting}
     * transmits — bind it to a key for pure push-to-talk.
     */
    public void setVadEnabled(boolean on) {
        if (tx != null) {
            tx.setVadEnabled(on);
        }
    }

    public boolean isVadEnabled() {
        return tx != null && tx.isVadEnabled();
    }

    /** VAD sensitivity as an RMS threshold (~16-bit scale); lower = more sensitive. */
    public void setVadThresholdRms(double rms) {
        if (tx != null) {
            tx.setVadThresholdRms(rms);
        }
    }

    /** Toggle automatic gain control (transmit-path loudness normalization). */
    public void setAgcEnabled(boolean on) {
        if (tx != null) {
            tx.setAgcEnabled(on);
        }
    }

    public boolean isAgcEnabled() {
        return tx != null && tx.isAgcEnabled();
    }

    /**
     * Supply listener-relative spatial vectors for panning (gobId → vector where
     * +x is the listener's right, magnitude is distance in tiles). Use this when
     * the client's camera can rotate, so panning follows the screen; the vectors
     * reported to the server stay world-aligned regardless. Calling this switches
     * spatialization off the default world-vector feed.
     */
    public void setSpatialVectors(Map<Long, Vec> vectors) {
        clientSpatial = true;
        if (mixer != null) {
            mixer.setLocalVectors(vectors);
        }
    }

    /** Default spatialization feed: world vectors, used until the client overrides. */
    private void onWorldVectors(Map<Long, Vec> world) {
        if (!clientSpatial && mixer != null) {
            mixer.setLocalVectors(world);
        }
    }

    public void addListener(VoiceListener l) {
        listeners.add(l);
    }

    public void removeListener(VoiceListener l) {
        listeners.remove(l);
    }

    /** Gobs this client can currently hear. */
    public Set<Long> audibleGobs() {
        return audible;
    }

    /** Gobs whose owners can currently hear this client ("who can hear me"). */
    public Set<Long> heardByGobs() {
        return heardBy;
    }

    public long sessionId() {
        PresenceClient p = presence;
        return p == null ? -1 : p.welcome().sessionId();
    }

    /** Last measured UDP round-trip to the relay in nanoseconds, or -1. */
    public long udpRttNanos() {
        return udp == null ? -1 : udp.lastRttNanos();
    }

    /**
     * Forwarded-audio packets this client has received from the relay, counted
     * before any client-side gating — i.e. exactly what the relay sent this
     * client.
     */
    public long udpAudioPacketsReceived() {
        return udp == null ? 0 : udp.audioPacketsReceived();
    }

    public long framesSent() {
        return tx == null ? 0 : tx.framesSent();
    }

    public long framesMixed() {
        return mixer == null ? 0 : mixer.framesPlayed();
    }

    public int activeIncomingStreams() {
        return mixer == null ? 0 : mixer.activeStreams();
    }

    /**
     * Whether a specific remote gob is producing voice right now. Cheap enough to
     * call per-gob every frame — ideal for a "speaking" icon over the avatar. For
     * the local player's own gob use {@link #isLocalSpeaking()}.
     */
    public boolean isSpeaking(long gobId) {
        return mixer != null && mixer.isSpeaking(gobId);
    }

    /** Snapshot of the remote gobs currently speaking (excludes the local player). */
    public Set<Long> speakingGobs() {
        return mixer == null ? Collections.emptySet() : mixer.speakingGobs();
    }

    /** Whether the local player is transmitting voice right now (post PTT + VAD). */
    public boolean isLocalSpeaking() {
        return tx != null && tx.isSpeaking();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        reconnectExec.shutdownNow(); // cancel any pending/backing-off reconnect
        try {
            // Let any in-flight reconnect attempt finish; it sees closed==true and
            // tears down whatever it created.
            reconnectExec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Send Bye, then tear the session down.
        PresenceClient p = presence;
        if (p != null) {
            try {
                p.send(new Bye());
            } catch (VoiceException ignored) {
            }
        }
        closeSession();

        // Audio pipeline (closes the mic and speaker).
        if (tx != null) {
            tx.close();
        }
        if (mixer != null) {
            mixer.close();
        }
        events.shutdown();
        try {
            events.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------- internals

    private void handleEdges(EdgesUpdate edges) {
        Set<Long> hear = toSet(edges.hear());
        Set<Long> by = toSet(edges.heardBy());
        boolean hearChanged = !hear.equals(audible);
        boolean byChanged = !by.equals(heardBy);
        audible = hear;
        heardBy = by;
        mixer.setAudible(hear);
        if (hearChanged) {
            fire(l -> l.onAudibleSetChanged(hear));
        }
        if (byChanged) {
            fire(l -> l.onHeardByChanged(by));
        }
    }

    private static Set<Long> toSet(long[] values) {
        Set<Long> s = new HashSet<>();
        for (long v : values) {
            s.add(v);
        }
        return Collections.unmodifiableSet(s);
    }

    private void fireErrorNonFatal(String code, String message) {
        fireError(code, message, false);
    }

    private void fireError(String code, String message, boolean fatal) {
        fire(l -> l.onError(code, message == null ? "" : message, fatal));
    }

    private void fire(java.util.function.Consumer<VoiceListener> call) {
        try {
            events.execute(() -> {
                for (VoiceListener l : listeners) {
                    try {
                        call.accept(l);
                    } catch (Throwable ignored) {
                        // listener bugs must not kill the event thread
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }
}
