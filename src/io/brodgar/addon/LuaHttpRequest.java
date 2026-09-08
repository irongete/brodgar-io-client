package io.brodgar.addon;

import java.net.HttpURLConnection;
import java.util.Map;

import org.luaj.vm2.LuaValue;

/**
 * One in-flight {@code hafen.http} request (N2a, spec {@code 19-data-and-network.md} §3.3). It is the
 * bridge-owned handle (registered in {@link Addon#requests}) plus the immutable job parameters the worker
 * needs — the same owned-resource pattern as {@link AddonManager.Sub}/{@link AddonManager.Timer}/ghosts.
 *
 * <p><b>Async model (the gob-delta pattern, reused).</b> The call ({@code hafen.http():get}) validates +
 * gates on the UI thread, creates this handle, and registers it. {@link AddonManager} then submits the
 * blocking I/O to a bounded pool thread ({@link LuaHttp#perform}); the worker enqueues the
 * {@link LuaHttp.Result} onto {@link AddonManager}'s result queue, which {@code tick} drains on the UI
 * thread and, if the request is still live, arms the sandbox and invokes {@link #cb}.
 *
 * <p><b>Cancellation.</b> {@code :cancel()} and teardown both set {@link #dead} <b>and close
 * {@link #conn}</b>. A dead request is never started, one in flight has its exchange ended where it stands
 * rather than being left to finish unheard, its result is discarded on drain, and its handler <b>never
 * fires</b> (no "cancelled" event in v1) — the explicit no-callback-after-cancel guarantee of §3.3.
 *
 * <p><b>Built bare, sent on purpose</b> (095, A-115). Until 095 the call that created a request also
 * scheduled it, so every setter carried a lifetime rule no other builder in the API has — configure it in
 * the same statement or be refused, because the tick was already coming for it. Now {@link #sent} is written
 * by {@code :send()} and nothing else: an unsent request takes any setter, a sent one takes none, and there
 * is no timing in the rule at all. {@link #method}, {@link #url} and {@link #body} became mutable for the
 * same reason — they are what the builder configures.
 *
 * <p>All mutable fields except {@link #dead} are touched only on the UI thread (call + setters + drain +
 * scheduler);
 * {@link #dead} is {@code volatile} because a pool worker reads it to short-circuit before enqueueing.
 */
final class LuaHttpRequest {
    final Addon owner;
    String method;            // "GET" or "POST" (N2b); the worker may demote it to GET across a redirect
    String url;
    byte[] body;              // request body, or null (GET / empty POST)
    final Map<String, String> headers;   // caller headers, already hygiene-filtered (may be empty)
    int timeout;              // ms; a setter on the request object until :send() (UI thread only)
    /** {@code req:on("done", fn)} — the API's one notification verb, where a positional callback was (A-116). */
    final Subs subs;

    volatile boolean dead;    // cancelled / torn down — handler suppressed, result discarded
    /**
     * The exchange this request has open right now, or {@code null} between hops and before the first. Written
     * by the worker inside {@link LuaHttp#perform} and read by {@link LuaHttp#abort} on the UI thread, which is
     * what lets an ending <b>close</b> the connection rather than only stop listening to it — a dead request
     * whose socket is left open still reaches the host with its headers and still follows its redirects.
     * {@code volatile} for the same reason {@link #dead} is: two threads, and no lock between them.
     */
    volatile HttpURLConnection conn;
    /** The host {@link #url} names, resolved once at construction — what the allowlist is checked against
     *  when {@code :send()} runs the gate (095: there is no {@code :url(u)} setter, so it cannot change). */
    String host;
    /** The Lua handle over this record — what {@code hafen.http():list()} hands back (095, A-118). */
    LuaValue handle;
    /** Has {@code :send()} been called? UI-thread only. Until it has, every setter is legal (095). */
    boolean sent;
    boolean started;          // UI-thread only: submitted to the pool (RUNNING); false = queued (NEW)

    LuaHttpRequest(Addon owner, String method, String url, byte[] body,
                   Map<String, String> headers, int timeout) {
        this.owner = owner;
        this.method = method;
        this.url = url;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
        this.subs = new Subs(owner, Addon.C_EVENT);
    }

    /** The keys a request answers — one today, and {@code "progress"} costs nothing to add later. */
    static final String DONE = "done";
}
