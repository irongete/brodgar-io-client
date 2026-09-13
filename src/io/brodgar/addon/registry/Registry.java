package io.brodgar.addon.registry;

import haven.Utils;
import io.brodgar.addon.AddonManager;
import io.brodgar.addon.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <b>The hub client</b> — the client's side of the addon hub's JSON API ({@code brodgar-io-addons}, its
 * {@code README.md} being the contract): {@link #search} and {@link #lookup} read the list, each on the one
 * worker thread this class owns, and each hands back a {@link Request} the caller <b>polls</b> from its own
 * tick. No callback, no listener: nothing here ever touches a widget, so no widget is ever touched off the
 * UI thread, and an answer nobody polls for is simply never read.
 *
 * <p><b>The base URL</b> is {@link #DEFAULT_BASE} unless {@code -Dhaven.addon.registry=<base>} names another
 * ({@link #base}). An {@code https://} base is taken as it is; a plain {@code http://} one is taken for a
 * <b>loopback host only</b> — {@code localhost}, {@code 127.0.0.1}, {@code ::1} — because the development hub
 * runs there and nowhere else is a package worth fetching in the clear; anything else is logged and the
 * default kept. The client stays anonymous: it reads and downloads, it never signs in, so no header here
 * carries anything about the player.
 *
 * <p><b>HTTP</b> is {@link HttpURLConnection} as {@code LuaHttp} does it — connect and read timeouts,
 * {@code Accept: application/json}, a {@code User-Agent} naming the client and its jar version, an
 * {@link #MAX_JSON} cap on a body, and the JDK's own trust store, certificates validated. A failure is a
 * {@link Request#error} sentence saying why the hub did not answer, in the words the panel's line shows.
 */
public final class Registry {
    private Registry() {}

    /** The hub the client reads from unless {@link #PROP} names another. */
    public static final String DEFAULT_BASE = "https://brodgar.io/addons/api";
    /** The launch property naming another hub — the development one, at a loopback host. */
    public static final String PROP = "haven.addon.registry";
    /** Connect and read timeouts, each, in milliseconds — the ten seconds {@code LuaHttp} gives a request by default. */
    static final int TIMEOUT_MS = 10_000;
    /** The most a JSON answer may be, in bytes. */
    static final int MAX_JSON = 8 * 1024 * 1024;
    /** Who is asking: the client and its jar version, {@code dev} off a working tree. */
    public static final String USER_AGENT = "brodgar-client/" + version();

    private static volatile String base;             // resolved once, on first use (see base())

    // ------------------------------------------------------------- the base URL

    /**
     * The base URL every request is built on: the property's, taken by {@link #resolve}'s rule, else
     * {@link #DEFAULT_BASE}. Resolved <b>once</b>, on first use, and the refusal of a property that does not
     * pass the rule is logged then — one line, naming the value, the reason and the base kept.
     */
    public static String base() {
        String b = base;
        if(b == null) {
            synchronized(Registry.class) {
                b = base;
                if(b == null) {
                    String v = Utils.getprop(PROP, null);
                    try {
                        b = resolve(v);
                    } catch(IllegalArgumentException e) {
                        AddonManager.log("registry: -D" + PROP + "=" + v + " refused: " + e.getMessage()
                            + " -- reading " + DEFAULT_BASE);
                        b = DEFAULT_BASE;
                    }
                    base = b;
                }
            }
        }
        return b;
    }

    /**
     * The rule a configured base is held to, as a function of the value alone: absent or empty is the
     * default; {@code https://} anywhere is taken; {@code http://} is taken for a loopback host only; and
     * anything else raises {@link IllegalArgumentException} saying why. A trailing {@code /} is dropped so a
     * base is always joined to a path with exactly one.
     */
    static String resolve(String v) {
        if((v == null) || v.trim().isEmpty())
            return DEFAULT_BASE;
        String s = v.trim();
        while(s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        URI u;
        try {
            u = new URI(s);
        } catch(URISyntaxException e) {
            throw new IllegalArgumentException("not a URL (" + e.getMessage() + ")");
        }
        String scheme = (u.getScheme() == null) ? null : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost();
        if((scheme == null) || (host == null))
            throw new IllegalArgumentException("a base is written https://host/path, such as " + DEFAULT_BASE);
        if(scheme.equals("https"))
            return s;
        if(scheme.equals("http")) {
            if(loopback(host))
                return s;
            throw new IllegalArgumentException("http:// is taken for a loopback host only (localhost,"
                + " 127.0.0.1, ::1); another host is read over https://");
        }
        throw new IllegalArgumentException("the scheme is " + scheme + ": a base is https://, or http:// at"
            + " a loopback host");
    }

    /** Whether {@code host} names this machine: {@code localhost}, or a literal loopback address. No DNS. */
    static boolean loopback(String host) {
        String h = host;
        if(h.startsWith("[") && h.endsWith("]"))
            h = h.substring(1, h.length() - 1);
        if(h.equalsIgnoreCase("localhost"))
            return true;
        boolean literal = h.contains(":") || h.matches("^[0-9]+(\\.[0-9]+){3}$");
        if(!literal)
            return false;                            // a name would be a DNS lookup, and a name is not loopback
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch(IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------- requests

    /**
     * <b>One exchange in flight, or finished</b> — what {@link #search} and {@link #lookup} hand back. The
     * caller polls {@link #done} from its tick and then reads {@link #result} or {@link #error}, exactly one
     * of which is set. Nothing here calls back, so a request whose caller has moved on is never read: the
     * panel drops a late answer to an earlier search by never asking for it.
     */
    public static final class Request<T> {
        private volatile boolean done;
        private volatile T result;
        private volatile String error;
        private volatile int progress;
        private volatile boolean dead;                   // cancelled: not started, or being cut off
        volatile HttpURLConnection conn;                 // the exchange in flight, for cancel() to close

        Request() {}

        /**
         * Give this request up: one not yet started never goes out, one in flight has its connection closed
         * under the worker (which is what {@link HttpURLConnection#disconnect} is for) so the thread is free
         * for the next request rather than waiting the rest of a timeout out. It ends as {@code cancelled};
         * a caller that dropped it reads nothing either way. Idempotent.
         */
        public void cancel() {
            dead = true;
            HttpURLConnection c = conn;
            if(c != null) {
                try {
                    c.disconnect();
                } catch(RuntimeException e) { /* already closed, or closing under the worker */ }
            }
        }

        /** Whether {@link #cancel} was called. */
        public boolean cancelled() {
            return dead;
        }

        /** Whether the exchange has ended, one way or the other. */
        public boolean done() {
            return done;
        }

        /** The answer, or {@code null} while not done or when it failed. */
        public T result() {
            return result;
        }

        /** Why it failed, in a sentence for the panel's line, or {@code null} while not done or when it succeeded. */
        public String error() {
            return error;
        }

        /** How far along, {@code 0..100}; a read that is not a transfer stays at {@code 0} until it is done. */
        public int progress() {
            return progress;
        }

        void finish(T r) {
            result = r;
            progress = 100;
            done = true;
        }

        void fail(String why) {
            error = why;
            done = true;
        }
    }

    /** What a worker task computes: the answer, or an {@link IOException} whose message is the sentence. */
    interface Fetch<T> {
        T run(Request<T> r) throws IOException;
    }

    private static <T> Request<T> submit(final Fetch<T> f) {
        final Request<T> r = new Request<T>();
        worker().execute(new Runnable() {
            public void run() {
                if(r.dead) {                             // given up before its turn came: nothing goes out
                    r.fail("cancelled");
                    return;
                }
                try {
                    r.finish(f.run(r));
                } catch(IOException e) {
                    r.fail(r.dead ? "cancelled" : reason(e));
                } catch(RuntimeException e) {
                    r.fail(r.dead ? "cancelled" : e.getClass().getSimpleName() + ": " + reason(e));
                }
            }
        });
        return r;
    }

    /**
     * Search the hub — {@code GET <base>/addons?q=<q>&limit=50}: the items whose id, name, summary, author,
     * publisher or tags match, at most fifty, the hub's relevance order. An empty {@code q} is the caller's
     * to refuse; the hub answers it with everything.
     */
    public static Request<List<Entry>> search(final String q) {
        return submit(new Fetch<List<Entry>>() {
            public List<Entry> run(Request<List<Entry>> r) throws IOException {
                return items(getJson(base() + "/addons?q=" + enc(q) + "&limit=50", r));
            }
        });
    }

    /**
     * Look ids up — {@code GET <base>/addons?ids=a,b,c}: the same items for up to a hundred ids, unpaged;
     * an id the hub does not carry is simply absent from the answer. What the update check asks.
     */
    public static Request<List<Entry>> lookup(final Collection<String> ids) {
        final String joined = String.join(",", ids);
        return submit(new Fetch<List<Entry>>() {
            public List<Entry> run(Request<List<Entry>> r) throws IOException {
                return items(getJson(base() + "/addons?ids=" + enc(joined), r));
            }
        });
    }

    /** The {@code items} of a list answer, each read by {@link Entry#of}; a refusal there is the answer's. */
    static List<Entry> items(String body) throws IOException {
        Object doc;
        try {
            doc = Json.parse(body);
        } catch(RuntimeException e) {
            throw new IOException("the hub's answer is not JSON (" + reason(e) + ")");
        }
        if(!(doc instanceof Map))
            throw new IOException("the hub's answer is not an object");
        try {
            return Entry.list(((Map<?, ?>)doc).get("items"));
        } catch(IllegalArgumentException e) {
            throw new IOException("the hub's answer is not a list of addons: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- HTTP

    /**
     * One {@code GET} of a JSON body, as text. A status other than {@code 200} is an {@link IOException}
     * whose message is the hub's own {@code error} sentence where the body carries one, else the status; a
     * transport failure is one naming the failure; a body past {@link #MAX_JSON} is refused unread. The
     * connection is registered on {@code r} while it is open, so a {@link Request#cancel} can close it.
     */
    static String getJson(String url, Request<?> r) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)URI.create(url).toURL().openConnection();   // RFC 3986, as LuaHttp opens one
            if(r != null)
                r.conn = c;
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", "identity");   // the cap counts the bytes read
            c.setRequestProperty("User-Agent", USER_AGENT);
            int status = c.getResponseCode();
            InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream();
            String body = new String(readCapped(in, MAX_JSON), StandardCharsets.UTF_8);
            if(status != 200)
                throw new IOException(hubError(status, body));
            return body;
        } catch(SocketTimeoutException e) {
            throw new IOException("the hub did not answer within " + (TIMEOUT_MS / 1000) + " s");
        } finally {
            if(r != null)
                r.conn = null;
            if(c != null)
                c.disconnect();
        }
    }

    /** The sentence for a non-200 answer: the hub's own {@code {"error": …}} where there is one. */
    private static String hubError(int status, String body) {
        try {
            Object doc = Json.parse(body);
            if(doc instanceof Map) {
                Object err = ((Map<?, ?>)doc).get("error");
                if(err instanceof String)
                    return "the hub answered " + status + ": " + err;
            }
        } catch(RuntimeException e) { /* not JSON: the status alone is the sentence */ }
        return "the hub answered HTTP " + status;
    }

    /** Read a stream whole, refusing past {@code max} bytes. {@code in} may be null (no body). */
    static byte[] readCapped(InputStream in, int max) throws IOException {
        if(in == null)
            return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0, n;
        try {
            while((n = in.read(chunk)) >= 0) {
                total += n;
                if(total > max)
                    throw new IOException("the hub's answer is larger than " + (max / (1024 * 1024)) + " MB");
                buf.write(chunk, 0, n);
            }
        } finally {
            in.close();
        }
        return buf.toByteArray();
    }

    /** The message of an exception, or its kind when it carries none — never {@code null}. */
    static String reason(Throwable e) {
        String m = e.getMessage();
        if((m == null) || m.isEmpty())
            return e.toString();
        if(e instanceof java.net.UnknownHostException)
            return "unknown host " + m;
        if(e instanceof java.net.ConnectException)
            return "no connection to the hub (" + m + ")";
        return m;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch(UnsupportedEncodingException e) {
            throw new IllegalStateException(e);      // UTF-8 is always there
        }
    }

    // ------------------------------------------------------------- the worker

    private static ThreadPoolExecutor worker;

    /**
     * The one thread every exchange runs on, built on first use and gone again after a minute idle. A
     * daemon, so a request in flight never holds the client's exit open; one, so the hub is never hit with a
     * fan-out and answers arrive in the order they were asked.
     */
    private static synchronized ThreadPoolExecutor worker() {
        if(worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                                            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Addon registry");
                    t.setDaemon(true);
                    return t;
                }
            });
            worker.allowCoreThreadTimeOut(true);
        }
        return worker;
    }

    private static String version() {
        Object v = Utils.useragent.get("jar.version");
        return ((v == null) || v.toString().isEmpty()) ? "dev" : v.toString();
    }
}
