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
| `:message()` | `MessageCollection` | Collection of message lines in this channel (`:list()`, `:count()`, `:get(i)`, `:find()`). |
| `:info()` | `table` | Plain table snapshot `{ name, kind, urgency }`. |

---

## Methods on `Message`

| Method | Returns | Description |
|---|---|---|
| `:text()` | `string \| nil` | The line formatted as rendered in chat (HTML/rich-text characters quoted). |
| `:raw()` | `string \| nil` | The raw unquoted line text as sent by the server (for string matching). |
| `:kind()` | `string` | Site key style (`"chat"`, `"chat.mine"`, `"chat.party"`, `"chat.private"`, `"chat.system"`). |
| `:color()` | `table \| nil` | Color table `{r, g, b, a}` assigned to the line. |
| `:time()` | `number` | Epoch timestamp in seconds when the line was received. |
| `:speaker()` | `Kin \| nil` | Kin handle of the speaker (or `nil` for system/unnamed lines). |
| `:mine()` | `boolean` | `true` if this character said the line. |
| `:channel()` | `Channel` | The parent chat channel containing this line. |
| `:exists()` | `boolean` | `true` if this message's channel is still open. |
| `:info()` | `table` | Plain table snapshot. See [`Message`](types/ui.md#message). |

---

## Protected Actions

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `channel:send(message_text)` | `string` | `chat.send` | Sends a message line into this chat channel. |

---

## Events

Subscribe to `MessageAdded` on `hafen.event()`:

```lua
hafen.event():on("MessageAdded", function(message)
  local speaker_kin = message:speaker()
  local speaker_name = speaker_kin and speaker_kin:name() or "System"
  local channel = message:channel() and message:channel():name() or "Area"
  local text = message:text() or ""

  hafen.log():write(string.format("[%s] %s: %s", channel, speaker_name, text))
end)
```
