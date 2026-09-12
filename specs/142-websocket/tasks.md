# 142 — websocket: tasks

Suites at `addons/142-websocket.<X>/`, each with `"permissions": ["websocket.connect"]` and
`"network": {"hosts": ["ws.postman-echo.com", "echo.websocket.org", "ws.invalid"]}`, driven by one console
command `:t142`, printing `[pass]`/`[fail]`/`[manual]` lines and one `[summary]`. The echo address is
`wss://ws.postman-echo.com/raw`, with `wss://echo.websocket.org` as the fallback when the first ends in
`Error`. A suite scores every asynchronous check on one timer at the connect timeout plus two seconds, so
a wait is bounded and a verdict always prints. Refusal text is stripped with `^@?.-%.lua:%d+:?%s*`.

- [x] **142.1 — `hafen.websocket()`: a connection built bare, opened on purpose, and heard.** Adds the key
      `websocket.connect` (`Permission`, `PermissionSet.grantsNetwork`, `Manifest`'s hosts-without-key rule
      naming the three keys), `Addon.connections`, `WebSocketApi` mounted beside `HttpApi`, `LuaWebSocket`
      (the record, the JDK transport, a listener that only enqueues), the `Close`/`Error` payloads of
      `LuaWebSocketEvent`, the drain in `layerStep` after `runTimers()`, the teardown `Step` after "http
      requests", `HttpApi.pool()` and `LuaHttp.isBlockedAddress` widened. Verbs: `:connection(url)`,
      `conn:url/state/header/protocol/timeout/on/connect/close`; keys `Open`, `Close`, `Error`; the
      collection with its cap of 8 and no `:get`; `:close`'s five-second deadline. Docs:
      `docs/addons/api/websocket.md` (all but sending), the whole impact set of `spec.md` discharged,
      `docverbs.py` mapping `conn`; both checkers green.
      *Its suite* asserts, dry: the section is one object and a collection (`count()` is `0`, `#` is
      refused); `:connection("ws://…")` is refused naming `wss`, `"https://…"` naming the scheme,
      `"wss://a b"` naming the URL; a bare connection reads `state() == "new"`, `url()`, and `tostring`
      names both; `header("X-A", "1")` reads back under `"x-a"`, `timeout()` is `10000`, `timeout(0)` is
      refused naming `1..60000` and `timeout(nil)` is refused; `on("message", fn)` is refused naming
      `Open, Message, Close, Error`; `connect()` to `wss://api.example.org` is refused naming that origin
      and `approved:`; eight connections `connect()`ed count `8` and a ninth is refused naming
      `hafen.websocket():count()`, then all eight are closed; `close(1002)` is refused naming
      `1000 or 3000..4999`; a setter and a second `connect()` after `connect()` are each refused naming
      `:connect()`. Then, on the timer: `Open` came with `state() == "open"`,
      `hafen.client():stepping()` true and `hafen.session():current():ui()` readable inside it; after
      `close(4000, "bye")` the state read `"closing"`, `Close` came with a number in `ev:code()`,
      `ev:connection() == conn`, state `"closed"` and `count()` `0`; `wss://ws.invalid` ended in `Error`
      with `ev:error()` naming the host. The no-key and no-hosts refusals (the suite declares both), the
      teardown `Step` and the deadline are verified by reading their sites.
      `[manual]`: enable the suite in the AddOns panel and read its consent line — expect: *keep a live
      connection to the servers it lists: ws.postman-echo.com, echo.websocket.org, ws.invalid*.
      <!-- extra context: the http suite shape is the model for the timer-scored half -->

- [x] **142.2 — Messages: `send`, `Message`, `pending`, and the caps that close.** Adds `conn:send(v)` — a
      string as one text message, a table through `Json.write(v, true)` — and `conn:pending()`; the
      outbound queue chaining `sendText` futures, capped at 64 and refusing past it naming `conn:pending()`,
      a failed send ending the connection with `Error`; the `Message` payload (`ev:text()`,
      `ev:connection()`); inbound accumulation across partial `onText` parts, the 1 MB cap and the binary
      refusal, both `sendClose(1008, why)` recorded so `Close` reports the client's own code and reason; the
      `request(1)` discipline and the starved-at-256 re-arm on the drain. Docs: the sending section and the
      limits rows of `websocket.md`; `docverbs`/`refusalverbs` green.
      *Its suite* asserts, dry, on a bare connection: `send("x")` is refused naming `Open` and
      `conn:state()`; on an open one: `send(1)` is refused naming `a string` and `a table`;
      `send(string.rep("x", 1048577))` is refused naming the cap; `send({})` with a function inside is
      refused naming JSON. Then, against the echo, inside `Open`: it sends `"hello <nonce>"`, `{tag = nonce,
      n = 1}` and five strings `"<nonce> 1".."<nonce> 5"`; on the timer it scores, over the `Message`
      texts carrying the nonce: the string came back as sent; the table came back as JSON whose
      `hafen.json():parse` gives `tag == nonce`; the five arrived in order; `ev:connection() == conn`;
      `pending()` read `0` at the end; `Close` came after `close()` with code `1000`. The 64-pending
      refusal, the `1008` closes and the failed-send `Error` are verified by reading their sites — no echo
      service can be made to send binary or to stall on demand.
      `[manual]`: none.
