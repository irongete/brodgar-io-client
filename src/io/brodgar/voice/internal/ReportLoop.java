package io.brodgar.voice.internal;

import io.brodgar.voice.MovementIntent;
import io.brodgar.voice.Protocol;
import io.brodgar.voice.Vec;
import io.brodgar.voice.VisibleGob;
import io.brodgar.voice.msg.Report;
import io.brodgar.voice.BrodgarVoiceHost;
import io.brodgar.voice.VoiceException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Drives the periodic presence report from host-provided game state, and the
 * UDP keepalive pings that hold the NAT binding open (pinging fast until the
 * first pong, then every ~5 s).
 */
public final class ReportLoop implements AutoCloseable {

    private static final int INTENT_QUEUE_CAP = 32;

    private final BrodgarVoiceHost host;
    private final PresenceClient presence;
    private final UdpClient udp;
    private final BiConsumer<String, String> errorSink;
    private final Consumer<Map<Long, Vec>> localVectorSink;
    private final ScheduledExecutorService sched;

    private final ConcurrentLinkedQueue<MovementIntent> intents = new ConcurrentLinkedQueue<>();
    private final AtomicInteger intentCount = new AtomicInteger();

    private long seq;
    private int tick;
    private boolean sendFailedReported;

    public ReportLoop(BrodgarVoiceHost host, PresenceClient presence, UdpClient udp,
                      int intervalMs, BiConsumer<String, String> errorSink,
                      Consumer<Map<Long, Vec>> localVectorSink) {
        this.host = host;
        this.presence = presence;
        this.udp = udp;
        this.errorSink = errorSink;
        this.localVectorSink = localVectorSink;
        this.sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bv-report");
            t.setDaemon(true);
            return t;
        });
        udp.sendPing(); // bind the UDP address as early as possible
        sched.scheduleWithFixedDelay(this::safeTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Bounded queue fed by the host's movement-intent sink. */
    public void offerIntent(MovementIntent intent) {
        if (intent == null) {
            return;
        }
        if (intentCount.incrementAndGet() > INTENT_QUEUE_CAP) {
            intents.poll(); // drop oldest
            intentCount.decrementAndGet();
        }
        intents.add(intent);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            if (!sendFailedReported) {
                sendFailedReported = true;
                errorSink.accept("presence", "report loop error: " + t.getMessage());
            }
        }
    }

    private void tick() throws VoiceException {
        tick++;
        // Keepalive: every tick until bound, then every ~5 s.
        if (!udp.isBound() || tick % 10 == 0) {
            udp.sendPing();
        }

        long gob = host.localGobId();
        List<VisibleGob> visible = host.visiblePlayers();
        List<VisibleGob> capped = new ArrayList<>();
        if (visible != null) {
            for (VisibleGob g : visible) {
                if (g == null || g.gobId() <= 0 || g.gobId() == gob) {
                    continue;
                }
                capped.add(g);
                if (capped.size() >= Protocol.MAX_VISIBLE) {
                    break;
                }
            }
        }

        // Feed the mixer the current local vectors for spatialization. This map
        // never leaves the machine.
        Map<Long, Vec> vectors = new HashMap<>(capped.size());
        for (VisibleGob g : capped) {
            vectors.put(g.gobId(), g.vec());
        }
        localVectorSink.accept(vectors);

        List<MovementIntent> drained = new ArrayList<>();
        MovementIntent i;
        while (drained.size() < Protocol.MAX_INTENTS_PER_REPORT && (i = intents.poll()) != null) {
            intentCount.decrementAndGet();
            drained.add(i);
        }

        presence.send(new Report(seq++, System.currentTimeMillis(),
                gob <= 0 ? Report.NO_GOB : gob, capped, drained));
        sendFailedReported = false;
    }

    @Override
    public void close() {
        sched.shutdown();
        try {
            sched.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
