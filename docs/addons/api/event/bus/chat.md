# hafen.event: the chat

A channel appearing, going away or taking the tab, and a line landing in one. A channel is one character's
— the chat is a window of that login's HUD — so each of the keys below hands your handler the thing it is
about and that character's [`Session`](../../session.md) last. Everything here is part of
[the catalogue](README.md), so `hafen.event():on(key, fn)` is the door.

| Event | Payload | Fires |
|---|---|---|
| `ChannelAdded` | [`Channel`](../../chat.md#a-channel) | a channel appears in that character's chat |
| `ChannelRemoved` | [`Channel`](../../chat.md#a-channel) | a channel goes away — the object still keys your table, and every read on it is `nil` |
| `ChannelSelected` | [`Channel`](../../chat.md#a-channel) | that character's chat changed tab |
| `MessageAdded` | [`Message`](../../chat.md#a-line) | a line lands in a channel — any channel, any character |

```lua
hafen.event():on("MessageAdded", function(msg, s)
  if not msg:mine() then
    hafen.log():write(s:character() .. " heard: " .. msg:text())
  end
end)
```

**A new channel is added before it is selected.** The client puts a tab up and picks it in one motion, so a
channel arriving fires both, in that order. `ChannelSelected` fires only on a **change**, whether the player
clicked the tab or an addon wrote it with
[`s:chat():selected(ch)`](../../chat.md#write-unprotected), so naming the tab already on screen fires
nothing. Losing the selected channel selects nothing and fires nothing: the payload **is** a channel, and
none was picked.

On `ChannelRemoved` the channel is **already gone** — `ch:exists()` is `false` and `ch:name()`, `:kind()`
and `:urgency()` all read `nil`. The payload is still the object you indexed on `ChannelAdded`, because
channels are interned, so match on it rather than on a name you can no longer read.

`MessageAdded` fires for every line the client shows, the ones **this character said** included: the client
draws your own line only once the server has sent it back. Read `msg:mine()` to tell them apart, and
`msg:channel()` for the channel it landed in — the very object `ChannelAdded` handed you. A line is reported
after the channel it landed in was reported to arrive.

## See also

- [the catalogue](README.md) — the other families, and whose character an event was
- [`s:chat()`](../../chat.md) — the channels, their lines, and the one write that says one
- [the chat colours](../../ui/style/chat.md) — the site keys `ch:kind()` and `msg:kind()` answer with
- [data types](../../types/ui.md#channel) — what `ch:info()` and `msg:info()` copy out
