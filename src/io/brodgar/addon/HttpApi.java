package io.brodgar.addon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * pool runs the blocking {@link LuaHttp} I/O off the UI thread; each worker enqueues its result onto
 * {@link #results}, drained on the tick ({@link #drainHttp}). The per-addon in-flight limit is enforced by a
 * non-blocking UI-thread scheduler ({@link #maybeStartHttp}) that leaves excess requests queued in
 * {@code Addon.requests} as not-yet-started ({@code started=false}) and launches them as running ones complete —
 * so a pool thread never blocks waiting on a per-addon permit.
 *
 * <p>The blocking socket I/O + security checks live one layer down in {@link LuaHttp}; the host-allowlist
 * gate ({@link #requireNetwork}) is here; the callback dispatch goes through {@link AddonManager#callLua}. The
 * tick drain, the session {@link #reset}, and the per-addon {@link #teardownRequests} are the three hooks
 * {@link AddonManager} calls. All members static; not instantiable.
 */
final class HttpApi {
    private HttpApi() {}

    // A single engine-lifetime bounded pool runs the blocking HttpURLConnection I/O off the UI thread; each
    // worker enqueues its result onto results, drained on the tick (the exact gob-delta pattern).
    private static volatile ExecutorService pool;   // lazily created on first request; engine-lifetime
    private static final Queue<HttpCompletion> results = new ConcurrentLinkedQueue<HttpCompletion>();

    /** A finished HTTP request (its result) captured on a pool thread, awaiting UI-thread delivery (N2a). */
    private static final class HttpCompletion {
        final LuaHttpRequest req;
        final LuaHttp.Result result;

        HttpCompletion(LuaHttpRequest req, LuaHttp.Result result) {
            this.req = req;
            this.result = result;
        }
    }

    /** Build the {@code hafen.http} table (get/post) for {@code owner}. Called from {@code installHafen}. */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable http = new LuaTable();
        http.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String url = a.checkjstring(1);
                LuaValue opts = LuaValue.NIL, cb = LuaValue.NIL;
                if(a.arg(2).isfunction()) {                       // get(url, cb)
                    cb = a.arg(2);
                } else {                                          // get(url, opts, cb) / get(url) / get(url, opts)
                    opts = a.arg(2);
                    cb = a.arg(3);
                }
                if(!cb.isnil() && !cb.isfunction())
                    throw new LuaError("hafen.http.get: callback must be a function");
                String host = httpHost(url, "hafen.http.get");
                requireNetwork(owner, host, "hafen.http.get");
                Map<String, String> headers = httpHeaders(opts, "hafen.http.get");
                int timeout = httpTimeout(opts);
                return newHttpRequest(owner, "GET", url, null, headers, timeout, cb);
            }
        });
        // post(url, body[, opts], cb) — send + read (N2b). body = a string (verbatim) or a table (→ JSON,
        // application/json unless opts.headers sets its own Content-Type). Same gate/limits/res table as get;
        // both verbs now follow up to 5 redirects, re-validating the allowlist + private-IP block per hop.
        http.set("post", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String url = a.checkjstring(1);
                LuaValue body = a.arg(2);
                LuaValue opts = LuaValue.NIL, cb = LuaValue.NIL;
                if(a.arg(3).isfunction()) {                       // post(url, body, cb)
                    cb = a.arg(3);
                } else {                                          // post(url, body, opts, cb) / (url, body[, opts])
                    opts = a.arg(3);
                    cb = a.arg(4);
                }
                if(!cb.isnil() && !cb.isfunction())
                    throw new LuaError("hafen.http.post: callback must be a function");
                String host = httpHost(url, "hafen.http.post");
                requireNetwork(owner, host, "hafen.http.post");
                Map<String, String> headers = httpHeaders(opts, "hafen.http.post");
                int timeout = httpTimeout(opts);
                byte[] bytes = httpBody(body, headers, "hafen.http.post");
                return newHttpRequest(owner, "POST", url, bytes, headers, timeout, cb);
            }
        });
        hafen.set("http", http);
    }

    /**
     * Gate a network verb (D-037): the addon must have DECLARED a {@code network} block whose {@code hosts}
     * allowlist matches {@code host}, or this throws a guiding Lua error <b>synchronously at call</b> (same
     * instant feedback as {@code requireActions}). The declaration IS the allowlist — a host not listed is
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
     * Build + register a request, enforcing the per-addon queue cap, then kick the scheduler. On the UI thread
     * (the call path). {@code cb} may be NIL (result discarded). Returns the Lua handle ({@code :cancel()}).
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
        maybeStartHttp(owner);

        LuaTable h = new LuaTable();
        h.set("cancel", new ZeroArgFunction() {
            public LuaValue call() {
                if(!req.dead) {
                    req.dead = true;
                    owner.requests.remove(req);
                    maybeStartHttp(owner);   // freeing a slot may let a queued request start
                }
                return LuaValue.NIL;
            }
        });
        return h;
    }

    /**
     * The per-addon in-flight scheduler (UI thread): while fewer than {@link LuaHttp#MAX_INFLIGHT} of the
     * addon's requests are running, submit the next not-yet-started live one to the pool. Non-blocking — a pool
     * thread never waits on a per-addon permit (which could starve the shared pool); excess requests simply sit
     * in {@code Addon.requests} as {@code started=false} until a running one completes (drain re-invokes this).
     */
    private static void maybeStartHttp(Addon owner) {
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
            submitHttpJob(r);
        }
    }

    /** Submit one request to the pool. The worker does only Java I/O; it enqueues the result for the tick drain. */
    private static void submitHttpJob(final LuaHttpRequest req) {
        pool().execute(new Runnable() {
            public void run() {
                if(req.dead)
                    return;                       // cancelled before we started
                LuaHttp.Result res = LuaHttp.perform(req);
                if(req.dead)
                    return;                       // cancelled while in flight → discard, no callback
                results.add(new HttpCompletion(req, res));
            }
        });
    }

    /** Drain completed HTTP requests on the UI thread: deliver each live one's res table + advance the scheduler. */
    static void drainHttp() {
        HttpCompletion hc;
        while((hc = results.poll()) != null) {
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

    /** Session init: drop stale HTTP completions (their requests were torn down by the teardown loop). */
    static void reset() {
        results.clear();
    }

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

    /** Read {@code opts.headers} (a string→string table) into a Java map, or an empty map. Validates types. */
    private static Map<String, String> httpHeaders(LuaValue opts, String verb) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if((opts == null) || !opts.istable())
            return out;
        LuaValue h = opts.get("headers");
        if(h.isnil())
            return out;
        if(!h.istable())
            throw new LuaError(verb + ": opts.headers must be a table of string keys/values");
        LuaValue k = LuaValue.NIL;
        while(true) {
            Varargs n = h.next(k);
            k = n.arg1();
            if(k.isnil())
                break;
            LuaValue v = n.arg(2);
            if(!k.isstring() || !v.isstring())
                throw new LuaError(verb + ": opts.headers keys and values must be strings");
            out.put(k.tojstring(), v.tojstring());
        }
        return out;
    }

    /** Read {@code opts.timeout} (ms), defaulting + clamping to {@link LuaHttp}'s bounds. */
    private static int httpTimeout(LuaValue opts) {
        int t = LuaHttp.DEFAULT_TIMEOUT;
        if((opts != null) && opts.istable()) {
            LuaValue to = opts.get("timeout");
            if(to.isnumber())
                t = to.toint();
        }
        if(t <= 0)
            t = LuaHttp.DEFAULT_TIMEOUT;
        if(t > LuaHttp.MAX_TIMEOUT)
            t = LuaHttp.MAX_TIMEOUT;
        return t;
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
