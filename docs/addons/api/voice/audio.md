# hafen.voice: The Mic and the Mix

What a [link](link.md) sends and plays. Whether your microphone goes out and how it is gated. Whether incoming voices are heard and how loud. Whether you are speaking now. The counters. Every setting is written in any state, reads back what you wrote, and takes effect the moment the link is open.

```lua
local voice = hafen.voice():connection("wss://voice.brodgar.io")
  :vad(false)                                   -- push-to-talk: the key is the whole gate
  :connect()
local keybindings = hafen.client():options():keybindings()
keybindings:on("talk", function() end)          -- declared, so the user has a row to bind
keybindings:on("mute", function() voice:muted(not voice:muted()) end)
local talk_binding = keybindings:binding():get("talk")
hafen.timer():every(0.05, function() voice:transmitting(talk_binding:down()) end)
```

---

## The settings

Each is one verb: bare reads, one argument writes and hands the link back. Unprotected past the key that opened the link: a setting changes what the user's own microphone sends and speakers play.

| Method | Value | Default | Permission | Description |
|---|---|---|---|---|
| `voice:transmitting()` / `voice:transmitting(flag)` | `boolean` | `false` | Unprotected | The gate: while `true` the microphone goes out, subject to `vad` and `muted`. Bind it to a held key for push-to-talk, or set it once for an open mic. |
| `voice:vad()` / `voice:vad(flag)` | `boolean` | `true` | Unprotected | Voice detection: while `true` only frames louder than `threshold` go out, so the link is silent between sentences. `false` sends everything while `transmitting`. |
| `voice:threshold()` / `voice:threshold(rms)` | `number`, `0..32767` | `350` | Unprotected | How loud a frame must be to count as a voice, an RMS on the 16-bit sample scale. Lower is more sensitive. Read by `vad` only. |
| `voice:agc()` / `voice:agc(flag)` | `boolean` | `true` | Unprotected | Automatic gain: your loudness is evened out before it goes. |
| `voice:muted()` / `voice:muted(flag)` | `boolean` | `false` | Unprotected | The hard mute: while `true` nothing goes out, whatever `transmitting` says. Two switches for two gestures: the key you hold, the mute you toggle. |
| `voice:deafened()` / `voice:deafened(flag)` | `boolean` | `false` | Unprotected | While `true` every incoming voice is silenced. Yours still goes out. |
| `voice:volume()` / `voice:volume(gain)` | `number`, `0..4` | `1` | Unprotected | The gain every incoming voice is played at, `1` as sent. A [peer's own](peers.md#the-peer-object) multiplies with it. |

| Rule | Detail |
|---|---|
| Refused, never clamped | A write outside its row's range. An explicit `nil` too, since the read is the same name with no argument. The range check is the same in every state. |
| What you set, not what the audio has applied | A `"new"` or `"connecting"` link remembers each write and applies them all when it opens. An `"open"` one applies at once. An ended one reads back what it was set to, which a reconnect copies over. |

> **What goes out is `transmitting and not muted and (not vad or loud enough)`.** Nothing leaves the client while any of the three says no. `speaking()` is that expression, measured.

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `voice:speaking()` | `boolean` | Unprotected | Whether your voice is going out right now, past the gate, the mute and the detector. A short hangover keeps a pause between words from flickering. `false` in every state but `"open"`. |
| `voice:info()` | `table` | Unprotected | The settings under their own names, plus the fields below, as one snapshot. |

| Field | Type | Detail |
|---|---|---|
| `url` | `string` | `voice:url()`. |
| `state` | `string` | `voice:state()`. |
| `speaking` | `boolean` | `voice:speaking()`. |
| `id` | `number` | `voice:id()`. Absent before `Open` and once ended. |
| `rtt` | `number` | The last round trip to the audio relay, in milliseconds. Absent until the relay has answered a ping, moments after `Open`. |
| `sent` | `number` | Voice frames sent since the link opened, each 20 ms. `0` while the microphone is not open. |
| `received` | `number` | Voice packets the relay has sent this link, counted before any mute or deafen. |
| `mixed` | `number` | 20 ms frames the mix has played, speaking or silent: the playback clock. Climbs whether or not anyone talks, which tells a running audio thread from a dead one. |
| `streams` | `number` | How many players' voices the mix holds a decoder for right now. |

The counters count from `Open` and stop at the ending.

---

## See Also

- [The link](link.md) — building, opening and ending one.
- [Peers](peers.md) — who you hear and who hears you, and the per-player mute and volume.
- [`hafen.voice`](README.md) — the hub: the declaration, what the server is told, and the limits.
- [Keybindings](../client/keybindings.md) — the key a push-to-talk holds, and `down()` for the level of it.
