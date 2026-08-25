# session:chat: the chat channels

Read the channels one character's chat holds, see which one is on screen, put another one there, and say a
line. You reach it through the [session](session.md) whose character you mean, and `s:chat()` **is** that
character's channels: one collection over the tabs down the side of the chat window.

```lua
local s = hafen.session():current()                       -- the character on screen
for _, ch in ipairs(s and s:chat():list() or {}) do
  hafen.log():write((ch:name() or "?") .. "  " .. ch:kind() .. "  urgency " .. ch:urgency())
end
```

## Whose chat it is

The chat is a window of one login's HUD, so two characters have two of them: two **Party** channels, two
System logs, and two private conversations with the same person. The read says which character it is about,
and so does the line you say:

```lua
hafen.session():current():chat():count()       -- the channels of the character on screen
hafen.session():get("alt"):chat():count()      -- that character's, while you watch someone else
```

`s:chat()` is the same object every call, minted once for that session. A character with no HUD yet — still
connecting, or on the character list — has an empty collection and no selection, rather than an error.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:chat():list(filter)` | `Channel[]` | every channel, in tab order |
| `s:chat():count(filter)` | number | how many match |
| `s:chat():find(filter)` | `Channel` \| nil | the first that matches |
| `s:chat():selected()` | `Channel` \| nil | the channel on screen; `nil` before the HUD is up, and until another is picked after the selected one goes |

The [filter](conventions.md#the-filter-argument) matches a channel's name, and a predicate receives the
Channel object. Nothing here throws and nothing here is protected.

**There is no `s:chat():get`.** A channel carries no key the client addresses — the server places a tab and
takes it away, and two of them may carry one name — so writing it raises naming the two doors that do exist:
`s:chat():find(needle)` searches by name, and `s:chat():list()[n]` takes a position.

## A channel

| Method | Returns | Description |
|---|---|---|
| `channel:name()` | string \| nil | the caption on its tab |
| `channel:kind()` | string \| nil | the [kind of line](#the-four-kinds) it holds — one of four words |
| `channel:urgency()` | number \| nil | how loudly it is unread; `0` when nothing is |
| `channel:exists()` | boolean | whether it is still one of that character's — always answers |
| `channel:info()` | [`Channel`](types/ui.md#channel) \| nil | a plain-table **snapshot** |

A channel is interned on the tab itself, so `s:chat():list()[1] == s:chat():selected()` when it is the one
on screen, and `seen[ch] = true` works as a table key. Two characters' Party channels are two objects.

**A channel that has gone answers only its own identity.** The server closes a private conversation by
taking its tab away: `channel:exists()` goes `false` and every other read above answers `nil`, because there
is no longer a tab to read. The object stays the key your own table is under — that is what a
[`ChannelRemoved`](event/bus.md#chat) handler matches on, and why anything you want to know about a channel
after it goes is indexed while it is there.

`channel:name()` is also `nil` for a moment on a **private** conversation that has just opened: the client
names that tab after the other person, resolved through this character's own kin roster, and answers `???`
until the roster carries them. Read the name when you use it rather than when the channel arrives.

### The four kinds

`channel:kind()` is the very [site key](ui/style/chat.md) the channel's lines are coloured at, so what a
theme paints and what your addon reads are one closed set of words.

| Kind | The channel |
|---|---|
| `"chat"` | an ordinary channel — **Area Chat**, a village or realm channel, anything the server names |
| `"chat.system"` | the **System** log: what the client tells you, rather than what anyone said |
| `"chat.party"` | the **Party** channel |
| `"chat.private"` | one private conversation, both halves of it |

`channel:urgency()` is the level the client itself keeps for an unread tab, and it is what
[`chat.urgent`](ui/style/chat.md#the-two-colours-the-client-walks)'s palette is read one entry per, rising.
`0` is a channel with nothing unread, which is not a level and takes no colour from that palette.

## Write (unprotected)

### `s:chat():selected(ch)`

Put `ch` on screen in that character's chat — the gesture the player makes by clicking its tab, keyboard
focus included — and hand the **collection** back, so writes chain.

```lua
local s = hafen.session():current()
local party = s:chat():find("Party")
if party then s:chat():selected(party) end
```

Naming the channel already on screen changes nothing and fires no [`ChannelSelected`](event/bus.md#chat). It
raises, naming what is wrong, on a value that is not a `Channel`, a channel that has gone
(`channel:exists()` is the test), a character whose HUD is not up yet, and a channel belonging to **another
character** — a chat belongs to the login it was opened on, so the collection you read it from is the one
you may write it to.

**Changing tabs needs no permission.** It moves the client's own window and nothing else: the server is
never told, and nothing about any character changes.

## Write (protected)

### `channel:send(text)`

Say `text` in that channel, as that character, and hand the **channel** back so writes chain. It needs the
`chat.send` permission.

```lua
local s = hafen.session():get("alt")
local area = s:chat():find("Area Chat")
if area then area:send("on my way") end
```

The line goes out of the login the channel belongs to, so a character you are not looking at speaks for
itself. It raises, in this order, on the missing permission, on an empty or non-string `text`, and on a
channel that has gone or **has no entry line at all** — the System log is written by the client and nobody
says anything in it, so a `"chat.system"` channel refuses and names the kinds that do take a line.

`text` is sent exactly as you wrote it. The server decides what a line does — a command, an emote, a
whisper — precisely as it does for the line the player types.

**Why it is protected.** Saying a line leaves the client: other players see it, under your character's name.
The line the user reads when they enable your addon is "say a line in the chat, as any of your characters",
and it covers every login the client holds — the key names the action rather than the character it is
pointed at.

## Channels coming and going

The three [chat events](event/bus.md#chat) are where an addon learns that a channel was opened, closed or put
on screen. Each hands your handler the `Channel` it is about, and that character's
[`Session`](session.md) last.

```lua
hafen.event():on("ChannelSelected", function(ch, s)
  hafen.log():write(s:user() .. " is looking at " .. (ch:name() or "?"))
end)
```

They report changes rather than state: an addon loaded with four channels already open hears about none of
the four, and `s:chat():list()` is how it learns what is there. A channel that arrives is reported **added
before it is reported selected**, because the client puts a new tab up and picks it in one motion.

## See also

- [the `Channel` snapshot](types/ui.md#channel) — the shape `channel:info()` returns
- [events](event/bus.md#chat) — `ChannelAdded`, `ChannelRemoved` and `ChannelSelected`
- [the chat's style keys](ui/style/chat.md) — the same four words, from the painting side
- [`hafen.session`](session.md) — the address a chat is read through
- [permissions](../guides/permissions.md) — `chat.send`, and the whole catalogue of keys
