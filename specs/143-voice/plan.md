# 143 — voice: plan

## Approach

**The engine comes in as source, its two third-party halves as jars.** The 40 files under
`src/main/java/io/brodgar/voice/` of `../brodgar-io-voice-client` at `8b1f708` — the code the jar was
built from, `Spatializer`'s `(right, forward)` convention included — land in `src/io/brodgar/voice/`
unchanged, tests left behind (no JUnit in ant). `build.xml` gains `get-concentus`
(`https://jitpack.io/com/github/lostromb/concentus/Concentus/17318837bf/Concentus-17318837bf.jar`) and
`get-minimal-json` (Maven Central, `0.9.5`) into `lib/brodgar/`; `hafen-client` depends on both, the
`Class-Path` names both, `get-brodgar-voice` goes. `WireCodec` runs on `minimal-json`, so a jar missing
from the `Class-Path` fails at the first connect, not at compile.

**`hafen.voice()` is `WebSocketApi` a second time**, the engine where the JDK socket was. `VoiceApi`
installs the collection with `:connection(url)` as its `extra` verb; `:connect()` gates on
`Permission.VOICE_CONNECT` → `Manifest.usesNetwork()` → `Addon.hostGranted(origin)`
(`WebSocketApi.wssOrigin`), refuses a second link to an origin any addon's live link speaks to
(naming that addon) and a fifth live link in the client, then submits to `HttpApi.pool()`: resolve and
`LuaHttp.isBlockedAddress`, then `BrodgarVoice.connect(cfg, host)` — **blocking**, handshake and audio
open — with `autoReconnect(false)`, `clientInfo("hafen+brodgar")`, `connectTimeoutMs` from `timeout`,
`spatialAudio`/`bitrate` from the setters, `audioSource(SharedMic.feed())`. Success enqueues `Open` and
applies the desired settings (`applyAll`, as `Voice.java` did); a `VoiceException` enqueues `Error`.
`LuaVoice` is the record: the websocket's state machine and event queue, the desired settings, the
`BrodgarVoiceHost` bound to its `session` field, and a `VoiceListener` that only enqueues.

**The host is per link and derived every call.** `mapview()` is `Sessions.mapview(u)` for the pinned
session's UI (`AddonManager.sessionui(user)`) or the anchor's (`Sessions.anchor()`) for `nil`;
`localGobId()` is that view's `plgob`; `visiblePlayers()` is `Voice.Bridge.visiblePlayers` moved —
snapshot under the `OCache` lock, classify by `gfx/borka/body` outside it; the intent sink is fed by
`AddonManager.voiceMove(mv, mc)` from two taps, `MapView.clickhit` (the existing site) and `Sessions.send`
(an addon's order); the per-frame spatial vectors
(`MapView.spatialAzimuth`, kept and retagged) are pushed from `VoiceApi.drain()` in `layerStep`, beside
the websocket drain, which also delivers the queued events and drops an ended link.

**One microphone.** `SharedMic` opens the engine's `MicSource` on the first link's connect and closes it
on the last link's end, ref-counted; each link gets an `AudioSource` feed — a bounded queue the capture
thread fills and the link's `TxPipeline` drains — so VAD, AGC and `isLocalSpeaking` stay per link.

**Peers** are `LuaPeer` handles interned per `(link, gob id)`: `exists()` is membership of
`audibleGobs() ∪ heardByGobs()`, `speaking()` is `isSpeaking(id)`, `muted`/`volume` are remembered on the
link and written through `setLocalMute`/`setVolume` (re-applied at `Open`), `gob()` the link's session's
Gob. The listener diffs each set change against the last union into `PeerAdded`/`PeerRemoved`, and
`PeerChanged` for a member whose `audible`/`hears`/`speaking` flipped; `onConnectionState(false)` ends
the link with `Close("connection lost")`, a fatal `onError` with `Error`, a non-fatal one is logged.

**The petal.** `FlowerMenu.Petal` gains `client`, a `Runnable` replacing `voiceMuteGob`;
`FlowerMenu.addClientPetal(label, run)` appends the petal, replaces `opts` and re-runs `organize(opts)`
(`Opening.ntick` reads `opts` each tick, so it lands with the ring); `choose()` runs `client` and sends
`"cl", -1`. `FlowerMenuApi` marks the ring being announced around `fireFlowerMenu`, and `:add` is legal
only then; `petal:select()` goes through `choose` as every pick does; `fn(petal, s)` runs under the
ring's tree, the `Pressed` row.

**Retired in 143.1**: `Voice`, `SpeakerIcon`, `VoiceTarget`; `OptWnd.VoiceChatPanel`, its `PanelEntry`
and the two keybinding rows; `MapView`'s `attach`/`detach`/`tick`/`sweep`/`kb_ptt` sites and the
`VoiceTarget.note` calls; `FlowerMenu.addVoicePetal` and its `choose` branch; the javadocs naming them.

## Files to create or modify

- new: `src/io/brodgar/voice/**` (40, vendored); `src/io/brodgar/addon/VoiceApi.java`, `LuaVoice.java`,
  `LuaVoiceEvent.java`, `SharedMic.java` — 1; `LuaPeer.java` — 2
- `build.xml` — 1
- `Permission.java` (`VOICE_CONNECT` and its line), `PermissionSet.grantsNetwork`, `Manifest`'s
  hosts-without-key rule — 1
- `Addon.voices`, `AddonManager` (`installHafen`, `layerStep`, `voiceMove`), `AddonRegistry` (a `Step`),
  `Sessions.send` — 1
- `MapView`, `OptWnd`, `FlowerMenu`; `SpeakerIcon`, `VoiceTarget`, `Voice` deleted — 1
- `FlowerMenu` (`Petal.client`, `addClientPetal`), `FlowerMenuApi` (`add`, `native`), `types/ui.md` — 3
- `docs/addons/api/voice.md` — 1 the link and the policy, 2 the rest; a directory past 300 lines
- the impact set as `spec.md` lists it — 1; `api/flowermenu.md` (a section, l.147) — 3
- `docs/client/`: the rows `spec.md` lists (`haven.Speaking` is the billboard precedent) — 1;
  `radial-menu.md` — 3
- `tools/docverbs.py`: `arr(vo, "KEYS")` over `LuaVoice.java`, `voice` and `peer` in `RECEIVERS` — 1, 2

## Risks and gotchas

- **`BrodgarVoice.connect` blocks** on the handshake and the audio open: pool thread only. It throws
  `VoiceException` for a refused hello, a version mismatch, no microphone — each an `Error` naming it.
- **The engine reconnects by itself unless told not to** (`autoReconnect` defaults `true`), and a link
  resurrected behind `Close` breaks the one-ending rule.
- **`VoiceListener` fires on engine threads** (`onSpeaking` from the mixer, the sets from presence):
  never enter Lua there; `isSpeaking`, `isLocalSpeaking`, `audibleGobs` are lock-free reads.
- **A feed's queue is bounded and dropping** — a link that stalls must not stall the capture — and
  `MicSource` closes on the last link's end, never on one link's while another is live.
- **`Sessions.send` composes an addon's ground click itself**, never passing `clickhit`: without the
  second tap a bot-driven character reports no intent and earns no score.
- **`FlowerMenuAdded` runs under the ring's tree** (the Loader thread that added the widget): `:add`
  writes that tree and is legal there; `Chosen` and `Cancel` index `opts` by `num`, which the appended
  petal carries.
- `Protocol.VERSION` is negotiated in the hello: a server on another version is an `Error` naming it.

## Discarded alternatives

- **The engine's own auto-reconnect** — it resurrects a link behind `Close` and gets a fresh session
  either way; a `Close` handler and a timer, as a websocket's.
- **One link per client** — the microphone is one, but the engine takes an injected source, and a group's
  server beside the public one is the use a collection exists for; the cost was one tee.
- **Two captures of the one device** — Windows allows it, other platforms refuse it.
- **The presence protocol in Lua over `hafen.websocket()`, the client keeping the audio engine alone** —
  feasible (only public values cross the handshake, and the 500 ms report is a timer) and the freer
  design: a protocol change without a client release, a private server with any presence model. Chosen
  against: every voice addon would carry the protocol and could break its own voice, a private server
  would have to reimplement the relay wire format anyway, and the engine's lower-level API and a UDP-endpoint
  policy would be a feature of their own. The protocol is the engine's; a private server
  runs the maintainer's server; a protocol change is a client release.
- **Keeping the jar** — a release round trip per change of the maintainer's own code; the third-party
  halves stay jars, stable and not his.
- **Porting `WireCodec` to the client's `Json`** — a protocol codec rewritten to save a 23-class jar
  the server keeps using.
- **A singleton section object** — one link per client baked in.
- **Peers keyed by number** — every verb takes the Gob.
- **`PeerSpeaking`/`PeerSilent`** — a boolean flip is `Changed`, as `KinChanged` is.
- **A standing petal rule matched to later rings** — a ring is announced once, its set complete; a petal
  is decided where the ring is, as a pick is.
- **`petal:select()` on a client petal under `flowermenu.cancel`** — picking is picking.
- **The Java speaker icon kept** — a presentation an addon draws with `gob:overlay():draw`; the client
  keeps no UI an addon cannot replace.
