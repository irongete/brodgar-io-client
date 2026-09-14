# Chat Events

Events triggered when chat messages, area broadcasts, village communications, or private messages are received.

## Events Reference

### `ChatMessage`
* **Triggered**: When a new chat line is received by any active session.
* **Arguments**: `message_info` (`table`).

| Field in `message_info` | Type | Description |
|---|---|---|
| `channel` | `string` | Channel name (e.g. `"Area"`, `"Party"`, `"Village"`, or account name for PMs). |
| `sender` | `string` | Name of the character or system sending the message. |
| `text` | `string` | Content of the message. |
| `session` | `Session` | The character session that received the message. |

```lua
hafen.event():on("ChatMessage", function(message_info)
  local sender = message_info.sender or "System"
  local text = message_info.text or ""

  if message_info.channel == "Area" then
    hafen.log():write(string.format("[Area] %s says: %s", sender, text))
  end
end)
```
