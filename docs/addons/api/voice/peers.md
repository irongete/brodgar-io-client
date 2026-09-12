# hafen.voice: peers

A **peer** is a player a [link](link.md) relates you to: someone the server lets you hear, someone it lets
hear you, or both. `voice:peer()` is the collection of them and `voice:peer():get(gob)` addresses one **by
Gob** — the only handle a player has on this side of the wire — and the keys `PeerAdded`,
`PeerRemoved` and `PeerChanged` follow them in and out. Reach for it to draw who is talking, to show who
can hear you, and to mute one player without muting the rest.

```lua
local speaking = {}
voice:on("PeerChanged", function(peer)
  speaking[peer:gob()] = peer:speaking() or nil     -- the gob is the key: draw over it from here
end)
voice:on("PeerRemoved", function(peer) speaking[peer:gob()] = nil end)
```

## The collection

`voice:peer()` is a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)
read fresh on every call, and it is the set the **server** decides — who is near enough, on the server's own
model of proximity — not who is in view. It is empty until `Open`, empty again from the moment the link
begins to end, and empty for as long as nobody is near.

| Call | Returns |
|---|---|
| `voice:peer():list(filter)` | every peer of this link |
| `voice:peer():count(filter)` | how many |
| `voice:peer():find(filter)` | the first the function filter keeps |
| `voice:peer():get(gob)` | the peer for that [Gob](../gob.md) — **never `nil`**: `peer:exists()` is the question |

A peer has no name — the client is never sent one — so a string filter is refused naming why; pass a
function, or nothing. `:get(gob)` takes a Gob object and nothing else — an id, a name or `nil` is refused
naming a Gob — and mints a peer for any Gob you hand it, whether or not the server relates you to that
player, so the mute and volume below can be set before they are near.

## The Peer object

A peer is a [handle in the API's one shape](../conventions.md#snapshots-vs-handles): read through `:get`,
listed, or handed by a key, it is the same object as long as you hold it, so it works as a table key. It
wraps the link and the gob id, and every read below asks the link's engine at the moment of the call.

| Method | Returns | Description |
|---|---|---|
| `peer:gob()` | [Gob](../gob.md) \| nil | the player's object, in the link's own [session](link.md#link); `nil` on the login screen |
| `peer:id()` | number | the gob id — answers from the handle alone, inside `PeerRemoved` too |
| `peer:exists()` | boolean | whether the server relates you right now, either way round; `false` once they are out of range or the link has ended |
| `peer:audible()` | boolean | whether you hear them: the server sends you their voice |
| `peer:hears()` | boolean | whether they hear you: the server sends them yours |
| `peer:speaking()` | boolean | whether their voice is arriving right now, with the same short hangover as [`voice:speaking()`](audio.md#read) |
| `peer:muted()` / `peer:muted(on)` | boolean / the peer | whether **you** discard their voice on this link; `false` unless you set it |
| `peer:volume()` / `peer:volume(g)` | number / the peer | the gain their voice is played at, `0..4`, `1` being as sent, refused outside it; multiplies with [`voice:volume()`](audio.md#the-settings) |
| `peer:info()` | table | the [`Peer` snapshot](../types/world.md#peer) |

**`muted` and `volume` are yours, and they stay.** They are remembered on the link by gob id — not on the
server, and not on the engine's stream, which comes and goes — so a player you muted is still muted when
they walk back into range, and a peer that does not `exists()` reads them back as you set them. They are
unprotected: nothing about them leaves the client. Two links relating you to one player are two peers,
with a mute each, because what is asked — do I hear them *on this server* — is per link.

`peer:gob()` is minted through the character the link speaks for, so it is the very handle
[`s:world():gob():get(id)`](../world.md) answers there, and `==` holds between the two. A gob the
character's own world has not loaded — a peer the server hears for you before they are drawn — answers a
Gob whose `:exists()` is `false`, which is not the peer's own `exists()`: the first asks the world, the
second the server.

## What it says

The Peer keys are the link's own, subscribed on the link with [`voice:on`](link.md#what-it-says) and
delivered on the [step](../threading.md) with the rest of its edges, in the order the server reported them.

| Key | When | Your handler is given |
|---|---|---|
| `PeerAdded` | the server began relating you to a player — `audible()` or `hears()`, or both, just went `true` | the Peer |
| `PeerRemoved` | it stopped relating you: both are `false` and `exists()` reads `false` | the Peer |
| `PeerChanged` | one of `audible()`, `hears()` and `speaking()` flipped on a peer that exists | the Peer |

A player who walks into range fires `PeerAdded` once, however many directions open together;
`PeerChanged` carries no field saying which — read the peer. A voice starting and stopping is a
`PeerChanged` each way, so a speaker icon is one handler and one read. When the link ends, `Close` or
`Error` is the last thing fired: the peers it held stop existing with it and no `PeerRemoved` is sent for
them.

## See also

- [the link](link.md) — the keys in full, and how a link is opened and ended
- [the mic and the mix](audio.md) — the link-wide settings a peer's own multiply with
- [`Peer`](../types/world.md#peer) — the snapshot shape
- [`gob:overlay()`](../overlay.md) — drawing a speaker's state above their head
