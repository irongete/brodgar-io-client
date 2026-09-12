package io.brodgar.addon;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.luaj.vm2.LuaValue;

/**
 * One {@code hafen.websocket()} connection (142.1) — the record behind the {@code Connection} handle, the
 * JDK transport under it, and the queues between the two. The sibling of {@link LuaHttpRequest} one door
 * along: bridge-owned, registered in {@link Addon#connections} from {@code :connect()} until its ending is
 * heard, torn down with the addon.
 *
 * <p><b>Built bare, opened on purpose</b> (the builder rule, 095's for a request). Every setter writes a
 * field here until {@code :connect()} and none after: {@link #dispatched} is written by that verb alone,
 * so there is no timing in the rule.
 *
 * <p><b>The transport is {@link java.net.http.WebSocket}</b>, built on {@link WebSocketApi#client()} with the
 * shared HTTP pool as its executor, and <b>the listener only enqueues</b>: it runs on a pool thread, which
 * holds no tree and may not enter Lua, so every edge it sees — the open, a message, a frame it refuses, the
 * peer's Close, an error — is a {@link Pending} on {@link #events}, and {@link WebSocketApi#drain} fires
 * them from the layer's step under the addon's own lock, <b>in the order they happened</b>. That is what
 * makes a handler run with {@code hafen.client():stepping()} true and every tree reachable, what makes a
 * connection alive from {@code Load} on with no login to drain it, and why the listener never writes
 * {@link #state}: a refusal it makes is an edge the drain applies behind the {@code Open} and the messages
 * that came before it, so an addon reads the connection's life in one order from one thread.
 *
 * <p><b>Sending is a queue of one</b> (142.2). The JDK takes one text send at a time and fails a second
 * while the first is in flight, so {@link #send} hands the wire one message and holds the rest in
 * {@link #outbox}, each dispatched by the completion of the one before it; {@link #pending} is what
 * {@code conn:pending()} reads and what {@link #MAX_PENDING} bounds. A {@code :close()} joins the same
 * queue rather than cutting it: its Close frame goes out behind every message the addon had already sent
 * ({@link #closeAfterPending}), because {@code send} then {@code close} means both.
 *
 * <p><b>Receiving is text, whole, and bounded.</b> The JDK hands a text message in parts; the listener
 * accumulates them until {@code last} and queues the whole. A binary frame or a message past
 * {@link #MAX_MESSAGE_BYTES} is the client's own refusal: the output is closed with {@code 1008} and the
 * pair recorded through the drain, so {@code Close} reports the client's code whatever the peer echoes
 * ({@link #refuse}). And the listener asks for one frame at a time — {@code request(1)} is an obligation,
 * and forgetting it stalls the socket — until {@link #INBOX_CAP} messages wait for a step that has not come,
 * where it stops asking and the drain asks again once it has fired them ({@link #starved}).
 *
 * <p><b>One ending, said once.</b> {@link #done} is set by the drain when it delivers {@code Close} or
 * {@code Error}, and nothing here fires after it: a peer's Close landing behind an error, an error behind a
 * close the addon asked for, a handshake completing after the connection was closed — each is queued and
 * ignored, and the socket it belongs to is aborted wherever it is first known. {@link #opened} is the other
 * half of that rule: a connection ended before its {@code Open} was delivered never sent a Close of its own,
 * so the socket under it is cut rather than closed politely.
 *
 * <p><b>Threading.</b> {@link #state}, {@link #ws} and the close pair are volatile: the Lua verbs and the
 * drain write them under the addon's lock, the handshake's completion writes {@link #ws} on a pool thread,
 * and the listener reads them. {@link #headers}, {@link #protocol} and {@link #timeout} are written only
 * before {@code :connect()} and read once, by the pool thread that runs the handshake, after the submit
 * that published them. The outbound queue is guarded by this record's own monitor, taken by the Lua thread
 * that adds and by whichever thread the JDK completes a send on; the inbound buffer belongs to the listener
 * alone, whose invocations the JDK serializes.
 */
final class LuaWebSocket {
    /** The keys a connection answers, in the order a refusal lists them — a closed set (D-129). */
    static final String[] KEYS = {"Open", "Message", "Close", "Error"};

    /** Live connections one addon may hold at once; the next {@code :connect()} is refused naming {@code :count()}. */
    static final int MAX_LIVE = 8;
    /** Milliseconds a {@code :close()} waits for the peer's Close before {@code abort()} ends the connection itself. */
    static final long CLOSE_DEADLINE_MS = 5_000L;
    /** The longest reason a Close frame carries, in UTF-8 bytes (RFC 6455 §5.5.1; the JDK refuses longer). */
    static final int MAX_REASON_BYTES = 123;
    /** The longest message either way, in UTF-8 bytes: one past it is refused at {@code :send(v)}, and one the peer sends closes the connection with {@code 1008}. */
    static final int MAX_MESSAGE_BYTES = 1_048_576;
    /** Messages {@code :send(v)} may have not yet on the wire; the next is refused naming {@code conn:pending()}. */
    static final int MAX_PENDING = 64;
    /** Messages the listener lets wait for the drain before it stops asking the socket for more. */
    static final int INBOX_CAP = 256;
    /** The Close code of the client's own refusals — the one the JDK lets a client send for a frame it will not take. */
    static final int POLICY_VIOLATION = 1008;

    /** Where a connection is in its life — the word {@code conn:state()} reads. */
    enum State {
        NEW("new"), CONNECTING("connecting"), OPEN("open"), CLOSING("closing"), CLOSED("closed");

        final String word;

        State(String word) {
            this.word = word;
        }
    }

    /**
     * One edge the listener saw, waiting for the drain: the key it fires as, and what the payload carries —
     * {@code Message} its text, {@code Close} its reason and code, {@code Error} its sentence, {@code Open}
     * nothing. {@code Refuse} is the one edge that fires as no key: it carries the {@code 1008} pair the
     * listener sent for a frame it would not take, and the drain applies it to the state in its turn.
     */
    static final class Pending {
        final String key;
        final String text;
        final int code;

        Pending(String key, String text, int code) {
            this.key = key;
            this.text = text;
            this.code = code;
        }
    }

    final Addon owner;
    /** The address as written, what {@code conn:url()} reads and the collection's string filter matches. */
    final String url;
    final URI uri;
    /** The <b>origin</b> the allowlist is asked about: the {@code https} server a {@code wss} address names. */
    final String origin;
    /** The handshake headers the addon set, in the order it set them, transport-owned ones already dropped. */
    final Map<String, String> headers = new LinkedHashMap<String, String>();
    /** The subprotocol asked for, or {@code null} — what {@code conn:protocol()} reads until the handshake. */
    String protocol;
    /** The handshake deadline in milliseconds; {@link LuaHttp#DEFAULT_TIMEOUT} unless set. */
    int timeout = LuaHttp.DEFAULT_TIMEOUT;
    /** {@code conn:on(key, fn)} — the one notification verb, over the four {@link #KEYS}. */
    final Subs subs;
    /** The Lua handle over this record — what {@code Open} hands over and {@code ev:connection()} answers. */
    LuaValue handle;

    volatile State state = State.NEW;
    /** The JDK socket, from the first of {@code onOpen} and the handshake's completion; {@code null} before. */
    volatile WebSocket ws;
    /** Has {@code :connect()} run? Written under the owner's lock; until it has, every setter is legal. */
    boolean dispatched;
    /** Has the drain delivered {@code Open}? A connection ended before that never sent a Close of its own. */
    volatile boolean opened;
    /** Has the drain delivered {@code Close} or {@code Error}? After it nothing here fires again. */
    volatile boolean done;
    /** What the listener queued and the drain has not yet fired, in the order it happened. */
    final Queue<Pending> events = new ConcurrentLinkedQueue<Pending>();
    /** The code {@code :close()} asked for, or {@code -1}: what {@code Close} reports whatever the peer echoes. */
    volatile int closeCode = -1;
    volatile String closeReason;
    /** System clock at which a {@code :close()} stops waiting for the peer's Close, or {@code 0}. */
    volatile long closeDeadline;
    /** The subprotocol the server agreed to, written at {@code onOpen}; {@code ""} for none. */
    volatile String agreed = "";

    // ---- the outbound queue (142.2), guarded by this record's monitor
    /** Messages {@code :send(v)} accepted and the wire has not taken, behind the one in flight. */
    private final ArrayDeque<String> outbox = new ArrayDeque<String>();
    /** Is a {@code sendText} in flight — its future not yet complete? While it is, the next waits in {@link #outbox}. */
    private boolean sending;
    /** The in-flight message plus {@link #outbox}: what {@code conn:pending()} reads and {@link #MAX_PENDING} bounds. */
    volatile int pending;
    /** A {@code :close()} asked for while sends were pending: its Close frame follows the last of them, or {@code -1}. */
    private int queuedCloseCode = -1;
    private String queuedCloseReason;

    // ---- the inbound side (142.2): the listener's own, whose invocations the JDK serializes
    /** The parts of the text message being received, until {@code last}. */
    private final StringBuilder inbound = new StringBuilder();
    /** UTF-8 bytes accumulated in {@link #inbound}, checked against {@link #MAX_MESSAGE_BYTES} part by part. */
    private int inboundBytes;
    /** Messages queued for the drain and not yet fired — the count {@link #INBOX_CAP} bounds. */
    final AtomicInteger inbox = new AtomicInteger();
    /** The listener stopped asking for frames because {@link #inbox} reached the cap; the drain asks again. */
    volatile boolean starved;
    /** The client refused a frame and told the peer {@code 1008}: what still arrives is dropped unread. */
    volatile boolean refused;

    LuaWebSocket(Addon owner, String url, URI uri, String origin) {
        this.owner = owner;
        this.url = url;
        this.uri = uri;
        this.origin = origin;
        this.subs = new Subs(owner, Addon.C_EVENT);
    }

    /** {@code tostring(conn)} — the address and where the connection is in its life. */
    public String toString() {
        return "Connection(" + url + ", " + state.word + ")";
    }

    /**
     * The listener the JDK calls on a pool thread. <b>It enters no Lua</b>: every method records what it saw
     * on {@link #events} and asks for the next frame, and the drain does the rest on the step. {@code onOpen}
     * is the first call and the one that hands the socket over, so the socket is known before {@code Open} is
     * ever fired; {@code onClose} and {@code onError} are terminal and exclusive, which is why one
     * {@link #done} covers both.
     *
     * <p><b>{@code request(1)} after every frame, or the socket stalls</b>: the JDK invokes a receive method
     * only while its counter is above zero, and {@code onClose} is a receive method too, so a listener that
     * stops asking never hears the peer's Close either. The ping and pong methods keep the interface's
     * defaults, which ask for one more; the text and binary ones ask below, except at the one moment the
     * listener means to stop — {@link #INBOX_CAP} messages waiting for a drain — and after a refusal the
     * frames keep coming and are dropped, so the peer's answer to the {@code 1008} still arrives.
     */
    final WebSocket.Listener listener = new WebSocket.Listener() {
        public void onOpen(WebSocket w) {
            ws = w;
            agreed = w.getSubprotocol();
            events.add(new Pending("Open", null, 0));
            w.request(1);
        }

        /**
         * A text message, or a part of one: the JDK delivers a message in the parts it read, {@code last}
         * marking the one that completes it, so the parts are joined here and the whole is queued once. The
         * cap is applied part by part and a message that crosses it is cut at that part — nothing waits for
         * the end of a message that is already refused.
         */
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            if(refused) {
                w.request(1);                               // drained unread: the peer's Close is behind it
                return null;
            }
            int bytes = utf8Length(data);
            if(inboundBytes + bytes > MAX_MESSAGE_BYTES) {
                refuse("message too large (over " + MAX_MESSAGE_BYTES + " bytes)");
                w.request(1);
                return null;
            }
            inbound.append(data);
            inboundBytes += bytes;
            if(!last) {
                w.request(1);
                return null;
            }
            String text = inbound.toString();
            inbound.setLength(0);
            inboundBytes = 0;
            events.add(new Pending("Message", text, 0));
            // The ask, unless INBOX_CAP messages already wait for a step that has not come: then this is the
            // one frame not asked for, and the drain asks once it has fired them (WebSocketApi.step).
            if(inbox.incrementAndGet() >= INBOX_CAP)
                starved = true;
            else
                w.request(1);
            return null;
        }

        /** A binary frame is what this connection does not take: the peer is told so, and the rest is dropped. */
        public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
            if(!refused)
                refuse("binary frame refused: this connection carries text only");
            w.request(1);
            return null;
        }

        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            events.add(new Pending("Close", reason, code));
            return null;             // the JDK echoes the Close at once, which is the whole of the protocol's ask
        }

        public void onError(WebSocket w, Throwable error) {
            events.add(new Pending("Error", describe(error), 0));
        }
    };

    /**
     * <b>The client's own refusal</b>, from the listener: the output is closed with {@code 1008} and
     * {@code why} now — the protocol's answer to a frame the endpoint will not take — and the pair is queued
     * for the drain to record behind the edges before it, so {@code Close} reports the client's code and
     * reason whatever the peer answers. The input stays open and is drained unread ({@link #refused}) until
     * the peer's Close, an error or the close deadline the drain sets when it applies the refusal; what the
     * addon still had queued to send is dropped there too.
     */
    private void refuse(String why) {
        refused = true;
        inbound.setLength(0);
        inboundBytes = 0;
        WebSocket w = ws;
        if(w != null)
            w.sendClose(POLICY_VIOLATION, why);   // fails silently when a :close() already closed the output
        events.add(new Pending("Refuse", why, POLICY_VIOLATION));
    }

    /**
     * The UTF-8 length of {@code s} without encoding it — what the wire carries for a message, and the number
     * both caps count. A paired surrogate is one four-byte character; an unpaired one is written as three,
     * which is what the encoder does with it.
     */
    static int utf8Length(CharSequence s) {
        int n = 0;
        for(int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if(c < 0x80) {
                n += 1;
            } else if(c < 0x800) {
                n += 2;
            } else if(Character.isHighSurrogate(c) && (i + 1 < len) && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    // ------------------------------------------------------------- sending

    /**
     * <b>Queue one text message</b> — {@code :send(v)}'s other half, under the addon's lock, the caps already
     * checked by the verb (only the Lua thread adds, so a count it read is still true here). The first goes
     * to the wire now; every later one waits its turn in {@link #outbox} and is dispatched by the completion
     * of the one before it, because the JDK fails a second {@code sendText} while one is in flight.
     */
    synchronized void send(String text) {
        pending++;
        if(sending) {
            outbox.add(text);
            return;
        }
        sending = true;
        dispatchSend(text);
    }

    /** Hand one message to the JDK and arrange for {@link #sent} to run when it has gone, or failed. */
    private void dispatchSend(String text) {
        WebSocket w = ws;
        if(w == null) {                    // unreachable from :send(v), which needs the open state the socket precedes
            sent(new IllegalStateException("no socket"));
            return;
        }
        w.sendText(text, true).whenComplete(new BiConsumer<WebSocket, Throwable>() {
            public void accept(WebSocket r, Throwable ex) {
                sent(ex);
            }
        });
    }

    /**
     * One send has completed — on whichever thread the JDK finished it, or this one when it finished at
     * once. The next message in {@link #outbox} goes out; when there is none and a {@code :close()} is
     * waiting behind the queue, its Close frame goes now. A failure drops the queue whole and ends the
     * connection with {@code Error} — unless its ending is already decided: the addon closed it, the
     * client refused a frame, or the JDK has closed the input and a {@code Close} or {@code Error} of its
     * own is on the way, where a send failing is the consequence and not a second ending.
     */
    private void sent(Throwable ex) {
        String next = null;
        int code = -1;
        String reason = null;
        synchronized(this) {
            if(pending > 0)
                pending--;
            if(ex != null) {
                outbox.clear();
                pending = 0;
                sending = false;
            } else {
                next = outbox.poll();
                if(next == null)
                    sending = false;
            }
            if(!sending && (queuedCloseCode >= 0)) {
                code = queuedCloseCode;
                reason = queuedCloseReason;
                queuedCloseCode = -1;
                queuedCloseReason = null;
            }
        }
        if(ex != null)
            sendFailed(ex);
        else if(next != null)
            dispatchSend(next);
        if(code >= 0) {
            WebSocket w = ws;
            if(w != null)
                w.sendClose(code, reason);   // a failure here is the deadline's to end (WebSocketApi.step)
        }
    }

    /** A send the wire refused: the connection's ending, where nothing else has ended it yet. */
    private void sendFailed(Throwable ex) {
        WebSocket w = ws;
        if(refused || (state != State.OPEN) || ((w != null) && w.isInputClosed()))
            return;
        events.add(new Pending("Error", "send failed: " + describe(ex), 0));
        if(w != null)
            w.abort();
    }

    /**
     * {@code :close(code, reason)} on an open connection — the Close frame <b>behind what was sent</b>: now
     * when nothing is pending, otherwise once the last queued message has gone ({@link #sent}). Under the
     * addon's lock; the deadline the caller set runs from the call either way, so a backlog the peer never
     * reads is still cut in time.
     */
    void closeAfterPending(int code, String reason) {
        synchronized(this) {
            if(sending) {
                queuedCloseCode = code;
                queuedCloseReason = reason;
                return;
            }
        }
        WebSocket w = ws;
        if(w != null)
            w.sendClose(code, reason);       // a failure here is an error the listener reports, or the deadline ends
    }

    /** Drop every message not yet on the wire — the drain's half of a refusal, and the teardown's. */
    synchronized void dropOutbox() {
        outbox.clear();
        pending = sending ? 1 : 0;
    }

    /**
     * <b>Dispatch</b> — {@code :connect()}'s other half, on the call path under the owner's lock: the state
     * turns {@code connecting} here and now, and the handshake goes to the pool, because the resolve it opens
     * with blocks and nothing on the call path may.
     */
    void dispatch() {
        dispatched = true;
        state = State.CONNECTING;
        HttpApi.pool().execute(new Runnable() {
            public void run() {
                handshake();
            }
        });
    }

    /**
     * On a pool thread: the address check, then the JDK handshake. <b>Every way this fails is an
     * {@code Error}</b> queued for the drain — a host that does not resolve, one that resolves into private
     * space, a handshake the server refuses or one that runs past the deadline — because a connection that
     * never opens still ends, and the addon hears its ending from the same door as any other.
     *
     * <p>The resolve is the {@code https} rule ({@link LuaHttp#isBlockedAddress}): every address the name
     * answers with is checked, and the JDK then connects by name with the certificate as the binding — a
     * rebound private address cannot present a chain valid for the host, which is what makes a second
     * lookup safe here where it is not for cleartext.
     */
    private void handshake() {
        try {
            String host = uri.getHost();
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for(InetAddress a : addrs) {
                if(LuaHttp.isBlockedAddress(a)) {
                    fail("host " + host + " resolves to a blocked address (" + a.getHostAddress()
                         + "; private/loopback ranges are refused)");
                    return;
                }
            }
            if(state != State.CONNECTING)
                return;                                // closed while the name resolved: nothing is opened
            WebSocket.Builder b = WebSocketApi.client().newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .header("User-Agent", LuaHttp.USER_AGENT);
            for(Map.Entry<String, String> e : headers.entrySet())
                b.header(e.getKey(), e.getValue());
            if(protocol != null)
                b.subprotocols(protocol);
            b.buildAsync(uri, listener).whenComplete(new BiConsumer<WebSocket, Throwable>() {
                public void accept(WebSocket w, Throwable ex) {
                    if(ex != null) {
                        fail(describe(ex));
                        return;
                    }
                    ws = w;
                    // Closed or torn down while the handshake was in flight: the Close the addon asked for
                    // is already queued (or the record already dropped), so the fresh socket is cut here,
                    // where it is first known. A connection whose Open was delivered is closing politely
                    // through sendClose and is left to the peer's answer or the deadline.
                    if(!opened && (state != State.CONNECTING))
                        w.abort();
                }
            });
        } catch(UnknownHostException e) {
            fail("DNS resolution failed for " + uri.getHost());
        } catch(RuntimeException e) {
            fail(e.getClass().getSimpleName() + ": " + Refusal.reason(e));
        }
    }

    /** Queue an {@code Error} with {@code why} — from the pool thread, never entering Lua. */
    void fail(String why) {
        events.add(new Pending("Error", why, 0));
    }

    /**
     * A failure as the one line {@code ev:error()} reads: the cause under a completion wrapper, a handshake
     * refusal by the status the server answered, a deadline as the word {@code timeout} the http page uses
     * for the same thing, and anything else by its kind and message.
     */
    static String describe(Throwable t) {
        while(((t instanceof CompletionException) || (t instanceof ExecutionException)) && (t.getCause() != null))
            t = t.getCause();
        if(t instanceof WebSocketHandshakeException) {
            WebSocketHandshakeException h = (WebSocketHandshakeException)t;
            return "handshake failed" + ((h.getResponse() != null)
                                         ? (": HTTP " + h.getResponse().statusCode()) : "");
        }
        if(t instanceof HttpTimeoutException)
            return "timeout";
        return t.getClass().getSimpleName() + ": " + Refusal.reason(t);
    }

    /**
     * <b>End this connection from the outside</b> — the teardown's half (142.1): a live socket is told
     * {@code 1001 going away}, one that never opened is cut, one still shaking hands is cut by its own
     * completion, and the record stops firing. No handler runs: the addon this belonged to is going away.
     * The polite close is left to {@link WebSocketApi#dying}, which cuts a peer that never answers it.
     */
    void teardown() {
        done = true;
        state = State.CLOSED;
        dropOutbox();                    // what the addon still had to say goes unsaid: it is going away
        WebSocket w = ws;
        if(w != null) {
            if(!opened) {
                w.abort();
            } else {
                if(!w.isOutputClosed())          // a :close() already sent its own Close: one is enough
                    w.sendClose(1001, "going away");
                WebSocketApi.dying(w);
            }
        }
        subs.clear();
    }
}
