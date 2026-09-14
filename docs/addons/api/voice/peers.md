# hafen.voice: Voice Peers

A **Peer** represents a player connected to the same voice server within proximity range. The `voice:peer()` collection manages peer handles, tracks speaker states, and provides per-player volume and mute adjustments.

```lua
local voice_connection = hafen.voice():connection("wss://voice.brodgar.io"):connect()

voice_connection:on("PeerChanged", function(peer_handle)
  if peer_handle:speaking() then
    hafen.log():write(string.format("Player %d is speaking", peer_handle:id()))
  end
end)
```

---

## The Peer Collection

Accessed via `voice_connection:peer()`:

| Method | Returns | Description |
|---|---|---|
| `peer_collection:list(filter?)` | `Peer[]` | Lists all active peers currently in proximity. |
| `peer_collection:count(filter?)` | `number` | Count of active peers. |
| `peer_collection:find(predicate)` | `Peer \| nil` | Finds first peer matching predicate function. |
| `peer_collection:get(gob)` | `Peer` | Retrieves a `Peer` handle for a [Gob](../gob.md). Never returns `nil`. |

```lua
local peer_collection = voice_connection:peer()

-- Query active peers
for _, peer_handle in ipairs(peer_collection:list()) do
  hafen.log():write(string.format("Peer Gob ID: %d (Audible: %s)",
    peer_handle:id(), tostring(peer_handle:audible())))
end
```

---

## The Peer Handle

| Method | Returns | Description |
|---|---|---|
| `peer:id()` | `number` | The game object (Gob) ID of the peer player. |
| `peer:gob()` | `Gob \| nil` | The player's [`Gob`](../gob.md) handle in the active session world. |
| `peer:exists()` | `boolean` | `true` if the server is actively relating this player in voice proximity. |
| `peer:audible()` | `boolean` | `true` if this player's audio stream is received by you. |
| `peer:hears()` | `boolean` | `true` if your audio stream is delivered to this player. |
| `peer:speaking()` | `boolean` | `true` if this player is actively speaking right now. |
| `peer:muted(enabled?)` | `boolean \| Peer` | Local mute for this specific player. Persists across range leaves. |
| `peer:volume(gain?)` | `number \| Peer` | Local volume multiplier for this player (`0.0` to `4.0`, default `1.0`). |
| `peer:info()` | `table` | Snapshot metadata: `{id, audible, hears, speaking, muted, volume}`. |

---

## Per-Player Mute and Volume Control

Volume adjustments and mutes are tracked locally per Gob ID and persist if the player leaves and re-enters proximity:

```lua
local function mute_player(player_gob)
  local peer_handle = voice_connection:peer():get(player_gob)
  peer_handle:muted(true)
  hafen.log():write(string.format("Muted player Gob %d", peer_handle:id()))
end

local function set_player_volume(player_gob, volume_multiplier)
  local peer_handle = voice_connection:peer():get(player_gob)
  peer_handle:volume(volume_multiplier)
end
```

---

## Tracking Speakers on HUD / Overlays

Use `PeerAdded`, `PeerRemoved`, and `PeerChanged` on the parent connection to render speaker indicators over player avatars:

```lua
local active_speakers = {}

voice_connection:on("PeerChanged", function(peer_handle)
  local player_gob = peer_handle:gob()
  if player_gob then
    if peer_handle:speaking() then
      active_speakers[player_gob] = true
    else
      active_speakers[player_gob] = nil
    end
  end
end)

voice_connection:on("PeerRemoved", function(peer_handle)
  local player_gob = peer_handle:gob()
  if player_gob then
    active_speakers[player_gob] = nil
  end
end)
```

---

## See Also

- [Voice Overview](README.md) — Permission keys and network declarations.
- [Voice Link](link.md) — Connection events and state management.
- [Audio & Microphone](audio.md) — Microphone input and master output volume.
- [`Gob:overlay()`](../overlay.md) — Attaching 2D badges and UI over game objects.
