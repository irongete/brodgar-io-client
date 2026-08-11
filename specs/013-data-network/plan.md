# 013-data-network — Plan

> History: this work appears in git history and `learnings/` tagged **N1, N2a, N2b**
> (the N-series).

## Approach
- **N1 = expose + unify what existed**: the `Json` reader (manifests/store) and the compact
  writer (REPL echo/store) were already correct — the writer moved INTO `Json` beside its
  reader (`Json.write(v[,strict])`), the JSON→Lua marshal into `LuaMarshal` — one canonical
  serializer, three callers, zero drift (D-013). Strict mode is the only fork (the REPL echo
  must stay forgiving). Integer-ness reconstructed at marshal (the reader always returns
  Double); `set(k, NIL)` = JSON null → absent key. Hostile-input caps live IN the parser
  (depth guard in `object()`/`array()`), hardening the manifest/store paths for free.
- **N2 = new I/O on the existing async patterns**: the gob-delta queue shape — validate +
  gate sync on the UI thread, blocking `HttpURLConnection` exchange on a shared 8-thread
  daemon pool (Java-1.8-compatible; never touches Lua), completion queued, tick drains and
  runs `cb` through `callLua`. A blocking Java call is invisible to the instruction watchdog
  — async is mandatory, not a nicety. The per-addon in-flight limit counts and schedules
  (started flags) rather than blocking a pool thread on a semaphore (would starve the pool).
- **`network` is a manifest BLOCK, not a permissions[] string** — the declaration carries
  config (the hosts ARE the allowlist); the REPL's internal manifest is allow-all.
- **Private-IP block**: `InetAddress`'s own classifiers cover almost everything; IPv6 ULA
  `fc00::/7` needs a manual check. `localtest.me` (resolves 127.0.0.1) is the clean demo.
- **Redirects followed manually** (`setInstanceFollowRedirects(false)`) — the only way to
  re-validate every hop against the allowlist + IP block; HTTP-conventional method demotion.

## Files created / modified
- `src/io/brodgar/addon/Json.java` — writer + caps; `LuaMarshal.java` — `jsonToLua`
- `src/io/brodgar/addon/LuaHttp.java`, `LuaHttpRequest.java` — new (worker + handle)
- `AddonManager.java` (→ `HttpApi.java`) — `hafen.json` + `hafen.http` facades, pool,
  queue+drain, scheduler, `requireNetwork`, teardown
- `Manifest.java` — `network` block + `hostAllowed`; `Addon.java` — `requests` list
- `ui/AddonPanel.java` — `[net]` badge + hosts tooltip
- `addons/netdemo/` — new example; `addons/hello/` — v0.45.0 (json self-check)
- No `haven` edits (daemon pool → no shutdown hook needed).

## Risks & gotchas hit (detail: learnings/network-data.md)
- LuaJ canonicalises small whole doubles to LuaInteger — tests must expect it.
- A naïve per-addon `Semaphore` acquire in the worker would starve the shared pool.
- `Content-Type` auto-tag needs a case-insensitive scan of user headers first.
- A POST following 301/302/303 must demote to GET + drop the body (HTTP semantics).

## Discarded alternatives
- `java.net.http.HttpClient` — Java 11+; the project compiles at 1.8.
- Exposing the REPL's forgiving encoder publicly — the public entry must only emit valid JSON.
- Auto-follow via `HttpURLConnection` — cannot re-validate hops against the allowlist.
