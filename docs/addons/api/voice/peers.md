# hafen.voice: Peers

A peer is a player a [link](link.md) relates you to: someone the server lets you hear, someone it lets hear you, or both. `voice:peer()` is the collection. `voice:peer():get(gob)` addresses one by Gob, the only handle a player has on this side of the wire. `PeerAdded`, `PeerRemoved` and `PeerChanged` follow them in and out.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
local speaking = {}
voice:on("PeerChanged", function(peer)
  speaking[peer:gob()] = peer:speaking() or nil     -- the gob is the key: draw over it from here
end)
voice:on("PeerRemoved", function(peer) speaking[peer:gob()] = nil end)
```

---

## The collection

| Method | Returns | Permission | Description |
|---|---|---|---|
| `voice:peer():list(filter)` | `Peer[]` | Unprotected | Every peer of this link. |
| `voice:peer():count(filter)` | `number` | Unprotected | How many. |
| `voice:peer():find(filter)` | `Peer \| nil` | Unprotected | The first the function filter keeps. |
| `voice:peer():get(gob)` | `Peer` | Unprotected | The peer for that [Gob](../gob.md). Never `nil`, `peer:exists()` is the question. |

| Rule | Detail |
|---|---|
| The server's set | Read fresh on every call: who is near enough on the server's own model of proximity, not who is in view. Empty until `Open`, empty from the moment the link begins to end, empty while nobody is near. |
| No name | The client is never sent one, so a string filter is refused naming why. Pass a function, or nothing. |
| `:get(gob)` | A Gob object and nothing else (an id, a name or `nil` is refused naming a Gob). It mints a peer for any Gob, related or not, so the mute and volume below can be set before they are near. |

## The Peer object

A [handle in the API's one shape](../conventions.md#snapshots-vs-handles). Read through `:get`, listed or handed by a key, it is the same object while you hold it, so it works as a table key. It wraps the link and the gob id, and every read asks the link at the call.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `peer:gob()` | [Gob](../gob.md) `\| nil` | Unprotected | The player's object, in the link's own [session](link.md#link). `nil` on the login screen. |
| `peer:id()` | `number` | Unprotected | The gob id. Answers from the handle alone, inside `PeerRemoved` too. |
| `peer:exists()` | `boolean` | Unprotected | Whether the server relates you right now, either way round. `false` once out of range or the link has ended. |
| `peer:audible()` | `boolean` | Unprotected | Whether you hear them: the server sends you their voice. |
| `peer:hears()` | `boolean` | Unprotected | Whether they hear you: the server sends them yours. |
| `peer:speaking()` | `boolean` | Unprotected | Whether their voice is arriving right now, with the hangover of [`voice:speaking()`](audio.md#read). |
| `peer:muted()` / `peer:muted(flag)` | `boolean` / the peer | Unprotected | Whether you discard their voice on this link. `false` unless set. |
| `peer:volume()` / `peer:volume(gain)` | `number` / the peer | Unprotected | The gain their voice is played at, `0..4`, `1` as sent, refused outside. Multiplies with [`voice:volume()`](audio.md#the-settings). |
| `peer:info()` | `table` | Unprotected | The [`Peer` snapshot](../types/world.md#peer). |

| Rule | Detail |
|---|---|
| `muted` and `volume` are yours, and they stay | Remembered on the link by gob id, not on the server or the audio stream. A player you muted is still muted when they walk back into range. A peer that does not `exists()` reads them as set. Two links relating you to one player are two peers with a mute each. |
| `peer:gob()` | Minted through the character the link speaks for, the handle [`session:world():gob():get(id)`](../world.md) answers there, so `==` holds. A gob that world has not loaded (a peer the server hears for you before they are drawn) answers a Gob whose `:exists()` is `false`. That asks the world, where `peer:exists()` asks the server. |

## What it says

The Peer keys are subscribed on the link with [`voice:on`](link.md#what-it-says) and delivered on the [step](../threading.md) in the order the server reported them.

| Key | When | Handler is given |
|---|---|---|
| `PeerAdded` | The server began relating you to a player: `audible()` or `hears()`, or both, went `true`. Once per player, however many directions open together. | The Peer. |
| `PeerRemoved` | It stopped: both are `false` and `exists()` reads `false`. | The Peer. |
| `PeerChanged` | One of `audible()`, `hears()` and `speaking()` flipped on a peer that exists. No field says which, so read the peer. A voice starting and stopping is one each way. | The Peer. |

When the link ends, `Close` or `Error` is the last thing fired: the peers stop existing with it and no `PeerRemoved` is sent.

---

## See Also

- [The link](link.md) — the keys in full, and how a link is opened and ended.
- [The mic and the mix](audio.md) — the link-wide settings a peer's own multiply with.
- [`Peer`](../types/world.md#peer) — the snapshot shape.
- [`gob:overlay()`](../overlay.md) — drawing a speaker's state above their head.
