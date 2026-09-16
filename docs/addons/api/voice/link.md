# hafen.voice: The Link

A voice link is one connection to one voice server: built bare by `hafen.voice():connection(url)`, configured by chained setters, opened by `:connect()`, heard through `:on`, ended by `:close()` or by the server. What it sends and plays is [the mic and the mix](audio.md); who it relates you to is [peers](peers.md).

```lua
hafen.voice():connection("wss://voice.brodgar.io")
  :timeout(5000)
  :spatial(true)
  :bitrate(32000)
  :on("Open", function(link) hafen.log():write("talking on " .. link:url()) end)
  :connect()
```

---

## Link

| Rule | Detail |
|---|---|
| Built bare, opened on purpose | `:connect()` is the handshake, the microphone and the audio. `timeout`, `spatial` and `bitrate` are legal until `:connect()` and none after; `session` and every [setting of the mic and the mix](audio.md#the-settings) are legal in every state. |
| `wss://` only, parsed strictly | The rule a [connection](../websocket.md#connection)'s URL follows: `ws://`, any other scheme, no host, a fragment, a space or a malformed escape raise at `:connection(url)` naming the URL. Write the server's root, `wss://host` or `wss://host:port`; the protocol's path is the server's own. |
| Speaks for one character | Its `session()`. By default it follows the screen, and a [session switch](../session.md) moves it; `session(session)` pins it to that character, drawn or not (a link per login on a client holding several); `session(nil)` follows the screen again. |
| Asynchronous | `:connect()` returns at once with the link `"connecting"`; every edge arrives through `:on` a frame or more later on the [step](../threading.md), holding no tree. A link needs no login to exist (one opened from `Load` is live on the login screen and outlives every character) and has nothing to report until its session is in the world. |

## The link object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `voice:url()` | `string` | Unprotected | The address it was built for. |
| `voice:state()` | `string` | Unprotected | `"new"`, `"connecting"`, `"open"`, `"closing"` or `"closed"`. |
| `voice:id()` | `number \| nil` | Unprotected | The session id the server gave this link; `nil` before `Open` and once ended. |
| `voice:session()` | [`Session`](../session.md) `\| nil` | Unprotected | The character it speaks for: the pinned one, else the one on screen; `nil` on the login screen. |
| `voice:session(session)` | the link | Unprotected | Pin it; `session(nil)` follows the screen again. Legal in every state. |
| `voice:timeout()` / `voice:timeout(milliseconds)` | `number` / the link | Unprotected | The milliseconds the handshake may take: `10000` by default, a whole number `1..60000`, refused outside, never clamped. |
| `voice:spatial()` / `voice:spatial(flag)` | `boolean` / the link | Unprotected | Whether the players you hear are panned and faded by where they stand; `true` by default. |
| `voice:bitrate()` / `voice:bitrate(bits)` | `number` / the link | Unprotected | The Opus bitrate in bits per second: `24000` by default, a whole number `8000..64000`, refused outside. |
| `voice:on(key, fn)` | [`Sub`](../event/README.md#subscribe) | Unprotected | A handler for one of the [keys](#what-it-says); legal before and after `:connect()`. |
| `voice:connect()` | the link | `voice.connect` | Open it. The connect-time setters are refused from here on, and so is a second `:connect()`. |
| `voice:close()` | the link | Unprotected | End it ([below](#ending-a-link)). |
| `voice:transmitting()` … `voice:volume(gain)` | the value / the link | Unprotected | [The mic and the mix](audio.md#the-settings), legal in every state. |
| `voice:speaking()` | `boolean` | Unprotected | Whether your voice is going out right now ([read](audio.md#read)). |
| `voice:info()` | `table` | Unprotected | The settings and the counters as one [snapshot](audio.md#read). |
| `voice:peer()` | [collection](peers.md) | Unprotected | The players this link relates you to. |

| Rule | Detail |
|---|---|
| An explicit `nil` is refused | Except `session(nil)`, the write that answers the read's own default. |
| A handle | A name it does not answer raises naming the vocabulary; nothing can be written onto it ([handles](../conventions.md#snapshots-vs-handles)). `tostring(voice)` is `Voice(wss://voice.brodgar.io, open)`. |

## What it says

A closed set of keys; a name outside it raises at `voice:on`, naming them. `Open` hands the link; `Close` and `Error` hand an `event` answering `event:connection()` (the object `:connection(url)` gave you) and what the key carries; the Peer keys hand the [Peer](peers.md#the-peer-object).

| Key | When | Handler is given |
|---|---|---|
| `Open` | The handshake completed and the audio runs; `voice:state()` reads `"open"`, `voice:id()` is a number. | The link. |
| `Close` | The link ended, by your `:close()` or by the server; state `"closed"`. | `event`: `event:reason()` the text, `""` where you closed it. |
| `Error` | The link failed, or never opened; state `"closed"`. | `event`: `event:error()` one line saying why (a host that does not resolve, an address refused, a microphone that will not open, a server that refused the hello or speaks another protocol version, a handshake past the timeout). |
| `PeerAdded` | The server began relating you to a player: you hear them, or they you. | The [Peer](peers.md). |
| `PeerRemoved` | It stopped; `peer:exists()` reads `false`. | The Peer. |
| `PeerChanged` | A peer's `audible()`, `hears()` or `speaking()` flipped. | The Peer. |

| Rule | Detail |
|---|---|
| Exactly one of `Close` and `Error` ends a link | After it nothing fires: no `PeerRemoved` for the players it related you to, who stop existing. |
| Where handlers run | On the [step](../threading.md): `hafen.client():stepping()` is `true`, no tree held, any character's UI writable. A `Sub` ends with `subscription:off()`; every subscription is dropped once `Close` or `Error` has run. |

## What is live

`hafen.voice()` is the [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of your own links opened with `:connect()` and not ended.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.voice():list(filter)` | link`[]` | Unprotected | Every live link of yours. |
| `hafen.voice():count(filter)` | `number` | Unprotected | How many. |
| `hafen.voice():find(filter)` | link `\| nil` | Unprotected | The first whose URL matches. |
| `hafen.voice():connection(url)` | link | Unprotected | A new one, unopened. |

A string filter is a substring test over `voice:url()`. A link has no key (it is the handle), so `:find(url)` is the search and there is no `:get`. One built and never `:connect()`ed, or closed before, holds no slot; one closed is live until its `Close` has run.

```lua
for _, voice in ipairs(hafen.voice():list("brodgar.io")) do voice:close() end
```

## Ending a link

`voice:close()` is the one verb legal in every state.

| State | Effect |
|---|---|
| Open | Reads `"closing"` while the server is told goodbye and the audio stops; `Close` fires with `""` once done, so inside the handler the microphone is released, the server slot is free, and a `:connect()` to the same server is not refused. |
| Connecting | Reads `"closing"`; `Close` fires with `""` on the next step; the handshake in flight is abandoned when it completes, and no `Open` fires. |
| New | Reads `"closed"` at once, silently. |
| Closing or closed | Nothing; the link is answered again. |

| Rule | Detail |
|---|---|
| The server ends it | `Close` with the server's reason; a failure fires `Error`. |
| Reload, disable, client exit | Every link of yours is closed and no handler runs. |
| No automatic reconnect | A `Close` handler and a [timer](../timer.md) are the whole of one. |

---

## See Also

- [`hafen.voice`](README.md) — the hub: the declaration, what the server is told, and the limits.
- [The mic and the mix](audio.md) — what the link sends and plays.
- [Peers](peers.md) — who the link relates you to.
- [`hafen.websocket`](../websocket.md) — the shape this link shares.
