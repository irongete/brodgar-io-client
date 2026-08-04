package io.brodgar.addon;

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
 * <p><b>Cancellation.</b> {@code :cancel()} and teardown both set {@link #dead}. A dead request is never
 * started, its in-flight result is discarded on drain, and its callback <b>never fires</b> (no "cancelled"
 * callback in v1) — the explicit no-callback-after-cancel guarantee of §3.3.
 *
 * <p>All mutable fields except {@link #dead} are touched only on the UI thread (call + setters + drain +
 * scheduler);
 * {@link #dead} is {@code volatile} because a pool worker reads it to short-circuit before enqueueing.
 */
final class LuaHttpRequest {
    final Addon owner;
    final String method;      // "GET" or "POST" (N2b); the worker may demote it to GET across a redirect
    final String url;
    final byte[] body;        // request body, or null (GET / empty POST)
    final Map<String, String> headers;   // caller headers, already hygiene-filtered (may be empty)
    int timeout;              // ms; a setter on the request object until it goes out (UI thread only)
    final LuaValue cb;        // function(res), or NIL

    volatile boolean dead;    // cancelled / torn down — callback suppressed, result discarded
    boolean started;          // UI-thread only: submitted to the pool (RUNNING); false = queued (NEW)

    LuaHttpRequest(Addon owner, String method, String url, byte[] body,
                   Map<String, String> headers, int timeout, LuaValue cb) {
        this.owner = owner;
        this.method = method;
        this.url = url;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
        this.cb = cb;
    }
}
