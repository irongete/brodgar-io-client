package io.brodgar.voice.internal;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.ProtocolException;
import io.brodgar.voice.crypto.AeadChannel;
import io.brodgar.voice.wire.UdpPackets;
import io.brodgar.voice.VoiceException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * UDP leg of a session: sends AEAD-sealed pings (address binding + RTT) and
 * audio frames, receives sealed forwarded audio on a dedicated thread and opens
 * it with the session channel.
 */
public final class UdpClient implements AutoCloseable {

    private final DatagramSocket socket;
    private final byte[] token;
    private final AeadChannel channel;
    private final Consumer<UdpPackets.ForwardedInner> audioHandler;
    private final LongConsumer rttHandler;
    private final Thread rxThread;

    private volatile boolean running = true;
    private volatile long lastPongNanos = Long.MIN_VALUE;
    private volatile long lastRttNanos = -1;
    private volatile long audioPacketsReceived;

    public UdpClient(InetSocketAddress serverAddr, byte[] token, AeadChannel channel,
                     Consumer<UdpPackets.ForwardedInner> audioHandler,
                     LongConsumer rttHandler) throws VoiceException {
        this.token = token.clone();
        this.channel = channel;
        this.audioHandler = audioHandler;
        this.rttHandler = rttHandler;
        try {
            this.socket = new DatagramSocket();
            // connect() filters datagrams from other sources at the OS level.
            this.socket.connect(serverAddr);
        } catch (SocketException e) {
            throw new VoiceException("cannot open UDP socket: " + e.getMessage(), e);
        }
        this.rxThread = new Thread(this::receiveLoop, "bv-udp-rx");
        this.rxThread.setDaemon(true);
        this.rxThread.start();
    }

    public void sendPing() {
        byte[] sealed = channel.seal(UdpPackets.encodeTimestampInner(System.nanoTime()));
        sendRaw(UdpPackets.clientFrame(Protocol.PT_PING, token, sealed));
    }

    public void sendAudio(int seq, int flags, byte[] opus, int opusLen) {
        byte[] sealed = channel.seal(UdpPackets.encodeAudioInner(seq, flags, opus, opusLen));
        sendRaw(UdpPackets.clientFrame(Protocol.PT_AUDIO, token, sealed));
    }

    private void sendRaw(byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length));
        } catch (IOException e) {
            // Transient UDP send failures are ignored; outages surface via the
            // presence channel.
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[Protocol.MAX_DATAGRAM_BYTES + 1];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(pkt);
            } catch (IOException e) {
                if (running) {
                    continue;
                }
                return;
            }
            handleDatagram(buf, pkt.getLength());
        }
    }

    private void handleDatagram(byte[] buf, int len) {
        UdpPackets.ServerFrame frame;
        try {
            frame = UdpPackets.parseServerFrame(buf, len);
        } catch (ProtocolException e) {
            return; // not a well-formed server frame
        }
        byte[] inner;
        try {
            inner = channel.open(frame.sealed);
        } catch (GeneralSecurityException e) {
            return; // forged or corrupt: drop
        }
        try {
            if (frame.type == Protocol.PT_AUDIO_FWD) {
                audioPacketsReceived++;
                audioHandler.accept(UdpPackets.decodeForwardedInner(inner));
            } else if (frame.type == Protocol.PT_PONG) {
                long echoed = UdpPackets.decodeTimestampInner(inner);
                long rtt = System.nanoTime() - echoed;
                lastPongNanos = System.nanoTime();
                lastRttNanos = rtt;
                rttHandler.accept(rtt);
            }
        } catch (ProtocolException e) {
            // Authenticated but malformed inner payload; ignore.
        }
    }

    /** True once the server has echoed at least one ping (address bound). */
    public boolean isBound() {
        return lastPongNanos != Long.MIN_VALUE;
    }

    /** Last measured round-trip to the relay in nanos, or -1. */
    public long lastRttNanos() {
        return lastRttNanos;
    }

    /** Total forwarded-audio packets received from the relay (pre-gating). */
    public long audioPacketsReceived() {
        return audioPacketsReceived;
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        try {
            rxThread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
