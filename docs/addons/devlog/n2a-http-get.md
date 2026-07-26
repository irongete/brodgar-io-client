# N2a — `hafen.http.get` + the async / security substrate

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the
> `netdemo` addon; **38/38 headless logic checks** (manifest `network` parse — hosts lower-cased, `*`
> rejected on disk, block must be an object, `{}`→no-network; the host allowlist — exact ci match, apex
> vs `*.domain` wildcard, deep sub, unlisted refused, null; REPL allow-all; the private/loopback-IP block
> across v4 `127/10/172.16/192.168/169.254/0.0.0.0` + the `172.32`/`8.8.8.8`/`1.1.1.1` public negatives +
> v6 `::1`/`fc00`/`fe80` + the `2606::` public negative; header hygiene — `Authorization`/`Content-Type`
> allowed, `Host`/`Content-Length`/`Connection`/`User-Agent` forbidden ci, null) + LuaJ parse of
> `netdemo`. **In-game DoD pending.** *(Java engine change ⇒ rebuild + restart.)*
> **Design:** [specs/addons/19-data-and-network.md](../../../specs/addons/19-data-and-network.md) §3–§6
> (the async model, the `res` table, security — decisions **D-037** network-is-`hafen.http` gated by a
> `network` manifest block, **D-018** resource caps),
> [specs/addons/api-reference.md](../../../specs/addons/api-reference.md) (`hafen.http`),
> [specs/addons/code-map.md](../../../specs/addons/code-map.md) (N-series seams).

The second slice of the **N-series**: the whole `hafen.http` async + security substrate, exposing an
addon's first way to reach the network — **GET only** in N2a (POST + redirect-follow land in N2b). This is
*new I/O built on the existing async patterns*, the mirror image of N1 (which mostly exposed machinery
that already existed).

## What shipped

- **`hafen.http.get(url [, opts], cb)`** → a request handle `{ :cancel() }`. Asynchronous: it validates +
  gates synchronously and returns immediately, delivering the reply later via `cb(res)` on the tick.
  `res = { ok, status, body, headers (lower-cased), error }` — `ok` distinguishes *"a reply arrived"*
  (any HTTP status) from a *transport* failure.
- **The `network` manifest block** (D-037) — `"network": { "hosts": [...] }`. Its presence (non-empty
  hosts) grants network access, scoped to those hosts; the declaration *is* the allowlist.
- **The security model** — host allowlist (exact + `*.domain`) checked synchronously at call;
  private/loopback/link-local IP block on the resolved address; size (8 MB) / timeout (10 s, cap 60 s) /
  per-addon in-flight (6, excess queued; hard cap 64) / shared-pool (8) caps; generic UA, no cookies,
  hop-by-hop headers stripped, TLS verified, **no redirects** (3xx raw).
- **The AddOns panel** shows a `[net]` row badge + the declared hosts in the tooltip (§5.3).
- **`netdemo`** — a dedicated example addon (`:netdemo get|bad|lan`).

## Threading — the gob-delta pattern, reused

The exact shape the engine already uses for `GobAdded`/`GobRemoved` and `hafen.timer`:

1. **Call** (UI thread, under `synchronized(ui)`). `hafen.http.get` validates the URL, resolves the host,
   calls **`requireNetwork(owner, host, verb)`** (throws a guiding `LuaError` on a missing `network` block
   or a non-allowlisted host — instant feedback, like `requireActions`), builds a `LuaHttpRequest`,
   registers it in `Addon.requests`, and kicks the per-addon scheduler.
2. **I/O** (pool thread). `LuaHttp.perform(req)` runs the blocking `HttpURLConnection` exchange — never
   touches Lua or `haven` state. It resolves the host and refuses a private/loopback IP, applies the caps,
   and returns a `LuaHttp.Result` (never throws — a transport failure is `Result.fail(msg)`).
3. **Completion → queue.** The worker enqueues an `HttpCompletion(req, result)` onto `httpResults` (the
   `GobEvent`-queue mechanism).
4. **Drain** (UI thread, on tick — new step **1a**, `drainHttp()`). For each completion, if the request is
   still live, `callLua(owner, cb, resTable)` runs it armed + isolated; a cancelled/torn-down request is
   discarded (**its callback never fires** — D-037 §3.3). Draining frees an in-flight slot, so the
   scheduler re-runs.

So **all** Lua stays serialized on the UI thread under the watchdog (the L3/V2 guarantee); the pool only
does Java I/O.

### The per-addon in-flight scheduler (non-blocking)

`LuaHttp.MAX_INFLIGHT` (6) concurrent requests per addon is enforced **without blocking a pool thread**:
`maybeStartHttp(owner)` counts running (`started && !dead`) requests and submits not-yet-started live ones
until the limit; excess requests sit in `Addon.requests` as `started=false` and launch as running ones
complete (the drain re-invokes the scheduler). A pool thread never waits on a per-addon permit — which
would starve the shared 8-thread pool. The hard per-addon cap (64 pending) is a synchronous `LuaError` at
call.

## Code changes

**New (`io.brodgar.addon`):**
- **`LuaHttpRequest`** — the bridge-owned handle + immutable job params (method/url/body/headers/timeout/
  cb); `volatile dead` (cancel/teardown), `started` (scheduler).
- **`LuaHttp`** — the blocking worker + security checks: `perform(req)` (`HttpURLConnection`, no-redirect,
  caps, charset decode, lower-cased response headers), `isBlockedAddress` (private/loopback via
  `InetAddress`'s classifiers + a manual `fc00::/7` check), `headerAllowed` (hop-by-hop hygiene), the
  `-D`-tunable cap constants, and the `Result` record. Java-1.8-compatible (`HttpURLConnection`, not
  `java.net.http.HttpClient`).

**Edited:**
- **`Manifest`** — new `network` field + `usesNetwork()` / `hostAllowed(host)` (exact ci + `*.domain` +
  the internal-owner `"*"`) + `networkhosts(m)` parse (rejects a disk `"*"`); the private ctor +
  `internal()` (REPL = allow-all) updated.
- **`Addon`** — new `requests` owned-resource list.
- **`AddonManager`** — the static `httpPool` (lazy, daemon) + `httpResults` queue + `HttpCompletion`; the
  tick drain (step 1a); `requireNetwork`; the `hafen.http` table (`get`) with `httpHost`/`httpHeaders`/
  `httpTimeout` arg parsing; `newHttpRequest`/`maybeStartHttp`/`submitHttpJob`/`drainHttp`/`httpResTable`;
  `teardownRequests` (wired into `teardown`, `httpResults.clear()` in `init`); `AddonInfo.networkHosts` +
  `declaresNetwork()` + `describeAddons` wiring.
- **`ui/AddonPanel`** — the `[net]` badge + declared-hosts tooltip.

**`haven` core edits: none** — `HttpURLConnection` is JDK, the async plumbing is all in the addon package
(the spec's "if a shared `ExecutorService` needs a client-shutdown hook" caveat did not materialize: the
pool uses daemon threads, so it never blocks JVM exit).

## Verification

- Compile: `ant hafen-client` → BUILD SUCCESSFUL (two `new URL(String)` deprecation warnings only —
  harmless on our 1.8 target). `ant bin` copies `netdemo`.
- 38/38 headless logic checks (`N2aTest`) over the pure pieces — manifest parse, host allowlist,
  private-IP block, header hygiene (see the status block). The live request path is verified in-game
  (a headless harness has no session/UI to drive the tick drain).
- LuaJ parse of `netdemo/main.lua`.

## In-game DoD (for the maintainer)

Enable **NetDemo** in Options > AddOns (no consent dialog — network is declaration-gated, not
default-disabled like write addons; the row shows `[net]` + the two hosts in its tooltip), Reload UI, then:

- `:netdemo get` — an allowlisted GET to `api.github.com` returns JSON that `hafen.json.parse` reads →
  logs `repo=dolda2000/hafen-client stars=… forks=… lang=Java`.
- `:netdemo bad` — a non-allowlisted host (`evil.example.org`) is **refused synchronously at call**
  (pcall'd → logs the guiding error).
- `:netdemo lan` — an allowlisted host that resolves to `127.0.0.1` (`localtest.me`) is **refused by the
  private-IP block** → `ok=false, error="… blocked address …"`.
- Fire `:netdemo get`, then `:reload` immediately — the in-flight request is cancelled, its callback never
  fires, nothing leaks.

## Deferred (→ N2b)

`hafen.http.post` (string + table→JSON body), request/response rich headers, follow ≤5 redirects with
per-hop allowlist + private-IP re-validation. Also deferred (§9): DNS-rebinding hardening (pin the
validated IP into the connection), a generic `request{method=}`, non-default ports, streaming/download.
