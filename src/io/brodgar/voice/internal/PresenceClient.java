package io.brodgar.voice.internal;

import io.brodgar.voice.Protocol;
import io.brodgar.voice.ProtocolException;
import io.brodgar.voice.crypto.KeyExchange;
import io.brodgar.voice.crypto.SessionKeys;
import io.brodgar.voice.msg.ClientHello;
import io.brodgar.voice.msg.EdgesUpdate;
import io.brodgar.voice.msg.ErrorMessage;
import io.brodgar.voice.msg.Message;
import io.brodgar.voice.msg.ServerWelcome;
import io.brodgar.voice.util.Hex;
import io.brodgar.voice.wire.WireCodec;
import io.brodgar.voice.VoiceException;

import java.net.URI;
import java.security.KeyPair;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket presence connection built on {@code java.net.http} (no external
 * dependency, available since Java 11). Sends hello, waits for welcome, then
 * streams reports out and edge updates in.
 */
public final class PresenceClient implements AutoCloseable {

    public interface Events {
        void onEdges(EdgesUpdate edges);

        void onServerError(ErrorMessage error);

        /** Fired once, when the socket dies or the server closes it. */
        void onDisconnected(String reason);
    }

    /**
     * Shared across all connections; one selector + executor thread pool for the
     * process.
     */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final WebSocket ws;
    private final ServerWelcome welcome;
    private final SessionKeys sessionKeys;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PresenceClient(WebSocket ws, ServerWelcome welcome, SessionKeys sessionKeys) {
        this.ws = ws;
        this.welcome = welcome;
        this.sessionKeys = sessionKeys;
    }

    public static PresenceClient connect(URI uri, String clientInfo,
                                         long timeoutMs, Events events) throws VoiceException {
        CompletableFuture<ServerWelcome> welcomeFuture = new CompletableFuture<>();
        Listener listener = new Listener(welcomeFuture, events);

        KeyPair keyPair = KeyExchange.generateKeyPair();
        byte[] publicKey = KeyExchange.encodePublicKey(keyPair.getPublic());

        WebSocket ws;
        try {
            ws = HTTP.newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
                    .buildAsync(uri, listener)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("interrupted while connecting", e);
        } catch (ExecutionException e) {
            throw new VoiceException("cannot connect to " + uri + ": " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new VoiceException("timeout connecting to " + uri, e);
        }

        try {
            ws.sendText(WireCodec.encode(
                            new ClientHello(Protocol.VERSION, clientInfo, publicKey)), true)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            ServerWelcome welcome = welcomeFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            SessionKeys keys = deriveKeys(keyPair, welcome);
            return new PresenceClient(ws, welcome, keys);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ws.abort();
            throw new VoiceException("interrupted during handshake", e);
        } catch (ExecutionException e) {
            ws.abort();
            throw new VoiceException("handshake failed: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            ws.abort();
            throw new VoiceException("timeout waiting for welcome", e);
        }
    }

    private static SessionKeys deriveKeys(KeyPair keyPair, ServerWelcome welcome) throws VoiceException {
        try {
            byte[] shared = KeyExchange.agree(keyPair.getPrivate(),
                    KeyExchange.decodePublicKey(welcome.publicKey()));
            return SessionKeys.derive(shared, Hex.decode(welcome.udpTokenHex()));
        } catch (RuntimeException e) {
            // Includes CryptoException (bad server key) and Hex decode failures.
            throw new VoiceException("cannot derive channel keys from welcome: " + e.getMessage(), e);
        }
    }

    public ServerWelcome welcome() {
        return welcome;
    }

    /** Derived UDP channel keys for this session. */
    public SessionKeys sessionKeys() {
        return sessionKeys;
    }

    /**
     * Sends a message and waits for the socket to accept it. All calls come
     * from the report-loop thread (plus one bye at close), so serializing here
     * keeps {@code java.net.http}'s one-outstanding-send rule satisfied.
     */
    public synchronized void send(Message m) throws VoiceException {
        try {
            ws.sendText(WireCodec.encode(m), true).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("interrupted sending", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new VoiceException("presence send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    // ------------------------------------------------------------- listener

    private static final class Listener implements WebSocket.Listener {

        private final CompletableFuture<ServerWelcome> welcomeFuture;
        private final Events events;
        private final StringBuilder partial = new StringBuilder();
        private final AtomicBoolean disconnectFired = new AtomicBoolean();

        Listener(CompletableFuture<ServerWelcome> welcomeFuture, Events events) {
            this.welcomeFuture = welcomeFuture;
            this.events = events;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                handle(msg);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(String json) {
            Message m;
            try {
                m = WireCodec.decodeServerMessage(json);
            } catch (ProtocolException e) {
                welcomeFuture.completeExceptionally(new VoiceException("bad server message", e));
                return;
            }
            if (m instanceof ServerWelcome) {
                welcomeFuture.complete((ServerWelcome) m);
            } else if (m instanceof EdgesUpdate) {
                events.onEdges((EdgesUpdate) m);
            } else if (m instanceof ErrorMessage) {
                ErrorMessage err = (ErrorMessage) m;
                welcomeFuture.completeExceptionally(
                        new VoiceException("server error: " + err.code() + " " + err.message()));
                events.onServerError(err);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fireDisconnect("closed by server: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            welcomeFuture.completeExceptionally(error);
            fireDisconnect("socket error: " + error);
        }

        private void fireDisconnect(String reason) {
            if (disconnectFired.compareAndSet(false, true)) {
                events.onDisconnected(reason);
            }
        }
    }

    /** Resolves a user-supplied server URI, appending the standard path if absent. */
    public static URI resolveUri(String serverUri) throws VoiceException {
        try {
            URI uri = URI.create(serverUri);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("ws") || scheme.equals("wss"))) {
                throw new VoiceException("serverUri must use ws:// or wss://");
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                String base = serverUri.endsWith("/")
                        ? serverUri.substring(0, serverUri.length() - 1)
                        : serverUri;
                return URI.create(base + Protocol.WS_PATH);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new VoiceException("bad serverUri: " + serverUri, e);
        }
    }
}
