package io.brodgar.addon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The {@code hafen.websocket} section (142.1) — {@link HttpApi}'s shape one step further: the collection of
 * an addon's live connections, {@code :connection(url)} as its one extra verb, a {@code Connection} handle
 * built bare and dispatched by {@code :connect()}, and {@code :on(key, fn)} as the one door its four edges
 * come through. The record and the transport are {@link LuaWebSocket}; the payloads are
 * {@link LuaWebSocketEvent}; this class owns the vocabulary, the gate, the drain and the teardown.
 *
 * <p><b>The gate is {@link HttpApi#requireNetwork}</b>, under {@link Permission#WEBSOCKET_CONNECT} with the
 * {@code https} origin the address names: the allowlist names servers, and a server is the same under
 * {@code https} and {@code wss}, so an addon that may fetch from a host may keep a connection to it with the
 * same line in its manifest and the same line in the consent dialog. It runs in {@code :connect()} — the
 * one call that reaches the network — and first (D-213).
 *
 * <p><b>Delivery is the layer's step</b> ({@link #drain}, from {@code AddonManager.layerStep} after the
 * timers): every addon's connections, every queued edge, fired through the connection's own {@link Subs}
 * under {@code enterLua} exactly as a timer is. Not a session's tick, on purpose: a connection opened at
 * {@code Load} has no session to drain it, and one whose login ended would die with the login, while its
 * subject is a server. The same walk checks each closing connection's deadline and cuts a peer that never
 * answered, and sweeps {@link #dying} — the sockets a teardown told {@code 1001} — so nothing here needs a
 * thread of its own.
 *
 * <p>All members static; not instantiable.
 */
final class WebSocketApi {
    private WebSocketApi() {}

    /** A subprotocol name is an HTTP token (RFC 6455 §4.1 by way of RFC 7230 §3.2.6); the JDK refuses any other. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    /**
     * The one JDK client every connection is built on, created on first use for the life of the engine —
     * with {@link HttpApi#pool()} as its executor, so a listener's callbacks ride the eight daemon threads
     * a request already does, and with redirects off, so a {@code 3xx} on the handshake is the failure it is
     * rather than a hop to a server the user never approved. TLS is the JDK's default: verified, never
     * disabled.
     */
    private static volatile HttpClient client;

    static HttpClient client() {
        HttpClient c = client;
        if(c == null) {
            synchronized(WebSocketApi.class) {
                if((c = client) == null) {
                    c = client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .executor(HttpApi.pool())
                        .build();
                }
            }
        }
        return c;
    }

    /** A socket a teardown told {@code 1001}, and the clock at which the drain stops waiting for its answer. */
    private static final class Dying {
        final WebSocket ws;
        final long deadline;

        Dying(WebSocket ws, long deadline) {
            this.ws = ws;
            this.deadline = deadline;
        }
    }

    /**
     * The sockets a teardown closed politely and whose peer has not answered yet ({@link LuaWebSocket#teardown}).
     * Their records are gone, so this is the one place that still knows them; {@link #drain} cuts each once
     * its input closes or its deadline passes. Concurrent: the exit path files here off the step.
     */
    private static final Queue<Dying> dying = new ConcurrentLinkedQueue<Dying>();

    /** File {@code ws} for the sweep: it was told {@code 1001}, and it has the close deadline to answer. */
    static void dying(WebSocket ws) {
        dying.add(new Dying(ws, System.currentTimeMillis() + LuaWebSocket.CLOSE_DEADLINE_MS));
    }

    /**
     * Build the {@code hafen.websocket} section for {@code owner} — <b>the collection of its live
     * connections</b>, with {@code :connection(url)} as its {@code extra} verb. Called from
     * {@code installHafen}, beside {@link HttpApi#install}.
     *
     * <p>A connection is in the set from {@code :connect()} until its {@code Close} or {@code Error} has
     * been delivered — the set the cap of {@link LuaWebSocket#MAX_LIVE} counts, so an addon sees the refusal
     * coming through {@code :count()} and can end one through {@code :list()}. One built and never
     * connected, or closed before it was, is in no collection and holds no slot.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable extra = new LuaTable();
        // connection(url) -- a BARE connection. Nothing is opened, so every setter is legal until
        // :connect(), and the URL is checked here, where it was written: a typo is a refusal at this call
        // rather than an Error a frame later. The permission is not asked here -- nothing has left.
        extra.set("connection", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.websocket()", "connection");
                Args.only(a, 1, "hafen.websocket():connection");
                String url = Args.str(a, 2, "hafen.websocket():connection", "url", "a wss:// address").tojstring();
                return newConnection(owner, url, "hafen.websocket():connection");
            }
        });
        LuaValue coll = LuaCollection.create("hafen.websocket()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(LuaWebSocket c : owner.connections) {
                    if(!c.done && (c.handle != null))
                        out.add(c.handle);
                }
                return out;
            }

            /** A connection has an address, so a string filter is a substring test over it. */
            public String needle(LuaValue member) {
                LuaWebSocket c = resolve(member);
                return (c == null) ? "" : c.url;
            }

            public boolean named() {
                return true;
            }

            /** A connection has no key: it is the handle :connection(url) handed you. */
            public String noGet() {
                return "a connection has no key of its own -- it is the handle :connection(url) handed you."
                    + " hafen.websocket():find(\"<url substring>\") searches, and :list() is all of them";
            }
        }, extra);
        Section.mount(hafen, "websocket", coll, null);
    }

    /** The connection behind a handle, or {@code null} — the userdata is the record itself. */
    static LuaWebSocket resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaWebSocket) ? (LuaWebSocket)o : null;
    }

    /** The receiver of a colon call on a connection, or the error that says a dot call passed the wrong self. */
    private static LuaWebSocket self(LuaValue v, String method) {
        LuaWebSocket c = resolve(v);
        if(c == null)
            throw new LuaError("connection:" + method + "() — use a COLON call on the connection"
                + " hafen.websocket():connection(url) handed you (conn:" + method + "(…))");
        return c;
    }

    /**
     * Validate a {@code wss} address and return the <b>origin</b> it names, or throw a guiding LuaError.
     *
     * <p>Parsed by {@link URI} alone, never through {@code toURL()}: there is no protocol handler for
     * {@code wss}, so that door throws for every address this section takes. RFC 3986 is still the parse —
     * a space, a brace, a bare {@code %} are refused naming the URL — and an empty host is the hostless
     * refusal. {@code ws://} is refused by name: the address policy admits no cleartext, and a reader who
     * wrote it is told the scheme to write. The origin is {@code https} on the address's port, because
     * that is the server the allowlist names.
     */
    private static String wssOrigin(String url, String verb) {
        URI u;
        try {
            u = URI.create(url);
        } catch(IllegalArgumentException e) {
            throw new LuaError(verb + ": malformed url \"" + url + "\" (" + Refusal.reason(e) + ")");
        }
        String scheme = (u.getScheme() == null) ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if(scheme.equals("ws"))
            throw new LuaError(verb + ": url scheme must be wss (got \"ws\") — a cleartext connection is"
                + " refused, so write wss:// and the server's TLS port");
        if(!scheme.equals("wss"))
            throw new LuaError(verb + ": url scheme must be wss (got \"" + scheme + "\")");
        String host = u.getHost();
        if((host == null) || host.isEmpty())
            throw new LuaError(verb + ": url has no host");
        if(u.getFragment() != null)
            throw new LuaError(verb + ": url \"" + url + "\" carries a fragment, which a WebSocket address"
                + " cannot — drop the part from # on");
        return Manifest.origin("https", host, (u.getPort() > 0) ? u.getPort() : Manifest.defaultPort("https"));
    }

    /**
     * Build a connection — <b>bare</b>. Returns the Lua handle: {@code :url()} {@code :state()}
     * {@code :header(name[, value])} {@code :protocol([name])} {@code :timeout([ms])} {@code :on(key, fn)}
     * {@code :connect()} and {@code :close([code[, reason]])}. Nothing is registered or scheduled here: the
     * cap is charged by {@code :connect()}, because a connection that never opens reaches no wire and
     * should hold no slot against a cap that counts what is live.
     */
    private static LuaValue newConnection(Addon owner, String url, String verb) {
        String origin = wssOrigin(url, verb);
        LuaWebSocket c = new LuaWebSocket(owner, url, URI.create(url), origin);
        LuaValue h = LuaValue.userdataOf(c, meta(owner));
        c.handle = h;
        return h;
    }

    /** A setter refuses once the connection has been DISPATCHED: the wire has it, so writing the field would lie. */
    private static void requireNew(LuaWebSocket c, String verb) {
        if(c.dispatched)
            throw new LuaError("connection:" + verb + ": this connection has already been dispatched by"
                + " :connect() -- configure it before :connect(), which is the whole of the rule");
        if(c.state == LuaWebSocket.State.CLOSED)
            throw new LuaError("connection:" + verb + ": this connection was closed by :close() before it"
                + " was opened -- build another with hafen.websocket():connection(url)");
    }

    /**
     * Whether an addon may set {@code name} on the handshake: the transport-owned list a request already
     * refuses ({@link LuaHttp#headerAllowed}), plus the handshake's own — every {@code Sec-WebSocket-*} field
     * and {@code Expect}, which the JDK refuses at the build. Dropped silently at the setter, as a request's
     * are at the wire, so the setter reads back nothing for them.
     */
    private static boolean headerAllowed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return LuaHttp.headerAllowed(name) && !n.startsWith("sec-websocket-") && !n.equals("expect");
    }

    /** The value {@code headers} carries for {@code name}, matched case-insensitively, or {@code null}. */
    private static String headerOf(Map<String, String> headers, String name) {
        for(Map.Entry<String, String> e : headers.entrySet()) {
            if(e.getKey().equalsIgnoreCase(name))
                return e.getValue();
        }
        return null;
    }

    /** Set {@code name} to {@code value}, replacing whatever spelling of it the connection already carries. */
    private static void putHeader(Map<String, String> headers, String name, String value) {
        for(Iterator<Map.Entry<String, String>> i = headers.entrySet().iterator(); i.hasNext();) {
            if(i.next().getKey().equalsIgnoreCase(name))
                i.remove();
        }
        headers.put(name, value);
    }

    /** The per-addon {@code Connection} metatable, built once (D-017) — the vocabulary, closed. */
    private static LuaValue meta(Addon owner) {
        if(owner.wsMeta != null)
            return owner.wsMeta;
        LuaTable m = new LuaTable();
        // url() / state() -- what the connection IS and where it is: the filter matches on the first, and
        // the second is the one word every other verb's legality follows from.
        m.set("url", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "connection:url");
                return LuaValue.valueOf(self(a.arg1(), "url").url);
            }
        });
        m.set("state", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "connection:state");
                return LuaValue.valueOf(self(a.arg1(), "state").state.word);
            }
        });
        // header(name) reads, header(name, value) writes and returns SELF so it chains. Case-insensitive:
        // one header has one value however it is spelled, which is also what the wire means by it.
        m.set("header", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "header");
                Args.only(a, 2, "connection:header");
                String name = Args.str(a, 2, "connection:header", "name", null).tojstring();
                LuaValue value = Args.written(a, 3, "connection:header", "value");
                if(value == null) {
                    String v = headerOf(c.headers, name);
                    return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v);
                }
                requireNew(c, "header");
                Args.str(value, "connection:header", "value", null);
                if(headerAllowed(name))
                    putHeader(c.headers, name, value.tojstring());
                return a.arg1();
            }
        });
        // protocol() reads the subprotocol -- the one asked for until the handshake, the one the server
        // agreed to from Open on, nil for none -- and protocol(name) asks for one.
        m.set("protocol", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "protocol");
                Args.only(a, 1, "connection:protocol");
                LuaValue v = Args.written(a, 2, "connection:protocol", "name");
                if(v == null) {
                    String p = c.opened ? c.agreed : c.protocol;
                    return ((p == null) || p.isEmpty()) ? LuaValue.NIL : LuaValue.valueOf(p);
                }
                requireNew(c, "protocol");
                String nm = Args.str(v, "connection:protocol", "name", "a subprotocol name").tojstring();
                if(!TOKEN.matcher(nm).matches())
                    throw new LuaError("connection:protocol(name): \"" + nm + "\" is not a subprotocol name"
                        + " -- one is a token: letters, digits and !#$%&'*+-.^_`|~, with no space");
                c.protocol = nm;
                return a.arg1();
            }
        });
        // timeout() reads the milliseconds the handshake may take, timeout(ms) writes it and returns SELF.
        // The range is REFUSED, never clamped (ht-11): a deadline silently rewritten is a wait nobody wrote.
        m.set("timeout", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "timeout");
                Args.only(a, 1, "connection:timeout");
                LuaValue ms = Args.written(a, 2, "connection:timeout", "ms");
                if(ms == null)
                    return LuaValue.valueOf(c.timeout);
                requireNew(c, "timeout");
                c.timeout = (int)Args.integer(ms, "connection:timeout", "ms", "milliseconds", 1,
                                              LuaHttp.MAX_TIMEOUT);
                return a.arg1();
            }
        });
        // on(key, fn) -- THE HANDLER, the API's one notification verb, over a CLOSED set of four keys: an
        // unknown one raises naming them rather than being accepted and never firing (D-129). Legal after
        // :connect() too -- a connection in flight has not ended yet, so arming a listener is a moment
        // rather than a mistake -- and it hands back a Sub, so it ends with sub:off() like every other.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "on");
                Args.only(a, 2, "connection:on");
                String key = Args.str(a, 2, "connection:on", "key", "one of Open, Message, Close and Error")
                                 .tojstring();
                LuaValue fnArg = Args.required(a, 3, "connection:on", "fn");
                if(!fnArg.isfunction())
                    throw new LuaError("connection:on(key, fn): fn must be a function, got " + fnArg.typename());
                String moved = Refusal.eventKey("connection", key);
                if(moved != null)
                    throw new LuaError(moved);
                if(!isKey(key))
                    throw new LuaError("connection:on(key, fn): a connection has no event '" + key + "' -- it"
                        + " has: Open, Message, Close, Error. Open hands the connection; the other three hand"
                        + " an ev answering ev:connection()");
                return c.subs.on(key, fnArg);
            }
        });
        // connect() -- DISPATCH, said out loud, and the GATE, which is the call it belongs to (D-213 for a
        // builder): nothing has left the client until here. The key before the allowlist, as 093 wrote it;
        // then the builder's own rule; then the cap, charged here and not at construction.
        m.set("connect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "connect");
                Args.only(a, 0, "connection:connect");
                HttpApi.requireNetwork(c.owner, Permission.WEBSOCKET_CONNECT, c.origin, "connection:connect");
                if(c.dispatched)
                    throw new LuaError("connection:connect(): this connection has already been dispatched"
                        + " by :connect() -- build another with hafen.websocket():connection(url)");
                if(c.state == LuaWebSocket.State.CLOSED)
                    throw new LuaError("connection:connect(): this connection was closed by :close() before"
                        + " it was opened -- build another with hafen.websocket():connection(url)");
                int live = c.owner.connections.size();
                if(live >= LuaWebSocket.MAX_LIVE)
                    throw new LuaError("connection:connect(): this addon already holds " + live + " live"
                        + " connections, which is the cap; hafen.websocket():count() is how many, and"
                        + " hafen.websocket():list() is which -- close one, or wait for one to end.");
                c.owner.connections.add(c);
                c.dispatch();
                return a.arg1();
            }
        });
        // close(code, reason) -- the ending, and the only verb legal in every state. 1000 by default, or
        // 3000..4999, which are the codes the protocol leaves to an application; the rest are the
        // protocol's own and the JDK refuses most of them, so they are refused here by name. Idempotent:
        // a second close answers the connection and changes nothing.
        m.set("close", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaWebSocket c = self(a.arg1(), "close");
                Args.only(a, 2, "connection:close");
                int code = Args.optint(a, 2, "connection:close", "code", "1000 or 3000..4999", 1000);
                if((code != 1000) && ((code < 3000) || (code > 4999)))
                    throw new LuaError("connection:close(code): the code is 1000 or 3000..4999, got " + code
                        + " -- the codes below 3000 are the protocol's own, and a connection you end says"
                        + " 1000 or one of the application's");
                LuaValue r = Args.written(a, 3, "connection:close", "reason");
                String reason = (r == null) ? ""
                    : Args.str(r, "connection:close", "reason", "a short sentence").tojstring();
                if(reason.getBytes(StandardCharsets.UTF_8).length > LuaWebSocket.MAX_REASON_BYTES)
                    throw new LuaError("connection:close(code, reason): the reason is at most "
                        + LuaWebSocket.MAX_REASON_BYTES + " bytes of UTF-8, which is the frame's own limit");
                close(c, code, reason);
                return a.arg1();
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("connection", m,
            "a connection",
            "it is dispatched by :connect(): every setter is legal until then and none after"));
        mt.set("__name", LuaValue.valueOf("Connection"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaWebSocket c = resolve(self);
                return LuaValue.valueOf((c == null) ? "Connection(?)" : c.toString());
            }
        });
        owner.wsMeta = mt;
        return mt;
    }

    /** Is {@code key} one of the four a connection fires? */
    private static boolean isKey(String key) {
        for(String k : LuaWebSocket.KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    /**
     * <b>End a connection from Lua</b> — {@code :close()}'s body, under the owner's lock, by state:
     * <ul>
     *   <li>{@code new}: nothing was opened, so it is {@code closed} here and silently — no {@code Close}
     *       fires for a connection that never went anywhere, and it is in no collection to leave.</li>
     *   <li>{@code connecting}: {@code closing}, and a {@code Close} carrying the addon's own pair is queued
     *       for the drain. The socket, if the handshake has already produced one, is cut here; if it has not,
     *       the handshake's completion cuts it ({@link LuaWebSocket#handshake}).</li>
     *   <li>{@code open}: {@code closing}, the pair recorded, a Close frame sent, and the deadline set —
     *       the input stays open until the peer's Close, an error, or the drain giving up.</li>
     *   <li>{@code closing} and {@code closed}: nothing; a second ending answers the receiver again.</li>
     * </ul>
     */
    private static void close(LuaWebSocket c, int code, String reason) {
        switch(c.state) {
        case NEW:
            c.state = LuaWebSocket.State.CLOSED;
            break;
        case CONNECTING:
            c.closeCode = code;
            c.closeReason = reason;
            c.state = LuaWebSocket.State.CLOSING;
            c.events.add(new LuaWebSocket.Pending("Close", reason, code));
            WebSocket w = c.ws;
            if(w != null)
                w.abort();
            break;
        case OPEN:
            c.closeCode = code;
            c.closeReason = reason;
            c.state = LuaWebSocket.State.CLOSING;
            c.closeDeadline = System.currentTimeMillis() + LuaWebSocket.CLOSE_DEADLINE_MS;
            WebSocket open = c.ws;
            if(open != null)
                open.sendClose(code, reason);   // a failure here is an error the listener reports, or the deadline ends
            break;
        default:
            break;
        }
    }

    // ------------------------------------------------------------- the drain

    /**
     * <b>Every addon's queued edges, fired on the step</b> — called from {@code AddonManager.layerStep} right
     * after the timers, holding no tree monitor. The {@code :lua} REPL owner is walked too, as its timers are.
     * Then the sockets a teardown left closing politely, cut once answered or once out of time.
     */
    static void drain() {
        long now = System.currentTimeMillis();
        for(Addon a : AddonManager.addons)
            drain(a, now);
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            drain(c, now);
        sweepDying(now);
    }

    /**
     * One addon's connections — <b>the whole pass as one entry into its Lua</b>, for {@link Subs#fire}'s
     * reason: several edges landing on one frame are one addon's own beat. Ended connections are collected
     * and dropped in one call, since the list is copy-on-write.
     */
    private static void drain(Addon a, long now) {
        if(a.connections.isEmpty() || !AddonManager.enterLua(a))
            return;
        try {
            List<LuaWebSocket> ended = null;
            for(LuaWebSocket c : a.connections) {
                if(step(c, now)) {
                    if(ended == null)
                        ended = new ArrayList<LuaWebSocket>();
                    ended.add(c);
                }
            }
            if(ended != null)
                a.connections.removeAll(ended);
        } finally {
            AddonManager.leaveLua(a);
        }
    }

    /**
     * Fire what one connection queued, in order, and stop at its ending; then, for a connection that is
     * closing and out of time, cut the socket and report the pair the addon asked for. Answers whether the
     * connection is done and leaves the collection.
     *
     * <p>Each edge is read against the state: an {@code Open} queued for a connection the addon has since
     * closed is not fired, and a {@code Close} or {@code Error} behind the one that ended it is not either —
     * exactly one of the two ends a connection, whatever the listener saw after.
     */
    private static boolean step(LuaWebSocket c, long now) {
        LuaWebSocket.Pending p;
        while(!c.done && ((p = c.events.poll()) != null)) {
            if("Open".equals(p.key)) {
                if(c.state == LuaWebSocket.State.CONNECTING) {
                    c.state = LuaWebSocket.State.OPEN;
                    c.opened = true;
                    c.subs.fire("Open", c.handle);
                }
            } else if("Close".equals(p.key)) {
                // What the addon asked for is what it reads back (the plan's rule): the peer may echo
                // anything or nothing, and a Close the client sent for a refusal of its own says the
                // client's code. A close the peer began reports the peer's pair.
                boolean own = c.closeCode >= 0;
                end(c, "Close", LuaWebSocketEvent.close(c.owner, c.handle, own ? c.closeCode : p.code,
                                                        own ? c.closeReason : p.text));
            } else if("Error".equals(p.key)) {
                end(c, "Error", LuaWebSocketEvent.error(c.owner, c.handle, p.text));
            }
        }
        if(!c.done && (c.state == LuaWebSocket.State.CLOSING) && (c.closeDeadline != 0)
           && (now >= c.closeDeadline)) {
            WebSocket w = c.ws;
            if(w != null)
                w.abort();
            end(c, "Close", LuaWebSocketEvent.close(c.owner, c.handle, c.closeCode, c.closeReason));
        }
        return c.done;
    }

    /**
     * Deliver the ending: the state is {@code closed} before the handler runs, so {@code conn:state()} read
     * inside it says what the event says; the socket of a connection that never opened is cut, since no
     * Close of its own was ever sent for it; and the subscriptions are dropped after the fire, so a handler
     * that closed over its own connection no longer holds the record alive through it.
     */
    private static void end(LuaWebSocket c, String key, LuaValue ev) {
        c.state = LuaWebSocket.State.CLOSED;
        c.done = true;
        c.subs.fire(key, ev);
        WebSocket w = c.ws;
        if((w != null) && !c.opened)
            w.abort();
        c.subs.clear();
    }

    /** Cut every politely-closed socket whose peer has answered or run out of time. */
    private static void sweepDying(long now) {
        for(Iterator<Dying> i = dying.iterator(); i.hasNext();) {
            Dying d = i.next();
            if(d.ws.isInputClosed()) {
                i.remove();
            } else if(now >= d.deadline) {
                d.ws.abort();
                i.remove();
            }
        }
    }

    // ------------------------------------------------------------- teardown

    /**
     * Teardown (the {@code Step} after the http requests, and the exit): every connection is told
     * {@code 1001 going away} or cut, its record dropped, and <b>no handler runs</b> — the addon these belonged
     * to is going away, and there is nothing left to tell it. A politely-closed socket is filed on
     * {@link #dying} for the drain to cut if its peer never answers.
     */
    static void teardown(Addon a) {
        if(a.connections.isEmpty())
            return;
        for(LuaWebSocket c : a.connections)
            c.teardown();
        a.connections.clear();
    }
}
