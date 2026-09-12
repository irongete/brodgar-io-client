# 142 — websocket: `hafen.websocket()`

## What & why

An addon can fetch a URL and nothing more. A relay, a bridge, a feed that pushes — each is a **live
connection**, and today only Java inside the client can hold one. `hafen.websocket()` is that connection:
built bare, opened on purpose, heard through `:on`, under `hafen.http()`'s network policy — a catalogue
key, the `network` hosts as its argument, one consent line. The transport is the JDK's
`java.net.http.WebSocket`: no dependency, and it compiles today.

## The surface

`hafen.websocket()` is the collection of your **live** connections (`:list`, `:count`, `:find`; no
`:get`). `hafen.websocket():connection(url)` hands you a bare `Connection`:

| Verb | What it does |
|---|---|
| `conn:url()` · `conn:state()` | the address; `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"` |
| `conn:header(name[, value])` · `conn:protocol([name])` · `conn:timeout([ms])` | setters, legal until `:connect()`; the handshake deadline is `10000`, `1..60000` |
| `conn:on(key, fn)` → `Sub` | `Open`, `Message`, `Close`, `Error` — a closed set; legal before and after `:connect()` |
| `conn:connect()` | the gate, then the dispatch |
| `conn:send(v)` · `conn:pending()` | a string as a text message, a table as JSON; how many are not yet on the wire |
| `conn:close([code[, reason]])` | `1000` and `""` by default; `1000` or `3000..4999`, else refused |

`Open` hands the connection; `Message`, `Close`, `Error` hand an `ev` answering `ev:connection()` and
respectively `ev:text()`, `ev:code()` + `ev:reason()`, `ev:error()`. Exactly one of `Close` and `Error`
ends a connection. Handlers run on the **layer's** step, holding no tree: a connection works from `Load`
on, no login needed. Reload, disable and exit close every connection with `1001`; no handler runs.

**Policy, inherited whole**: `wss://` only, `ws://` refused at `:connection(url)` naming `wss`; the
`network` entry granting `https://h:p` grants `h:p`; private, loopback and link-local addresses refused;
TLS verified. Text only, both ways: a binary frame or a message past the cap closes the connection with
`1008`, `Close` saying why. Caps: **8** live connections per addon, **1 MB** a message, **64** pending
outbound — each refused naming the read that sees it coming. Pings are answered by the
client. No automatic reconnect: a `Close` handler and a timer are it.

## Acceptance criteria

Each is a suite's assertion unless it says how else.

1. **The key.** `websocket.connect` is a catalogue key; its consent line reads *"keep a live connection to
   the servers it lists"* with the hosts appended; a manifest with `network.hosts` and this key alone loads;
   `websocket.*` parses. `network` with none of the three keys is a load error naming all three (verified by
   reading).
2. **The gate.** `:connect()` without the key raises naming `websocket.connect`; with it and no hosts,
   naming the block; to an unapproved origin, naming it and the approved list.
3. **The builder.** `:connection(url)` refuses `ws://`, `http(s)://`, a hostless and a malformed URL,
   naming it; every setter reads back, refuses an explicit `nil`, a timeout outside `1..60000`, and any
   write after `:connect()`; `:on` outside the four keys raises naming them; `tostring(conn)` names URL and
   state.
4. **The life.** `:connect()` returns at once, state `"connecting"`; `Open` fires with the connection,
   state `"open"`, `:count()` counting it; `:close(code, reason)` reads `"closing"`, then `Close` fires with
   a code, state `"closed"`, the collection empty; a host that does not resolve fires `Error` naming it; a
   second `:connect()` raises.
5. **Messages.** A string echoes as sent, a table as JSON that parses back, five in order arrive in
   order; `:send` refuses a non-string non-table, a message over the cap, and any state but `"open"`;
   `:pending()` reads `0` once echoed.
6. **The cap.** The ninth live connection is refused naming `hafen.websocket():count()`.
7. **Threading.** `hafen.client():stepping()` is `true` inside every handler, which can read a
   character's tree.
8. **Docs and checkers.** The page is whole, the impact set below revised or discharged, and both
   `tools/` checkers exit zero.

## Out of scope

The boundary is where a *connection* ends and a *protocol* begins. Binary frames, a `:ping()` and an
automatic reconnect are verbs on this section when they come. A socket to `localhost` is
refused by the rule both network sections share; lifting it is a change to that rule. What rides a
connection is an addon's.

## Docs impact

Written: `docs/addons/api/websocket.md`. Derived — the pages stating the network as `http` alone:

```text
grep -rlnE "http\.get|http\.post|http\.\*|\`network\`|network block|\[net\]" docs/
  api/conventions.md     l.364 "Reaching outside the client…"; endings table l.148
  api/http.md            a See-also line
  guides/permissions.md  catalogue row, groups, l.67, l.270 "Reaching outside the client is http.get…"
  manifest.md            l.44 "the argument of the http.get/http.post key", l.56 "no http.* permission"
  runtime.md             l.149 [net] badge — discharged
grep -rnE "outside the client|an HTTP reply|HTTP.*reply" docs/addons
  api/threading.md:31    the row "an HTTP reply"; l.136
  api/client/README.md:42  what the client queues for the step
  guides/saved-data.md:118 "Data from elsewhere comes through hafen.http"
  README.md:49 · api/README.md:170  the index rows
```

## Context files

- `src/io/brodgar/addon/WebSocketApi.java` — 2 (the section, the gate, the drain, the teardown; 142.1 wrote it)
- `src/io/brodgar/addon/LuaWebSocket.java` — 2 (the record, the JDK listener, the event queue; 142.1 wrote it)
- `src/io/brodgar/addon/LuaWebSocketEvent.java` — 2 (the `Close`/`Error` payloads; `Message` joins them)
- `src/io/brodgar/addon/HttpApi.java` — 1, 2
- `src/io/brodgar/addon/LuaHttpRequest.java` — 1
- `src/io/brodgar/addon/LuaHttpResult.java` — 2
- `src/io/brodgar/addon/LuaHttp.java` — 1, 2
- `src/io/brodgar/addon/LuaEvent.java` — 2
- `src/io/brodgar/addon/Permission.java` — 1
- `src/io/brodgar/addon/PermissionSet.java` — 1
- `src/io/brodgar/addon/Manifest.java` — 1
- `src/io/brodgar/addon/Addon.java` — 1
- `src/io/brodgar/addon/AddonManager.java` — 1, 2
- `src/io/brodgar/addon/AddonRegistry.java` — 1
- `src/io/brodgar/addon/Subs.java` — 1, 2
- `src/io/brodgar/addon/Section.java`, `LuaCollection.java` — 1
- `src/io/brodgar/addon/Refusal.java`, `Args.java` — 1, 2
- `src/io/brodgar/addon/Json.java` — 2
- `C:/Program Files/Java/jdk-23/lib/src.zip` → `java.net.http/java/net/http/WebSocket.java` — 1, 2
- `docs/addons/api/websocket.md` — 2 (142.1 wrote all but the sending section and the message-cap rows)
- `docs/addons/api/http.md` — 1, 2
- `docs/addons/`: `api/conventions.md`, `api/threading.md`, `api/event/README.md`, `api/client/README.md`,
  `guides/permissions.md`, `guides/saved-data.md`, `manifest.md`, `README.md`, `api/README.md` — 1
- `docs/addons/api/json.md` — 2
- `DOCUMENTATION.md`
- `tools/docverbs.py`, `tools/refusalverbs.py` — 1, 2
