# hafen.websocket: A Live Connection to a Server

Keep a connection open to a server outside the game and hear what it pushes (a relay, a bridge, a feed). Protected as [`hafen.http`](http.md) is: the `websocket.connect` [permission key](../guides/permissions.md) says whether, the manifest's `network` block says to which servers. For a single request and reply, use [`hafen.http`](http.md).

```lua
local connection = hafen.websocket():connection("wss://relay.example.com/feed")
connection:on("Open", function(opened) opened:send({ subscribe = "prices" }) end)
connection:on("Message", function(event) hafen.log():write("relay: " .. event:text()) end)
connection:on("Close", function(event) hafen.log():write("closed: " .. event:code() .. " " .. event:reason()) end)
connection:on("Error", function(event) hafen.log():write("failed: " .. event:error()) end)
connection:connect()
```

---

## Declaring network access

The same declaration a request needs, under its own key. The user reads it as *"keep a live connection to the servers it lists: relay.example.com"*. An addon that fetches and connects declares both keys and one block, and the dialog prints one line per key.

```json
{
  "id": "relay",
  "api_version": "1.2",
  "files": ["main.lua"],
  "permissions": ["websocket.connect"],
  "network": {
    "hosts": ["relay.example.com"]
  }
}
```

| Rule | Detail |
|---|---|
| A `wss://` address is the `https` server the block names | An entry grants one origin, `https` on `443` unless another port is written: `wss://relay.example.com/feed` is `https://relay.example.com:443`, which `relay.example.com` grants. A `wss://` entry is not how it is spelled. The wildcard, case and port rules are [`hafen.http`](http.md#declaring-network-access)'s, unchanged. |
| The approved allowlist gates a connection | Not today's manifest. Hosts with no key is a load error. A key with no hosts is refused at `:connect()` naming the block. A connection to any other origin is refused at `:connect()`, synchronously, naming the origin and the approved list. |

## Connection

Built bare with `hafen.websocket():connection(url)`, configured with chained setters, opened by `:connect()`. Every setter is legal until `:connect()` and none after.

```lua
hafen.websocket():connection("wss://relay.example.com/feed")
  :header("Authorization", "Bearer " .. hafen.store():var("cfg").token)
  :protocol("feed.v1")
  :timeout(5000)
  :on("Open", function(opened) hafen.log():write("open, speaking " .. tostring(opened:protocol())) end)
  :connect()
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.websocket():connection(url)` | connection | Unprotected | A new connection, unopened. |
| `connection:url()` | `string` | Unprotected | The address it was built for. |
| `connection:state()` | `string` | Unprotected | `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"`. |
| `connection:header(name)` | `string \| nil` | Unprotected | The value this connection carries for `name`, matched case-insensitively. |
| `connection:header(name, value)` | the connection | Unprotected | Set a handshake header. Setting it again replaces it. |
| `connection:protocol()` | `string \| nil` | Unprotected | The subprotocol: the one asked for until the handshake, the one the server agreed to from `Open` on, `nil` for none. |
| `connection:protocol(name)` | the connection | Unprotected | Ask for a subprotocol. A name that is not an HTTP token (letters, digits, token punctuation, no space) is refused. |
| `connection:timeout()` / `connection:timeout(milliseconds)` | `number` / the connection | Unprotected | The milliseconds the handshake may take: `10000` by default, a whole number `1..60000`, refused outside, never clamped. |
| `connection:on(key, fn)` | [`Sub`](event/README.md#subscribe) | Unprotected | A handler for one of the [keys](#what-it-says). Legal before and after `:connect()`. |
| `connection:connect()` | the connection | `websocket.connect` | Open it. Every setter is refused from here on, and so is a second `:connect()`. |
| `connection:send(value)` | the connection | Unprotected | Send a string as one text message, or a table as JSON. Legal while `"open"` ([messages](#messages)). |
| `connection:pending()` | `number` | Unprotected | How many messages `:send` has taken and the wire has not. `0` once everything has gone. |
| `connection:close(code, reason)` | the connection | Unprotected | End it. `1000` and `""` when you pass nothing ([ending](#ending-a-connection)). |

| Rule | Detail |
|---|---|
| `wss://` only, parsed strictly | By RFC 3986. `ws://` is refused naming the scheme to write (no connection in the clear). So is any other scheme, no host, a fragment, a space, an unescaped `{`, `}`, `\|` or `^`, a malformed `%` escape. Each raises at `:connection(url)` naming the URL. The parsed host is the one the allowlist is asked about. |
| Asynchronous | `:connect()` returns at once with the connection `"connecting"`. Every edge arrives through `:on` a frame or more later on the [step](threading.md), holding no tree, so a handler may read and write any. A connection needs no login: one opened from `Load` is live on the login screen and outlives every character. |
| Headers ignored | Transport-owned (`Host`, `Connection`, `Upgrade`, `Content-Length`, `Expect`, `User-Agent`, every `Sec-WebSocket-*`), plus `Accept-Encoding` and `Cookie`, for the reasons [`hafen.http`](http.md#request) gives. An explicit `nil` to a setter is refused. |
| A handle | A name it does not answer raises naming the vocabulary. Nothing can be written onto it ([handles](conventions.md#snapshots-vs-handles)). `tostring(connection)` is `Connection(wss://relay.example.com/feed, open)`. |
| The permission is checked by `:connect()` | Not by `:connection(url)`. The URL's syntax is checked where you wrote it. |

## What it says

A closed set of keys. A name outside it raises at `connection:on`, naming them. `Open` hands the connection. The others hand an `event` answering `event:connection()` and what the key carries. The connection is the object `:connection(url)` gave you, so `==` tells connections apart in a shared handler.

| Key | When | Handler is given |
|---|---|---|
| `Open` | The handshake completed. `connection:state()` reads `"open"`. | The connection. |
| `Message` | A whole text message arrived ([messages](#messages)). | `event`: `event:text()` the message. |
| `Close` | The connection ended by a Close, yours or the peer's. State `"closed"`. | `event`: `event:code()` the status number, `event:reason()` the text, `""` for none. |
| `Error` | The connection failed, or never opened. State `"closed"`. | `event`: `event:error()` one line saying why (a host that does not resolve, an address refused, a handshake declined and its status, `timeout`). |

| Rule | Detail |
|---|---|
| Exactly one of `Close` and `Error` ends a connection | After it nothing more fires. `Close` reports your pair where you ended it, the peer's code and reason where the peer did (a peer may echo anything or nothing). |
| Where handlers run | On the [step](threading.md): `hafen.client():stepping()` is `true`, no tree held, any character's UI writable. Two handlers on one key fire in registration order. An erroring handler is isolated. A `Sub` ends with `subscription:off()`, and every subscription is dropped once `Close` or `Error` has run. |

## Messages

A connection carries text both ways. `connection:send(value)` puts one message on the wire: a string as is, a table as [`hafen.json():encode`](json.md) writes it, the rule a request body follows. Anything else raises naming both. What the peer sends arrives as `Message`, one handler call per whole message however the wire split it.

```lua
connection:on("Message", function(event)
  local ok, message = pcall(function() return hafen.json():parse(event:text()) end)
  if ok and message.price then hafen.log():write("iron is " .. message.price) end
end)
connection:send({ subscribe = "iron" })
```

| Rule | Detail |
|---|---|
| `:send` while `"open"` | Refused in every other state naming `connection:state()`: send from `Open` on. Hands the connection back, so a burst chains. |
| In order, one at a time | The wire takes one and the rest wait. `connection:pending()` is how many have not gone. The queue is [capped](#security-and-limits). The next `:send` past the cap raises. |
| A `:close()` follows what you sent | Its Close frame goes out behind every pending message, so `connection:send("bye"); connection:close()` says both in order. The wait for the peer's answer runs from the call. |
| Still delivered while your Close is unanswered | The connection has not ended until `Close` has run. |
| A send the wire refuses | Ends the connection with `Error` naming why, unless you had closed it, in which case `Close` reports your pair. |

## What is live

`hafen.websocket()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your own connections opened with `:connect()` and not ended.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.websocket():list(filter)` | connection`[]` | Unprotected | Every live connection of yours. |
| `hafen.websocket():count(filter)` | `number` | Unprotected | How many. What the cap counts. |
| `hafen.websocket():find(filter)` | connection `\| nil` | Unprotected | The first whose URL matches. |

A string filter is a substring test over `connection:url()`. A connection has no key (it is the handle), so `:find(url)` is the search and there is no `:get`. One built and never `:connect()`ed, or closed before, holds no slot. One closed is live until its `Close` has run, the next step at the earliest.

```lua
if hafen.websocket():count() >= 8 then return end          -- see the cap coming
for _, connection in ipairs(hafen.websocket():list("relay.example.com")) do connection:close() end
```

## Ending a connection

`connection:close(code, reason)` is the one verb legal in every state. `code` is `1000` or a number in `3000..4999` (the application range). The protocol's own codes are refused by name. `reason` is a string of at most 123 bytes. Both default: `connection:close()` is `1000` and `""`.

| State | Effect |
|---|---|
| Open | Reads `"closing"`. A Close frame goes out [behind what you sent](#messages). `Close` fires with your pair when the peer answers, or after five seconds when the client cuts the socket. |
| Connecting | Reads `"closing"`. The handshake is abandoned. `Close` fires with your pair on the next step. No `Open` is fired. |
| New | Reads `"closed"` at once, silently. |
| Closing or closed | Nothing. The connection is answered again. |

| Rule | Detail |
|---|---|
| The peer ends it | `Close` with the peer's code and reason. `1006` and `""` where the peer dropped without sending one. A failure fires `Error`. |
| Reload, disable, client exit | Every connection of yours is closed with `1001` and no handler runs. |
| No automatic reconnect | A `Close` handler and a [timer](timer.md) are the whole of one. What to resend is yours. |

## Security and limits

> **A connection can never reach a server the user did not approve**, and it is never in the clear.

| Rule | Detail |
|---|---|
| `wss://` only | TLS verified against the JDK trust store, never disabled. The certificate binds the address you named to the socket. |
| Private, loopback and link-local addresses are refused | The list [`hafen.http`](http.md#security-and-limits) closes. A host resolving into one fails with `Error` naming it. |
| No redirects | A `3xx` from the server is a failed handshake, reported by `Error` with its status. |
| Identity | A generic `User-Agent`, `brodgar-addon/1`. Nothing identifies your character or account. No cookies. A token is one you keep in [`hafen.store`](store/README.md) and set with `connection:header`. |
| Pings are answered by the client | A peer that pings hears a pong without your addon doing anything. |
| Text only, 1 MB a message, either direction | `:send` raises for one past it naming the cap. A message the peer sends past it, or any binary frame, closes the connection with `1008`, `Close` reporting the code and a reason saying which. Nothing of a refused message reaches your handler. |
| Caps | 8 live connections per addon, past which `:connect()` raises (`hafen.websocket():count()` sees it coming). 64 messages pending on one connection, past which `:send` raises (`connection:pending()` sees it coming). Handshake timeout 10 s by default, `1 ms..60 s`. Five seconds for a peer to answer your Close before the socket is cut. |

---

## See Also

- [`hafen.http`](http.md) — the other door outside the client, and the network declaration in full.
- [`hafen.voice`](voice/README.md) — a link to a voice server, in this section's shape.
- [`hafen.json`](json.md) — the shape a feed's messages usually take.
- [`hafen.timer`](timer.md) — reconnecting, on a schedule you choose.
- [Threading](threading.md) — where a handler runs, and why every one of these reaches every tree.
- [Permissions](../guides/permissions.md) — the protected tier, and the key this section needs.
