# hafen.voice: a link to a voice server

Talk to the players near you, over a proximity voice server, and hear them panned by where they stand.
`hafen.voice()` is the collection of your addon's live **voice links**, each one a connection to one
server in the shape of a [`hafen.websocket`](../websocket.md) connection: built bare, opened on purpose,
heard through `:on`. It is **protected** the way that section is — the `voice.connect`
[permission key](../../guides/permissions.md) says whether your addon may open one, and the manifest's
`network` block says to which servers — because a link opens the user's microphone and puts their voice on
a server. Reach for it to build the voice addon your players use; the client itself draws no voice UI and
binds no key.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
voice:on("Open", function(v) hafen.log():write("voice up, session " .. v:id()) end)
voice:on("Close", function(ev) hafen.log():write("voice closed: " .. ev:reason()) end)
voice:on("PeerAdded", function(peer) hafen.log():write("near you: " .. peer:id()) end)
voice:vad(true):transmitting(true):connect()
```

## The pages

| Page | What it holds |
|---|---|
| [the link](link.md) | building one, opening it, what it says, what is live, and ending it |
| [the mic and the mix](audio.md) | what you send and what you hear: the settings, whether you are speaking, and the counters |
| [peers](peers.md) | the players a link relates you to, addressed by Gob, and the keys that follow them |

## Declaring network access

**The same declaration a request or a connection needs, under its own key.** `voice.connect` is a
[permission key](../../guides/permissions.md) like any other, and the `network` block is what it takes — the
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
  [connection](../websocket.md#declaring-network-access): `wss://voice.brodgar.io` is a link to
  `https://voice.brodgar.io:443`, which `voice.brodgar.io` grants. Everything else about an entry — the
  wildcard, the case, the port — is on [`hafen.http`](../http.md#declaring-network-access), and holds here
  unchanged.
- **The allowlist that gates a link is the one the user approved**, not the one your manifest lists today;
  **hosts with no key is a load error**, and **a key with no hosts** is refused at `:connect()`, naming the
  block to add. A link to any other origin is refused at `:connect()`, synchronously, as a Lua error naming
  the origin and the list that was approved.

> **The [permission](../../guides/permissions.md) is checked by `:connect()`**, not by `:connection(url)`:
> nothing leaves the client, and the microphone stays closed, until then. The URL's *syntax* is checked
> where you wrote it.

## What the server is told

The link reports your character to the server so the server can decide who hears whom — and it reports
**relative positions only**. Every half second it sends the id of the character's own object, the vector
from the character to each player object in view, in tiles and in the world's own frame, and each **move
order** the character issued since the last report — a click on the ground, or an
[`s:player():move`](../player.md) — as a vector from where they stood, the instant it was given, so the
server can keep proximity right between two reports. No world coordinate, grid id or account name ever
leaves the client; the audio itself travels encrypted over a relay the server names in its welcome.

**The client draws nothing.** Whether a player is speaking, who can hear you, a mute — everything a voice
addon shows is yours to draw, with [`gob:overlay()`](../overlay.md) and [`hafen.ui`](../ui/README.md); the
only thing the client does by itself is pan each voice by its position, when `spatial()` is on.

## Security and limits

> **A link can never reach a server the user did not approve**, and the microphone opens only for a link
> the user approved.

- **`wss://` only.** TLS is verified against the JDK trust store, and certificate verification is never
  disabled.
- **Private, loopback and link-local addresses are refused**, even for an allowlisted host — the same list
  [`hafen.http`](../http.md#security-and-limits) closes. A host that resolves into one fails with `Error`
  naming it. A server on your own machine is one such host: it cannot be reached from an addon.
- **The audio relay the server names is trusted with the audio**, and with nothing else: a server the user
  approved chooses where its own audio goes.
- **One microphone, shared.** The first link to open takes the capture device and the last to end releases
  it; every link between is fed from it. Whether it transmits is each link's own
  [setting](audio.md#the-settings).
- **One link per server, for the whole client.** A second `:connect()` to a server any addon already holds a
  live link to is refused naming that addon, because a second session from the same client would silence
  both — `hafen.voice():find(host)` is how you reach the one that is live when it is yours.
- **Resource caps**: **4** live links in the whole client, every addon together, past which `:connect()`
  raises; the handshake timeout **10 s** by default and settable anywhere in **1 ms..60 s**, refused
  outside it.

## See also

- [`hafen.websocket`](../websocket.md) — the shape this section shares, and the network declaration in full
- [`hafen.session`](../session.md) — the character a link speaks for
- [`gob:overlay()`](../overlay.md) — drawing a speaker's state above their head
- [threading](../threading.md) — where a handler runs, and why every one of these reaches every tree
- [permissions](../../guides/permissions.md) — the protected tier, and the key this section needs
