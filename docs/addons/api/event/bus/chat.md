# hafen.event: The Chat

A channel appearing, going away or taking the tab, and a line landing in one. A channel is one character's: the chat is a window of that login's HUD. So each key hands the thing it is about and that character's [`Session`](../../session.md) last. Part of [the catalogue](README.md).

```lua
hafen.event():on("MessageAdded", function(message, session)
  if not message:mine() then
    hafen.log():write(session:character() .. " heard: " .. message:text())
  end
end)
```

---

| Event | Payload | Fires |
|---|---|---|
| `ChannelAdded` | [`Channel`](../../chat.md#a-channel) | A channel appears in that character's chat. |
| `ChannelRemoved` | [`Channel`](../../chat.md#a-channel) | A channel goes away. The object still keys your table, and every read on it is `nil`. |
| `ChannelSelected` | [`Channel`](../../chat.md#a-channel) | That character's chat changed tab. |
| `MessageAdded` | [`Message`](../../chat.md#a-line) | A line lands in a channel: any channel, any character. |

| Rule | Detail |
|---|---|
| Added before selected | The client puts a tab up and picks it in one motion, so a channel arriving fires both, in that order. |
| `ChannelSelected` fires on a change | Whether the player clicked the tab or an addon wrote [`session:chat():selected(channel)`](../../chat.md#write-unprotected). Naming the tab already on screen fires nothing. Losing the selected channel selects nothing and fires nothing: the payload is a channel, and none was picked. |
| `ChannelRemoved` is already gone | `channel:exists()` is `false`. `channel:name()`, `:kind()` and `:urgency()` read `nil`. The payload is the object `ChannelAdded` handed you (channels are interned): match on it, not on a name you can no longer read. |
| `MessageAdded` includes your own lines | The client draws your line once the server sends it back. `message:mine()` tells them apart. `message:channel()` is the object `ChannelAdded` handed you. A line is reported after its channel was reported to arrive. |

---

## See Also

- [The catalogue](README.md) — the other families, and whose character an event was.
- [`session:chat()`](../../chat.md) — the channels, their lines, and the one write that says one.
- [The chat colours](../../ui/style/chat.md) — the site keys `channel:kind()` and `message:kind()` answer with.
- [Data types](../../types/ui.md#channel) — what `channel:info()` and `message:info()` copy out.
