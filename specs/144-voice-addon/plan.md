# 144 — voice-addon: plan

## Approach

**One addon, one file, three layers inside it — the link, what is drawn, what is driven.** `voice/main.lua`
holds a single `link` (the `Voice` handle, or `nil`), the options, the hotkeys, the overlays keyed by gob
id, the window, and the petal handler; `voice/manifest.json` declares `voice.connect` over
`voice.brodgar.io` and nothing else, and `voice/README.md` names the suggested keys.

**The link is a state machine driven by two inputs: the `voice on` option and whether a character is in
the world.** `connect()` builds `hafen.voice():connection("wss://voice.brodgar.io")`, applies `spatial` and
`bitrate` from the options, the mic and mix settings from the options and `mode`, subscribes the six keys
and `:connect()`s. It runs from `SessionEnteredWorld` when `link` is `nil` and the option is on, and from
the option's `Changed(true)` when some session is in the world (`hafen.session():find` over
`s:player():gob() ~= nil`). `Close` and `Error` set `link = nil` and, while the option is on, arm one
`hafen.timer():after(delay, connect)` with `delay` doubling from 2 s to 60 s and reset to 2 s by `Open`.
`Changed(false)` closes the link and cancels the timer. A connect-time option's `Changed` — `spatial`,
`bitrate` — closes the link and reconnects at once. Every other option's `Changed` writes through
(`link:volume(v / 100)`, `link:threshold(v)`, `link:agc(v)`) whether or not a link is up; `connect()`
copies them all in first, so a settings write is one function used from both sides.

**`mode` decides two settings.** *push to talk*: `vad(false)`, `transmitting(false)` and the `talk` key held
is the gate; *voice detection*: `vad(true)`, `transmitting(true)`; *open mic*: `vad(false)`,
`transmitting(true)`. In every mode a 50 ms timer, started by the `talk` hotkey's own fire and stopped when
`talk:down()` reads `false`, writes `transmitting(true)` while the key is down and puts the mode's own
value back when it comes up — so `talk` works as a "speak now" in detection mode too. `mute` and `deafen`
toggle `link:muted`/`link:deafened` and, with no link, a remembered flag `connect()` applies.

**Drawing is one painter per gob, reading live.** `PeerAdded` attaches `peer:gob():overlay():add("voice")`
with a `:draw(fn)` whose `fn` reads `peer:speaking()` and `peer:muted()` on every frame and paints nothing,
a speaker, or a struck speaker with `g:poly`/`g:line` at `sx, sy`; `PeerRemoved` removes it; `Open`
attaches the same to `voice:session():player():gob()` reading `link:speaking()`, refreshed on
`SessionSelected` since the link follows the screen, and removed at the ending. No `PeerChanged` handler
is needed for the icon — the draw reads the level — and the icon is vector so the addon ships no image.

**The window is the layer's**, a `hafen.ui():window()` titled `Voice`, `:remember("window")`, built once
at load and shown/hidden by `:voice`. Its body is a column rebuilt by a `refresh()` the six keys and a 1 s
timer call while it is shown: a status line from `link:info()` (`state`, `rtt`, `streams`), two checks bound
to nothing and wired by `Changed` to `muted`/`deafened`, and a row per `link:peer():list()` — the kin name
(`gob:kin()` when the roster has them, else `#<id>`), `hears`/`audible` as two glyphs, a check writing
`peer:muted`, a slider `0..400` writing `peer:volume(v / 100)`. Rows are rebuilt on `PeerAdded`/`Removed`
only; the timer updates text.

**The petal** is one `FlowerMenuAdded` handler: `s:flowermenu():gob()` is a player (`gob:player()`), not
`s:player():gob()`, and a link is up → `add(muted and "Unmute voice" or "Mute voice", fn)` where `fn`
toggles `link:peer():get(gob):muted(...)`. A `:get(gob)` mints a peer whether or not the server relates
you, so the petal works before they are near.

**The client repository's side**: `voice` on `etc/release-addons`; a `## voice` section and a table row
on `examples.md`, written to `DOCUMENTATION.md` §6's link rule (the one URL allowed); the addons
repository's README table row.

## Files to create or modify

- new: `../brodgar-io-client-addons/voice/main.lua`, `manifest.json`, `README.md` — 1, grown by 2, 3
- `../brodgar-io-client-addons/README.md` (the table row) — 3
- `etc/release-addons` — 3
- `docs/addons/examples.md` (the table row, the `## voice` section, the orientation paragraph's "three
  are worth knowing" — counted in prose, so reworded) — 3
- suites `addons/144-voice-addon.1..3/` — each task its own

No `docs/client/` page: the feature reads no upstream `haven`.

## Risks and gotchas

- **A second `:connect()` to `voice.brodgar.io` is refused naming the holder** — which is the suite's
  proof, and also why a reconnect must wait for `Close` to have run: `link:close()` then an immediate
  `connect()` in the same handler is refused naming `voice` itself. Reconnect from the `Close` handler.
- **`spatial` and `bitrate` are refused after `:connect()`**: an option `Changed` on either goes through
  close-and-reconnect, never a write on the live link.
- **`:remember(name)` before any session is in the world puts nothing back** and says so in the log; the
  window is in the layer, filed under the account, so build it at load and it comes back at the first
  world entry.
- **Hotkeys are declared at load, never from an event** — a key declared later is free until then.
- **`peer:gob()` is `nil` on the login screen** and a Gob whose `:exists()` is `false` for a player not yet
  drawn: `gob:overlay():add` raises on a gone gob, so guard the attach with `gob:exists()` and let
  `PeerAdded` of a re-entering peer re-attach.
- **The options page is rebuilt on every visit**: bind controls in `panel(fn)`, keep no handles.
- **`volume` is a number option and whole**: `0..400` stored, `/ 100` written (the page says to scale).
- **`gob:player()` is `nil` while the gob is loading**: treat `nil` as not a player; no petal.
- **A ring on a tree must not take the petal** — the suite reads that; the gob test is what keeps it out.

## Discarded alternatives

- **A `server` text option** — the manifest's `network` block gates the host and an option cannot widen it,
  so an editable server would be one the user could type and never reach; a private server is a manifest
  edit and its own addon.
- **Connecting at `Load`** — legal on the login screen, but it opens the microphone with nobody to talk to
  and holds the server slot through the login; the first world entry is the moment voice means something.
- **A `PeerChanged` handler driving the icon** — the painter runs every frame anyway and `peer:speaking()`
  is a lock-free read, so a handler would keep a second copy of a level the draw can read.
- **A speaker image asset** — a PNG the repository has to carry and version; the icon is nine points of
  polygon and two lines.
- **The window in a session's tree** so a suite could `:match` it — it would be rebuilt on every session
  switch while the link follows the screen; the layer is where a client-wide surface stands, and its
  presentation is `[manual]` as every window's is.
- **Persisting peer mutes** — a peer is a gob id the server assigns per login and the client is sent no
  name; the kin name covers only the roster.
- **`talk` only in push-to-talk mode** — a held key that speaks in every mode costs one branch and lets a
  detection-mode player push through a quiet sentence.
