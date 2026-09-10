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
`channel:message()` is not interned, because it hangs off the channel: it is
[a view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many), minted per call, and the
identity is on the [lines](#a-line) inside it.

**A channel that has gone answers only its own identity.** The server closes a private conversation by
taking its tab away: `channel:exists()` goes `false` and every other read above answers `nil`, because there
is no longer a tab to read. The object stays the key your own table is under — that is what a
[`ChannelRemoved`](event/bus/chat.md) handler matches on, and why anything you want to know about a channel
after it goes is indexed while it is there.

`channel:name()` is also `nil` for a moment on a **private** conversation that has just opened: the client
names that tab after the other person, resolved through this character's own kin roster, and has no name to
give until the roster carries them. Read the name when you use it rather than when the channel arrives.

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

## The lines

`channel:message()` is what that channel has shown: its lines, oldest first, as one collection.

```lua
local ch = hafen.session():current():chat():selected()
local last = ch and ch:message():get(ch:message():count())
if last then
  local who = last:speaker() and last:speaker():name() or "-"
  hafen.log():write(who .. ": " .. (last:text() or ""))
end
```

| Call | Returns | Description |
|---|---|---|
| `channel:message():list(filter)` | `Message[]` | every line, oldest first |
| `channel:message():count(filter)` | number | how many match |
| `channel:message():find(filter)` | `Message` \| nil | the first that matches |
| `channel:message():get(i)` | `Message` \| nil | the line at position `i`; `nil` past the newest |

The [filter](conventions.md#the-filter-argument) matches a line's own text — not the speaker's name, which
is not part of it — and a predicate receives the Message object. Nothing here is protected: a line is a read.

**A position is a real address.** The client keeps every line a channel has shown for as long as that
channel is open, so line 40 is line 40 for the rest of the login: an index you took an hour ago still names
what it named, and `:get(ch:message():count())` is the newest line. `:list()` copies the whole scrollback
into a Lua array, which on a long login is a large one — read the count and address the lines you want.

**Indices start at one**, whatever position the client keeps them at internally. `:get(0)`, a negative and
a fraction each raise saying so; a position past the newest line is a miss rather than a mistake and
answers `nil`, because the scrollback grows while you read it.

### A line

| Method | Returns | Description |
|---|---|---|
| `msg:text()` | string \| nil | the line as the client draws it — markup quoted |
| `msg:raw()` | string \| nil | the line as it arrived, quoting still to do |
| `msg:kind()` | string \| nil | the [site key](#the-kind-a-line-wears) this line is drawn at |
| `msg:color()` | [colour](shapes.md#colours) \| nil | the colour the line carries of itself |
| `msg:time()` | number \| nil | when the client took the line, in epoch **seconds** |
| `msg:speaker()` | [`Kin`](kin.md) \| nil | who said it, where the line names anyone |
| `msg:mine()` | boolean \| nil | whether this character is the one who said it |
| `msg:channel()` | [`Channel`](#a-channel) \| nil | the channel it landed in |
| `msg:exists()` | boolean | whether it is still readable — always answers |
| `msg:info()` | [`Message`](types/ui.md#message) \| nil | a plain-table **snapshot** |

A line is interned on its channel and its position, so two reads of the same line are one object,
`msg:channel()` **is** the channel you read it from, and `seen[msg] = true` works as a table key.

**A line goes when its channel does, and only then.** Closing a private conversation takes its whole
scrollback with it: `msg:exists()` goes `false` and every other read above answers `nil`. Nothing else drops
a line — a line scrolled far out of sight is still read back exactly as it was written.

`msg:time()` is a number with a fraction, so print it with `string.format("%d", msg:time())`. Lua's own
`tostring` gives you scientific notation for it, which is a stamp nothing can read back.

### The quoting rule

`msg:text()` is the line **as the chat drew it**, and the chat draws every line through the rich-text
parser with its markup characters quoted — so a `$col{ff0000}{...}` another player typed appears in the
window as those characters and not as red text. That quoting is why a stranger cannot colour, resize or
picture your chat by talking in it, and `msg:text()` carries it with the line: put the answer on a label, a
tooltip or a HUD overlay and it draws there exactly as it drew in the chat.

`msg:raw()` is the same line **before** that, the bytes the server sent. It is the read for **matching** —
a pattern written against what was said needs what was said — and it is the one to keep away from anything
that renders rich text, because whatever markup the sender put in it becomes live the moment it is drawn.

```lua
if msg:raw():match("^wtb ") then ... end          -- matching: the raw line
label:text(msg:text())                            -- drawing: the quoted one
```

`msg:color()` is the colour that line carries before any [theme](ui/style/chat.md) paints
over it: the server's for a party line, the client's for your own, and `nil` for a line that takes the
channel's ordinary colour.

`msg:speaker()` is a [`Kin`](kin.md) of **that character's** roster, and `nil` wherever a line names nobody
at all — the System log, an ordinary channel's plain lines, and both halves of a private conversation, which
carry a direction rather than a sender. `msg:mine()` is the read that answers there: it is asked of every
line, not only the ones that name somebody.

`msg:mine()` is a test of **which kind of line the client built**, not a comparison of names: the client
mints a distinct kind for a line you said, and that is what the read looks at. A line nobody said — the
System log's own output — is therefore `false` rather than unanswerable, which is the safe answer for a
test whose whole job is to pick your own out.

### The kind a line wears

`msg:kind()` is the [site key](ui/style/chat.md) that line is drawn at, which is its channel's own — one of
[the four](#the-four-kinds) — except for the one line that names its own:

| Kind | The line |
|---|---|
| `"chat.mine"` | a line **this character said**, in an ordinary channel or the Party channel |

So a channel of kind `"chat"` holds lines of kind `"chat"` and `"chat.mine"`, and a read of the line's kind
and a theme rule written at it name one thing. A private conversation is the exception the other way: both
halves are `"chat.private"`, and `msg:mine()` is what tells them apart.

## Lines arriving

[`MessageAdded`](event/bus/chat.md) fires for every line that lands in any channel of any character —
your own lines included, because the client shows one only once the server has taken it.

```lua
hafen.event():on("MessageAdded", function(msg, s)
  if msg:kind() == "chat.private" and not msg:mine() then
    hafen.log():write(s:user() .. " was told: " .. (msg:text() or ""))
  end
end)
```

It hands your handler the `Message` and that character's [`Session`](session.md) last, and the Message is
the same object `channel:message():get(i)` answers, so a handler that indexes lines is indexing the objects
it will read back. Lines that arrived before your addon loaded fire nothing:
`channel:message():list()` is how it reads what is already there.

> **A line you say in this handler comes back to it.** `channel:send` puts the line on the wire and the
> server hands it back as another `MessageAdded`, on this key, to every handler including yours — so a
> handler that speaks on every line speaks on its own echo, for ever, one line a frame. Decide what you
> are answering before you answer it: `msg:mine()` is false for everyone else's line, and `msg:text()`
> and `msg:kind()` say the rest.

## Write (unprotected)

### `s:chat():selected(ch)`

Put `ch` on screen in that character's chat — the gesture the player makes by clicking its tab, keyboard
focus included — and hand the **collection** back, so writes chain.

```lua
local s = hafen.session():current()
local party = s:chat():find("Party")
if party then s:chat():selected(party) end
```

Naming the channel already on screen changes nothing and fires no [`ChannelSelected`](event/bus/chat.md). It
raises, naming what is wrong, on a value that is not a `Channel`, a channel that has gone
(`channel:exists()` is the test), a character whose HUD is not up yet, and a channel belonging to **another
character** — a chat belongs to the login it was opened on, so the collection you read it from is the one
you may write it to.

**Changing tabs needs no permission.** It moves the client's own window and nothing else: the server is
never told, and nothing about any character changes.

**Taking the keyboard with it needs `ui.focus`.** The client's own tab change puts the cursor in that
channel's entry line, and so does this one — for an addon the user granted
[`ui.focus`](../guides/permissions.md#the-catalogue). Without the key the tab still changes and the keyboard
stays exactly where the player left it, which is the only half of this verb they would have to undo by hand:
called every frame, the focusing half held the entry line against them.

## Write (protected)

The client sends only shapes a player could compose, and what the server does with more than that is
the server's.

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

`text` is **one typed line**: 1 to 512 characters, and a newline, tab or other control character raises
naming its position. That is the whole of what the entry line under a channel can compose, and it is the
whole of what this verb will send.

`text` is sent exactly as you wrote it. The server decides what a line does — a command, an emote, a
whisper — precisely as it does for the line the player types.

**Why it is protected.** Saying a line leaves the client: other players see it, under your character's name.
The line the user reads when they enable your addon is "say a line in the chat, as any of your characters",
and it covers every login the client holds — the key names the action rather than the character it is
pointed at.

## Channels coming and going

The three [chat events](event/bus/chat.md) are where an addon learns that a channel was opened, closed or put
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

- [the `Channel` and `Message` snapshots](types/ui.md#channel) — the shapes `:info()` returns
- [events](event/bus/chat.md) — `ChannelAdded`, `ChannelRemoved`, `ChannelSelected` and `MessageAdded`
- [the chat's style keys](ui/style/chat.md) — the same four words, from the painting side
- [`hafen.session`](session.md) — the address a chat is read through
- [permissions](../guides/permissions.md) — `chat.send`, and the whole catalogue of keys
