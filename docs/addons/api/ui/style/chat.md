# hafen.ui: The Chat Window and Its Kinds of Line

The chat is one [site key](keys.md#site-keys), `chat`, with keys that refine it by kind of line, keys that walk a colour and keys that dress its decoration. Colour is how the client tells one kind from another, so a `color` on `chat` alone paints them all alike.

```lua
local sheet = hafen.ui():sheet()
sheet:rule("chat"):color{180, 190, 200}                     -- everything the kinds below do not claim
sheet:rule("chat.system"):color{255, 200, 0}
sheet:rule("chat.private"):color{255, 60, 200}
sheet:rule("chat.speaker"):color{ palette = {{220, 90, 90}, {90, 200, 120}, {110, 150, 230}} }
sheet:rule("chat.urgent"):color{ palette = {{0, 200, 0}, {230, 200, 0}, {230, 0, 0}} }
sheet:install()
```

---

## Keys

| Key | The lines it covers | The client's own colour |
|---|---|---|
| `chat` | The whole window: every message, the channel tabs, the typed line. | The stock face, and each kind's colour below. |
| `chat.system` | The System log: what the client tells you. | White; dark red for an error. |
| `chat.mine` | Your own line, in whichever channel. | A pale blue-lilac. |
| `chat.private` | A private message, received or sent. | Warm pink in, cool blue out. |
| `chat.party` | A line in the Party channel. | The speaker's party colour, assigned by the server. |
| `chat.urgent` | The unread indicator: an unread channel's tab, the glow on the chat button. | Blue, orange, then red as urgency rises. |
| `chat.speaker` | The colour a speaker is given in a multi-person channel. | A hue the client walks, one step per new speaker. |

A channel answers the key its lines resolve at as [`channel:kind()`](../../chat.md#the-four-kinds), and a line its own as [`msg:kind()`](../../chat.md#the-kind-a-line-wears) — `chat.mine` being the one key a line wears and no channel does — so a rule that colours a kind and an addon that reads one name the same thing.

### Each kind falls back to `chat`

| Rule | Detail |
|---|---|
| One ladder | A kind with no rule reads as `chat`; `chat` with no rule reads as `*`. `["chat"] = {color = …}` paints the whole window; naming one kind changes that kind alone. |
| A fallback, not a blend | A key has a rule or takes the one beneath it whole, as the site half of the cascade always does: write both properties in the rule naming the kind. A [tree key](keys.md#tree-keys) or [`widget:rule()`](README.md#restyle-one-widget) composes per property and outranks every key here. |
| `$col[…]` markup wins | Part of the string the server sent, so a line carrying its own colour keeps it under any theme. |
| `font` | Honoured on every key; a kind without one takes `chat`'s by the same ladder. On the two walked keys it lands on nothing. |

---

## The two colours the client walks

`chat.speaker` is a colour per speaker and `chat.urgent` a colour per urgency level; a single colour would destroy what each is for, so both take a sequence and refuse a colour.

| Written | Is |
|---|---|
| `{palette = {{r,g,b}, …}}` | The colours to hand out, in order, cycling when they run out. Never empty. |
| `{generate = {step =, saturation =, brightness =}}` | A walk around the hue circle, the shape the client's own speaker colour has. All three required: `step` in `0 < step <= 1` (the client's own is about `0.414`); `saturation`, `brightness` in `0..1`, carried by every colour minted. |

| Rule | Detail |
|---|---|
| Refusals | A colour on either key raises naming the sequence; a sequence on any other key raises naming these two; a value naming both spellings raises. For every answer alike, a palette of one colour. |
| Read-back | `rule:color()` reads whichever shape was written. |
| `chat.urgent` | Read one entry per level, rising: the first entry is the quietest unread channel, the last the loudest. A channel with nothing unread keeps the client's resting colour. |
| A speaker keeps the colour given | Minted the first time someone speaks and remembered, so a sequence re-colours the speakers who talk after it is installed; scrollback keeps what it was drawn with. |

---

## The chat's own decoration

`chat` writes the letters; `chat.frame` is what the chat paints round them and `chat.log` the wash behind them. The chat is docked into the HUD, not framed by a decoration, so [`window.frame`](chrome.md) and `window.title` never reach it.

```lua
sheet:rule("chat.frame"):bg{ asset = "img/paper.png", mode = "tile" }        -- the field, behind everything
                        :border{ asset = "img/chatframe.png", slice = {8, 14, 8, 6} }
sheet:rule("chat.log"):bg{ color = {9, 13, 22, 200} }                        -- the wash behind one channel
sheet:rule("chat"):color{150, 225, 240}
```

| Rule | Detail |
|---|---|
| Parts, not kinds | Neither cascades from `chat`: both fall straight back to `*`, as [`checkbox.mark`](keys.md#site-keys) does against `checkbox`. |
| The frame has no bottom edge | The client's own is two corners, three runs and two pinned ornaments, open at the foot: a 9-slice for it wants a transparent bottom slice. That shape is why the [catalogue](README.md#the-clients-own-look) declares the field and stays quiet about the frame. |
| The field is a window's field | `chat.frame`'s stock `bg` is `gfx/hud/wnd/lg/bg`, tiled: the rule that themes your windows themes the chat's field. |
| `padding` | Nothing to move on either: the chat's box is the size the user dragged it to. |
| Not reached | The channel list's own art and the notification popups the chat throws up while collapsed. |

---

## What else is worth knowing

| Rule | Detail |
|---|---|
| Re-rendering | Only the visible messages re-render, the scrollback as it scrolls into view; each message's height is re-measured so the log re-flows under a bigger font. URLs stay clickable. |
| The typed line | Belongs to `chat`, not [`textentry`](surfaces.md#textentry). |
| Tab truncation | Measured from the stock font once, so a much wider font can shorten a long channel name slightly early. |
| Party vs kin colours | A party member's colour is assigned per session and `chat.party` overrides it; a kin group's colour is one you picked, and [`world.nick`](surfaces.md#worldspeech-and-worldnick) paints over it. |

---

## See Also

- [Keys](keys.md#what-each-key-accepts) — every key, and what each does with each property.
- [Text](text.md#color) — the `color` property, and what else wins over it.
- [Surfaces](surfaces.md) — the other surfaces the client draws at.
- [Style](README.md) — installing the sheet.
- [`session:chat`](../../chat.md) — the same words from the reading side.
