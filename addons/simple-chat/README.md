# Simple Chat

**This addon replaces the client's chat window.** The client stacks its channels down the left-hand side,
one tab under the last, and docks the whole thing into the bottom of the HUD, where it cannot be moved and
cannot be resized. Simple Chat puts that window away and stands in for it: the same channels and the same
lines, with the tabs in a **row across the top**, in a window you drag where you want it and size how you
want it.

| You do | It does |
|---|---|
| type `:simplechat` | hides the window of the character on screen, or brings it back |
| click a tab | puts that channel on screen |
| **drag anywhere on the body** | moves the window |
| drag the **bottom-right corner**, where the frame is scored | resizes it |
| turn the wheel over the lines | scrolls back through them |
| type in the line at the bottom and press Enter | says it in the channel on screen |

Where you put it and how big you made it are remembered, for every character on the account.

**It stays on the screen, whole.** You cannot drag it off an edge or size it past one, and if you make the
client's window smaller the chat is pulled back inside on the next frame. That is stricter than the
client's own rule, which only keeps a hundred pixels of a window graspable — a hundred pixels is an action
bar's whole height, so a bar looks locked to the screen, while the same allowance would let most of a chat
this size walk off it.

## The tabs are the channels

One tab per channel the character has, in the order the client keeps them, and the tab reads what the
client's own tab reads: **Area Chat**, **Party**, the System log, one per private conversation. The tab on
screen is the lit one. A tab with something in it nobody has read is written in amber — the same
distinction the client draws with its unread glow.

A name too long for its tab is cut with an ellipsis rather than wrapped, and the cut is measured against
the font actually being drawn, so it lands where the letters stop rather than at a guessed character count.

**Tabs share the width they have.** With a handful of channels each tab is at its natural width; with many,
they narrow together down to a floor, and past that the ones that do not fit are simply not shown. Make the
window wider and they come back.

## The lines

Lines are drawn newest at the bottom, wrapped to the window's width, each in the colour it carries — a
speaker's own colour, the pale blue of a line you said, the dark red of a client error. A line that names a
speaker is drawn with their name in front of it, because the line the client hands over is what was said
and not who said it.

Markup inside a line is honoured, so a server that colours part of a line still colours it here.

**Scrolling back holds still.** Once you have scrolled up, a line arriving below does not shift what you are
reading — the view stays where you parked it, and scrolling to the bottom picks the newest lines up again.

## Saying a line

The line at the bottom sends to the channel whose tab is lit, exactly as the client's own does: what you
type is sent as you typed it, and the server decides what it means — a command, an emote, a whisper. It
runs the full width the frame leaves, the same width the lines above it wrap to — the corner you resize by
is scored into the frame itself, so nothing standing inside the window has to keep clear of it.

**It needs the `chat.send` permission**, which the client asks you to grant the first time it sees this
addon. Without it the addon still shows everything; only the sending is refused, and the refusal is written
to the log rather than swallowed.

## The client's chat comes back

The client's chat is hidden while this addon runs and is **given back when you disable or reload it**, on
every character it was hidden on. That matters more here than it does elsewhere: the chat is not one of the
windows the client can reopen from a key or a menu tick, so an addon that hid it and walked away would
leave you with no chat and nothing to bring it back with. Simple Chat gives it back by hand, before the
teardown runs.

A character that logs in while the addon is running has its chat hidden as it arrives, and characters
already logged in when the addon loads are swept as well — so a `:reload` in the middle of a game does not
leave a background character showing the window this one is standing in for.

## One window per character

A chat belongs to a login: two characters have two System logs, two Party channels, and two private
conversations with the same person. So the window is that login's too — it hangs on that character's own
HUD, is built when the character reaches the world and goes down with them. Tab between two characters and
you are looking at two windows, each already showing its own tabs and its own scrollback, with nothing to
re-point and nothing to reload.

You never see both at once. The client holds every login as a live session and **draws one** of them, so
the other character's window is not hidden or moved aside — it is simply not in the tree being drawn.

Nothing of this addon exists before a character does, which is why the login screen and the character list
are bare: there is no window there waiting to be shown. Picking another character on the same account tears
the HUD down and puts a new one up, and the window is rebuilt with it.

Where you put the window is remembered for the **account**, not the character, so every character opens the
chat where you like the chat.

## Making it look like something else

Every surface it draws is named, so a [theme](../themes) can dress it without this addon knowing themes
exist:

| Selector | The surface |
|---|---|
| `[name=simple-chat/panel]` | **the whole window's field** — its background, and nothing else |
| `[name=simple-chat/frame]` | the frame around it (three widgets, one rule dresses all three) |
| `[name=simple-chat/log]` | the rectangle the lines are laid out in — no field of its own |
| `[name^=simple-chat/tab]` | every tab; `[name=simple-chat/tab1]` is the first alone |
| `[name^=simple-chat/joint]` | the two corners that carry the frame round the lit tab |
| `[name=simple-chat/sizer]` | the scored corner you resize by |

Out of the box the frame wears `gfx/hud/wnd`, the box every panel in the game wears — the inventory, the
portrait, the skill lists, the action bars — so it sits among them rather than beside them. Everything is
**declared**, never painted, so a rule of yours beats any of it per property — replace only the tabs'
border and the frame stays the client's.

**The tabs wear that same frame, and it is not a likeness.** `tab.png` and `tab-on.png` are cut from
`gfx/hud/wnd` itself — its four corners and its four edge runs, reduced from the scale the client authors
them at to the design pixels a shipped file is authored in. Not a band that resembles the window's brass:
the window's brass, at its own weight, so a tab and the panel it stands on are one material rather than two
that happen to touch. `tab.png` is cut 8/8/8/8, a closed box. `tab-on.png` is cut 8/8/8/**1** — corners and
runs down three sides, and a bottom slice of a single row carrying the two side runs with nothing between
them. That one row is the open foot, and it is what lets the sides reach the tab's own bottom edge instead
of stopping a corner short of it.

**There is one field, and it is the action bars'.** The same colour at the same alpha: everything inside
the frame — behind the lines, behind the line you type in, behind the margins around them — is that one
translucent wash, declared on the panel, running to the ink on every side. The lines have no background of
their own: what looks like the log's is the panel's, and the room the lines keep off the frame is a margin
inside one colour rather than a second colour laid around them. That is what makes this window read the way
an action bar does — a field under a frame, with things standing on it — instead of as a box inside a box.

**The tab on screen is filled with that field, exactly.** Same colour, same alpha, over the same world, so
where its foot meets the window the two are literally the same pixels.

**The two tab faces differ in one line, and in nothing else.** Every tab starts on the same row and is cut
the same way; the lit one simply has no bottom edge, and goes on down through the air the others leave
under them until it reaches the frame. It does not stand taller at the top — a tab that reached higher than
its neighbours would read as a bigger tab rather than as the open one. If you replace the art, keep that
difference: the missing foot is the whole of what makes a row of rectangles read as tabs.

**And only the lit one touches the window.** The others stand a pixel clear of the frame, so a tab
that is not the one you are reading belongs to nothing yet: the air under it says as much as its colour
does. The lit tab reaches down through that gap and past it.

**The selected tab stops at the frame's top edge** and goes no further. What joins it to the panel is not
the tab growing downwards — that would drag its two brass sides down across the frame and into the
content — but the frame simply not being drawn for that tab's whole width. A tab meeting its panel is a
*hole* in the frame, and a hole is not a shape a nine-slice can cut: a border goes all the way round by
definition, and nothing this window could paint over one would hide it, because the field is translucent.

**And the line turns the corner.** At each end of that hole the frame's top run runs up into the tab's
side and keeps going, round the tab's own top and down again — one unbroken outline, as though the window's
border had simply detoured to go round the tab. The piece that turns it is `joint-l.png` / `joint-r.png`,
and it is shipped because the client has none to lend: every corner a nine-slice carries has the metal in
one quadrant with the world wrapped round three, and this corner is the other way about — metal on three
sides, world in one. So it is **mitred** from the two runs' own cross-sections, the tab's side continuing
down above the diagonal and the panel's run continuing across below it, meeting along it. No pixel in it
was invented; the two sections are one bevel rotated, which is why the mitre comes out exact.

**So the frame is drawn three times** — same art, same size, same place, each inside a window that lets a
different part of it through: the top edge left of the mouth, the top edge right of it, and everything
below the top edge. Nothing can be misregistered, because nothing is drawn twice; the stretch no window
shows is the mouth. That is why a rule naming `[name=simple-chat/frame]` dresses three widgets — and why
it dresses them all identically without you doing anything.

The strip the tabs stand in is outside the panel, so nothing dresses it: it is an unnamed holder with no
surface of its own, which is why there is no selector for it here.

