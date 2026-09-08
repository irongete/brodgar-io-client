package io.brodgar.addon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The blocking-I/O worker + security checks behind {@code hafen.http} (N2a, spec
 * {@code 19-data-and-network.md} §3–§5). Pure Java, no Lua/{@code haven} state — it runs on a
 * {@link HttpApi} pool thread, never the UI thread ({@link #perform}), so it can safely block on
 * DNS/connect/transfer. {@link HttpApi} owns the pool, the result queue, the tick drain, and the
 * host-allowlist gate ({@code requireNetwork}); this class owns everything below the socket:
 * {@link HttpURLConnection}, the private/loopback-IP block, the resource caps, and header hygiene.
 *
 * <p><b>Java 1.8 compatible</b> — {@link HttpURLConnection} (not {@code java.net.http.HttpClient}, which
 * needs Java 11 bytecode). TLS uses the JDK default trust store (certificates are validated; we never
 * disable verification). All members static; not instantiable.
 */
final class LuaHttp {
    private LuaHttp() {}

    // -- resource caps (D-018 spirit; all -D-tunable) -------------------------------------------------
    /** Max response body buffered in memory, {@code -Dhaven.addon.http.maxsize} (default 8 MB). */
    static final int  MAX_SIZE      = (int)propLong("haven.addon.http.maxsize", 8L * 1024 * 1024);
    /** Default request timeout (ms) when {@code opts.timeout} is absent, {@code -Dhaven.addon.http.timeout}. */
    static final int  DEFAULT_TIMEOUT = (int)propLong("haven.addon.http.timeout", 10_000L);
    /** Hard cap on {@code opts.timeout} (ms), {@code -Dhaven.addon.http.maxtimeout}. */
    static final int  MAX_TIMEOUT   = (int)propLong("haven.addon.http.maxtimeout", 60_000L);
    /** Shared pool size — the global fan-out ceiling across all addons, {@code -Dhaven.addon.http.pool}. */
    static final int  POOL_THREADS  = (int)propLong("haven.addon.http.pool", 8L);
    /** Concurrent in-flight requests per addon (excess queued), {@code -Dhaven.addon.http.inflight}. */
    static final int  MAX_INFLIGHT  = (int)propLong("haven.addon.http.inflight", 6L);
    /** Hard cap on a single addon's total pending (in-flight + queued) requests, {@code -Dhaven.addon.http.queuecap}. */
    static final int  QUEUE_CAP     = (int)propLong("haven.addon.http.queuecap", 64L);
    /** Max redirect hops followed before aborting (N2b; §5.6), {@code -Dhaven.addon.http.maxredirects}. */
    static final int  MAX_REDIRECTS = (int)propLong("haven.addon.http.maxredirects", 5L);

    /** A generic, non-identifying User-Agent (§5.5 — never the character/account). */
    static final String USER_AGENT = "brodgar-addon/1";

    /**
     * Request headers an addon may NOT set — hop-by-hop / transport headers the connection owns. Lower-cased;
     * matched case-insensitively (§5.5). {@code Content-Length}/{@code Content-Type} for a body are set by us.
     */
    private static final Set<String> FORBIDDEN_HEADERS = new HashSet<String>();
    static {
        for(String h : new String[] {
                "host", "content-length", "connection", "keep-alive", "transfer-encoding",
                "te", "trailer", "upgrade", "proxy-authorization", "proxy-connection", "user-agent",
                // audit2 B08 (ht-08): and these two, which are not hop-by-hop but are OURS all the same.
                // `Accept-Encoding` is the premise the size cap is written against -- we ask for `identity`
                // because MAX_SIZE counts the bytes we buffer, and an addon setting `gzip` replaced it, so a
                // compressed body decompressed past the cap. `Cookie` is refused because http.md says there
                // are none: nothing here keeps a jar, so a hand-written one is a session an addon carries
                // across requests behind the user's back.
                "accept-encoding", "cookie" })
            FORBIDDEN_HEADERS.add(h);
    }

    /** Whether an addon-supplied request header name is allowed (not hop-by-hop / transport-owned). */
    static boolean headerAllowed(String name) {
        return (name != null) && !FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The outcome of one request: a received reply ({@link #ok}) or a transport failure. Immutable. */
    static final class Result {
        final boolean ok;
        final int status;                     // valid iff ok
        final String body;                    // valid iff ok
        final Map<String, String> headers;    // lower-cased keys; valid iff ok
        final String error;                   // human-readable; valid iff !ok

        private Result(boolean ok, int status, String body, Map<String, String> headers, String error) {
            this.ok = ok; this.status = status; this.body = body; this.headers = headers; this.error = error;
        }
        static Result ok(int status, String body, Map<String, String> headers) {
            return new Result(true, status, body, headers, null);
        }
        static Result fail(String error) {
            return new Result(false, 0, null, null, error);
        }
    }

    /**
     * Run the request (blocking — pool thread only). The initial host's allowlist was already enforced
     * synchronously at call ({@code requireNetwork}); here we (re-)validate every hop's host — allowlist +
     * private/loopback/link-local IP block (§5.2) — then perform the HTTP exchange with the caps applied.
     *
     * <p><b>Redirects (N2b, §5.6).</b> We follow up to {@link #MAX_REDIRECTS} 3xx hops manually
     * ({@code setInstanceFollowRedirects(false)} so the JDK never silently jumps for us), and each hop's
     * {@code Location} host is <b>re-checked against the hosts the user approved and the private-IP block</b> —
     * a redirect to a non-approved or private host aborts, so redirects can never escape the granted hosts.
     * Per HTTP semantics a 303 (and a 301/302 on a non-idempotent method) demotes to {@code GET} and drops the
     * body; 307/308 preserve method + body.
     *
     * <p>Any transport failure returns {@code Result.fail(...)} — never throws — so the drain can hand the addon
     * {@code ok=false, error=...}.
     */
    static Result perform(LuaHttpRequest r) {
        String method = r.method;
        byte[] body = r.body;
        String url = r.url;
        int hops = 0;
        // audit2 B08 (ht-03): ONE DEADLINE OVER THE WHOLE EXCHANGE. The two timeouts below are per SOCKET
        // OPERATION -- setReadTimeout is SO_TIMEOUT, a bound on one read and not on the transfer -- and they
        // were set inside this loop, per hop, so each of the six hops MAX_REDIRECTS permits was entitled to
        // the full 60 s the page documents as the ceiling, and a server trickling one byte inside every
        // window held one of the eight pool threads every addon shares for as long as it liked. This is the
        // clock the page's number now means: the whole exchange, redirects and body included.
        final long deadline = System.currentTimeMillis() + r.timeout;
        // audit2 B08 (ht-04): the host of the FIRST hop. The addon's own headers were re-applied on every
        // iteration, so a 302 from one approved host to another handed the first host's Authorization to the
        // second -- two hosts the user approved separately, and a bearer token for one is not a bearer token
        // for the other. They now travel no further than the host they were addressed to.
        String firstHost = null;
        boolean sameHost = true;
        while(true) {
            // Torn down or cancelled: no hop of a dead request goes out. The redirect loop is the reason this
            // is read here and not only at submit -- a request that died mid-flight used to follow the rest of
            // its chain, sending its headers to every host on it, for an addon that had stopped running.
            if(r.dead)
                return Result.fail("cancelled");
            int left = (int)(deadline - System.currentTimeMillis());
            if(left <= 0)
                return Result.fail("timeout");
            HttpURLConnection c = null;
            try {
                URL u;
                try {
                    // RFC 3986, as at :request(url) -- and on a hop, of the Location it resolved
                    u = URI.create(url).toURL();
                } catch(MalformedURLException | IllegalArgumentException e) {
                    return Result.fail("malformed url: " + e.getMessage());
                }
                // Per-hop ORIGIN validation: the grant (a redirect cannot escape it) and the private/loopback
                // block. ALL resolved addresses are checked, so a host with both a public and a private A
                // record cannot sneak the private one -- and the ONE resolve this does is the address the
                // connection is then made to (ht-01, see open()).
                Hop hop = validateHop(r, u);
                if(hop.bad != null)
                    return hop.bad;
                String host = u.getHost().toLowerCase(Locale.ROOT);
                if(firstHost == null)
                    firstHost = host;
                else if(!firstHost.equals(host))
                    sameHost = false;

                c = open(u, hop.pin);
                r.conn = c;                            // ...so an ending can close this exchange under us
                if(r.dead)
                    return Result.fail("cancelled");   // it died while we opened: nothing has gone out yet
                c.setInstanceFollowRedirects(false);   // we follow manually so every hop is re-validated
                c.setConnectTimeout(left);             // ht-03: what is LEFT of the exchange, not a fresh one
                c.setReadTimeout(left);
                c.setUseCaches(false);
                c.setRequestMethod(method);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept-Encoding", "identity");   // no gzip: we buffer raw bytes (size cap)
                if((r.headers != null) && sameHost) {  // ht-04: the headers stop at the host they name
                    for(Map.Entry<String, String> e : r.headers.entrySet()) {
                        if(headerAllowed(e.getKey()) && (e.getValue() != null))
                            c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                if(body != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    OutputStream os = c.getOutputStream();
                    try { os.write(body); } finally { os.close(); }
                }

                int status = c.getResponseCode();   // performs the exchange
                if(isRedirect(status)) {
                    String loc = c.getHeaderField("Location");
                    if((loc != null) && !loc.isEmpty()) {
                        if(++hops > MAX_REDIRECTS)
                            return Result.fail("too many redirects (> " + MAX_REDIRECTS + ")");
                        URL next;
                        try {
                            // resolve a relative Location against the current URL, by RFC 3986
                            next = u.toURI().resolve(loc).toURL();
                        } catch(URISyntaxException | MalformedURLException | IllegalArgumentException e) {
                            return Result.fail("malformed redirect Location \"" + loc + "\": " + e.getMessage());
                        }
                        url = next.toString();
                        // 303 See Other → GET; 301/302 on a body-bearing method → GET (browser convention);
                        // 307/308 preserve the method + body.
                        if((status == 303)
                           || (((status == 301) || (status == 302)) && !method.equals("GET") && !method.equals("HEAD"))) {
                            method = "GET";
                            body = null;
                        }
                        // finally disconnects c, then re-loop on the new url -- where validateHop checks it
                        // again, scheme included, so a Location naming another scheme never reaches
                        // openConnection and the (HttpURLConnection) cast behind it.
                        continue;
                    }
                    // 3xx with no Location → nothing to follow; fall through and return it raw.
                }
                InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
                byte[] data = readCapped(in, deadline);   // TooLarge past MAX_SIZE, Deadline past the clock
                String bodyStr = new String(data, charsetOf(c));
                return Result.ok(status, bodyStr, lowerHeaders(c));
            } catch(TooLargeException e) {
                return Result.fail("response too large (> " + MAX_SIZE + " bytes)");
            } catch(DeadlineException e) {
                return Result.fail("timeout");
            } catch(SocketTimeoutException e) {
                return Result.fail("timeout");
            } catch(MalformedURLException e) {
                return Result.fail("malformed url: " + e.getMessage());
            } catch(IOException e) {
                return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch(RuntimeException e) {
                return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                r.conn = null;
                if(c != null)
                    c.disconnect();
            }
        }
    }

    /**
     * <b>Stop a dead request's exchange where it stands</b> (N2a). {@code dead} alone suppresses the callback
     * and nothing else: the worker goes on to finish the round trip, so a disabled addon's request still
     * reaches the host with its headers and still follows its redirects. Closing the connection ends the
     * exchange instead — the blocked read raises, {@link #perform} returns a failure nothing is waiting for,
     * and no later hop is opened.
     *
     * <p>Called from the UI thread while the worker is inside {@link #perform}, which is exactly what
     * {@link HttpURLConnection#disconnect()} is for. A request that has opened nothing yet holds no
     * connection, and one between hops finds {@code dead} itself at the top of the loop. Best-effort: a
     * connection closing under the worker at the same moment is already doing what this asks.
     */
    static void abort(LuaHttpRequest r) {
        HttpURLConnection c = r.conn;
        if(c == null)
            return;
        try {
            c.disconnect();
        } catch(RuntimeException e) { /* already closed, or closing under the worker: nothing left to do */ }
    }

    /** True for the 3xx statuses we follow (N2b): 301, 302, 303, 307, 308. */
    private static boolean isRedirect(int status) {
        return (status == 301) || (status == 302) || (status == 303) || (status == 307) || (status == 308);
    }

    /**
     * <b>One hop, checked and pinned</b> (audit2 B08) — either the failure that stops the exchange, or the
     * address the connection is to be made to. It is one object because the two answers come out of one
     * resolve, and splitting them is what let the check and the connect disagree.
     */
    private static final class Hop {
        final Result bad;          // non-null: the hop is refused, and this is why
        final InetAddress pin;     // the vetted address, valid iff bad == null

        private Hop(Result bad, InetAddress pin) {
            this.bad = bad;
            this.pin = pin;
        }
        static Hop refused(String why) {
            return new Hop(Result.fail(why), null);
        }
        static Hop at(InetAddress a) {
            return new Hop(null, a);
        }
    }

    /**
     * Validate one hop before connecting: the scheme must be http/https, the <b>origin</b> must be one the
     * user approved for the addon ({@link Addon#hostGranted} — the consent record, not the manifest on disk),
     * and no resolved address may be private/loopback/link-local (§5.2). Returns the vetted address to
     * connect to, or the failure.
     *
     * <p><b>Every hop, including the first</b>, so a redirect can never escape the granted origins and the
     * first hop's synchronous check at the call is confirmed here where the packet actually goes.
     *
     * <p><b>The origin, and not the host</b> (ht-05). The grant was {@code URL.getHost()} alone — the scheme
     * was validated per request but never recorded and the port never entered the match at all — so a host
     * the user approved for {@code https} was equally reachable in cleartext, on any port. {@link Manifest}
     * builds both sides of the comparison, so the pattern and the url are read by one rule.
     *
     * <p><b>ONE resolve</b> (ht-01), whose answer is handed back to be connected to. It used to resolve here,
     * check what it found, and then let {@code openConnection} resolve the name a second time and connect to
     * whatever THAT answered — so a host answering public at check-time and 127.0.0.1 a moment later reached
     * loopback with the addon's headers on it.
     */
    private static Hop validateHop(LuaHttpRequest r, URL u) {
        String scheme = (u.getProtocol() == null) ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if(!scheme.equals("http") && !scheme.equals("https"))
            return Hop.refused("hop to a non-http(s) url refused: " + u);
        String host = u.getHost();
        if((host == null) || host.isEmpty())
            return Hop.refused("hop url has no host: " + u);
        String origin = Manifest.origin(scheme, host,
                                        (u.getPort() > 0) ? u.getPort() : Manifest.defaultPort(scheme));
        if((r.owner == null) || !r.owner.hostGranted(origin))
            return Hop.refused("hop to non-allowlisted origin \"" + origin + "\" refused (D-037: a request"
                + " reaches the scheme, host and port the user approved, and a redirect may not escape them)");
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch(Exception e) {
            return Hop.refused("DNS resolution failed for " + host);
        }
        if(addrs.length == 0)
            return Hop.refused("DNS resolution failed for " + host);
        for(InetAddress a : addrs) {
            if(isBlockedAddress(a))
                return Hop.refused("host " + host + " resolves to a blocked address ("
                    + a.getHostAddress() + "; private/loopback ranges are refused)");
        }
        return Hop.at(addrs[0]);
    }

    /**
     * <b>Open the connection to the address the hop was vetted at</b> (audit2 B08, ht-01).
     *
     * <p>For {@code http} the connection is made through an explicit proxy address, which is the one hook
     * {@link HttpURLConnection} offers for choosing where the socket goes: the JDK connects to
     * {@code pin} and writes the request in absolute form, so the {@code Host} header still names the host
     * the url does and the origin server — which RFC 7230 §5.3.2 requires to accept that form — answers for
     * exactly the address we checked. No second name lookup happens anywhere on that path.
     *
     * <p>For {@code https} the connection is made by name, and the pin is the <b>certificate</b>: whatever
     * address the name resolves to at connect time has to present a chain valid for that host, which an
     * attacker's rebound private address cannot. That is a stronger binding than an IP, and it is the reason
     * this half needs no proxy: the JDK gives no way to choose the address without also taking over SNI and
     * hostname verification, and re-implementing certificate identity to gain nothing would be the worse
     * trade. Plain http, which has no such binding, is the half that can reach a metadata service.
     */
    private static HttpURLConnection open(URL u, InetAddress pin) throws IOException {
        String scheme = (u.getProtocol() == null) ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if(scheme.equals("http") && (pin != null)) {
            int port = (u.getPort() > 0) ? u.getPort() : 80;
            Proxy at = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pin, port));
            return (HttpURLConnection)u.openConnection(at);
        }
        return (HttpURLConnection)u.openConnection();
    }

    /**
     * Read a stream fully, aborting past {@link #MAX_SIZE} and past {@code deadline} (audit2 B08, ht-03).
     * {@code in} may be null (no body).
     *
     * <p>The socket timeout bounds one {@code read}, so a server dribbling a byte inside every window was
     * never timed out by it however long the body took. The exchange's own clock is checked per chunk, which
     * is where the trickle is actually visible.
     */
    private static byte[] readCapped(InputStream in, long deadline) throws IOException {
        if(in == null)
            return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0, n;
        try {
            while((n = in.read(chunk)) >= 0) {
                total += n;
                if(total > MAX_SIZE)
                    throw new TooLargeException();
                if(System.currentTimeMillis() > deadline)
                    throw new DeadlineException();
                buf.write(chunk, 0, n);
            }
        } finally {
            in.close();
        }
        return buf.toByteArray();
    }

    /** Response charset from {@code Content-Type} (default UTF-8; unknown/invalid → UTF-8). */
    private static Charset charsetOf(HttpURLConnection c) {
        String ct = c.getContentType();
        if(ct != null) {
            for(String part : ct.split(";")) {
                part = part.trim();
                if(part.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String cs = part.substring("charset=".length()).trim().replace("\"", "");
                    try {
                        if(Charset.isSupported(cs))
                            return Charset.forName(cs);
                    } catch(RuntimeException e) { /* fall through to UTF-8 */ }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Response headers with lower-cased keys (HTTP headers are case-insensitive); the status line dropped. */
    private static Map<String, String> lowerHeaders(HttpURLConnection c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for(Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if(e.getKey() == null)   // the null key holds the "HTTP/1.1 200 OK" status line — not a header
                continue;
            List<String> vals = e.getValue();
            out.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", vals));
        }
        return out;
    }

    /**
     * Whether {@code a} is an address third-party code must not reach (§5.2). The JDK's own classifiers
     * cover {@code 127/8}, {@code 10/8}, {@code 172.16/12}, {@code 192.168/16}, {@code 169.254/16},
     * {@code ::1}, {@code fe80::/10} and any multicast or wildcard address; {@code fc00::/7} and three IPv4
     * ranges they do not classify are added by hand below.
     *
     * <p><b>The three by hand are audit2 B08 (ht-06)</b>, and they were reachable: {@code 100.64/10} is
     * carrier-grade NAT, where a home router's own management interface commonly sits; {@code 192.0.0/24} is
     * the IETF protocol block, which carries DS-Lite's {@code 192.0.0.1} gateway; {@code 198.18/15} is the
     * benchmarking range that routes to lab equipment on the networks that use it. {@code http.md} prints
     * its ranges as a closed list, so an omission there is a promise the client was not keeping.
     */
    static boolean isBlockedAddress(InetAddress a) {
        return a.isLoopbackAddress()        // 127/8, ::1
            || a.isAnyLocalAddress()        // 0.0.0.0, ::
            || a.isLinkLocalAddress()       // 169.254/16, fe80::/10
            || a.isSiteLocalAddress()       // 10/8, 172.16/12, 192.168/16
            || a.isMulticastAddress()
            || isUniqueLocalV6(a)           // fc00::/7 (not covered by isSiteLocalAddress for IPv6)
            || isReservedV4(a);             // 100.64/10, 192.0.0/24, 198.18/15
    }

    /**
     * The IPv4 ranges the JDK classifies as ordinary public addresses and which are not (ht-06):
     * {@code 100.64.0.0/10}, {@code 192.0.0.0/24} and {@code 198.18.0.0/15}.
     */
    private static boolean isReservedV4(InetAddress a) {
        byte[] b = a.getAddress();
        if(b.length != 4)
            return false;
        int o0 = b[0] & 0xff, o1 = b[1] & 0xff, o2 = b[2] & 0xff;
        if((o0 == 100) && (o1 >= 64) && (o1 <= 127))       // 100.64.0.0/10 - carrier-grade NAT
            return true;
        if((o0 == 192) && (o1 == 0) && (o2 == 0))          // 192.0.0.0/24 - IETF protocol assignments
            return true;
        return (o0 == 198) && ((o1 == 18) || (o1 == 19));  // 198.18.0.0/15 - benchmarking
    }

    /** IPv6 unique-local {@code fc00::/7} (the IPv6 analog of RFC-1918; JDK has no direct predicate for it). */
    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return (b.length == 16) && ((b[0] & 0xfe) == 0xfc);
    }

    private static long propLong(String key, long def) {
        try {
            String v = System.getProperty(key);
            return (v != null) ? Long.parseLong(v.trim()) : def;
        } catch(RuntimeException e) {
            return def;
        }
    }

    /** Internal signal that the response exceeded {@link #MAX_SIZE} (mapped to a clean transport error). */
    private static final class TooLargeException extends IOException {
    }

    /** Internal signal that the exchange ran past its deadline mid-body (audit2 B08, ht-03). */
    private static final class DeadlineException extends IOException {
    }
}
