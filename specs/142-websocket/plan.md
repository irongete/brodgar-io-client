# 142 — websocket: plan

## Approach

`hafen.websocket()` is `hafen.http()`'s shape one step further: a `LuaCollection` of the addon's live
connections with `:connection(url)` as its `extra` verb, mounted by `Section.mount` from
`AddonManager.installHafen` beside `HttpApi.install`; a connection is userdata over a record with a
`Refusal.closedIndex("connection", …)` vocabulary; the gate runs in `:connect()` as
`HttpApi.requireNetwork` runs in `:send()` — `AddonManager.requirePermission` on
`Permission.WEBSOCKET_CONNECT`, then `Manifest.usesNetwork()`, then `Addon.hostGranted(origin)`, the
origin built by `Manifest.origin("https", host, port)`: the allowlist names servers, and a `wss` address
is the `https` server.

**The transport is the JDK's `java.net.http.WebSocket`**, on one engine-lifetime `HttpClient`
(`followRedirects(NEVER)`, executor = `HttpApi.pool()`, widened to package-private). `:connect()` submits
to that pool: resolve the host, refuse a blocked address through `LuaHttp.isBlockedAddress` (widened),
then `newWebSocketBuilder().connectTimeout(…).header(…).subprotocols(…).buildAsync(uri, listener)`; the
future's completion sets the state and enqueues `Open` or `Error`. **The listener only enqueues** — it
runs on the pool's thread, which may not enter Lua; it accumulates partial `onText` parts until `last`,
and calls `request(1)` after each callback while fewer than 256 messages wait, marking the connection
starved otherwise.

**Delivery is the layer's step**: `WebSocketApi.drain()` runs in `AddonManager.layerStep` right after
`runTimers()`, walks each addon's `connections` and fires each queued event through the connection's
`Subs` under `enterLua(a)`, as `runTimers(Addon)` does — `hafen.client():stepping()` true, no tree held,
a connection alive from `Load` on. It re-arms a starved connection, checks the close deadline, and drops
a connection once its `Close` or `Error` is delivered.

**Endings.** `:close(code, reason)` on `open` → `closing`, `sendClose`, the pair recorded, a five-second
deadline after which `abort()` sends the recorded pair out as `Close`; on `connecting` → cancel the
future, `Close` with your code; on `new` → `closed`, silently. The client's own refusals — a binary
frame, a message past 1 MB — `sendClose(1008, why)` and record it, so `Close` reports the client's code
whatever the peer echoes. Teardown is an `AddonRegistry` `Step` after "http requests": `sendClose(1001,
"going away")`, the record dropped, no handler run.

**Sending** chains `sendText` futures — the JDK refuses a second send while one is in flight — through a
per-connection queue capped at 64, with `conn:pending()` as the read the refusal names; a table goes
through `Json.write(v, true)` like a request body.

## Files to create or modify

- `src/io/brodgar/addon/WebSocketApi.java` — new: install, the vocabulary, the gate, drain, teardown
- `src/io/brodgar/addon/LuaWebSocket.java` — new: the record, the JDK listener, the queues
- `src/io/brodgar/addon/LuaWebSocketEvent.java` — new: the `Message`/`Close`/`Error` payloads, entity
  `ev`, one vocabulary each (the `LuaEvent` shape)
- `Permission.java` (`WEBSOCKET_CONNECT`, line *"keep a live connection to the servers it lists"*),
  `PermissionSet.grantsNetwork`, `Manifest` (the hosts-without-key rule and its message naming the three
  keys), `Addon` (`connections` beside `requests`), `AddonManager` (`installHafen`, `layerStep`),
  `AddonRegistry` (the `Step`), `HttpApi.pool()`, `LuaHttp.isBlockedAddress`
- `docs/addons/api/websocket.md` — new, 142.1 all but sending, 142.2 sending and the message caps
- the impact set: `api/conventions.md` (endings row `:close(code, reason)`, the l.364 sentence),
  `api/threading.md` (the row), `api/client/README.md` (l.42), `guides/permissions.md` (row, groups,
  l.67, the network section), `guides/saved-data.md`, `manifest.md`, `README.md`, `api/README.md`,
  `api/http.md` (See also)
- `tools/docverbs.py` — `"conn": "connection"` in `RECEIVERS` (`refusalverbs` inherits it through
  `MSG_RECEIVERS`); `ev` stays skipped, as it is for every `ev:` page
- `addons/142-websocket.1/`, `addons/142-websocket.2/` — the suites
- no `docs/client/` page: nothing upstream was read

## Risks and gotchas

- **`URI.toURL()` has no `wss` handler**: `HttpApi.httpOrigin` parses through `toURL`, and that path
  throws `MalformedURLException: unknown protocol: wss`. Parse with `java.net.URI` alone; `URI.create`
  still refuses a space, a brace and a bare `%`, and `getHost()` empty is the hostless refusal.
- **`WebSocket.sendClose` refuses `1002, 1003, 1006, 1007, 1009, 1010, 1012, 1013, 1015`** (its javadoc).
  The client's own refusals therefore close with `1008`, never `1003` or `1009`; the addon's `:close`
  accepts `1000` or `3000..4999`, refusing the rest by name.
- **`onText` is partial**: `last` says when a message is whole; one over the cap is cut at the part that
  crosses it, not buffered to the end.
- **`request(1)` is an obligation**: a listener that forgets it stalls the socket; `onOpen`, `onText`,
  `onPing` and `onPong` each request one more unless starved.
- **`buildAsync`'s failure calls no listener method**: `Error` comes from `whenComplete`; `onError` and
  `onClose` are terminal and exclusive, and a handshake `3xx` is a failure (no redirects).
- **Your own `sendClose` leaves the input open** until the peer's Close, an error or `abort()`: the
  deadline is what ends a peer that never answers, checked in the drain, no thread of its own.
- **Headers the JDK refuses raise at `buildAsync`**: `Sec-WebSocket-*` (`OpeningHandshake.ILLEGAL_HEADERS`)
  and `connection`, `content-length`, `expect`, `host`, `upgrade`. Drop them silently at `:header`, with
  `LuaHttp`'s transport-owned list.
- **`PermissionSet.grantsNetwork` decides whether the consent line carries the hosts**: without the new
  key there, the badge shows `[net]` and the dialog says nothing about where.
- **`layerStep` returns early** on `quiet()` and `state(u) == null`: the drain sits after `runTimers()`,
  inside the same try, so `stepThread` covers it.
- **The suite's echo hosts**: `echo.websocket.org` greets on connect, so a message check filters by a
  nonce; `ws.invalid` never resolves (RFC 2606) and is the `Error` case. Verification needs the network.
- A suite's `why()` strip is `^@?.-%.lua:%d+:?%s*` — the Java prefix carries a space, not a colon.

## Discarded alternatives

- **A hand-written RFC 6455 client over `SSLSocket`** — Java 8 API and address pinning for free, but
  five hundred lines of framing, masking, fragmentation and close handshake owned for ever, to gain
  cleartext `ws://`, which the address policy refuses to private space anyway.
- **Cleartext `ws://` with `hafen.http()`'s pin** — that pin connects "through a proxy" at the checked
  address, and the JDK's WebSocket tunnels through any proxy with a `CONNECT` the origin refuses; without
  the pin, cleartext is the half a rebinding name can point at a private address.
- **Draining on the session tick as http does** — a connection opened at `Load` has no session to drain
  it, and one whose login ends would die with the login; a connection's subject is a server, not a
  character.
- **Lower-case keys after `req:on("done")`** — conventions.md states the rule for keys the client fires;
  `done` is the exception, not the precedent.
- **A `wss://` spelling in the `network` block** — two spellings of one grant is the dual style this API
  refuses; the allowlist names servers, and a server is the same under `https` and `wss`.
- **Binary frames as Lua byte strings** — a send would need a second argument to say "binary", and
  nothing else in the API reads bytes; the other half of the page when a use exists.
- **An automatic reconnect** — five lines an addon should write, choosing what to resend and when to
  give up; a bridge that reconnects by itself is one the user cannot stop.
- **Delivering a message truncated at the cap** — half a message is a protocol error no handler can
  detect; a `1008` close is the honest outcome.
- **Queueing `send` before `Open`** — a message into a connection that may never open is a silent loss;
  refusing until `open` makes the order explicit.
- **`Close` carrying the peer's echo of a close you started** — the peer may echo anything or nothing;
  what you asked for is what you read back.
- **The JDK client's default executor** — thread growth beside the http pool, whose eight daemon threads
  carry callbacks that only enqueue.
