package io.brodgar.voice.internal;

import java.util.TreeMap;

/**
 * Per-sender reorder/dejitter buffer over the unsigned-16-bit packet sequence.
 * Pure logic, no clocks: {@code put} is called by the UDP receive thread,
 * {@code poll} once per 20 ms tick by the mixer thread.
 *
 * <p>States: PRIMING (accumulate {@code prefill} frames before playing) and
 * PLAYING (emit exactly one slot per poll: a frame, or {@code null} for a
 * lost/late slot). After {@code resetAfterEmptyPolls} consecutive polls with
 * nothing buffered the buffer re-primes — that is what separates talk spurts.
 */
public final class JitterBuffer {

    /** One polled slot: the wire seq that was due and its frame (null = loss). */
    public static final class Slot {
        public final int seq16;
        public final byte[] frame;
        /** When {@code frame == null} (a loss) and the immediately following packet
         *  is buffered, the frame carrying this slot's in-band FEC copy; else null. */
        public final byte[] fecFrame;

        Slot(int seq16, byte[] frame, byte[] fecFrame) {
            this.seq16 = seq16;
            this.frame = frame;
            this.fecFrame = fecFrame;
        }
    }

    private final int prefill;
    private final int maxDepth;
    private final int resetAfterEmptyPolls;

    private final TreeMap<Long, byte[]> frames = new TreeMap<>();
    private long lastUnwrapped = -1;
    private long nextPlay = -1;
    private boolean priming = true;
    private int emptyPolls;

    private long lateDropped;
    private long lost;
    private long fastForwards;

    public JitterBuffer(int prefill, int maxDepth) {
        this(prefill, maxDepth, 10);
    }

    public JitterBuffer(int prefill, int maxDepth, int resetAfterEmptyPolls) {
        if (prefill < 1 || maxDepth < prefill) {
            throw new IllegalArgumentException("bad jitter bounds");
        }
        this.prefill = prefill;
        this.maxDepth = maxDepth;
        this.resetAfterEmptyPolls = resetAfterEmptyPolls;
    }

    public synchronized void put(int seq16, byte[] frame, boolean spurtStart) {
        if (spurtStart && frames.isEmpty()) {
            // Fresh talk spurt: forget the old timeline entirely.
            resetLocked();
        }
        long unwrapped = unwrap(seq16);
        if (!priming && unwrapped < nextPlay) {
            lateDropped++;
            return;
        }
        frames.put(unwrapped, frame);
        if (unwrapped > lastUnwrapped) {
            lastUnwrapped = unwrapped;
        }
        if (priming && frames.size() >= prefill) {
            priming = false;
            nextPlay = frames.firstKey();
        }
        while (frames.size() > maxDepth) {
            // Overflow (receiver stalled or burst): fast-forward to fresh audio.
            frames.pollFirstEntry();
            nextPlay = frames.firstKey();
            fastForwards++;
        }
    }

    /** @return the slot due this tick, or {@code null} when there is nothing to play. */
    public synchronized Slot poll() {
        if (priming) {
            return null;
        }
        if (frames.isEmpty()) {
            if (++emptyPolls >= resetAfterEmptyPolls) {
                resetLocked();
            }
            return null;
        }
        emptyPolls = 0;
        long first = frames.firstKey();
        long due = nextPlay;
        nextPlay++;
        if (first == due) {
            return new Slot((int) (due & 0xFFFF), frames.pollFirstEntry().getValue(), null);
        }
        // first > due: the due packet is missing -> loss slot, buffer keeps waiting.
        // If the very next packet (due+1) is buffered, it carries in-band FEC for it.
        lost++;
        byte[] fec = (first == due + 1) ? frames.get(first) : null;
        return new Slot((int) (due & 0xFFFF), null, fec);
    }

    private void resetLocked() {
        frames.clear();
        priming = true;
        nextPlay = -1;
        lastUnwrapped = -1;
        emptyPolls = 0;
    }

    private long unwrap(int seq16) {
        if (lastUnwrapped < 0) {
            return seq16;
        }
        long base = lastUnwrapped;
        long delta = (seq16 - (base & 0xFFFF)) & 0xFFFF;
        if (delta > 0x8000) {
            delta -= 0x10000;
        }
        return base + delta;
    }

    public synchronized int depth() {
        return frames.size();
    }

    public synchronized boolean isPriming() {
        return priming;
    }

    public synchronized long lateDropped() {
        return lateDropped;
    }

    public synchronized long lost() {
        return lost;
    }

    public synchronized long fastForwards() {
        return fastForwards;
    }
}
