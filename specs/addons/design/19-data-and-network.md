# Data & Networking — `hafen.json` + `hafen.http` (external requests + JSON)

> **Status:** 🟢 Design closed · **Spec:** AddOns · **Series:** N (Networking & data)
> **Related:** [DECISIONS.md](../DECISIONS.md) (D-036, D-037 — **ratified 2026-07-26**),
> [12-security-and-permissions.md](12-security-and-permissions.md) (the sandbox + the "actions" permission model
> this mirrors), [03-addon-format.md](03-addon-format.md) (the manifest), [09-events-catalog.md](09-events-catalog.md)
> (the tick-drained callback pattern reused here), [../../codebase-map.md](../../codebase-map.md) (N-series seams),
> [the API reference](../../../docs/addons/api/README.md) (`hafen.json` / `hafen.http`)

Two sibling namespaces that let an addon **fetch data from an external URL** and **parse/produce JSON**. This is
the capability the maintainer asked for (2026-07-26): *"algo como `hafen.utils.request("…")` y
`hafen.utils.parseJSON(…)`"* — re-homed onto the project's `hafen.*`-by-concern convention ([D-020](../decisions/architecture-api.md)/
[D-013](../decisions/architecture-api.md)) as **`hafen.http`** (network) + **`hafen.json`** (serialization), **not** a `hafen.utils`
grab-bag.

## 0. The gap today, and what already exists

Right now an addon has **no way to reach the network and no JSON on the Lua side** — both are deliberate, not
oversights:

- **No network.** The sandbox ([Sandbox.java:100](src/io/brodgar/addon/Sandbox.java:100), [D-017](../decisions/security-sandbox.md)) is
  a constructive whitelist: `io`, `luajava`, `require`/`load*`, and most of `os` are **absent by construction**, and
  LuaJ ships no `socket`. An addon literally cannot open a connection today. Adding one is a deliberate new capability.
- **No JSON on the Lua side — but the machinery is already written.** The client already has a complete,
  dependency-free JSON **reader** ([`Json.parse`](src/io/brodgar/addon/Json.java:25) → plain Java
  `Map`/`List`/`String`/`Double`/`Boolean`/`null`) and a compact JSON **writer**
  ([`AddonManager.json(LuaValue)`](src/io/brodgar/addon/AddonManager.java:8250) — array-vs-object heuristic, integer
  cleanup, cycle detection). Both run **internally** only: the reader parses manifests ([Manifest.java:84](src/io/brodgar/addon/Manifest.java:84))
  and saved variables; the writer backs the `:lua` REPL echo and `hafen.store` persistence. Neither is exposed to Lua.

So `hafen.json` is mostly *exposing what exists*, and `hafen.http` is *new I/O built on the existing async patterns*.

## 1. The surface & the namespaces

| # | Capability | Lua | Gated? | Backing |
|---|---|---|---|---|
| 1 | **parse JSON** | `hafen.json.parse(str)` → Lua value | no (pure CPU) | [`Json.parse`](src/io/brodgar/addon/Json.java:25) + a new `Map`/`List`→`LuaTable` marshal |
| 2 | **encode JSON** | `hafen.json.encode(value)` → string | no (pure CPU) | the REPL writer ([AddonManager.java:8250](src/io/brodgar/addon/AddonManager.java:8250)), moved into `Json` |
| 3 | **HTTP GET** | `hafen.http.get(url[,opts], cb)` → request handle | **yes — network permission (D-037)** | `HttpURLConnection` off-thread + tick-drained callback |
| 4 | **HTTP POST** | `hafen.http.post(url, body[,opts], cb)` → request handle | **yes — network permission (D-037)** | same, with a request body |

**Why two namespaces, not `hafen.utils`.** [D-020](../decisions/architecture-api.md) (namespaced `hafen.*`, no flat globals) +
[D-013](../decisions/architecture-api.md) (one canonical home per concern) rule out a `utils` catch-all. JSON is serialization; HTTP is
I/O; they are independent (you can `json.parse` a `hafen.store` string with no network, and you can `http.get` a
non-JSON asset). `http` (not `net`) because we expose **HTTP only** — no raw sockets/websockets (§9).

```lua
-- the motivating use case: fetch JSON from an API
hafen.http.get("https://api.example.com/prices", function(res)
  if not res.ok then hafen.log.warn("request failed: " .. res.error); return end
  if res.status ~= 200 then hafen.log.warn("HTTP " .. res.status); return end
  local data = hafen.json.parse(res.body)      -- JSON string → Lua table
  hafen.log.info("iron = " .. tostring(data.iron))
end)
```

## 2. `hafen.json` — parse / encode

### 2.1 `parse(str)` → Lua value
Wraps [`Json.parse`](src/io/brodgar/addon/Json.java:25) (Java `Map`/`List`/`String`/`Double`/`Boolean`/`null`), then
marshals the result to Lua with a **new** small recursive converter (`LuaMarshal.toLua`
([LuaMarshal.java:42](src/io/brodgar/addon/LuaMarshal.java:42)) does **not** handle `Map`/`List` today):

| JSON | Lua |
|---|---|
| object `{…}` | table with **string** keys |
| array `[…]` | table, **1-indexed** sequential |
| string / `true`/`false` | string / boolean |
| number | Lua number (integers returned as ints, so `{"n":5}` → `5`, not `5.0`) |
| `null` | **`nil`** — see caveat |

- **`null` → `nil` (documented caveat).** `nil` cannot be a live table value in Lua, so a JSON `null` inside an
  object simply yields an absent key, and `[1, null, 3]` becomes a table with a hole (its `#` length is
  implementation-defined). This is the standard Lua-JSON trade-off; we take the simplest predictable rule (one
  canonical way — no `json.null` sentinel in v1, §9).
- **Malformed input → `LuaError`.** `Json.err` already reports `"JSON: <msg> at offset <n>"`; the bridge rethrows it
  as a `LuaError` so the addon can `pcall` it.
- **Hostile-input caps ([D-018](../decisions/security-sandbox.md) spirit).** `Json` is recursive (`object`→`value`→`object`…), so deep
  nesting could `StackOverflow`, and a huge string could exhaust memory. Add an **input-length cap** and a
  **max-depth cap** (both `-D`-tunable like `haven.addon.insncap`), failing with a clear `LuaError` before the crash.

### 2.2 `encode(value)` → string
Reuses the REPL writer ([AddonManager.java:8250](src/io/brodgar/addon/AddonManager.java:8250)) verbatim — same
**array-vs-object heuristic** (a table whose keys are exactly `1..#t` is an array, else an object), same integer
cleanup, same cycle detection. Two deliberate differences for the public entry point vs the REPL's forgiving echo:

- **Non-serializable values → `LuaError`.** The REPL writer stringifies a function/userdata/thread as a quoted
  `tostring`; the public `encode` instead **throws** (JSON has no such types) so output is always valid JSON.
- **Cycles → `LuaError`** (the REPL emits `"<cycle>"`; the public path rejects).

### 2.3 The one refactor (D-013, zero core edit)
Move the writer out of `AddonManager` **into [`Json`](src/io/brodgar/addon/Json.java)** (which already owns the
reader) as `Json.write(LuaValue[, strict])`, and have the REPL echo, `hafen.store` persistence, `hafen.json.encode`,
**and** the `hafen.http` JSON-body encoder (§4.2) all call it. Reader + writer live together; one canonical
serializer, no drift — exactly the shared-`LuaGOut`/`readEquipment`/`LuaMarshal` pattern. **Zero `haven` core edit**
(all `io.brodgar.addon`).

## 3. `hafen.http` — the async model (the real work)

### 3.1 Why async is mandatory
A network request blocks (DNS + connect + transfer). It **cannot** run on the UI thread: it would freeze the client,
and the instruction watchdog ([Sandbox.java:156](src/io/brodgar/addon/Sandbox.java:156)) would **not** save us — the
watchdog counts **Lua instructions**, so a blocking **Java** call from Lua is never interrupted and would hang the UI
thread indefinitely. Therefore `hafen.http.*` is **asynchronous, always**: it returns immediately and delivers the
result later via a callback.

### 3.2 Threading — the gob-delta / timer pattern, reused
This is the exact shape the engine already uses for `GobAdded`/`GobRemoved` (marshalled off the network thread via a
queue drained on tick) and `hafen.timer`:

1. **Call (UI thread, under `synchronized(ui)`).** `hafen.http.get(...)` validates args + checks the **network
   permission and host allowlist synchronously** (immediate `LuaError` on a missing permission or a disallowed host —
   same instant feedback as `requireActions` [AddonManager.java:311](src/io/brodgar/addon/AddonManager.java:311)),
   creates a **request handle**, registers it in `Addon.requests`, submits the job to a **shared bounded thread
   pool**, and returns the handle.
2. **I/O (pool thread, off the UI thread).** `HttpURLConnection` runs the blocking request. Never touches Lua or any
   `haven` widget state.
3. **Completion → queue.** Success or failure, the worker enqueues a result record onto an `AddonManager` queue (the
   same mechanism as `GobEvent` [AddonManager.java:8241](src/io/brodgar/addon/AddonManager.java:8241)).
4. **Drain (UI thread, on tick).** `tick(dt)` drains the queue: for each result, **if** the request was not cancelled
   and its addon is still live/enabled, `Sandbox.arm(env)` then invoke the callback with the `res` table, under
   **per-handler error isolation** (a throwing callback is logged, never propagated — the event-handler contract).

So **all** Lua stays serialized on the UI thread under the watchdog (the L3/V2 guarantee); the pool only does Java I/O.

### 3.3 The request handle, ownership & teardown
`hafen.http.get/post` return a handle `{ :cancel() }`, **bridge-owned** in a new `Addon.requests` set — the same
owned-resource pattern as `Addon.subs`/`timers`/`ghosts`/`images`. Teardown (`:reload`/disable/relog) cancels every
in-flight request; the pool worker's result is discarded on drain (dead handle → callback never fires). **Cancelled
⇒ the callback never fires** (explicit `:cancel()` and teardown alike — no "cancelled" callback in v1). The pool is a
single bounded `ExecutorService` on `AddonManager` (static, like the tick machinery), shut down with the manager.

### 3.4 Java level
`HttpURLConnection` (via `URL.openConnection`) — **Java 1.8 source/target compatible** (unlike `java.net.http.HttpClient`,
which needs Java 11 bytecode). Runs fine on the Java 23 runtime. TLS uses the JDK default trust store (certificates
**are** validated; we never disable verification).

## 4. `hafen.http` — the API shape

### 4.1 Calls
```lua
hafen.http.get(url [, opts], cb)                 -- read
hafen.http.post(url, body [, opts], cb)          -- send + read
```
- **`url`** — string. Scheme `http` or `https`; host must be in the allowlist (§5); ports limited to the scheme
  default (80/443) in v1 (§9).
- **`body`** (post) — a **string** (sent verbatim) or a **table** (auto-encoded with `Json.write` → sent as
  `application/json` unless the addon sets its own `Content-Type`). The ergonomic default for the common "POST JSON"
  case; one canonical way.
- **`opts`** (optional table) — `headers` (table; keys/values strings), `timeout` (ms; default 10000, capped 60000).
- **`cb`** (optional) — `function(res)`. Omitted ⇒ result discarded (transport errors still logged). **One argument,
  a `res` table** (the project's payload-as-table event style), not Node-style `(err, res)`.

**Verbs:** GET + POST only in v1 ([D-037](../decisions/network-data.md); maintainer 2026-07-26) — read + send, the two distinct
operations (not a dual style for one operation, so [D-013](../decisions/architecture-api.md) holds). PUT/DELETE/PATCH via a generic
`request{method=}` is the natural extension (§9) if an addon needs it.

### 4.2 The `res` table
| Field | When | Meaning |
|---|---|---|
| `res.ok` | always | `true` if an HTTP response was received (**any** status, incl. 4xx/5xx); `false` only on a **transport** failure (timeout, DNS, connect refused, size/limit exceeded, disallowed redirect). |
| `res.status` | `ok` | HTTP status code (200, 404, 500…). |
| `res.body` | `ok` | response body as a string (decoded via the response charset, default UTF-8). |
| `res.headers` | `ok` | response headers, keys **lower-cased** (HTTP headers are case-insensitive). |
| `res.error` | `not ok` | human-readable transport error string. |

`ok` separates **"did we get a reply?"** from **"what code?"** — a 404 is `ok=true, status=404` (the server
answered), a DNS failure is `ok=false, error=…`. The addon checks `res.status` for HTTP-level outcomes.

```lua
-- POST JSON, read JSON back
hafen.http.post("https://api.example.com/report",
  { char = hafen.player.name(), lp = hafen.char.attrs().lp },   -- table → application/json
  { headers = { Authorization = "Bearer " .. hafen.store.cfg.token }, timeout = 5000 },
  function(res)
    if res.ok and res.status == 200 then
      local reply = hafen.json.parse(res.body)
      ...
    end
  end)
```

## 5. Security & permissions ([D-037](../decisions/network-data.md))

Network access from third-party code is powerful (data exfiltration, phone-home, LAN/SSRF, DoS amplification), so it
is **opt-in, declared, allowlisted, and visible** — mirroring the "actions" tier's philosophy ([D-027](../decisions/actions-permissions.md)/
[D-028](../decisions/actions-permissions.md)) but as its **own manifest block**, because unlike a bare flag it carries configuration (the
host list). This follows the `saved_variables` precedent ([Manifest.java:112](src/io/brodgar/addon/Manifest.java:112)):
a capability-with-config gets its own block, not a `permissions[]` string.

### 5.1 The manifest block (declaration = the allowlist)
```json
{
  "id": "prices",
  "files": ["main.lua"],
  "network": {
    "hosts": ["api.example.com", "*.githubusercontent.com"]
  }
}
```
- **Presence of `network` (with a non-empty `hosts`) grants the permission**, scoped to those hosts.
- **No `network` block ⇒ no network.** Any `hafen.http.*` call throws a guiding `LuaError` ("this addon did not
  declare a `network` block in its manifest.json — add `\"network\": { \"hosts\": [...] }`"), like `requireActions`.
- **`hosts` is an exact allowlist**, case-insensitive, with a **`*.domain`** wildcard matching sub-domains (documented:
  `*.example.com` matches `a.example.com`, **not** the apex `example.com` — list the apex separately). A request to
  any host not matched is **rejected synchronously** with a `LuaError` at call time.

### 5.2 Blocked destinations (private/loopback — [D-037](../decisions/network-data.md))
Even for an allowlisted host, the resolved IP is checked and **private/loopback/link-local ranges are rejected by
default**: `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7`,
`fe80::/10`. This blocks LAN scanning / internal-service SSRF from third-party code. *(Known v1 limitation: a
resolve-then-connect gap leaves a narrow DNS-rebinding window; full mitigation — pin the checked IP into the
connection — is a later hardening, §9. No user-facing localhost override in v1.)*

### 5.3 Transparency (the panel)
The AddOns panel ([AddonPanel.java](src/io/brodgar/addon/ui/AddonPanel.java)) shows each addon's **declared hosts**
(and a "network" badge) so the user sees exactly which servers an addon talks to **before enabling it** — the
network analog of the actions permission notice ([D-025](../decisions/actions-permissions.md)).

### 5.4 Resource limits ([D-018](../decisions/security-sandbox.md) spirit — all `-D`-tunable)
| Limit | Default | On breach |
|---|---|---|
| response size | 8 MB | abort → `ok=false, error="response too large"` |
| timeout | 10 s (cap 60 s) | abort → `ok=false, error="timeout"` |
| in-flight per addon | 6 | excess **queued**; hard queue cap (64) → `LuaError` at call |
| shared pool threads | 8 | global fan-out ceiling across all addons |

### 5.5 Header & privacy hygiene
- Addon may set request headers (e.g. `Authorization`, `Content-Type`) from `opts.headers`. **Hop-by-hop / unsafe
  headers** (`Host`, `Content-Length`, `Connection`, …) are ignored/overridden.
- A **generic `User-Agent`** (e.g. `brodgar-addon/1`) is sent — never anything identifying the character/account.
- **No cookie jar, no shared session** — each request is stateless and addon-isolated; nothing auto-attaches user
  identity. Any token is the addon's own (typically from its `hafen.store`).
- HTTPS is recommended (plain `http` is allowed for the rare non-TLS API, but private IPs stay blocked).

### 5.6 Redirects
**v1 (N2a): no auto-follow** — `setInstanceFollowRedirects(false)`; a 3xx is returned to the addon as-is
(`status` + `location` header). **N2b upgrade:** follow up to 5 redirects, **re-validating the allowlist + private-IP
check on every hop** (a hop to a non-allowlisted or private host aborts with an error) — so redirects can never
escape the allowlist.

## 6. Architecture / code map (N-series seams)

**New (`io.brodgar.addon`):**
- `Json.write(LuaValue[, strict])` — the REPL writer moved in beside the reader (§2.3); callers updated.
- `LuaMarshal.jsonToLua(Object)` — `Map`/`List`/scalar → `LuaValue` (§2.1).
- `LuaHttp` (the `hafen.http` facade builder) + `LuaHttpRequest` (the handle: `:cancel()`, dead flag, owning addon).
- `Manifest.network` — parsed `hosts` list + a `usesNetwork()`/host-matcher (mirrors `usesActions`
  [Manifest.java:30](src/io/brodgar/addon/Manifest.java:30)); `requireNetwork(owner, host, verb)` gate + the
  private-IP check.
- `AddonManager`: a static bounded `ExecutorService`, an `HttpResult` queue drained in `tick` (beside `GobEvent`),
  `Addon.requests` teardown, and the `hafen.json`/`hafen.http` table builders.

**Reused (no reinvention):** `Json.parse`; `LuaMarshal`; the tick callback-drain + per-handler error isolation; the
owned-resource teardown; `Sandbox.arm`; the AddOns panel.

**`haven` core edits: none expected** — `HttpURLConnection` is JDK, JSON is ours, the async plumbing is all in the
addon package. (If a shared `ExecutorService` lifecycle needs a hook into client shutdown, that would be one tagged
`// addon:` line — flag it at build time, don't assume it.)

## 7. Decisions (ratified — maintainer, 2026-07-26)

- **[D-036](../decisions/network-data.md)** — JSON is exposed as **`hafen.json`** (`parse`/`encode`), reusing the existing reader +
  REPL writer (writer moved into `Json`); not a `hafen.utils`. Ungated (pure CPU).
- **[D-037](../decisions/network-data.md)** — External requests are **`hafen.http`** (`get`/`post`), **async** with a tick-drained
  callback and a `:cancel()` handle; gated by a **`network` manifest block** whose **`hosts` allowlist** is the
  declaration; **private/loopback IPs blocked**; per-addon + global limits; the panel shows declared hosts.

## 8. Task slices (delivered as [013-data-network](../013-data-network/tasks.md))

- **N1 — `hafen.json`.** `parse` + `encode`; move the writer into `Json`; `jsonToLua`; null/array caveats; depth/size
  caps. Extend `hello` to round-trip a table. Zero core edit. **DoD:** `hafen.json.encode`/`parse` round-trip from
  `:lua` and an addon; malformed input `pcall`-able.
- **N2a — `hafen.http.get` + the whole async/security substrate.** Thread pool, `HttpURLConnection` GET, result
  queue + tick drain, `res` table, request handle + `:cancel()` + `Addon.requests` teardown, the `network` manifest
  block + host allowlist + private-IP block + the size/timeout/concurrency caps, panel host display. **No redirects**
  (3xx returned raw). A dedicated **example addon** (fetch + parse a small public JSON API — `hello` may be too
  broad; propose `netdemo`). **DoD:** an allowlisted GET returns JSON that `hafen.json.parse` reads; a disallowed
  host errors at call; a private-IP host is refused; `:reload` cancels in-flight, leaks nothing.
- **N2b — `hafen.http.post` + rich headers + redirect-follow.** POST (string + table→JSON body), request/response
  header round-trip, follow ≤5 redirects with per-hop re-validation. **DoD:** a POST round-trips a body; a redirect
  to a non-allowlisted host is blocked.

## 9. Non-goals / open items

- **No raw sockets / websockets / TCP-UDP** — HTTP request-response only (hence `hafen.http`, not `hafen.net`).
- **No streaming / download-to-file** — the body is buffered in memory (size-capped); no `io` write path (D-017).
- **No generic `request{method=}` / PUT/DELETE/PATCH in v1** — GET+POST only; add the generic form later if needed.
- **No `json.null` sentinel** — `null`↔`nil`, with the documented array-hole caveat.
- **No user-facing localhost/LAN override in v1** — private IPs blocked unconditionally; a declared opt-in is a later
  policy decision if a real local-tool use case appears.
- **No cookie jar / auth manager / ret=auto-retry** — stateless requests; the addon owns tokens and retries.
- **DNS-rebinding hardening** (pin the validated IP into the connection) — deferred to a later slice.
- **Async JSON parse for huge payloads** — v1 parses synchronously on the tick after the body arrives; size cap keeps
  it bounded.
