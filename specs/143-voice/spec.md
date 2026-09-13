# 143 — voice: `hafen.voice()`, and the engine in-tree

## What & why

Proximity voice is Java hardcoded into the client, over an SDK fetched as a jar from a release of
its own. It turns round here: the **engine** comes into the tree as `io.brodgar.voice.*`, its two
third-party jars fetched like LuaJ; the **link** to a voice server becomes `hafen.voice()`, a connection
in `hafen.websocket()`'s shape; everything a player sees or presses becomes an addon's.

## The surface

`hafen.voice()` is the collection of your voice connections: **one per server, four per client**, one
microphone shared, opened once and fed to every link. A second `:connect()` to a server a link already
speaks to is refused naming the addon holding it: the protocol would silence both.
`hafen.voice():connection(url)` hands you a bare `Voice` with a websocket's `url`, `state`, `on`,
`connect`, `close` and `timeout`, plus `spatial` and `bitrate` as connect-time setters. Its own: `id()`,
the session id, `nil` until `Open`; `session([s])`, the login it speaks for — `nil`, the default, follows
the screen — writable any time; the mic and the mix, legal in every state, live from `Open`: `transmitting`, `vad`, `threshold`, `agc`, `muted`, `deafened`, `volume` (`0..4`);
`speaking()`; `info()`; `peer()`, the players the link relates you to, addressed by Gob — who,
whether you hear them, they you, they talk; `muted` and `volume`, yours, kept across a peer's comings and goings. Six keys: `Open`, `Close`, `Error` (an `ev`: `connection`, `reason`/`error`), `PeerAdded`, `PeerRemoved`,
`PeerChanged` (the Peer). Handlers run on the layer's step; a reload closes the link; no automatic
reconnect.

**The radial menu takes a petal of yours**: `s:flowermenu():add(label, fn)` inside a `FlowerMenuAdded`
handler appends one the ring lays out; picking it runs `fn(petal, s)` and ends the ring, only the cancel
sent; `petal:native()` says whose a petal is.

**Policy**: `voice.connect`, consent line *"use your microphone to talk on the voice servers it lists"*,
the `network` hosts its argument; `wss://` only; private addresses refused; the relay a server names carries
its audio.

## Acceptance criteria

1. **In-tree, and retired.** `ant hafen-client` is green with the voice jar gone: `io.brodgar.voice.*`
   compiles from `src/`, `get-concentus` and `get-minimal-json` fetch what the `Class-Path` names;
   `Voice`, `SpeakerIcon`, `VoiceTarget`, the options page, the Java petal and `brodgar/ptt` are gone
   (the build proves it; the binding's `:exists()` reads `false`).
2. **The key.** `voice.connect` is in the catalogue with that line; hosts with any of the four keys
   load; the dialog prints it with the hosts (`[manual]`).
3. **The link.** URL and connect-time setters are checked as a websocket's; `:on` outside the six keys
   raises naming them; `Open` comes with state `"open"` and a numeric `id()`; `session()` is
   the drawn session by default and a pinned one reads back; `:close()` fires `Close` and empties the
   collection; a second link to another server is live beside the first (`count()` `2`) until its
   unresolvable host ends it with `Error`; one to the same server is refused naming the holder.
4. **The mic and the mix.** Every live setter round-trips before and after `Open`; `volume(5)` and
   `threshold(-1)` are refused naming their ranges; `speaking()` reads `true` while the maintainer talks
   in a bounded window; `info()` carries the counters.
5. **Peers.** `peer():get(gob)` answers a Peer with `exists()` false until the server relates it, and a
   non-Gob is refused; `muted(true)` and `volume(2)` read back on it; `list()` is empty alone; the peer
   keys fire from the engine's set changes and the speaking flip (read: no suite has a second player).
6. **The host.** Positions, move intents (the user's click and `s:player():move`) and spatial vectors
   are Java, reported to every live link from its `session()`, re-derived every call; the microphone is
   one capture, opened by the first link and closed by the last (read).
7. **The petal.** `:add(label, fn)` from `FlowerMenuAdded` appends a petal `list()` includes, `native()`
   false; `petal:select()` runs `fn(petal, s)` and `FlowerMenuRemoved` carries the label; outside the
   handler, with an empty label or a non-function, it is refused naming why.
8. **Docs and checkers.** The page whole, the impact set discharged, the checkers green.

## Out of scope

The engine's other knobs — jitter, complexity, hangover, the spatial radius and floor — keep their
defaults. A server on `localhost` is refused by the network rule; the engine's `testclient` is for that. **The `voice` addon is the addons repository's**: written against this surface once it closes,
then listed on `etc/release-addons` and `examples.md`; the server is its own repository.

## Docs impact

Written: `docs/addons/api/voice.md` (a directory past 300 lines). Derived:

```text
grep -rn "websocket\.connect" docs/   -- three network keys become four
  api/conventions.md:366 · guides/permissions.md:52,68,271,276 · manifest.md:44
grep -rn "websocket" docs/addons/     -- the rows a voice connection joins
  api/threading.md:32 · api/client/README.md:43 · api/conventions.md:150 · README.md:49 · api/README.md:171
grep -rn -i "voice|SpeakerIcon|VoiceTarget|mute toggle" docs/
  addons/api/flowermenu.md:147 · addons/api/types/ui.md:74 · client/map-click.md:19
  client/network.md:25,26,31 · client/state.md:14 · client/world-3d.md:116,117
```

## Context files

- `src/io/brodgar/voice/`: `BrodgarVoice.java`, `BrodgarVoiceHost.java`, `VoiceListener.java`,
  `VoiceConfig.java`, `internal/RxMixer.java`, `internal/TxPipeline.java` — 2 (the engine, in-tree since 143.1)
- `src/io/brodgar/addon/`: `VoiceApi.java`, `LuaVoice.java`, `LuaVoiceEvent.java`, `SharedMic.java`,
  `LuaPeer.java` — 2 (the section, the record and host, the endings, the microphone, the peer); `WebSocketApi.java`,
  `LuaWebSocket.java`, `LuaWebSocketEvent.java` — 2; `FlowerMenuApi.java`, `LuaPetal.java` — 3, 4;
  `Addon.java`, `AddonManager.java` — 2; `AddonRegistry.java` — 2, 4; `LuaGob.java` — 2; `Subs.java`, `Section.java`,
  `LuaCollection.java`, `Refusal.java`, `Args.java`
- `src/haven/FlowerMenu.java` — 3
- `docs/addons/api/`: `voice/README.md`, `voice/link.md`, `voice/audio.md`, `voice/peers.md` — 2 (the
  hub, the link, the mic and the mix, the peers); `websocket.md` — 2; `flowermenu.md`, `types/ui.md` — 3;
  `types/world.md`, `gob.md` — 2
- `docs/client/`: `radial-menu.md` — 3; `audio.md` — 2 (the positional pipeline a link's panning mirrors)
- `DOCUMENTATION.md`, `tools/docverbs.py` (`arr(vo, "KEYS")`, the `voice` and `peer` receivers are in),
  `tools/refusalverbs.py`
