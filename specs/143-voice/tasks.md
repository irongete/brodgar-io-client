# 143 — voice: tasks

Suites at `addons/143-voice.<X>/`, one console command `:t143`, verdict lines and one `[summary]`; every
asynchronous check is scored on one timer at the connect timeout plus two seconds. The voice hosts are
`voice.brodgar.io` (the real server) and `ws.invalid` (never resolves, RFC 2606). Refusal text is stripped
with `^@?.-%.lua:%d+:?%s*`. The `voice` addon is not a task: it is written in the addons repository once
this feature closes.

- [x] **143.1 — The engine in-tree, and `hafen.voice()` opens the link.** Vendors the 40 sources of
      `../brodgar-io-voice-client` at `8b1f708` into `src/io/brodgar/voice/`; `build.xml` gains
      `get-concentus` and `get-minimal-json` and loses `get-brodgar-voice`, the `Class-Path` naming the two
      jars. Adds `voice.connect` (`Permission`, `PermissionSet.grantsNetwork`, `Manifest`'s rule naming
      four keys), `Addon.voices`, `VoiceApi` mounted beside `WebSocketApi`, `LuaVoice` (the record, the
      per-link `BrodgarVoiceHost`, a listener that only enqueues), `LuaVoiceEvent` for `Close`/`Error`,
      `SharedMic`, the drain in `layerStep` (events and spatial vectors), the teardown `Step`, the two
      intent taps (`MapView.clickhit`, `Sessions.send`). Verbs: `:connection(url)`,
      `url/state/id/session/timeout/spatial/bitrate/on/connect/close`; keys `Open`, `Close`, `Error`; one
      link per origin, four per client. Retires `Voice`, `SpeakerIcon`, `VoiceTarget`, `OptWnd`'s panel
      and rows, `MapView`'s sites and `kb_ptt`, `FlowerMenu.addVoicePetal`, the javadocs naming them.
      Docs: `voice.md` (the link and the policy), the impact set of `spec.md` but the petal's rows, the
      `docs/client/` rows, `docverbs.py`; both checkers green.
      *Its suite* (`voice.connect`, hosts `voice.brodgar.io`, `ws.invalid`) asserts, dry: the section is a
      collection with `count()` `0`; `:connection("ws://…")` is refused naming `wss`, `"wss://a b"` naming
      the URL; a bare link reads `state() == "new"`, `url()`, and `tostring` names both; `timeout(0)` is
      refused naming `1..60000`, `spatial(1)` naming a boolean, `bitrate("x")` naming a number;
      `session()` is `hafen.session():current()`, `session(s)` reads back, `session(nil)` reads the current
      again; `on("PeerSpeaking", fn)` is refused naming the six keys; `connect()` reads `"connecting"`, a
      setter after it is refused naming `:connect()`, a second `:connection` to the same URL
      `connect()`ed is refused naming this addon; `keybindings():binding():get("brodgar/ptt"):exists()` is
      `false`. On the timer: `Open` came with `state() == "open"`, a number in `id()` and
      `hafen.client():stepping()` true; a link to `wss://ws.invalid` connected beside it read `count()`
      `2` and ended in `Error` naming the host; after `close()` `Close` came with a string in `ev:reason()`
      and `count()` read `0`. The shared microphone, the taps and the teardown are verified by reading.
      `[manual]`: enable the suite in the AddOns panel and read its consent line — expect: *use your
      microphone to talk on the voice servers it lists: voice.brodgar.io, ws.invalid*.

- [x] **143.2 — What you say and what you hear: the mic, the mix, and the peers.** Adds the desired
      settings on `LuaVoice` — `transmitting`, `vad`, `threshold`, `agc`, `muted`, `deafened`, `volume`
      (`0..4`) — read back in every state, applied at `Open` (`applyAll`) and written through live;
      `speaking()` (`isLocalSpeaking`); `info()` (the settings, `id`, `rtt` in ms, `sent`, `received`,
      `mixed`, `streams`); `voice:peer()` with `LuaPeer` (`gob/id/exists/audible/hears/speaking`,
      `muted([b])`, `volume([g])`, `info()`), addressed by Gob; the listener's diff into `PeerAdded`,
      `PeerRemoved`, `PeerChanged`; the `KEYS` array grown to six and `docverbs.py` reading it, `peer` in
      `RECEIVERS`. Docs: the rest of `voice.md`, a Peer row on `types/world.md`.
      *Its suite* asserts, dry, on a bare link: each setter reads back what it wrote, `volume(5)` is refused
      naming `0..4`, `threshold(-1)` naming its range, `muted("yes")` naming a boolean; `speaking()` is
      `false`; `peer():count()` is `0` and `peer():list()` empty; `peer():get(tree)` answers a Peer with
      `exists()` false, `gob() == tree`, `id() == tree:id()`; `peer():get(42)` is refused naming a Gob;
      `peer:muted(true)` and `peer:volume(2)` read back and `peer:info()` carries them. Then it connects
      with `vad(true)` and `transmitting(true)` and, on the timer: every setter still reads back after
      `Open`, `info()` carries the six numbers, `speaking()` read `true` at least once in the window —
      the suite prints *talk now* when `Open` comes — and `close()` ended it. The peer keys are verified
      by reading the diff: no suite has a second player.
      `[manual]`: none.

- [x] **143.3 — A petal of your own on the radial menu.** Adds `FlowerMenu.Petal.client` (a `Runnable`)
      and `FlowerMenu.addClientPetal(label, run)`, the `choose()` branch running it and cancelling;
      `s:flowermenu():add(label, fn)` in `FlowerMenuApi`, legal while that ring is being announced,
      handing back a Petal whose `native()` is `false` and whose `select()` picks it through `choose`;
      `petal:native()` on every petal, `native` in `petal:info()`. Docs: a `## Write (unprotected)` section
      on `flowermenu.md` and l.147, the Petal row of `types/ui.md`, `radial-menu.md`'s seam rows.
      *Its suite* (`gob.click`, `flowermenu.select`) subscribes `FlowerMenuAdded` once and right-clicks
      the nearest gob through `s:world():click(g, 3)`; in the handler it asserts: `add("Suite petal", fn)`
      answers a Petal with `native() == false`, `label() == "Suite petal"`, `index() == count()` and
      `wire() == count() - 1`, `list()` ending in it and `count()` one more than the payload's length; a
      server petal reads `native() == true`; `add("", fn)` is refused naming the label and `add("x", 1)`
      naming a function; then `petal:select()`. On the timer: `fn` ran with the petal and the session (`==`
      both), `FlowerMenuRemoved` carried `"Suite petal"`, and `add("late", fn)` after the ring closed was
      refused naming `FlowerMenuAdded`. The subscription is `off()`ed at the end.
      `[manual]`: none.

- [ ] **143.4 — A petal goes with its addon.** A ring an addon added to outlives that addon's teardown by
      the second the ring stays up, and the petal's `fn` still runs through `callLua` when it is picked —
      the one callback an addon holds that no `Step` pulls. Adds `FlowerMenuApi`'s record of the petals
      each addon added (a weak map ring → petals, written by `addPetal`) and a `Step` (`"client petals"`)
      in `AddonRegistry` that swaps each one's `client` for a run that does nothing: `Petal.client` stays
      non-null, so `choose` still cancels the server's menu rather than naming a number it never offered
      (the `uimsg "act"` gotcha), `petal:native()` still reads `false`, and `FlowerMenuRemoved` still
      carries the label. Docs: one sentence on `flowermenu.md`'s `## Write (unprotected)` (a petal of a
      disabled addon is painted and ends the ring, and runs nothing), the `Step` on `radial-menu.md`'s seam
      rows.
      *Its suite* (`gob.click`, `flowermenu.select`) repeats 143.3's ring round trip — `add`, `native() ==
      false`, `label()`, `select()`, `fn` ran with the petal, `FlowerMenuRemoved` carried the label — since
      the step changes nothing a live addon can see. The step is verified by reading, as 143.1's teardown
      was: a ring holds the mouse and the keyboard, so no player can disable an addon while one is up, and
      no verb tears one down.
      `[manual]`: none.
