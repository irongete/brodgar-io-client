# hafen.voice: Proximity Voice Chat

`hafen.voice()` provides proximity-based voice chat capabilities. It allows addons to establish real-time audio links with voice servers, handle microphone input, play 3D spatialized audio streams, and track nearby speakers.

```lua
local voice_connection = hafen.voice():connection("wss://voice.brodgar.io")

voice_connection:on("Open", function(connection)
  hafen.log():write("Voice connection established, session ID: " .. tostring(connection:id()))
end)

voice_connection:on("Close", function(close_event)
  hafen.log():write("Voice disconnected: " .. close_event:reason())
end)

voice_connection:on("PeerAdded", function(peer)
  hafen.log():write("Player within voice proximity: " .. tostring(peer:id()))
end)

voice_connection:vad(true):transmitting(true):connect()
```

---

## Subsystem Documentation

| Guide | Description |
|---|---|
| [Voice Link](link.md) | Establishing, configuring, and closing voice connections. |
| [Audio & Microphone](audio.md) | Microphone gating, VAD, volume mixing, and audio telemetry. |
| [Peers](peers.md) | Tracking and adjusting individual nearby speakers. |

---

## Permission & Network Requirements

Voice links require the [`voice.connect`](../../guides/permissions.md) permission and an explicit host entry in `manifest.json`:

```json
{
  "id": "proximity_chat",
  "api_version": "1.0",
  "files": ["main.lua"],
  "permissions": ["voice.connect"],
  "network": {
    "hosts": ["voice.brodgar.io"]
  }
}
```

- **User Consent**: The client prompts the user when installing the addon: *"use your microphone to talk on the voice servers it lists: voice.brodgar.io"*.
- **Permission Check**: Permissions and host allowlists are enforced synchronously during `:connect()`.
- **WSS Only**: Only secure WebSocket (`wss://`) endpoints are supported. Self-signed or unverified certificates are rejected.
- **Localhost / Private IP Restrictions**: Connections to loopback (`127.0.0.1`), link-local, or private RFC1918 subnets are blocked.

---

## Proximity & Privacy Model

The voice subsystem transmits **relative** positional data to the voice server:
- Character relative position vectors (in tiles) to other player objects in view.
- Recent movement orders (clicks and directions) to allow server-side dead reckoning.
- **No absolute coordinates**: World coordinates, grid IDs, and character account names are never transmitted to the voice server.
- Audio packets are encrypted and relayed via the audio endpoints designated by the voice server.

---

## Limits & Constraints

- **Single Active Microphone**: The client shares one physical capture stream across all active voice links.
- **Connection Caps**: Maximum 4 concurrent voice links across all active addons.
- **Handshake Timeout**: 10 seconds by default (configurable between 1 ms and 60,000 ms via `:timeout()`).

---

## See Also

- [Voice Link](link.md) — Connection lifecycle and state management.
- [Audio & Mic](audio.md) — Voice activation detection and audio streams.
- [Peers](peers.md) — Interacting with individual nearby players.
- [`hafen.websocket`](../websocket.md) — Raw WebSocket communication.
