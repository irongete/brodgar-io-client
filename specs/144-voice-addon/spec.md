# 144 — voice-addon: the `voice` addon, in the addons repository

## What & why

`hafen.voice()` ships and the client draws no voice UI and binds no key: proximity voice is an addon's to
build, and no addon builds it. This feature writes **`voice`**, the maintainer's addon in
`../brodgar-io-client-addons/voice/`, the first consumer of the whole voice surface — the link, the mic and
the mix, the peers, the petal — and lists it among the addons a release ships. The client repository
gains no `hafen.*` spelling: its own writes are `etc/release-addons`, the `examples.md` entry, and the
suites. **The addon's files are committed by the maintainer, in that repository**; `/end` commits what
lands here.

## The surface

**One link, to the public server.** `voice` connects to `wss://voice.brodgar.io` when the first character
enters the world and holds the link for the client's life, following the screen; a `Close` or an `Error`
reconnects after a pause that doubles up to a minute, and a `voice on` option turned off closes it. The
manifest declares `voice.connect` over `voice.brodgar.io`, and nothing else.

**Options ▸ AddOns ▸ Voice** holds every setting a player has: `voice on` · `mode` — *push to talk*, *voice
detection*, *open mic* · `threshold` · `automatic gain` · `spatial` · `volume` (0..400, hundredths) · `bitrate`
(8000..64000). Each is a client option bound to a control on the page; a live setting writes through to
the link the moment it moves, and the two connect-time ones (`spatial`, `bitrate`) reconnect it.

**Three hotkeys**, unbound until the user assigns them: `talk` (held: the microphone goes out, in every
mode), `mute` and `deafen` (toggles). The README suggests keys.

**Who is talking** is drawn over heads with `gob:overlay()`: a speaker icon over every peer while their
voice arrives, a struck-through one over a peer the player muted, and the same icon over the player's own
character while `voice:speaking()`. Vector-drawn, no asset.

**The window** — `:voice` opens and closes it, its place remembered for the account — shows the link's
state and the counters, a mute and a deafen toggle, and **a row per peer**: their character's name, whether
you hear them and they you, a mute box and a volume slider.

**The petal**: a ring opened on another player's character takes `Mute voice` / `Unmute voice`, toggling
that peer's mute.

## Acceptance criteria

1. **The link is held by `voice`.** With the addon enabled and a character in the world, a second link to
   `wss://voice.brodgar.io` from any addon is refused naming `voice`; with `voice on` off, that `:connect()`
   is not refused on that ground.
2. **The hotkeys exist**: `addon/voice/talk`, `addon/voice/mute`, `addon/voice/deafen` read `exists()`
   true in the binding registry, unbound by default.
3. **The options page** stands under Options ▸ AddOns ▸ Voice with a control per setting; moving a control
   moves the link (`[manual]`); `mode` decides `vad`/`transmitting`, and `talk` held transmits in every mode.
4. **Speaking is drawn**: an icon over the player's own head while `voice:speaking()`, over a peer's while
   theirs; a muted peer's is struck through (`[manual]`, two clients).
5. **The window** opens and closes on `:voice`, comes back where it was left, and lists each peer with a
   working mute box and volume slider (`[manual]`).
6. **The petal** appears on a ring opened on another player and toggles that peer's mute, and appears on no
   other ring — a suite right-clicking a tree reads a ring without it.
7. **Shipped**: `etc/release-addons` names `voice`; `examples.md` has its section; the addons repository's
   README table has its row.

## Out of scope

Another server than the public one — a private server is the manifest's `network` block and a different
addon, or this one edited by hand. Persisting a peer's mute across logins: a peer is a gob id, which the
server assigns per login, and the client is never sent a name that would key it. A reconnect on a session
switch: the link follows the screen by itself. The server and the relay are their own repository.

## Docs impact

Written: a `## voice` section on `docs/addons/examples.md` and its row in the table; `etc/release-addons`;
`../brodgar-io-client-addons/README.md`'s table and `voice/README.md`. Derived:

```text
grep -rn -i "voice addon\|release-addons" docs/ | grep -v api/voice/
  addons/examples.md:6 (the sentence that names the release list — stands)
  addons/api/voice/README.md:9 ("the voice addon your players use" — stands; it now exists)
```

No `hafen.*` page changes: the feature adds no spelling.

## Context files

- `docs/addons/api/voice/`: `README.md`, `link.md`, `audio.md` — 1, 2, 3; `peers.md` — 2, 3
- `docs/addons/api/client/`: `addon.md`, `keybindings.md` — 1
- `docs/addons/api/session.md`, `docs/addons/api/event/bus/lifecycle.md`, `docs/addons/api/timer.md` — 1
- `docs/addons/manifest.md` — 1
- `docs/addons/api/overlay.md`, `docs/addons/api/ui/drawing.md`, `docs/addons/api/gob.md` — 2
- `docs/addons/api/ui/`: `custom.md`, `column.md`, `native.md`, `controls/README.md` — 3
- `docs/addons/api/console.md`, `docs/addons/api/flowermenu.md`, `docs/addons/api/world.md` — 3
- `docs/addons/examples.md`, `etc/release-addons`, `../brodgar-io-client-addons/README.md`,
  `DOCUMENTATION.md` — 3
- `../brodgar-io-client-addons/voice/` — 2, 3 (what the earlier task of this feature wrote)
