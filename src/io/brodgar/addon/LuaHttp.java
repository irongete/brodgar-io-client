package io.brodgar.addon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
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
 * {@code 19-data-and-network.md} §3–§5). Pure Java, no Lua/{@code haven} state — it runs on an
 * {@link AddonManager} pool thread, never the UI thread ({@link #perform}), so it can safely block on
 * DNS/connect/transfer. {@link AddonManager} owns the pool, the result queue, the tick drain, and the
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
                "te", "trailer", "upgrade", "proxy-authorization", "proxy-connection", "user-agent" })
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
     * Run the request (blocking — pool thread only). The host allowlist was already enforced synchronously
     * at call ({@code requireNetwork}); here we resolve the host and reject private/loopback/link-local
     * addresses (§5.2), then perform the HTTP exchange with the caps applied. Any transport failure returns
     * {@code Result.fail(...)} — never throws — so the drain can hand the addon {@code ok=false, error=...}.
     */
    static Result perform(LuaHttpRequest r) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(r.url);
            String host = u.getHost();
            // §5.2 private/loopback block. Resolve once; note the documented resolve-then-connect DNS-rebinding
            // gap (a later hardening pins the checked IP into the connection — §9). We check ALL resolved
            // addresses so a host that returns both a public and a private A record can't sneak the private one.
            InetAddress[] addrs;
            try {
                addrs = InetAddress.getAllByName(host);
            } catch(Exception e) {
                return Result.fail("DNS resolution failed for " + host);
            }
            for(InetAddress a : addrs) {
                if(isBlockedAddress(a))
                    return Result.fail("host " + host + " resolves to a blocked address ("
                        + a.getHostAddress() + "; private/loopback ranges are refused)");
            }

            c = (HttpURLConnection)u.openConnection();
            c.setInstanceFollowRedirects(false);   // §5.6 v1: a 3xx is returned raw
            c.setConnectTimeout(r.timeout);
            c.setReadTimeout(r.timeout);
            c.setUseCaches(false);
            c.setRequestMethod(r.method);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");   // no gzip: we buffer raw bytes (size cap)
            if(r.headers != null) {
                for(Map.Entry<String, String> e : r.headers.entrySet()) {
                    if(headerAllowed(e.getKey()) && (e.getValue() != null))
                        c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if(r.body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(r.body.length);
                OutputStream os = c.getOutputStream();
                try { os.write(r.body); } finally { os.close(); }
            }

            int status = c.getResponseCode();   // performs the exchange
            InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
            byte[] data = readCapped(in);       // throws TooLarge past MAX_SIZE
            String bodyStr = new String(data, charsetOf(c));
            return Result.ok(status, bodyStr, lowerHeaders(c));
        } catch(TooLargeException e) {
            return Result.fail("response too large (> " + MAX_SIZE + " bytes)");
        } catch(SocketTimeoutException e) {
            return Result.fail("timeout");
        } catch(MalformedURLException e) {
            return Result.fail("malformed url: " + e.getMessage());
        } catch(IOException e) {
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch(RuntimeException e) {
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if(c != null)
                c.disconnect();
        }
    }

    /** Read a stream fully, aborting past {@link #MAX_SIZE}. {@code in} may be null (no body). */
    private static byte[] readCapped(InputStream in) throws IOException {
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
     * Whether {@code a} is a private/loopback/link-local/unspecified address that third-party code must not
     * reach (§5.2): blocks {@code 127/8}, {@code 10/8}, {@code 172.16/12}, {@code 192.168/16},
     * {@code 169.254/16}, {@code ::1}, {@code fc00::/7}, {@code fe80::/10} (and any multicast/wildcard).
     * Implemented via {@link InetAddress}'s own classifiers, which cover exactly these ranges per family.
     */
    static boolean isBlockedAddress(InetAddress a) {
        return a.isLoopbackAddress()        // 127/8, ::1
            || a.isAnyLocalAddress()        // 0.0.0.0, ::
            || a.isLinkLocalAddress()       // 169.254/16, fe80::/10
            || a.isSiteLocalAddress()       // 10/8, 172.16/12, 192.168/16
            || a.isMulticastAddress()
            || isUniqueLocalV6(a);          // fc00::/7 (not covered by isSiteLocalAddress for IPv6)
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
}
