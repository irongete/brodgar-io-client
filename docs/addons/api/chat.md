# session:chat: The Chat Channels

The channels one character's chat holds, which one is on screen, putting another there, and saying a line, reached through the [session](session.md) of the character you mean; `session:chat()` is one collection over the tabs down the side of the chat window.

```lua
local session = hafen.session():current()                       -- the character on screen
for _, channel in ipairs(session and session:chat():list() or {}) do
  hafen.log():write((channel:name() or "?") .. "  " .. channel:kind() .. "  urgency " .. channel:urgency())
end
```

---

| Rule | Detail |
|---|---|
| Whose chat | The chat is a window of one login's HUD: two characters have two Party channels, two System logs, two private conversations with the same person. `hafen.session():get("alt"):chat():count()` answers for that character. |
| One object | `session:chat()` is the same object every call, minted once per session. A character with no HUD yet has an empty collection and no selection. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:chat():list(filter)` | `Channel[]` | Unprotected | Every channel, in tab order. |
| `session:chat():count(filter)` | `number` | Unprotected | How many match. |
| `session:chat():find(filter)` | `Channel \| nil` | Unprotected | The first that matches. |
| `session:chat():selected()` | `Channel \| nil` | Unprotected | The channel on screen; `nil` before the HUD is up, and until another is picked after the selected one goes. |

| Rule | Detail |
|---|---|
| `filter` | The [filter](conventions.md#the-filter-argument) matches a channel's name; a predicate receives the Channel. Nothing throws; nothing is protected. |
| No `session:chat():get` | A channel carries no key the client addresses (the server places and removes tabs, and two may carry one name): `session:chat():find(needle)` searches by name, `session:chat():list()[n]` takes a position, and `get` raises naming both. |

## A channel

| Method | Returns | Permission | Description |
|---|---|---|---|
| `channel:name()` | `string \| nil` | Unprotected | The caption on its tab. |
| `channel:kind()` | `string \| nil` | Unprotected | The [kind of line](#the-four-kinds) it holds. |
| `channel:urgency()` | `number \| nil` | Unprotected | How loudly it is unread; `0` when nothing is. |
| `channel:exists()` | `boolean` | Unprotected | Whether it is still one of that character's; always answers. |
| `channel:message()` | collection | Unprotected | [The lines](#the-lines) it has shown. |
| `channel:info()` | [`Channel`](types/ui.md#channel) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Interned on the tab | `session:chat():list()[1] == session:chat():selected()` when it is the one on screen; `seen[channel] = true` works. Two characters' Party channels are two objects. `channel:message()` is [a view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) minted per call; the identity is on the [lines](#a-line). |
| A gone channel answers only its identity | The server closes a private conversation by taking its tab: `channel:exists()` goes `false` and every other read answers `nil`. The object stays the key your table is under, what a [`ChannelRemoved`](event/bus/chat.md) handler matches on; index what you want to know while the channel is there. |
| A new private conversation is nameless for a moment | The client names the tab after the other person through this character's kin roster; read the name when you use it. |

### The four kinds

`channel:kind()` is the [site key](ui/style/chat.md) the channel's lines are coloured at: what a theme paints and what your addon reads are one closed set.

| Kind | The channel |
|---|---|
| `"chat"` | An ordinary channel: Area Chat, a village or realm channel, anything the server names. |
| `"chat.system"` | The System log: what the client tells you. |
| `"chat.party"` | The Party channel. |
| `"chat.private"` | One private conversation, both halves. |

`channel:urgency()` is the level the client keeps for an unread tab, what [`chat.urgent`](ui/style/chat.md#the-two-colours-the-client-walks)'s palette is read one entry per, rising; `0` is nothing unread and takes no colour.

## The lines

`channel:message()` is what the channel has shown, oldest first.

```lua
local channel = hafen.session():current():chat():selected()
local newest = channel and channel:message():get(channel:message():count())
if newest then
  local speaker_name = newest:speaker() and newest:speaker():name() or "-"
  hafen.log():write(speaker_name .. ": " .. (newest:text() or ""))
end
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `channel:message():list(filter)` | `Message[]` | Unprotected | Every line, oldest first; copies the whole scrollback, large on a long login. |
| `channel:message():count(filter)` | `number` | Unprotected | How many match. |
| `channel:message():find(filter)` | `Message \| nil` | Unprotected | The first that matches. |
| `channel:message():get(index)` | `Message \| nil` | Unprotected | The line at position `index`; `nil` past the newest. |

| Rule | Detail |
|---|---|
| `filter` | Matches a line's own text, not the speaker's name; a predicate receives the Message. |
| A position is a real address | The client keeps every line while the channel is open: line 40 is line 40 for the login, and `:get(channel:message():count())` is the newest. Read the count and address the lines you want. |
| Indices start at one | `:get(0)`, a negative and a fraction raise; past the newest is `nil`, since the scrollback grows while you read. |

### A line

| Method | Returns | Permission | Description |
|---|---|---|---|
| `message:text()` | `string \| nil` | Unprotected | The line as the client draws it, markup quoted. |
| `message:raw()` | `string \| nil` | Unprotected | The line as it arrived, quoting still to do. |
| `message:kind()` | `string \| nil` | Unprotected | The [site key](#the-kind-a-line-wears) this line is drawn at. |
| `message:color()` | [colour](shapes.md#colours) `\| nil` | Unprotected | The colour the line carries of itself before any [theme](ui/style/chat.md): the server's for a party line, the client's for your own, `nil` for the channel's ordinary colour. |
| `message:time()` | `number \| nil` | Unprotected | When the client took the line, epoch seconds with a fraction: print with `string.format("%d", message:time())`, since `tostring` gives scientific notation. |
| `message:speaker()` | [`Kin`](kin.md) `\| nil` | Unprotected | Who said it, a `Kin` of that character's roster; `nil` where the line names nobody (the System log, an ordinary channel's plain lines, both halves of a private conversation). |
| `message:mine()` | `boolean \| nil` | Unprotected | Whether this character said it: a test of which kind of line the client built, asked of every line, `false` for the System log's own output. |
| `message:channel()` | [`Channel`](#a-channel) `\| nil` | Unprotected | The channel it landed in, the object you read it from. |
| `message:exists()` | `boolean` | Unprotected | Whether it is still readable; always answers. |
| `message:info()` | [`Message`](types/ui.md#message) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Interned on channel and position | Two reads of one line are one object; `seen[message] = true` works. |
| A line goes when its channel does, and only then | Closing a private conversation takes its scrollback: `message:exists()` goes `false`, every other read `nil`. A line scrolled out of sight reads back as written. |
| The quoting rule | `message:text()` is the line as the chat drew it through the rich-text parser with markup quoted, so a `$col{ff0000}{...}` another player typed appears as those characters: put it on a label, a tooltip or a HUD overlay and it draws as it drew in the chat. `message:raw()` is the bytes the server sent: the read for matching (`if message:raw():match("^wtb ") then`), and the one to keep away from anything that renders rich text. |

### The kind a line wears

`message:kind()` is its channel's kind, one of [the four](#the-four-kinds), except for one line that names its own.

| Kind | The line |
|---|---|
| `"chat.mine"` | A line this character said, in an ordinary channel or the Party channel. |

A channel of kind `"chat"` holds lines of kind `"chat"` and `"chat.mine"`. Both halves of a private conversation are `"chat.private"`, and `message:mine()` tells them apart.

## Lines arriving

[`MessageAdded`](event/bus/chat.md) fires for every line landing in any channel of any character, your own included (the client shows one once the server has taken it). It hands the `Message`, the object `channel:message():get(index)` answers, and the [`Session`](session.md) last. Lines that arrived before your addon loaded fire nothing; `channel:message():list()` reads them.

```lua
hafen.event():on("MessageAdded", function(message, session)
  if message:kind() == "chat.private" and not message:mine() then
    hafen.log():write(session:user() .. " was told: " .. (message:text() or ""))
  end
end)
```

> **A line you say in this handler comes back to it.** `channel:send` puts the line on the wire and the server hands it back as another `MessageAdded` to every handler, yours included: a handler that speaks on every line speaks on its own echo, one line a frame. `message:mine()` is false for everyone else's line.

## Write (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:chat():selected(channel)` | the collection | Unprotected | Put `channel` on screen in that character's chat, the gesture of clicking its tab. |

```lua
local session = hafen.session():current()
local party = session:chat():find("Party")
if party then session:chat():selected(party) end
```

| Rule | Detail |
|---|---|
| Already on screen | Changes nothing and fires no [`ChannelSelected`](event/bus/chat.md). |
| Raises | A value that is not a `Channel`; a channel that has gone (`channel:exists()`); a character whose HUD is not up; a channel belonging to another character (the collection you read it from is the one you may write it to). |
| No permission | It moves the client's own window; the server is never told. |
| The keyboard needs `ui.focus` | The client's tab change puts the cursor in the channel's entry line, and so does this one for an addon granted [`ui.focus`](../guides/permissions.md#the-catalogue). Without the key the tab changes and the keyboard stays where the player left it. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `channel:send(text)` | the channel | `chat.send` | Say `text` in that channel, as that character. |

```lua
local alt_session = hafen.session():get("alt")
local area = alt_session:chat():find("Area Chat")
if area then area:send("on my way") end
```

| Rule | Detail |
|---|---|
| Out of the channel's login | A character you are not looking at speaks for itself. |
| Raises, in this order | The missing permission; an empty or non-string `text`; a channel that has gone or has no entry line (a `"chat.system"` channel refuses naming the kinds that take a line). |
| `text` | One typed line, 1 to 512 characters; a newline, tab or other control character raises naming its position. Sent exactly as written: the server decides what a line does (a command, an emote, a whisper). |
| Why protected | Other players see it under your character's name. The consent line is "say a line in the chat, as any of your characters", covering every login. |

## Channels coming and going

The [chat events](event/bus/chat.md) report a channel opened, closed or put on screen, each handing the `Channel` and the [`Session`](session.md) last. They report changes: an addon loaded with four channels open hears about none, and `session:chat():list()` reads what is there. A channel arriving is reported added before selected.

```lua
hafen.event():on("ChannelSelected", function(channel, session)
  hafen.log():write(session:user() .. " is looking at " .. (channel:name() or "?"))
end)
```

---

## See Also

- [The `Channel` and `Message` snapshots](types/ui.md#channel) — the shapes `:info()` returns.
- [Events](event/bus/chat.md) — `ChannelAdded`, `ChannelRemoved`, `ChannelSelected`, `MessageAdded`.
- [The chat's style keys](ui/style/chat.md) — the same words, from the painting side.
- [`hafen.session`](session.md) — the address a chat is read through.
- [Permissions](../guides/permissions.md) — `chat.send`, and the catalogue of keys.
