# hafen.websocket: a live connection to a server

Keep a connection open to a server outside the game and hear what it sends, instead of fetching a URL
whenever you want to know. It is **protected** the way [`hafen.http`](http.md) is: the
`websocket.connect` [permission key](../guides/permissions.md) says whether your addon may keep one, and
the manifest's `network` block says to which servers. Reach for it for a relay, a bridge or a feed that
pushes; for a question with one answer, [`hafen.http`](http.md) is the simpler door.

```lua
local conn = hafen.websocket():connection("wss://relay.example.com/feed")
conn:on("Open", function(c) c:send({ subscribe = "prices" }) end)
conn:on("Message", function(ev) hafen.log():write("relay: " .. ev:text()) end)
conn:on("Close", function(ev) hafen.log():write("closed: " .. ev:code() .. " " .. ev:reason()) end)
conn:on("Error", function(ev) hafen.log():write("failed: " .. ev:error()) end)
conn:connect()
```

## Declaring network access

**The same declaration a request needs, under its own key.** `websocket.connect` is a
[permission key](../guides/permissions.md) like any other, and the `network` block is what it takes — the
key says *whether*, the hosts say *where*:

```json
{
  "id": "relay",
  "api_version": "1.0",
  "files": ["main.lua"],
  "permissions": ["websocket.connect"],
  "network": {
    "hosts": ["relay.example.com"]
  }
}
```

- **The user reads them as one line** when they enable your addon: *"keep a live connection to the servers
  it lists: relay.example.com"*. An addon that fetches and connects declares both keys and one block, and
  the dialog prints one line per key over the same hosts.
- **A `wss://` address is the `https` server the block names.** An entry grants one origin — a host and a
  port, `https` on `443` unless you write another port — and a connection to `wss://relay.example.com/feed`
  is a connection to `https://relay.example.com:443`, which `relay.example.com` grants. Writing a `wss://`
  entry in the block is not how it is spelled: the block names servers, and a server is the same under
  both. Everything else about an entry — the wildcard, the case, the port — is on
  [`hafen.http`](http.md#declaring-network-access), and holds here unchanged.
- **The allowlist that gates a connection is the one the user approved**, not the one your manifest lists
  today; **hosts with no key is a load error**, and **a key with no hosts** is refused at `:connect()`,
  naming the block to add. A connection to any other origin is refused at `:connect()`, synchronously, as
  a Lua error naming the origin and the list that was approved.

## Connection

**A connection is built bare and opened on purpose.** `hafen.websocket():connection(url)` hands you one
that has gone nowhere, you configure it with chained setters, and `:connect()` is what opens it. Every
setter is legal until `:connect()` and none after — a rule with no timing in it.

```lua
hafen.websocket():connection("wss://relay.example.com/feed")
  :header("Authorization", "Bearer " .. hafen.store():var("cfg").token)
  :protocol("feed.v1")
  :timeout(5000)
  :on("Open", function(c) hafen.log():write("open, speaking " .. tostring(c:protocol())) end)
  :connect()
```

**The URL is `wss://` and is parsed strictly**, by RFC 3986. A `ws://` address is refused naming the scheme
to write — a connection in the clear is not one the client will make — and so is any other scheme, an
address with no host, one with a fragment, or one carrying a space, an unescaped `{`, `}`, `|` or `^`, or a
malformed `%` escape. Each raises at `:connection(url)`, naming the URL. The host that parse produces is
the one the approved allowlist is asked about.

Everything is **asynchronous**: `:connect()` returns at once with the connection `"connecting"`, and every
edge of its life arrives through `:on` a frame or more later, on the [step](threading.md), holding no
widget tree — so a handler may read and write any of them, and a connection needs no login: one opened from
`Load` is live on the login screen and outlives every character.

## The connection object

| Method | Returns | Description |
|---|---|---|
| `conn:url()` | string | the address it was built for |
| `conn:state()` | string | `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"` |
| `conn:header(name)` | string \| nil | the value this connection carries for `name`, matched case-insensitively |
| `conn:header(name, value)` | the connection | set a handshake header; setting it again replaces it, whatever the spelling |
| `conn:protocol()` | string \| nil | the subprotocol: the one you asked for until the handshake, the one the server agreed to from `Open` on, `nil` for none |
| `conn:protocol(name)` | the connection | ask for a subprotocol; a name that is not a token — letters, digits and the punctuation an HTTP token allows, with no space — is refused |
| `conn:timeout()` / `conn:timeout(ms)` | number / the connection | the milliseconds the handshake may take; **10000** by default, and a whole number **1..60000** — anything else is refused, never clamped |
| `conn:on(key, fn)` | [`Sub`](event/README.md#subscribe) | a handler for one of the [four keys](#what-it-says); legal before `:connect()` and after it |
| `conn:connect()` | the connection | **open it.** Every setter above is refused from here on, and so is a second `:connect()` — `conn:on` is not a setter, because a connection in flight has not ended yet |
| `conn:send(v)` | the connection | send a **string** as one text message, or a **table** as JSON; legal while `"open"`, refused in every other state; [below](#messages) |
| `conn:pending()` | number | how many messages `:send` has taken and the wire has not; `0` once everything sent has gone |
| `conn:close(code, reason)` | the connection | end it — `1000` and `""` when you pass nothing; [below](#ending-a-connection) |

Transport-owned headers are ignored — `Host`, `Connection`, `Upgrade`, `Content-Length`, `Expect`,
`User-Agent`, every `Sec-WebSocket-*` field the handshake writes for itself — and so are
**`Accept-Encoding`** and **`Cookie`**, for the reasons [`hafen.http`](http.md#the-request-object) gives.
A setter refuses an explicit `nil`: the read is the same name with no argument, so `conn:timeout(t)` with a
`t` you forgot to set would otherwise read the timeout and change nothing.

The connection is a [handle in the API's one shape](conventions.md#snapshots-vs-handles): a name it does
not answer raises naming the vocabulary, nothing can be written onto it, and `tostring(conn)` names the
address and the state — `Connection(wss://relay.example.com/feed, open)`.

> **The [permission](../guides/permissions.md) is checked by `:connect()`**, not by `:connection(url)`:
> nothing leaves the client until then. The URL's *syntax* is checked where you wrote it.

## What it says

A connection fires four keys, a **closed set**: a name outside it raises at `conn:on`, naming them. `Open`
has one thing to say and hands the connection; the other three hand an `ev` answering `ev:connection()` —
the very object `:connection(url)` gave you, so `==` tells connections apart in a shared handler — and
what the key carries.

| Key | When | Your handler is given |
|---|---|---|
| `Open` | the handshake completed; `conn:state()` reads `"open"` | the connection |
| `Message` | a whole text message arrived, [below](#messages) | `ev` — `ev:text()` the message |
| `Close` | the connection ended by a Close, yours or the peer's; `conn:state()` reads `"closed"` | `ev` — `ev:code()` the status number, `ev:reason()` the text, `""` for none |
| `Error` | the connection failed, or never opened; `conn:state()` reads `"closed"` | `ev` — `ev:error()` one line saying why: a host that does not resolve, an address that is refused, a handshake the server declined and the status it answered, `timeout` |

**Exactly one of `Close` and `Error` ends a connection**, and after it nothing more is fired: a connection
is either closed or failed, never both. What `Close` reports is what you asked for where you ended it, and
the peer's own code and reason where the peer did — a peer may echo anything or nothing, so your own pair
is what you read back.

Every handler runs on the [step](threading.md): `hafen.client():stepping()` is `true` inside it, it holds
no tree, and it may build a window or write any character's UI. Two handlers on one key both fire, in the
order they registered, and a handler that errors is isolated like every other. A `Sub` ends with
`sub:off()`, and every subscription on a connection is dropped for you once its `Close` or `Error` has run.

## Messages

**A connection carries text, both ways.** `conn:send(v)` puts one message on the wire: a **string** goes
as it is, a **table** goes as the JSON [`hafen.json():encode`](json.md) would write — the same rule a
request body follows, so one that JSON cannot hold (a function, a cycle, a key with no name) raises at
the call naming it, and anything that is neither raises naming both. What the peer sends arrives as
`Message`, one handler call per whole message however the wire split it, with `ev:text()` the message
and [`hafen.json():parse`](json.md) the reader when it is JSON.

```lua
conn:on("Message", function(ev)
  local ok, msg = pcall(function() return hafen.json():parse(ev:text()) end)
  if ok and msg.price then hafen.log():write("iron is " .. msg.price) end
end)
conn:send({ subscribe = "iron" })
```

- **`:send` is legal while the connection is `"open"`**, and refused in every other state naming
  `conn:state()`: a message into a connection that has not opened yet, or has begun to close, would go
  nowhere, so the order is explicit — send from `Open` on. `:send` hands the connection back, so a burst
  chains.
- **Messages go out in the order you sent them**, one at a time: the wire takes one, the rest wait, and
  `conn:pending()` is how many have not gone yet. It is `0` once everything you sent is on the wire, and
  how many may wait is [capped](#security-and-limits) — the next `:send` past the cap raises, and
  `conn:pending()` is the read that sees it coming.
- **A `:close()` follows what you sent.** Its Close frame goes out behind every message still pending, so
  `conn:send("bye"); conn:close()` says both, in that order; the wait for the peer's answer runs from the
  call either way.
- **A message that arrives while your own Close is unanswered is still delivered**: the connection has
  not ended until `Close` has run, and what the peer said before it heard you is part of the conversation.
- **A send the wire refuses ends the connection** with `Error` naming why — unless you had already closed
  it, in which case `Close` reports your pair and the send is the casualty.

## What is live

`hafen.websocket()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)
of **your own** connections that have been opened with `:connect()` and have not ended — the set the cap
below counts.

| Call | Returns |
|---|---|
| `hafen.websocket():list(filter)` | every live connection of yours |
| `hafen.websocket():count(filter)` | how many, which is what the cap counts |
| `hafen.websocket():find(filter)` | the first whose URL matches |
| `hafen.websocket():connection(url)` | a new one, unopened |

A string filter is a substring test over `conn:url()`. A connection has **no key** — it *is* the handle
`:connection(url)` gave you — so `:find(url)` is the search, and there is no `:get`. One you build and never
`:connect()`, or close before you do, costs nothing and holds no slot; one you have closed is live until its
`Close` has run, which is the next step at the earliest, so a `:connect()` in the same breath still counts it.

```lua
if hafen.websocket():count() >= 8 then return end          -- see the cap coming
for _, c in ipairs(hafen.websocket():list("relay.example.com")) do c:close() end
```

## Ending a connection

`conn:close(code, reason)` ends it, and it is the one verb legal in every state. The code is `1000` or a
number in `3000..4999` — the ones the protocol leaves to an application; the rest are the protocol's own
and are refused by name. The reason is a string of at most 123 bytes. Both default: `conn:close()` is
`1000` and `""`.

- **Open**: the connection reads `"closing"`, a Close frame goes out [behind what you sent](#messages), and
  `Close` fires with your pair when the peer answers — or after **five seconds** if it never does, when the
  client cuts the socket itself.
- **Connecting**: it reads `"closing"`, the handshake is abandoned, and `Close` fires with your pair on the
  next step. No `Open` is fired for a connection you closed before it opened.
- **New**: it reads `"closed"` at once, silently — it went nowhere, so there is nothing to report.
- **Closing or closed**: nothing; a second `:close()` answers the connection again.

A connection the peer ends fires `Close` with the peer's code and reason — `1006` and `""` where the peer
dropped the connection without sending one; one that fails fires `Error`. A reload, a disable and the
client exiting close every connection of yours with `1001`, and **no handler runs** — the addon they
belonged to is going away. There is no automatic reconnect: a `Close` handler and
a [timer](timer.md) are the whole of one, and what to resend when it reopens is yours to decide.

## Security and limits

> **A connection can never reach a server the user did not approve**, and it is never in the clear.

- **`wss://` only.** TLS is verified against the JDK trust store, and certificate verification is never
  disabled; the certificate is what binds the address you named to the socket you get.
- **Private, loopback and link-local addresses are refused**, even for an allowlisted host — the same list
  [`hafen.http`](http.md#security-and-limits) closes. A host that resolves into one fails with `Error`
  naming it.
- **The handshake does not follow redirects.** A `3xx` from the server is a failed handshake, reported by
  `Error` with its status, never a hop to a server the user did not approve.
- **A generic `User-Agent`, `brodgar-addon/1`, is sent**, and nothing identifies your character or your
  account. There are no cookies: any token is one you keep yourself, in [`hafen.store`](store/README.md), and set
  with `conn:header`.
- **Pings are answered by the client.** A peer that pings to see whether you are there hears a pong
  without your addon doing anything.
- **Text only, and no message over 1 MB, in either direction.** The cap is **1 MB** of UTF-8 a message:
  `:send` raises for one past it, naming the cap, and a message the peer sends past it — or any binary
  frame at all — closes the connection with **`1008`**, `Close` reporting that code and a reason saying
  which. Nothing of a refused message reaches your handler: half a message is a protocol error no
  handler could detect, so the close is the honest outcome.
- **Resource caps**: **8** live connections per addon, past which `:connect()` raises —
  `hafen.websocket():count()` sees it coming; **64** messages pending on one connection, past which
  `:send` raises — `conn:pending()` sees it coming; the handshake timeout **10 s** by default and settable
  anywhere in **1 ms..60 s**, refused outside it; **five seconds** for a peer to answer your Close before
  the socket is cut.

## See also

- [`hafen.http`](http.md) — the other door outside the client, and the network declaration in full
- [`hafen.voice`](voice/README.md) — a link to a voice server, in this section's shape
- [`hafen.json`](json.md) — the shape a feed's messages usually take
- [`hafen.timer`](timer.md) — reconnecting, on a schedule you choose
- [threading](threading.md) — where a handler runs, and why every one of these reaches every tree
- [permissions](../guides/permissions.md) — the protected tier, and the key this section needs
