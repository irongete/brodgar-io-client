# hafen.voice: A Link to a Voice Server

Talk to the players near you over a proximity voice server and hear them panned by where they stand. `hafen.voice()` is the collection of your addon's live voice links, each a connection to one server in the shape of a [`hafen.websocket`](../websocket.md) connection. It is protected under `voice.connect`, because a link opens the user's microphone. The client draws no voice UI and binds no key.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
voice:on("Open", function(link) hafen.log():write("voice up, session " .. link:id()) end)
voice:on("Close", function(event) hafen.log():write("voice closed: " .. event:reason()) end)
voice:on("PeerAdded", function(peer) hafen.log():write("near you: " .. peer:id()) end)
voice:vad(true):transmitting(true):connect()
```

---

## The pages

| Page | Holds |
|---|---|
| [The link](link.md) | Building one, opening it, what it says, what is live, ending it. |
| [The mic and the mix](audio.md) | What you send and what you hear: the settings, whether you are speaking, the counters. |
| [Peers](peers.md) | The players a link relates you to, addressed by Gob, and the keys that follow them. |

## Declaring network access

The same declaration a request or a connection needs, under its own key. The user reads it as *"use your microphone to talk on the voice servers it lists: voice.brodgar.io"*. An addon that also fetches or keeps a connection declares those keys beside it, one line per key over the same hosts.

```json
{
  "id": "myaddon",
  "api_version": "1.1",
  "files": ["main.lua"],
  "permissions": ["voice.connect"],
  "network": {
    "hosts": ["voice.brodgar.io"]
  }
}
```

| Rule | Detail |
|---|---|
| A `wss://` address is the `https` server the block names | As for a [connection](../websocket.md#declaring-network-access): `wss://voice.brodgar.io` is `https://voice.brodgar.io:443`, which `voice.brodgar.io` grants. The wildcard, case and port rules are [`hafen.http`](../http.md#declaring-network-access)'s. |
| The approved allowlist gates a link | Not today's manifest. Hosts with no key is a load error. A key with no hosts is refused at `:connect()` naming the block. Any other origin is refused at `:connect()`, synchronously, naming the origin and the approved list. |
| Checked by `:connect()` | Not by `:connection(url)`: nothing leaves the client and the microphone stays closed until then. The URL's syntax is checked where you wrote it. |

## What the server is told

| Sent | Detail |
|---|---|
| Every half second | The id of the character's own object. The vector from the character to each player object in view, in tiles and in the world's own frame. Each move order issued since the last report (a ground click, a [`session:player():move`](../player.md)), as a vector from where they stood. The server keeps proximity right between reports. |
| Never | A world coordinate, a grid id or an account name. |
| The audio | Encrypted, over a relay the server names in its welcome. |

The client draws nothing: whether a player is speaking, who hears you, a mute are yours to draw with [`gob:overlay()`](../overlay.md) and [`hafen.ui`](../ui/README.md). The client pans each voice by its position when `spatial()` is on.

## Security and limits

> **A link can never reach a server the user did not approve**, and the microphone opens only for a link the user approved.

| Rule | Detail |
|---|---|
| `wss://` only | TLS verified against the JDK trust store, never disabled. |
| Private, loopback and link-local addresses are refused | The list [`hafen.http`](../http.md#security-and-limits) closes. A host resolving into one fails with `Error` naming it. A server on your own machine cannot be reached from an addon. |
| The audio relay | Trusted with the audio and nothing else: a server the user approved chooses where its audio goes. |
| One microphone, shared | The first link to open takes the capture device, the last to end releases it. Every link between is fed from it. Whether it transmits is each link's own [setting](audio.md#the-settings). |
| One link per server, for the whole client | A second `:connect()` to a server any addon holds a live link to is refused naming that addon (a second session would silence both). `hafen.voice():find(host)` reaches the live one when it is yours. |
| Caps | 4 live links in the whole client, every addon together, past which `:connect()` raises. Handshake timeout 10 s by default, `1 ms..60 s`. |

---

## See Also

- [`hafen.websocket`](../websocket.md) — the shape this section shares, and the network declaration in full.
- [`hafen.session`](../session.md) — the character a link speaks for.
- [`gob:overlay()`](../overlay.md) — drawing a speaker's state above their head.
- [Threading](../threading.md) — where a handler runs, and why every one of these reaches every tree.
- [Permissions](../../guides/permissions.md) — the protected tier, and the key this section needs.
