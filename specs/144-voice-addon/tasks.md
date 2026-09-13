# 144 — voice-addon: tasks

Suites at `addons/144-voice-addon.<X>/`, one console command `:t144`, verdict lines and one `[summary]`.
The addon is `../brodgar-io-client-addons/voice/` — `main.lua`, `manifest.json` (`id` `voice`, name `Voice`,
`voice.connect` over `voice.brodgar.io`), `README.md` — and **its files are the maintainer's to commit, in
that repository**; `ant bin` copies it beside the suite so a run sees both. Every suite proves the link is
held the one way another addon can see it: its own `hafen.voice():connection("wss://voice.brodgar.io")`
`:connect()` is refused naming `voice` (`voice.connect`, host `voice.brodgar.io`). Refusal text is
stripped with `^@?.-%.lua:%d+:?%s*`. The addon logs one line per link edge — `[voice] open, session N`,
`[voice] closed: <reason>`, `[voice] error: <text>`, `[voice] reconnecting in N s` — which is what the
`[manual]` lines read.

- [x] **144.1 — The link, the options page and the keys.** Writes the addon whole but for what is
      drawn: the manifest; the link state machine (`connect()` from the first `SessionEnteredWorld` and
      from `voice on`'s `Changed(true)` with a character in the world, `Close`/`Error` reconnecting on a
      timer that doubles 2 s → 60 s and resets at `Open`, `Changed(false)` closing and cancelling); the
      options `voice on`, `mode` (*push to talk*, *voice detection*, *open mic*), `threshold` (0..2000),
      `automatic gain`, `spatial`, `volume` (0..400), `bitrate` (8000..64000), each bound on the page, the
      live ones written through and the two connect-time ones reconnecting; the hotkeys `talk` (a 50 ms
      poll on `down()` writing `transmitting`, the mode's value put back on release), `mute`, `deafen`;
      the README with suggested keys.
      *Its suite* asserts: `keys:binding():get("addon/voice/talk")`, `…/mute`, `…/deafen` read `exists()`
      true; its own link's `:connect()` is refused naming `voice`; a link it builds to
      `wss://ws.invalid` is not refused on that ground (`state()` reads `"connecting"`), and it closes it.
      `[manual]`: open Options ▸ AddOns ▸ Voice — expect: seven controls, `voice on` ticked. Untick
      `voice on`, wait, tick it — expect: `[voice] closed: ` then `[voice] open, session N` in the log.

- [x] **144.2 — Who is talking, over their heads.** Adds the painters: at `Open`, `"voice"` on
      `link:session():player():gob():overlay()` drawing a speaker while `link:speaking()`, re-attached on
      `SessionSelected` and removed at the ending; at `PeerAdded`, the same on `peer:gob()` reading
      `peer:speaking()` and `peer:muted()` — silent: nothing, speaking: the speaker, muted: the speaker
      struck through — removed at `PeerRemoved`; a `gob:exists()` guard on every attach. The icon is
      `g:poly` and `g:line` at `sx, sy`, offset above the name label.
      *Its suite* repeats the hold assertion and the three bindings; nothing else is readable across
      addons — the overlays are `voice`'s own and `gob:overlay():list()` lists no other addon's.
      `[manual]`: in *voice detection*, say a sentence — expect: a speaker over your character while you
      talk, gone after. In *push to talk*, hold `talk` and talk — expect: the same, only while held. With a
      second client near: they talk — expect: a speaker over their character. The struck speaker is read
      in 144.3, where a mute can first be set.
      <!-- extra context: docs/addons/api/event/bus/lifecycle.md -->

- [ ] **144.3 — The window, the petal, and the release.** Adds the `Voice` window in the layer,
      `:remember("window")`, toggled by `:voice`: a status line from `link:info()` (`state`, `rtt`,
      `streams`), a `muted` and a `deafened` check writing the link, and a row per `link:peer():list()` —
      the kin name or `#<id>`, `hears`/`audible` as two glyphs, a check on `peer:muted`, a slider `0..400`
      on `peer:volume(v / 100)` — rows rebuilt on `PeerAdded`/`PeerRemoved`, text on a 1 s timer while
      shown; the `FlowerMenuAdded` handler adding `Mute voice`/`Unmute voice` to a ring whose
      `s:flowermenu():gob()` is a player other than `s:player():gob()`, toggling `link:peer():get(gob)`'s
      mute. Ships it: `voice` on `etc/release-addons`, the `## voice` section and table row on
      `examples.md` (its "three are worth knowing" reworded — no count in prose), the row on the addons
      repository's README.
      *Its suite* (`gob.click`, `flowermenu.select`, `flowermenu.cancel`) repeats the hold assertion, then
      right-clicks the nearest tree and, inside `FlowerMenuAdded`, asserts every petal of `menu:list()`
      reads `native() == true` and none is labelled `Mute voice` or `Unmute voice`, then `:cancel()`s the
      ring; on a 4 s timer, `FlowerMenuRemoved` carried `nil`.
      `[manual]`: type `:voice` — expect: the window, its status line `open`, an rtt. Drag it, `:voice`
      twice — expect: it comes back where it was left. With a second client near: their row — expect: the
      name or `#id`, two glyphs, a mute box, a slider; tick the box — expect: silence from them and the
      struck speaker. Right-click their character — expect: `Mute voice` on the ring, and `Unmute voice` on
      the next.
