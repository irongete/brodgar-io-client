# hafen.voice: a link to a voice server

Talk to the players near you, over a proximity voice server, and hear them panned by where they stand.
`hafen.voice()` is the collection of your addon's live **voice links**, each one a connection to one
server in the shape of a [`hafen.websocket`](websocket.md) connection: built bare, opened on purpose,
heard through `:on`. It is **protected** the way that section is — the `voice.connect`
[permission key](../guides/permissions.md) says whether your addon may open one, and the manifest's
`network` block says to which servers — because a link opens the user's microphone and puts their voice on
a server. Reach for it to build the voice addon your players use; the client itself draws no voice UI and
binds no key.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
voice:on("Open", function(v) hafen.log():write("voice up, session " .. v:id()) end)
voice:on("Close", function(ev) hafen.log():write("voice closed: " .. ev:reason()) end)
voice:on("Error", function(ev) hafen.log():write("voice failed: " .. ev:error()) end)
voice:connect()
```

## Declaring network access

**The same declaration a request or a connection needs, under its own key.** `voice.connect` is a
[permission key](../guides/permissions.md) like any other, and the `network` block is what it takes — the
key says *whether*, the hosts say *where*:

```json
{
  "id": "myaddon",
  "api_version": "1.0",
  "files": ["main.lua"],
  "permissions": ["voice.connect"],
  "network": {
    "hosts": ["voice.brodgar.io"]
  }
}
```

- **The user reads it as one line** when they enable your addon: *"use your microphone to talk on the
  voice servers it lists: voice.brodgar.io"*. The line names the microphone because that is what the key
  grants; an addon that also fetches or keeps a connection declares those keys beside it, and the dialog
  prints one line per key over the same hosts.
- **A `wss://` address is the `https` server the block names**, exactly as it is for a
  [connection](websocket.md#declaring-network-access): `wss://voice.brodgar.io` is a link to
  `https://voice.brodgar.io:443`, which `voice.brodgar.io` grants. Everything else about an entry — the
  wildcard, the case, the port — is on [`hafen.http`](http.md#declaring-network-access), and holds here
  unchanged.
- **The allowlist that gates a link is the one the user approved**, not the one your manifest lists today;
  **hosts with no key is a load error**, and **a key with no hosts** is refused at `:connect()`, naming the
  block to add. A link to any other origin is refused at `:connect()`, synchronously, as a Lua error naming
  the origin and the list that was approved.

## Link

**A link is built bare and opened on purpose.** `hafen.voice():connection(url)` hands you one that has
gone nowhere, you configure it with chained setters, and `:connect()` is what opens it — the handshake, the
microphone, the audio. `timeout`, `spatial` and `bitrate` are legal until `:connect()` and none after, a
rule with no timing in it; `session` is legal in every state, because whose world the link reports can
change while it is open.

```lua
hafen.voice():connection("wss://voice.brodgar.io")
  :timeout(5000)
  :spatial(true)
  :bitrate(32000)
  :on("Open", function(v) hafen.log():write("talking on " .. v:url()) end)
  :connect()
```

**The URL is `wss://` and is parsed strictly**, by the same rule a [connection](websocket.md#connection)'s
is: a `ws://` address is refused naming the scheme to write, and so is any other scheme, an address with no
host, one with a fragment, or one carrying a space or a malformed escape. Each raises at
`:connection(url)`, naming the URL. Write the server's root, `wss://host` or `wss://host:port`; the
protocol's path is the server's own.

**A link speaks for one character** — its `session()`. By default it follows the screen: the character the
player is looking at is the one whose position, neighbours and orders the link reports, and a
[session switch](session.md) moves the link with it. Pin a session with `session(s)` and the link stays with
that character whether or not it is drawn — a link per login on a client that holds several — and
`session(nil)` lets it follow the screen again.

Everything is **asynchronous**: `:connect()` returns at once with the link `"connecting"`, and every edge
of its life arrives through `:on` a frame or more later, on the [step](threading.md), holding no widget
tree. A link needs no login to exist — one opened from `Load` is live on the login screen and outlives
every character — but it has nothing to report until its session is in the world.

## The link object

| Method | Returns | Description |
|---|---|---|
| `voice:url()` | string | the address it was built for |
| `voice:state()` | string | `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"` |
| `voice:id()` | number \| nil | the session id the server gave this link; `nil` before `Open` and once it has ended |
| `voice:session()` | [`Session`](session.md) \| nil | the character it speaks for: the pinned one, else the one on screen, `nil` on the login screen |
| `voice:session(s)` | the link | pin it to `s`; `session(nil)` follows the screen again. Legal in every state |
| `voice:timeout()` / `voice:timeout(ms)` | number / the link | the milliseconds the handshake may take; **10000** by default, a whole number **1..60000** — anything else is refused, never clamped |
| `voice:spatial()` / `voice:spatial(on)` | boolean / the link | whether the players you hear are panned and faded by where they stand; **`true`** by default |
| `voice:bitrate()` / `voice:bitrate(n)` | number / the link | the Opus bitrate in bits per second; **24000** by default, a whole number **8000..64000**, refused outside it |
| `voice:on(key, fn)` | [`Sub`](event/README.md#subscribe) | a handler for one of the [keys below](#what-it-says); legal before `:connect()` and after it |
| `voice:connect()` | the link | **open it.** The three connect-time setters are refused from here on, and so is a second `:connect()` |
| `voice:close()` | the link | end it; [below](#ending-a-link) |

A setter refuses an explicit `nil` — the read is the same name with no argument — except `session(nil)`,
which is the write that answers the read's own default. The link is a
[handle in the API's one shape](conventions.md#snapshots-vs-handles): a name it does not answer raises
naming the vocabulary, nothing can be written onto it, and `tostring(voice)` names the address and the
state — `Voice(wss://voice.brodgar.io, open)`.

> **The [permission](../guides/permissions.md) is checked by `:connect()`**, not by `:connection(url)`:
> nothing leaves the client, and the microphone stays closed, until then. The URL's *syntax* is checked
> where you wrote it.

## What it says

A link fires a **closed set** of keys: a name outside it raises at `voice:on`, naming them. `Open` has one
thing to say and hands the link; the other two hand an `ev` answering `ev:connection()` — the very object
`:connection(url)` gave you, so `==` tells links apart in a shared handler — and what the key carries.

| Key | When | Your handler is given |
|---|---|---|
| `Open` | the handshake completed and the audio is running; `voice:state()` reads `"open"` and `voice:id()` is a number | the link |
| `Close` | the link ended, by your `:close()` or by the server; `voice:state()` reads `"closed"` | `ev` — `ev:reason()` the text, `""` where you closed it |
| `Error` | the link failed, or never opened; `voice:state()` reads `"closed"` | `ev` — `ev:error()` one line saying why: a host that does not resolve, an address that is refused, a microphone that will not open, a server that refused the hello or speaks another protocol version, a handshake past the timeout |

**Exactly one of `Close` and `Error` ends a link**, and after it nothing more is fired. Every handler runs
on the [step](threading.md): `hafen.client():stepping()` is `true` inside it, it holds no tree, and it may
build a window or write any character's UI. A `Sub` ends with `sub:off()`, and every subscription on a link
is dropped for you once its `Close` or `Error` has run.

## What is live

`hafen.voice()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of
**your own** links that have been opened with `:connect()` and have not ended.

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
to is going away. There is no automatic reconnect: a `Close` handler and a [timer](timer.md) are the whole
of one.

## What the server is told

The link reports your character to the server so the server can decide who hears whom — and it reports
**relative positions only**. Every half second it sends the id of the character's own object, the vector
from the character to each player object in view, in tiles and in the world's own frame, and each **move
order** the character issued since the last report — a click on the ground, or an
[`s:player():move`](player.md) — as a vector from where they stood, the instant it was given, so the server
can keep proximity right between two reports. No world coordinate, grid id or account name ever leaves the
client; the audio itself travels encrypted over a relay the server names in its welcome.

**The client draws nothing.** Whether a player is speaking, who can hear you, a mute — everything a voice
addon shows is yours to draw, with [`gob:overlay()`](overlay.md) and [`hafen.ui`](ui/README.md); the only
thing the client does by itself is pan each voice by its position, when `spatial()` is on.

## Security and limits

> **A link can never reach a server the user did not approve**, and the microphone opens only for a link
> the user approved.

- **`wss://` only.** TLS is verified against the JDK trust store, and certificate verification is never
  disabled.
- **Private, loopback and link-local addresses are refused**, even for an allowlisted host — the same list
  [`hafen.http`](http.md#security-and-limits) closes. A host that resolves into one fails with `Error`
  naming it. A server on your own machine is one such host: it cannot be reached from an addon.
- **The audio relay the server names is trusted with the audio**, and with nothing else: a server the user
  approved chooses where its own audio goes.
- **One microphone, shared.** The first link to open takes the capture device and the last to end releases
  it; every link between is fed from it. Whether it transmits is each link's own setting.
- **One link per server, for the whole client.** A second `:connect()` to a server any addon already holds a
  live link to is refused naming that addon, because a second session from the same client would silence
  both — `hafen.voice():find(host)` is how you reach the one that is live when it is yours.
- **Resource caps**: **4** live links in the whole client, every addon together, past which `:connect()`
  raises; the handshake timeout **10 s** by default and settable anywhere in **1 ms..60 s**, refused
  outside it.

## See also

- [`hafen.websocket`](websocket.md) — the shape this section shares, and the network declaration in full
- [`hafen.session`](session.md) — the character a link speaks for
- [`gob:overlay()`](overlay.md) — drawing a speaker's state above their head
- [threading](threading.md) — where a handler runs, and why every one of these reaches every tree
- [permissions](../guides/permissions.md) — the protected tier, and the key this section needs
