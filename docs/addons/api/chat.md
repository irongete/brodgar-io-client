# session:chat: Chat Channels

Inspect chat tabs, read messages, monitor urgency indicators, and post messages into chat channels.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local chat_subsystem = session:chat()

-- List active chat channels
for _, chat_channel in ipairs(chat_subsystem:list()) do
  local channel_name = chat_channel:name() or "Main"
  local unread_urgency = chat_channel:urgency() or 0
  hafen.log():write(string.format("Channel: %s (Unread urgency: %d)", channel_name, unread_urgency))
end
```

---

## Methods on `session:chat()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Channel[]` | Array of all open chat channels in tab order. |
| `:selected()` | None | `Channel \| nil` | The channel tab currently selected on screen. |
| `:count()` | None | `number` | Total number of open channel tabs. |
| `:find(name)` | `string` | `Channel \| nil` | Finds a channel by exact tab caption name. |

---

## Methods on `Channel`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Caption displayed on the channel tab. |
| `:kind()` | `string` | Channel category (`"area"`, `"party"`, `"village"`, `"pm"`). |
| `:urgency()` | `number` | Unread activity score (`0` if all messages read). |
| `:messages()` | `ChatMessage[]` | Recent message lines in this channel. |
| `:info()` | `table` | Plain table snapshot `{ name, kind, urgency }`. |

---

## Protected Actions

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `channel:send(message_text)` | `string` | `chat.send` | Sends a message line into this chat channel. |

---

## Events

Subscribe to `ChatMessage` on `hafen.event()`:

```lua
hafen.event():on("ChatMessage", function(message_info)
  local sender = message_info.sender or "System"
  local channel = message_info.channel or "Area"
  local text = message_info.text or ""

  hafen.log():write(string.format("[%s] %s: %s", channel, sender, text))
end)
```
