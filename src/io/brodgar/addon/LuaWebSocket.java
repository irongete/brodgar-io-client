package io.brodgar.addon;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import org.luaj.vm2.LuaValue;

/**
 * One {@code hafen.websocket()} connection (142.1) — the record behind the {@code Connection} handle, the
 * JDK transport under it, and the queue between the two. The sibling of {@link LuaHttpRequest} one door
 * along: bridge-owned, registered in {@link Addon#connections} from {@code :connect()} until its ending is
 * heard, torn down with the addon.
 *
 * <p><b>Built bare, opened on purpose</b> (the builder rule, 095's for a request). Every setter writes a
 * field here until {@code :connect()} and none after: {@link #dispatched} is written by that verb alone,
 * so there is no timing in the rule.
 *
 * <p><b>The transport is {@link java.net.http.WebSocket}</b>, built on {@link WebSocketApi#client()} with the
 * shared HTTP pool as its executor, and <b>the listener only enqueues</b>: it runs on a pool thread, which
 * holds no tree and may not enter Lua, so every edge it sees — the open, the peer's Close, an error — is a
 * {@link Pending} on {@link #events}, and {@link WebSocketApi#drain} fires them from the layer's step under
 * the addon's own lock. That is what makes a handler run with {@code hafen.client():stepping()} true and
 * every tree reachable, and what makes a connection alive from {@code Load} on with no login to drain it.
 *
 * <p><b>One ending, said once.</b> {@link #done} is set by the drain when it delivers {@code Close} or
 * {@code Error}, and nothing here fires after it: a peer's Close landing behind an error, an error behind a
 * close the addon asked for, a handshake completing after the connection was closed — each is queued and
 * ignored, and the socket it belongs to is aborted wherever it is first known. {@link #opened} is the other
 * half of that rule: a connection ended before its {@code Open} was delivered never sent a Close of its own,
 * so the socket under it is cut rather than closed politely.
 *
 * <p><b>Threading.</b> {@link #state}, {@link #ws} and the close pair are volatile: the Lua verbs write
 * them under the addon's lock, the listener and the handshake's completion write them on a pool thread, and
 * the drain reads them on the step. {@link #headers}, {@link #protocol} and {@link #timeout} are written
 * only before {@code :connect()} and read once, by the pool thread that runs the handshake, after the
 * submit that published them.
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
     * {@code Close} its reason and code, {@code Error} its sentence, {@code Open} nothing.
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
     * {@link #done} covers both. The text, binary, ping and pong methods keep the interface's defaults —
     * each requests one more invocation — until the messages arrive with their own task.
     */
    final WebSocket.Listener listener = new WebSocket.Listener() {
        public void onOpen(WebSocket w) {
            ws = w;
            agreed = w.getSubprotocol();
            events.add(new Pending("Open", null, 0));
            w.request(1);
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
