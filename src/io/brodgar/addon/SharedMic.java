package io.brodgar.addon;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.VoiceException;
import io.brodgar.voice.audio.AudioSource;
import io.brodgar.voice.audio.MicSource;

/**
 * <b>The one microphone</b> (143.1) — the engine's {@link MicSource}, opened by the first voice link to
 * connect and closed by the last to end, and fed to every link between. A capture device is one thing on
 * most platforms (a second open of the same line is refused everywhere but Windows), and {@code hafen.voice()}
 * is a collection of links, so the capture is shared and each link is handed a {@link Feed}: an
 * {@link AudioSource} of its own that the engine's transmit pipeline drains at its own pace, with its own
 * VAD, gain and {@code isLocalSpeaking} downstream of it.
 *
 * <p><b>Ref-counted by link, not by feed.</b> {@link #acquire} runs on the pool thread <i>before</i> the
 * engine is built, so a microphone that will not open is a {@code VoiceException} the connect path turns into
 * one {@code Error} naming it — rather than the engine's own path, where the pipeline thread reports the
 * same failure a moment later as a non-fatal error nobody asked for. {@link #release} runs after that link's
 * engine has closed, or after its connect failed; the feed attaches and detaches inside that window, from
 * the engine's own threads, and holds nothing.
 *
 * <p><b>A feed is a bounded, dropping queue.</b> The capture thread offers each frame to every attached feed
 * and never waits: a link whose pipeline stalls loses its own frames, and the microphone and every other
 * link go on. {@link Feed#read} blocks as the engine requires (it is the pipeline's pacing clock), but only
 * for a bounded slice, answering {@code 0} — <i>no frame yet</i>, which the pipeline treats as a moment to
 * wait — so a device that stops producing can still be closed under it.
 *
 * <p>All members static; not instantiable.
 */
final class SharedMic {
    private SharedMic() {}

    /** Frames a feed holds for a pipeline that has fallen behind, before the oldest is dropped: 8 × 20 ms. */
    private static final int FEED_FRAMES = 8;
    /** How long {@link Feed#read} waits for a frame before answering {@code 0} — a slice, so a close is felt. */
    private static final long READ_WAIT_MS = 100L;

    /** The open device, or {@code null} between the last release and the next acquire. Guarded by the class monitor. */
    private static MicSource mic;
    /** Links holding the device open. Guarded by the class monitor. */
    private static int holders;
    /** The feeds the capture thread offers to. Concurrent: attached and detached from engine threads. */
    private static final List<Feed> feeds = new CopyOnWriteArrayList<Feed>();

    /**
     * One more link holds the microphone: opened here on the first, with the failure the device reports
     * ({@code cannot open microphone at 48kHz mono 16-bit: ...}) thrown for the connect path to report.
     * Pool thread.
     */
    static synchronized void acquire() throws VoiceException {
        if(mic == null) {                 // the first holder, or the first after the device ended on its own
            MicSource m = new MicSource();
            m.start();
            mic = m;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    pump(m);
                }
            }, "voice-mic");
            t.setDaemon(true);
            t.start();
        }
        holders++;
    }

    /** One link fewer: the device closes with the last, which unblocks and ends the capture thread. */
    static synchronized void release() {
        if(holders == 0)
            return;
        if(--holders == 0) {
            MicSource m = mic;
            mic = null;
            if(m != null)
                m.close();            // a blocked read answers -1 or throws on the closed line: pump ends
        }
    }

    /** A fresh feed for one link — handed to the engine as its {@code audioSource}, attached when it starts it. */
    static Feed feed() {
        return new Feed();
    }

    /**
     * The capture loop: one frame from the device, offered to every attached feed as a copy of its own.
     * Ends when the device closes or fails; a failure ends every attached feed too, so each link's pipeline
     * reports {@code audio source ended} through its own listener rather than waiting on a queue nothing
     * fills.
     */
    private static void pump(MicSource m) {
        short[] frame = new short[Protocol.FRAME_SAMPLES];
        boolean failed = false;
        try {
            while(true) {
                int n;
                try {
                    n = m.read(frame);
                } catch(VoiceException e) {
                    n = -1;
                }
                if(n < 0)
                    break;
                if(n == 0)
                    continue;
                for(Feed f : feeds)
                    f.offer(frame);
            }
        } catch(RuntimeException e) {
            failed = true;
        }
        synchronized(SharedMic.class) {
            if(mic == m) {             // the device ended on its own: nothing released it, and the next
                failed = true;         // acquire opens it again rather than counting on a dead line
                mic = null;
                m.close();
            }
        }
        if(failed) {
            for(Feed f : feeds)
                f.end();
        }
    }

    /**
     * One link's share of the capture: attached to the fan-out by the engine's {@code start()}, detached by
     * its {@code close()}. Frames are copied in, so a feed never sees the pump's buffer change under it.
     */
    static final class Feed implements AudioSource {
        private final ArrayBlockingQueue<short[]> frames = new ArrayBlockingQueue<short[]>(FEED_FRAMES);
        private volatile boolean closed;

        public void start() throws VoiceException {
            synchronized(SharedMic.class) {
                if(mic == null)
                    throw new VoiceException("microphone is not open");   // unreachable: acquire() precedes the engine
            }
            feeds.add(this);
        }

        /** Offer one frame; the oldest waiting frame goes when the pipeline behind this feed has fallen behind. */
        void offer(short[] frame) {
            if(closed)
                return;
            short[] copy = frame.clone();
            while(!frames.offer(copy))
                frames.poll();
        }

        /** The device ended: the next read answers -1, which the pipeline reports as the source ending. */
        void end() {
            closed = true;
            feeds.remove(this);
        }

        public int read(short[] frame) throws VoiceException {
            short[] next;
            try {
                next = frames.poll(READ_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if(next == null)
                return closed ? -1 : 0;
            System.arraycopy(next, 0, frame, 0, Math.min(next.length, frame.length));
            return frame.length;
        }

        public void close() {
            closed = true;
            feeds.remove(this);
            frames.clear();
        }
    }
}
