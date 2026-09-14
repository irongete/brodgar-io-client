# hafen.voice: Audio & Microphone Control

This section covers microphone input gating, voice activity detection (VAD), volume levels, and audio telemetry on active voice connections.

```lua
local voice_connection = hafen.voice():connection("wss://voice.brodgar.io")
  :vad(false) -- Disable VAD for explicit push-to-talk
  :connect()

-- Implement Push-to-Talk using keybindings
local keybindings = hafen.client():options():keybindings()
keybindings:on("push_to_talk", function() end)

local talk_binding = keybindings:binding():get("push_to_talk")
hafen.timer():every(0.05, function()
  voice_connection:transmitting(talk_binding:down())
end)
```

---

## Audio Settings

All audio setting methods read the current value when called with zero arguments, or update the value and return the connection handle when called with one argument.

| Method | Type | Default | Description |
|---|---|---|---|
| `voice:transmitting(enabled?)` | `boolean` | `false` | Microphone transmission gate. Set to `true` while speaking or holding a PTT key. |
| `voice:vad(enabled?)` | `boolean` | `true` | Voice Activity Detection. When `true`, audio frames below `threshold` are not sent. |
| `voice:threshold(rms?)` | `number` | `350` | RMS audio sensitivity threshold (`0` to `32767`). Lower values increase sensitivity. |
| `voice:agc(enabled?)` | `boolean` | `true` | Automatic Gain Control for microphone level normalization. |
| `voice:muted(enabled?)` | `boolean` | `false` | Hard mute. When `true`, no microphone data is transmitted. |
| `voice:deafened(enabled?)` | `boolean` | `false` | When `true`, incoming peer audio playback is silenced. |
| `voice:volume(multiplier?)` | `number` | `1.0` | Global playback gain multiplier (`0.0` to `4.0`). Multiplies with per-peer volumes. |

### Transmission Logic

Audio is transmitted over the network only when the following condition evaluates to `true`:
```lua
is_transmitting = voice:transmitting() and not voice:muted() and (not voice:vad() or voice:speaking())
```

---

## Live Audio State & Telemetry

### `voice:speaking()`

Returns `true` if the local user's microphone is actively producing and transmitting voice audio right now (includes a brief hangover period to avoid choppy transitions between words). Returns `false` when disconnected.

```lua
if voice_connection:speaking() then
  -- Highlight local player's voice indicator on HUD
end
```

### `voice:info()`

Returns a telemetry snapshot of connection and playback performance:

```lua
local telemetry = voice_connection:info()
hafen.log():write(string.format("Voice RTT: %d ms | Sent: %d frames | Streams: %d",
  telemetry.rtt or 0, telemetry.sent or 0, telemetry.streams or 0))
```

| Field | Type | Description |
|---|---|---|
| `url` | `string` | Voice server endpoint. |
| `state` | `string` | Connection status (`"new"`, `"connecting"`, `"open"`, `"closing"`, `"closed"`). |
| `speaking` | `boolean` | Local microphone transmission state. |
| `id` | `number \| nil` | Server-assigned session identifier. |
| `rtt` | `number \| nil` | Round-trip latency to the audio relay server in milliseconds. |
| `sent` | `number` | Total 20 ms voice frames transmitted. |
| `received` | `number` | Total voice packets received from the server. |
| `mixed` | `number` | Total 20 ms audio playback frames processed. |
| `streams` | `number` | Number of active peer audio decoders currently running. |

---

## Complete Push-to-Talk & Mute Example

```lua
local voice_connection = hafen.voice():connection("wss://voice.brodgar.io"):connect()
local keybindings = hafen.client():options():keybindings()

-- Push-to-talk toggle
keybindings:on("ptt_voice", function() end)
local ptt_binding = keybindings:binding():get("ptt_voice")

-- Toggle mute
keybindings:on("toggle_mute", function()
  local is_muted = not voice_connection:muted()
  voice_connection:muted(is_muted)
  hafen.log():write("Microphone " .. (is_muted and "muted" or "unmuted"))
end)

hafen.timer():every(0.05, function()
  if ptt_binding:assigned() then
    voice_connection:transmitting(ptt_binding:down())
  end
end)
```

---

## See Also

- [Voice Link](link.md) — Connection lifecycle and state management.
- [Peers](peers.md) — Per-player audio volume and mute settings.
- [`hafen.client():options():keybindings()`](../client/keybindings.md) — Keyboard binding configuration.
