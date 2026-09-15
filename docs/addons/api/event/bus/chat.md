# Chat Events

Events triggered when chat messages, area broadcasts, village communications, or channels change.

## Events Reference

### `MessageAdded`
* **Triggered**: When a new chat line is received by any active session.
* **Arguments**: `message` (`Message`).

```lua
hafen.event():on("MessageAdded", function(message)
  local speaker_kin = message:speaker()
  local sender = speaker_kin and speaker_kin:name() or "System"
  local text = message:text() or ""
  local channel = message:channel() and message:channel():name() or "Area"

  if channel == "Area" then
    hafen.log():write(string.format("[Area] %s says: %s", sender, text))
  end
end)
```

---

### `ChannelAdded`
* **Triggered**: When a new chat channel becomes available (e.g. party or village channel created).
* **Arguments**: `channel` (`Channel`).

---

### `ChannelRemoved`
* **Triggered**: When a chat tab or private message channel is closed.
* **Arguments**: `channel` (`Channel`).

---

### `ChannelSelected`
* **Triggered**: When the player switches active chat channel tabs.
* **Arguments**: `channel` (`Channel`).
