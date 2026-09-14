# hafen.voice: Voice Connection Link

A **voice link** represents an active WebSocket connection to a proximity voice server. Links are created via `hafen.voice():connection(url)`, configured using builder setters, and opened via `:connect()`.

```lua
local voice_connection = hafen.voice():connection("wss://voice.brodgar.io")
  :timeout(5000)
  :spatial(true)
  :bitrate(32000)

voice_connection:on("Open", function(connection)
  hafen.log():write("Connected to voice server: " .. connection:url())
end)

voice_connection:on("Close", function(close_event)
  hafen.log():write("Voice connection closed: " .. close_event:reason())
end)

voice_connection:on("Error", function(error_event)
  hafen.log():write("Voice connection error: " .. error_event:error())
end)

voice_connection:connect()
```

---

## Connection Methods

| Method | Returns | Description |
|---|---|---|
| `voice:url()` | `string` | Target voice server URL. |
| `voice:state()` | `string` | `"new"`, `"connecting"`, `"open"`, `"closing"`, or `"closed"`. |
| `voice:id()` | `number \| nil` | Server-assigned session ID. `nil` before `Open` or after close. |
| `voice:session(session?)` | `Session \| VoiceConnection` | Bind connection to a specific character session (`nil` follows current screen). |
| `voice:timeout(milliseconds?)` | `number \| VoiceConnection` | Connection handshake timeout (`1` to `60000` ms, default `10000`). Pre-connect only. |
| `voice:spatial(enabled?)` | `boolean \| VoiceConnection` | 3D spatial panning and distance attenuation (default `true`). Pre-connect only. |
| `voice:bitrate(bps?)` | `number \| VoiceConnection` | Opus encoder bitrate (`8000` to `64000` bps, default `24000`). Pre-connect only. |
| `voice:on(event, handler)` | `Subscription` | Subscribes to connection events. See [Events](#events). |
| `voice:connect()` | `VoiceConnection` | Opens the connection. |
| `voice:close()` | `VoiceConnection` | Gracefully closes the connection. |
| `voice:peer()` | `PeerCollection` | Returns the [peer collection](peers.md) for nearby players. |

---

## Events

Voice connections emit the following events via `:on(event, handler)`:

| Event | Handler Arguments | Description |
|---|---|---|
| `"Open"` | `connection: VoiceConnection` | Connection handshake succeeded and audio pipeline is active. |
| `"Close"` | `close_event: CloseEvent` | Connection closed cleanly. `close_event:reason()` provides details. |
| `"Error"` | `error_event: ErrorEvent` | Connection failed or terminated unexpectedly. `error_event:error()` gives error message. |
| `"PeerAdded"` | `peer: Peer` | A nearby player entered voice proximity range. |
| `"PeerRemoved"` | `peer: Peer` | A player left voice proximity range. |
| `"PeerChanged"` | `peer: Peer` | A nearby player's state (`speaking`, `audible`, `hears`) changed. |

```lua
voice_connection:on("PeerAdded", function(peer_handle)
  hafen.log():write("Player entered voice range: " .. tostring(peer_handle:id()))
end)

voice_connection:on("PeerChanged", function(peer_handle)
  if peer_handle:speaking() then
    hafen.log():write("Player is speaking: " .. tostring(peer_handle:id()))
  end
end)
```

---

## The Voice Collection

`hafen.voice()` manages all active voice connections opened by this addon:

| Method | Returns | Description |
|---|---|---|
| `hafen.voice():connection(url)` | `VoiceConnection` | Creates a new uninitialized voice link. |
| `hafen.voice():list(filter?)` | `VoiceConnection[]` | Lists all active connections. |
| `hafen.voice():count(filter?)` | `number` | Count of active connections. |
| `hafen.voice():find(filter)` | `VoiceConnection \| nil` | Finds first connection matching URL or predicate. |

```lua
local active_links = hafen.voice():list()
for _, connection in ipairs(active_links) do
  hafen.log():write(string.format("Active link: %s (%s)", connection:url(), connection:state()))
end
```

---

## Closing a Connection

Calling `voice_connection:close()` shuts down the connection gracefully:
- Changes state to `"closing"`, notifies the voice server, and releases microphone capture.
- Emits the `"Close"` event once socket teardown completes.
- Disabling or reloading the addon automatically closes all open voice links.

---

## See Also

- [Voice Overview](README.md) — Permissions and network declaration requirements.
- [Audio & Microphone](audio.md) — Voice activation detection and volume settings.
- [Peers](peers.md) — Tracking and muting individual nearby speakers.
- [`hafen.session`](../session.md) — Character session management.
