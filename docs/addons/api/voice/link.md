# hafen.voice: the link

A **voice link** is one connection to one voice server: built bare by `hafen.voice():connection(url)`,
configured by chained setters, opened by `:connect()`, heard through `:on`, ended by `:close()` or by the
server. This page is the link's own life; what it sends and plays is [the mic and the mix](audio.md), and
who it relates you to is [peers](peers.md).

```lua
hafen.voice():connection("wss://voice.brodgar.io")
  :timeout(5000)
  :spatial(true)
  :bitrate(32000)
  :on("Open", function(v) hafen.log():write("talking on " .. v:url()) end)
  :connect()
```

## Link

**A link is built bare and opened on purpose.** `hafen.voice():connection(url)` hands you one that has
gone nowhere, you configure it with chained setters, and `:connect()` is what opens it — the handshake, the
microphone, the audio. `timeout`, `spatial` and `bitrate` are legal until `:connect()` and none after, a
rule with no timing in it; `session` and every [setting of the mic and the mix](audio.md#the-settings) are
legal in every state, because whose world the link reports and what it sends can change while it is open.

**The URL is `wss://` and is parsed strictly**, by the same rule a
[connection](../websocket.md#connection)'s is: a `ws://` address is refused naming the scheme to write, and
so is any other scheme, an address with no host, one with a fragment, or one carrying a space or a
malformed escape. Each raises at `:connection(url)`, naming the URL. Write the server's root, `wss://host`
or `wss://host:port`; the protocol's path is the server's own.

**A link speaks for one character** — its `session()`. By default it follows the screen: the character the
player is looking at is the one whose position, neighbours and orders the link reports, and a
[session switch](../session.md) moves the link with it. Pin a session with `session(s)` and the link stays
with that character whether or not it is drawn — a link per login on a client that holds several — and
`session(nil)` lets it follow the screen again.

Everything is **asynchronous**: `:connect()` returns at once with the link `"connecting"`, and every edge
of its life arrives through `:on` a frame or more later, on the [step](../threading.md), holding no widget
tree. A link needs no login to exist — one opened from `Load` is live on the login screen and outlives
every character — but it has nothing to report until its session is in the world.

## The link object

| Method | Returns | Description |
|---|---|---|
| `voice:url()` | string | the address it was built for |
| `voice:state()` | string | `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"` |
| `voice:id()` | number \| nil | the session id the server gave this link; `nil` before `Open` and once it has ended |
| `voice:session()` | [`Session`](../session.md) \| nil | the character it speaks for: the pinned one, else the one on screen, `nil` on the login screen |
| `voice:session(s)` | the link | pin it to `s`; `session(nil)` follows the screen again. Legal in every state |
| `voice:timeout()` / `voice:timeout(ms)` | number / the link | the milliseconds the handshake may take; **10000** by default, a whole number **1..60000** — anything else is refused, never clamped |
| `voice:spatial()` / `voice:spatial(on)` | boolean / the link | whether the players you hear are panned and faded by where they stand; **`true`** by default |
| `voice:bitrate()` / `voice:bitrate(n)` | number / the link | the Opus bitrate in bits per second; **24000** by default, a whole number **8000..64000**, refused outside it |
| `voice:on(key, fn)` | [`Sub`](../event/README.md#subscribe) | a handler for one of the [keys below](#what-it-says); legal before `:connect()` and after it |
| `voice:connect()` | the link | **open it.** The three connect-time setters are refused from here on, and so is a second `:connect()` |
| `voice:close()` | the link | end it; [below](#ending-a-link) |
| `voice:transmitting()` … `voice:volume(g)` | the value / the link | [the mic and the mix](audio.md#the-settings): what the link sends and plays, legal in every state |
| `voice:speaking()` | boolean | whether your voice is going out right now; [the mic and the mix](audio.md#read) |
| `voice:info()` | table | the settings and the counters as one [snapshot](audio.md#read) |
| `voice:peer()` | [collection](peers.md) | the players this link relates you to |

A setter refuses an explicit `nil` — the read is the same name with no argument — except `session(nil)`,
which is the write that answers the read's own default. The link is a
[handle in the API's one shape](../conventions.md#snapshots-vs-handles): a name it does not answer raises
naming the vocabulary, nothing can be written onto it, and `tostring(voice)` names the address and the
state — `Voice(wss://voice.brodgar.io, open)`.

## What it says

A link fires a **closed set** of keys: a name outside it raises at `voice:on`, naming them. `Open` has one
thing to say and hands the link; `Close` and `Error` hand an `ev` answering `ev:connection()` — the very
object `:connection(url)` gave you, so `==` tells links apart in a shared handler — and what the key
carries; the Peer keys hand the [Peer](peers.md#the-peer-object).

| Key | When | Your handler is given |
|---|---|---|
| `Open` | the handshake completed and the audio is running; `voice:state()` reads `"open"` and `voice:id()` is a number | the link |
| `Close` | the link ended, by your `:close()` or by the server; `voice:state()` reads `"closed"` | `ev` — `ev:reason()` the text, `""` where you closed it |
| `Error` | the link failed, or never opened; `voice:state()` reads `"closed"` | `ev` — `ev:error()` one line saying why: a host that does not resolve, an address that is refused, a microphone that will not open, a server that refused the hello or speaks another protocol version, a handshake past the timeout |
| `PeerAdded` | the server began relating you to a player — you hear them, or they you | the [Peer](peers.md) |
| `PeerRemoved` | it stopped; `peer:exists()` reads `false` | the Peer |
| `PeerChanged` | a peer's `audible()`, `hears()` or `speaking()` flipped | the Peer |

**Exactly one of `Close` and `Error` ends a link**, and after it nothing more is fired — no `PeerRemoved`
for the players it was relating you to, who simply stop existing. Every handler runs on the
[step](../threading.md): `hafen.client():stepping()` is `true` inside it, it holds no tree, and it may build
a window or write any character's UI. A `Sub` ends with `sub:off()`, and every subscription on a link is
dropped for you once its `Close` or `Error` has run.

## What is live

`hafen.voice()` is the
[collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of **your own**
links that have been opened with `:connect()` and have not ended.

| Call | Returns |
|---|---|
| `hafen.voice():list(filter)` | every live link of yours |
| `hafen.voice():count(filter)` | how many |
| `hafen.voice():find(filter)` | the first whose URL matches |
| `hafen.voice():connection(url)` | a new one, unopened |

A string filter is a substring test over `voice:url()`. A link has **no key** — it *is* the handle
`:connection(url)` gave you — so `:find(url)` is the search, and there is no `:get`. One you build and never
`:connect()`, or close before you do, costs nothing and holds no slot; one you have closed is live until its
`Close` has run.

```lua
for _, v in ipairs(hafen.voice():list("brodgar.io")) do v:close() end
```

## Ending a link

`voice:close()` ends it, and it is the one verb legal in every state.

- **Open**: the link reads `"closing"` while the server is told goodbye and the audio stops, and `Close`
  fires with `""` once that is done — so inside the handler the microphone is already released, the server
  slot is free, and a `:connect()` to the same server is not refused.
- **Connecting**: it reads `"closing"`, and `Close` fires with `""` on the next step; the handshake in
  flight is abandoned when it completes, and no `Open` is fired for it.
- **New**: it reads `"closed"` at once, silently — it went nowhere, so there is nothing to report.
- **Closing or closed**: nothing; a second `:close()` answers the link again.

A link the server ends fires `Close` with the server's reason; one that fails fires `Error`. A reload, a
disable and the client exiting close every link of yours, and **no handler runs** — the addon they belonged
to is going away. There is no automatic reconnect: a `Close` handler and a [timer](../timer.md) are the
whole of one.

## See also

- [`hafen.voice`](README.md) — the hub: the declaration, what the server is told, and the limits
- [the mic and the mix](audio.md) — what the link sends and plays
- [peers](peers.md) — who the link relates you to
- [`hafen.websocket`](../websocket.md) — the shape this link shares
