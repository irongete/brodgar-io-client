# N2b — `hafen.http.post` + rich headers + redirect-follow

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the
> `netdemo` addon; LuaJ parse of `netdemo`/`hello`. **In-game DoD pending.** *(Java engine change ⇒
> rebuild + restart.)*
> **Design:** [specs/addons/19-data-and-network.md](../../../specs/addons/19-data-and-network.md) §4
> (the POST body shape) + §5.6 (redirect re-validation) — decision **D-037**,
> [specs/addons/api-reference.md](../../../specs/addons/api-reference.md) (`hafen.http`).

The final slice of the **N-series**: completes `hafen.http` by adding the **write** verb (`post`) and
turning the N2a *"3xx returned raw"* stub into real **redirect-following that can't escape the
allowlist**. Everything else — the async substrate, the `network` gate, the private-IP block, the caps,
the `res` table, `:cancel()`/teardown — was already built in [N2a](n2a-http-get.md); N2b is a thin
addition on top of it, no new substrate.

## What shipped

- **`hafen.http.post(url, body [, opts], cb)`** → the same request handle `{ :cancel() }` as `get`, with
  a request body:
  - `body` a **string** → sent verbatim (UTF-8).
  - `body` a **table** → encoded with the shared strict `Json.write(v, true)` serializer and tagged
    `Content-Type: application/json` **unless** the addon set its own `Content-Type` in `opts.headers`.
    A non-serializable table (function/userdata value, reference cycle) raises a guiding `LuaError` at call.
  - `body` `nil` → an empty POST.
  - Same gate (`requireNetwork`), same limits, same `res` table, same async delivery as `get`.
- **Redirect following (≤ 5 hops), allowlist-safe.** Both verbs now follow `301`/`302`/`303`/`307`/`308`
  **manually**, and **each hop's `Location` host is re-validated** against the addon's allowlist **and** the
  private/loopback-IP block. A redirect to a non-allowlisted or private host aborts with
  `ok=false, error="… refused …"` — a redirect can never take a request off the declared hosts. Method
  demotion follows HTTP convention: a `303` (and a `301`/`302` on a body-bearing method) becomes a `GET`
  with the body dropped; `307`/`308` preserve the method + body.

## Code changes (all `io.brodgar.addon`, zero `haven` core edit)

**`LuaHttp`** — the worker gained the redirect loop:
- `perform(r)` was a single-shot exchange; it is now a `while(true)` loop over hops. `method`/`body`/`url`
  are mutable locals seeded from the request; each iteration opens a fresh `HttpURLConnection`
  (`setInstanceFollowRedirects(false)` — we follow, so every hop is re-checked), and on a followable 3xx
  with a `Location` it resolves the (possibly relative) target against the current URL, bumps the hop
  counter, optionally demotes to `GET`, and `continue`s (the `finally` disconnects the old connection).
- New private helper **`validateHop(r, u, redirect)`** — the per-hop check: scheme is http/https, and (on a
  **redirect** hop) the host is in `r.owner.manifest.hostAllowed(...)`, and no resolved address is
  private/loopback/link-local (the N2a `isBlockedAddress`). Returns `null` when allowed, else the
  `Result.fail(...)`. The first hop's allowlist was already gated synchronously at call, so `validateHop`
  enforces it only on redirect hops (the IP block still runs on every hop, exactly as N2a did for the one
  hop). This is where "redirects can't escape the allowlist" lives.
- New `isRedirect(status)` (301/302/303/307/308) + the `-D`-tunable `MAX_REDIRECTS` (default 5).

**`AddonManager`** — the facade + body encoder:
- The `hafen.http` table gained **`post`** (arg parsing mirrors `get`: `post(url, body, cb)` /
  `post(url, body, opts, cb)`), reusing `httpHost`/`requireNetwork`/`httpHeaders`/`httpTimeout` and the
  existing `newHttpRequest(owner, "POST", url, bytes, …)`.
- New **`httpBody(body, headers, verb)`** — string→UTF-8 bytes; table→`Json.write(v, true)` bytes + adds
  `Content-Type: application/json` when absent (`hasContentType` is a ci scan); nil→empty; anything else →
  `LuaError`. A strict-encode failure is rewrapped with a body-specific message.

**`LuaHttpRequest`** — comment only (the `method`/`body` fields already existed; the worker may now demote
`method` to `GET` across a redirect).

No new fields, no new threads, no manifest change — N2b reuses the entire N2a substrate.

## Verification

- Compile: `ant hafen-client` → BUILD SUCCESSFUL (the pre-existing `new URL(...)` deprecation warnings
  only — harmless on the 1.8 target; the redirect loop's `new URL(u, loc)` is the same idiom the client
  uses elsewhere). `ant bin` copies `netdemo`.
- LuaJ parse of `netdemo/main.lua` + `hello/main.lua`.
- The live POST + redirect paths are verified **in-game** (a headless harness has no session/UI to drive
  the tick drain, and the redirect logic needs a real 3xx from a live server — same rationale as N2a's
  in-game-only request path). The pure pieces (host allowlist, `isBlockedAddress`, `hasContentType`) are
  unchanged from N2a's 38 headless checks; the new `validateHop` is those same two checks composed.

## In-game DoD (for the maintainer)

Enable **NetDemo** (v0.2.0) in Options > AddOns (the row shows `[net]` + now **three** hosts —
`api.github.com`, `httpbin.org`, `localtest.me` — in its tooltip), Reload UI, then:

- `:netdemo post` — POSTs a Lua table (auto-JSON) to `httpbin.org/post` and reads the echoed body back →
  logs `char=… lp=42 content-type=application/json` (the round-trip DoD).
- `:netdemo redirect` — a `302` to `httpbin.org/get` (an **allowlisted** hop) is followed → `HTTP 200`.
- `:netdemo escape` — a `302` whose `Location` is `example.com` (**not** allowlisted) is **blocked
  mid-hop** → `ok=false, error="redirect to non-allowlisted host …"` (the allowlist-safety DoD).
- The N2a demos (`:netdemo get|bad|lan`) still pass — the regression check.

## Deferred (§9)

DNS-rebinding hardening (pin the validated IP into the connection), a generic `request{method=}` /
PUT·DELETE·PATCH, non-default ports, streaming/download-to-file. **This completes the N-series.**
