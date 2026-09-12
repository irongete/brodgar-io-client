# hafen.voice: the mic and the mix

What a [link](link.md) sends and what it plays: whether your microphone goes out and how it is gated on the
way, whether the voices coming in are heard and how loud, whether you are speaking right now, and the
counters. Every setting is **yours and the link's**: you write it in any state, it reads back what you wrote,
and it takes effect the moment the link is open — the same verbs before `:connect()` and after `Open`.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
  :vad(false)                                   -- push-to-talk: the key is the whole gate
  :connect()
local keys = hafen.client():options():keybindings()
keys:on("talk", function() end)                 -- declared, so the user has a row to bind
keys:on("mute", function() voice:muted(not voice:muted()) end)
local talk = keys:binding():get("talk")
hafen.timer():every(0.05, function() voice:transmitting(talk:down()) end)
```

## The settings

Each is one verb — the bare name reads, one argument writes and hands the link back — and each is
**unprotected past the key that opened the link**: a setting changes what the user's own microphone sends
and what their speakers play, nothing the server judges. A write is refused, never clamped, outside the
range its row states; an explicit `nil` is refused too, since the read is the same name with no argument.

| Method | Value | Default | What it does |
|---|---|---|---|
| `voice:transmitting()` / `voice:transmitting(on)` | boolean | `false` | **the gate**: while `true` the microphone goes out, subject to `vad` and `muted` below. Bind it to a key held down for push-to-talk, or set it once for an open mic |
| `voice:vad()` / `voice:vad(on)` | boolean | `true` | **voice detection**: while `true`, only frames louder than `threshold` go out and the rest are held back, so the link is silent between your sentences. With it `false`, everything goes while `transmitting` is |
| `voice:threshold()` / `voice:threshold(rms)` | number, `0..32767` | `350` | how loud a frame has to be to count as a voice, as an RMS on the 16-bit sample scale; lower is more sensitive. Read by `vad` only |
| `voice:agc()` / `voice:agc(on)` | boolean | `true` | **automatic gain**: whether your loudness is evened out before it goes, so a quiet voice and a loud one arrive alike |
| `voice:muted()` / `voice:muted(on)` | boolean | `false` | **the hard mute**: while `true` nothing goes out, whatever `transmitting` says. Two switches because they are two gestures — the key you hold, and the mute you toggle |
| `voice:deafened()` / `voice:deafened(on)` | boolean | `false` | while `true` every voice coming in is silenced; your own still goes out |
| `voice:volume()` / `voice:volume(g)` | number, `0..4` | `1` | the gain every voice coming in is played at, `1` being as sent; a [peer's own](peers.md#the-peer-object) multiplies with it |

**The settings are what you set, not what the engine has.** A link that is `"new"` or `"connecting"`
remembers each write and applies them all the instant it opens; one that is `"open"` applies a write at
once; one that has ended still reads back what it was set to, which is what a reconnect copies over. The
range check is the same in every state.

> **What goes out is `transmitting and not muted and (not vad or loud enough)`.** Nothing leaves the
> client while any of the three says no, and `speaking()` is exactly that expression, measured.

## Read

| Method | Returns | Description |
|---|---|---|
| `voice:speaking()` | boolean | whether your voice is going out **right now** — past the gate, the mute and the detector, with a short hangover so a pause between words does not flicker. `false` in every state but `"open"` |
| `voice:info()` | table | the settings and the counters below, as one snapshot |

`voice:info()` is the one **snapshot** of a link, for logging and for a status line; every other read is the
live verb. It carries the settings above under their own names, plus:

| Field | Type | Notes |
|---|---|---|
| `url` | string | `voice:url()` |
| `state` | string | `voice:state()` |
| `speaking` | bool | `voice:speaking()` |
| `id` | number | `voice:id()`; optional — absent before `Open` and once the link has ended |
| `rtt` | number | the last round trip to the audio relay, in milliseconds; optional — absent until the relay has answered a ping, which is moments after `Open` |
| `sent` | number | voice frames sent since the link opened, each 20 ms; `0` with no engine |
| `received` | number | voice packets the relay has sent this link, counted before any mute or deafen |
| `mixed` | number | 20 ms frames the mix has played, speaking or silent — the playback clock |
| `streams` | number | how many players' voices the mix is holding a decoder for right now |

The counters count from `Open` and stop at the ending; `mixed` climbs whether or not anyone is talking,
which is how a status line tells a link whose audio is running from one whose audio thread died.

## See also

- [the link](link.md) — building, opening and ending one
- [peers](peers.md) — who you hear and who hears you, and the mute and volume that are per player
- [`hafen.voice`](README.md) — the hub: the declaration, what the server is told, and the limits
- [keybindings](../client/keybindings.md) — the key a push-to-talk holds, and `down()` for the level of it
