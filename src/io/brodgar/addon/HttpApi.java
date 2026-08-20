package io.brodgar.addon;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

/**
 * The {@code hafen.http} subsystem (N2a/N2b). Owns the shared async substrate: a single engine-lifetime bounded
 * pool runs the blocking {@link LuaHttp} I/O off the UI thread; each worker enqueues its result onto its
 * session's completion queue, drained on that session's tick ({@link #drainHttp}). The per-addon in-flight
 * limit is enforced by a non-blocking UI-thread scheduler ({@link #maybeStartHttp}) that leaves excess requests
 * queued in {@code Addon.requests} as not-yet-started ({@code started=false}) and launches them as running ones
 * complete — so a pool thread never blocks waiting on a per-addon permit.
 *
 * <p><b>The two queues are one session's</b> (073.5, {@code SessionState.httpResults}/{@code .httpStarts}),
 * because a request was made by an addon running for one login and its callback has to reach that login's
 * tick: a completion filed anywhere else is a handler run under a session that never asked. The session is
 * taken off the <b>addon that owns the request</b> ({@link Addon#state()}) and resolved on the UI thread at
 * submit, never on the pool thread — a worker holds no tree to read and the drawn session is not its.
 *
 * <p><b>The pool stays one</b>, and so does the host allowlist: the pool is the client's threads and holds no
 * session's anything, and an allowlist belongs to an addon's manifest, which is the same file whichever
 * session it runs in.
 *
 * <p>The blocking socket I/O + security checks live one layer down in {@link LuaHttp}; the host-allowlist
 * gate ({@link #requireNetwork}) is here; the callback dispatch goes through {@link AddonManager#callLua}. The
 * tick drain, the session {@link #reset}, and the per-addon {@link #teardownRequests} are the three hooks
 * {@link AddonManager} calls. All members static; not instantiable.
 */
final class HttpApi {
    private HttpApi() {}

    // A single engine-lifetime bounded pool runs the blocking HttpURLConnection I/O off the UI thread; each
    // worker enqueues its result onto its session's queue, drained on that session's tick (the exact
    // gob-delta pattern, per session since 073.5).
    private static volatile ExecutorService pool;   // lazily created on first request; engine-lifetime

    /** A finished HTTP request (its result) captured on a pool thread, awaiting UI-thread delivery (N2a). */
    static final class HttpCompletion {
        final LuaHttpRequest req;
        final LuaHttp.Result result;

        HttpCompletion(LuaHttpRequest req, LuaHttp.Result result) {
            this.req = req;
            this.result = result;
        }
    }

    /**
     * Build the {@code hafen.http} section object (get/post) for {@code owner}. Called from
     * {@code installHafen}. A plain section: {@code hafen.http():get(url, cb)}, so the receiver is argument 1
     * and the URL is argument 2.
     *
     * <p><b>There is no options table.</b> A request is constructed bare and configured by chained setters on
     * the object it hands back — {@code req:header(name, value)}, {@code req:timeout(ms)} — which is why the
     * request does not leave the client in the call that created it: it is sent on the next tick, so
     * everything chained onto it is applied first. A cancelled request is never sent at all.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable http = new LuaTable();
        // get(url, cb) — async GET. cb(res) is optional (the result is discarded without one).
        http.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "http", "get");
                String url = Args.str(a, 2, "hafen.http():get", "url", null).tojstring();
                LuaValue cb = a.arg(3);
                if(!cb.isnil() && !cb.isfunction())
                    throw new LuaError("hafen.http():get: callback must be a function");
                String host = httpHost(url, "hafen.http():get");
                requireNetwork(owner, host, "hafen.http():get");
                return newHttpRequest(owner, "GET", url, null,
                                      new LinkedHashMap<String, String>(), LuaHttp.DEFAULT_TIMEOUT, cb);
            }
        });
        // post(url, body, cb) — send + read (N2b). body = a string (verbatim) or a table (→ JSON, tagged
        // application/json unless the addon sets its own Content-Type). Same gate/limits/res table as get;
        // both verbs follow up to 5 redirects, re-validating the allowlist + private-IP block per hop.
        http.set("post", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "http", "post");
                String url = Args.str(a, 2, "hafen.http():post", "url", null).tojstring();
                LuaValue body = a.arg(3);
                LuaValue cb = a.arg(4);
                if(!cb.isnil() && !cb.isfunction())
                    throw new LuaError("hafen.http():post: callback must be a function");
                String host = httpHost(url, "hafen.http():post");
                requireNetwork(owner, host, "hafen.http():post");
                Map<String, String> headers = new LinkedHashMap<String, String>();
                byte[] bytes = httpBody(body, headers, "hafen.http():post");
                return newHttpRequest(owner, "POST", url, bytes, headers, LuaHttp.DEFAULT_TIMEOUT, cb);
            }
        });
        Section.install(hafen, "http", http);
    }

    /**
     * Gate a network verb (D-037): the addon must have DECLARED a {@code network} block whose {@code hosts}
     * allowlist matches {@code host}, or this throws a guiding Lua error <b>synchronously at call</b> (same
     * instant feedback as {@code requirePermission}). The declaration IS the allowlist — a host not listed is
     * refused before any I/O. (The resolved IP's private/loopback check happens later, on the pool thread.)
     */
    private static void requireNetwork(Addon owner, String host, String verb) {
        if((owner == null) || !owner.manifest.usesNetwork())
            throw new LuaError(verb + ": this addon did not declare a \"network\" block — add"
                + " \"network\": { \"hosts\": [\"" + ((host != null) ? host : "example.com")
                + "\"] } to its manifest.json (D-037: network access must be declared + allowlisted).");
        if(!owner.manifest.hostAllowed(host))
            throw new LuaError(verb + ": host \"" + host + "\" is not in this addon's network allowlist"
                + " (declared hosts: " + owner.manifest.network + "). Add it to \"network\": { \"hosts\": [...] }.");
    }

    /** The shared HTTP pool, created on first use (engine-lifetime, daemon threads so it never blocks exit). */
    private static ExecutorService pool() {
        ExecutorService p = pool;
        if(p == null) {
            synchronized(HttpApi.class) {
                if((p = pool) == null) {
                    final AtomicInteger seq = new AtomicInteger();
                    ThreadFactory tf = new ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "addon-http-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    };
                    p = pool = Executors.newFixedThreadPool(Math.max(1, LuaHttp.POOL_THREADS), tf);
                }
            }
        }
        return p;
    }

    /**
     * Build + register a request, enforcing the per-addon queue cap, and queue the addon for the tick's
     * scheduler run. On the UI thread (the call path). {@code cb} may be NIL (result discarded). Returns the
     * Lua request object: {@code :header(name, value)}, {@code :timeout(ms)} and {@code :cancel()}.
     *
     * <p><b>The request is not started here</b>, and that is what makes the setters honest: the call that
     * creates it returns first, everything chained onto it is applied, and the tick sends it. Starting it
     * inside this method would race a {@code :timeout(5000)} written one character later against a pool
     * thread already reading the field.
     */
    private static LuaValue newHttpRequest(final Addon owner, String method, String url, byte[] body,
                                           Map<String, String> headers, int timeout, LuaValue cb) {
        // Per-addon hard queue cap (D-018 spirit): count this addon's still-live requests (NEW + RUNNING).
        int pending = 0;
        for(LuaHttpRequest r : owner.requests)
            if(!r.dead) pending++;
        if(pending >= LuaHttp.QUEUE_CAP)
            throw new LuaError("hafen.http: too many pending requests for this addon (" + pending
                + " >= " + LuaHttp.QUEUE_CAP + "); cancel some or wait.");

        final LuaHttpRequest req = new LuaHttpRequest(owner, method, url, body, headers, timeout, cb);
        owner.requests.add(req);
        queueStart(owner);

        final LuaTable h = new LuaTable();
        // header(name) reads, header(name, value) writes and returns SELF so it chains. Case-insensitive:
        // one header has one value however it is spelled, which is also what the wire means by it.
        h.set("header", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String name = Args.str(a, 2, "request:header", "name", null).tojstring();
                LuaValue value = Args.written(a, 3, "request:header", "value");
                if(value == null) {
                    String v = headerOf(req.headers, name);
                    return (v == null) ? LuaValue.NIL : LuaValue.valueOf(v);
                }
                requireUnsent(req, "header");
                Args.str(value, "request:header", "value", null);
                putHeader(req.headers, name, value.tojstring());
                return h;
            }
        });
        // timeout() reads the milliseconds this request will wait, timeout(ms) writes it and returns SELF.
        h.set("timeout", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue ms = Args.written(a, 2, "request:timeout", "ms");
                if(ms == null)
                    return LuaValue.valueOf(req.timeout);
                requireUnsent(req, "timeout");
                req.timeout = clampTimeout(Args.num(ms, "request:timeout", "ms", "milliseconds").toint());
                return h;
            }
        });
        // cancel() — never sent if it has not gone yet, and its callback never fires if it has.
        h.set("cancel", new ZeroArgFunction() {
            public LuaValue call() {
                if(!req.dead) {
                    req.dead = true;
                    owner.requests.remove(req);
                    queueStart(owner);   // freeing a slot may let a queued request start
                }
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /** A setter refuses once the request has gone out: the wire has it, so writing the field would lie. */
    private static void requireUnsent(LuaHttpRequest req, String verb) {
        if(req.started || req.dead)
            throw new LuaError("request:" + verb + ": this request has already been sent — configure it in"
                + " the same call that created it (the request goes out on the next tick)");
    }

    /**
     * This addon has an unstarted request; the next tick of <b>its own</b> session runs its scheduler
     * ({@link #startPending}). An addon with no session — none is loaded outside one — has nothing that will
     * ever drain the queue, so it is not queued at all and its request simply waits for the teardown that
     * cancels it, which is what {@link #maybeStartHttp} also refuses to start.
     */
    private static void queueStart(Addon owner) {
        AddonManager.SessionState st = (owner == null) ? null : owner.state();
        if(st != null)
            st.httpStarts.add(owner);
    }

    /**
     * The per-addon in-flight scheduler (UI thread): while fewer than {@link LuaHttp#MAX_INFLIGHT} of the
     * addon's requests are running, submit the next not-yet-started live one to the pool. Non-blocking — a pool
     * thread never waits on a per-addon permit (which could starve the shared pool); excess requests simply sit
     * in {@code Addon.requests} as {@code started=false} until a running one completes (drain re-invokes this).
     */
    private static void maybeStartHttp(Addon owner) {
        AddonManager.SessionState st = owner.state();
        if(st == null)
            return;                               // nothing would drain its completion: see queueStart
        int running = 0;
        for(LuaHttpRequest r : owner.requests)
            if(r.started && !r.dead) running++;
        for(LuaHttpRequest r : owner.requests) {
            if(running >= LuaHttp.MAX_INFLIGHT)
                break;
            if(r.dead || r.started)
                continue;
            r.started = true;
            running++;
            submitHttpJob(st, r);
        }
    }

    /**
     * Submit one request to the pool. The worker does only Java I/O; it enqueues the result for the tick drain
     * of the session it is handed — resolved <b>here</b>, on the UI thread, and closed over, because the pool
     * thread that finishes the request has no tree to read and the session on screen by then need not be the
     * one whose addon asked.
     */
    private static void submitHttpJob(final AddonManager.SessionState st, final LuaHttpRequest req) {
        pool().execute(new Runnable() {
            public void run() {
                if(req.dead)
                    return;                       // cancelled before we started
                LuaHttp.Result res = LuaHttp.perform(req);
                if(req.dead)
                    return;                       // cancelled while in flight → discard, no callback
                st.httpResults.add(new HttpCompletion(req, res));
            }
        });
    }

    /**
     * Send this session's requests created since its last tick (UI thread). This is the other half of the
     * setters: a request spends the rest of the frame that created it being configured, and goes out here.
     */
    static void startPending(AddonManager.SessionState st) {
        if(st.httpStarts.isEmpty())
            return;
        Addon a;
        while((a = st.httpStarts.poll()) != null)
            maybeStartHttp(a);
    }

    /** Drain this session's completed requests on the UI thread: deliver each live one's res table + advance the scheduler. */
    static void drainHttp(AddonManager.SessionState st) {
        startPending(st);
        HttpCompletion hc;
        while((hc = st.httpResults.poll()) != null) {
            LuaHttpRequest req = hc.req;
            Addon owner = req.owner;
            if(req.dead) {                        // cancelled/torn down after the worker enqueued → discard
                owner.requests.remove(req);
                continue;
            }
            req.dead = true;                      // one-shot: completed
            owner.requests.remove(req);
            if(!req.cb.isnil())
                AddonManager.callLua(owner, Addon.C_EVENT, req.cb, httpResTable(hc.result));   // armed + isolated in callLua
            maybeStartHttp(owner);                // a slot freed → launch any queued request
        }
    }

    /** Build the Lua {@code res} table an addon's callback receives (D-037 §4.2). */
    private static LuaValue httpResTable(LuaHttp.Result r) {
        LuaTable t = new LuaTable();
        t.set("ok", LuaValue.valueOf(r.ok));
        if(r.ok) {
            t.set("status", LuaValue.valueOf(r.status));
            t.set("body", LuaValue.valueOf(r.body));
            LuaTable hd = new LuaTable();
            for(Map.Entry<String, String> e : r.headers.entrySet())
                hd.set(e.getKey(), LuaValue.valueOf(e.getValue()));
            t.set("headers", hd);
        } else {
            t.set("error", LuaValue.valueOf(r.error));
        }
        return t;
    }

    /** Teardown (N2a): cancel every in-flight request so a reload/disable/relog leaks nothing + never calls back. */
    static void teardownRequests(Addon a) {
        if(a.requests.isEmpty())
            return;
        for(LuaHttpRequest r : a.requests)
            r.dead = true;    // in-flight workers see this and discard; queued ones never start
        a.requests.clear();
    }

    /**
     * Session init: drop stale HTTP completions (their requests were torn down by the teardown loop). Every
     * session's, since {@code init} is not told which one ended and clearing them all is exactly what this
     * did when there was one queue for the client.
     */
    /** Validate an {@code http}/{@code https} URL and return its (non-empty) host, or throw a guiding LuaError. */
    private static String httpHost(String url, String verb) {
        java.net.URL u;
        try {
            u = new java.net.URL(url);
        } catch(java.net.MalformedURLException e) {
            throw new LuaError(verb + ": malformed url \"" + url + "\" (" + e.getMessage() + ")");
        }
        String scheme = (u.getProtocol() == null) ? "" : u.getProtocol().toLowerCase(java.util.Locale.ROOT);
        if(!scheme.equals("http") && !scheme.equals("https"))
            throw new LuaError(verb + ": url scheme must be http or https (got \"" + scheme + "\")");
        String host = u.getHost();
        if((host == null) || host.isEmpty())
            throw new LuaError(verb + ": url has no host");
        return host;
    }

    /** The value a request carries for {@code name}, matched case-insensitively, or {@code null}. */
    private static String headerOf(Map<String, String> headers, String name) {
        for(Map.Entry<String, String> e : headers.entrySet()) {
            if(e.getKey().equalsIgnoreCase(name))
                return e.getValue();
        }
        return null;
    }

    /** Set {@code name} to {@code value}, replacing whatever spelling of it the request already carries. */
    private static void putHeader(Map<String, String> headers, String name, String value) {
        for(Iterator<Map.Entry<String, String>> i = headers.entrySet().iterator(); i.hasNext();) {
            if(i.next().getKey().equalsIgnoreCase(name))
                i.remove();
        }
        headers.put(name, value);
    }

    /** A timeout in milliseconds, defaulted and clamped to {@link LuaHttp}'s bounds. */
    private static int clampTimeout(int ms) {
        if(ms <= 0)
            return LuaHttp.DEFAULT_TIMEOUT;
        return (ms > LuaHttp.MAX_TIMEOUT) ? LuaHttp.MAX_TIMEOUT : ms;
    }

    /**
     * Build the POST request body bytes (N2b). {@code body} is a <b>string</b> (sent verbatim, UTF-8) or a
     * <b>table</b> (encoded with the shared {@link Json#write(LuaValue, boolean) strict} serializer and, unless
     * the addon set its own {@code Content-Type} in {@code opts.headers}, tagged {@code application/json}). A
     * nil body sends an empty POST; any other type throws a guiding {@link LuaError}. {@code headers} may be
     * mutated to add the JSON content type.
     */
    private static byte[] httpBody(LuaValue body, Map<String, String> headers, String verb) {
        if((body == null) || body.isnil())
            return new byte[0];                       // POST with no body
        if(body.isstring())
            return body.tojstring().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(body.istable()) {
            String json;
            try {
                json = Json.write(body, true);        // strict: non-serializable value / cycle → LuaError
            } catch(LuaError e) {
                throw new LuaError(verb + ": body table is not JSON-serializable (" + e.getMessage() + ")");
            }
            if(!hasContentType(headers))
                headers.put("Content-Type", "application/json");
            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new LuaError(verb + ": body must be a string (sent verbatim) or a table (encoded as JSON)");
    }

    /** Whether {@code headers} already carries a {@code Content-Type} (case-insensitive) the addon set itself. */
    private static boolean hasContentType(Map<String, String> headers) {
        for(String k : headers.keySet())
            if("content-type".equalsIgnoreCase(k))
                return true;
        return false;
    }
}
