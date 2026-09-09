# Trellis Builder

Name a block pile, a string pile and a patch of ground; press **Start**. The character fetches what a
trellis takes, walks there by a route it works out itself, and fills every tile of the patch with as
many trellises as the trellis's own footprint leaves room for.

## Using it

1. `:trellis` opens the window. Three buttons, each a two-click pick:
   - **Block pile area** — click one corner of the ground your block piles stand on, then the opposite
     corner. Right-click cancels. While a pick is running the pointer is yours: clicks do not reach the
     game, so nothing is selected or walked to by accident.
   - **String pile area** — the same, over the piles holding the other material.
   - **Trellis build area** — the patch to fill. Every whole tile the rectangle touches is built on.
2. **Start**. **Stop** halts after the beat in progress.

The three areas are saved per account, so they survive a `:reload` and a restart. `:trellis status`
prints them, what the piles turned out to hold, and how the job is going; `:trellis clear` forgets all
three; `:trellis start` and `:trellis stop` are the two buttons.

## What a material *is*

**The area, not the name.** Nothing in this addon knows what a block or a string is called. It counts
the backpack, draws one item out of a pile inside the area you picked, reads what arrived, and that
resource is that material from then on — remembered beside the area that taught it. So a server whose
string is called something else needs no configuration, and a pile holding two kinds of the same thing
teaches both. Re-picking an area forgets what it taught: a new pile gets read afresh.

The **recipe** is the one thing stated up front, at the top of `main.lua`: three blocks and one string.
Change those two numbers if your server disagrees.

## The loop

One trellis at a time, and the order matters:

1. **Fetch the materials for one trellis**, walking to the piles by route.
2. **Walk to the tile next to** the spot the trellis is going on — never onto it.
3. **Build ▸ Trellis** — the ghost goes up here and nowhere earlier.
4. **Place** at the computed spot; the character walks the last step itself.
5. **Walk to the site** and press **Build** on its window.

Step 3 is last on purpose. While a building ghost is on the cursor the map view sends `place` for
*any* mouse button, so nothing else on the map can be clicked while one is up — a stockpile included.
Raising it before the shopping trip is what stops the piles from opening at all.

A ghost left standing — the server refused the spot, or you pressed Stop mid-placement — is taken back
with a right-click, which is the only way a player takes one back either. It happens before the next
trip to a pile, and on Stop.

## Building it

Nothing is carried into the site and nothing is dropped in its boxes. The boxes state what it wants;
**Build** is what takes the materials out of the backpack. So the whole of building is finding that
button and sending it the message its own click sends — which is why the materials are fetched *before*
walking over, and why the backpack is all that has to be right when the press goes out.

Build is pressed again every second and a half while the site still stands, up to twenty seconds: a
building that takes its materials in stages wants one press per stage, and a press the server did
nothing with costs a press and nothing else. The site turning into a trellis is what ends it.

The window is found by walking **up from a material box** to the window it stands in — never by a
caption, which is a word the server chose. A Stockpile's window is the one other thing with a box in
it, and it is skipped by name; `PILE_TITLE` at the top of `main.lua` is that name.

The button is then asked of the session's whole tree in the chained form the selectors exist for:
`window[title=<that window's own caption>] button` — the space is the descendant combinator, so this
says "a button with that window somewhere above it" rather than asking a window handle to search
inside itself, which would be a second mechanism to get wrong. Where the window holds more than one,
the one captioned `Build` wins; where it holds one, that is the one. The first press of a run says
which button it pressed, out of how many, under which window.

**`:trellis site` prints what that window actually holds** — its title, its boxes with their figures and
resources, and every button with its caption, class and server id. If a press ever fails, that one line
says why rather than leaving it to guesswork.

## How it decides how many fit

The trellis's footprint is read off the ghost the build action puts on the cursor — `placing:hitbox()`,
the same rings the finished object wears — and turned back out of the ghost's own facing so it is the
object's box rather than the cursor's. A tile is 11 world units, so a box 10 × 2 gives one column of
four; a different object, or a server that resizes one, divides the tile differently with no change here.
The box is read off the first ghost raised, and the tile is cut up at that moment — the ghost that
taught it is the one that gets placed, so nothing is raised twice and nothing is raised early.

**It works from the far end back, and always from the same side.** Four trellises in a tile leave gaps
narrower than a character, so a filled tile is a wall. Both the tiles and the slots within one are
therefore taken **farthest from the supply first** — the middle of the block pile area, which is the one
fixed point in the job, and the place the character comes back from before every single trellis. The
finished field is then always *behind* it: nothing is ever reached over, nothing has to be walked round,
and it never builds itself into a corner it cannot walk out of.

The reference has to be that fixed point and not the character. It ends every trellis standing beside
the one it just built, so "farthest from me" flips to the other end of the tile each time and walks it
back and forth across its own work.

**It never stands where the trellis goes.** The character's own body occupies the ground it stands on
and the server refuses a placement whose ground is occupied — so walking onto the spot means every
placement there is refused, which looks exactly like a bad footprint and is not. It stands a tile clear
of the spot, on the **supply side** of it — so what it has already built is beyond the spot rather than
behind its back — and the placement click does the last step, as it does for a player placing a building
from where they happen to stand.

A tile is cut into slots as soon as the footprint is known, so the walk that follows aims at the slot
rather than at the middle of the tile. Only the very first tile after a client start is walked to
before its slots can be worked out — there is nothing else to aim at until a ghost has been seen once.

A slot with a trellis or a building site already standing in it is passed over.

## The pathfinding

`s:player():move(p)` is a click on the ground: the server walks the character at it in a straight line
until something stops it. There is no path verb, so the route is A* over the tile lattice, in Lua, and
the waypoints go to that verb one at a time.

**It is a move that finds its own way, not a tour of tiles.** Two doors — `walkTo(place, reach)` and
`clickGob(gob, button)` — are the only ones the rest of the addon uses, and it names the *thing* it is
going to: the slot the trellis goes in, the pile it is drawing from. The lattice is how the way round
is worked out, never where the character is asked to stand. Tile centres are only ever intermediate,
a line-of-sight pass drops every one that can be walked straight past, and the last waypoint is always
the destination itself — so on open ground the whole route is one order, straight at it.

Two things are deliberately *not* obstacles. **What you are walking to**: a stockpile is wider than the
tile it stands in, so counting its own footprint would wall it off and leave the route stopping a tile
short and picking its way in by tile centres. **The tile you are standing in**: you are standing there,
so the question is settled — that one tile only, since an object is not excused everywhere else it
reaches for happening to touch this tile.

**How close is close enough is read off the object too.** An object is not a point: a stockpile is a
whole tile across, so its middle is somewhere the character can never get to — it collides with the
pile at the edge, plus its own body, and stands there ordering itself forward until the leg is given
up. The stop distance is the widest reach of the object's own rings plus a stand-off for the body. And
near enough while no longer getting nearer *is* near enough: the click goes out from there, because a
click on something out of arm's reach is one the server answers by walking the character the last step
itself — what a player's click across the yard does too.

**A character that will not move steps aside.** Nothing on the lattice explains one: a tile is eleven
units of "clear" or "blocked", and the thing actually in the way is inside the tile being stood in —
the trellis just built four units to the south. Another route down the same line would be the same
line, so the walk moves across the way out and carries on from there.

What the search knows about a tile is what the client can actually be asked:

- **the terrain** — `s:world():tile(p)`; a tile whose resource name reads as water is not walked on, and
  neither is ground the character is not streaming, since nobody has looked at it;
- **the height** — `s:world():height(p)`; two neighbours more than 2.5 units apart are a cliff, not a step;
- **what stands there** — `gob:hitbox()` for everything within 220 units, rasterised edge by edge.

The route is recomputed every three seconds as new ground streams in, corners are cut out of it by a
line-of-sight pass, and a leg that has not got nearer its target in twelve seconds is given up with a
line saying so. Diagonals never cut the corner between two blocked neighbours.

## What it cannot see, and what it does instead

- **A footprint is not the clearance.** The ring the server checks before it will let you place a
  building is a different one, and no verb answers it. So a slot the arithmetic likes may still be
  refused: the bot places, looks for the site that should have appeared, and takes the next slot when
  none did. Those show in the closing line as skipped.
- **A stockpile states nothing about its contents.** The window is opened with a right-click and one
  item is drawn out at a time with the pile's own `xfer` — the shift-click's message. Whether that
  landed is read off the backpack. A pile that gives nothing for three seconds ends the trip, and ends
  the job when what the character carries is not enough for one more trellis.
- **One trellis per trip.** `BATCH` at the top of `main.lua` is how many trellises' worth of material
  one trip fetches; it is 1, so the backpack never fills and the round trip is short. Raise it and the
  walking drops at the cost of carrying more.
- **The window it opens is the first one that matches.** The pile is always clicked before a window is
  looked for, so a Stockpile window left open by the previous trip is not mistaken for this one — but
  with two open at once on the same character, there is nothing in the client that tells them apart.

## Notes

- A building ghost left on the cursor is taken off for you, on Stop and before any trip to a pile.
- The job runs on the character it was started on. Tab away and it waits, saying so, rather than acting
  on somebody else.
- Permissions: it walks, clicks objects, places buildings, uses one action-menu entry, and sends two
  messages the client itself sends — a pile's withdrawal and the Build button's press. It never picks an
  item up and never touches the cursor. The client asks once, when it is enabled.
