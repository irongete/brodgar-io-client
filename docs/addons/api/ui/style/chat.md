# hafen.ui: the chat window, and the kinds of line in it

The chat is one [site key](keys.md#site-keys) covering the whole window, and five more that refine it — one
per **kind** of line. Colour is how this client tells one kind from another, so a `color` on `chat` alone
paints them all alike; the keys below are how a theme keeps them apart.

| Key | The lines it covers | The colour the client gives them |
|---|---|---|
| `chat` | the whole window: every message, the channel tabs, the quick line you type | the stock face, and each kind's own colour below |
| `chat.system` | the **System** log: what the client tells you rather than what anyone said | white, and a dark red for an error |
| `chat.mine` | your **own** line, in whichever channel you said it in | a pale blue-lilac |
| `chat.private` | a private message, **received or sent** | a warm pink in, a cool blue out |
| `chat.party` | a line in the **Party** channel | the speaker's own party colour, which the server assigns |
| `chat.urgent` | the **unread** indicator: an unread channel's tab, and the glow on the chat button | blue, orange, then red as urgency rises |
| `chat.speaker` | the colour a **speaker** is given in a multi-person channel | a hue the client walks, one step per new speaker |

```lua
local s = hafen.ui():sheet()
s:rule("chat"):color(180, 190, 200)                     -- everything the four kinds below do not claim
s:rule("chat.system"):color(255, 200, 0)
s:rule("chat.private"):color(255, 60, 200)
s:rule("chat.speaker"):color{ palette = {{220, 90, 90}, {90, 200, 120}, {110, 150, 230}} }
s:rule("chat.urgent"):color{ palette = {{0, 200, 0}, {230, 200, 0}, {230, 0, 0}} }
s:install()
```

## Each kind falls back to `chat`

A kind with no rule of its own reads as `chat`, and `chat` with no rule of its own reads as `*` — one
ladder, and a rule anywhere on it answers for every rung above that says nothing. So `["chat"] = {color =
…}` still paints the whole window, exactly as it does with no kind named at all, and naming one kind changes
that kind and nothing else.

It is a **fallback, not a blend**: a key either has a rule or takes the one beneath it whole, which is how
the site half of the cascade has always worked. Write both properties in the rule that names the kind if you
want both. Levels *above* the site half — a [tree key](keys.md#tree-keys), a
[`widget:rule()`](README.md#restyle-one-widget) — do compose per property, and both outrank every key here.

> **`$col[…]` markup inside a line wins over every rule on this page.** It is part of the *string* the
> server sent, not the site's choice of colour, so a line that carries its own colour keeps it whatever a
> theme says. That is what keeps a server's own emphasis legible under any theme.

## The two colours the client walks

`chat.speaker` and `chat.urgent` are not one colour each. The first is a colour **per speaker**, so that two
people talking are two colours; the second is a colour **per urgency level**, so that a channel one message
behind reads differently from one that is shouting. Flattening either to a single colour destroys the only
thing it is for, so neither takes a colour: both take the **sequence**, written one of two ways.

| Written | Is |
|---|---|
| `{palette = {{r,g,b}, …}}` | the colours to hand out, in the order written, cycling when they run out |
| `{generate = {step =, saturation =, brightness =}}` | a walk around the hue circle — the shape the client's own speaker colour has |

| Field of `generate` | Value |
|---|---|
| `step` | how far around the hue circle each answer moves, `0 < step <= 1`. The client's own is about `0.414` |
| `saturation`, `brightness` | `0..1` each, and every colour the walk mints carries them |

All three fields of a `generate` are required: each decides a different thing about every colour it mints,
and none has a default worth guessing at. A `palette` is never empty, a `step` is never `0` — it would never
move off one hue, which is one colour for every speaker — and a value naming both spellings is an error
rather than one of the two silently winning.

`rule:color()` reads back whichever shape was written, so a read round-trips into a write.

- **A colour on either key is an error**, and it names the sequence. **For every answer alike, say so**: a
  palette of one colour cycles to that colour every time, which is a sentence rather than an accident.
- **A sequence on any other key is an error too**, naming the two keys that take one. Nothing else in the
  client hands out colours one at a time.
- **`chat.urgent`'s palette is read one entry per level**, rising: the first entry is the quietest unread
  channel and the last the loudest. A channel with **nothing** unread is not a level and keeps the client's
  own resting colour, so a rule never reaches it.
- **A speaker keeps the colour they were given.** The client mints one the first time someone speaks and
  remembers it, so installing a sequence re-colours the speakers who talk *after* it, and lines already in
  the scrollback keep what they were drawn with. A line's colour is decided when it arrives.

## What else is worth knowing

Only the messages currently **visible** re-render, the scrollback re-rendering as you scroll it into view,
and each message's height is re-measured so the log re-flows correctly under a bigger font. URLs stay
clickable — a rule keeps the chat's own link parser.

The typed **quick line** belongs to `chat` rather than to [`textentry`](surfaces.md#textentry): it lives in
the chat window and is built from the chat's own recipe. The channel-tab **truncation width** was measured
from the stock font once, so a much wider font can shorten a long channel name slightly early.

The **party** colours and the **kin-group** colours are two different things, and only the first is on this
page. A party member's colour is assigned per session and `chat.party` overrides it like any other stock; a
kin group's colour is one you picked yourself, and it is [`world.nick`](surfaces.md#worldspeech-and-worldnick)
that paints over it.

`font` is honoured on every key here, and a kind with no `font` of its own takes `chat`'s, by the same
ladder its colour takes. The two keys that walk a colour draw no text of their own, so a `font` on either
lands on nothing — accepted and inert, like every other property a surface has no use for.

## See also

- [keys](keys.md#what-each-key-accepts) — every key in the client, and what each does with each property
- [text](text.md#color) — the `color` property itself, and what else wins over it
- [surfaces](surfaces.md) — the other surfaces the client draws text and boxes at
- [style](README.md) — installing the sheet these keys go in
