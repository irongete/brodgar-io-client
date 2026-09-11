package io.brodgar.addon;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import org.luaj.vm2.lib.OneArgFunction;
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
 * tick drain and the per-addon {@link #teardownRequests} are the two hooks {@link AddonManager} calls. A
 * session that ends needs no third: {@code AddonManager} drops the whole {@code SessionState}, and the queues
 * go with it. All members static; not instantiable.
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
     * Build the {@code hafen.http} section for {@code owner} — <b>the collection of its live requests</b>,
     * with {@code :request(url)}, {@code :get(url)} and {@code :post(url, body)} as its {@code extra} verbs
     * (095, A-115/A-118). Called from {@code installHafen}.
     *
     * <p><b>Constructed bare, dispatched on purpose.</b> A request is configured by chained setters and goes
     * nowhere until {@code :send()}, so every setter is legal until that call and none after — a rule with
     * no timing in it, where the shape before 095 sent on the next tick and every setter had to be chained
     * in the statement that created the request. A request never sent costs the wire nothing and holds no
     * slot against the cap; a cancelled one is never sent at all.
     */
    static void install(LuaTable hafen, final Addon owner) {
        // 095 (A-118): the section IS the collection of this addon's live requests. The set is bounded and
        // hard-capped -- 6 in flight, 64 pending, past which the call raises -- and it was the one bounded
        // set in the API you could not look at: hafen.timer(), hafen.sound(), hafen.asset() and
        // hafen.virtual():ghost() all answer "what of mine is live", so an addon that hit the cap got a raise it
        // could not have seen coming and no way to cancel its own backlog but to have kept every handle.
        LuaTable extra = new LuaTable();
        // request(url) -- 095 (A-115): a BARE request. Nothing is sent, so every setter is legal until
        // :send(), and the next-tick rule that used to make configuration a same-statement obligation is
        // gone with the call that scheduled it.
        extra.set("request", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.http()", "request");
                return newHttpRequest(owner, "GET", urlArg(a, 2, "hafen.http():request"));
            }
        });
        // get(url) / post(url, body) -- the one-line conveniences, and they take NO CALLBACK: the handler
        // has exactly one spelling, req:on("done", fn), which is the API's one notification verb.
        extra.set("get", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.http()", "get");
                String url = urlArg(a, 2, "hafen.http():get");
                refuseCallback(a, 3, "hafen.http():get(url)");
                return newHttpRequest(owner, "GET", url);
            }
        });
        extra.set("post", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.http()", "post");
                String url = urlArg(a, 2, "hafen.http():post");
                // audit2 B14 (ht-09): SLOT 3 AS WELL AS SLOT 4. The typo this refusal exists for is
                // :post(url, cb) -- the old shape with the body left out -- and that puts the function in
                // slot 3, which went to httpBody and came back "body must be a string or a table": true,
                // useless, and not the sentence written for the mistake. Slot 4 is the OTHER spelling of the
                // same typo, :post(url, body, cb), and both now meet the refusal that names the new shape.
                refuseCallback(a, 3, "hafen.http():post(url, body)");
                refuseCallback(a, 4, "hafen.http():post(url, body)");
                LuaValue h = newHttpRequest(owner, "POST", url);
                LuaHttpRequest req = resolve(h);
                req.method = "POST";
                if(Args.passed(a, 3))
                    req.body = httpBody(a.arg(3), req.headers, "hafen.http():post");
                return h;
            }
        });
        LuaValue coll = LuaCollection.create("hafen.http()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(LuaHttpRequest r : owner.requests) {
                    if(!r.dead && (r.handle != null))
                        out.add(r.handle);
                }
                return out;
            }

            /** A request has a URL, so a string filter is a substring test over it. */
            public String needle(LuaValue member) {
                LuaHttpRequest r = resolve(member);
                return (r == null) ? "" : r.url;
            }

            public boolean named() {
                return true;
            }

            /**
             * A request has no key. Note that {@code :get} is SHADOWED here by the section's own
             * convenience above -- {@code hafen.http():get(url)} builds one -- so this message is the
             * fallback if that verb ever moves, and {@code :find(url)} is the search either way. The
             * collision is catalogued: {@code audit/02-naming.md} C26 already has {@code :get} meaning an
             * HTTP GET, a collection member and a saved-variable table.
             */
            public String noGet() {
                return "a request has no key of its own -- it is the handle :request(url) handed you."
                    + " hafen.http():find(\"<url substring>\") searches, and :list() is all of them";
            }
        }, extra);
        Section.mount(hafen, "http", coll, null);
    }

    /**
     * A URL argument, through the house door so an explicit nil is refused like everywhere else — and
     * <b>validated here</b>, where it was written. {@link #httpOrigin} raises on anything that is not an
     * {@code http}/{@code https} address, so a typo is a refusal at {@code :request(url)} rather than
     * something the pool thread discovers with nobody left to tell.
     *
     * <p>The <b>permission</b> is not checked here: nothing has left the client yet. It is checked in
     * {@code :send()}, which is the call that reaches the network (095).
     */
    private static String urlArg(Varargs a, int i, String verb) {
        String url = Args.str(a, i, verb, "url", "an http:// or https:// address").tojstring();
        httpOrigin(url, verb);
        return url;
    }

    /**
     * <b>The one hard cut {@code Refusal} cannot carry</b> (095, A-116). It keys on a NAME, and what changed
     * here is an ARGUMENT COUNT: {@code hafen.http():get(url, cb)} still spells {@code get}. So the refusal
     * is written inside the verb, which is what {@code CLAUDE.md} asks for when a reshape has nothing to key
     * on — and it names the whole new shape rather than the argument.
     */
    private static void refuseCallback(Varargs a, int i, String verb) {
        if(!Args.passed(a, i) || !a.arg(i).isfunction())
            return;                     // ht-09: only a FUNCTION in that slot is this mistake
        throw new LuaError(verb + " takes no callback. The handler is req:on(\"done\", fn), the one"
            + " notification verb, and the request does not leave until you call :send():\n"
            + "  hafen.http():get(url):on(\"done\", function(res) end):send()\n"
            + "res is an object now too: res:ok() res:status() res:body() res:header(name) res:error().");
    }

    /** The request behind a handle, or {@code null} — the userdata is the record itself. */
    static LuaHttpRequest resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaHttpRequest) ? (LuaHttpRequest)o : null;
    }

    /**
     * Gate a network verb: <b>the key first, then the allowlist</b> (D-037, re-keyed by 093.4 / A-098).
     *
     * <p>Until 093 this was a second permission mechanism with none of the first one's vocabulary — no
     * {@link Permission} constant, no {@code <prefix>.*} group, and a consent surface of its own — so a user
     * who had learned that a key is {@code <section>.<verb>} wrote {@code "http.*"} and the addon failed to
     * load, while an addon that declared {@code network} got no line in the enable-time dialog at all and the
     * user approving it read about kin and items and nothing about the network.
     *
     * <p>Now {@code http.get} and {@code http.post} are ordinary catalogue keys and the {@code hosts} block is
     * <b>the argument of the key</b> — the shape {@code player.hand.use} already had for a nested one. The
     * catalogue decides <i>whether</i>, the allowlist decides <i>where</i>, and the consent dialog says both
     * in one line. Both refusals are synchronous at the call, before any I/O. (The resolved IP's
     * private/loopback check happens later, on the pool thread.)
     *
     * <p><b>The allowlist asked is the one the user approved</b>, carried on the {@link Addon} from the consent
     * record ({@link Addon#hostGranted}) — not the manifest on disk, which the addon writes and can rewrite
     * between one enable and the next. The manifest still says whether the addon declared any network at all,
     * because that refusal is about a declaration the author forgot rather than about a grant.
     */
    private static void requireNetwork(Addon owner, Permission perm, String origin, String verb) {
        AddonManager.requirePermission(AddonManager.current(), perm, verb);
        if((owner == null) || !owner.manifest.usesNetwork())
            throw new LuaError(verb + ": this addon declared \"" + perm.key + "\" but no hosts to reach — the"
                + " key says WHETHER and the allowlist says WHERE. Add \"network\": { \"hosts\": [\""
                + ((origin != null) ? origin : "example.com")
                + "\"] } to its manifest.json beside the permission.");
        // audit2 B08 (ht-05): the ORIGIN, not the host. The grant records a scheme and a port as well as a
        // name, so a host approved for https is no longer reachable in cleartext or on another port.
        if(!owner.hostGranted(origin))
            throw new LuaError(verb + ": \"" + origin + "\" is not an origin the user approved for this addon"
                + " (approved: " + owner.grantedHosts + ")."
                + (owner.manifest.hostAllowed(origin)
                   ? " Its manifest does list it, so it was added after consent was given: enable the addon"
                     + " again to be asked, and it is reachable once it is approved."
                   : " A declared host with no scheme means https on its own port; add the origin to"
                     + " \"network\": { \"hosts\": [...] } and enable the addon again."));
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
     * Build a request — <b>bare</b>. On the UI thread (the call path). Returns the Lua request object:
     * {@code :url()} {@code :method(name)} {@code :body(v)} {@code :header(name, value)} {@code :timeout(ms)}
     * {@code :on("done", fn)} {@code :send()} and {@code :cancel()}.
     *
     * <p><b>Nothing is registered or scheduled here</b> (095): the queue cap is charged by {@code :send()},
     * because a request that never goes reaches no wire and should hold no slot against a cap that counts
     * what is in flight. That is also what makes the setters honest — there is no tick coming for a request
     * that has not been sent, so a {@code :timeout(5000)} written a minute later cannot race a pool thread
     * already reading the field.
     */
    private static LuaValue newHttpRequest(final Addon owner, String method, String url) {
        final LuaHttpRequest req = new LuaHttpRequest(owner, method, url, null,
                                                      new LinkedHashMap<String, String>(),
                                                      LuaHttp.DEFAULT_TIMEOUT);
        req.origin = httpOrigin(url, "hafen.http():request");
        // The request handle is userdata over the record, the one shape every handle in the API has:
        // req.cancel = nil is refused where a table let an addon delete its own way of stopping a request,
        // a typo raises naming the vocabulary, and tostring(req) names the method and the URL.
        final LuaValue h = LuaValue.userdataOf(req);
        req.handle = h;
        LuaTable m = new LuaTable();
        // url() / method() -- 095 (A-118): what the collection's filter matches on, and what an addon
        // looking at its own backlog needs to tell one request from another. Reads only: they are what the
        // request IS, and :request(url) is where a different one comes from.
        m.set("url", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(req.url);
            }
        });
        m.set("method", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue v = Args.written(a, 2, "request:method", "name");
                if(v == null)
                    return LuaValue.valueOf(req.method);
                requireUnsent(req, "method");
                String nm = Args.str(v, "request:method", "name", "\"GET\" or \"POST\"")
                                .tojstring().toUpperCase();
                if(!"GET".equals(nm) && !"POST".equals(nm))
                    throw new LuaError("request:method(name): the client speaks GET and POST, got \""
                        + nm + "\"");
                req.method = nm;
                return h;
            }
        });
        // body(v) -- a string sent verbatim, or a table encoded as JSON and tagged application/json unless
        // this addon set its own Content-Type. The same door hafen.http():post's second argument went
        // through, which is now this setter and nothing else.
        m.set("body", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue v = Args.written(a, 2, "request:body", "body");
                if(v == null)
                    return (req.body == null) ? LuaValue.NIL
                        : LuaValue.valueOf(new String(req.body, java.nio.charset.StandardCharsets.UTF_8));
                requireUnsent(req, "body");
                req.body = httpBody(v, req.headers, "request:body");
                return h;
            }
        });
        // header(name) reads, header(name, value) writes and returns SELF so it chains. Case-insensitive:
        // one header has one value however it is spelled, which is also what the wire means by it.
        m.set("header", new VarArgFunction() {
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
        m.set("timeout", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue ms = Args.written(a, 2, "request:timeout", "ms");
                if(ms == null)
                    return LuaValue.valueOf(req.timeout);
                requireUnsent(req, "timeout");
                // ht-11: the range is REFUSED, never clamped. A deadline silently rewritten to the default
                // (a 0, a negative) or down to the ceiling (an hour) is a request that waits for a length
                // nobody wrote, and the only way to see it was to read the property back.
                req.timeout = (int)Args.integer(ms, "request:timeout", "ms", "milliseconds", 1,
                                                LuaHttp.MAX_TIMEOUT);
                return h;
            }
        });
        // on(key, fn) -- 095 (A-116): THE HANDLER, and it is the API's one notification verb rather than a
        // positional argument. It hands back a Sub, so it ends with sub:off() like every other subscription,
        // and "progress" costs nothing to add beside it later. Legal after :send() too: a request in flight
        // has not come back yet, so arming a second listener is a moment rather than a mistake.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // Args first, and one argument at a time (the hafen.console():on shape). The key is asked for
                // its TYPE: the old `isnumber() || !isstring()` pair refused "42" -- an ordinary string, since
                // in LuaJ a string that scans as a number answers isnumber() too -- with the sentence meant
                // for a missing pair. Now a real number is refused as the wrong kind, and a string that is
                // not an event reaches the refusal below, which names it.
                String key = Args.str(a, 2, "request:on", "key", "the event to hear, which is done").tojstring();
                LuaValue fnArg = Args.required(a, 3, "request:on", "fn");
                if(!fnArg.isfunction())
                    throw new LuaError("request:on(key, fn): fn must be a function -- it runs once with the"
                        + " result, got " + fnArg.typename());
                if(!LuaHttpRequest.DONE.equals(key))
                    throw new LuaError("request:on(key, fn): a request has no event '" + key + "' -- it has:"
                        + " done, which fires once with the result (res:ok() says whether the exchange"
                        + " completed at all)");
                return req.subs.on(key, fnArg);
            }
        });
        // send() -- 095 (A-115): DISPATCH, said out loud. Everything above is legal until this call and
        // nothing above is legal after it, which is a rule with no timing in it -- where the old shape sent
        // on the next tick and every setter had to be chained in the statement that created the request.
        //   The per-addon queue cap is charged HERE, not at construction: a request that is never sent costs
        //   the wire nothing, so it should not hold a slot against the cap either.
        m.set("send", new ZeroArgFunction() {
            public LuaValue call() {
                // The GATE, and this is the call it belongs to (D-213 for a builder): nothing has left the
                // client until :send(), and the method it leaves as is not known until then either. Both
                // halves in one door, the key before the allowlist, exactly as 093 wrote it.
                requireNetwork(owner, "POST".equals(req.method) ? Permission.HTTP_POST : Permission.HTTP_GET,
                               req.origin, "request:send");
                if(req.dead)
                    throw new LuaError("request:send(): this request was cancelled");
                if(req.sent)
                    throw new LuaError("request:send(): this request has already been sent -- build another"
                        + " with hafen.http():request(url)");
                int pending = 0;
                for(LuaHttpRequest r : owner.requests)
                    if(!r.dead) pending++;
                if(pending >= LuaHttp.QUEUE_CAP)
                    throw new LuaError("request:send(): too many pending requests for this addon (" + pending
                        + " >= " + LuaHttp.QUEUE_CAP + "); hafen.http():count() is how many, and"
                        + " hafen.http():list() is which -- cancel some or wait.");
                req.sent = true;
                owner.requests.add(req);
                queueStart(owner);
                return h;
            }
        });
        // cancel() -- never sent if it has not gone yet, and its handler never fires if it has.
        m.set("cancel", new ZeroArgFunction() {
            public LuaValue call() {
                if(!req.dead) {
                    kill(req);
                    owner.requests.remove(req);
                    queueStart(owner);   // freeing a slot may let a queued request start
                }
                return h;                 // the receiver: every ending chains
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("request", m,
            "a request",
            "it is dispatched by :send(): every setter is legal until then and none after"));
        mt.set("__name", LuaValue.valueOf("Request"));
        mt.set("__tostring", new ZeroArgFunction() {
            public LuaValue call() {
                String state = req.dead ? ", cancelled" : (req.started ? ", sent" : (req.sent ? ", queued" : ""));
                return LuaValue.valueOf("Request(" + req.method + " " + req.url + state + ")");
            }
        });
        h.setmetatable(mt);
        return h;
    }

    /** A setter refuses once the request has been SENT: the wire has it, so writing the field would lie. */
    private static void requireUnsent(LuaHttpRequest req, String verb) {
        if(req.dead)
            throw new LuaError("request:" + verb + ": this request was cancelled");
        if(req.sent)
            throw new LuaError("request:" + verb + ": this request has already been sent -- configure it"
                + " before :send(), which is the whole of the rule");
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
                // audit2 B08 (ht-07): AND THE LOGIN THIS WAS FOR MAY HAVE ENDED. The queue is that session's
                // and its only drain is that session's tick, so a completion filed after the UI died is a
                // result with no handler and up to 8 MB of body held behind a reference nothing reads. The
                // request is ended instead, which is what a cancelled one already does.
                if(!AddonManager.draining(st)) {
                    req.dead = true;
                    return;
                }
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
            // 095 (A-116/A-117): the handler is a subscription and the payload is an object.
            req.subs.fire(LuaHttpRequest.DONE, LuaHttpResult.of(owner, hc.result));
            maybeStartHttp(owner);                // a slot freed → launch any queued request
        }
    }

    /**
     * <b>End one request</b> — the flag every reader keeps, and the exchange under the worker. The two are one
     * act: a request whose flag alone is set has stopped being listened to, not stopped, and its round trip
     * goes on reaching the host it names. Both endings ({@code :cancel()} and the teardown below) come here,
     * so there is one answer to what ending a request does.
     */
    private static void kill(LuaHttpRequest r) {
        r.dead = true;        // in-flight workers see this and discard; queued ones never start
        LuaHttp.abort(r);     // ...and the exchange itself ends, rather than finishing unheard
    }

    /** Teardown (N2a): cancel every in-flight request so a reload/disable/relog leaks nothing + never calls back. */
    static void teardownRequests(Addon a) {
        if(a.requests.isEmpty())
            return;
        for(LuaHttpRequest r : a.requests)
            kill(r);
        a.requests.clear();
    }

    /**
     * Validate an {@code http}/{@code https} URL and return its (non-empty) host, or throw a guiding LuaError.
     *
     * <p><b>The parse is strict, because what it returns is the gate's question.</b> This host is
     * {@code req.origin}, the one {@code requireNetwork} measures against the consent record, so it is
     * produced by {@link java.net.URI} — an RFC 3986 parse that refuses a space, a brace, a bare
     * {@code %} and the rest of what a lenient parse hands on for the server to interpret. Both refusals
     * are caught: {@code URI.create} and {@code toURL} raise {@link IllegalArgumentException} (illegal
     * character, malformed escape, or a relative url with no protocol to open), {@code toURL} raises
     * {@link java.net.MalformedURLException} for a scheme with no handler. Either way the url is named.
     */
    private static String httpOrigin(String url, String verb) {
        java.net.URL u;
        try {
            u = java.net.URI.create(url).toURL();
        } catch(java.net.MalformedURLException | IllegalArgumentException e) {
            String why = Refusal.reason(e);
            throw new LuaError(verb + ": malformed url \"" + url + "\" (" + why + ")");
        }
        String scheme = (u.getProtocol() == null) ? "" : u.getProtocol().toLowerCase(java.util.Locale.ROOT);
        if(!scheme.equals("http") && !scheme.equals("https"))
            throw new LuaError(verb + ": url scheme must be http or https (got \"" + scheme + "\")");
        String host = u.getHost();
        if((host == null) || host.isEmpty())
            throw new LuaError(verb + ": url has no host");
        // audit2 B08 (ht-05): the scheme and the port travel with the name from here on, because the grant
        // is a grant to one origin. LuaHttp builds the same string per hop, out of the same method.
        return Manifest.origin(scheme, host, (u.getPort() > 0) ? u.getPort() : Manifest.defaultPort(scheme));
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
                throw new LuaError(verb + ": body table is not JSON-serializable (" + Refusal.reason(e) + ")");
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
