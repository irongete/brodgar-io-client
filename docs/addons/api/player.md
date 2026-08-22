# session:player: one of your characters

`s:player()` is the **Player object** for the character one of your [sessions](session.md) is playing. Its
main job is being the anchor for that character's own [Gob](gob.md).

```lua
local s = hafen.session():current()       -- the character on screen
local me = s and s:player():gob()         -- nil until that session is in the world
local p = me and me:position()
if p then hafen.log():write("standing on grid " .. p:info().gridId) end
```

Player deliberately forwards **nothing** from the Gob: position, health, movement and facing are read
on `s:player():gob()`, so there is exactly one way to reach each of them. What lives on Player is
only what has no per-gob equivalent.

## Which character

`s:player()` answers for the session you name and no other, so an addon watching two characters says which
one it means. `hafen.session():current():player()` is the one on screen and
`hafen.session():get("alt"):player()` is another; both hand back the same object every call, so a per-frame
read allocates nothing.

**The character it is playing is [`s:character()`](session.md#read)**, on the Session — one account plays one
character at a time, and the Session is what names the account. There is no `:name()` here: that would be a
second spelling of one fact whose only difference was which door you came through.

Every read below answers for the session named, and so does the walk — `move` reaches a character nobody
is looking at. What does not is what belongs to the **screen**, and each of those says so where it is
described: `worldToScreen` answers a point on it, and there is one screen however many characters are
logged in, while [`hand:use`](#the-hand) is a gesture with the pointer.

## Read

| Method | Returns | Description |
|---|---|---|
| `s:player():gob()` | [Gob](gob.md) \| nil | that character's own game object; `nil` before that session is in the world |
| `s:player():worldToScreen(p)` | `{x, y}` \| nil | project a [Position](position.md) to a screen point, in root [design pixels](ui/pixels.md); `nil` unless that session is on screen |

`s:player():gob()` is the same object as `s:world():gob():get(<that character's id>)` — so
`gob == s:player():gob()` is how you tell "is this that character?" from any other gob read through the same
session, with no id comparison. Neither read is protected.

`worldToScreen` takes a place in the world and answers a **root** screen point, in
[design pixels](ui/pixels.md) — the one space [the mouse](ui/mouse.md), `hafen.ui():hit(x, y)`,
[`widget:rootPos()`](ui/widget.md#read) and a [HUD overlay's](ui/custom.md#overlays) painter already share.
So the pair goes straight into a [`g:` verb](ui/drawing.md) or a hit test with nothing in between, at any
interface scale.

What comes back is not a Position: a pixel is not a place in the world, and only the direction that has an
answer will type-check. It answers `nil` before the map view exists, for a point the view cannot project, and
for a session that is not on screen — a projection through a scene nobody is drawing would name a pixel you
cannot use and cannot tell apart from one you can. Anything that is not a Position going in is an error, and
so is a Position **that** character cannot reach. The inverse is
[`s:world():screenToWorld`](world.md#screen-to-world-and-placement-snapping), which takes that same space
back.

> **It projects at that character's height, not at the ground under `p`.** A Position names two axes, and
> the third comes from where the character is standing — so for a spot up a hillside the point you get is
> where that spot would be at *its* altitude, and a raycast back down does not return to it. It is exact on
> ground level with that character's own feet, which is what a marker above a gob or a label beside one wants.

> There is no `exists()` and no `id()` on Player: `s:player():gob()`, `nil` or not, and `gob:id()`
> answer both questions.

The hp, stamina and energy bars are not here. They are a HUD slot the server fills rather than
per-player state, so they live in [`session:meter`](meter.md).

## Write (protected)

### `s:player():move(p)`

Walk that character to a [Position](position.md) — the click a left-click on that patch of
ground sends, so an **off-screen destination is fine**. Returns the Player, so a move chains. It needs the
`player.move` [permission key](../guides/permissions.md) declared in your manifest; without it the call
raises an error naming that key, before anything is sent.

**It reaches the session you named, drawn or not.** This is the one write on the whole API that does:

```lua
-- send everyone else to where the character on screen is standing
local cur = hafen.session():current()
local here = cur:player():gob():position()
for _, s in ipairs(hafen.session():list()) do
  if s ~= cur then s:player():move(here) end
end
```

`p` is required. Anything that is not a Position raises, a plain `{x, y}` table and a
[widget's pixel position](ui/widget.md) included: a place in the world and a point on the screen are
different kinds of thing, and the verb refuses the wrong one rather than walking a character somewhere else.
A Position **that** character cannot locate raises too, naming it — the destination is worked out against
the map of the character you addressed, so the same place may be perfectly reachable for another of them.
Before that session is in the world there is no map view, and it raises saying so. Nothing is sent in any of
those cases.

> **Walking is the whole of what a character you are not looking at will take.** Everything else a click can
> mean — [clicking an object](world.md#write-protected), [placing](world.md#write-protected) what is on the
> pointer, an area select, [applying a held item](#the-hand) — belongs to the character on screen and raises
> naming `hafen.session():current()` for any other. That line is the client's own: an order carries a
> destination and never a target.

**Your own [action handlers](event/streams.md) see the order to the character on screen, and not the
others.** An order to [`hafen.session():current()`](session.md) leaves by the same door a real click does, so
a `hafen.event():action():on("click", fn)` handler intercepts it, rewrites it or cancels it exactly as it
would a click of your own. An order to any other session bypasses that chain: it belongs to the character
being drawn and knows nothing about the one being walked, so a handler reading the destination would be
reading a place named in another session's frame. Order the drawn character if you want your own hooks to
run.

There is no `gob:move()` beside it. The server accepts a walk command for that character's **own** body
only, so there is nothing a general Gob could do with the verb; [`gob:moving()`](gob.md#read) is the other
direction, a property of any gob rather than an order to one.

## The Hand

`s:player():hand()` is **that character's cursor**: the Hand it is carrying something on, and **`nil`
whenever it is not. That `nil` is the point** — `if h then h:use(x) end` is the guard, and there is no state
in which you are holding nothing and a held-item action still means something.

The cursor is per character: one you are not looking at can perfectly well be carrying something, and
tabbing to it is picking that up. Reading is therefore addressed like everything else here; `use` **sends**,
so it is the drawn character's.

```lua
local s = hafen.session():current()
local h = s:player():hand()
if h then
  hafen.log():write("carrying " .. (h:item():name() or h:item():res() or "?"))
  h:use(s:world():gob():nearest("terobjs/plants"))     -- apply it to that plant
end
```

| Call | Returns | Description |
|---|---|---|
| `s:player():hand()` | Hand \| nil | that character's cursor while something is on it, `nil` while it is empty |
| `hand:item()` | [`Item`](ui/items.md#the-item-object) \| nil | what it is carrying |
| `hand:use(target, mods)` | the Hand | **protected**, `player.hand.use` — apply what it is carrying to `target` |

`hand:use` is the one nested key in the catalogue: `player.hand.use` grants it exactly, and so does the
group `player.*` — which grants `player.move` with it. Declare `player.hand.*` for the held-item gesture
alone.

The two reads are not protected and neither throws. `s:player():hand()` hands back the same object every
call for as long as you keep the Player, so `==` works and there is nothing to release; it is the *cursor*
rather than a snapshot of it, so one you kept across a drop answers `nil` from `:item()` instead of naming
what it was carrying. Read it again rather than holding one.

**Taking an item does not carry your handle onto the cursor.** The client destroys the container's item
and builds a new one in the hand, so the [`Item`](ui/items.md#the-item-object) you held goes stale and
`hand:item()` is a different object. Read the hand for what is on the cursor.

### `s:player():hand():use(target, mods)`

Apply what that character is carrying **to** something. `target` dispatches by type, and the three types are
the three the client itself has:

| `target` | What it does |
|---|---|
| an [`Item`](ui/items.md#the-item-object) | apply it onto that item, wherever the item is |
| a [Position](position.md) | apply it to the ground there |
| a [Gob](gob.md) | apply it to that object — the waterskin onto the plant, not onto the dirt beside it |

`mods` is optional and defaults to `0`: a bitfield, Shift = 1, Ctrl = 2, Alt = 4, added together — and
optional is not unchecked, so a value that is not a number raises naming the verb and the parameter, and one
that merely scans as a number is
[still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). It
returns the Hand, so a run of uses chains.

> **`use()` with no target raises**, naming the three types, and it is not how you activate what you are
> holding. Every held-item action targets something; activating is
> [`s:player():hand():item():use()`](ui/items.md#write-protected).

It raises with an empty cursor, with no map view, for a session that is not on screen, and for a target that
has gone — an item that was moved, used or consumed, or a gob that left view. Nothing is sent in any of those
cases.

## See also

- [`hafen.session`](session.md) — the address this hangs off, and the character it is playing
- [Gob](gob.md) — everything positional about that character
- [items](ui/items.md) — the Item the hand carries, and the verbs on one in a container
- [`session:meter`](meter.md) — the HUD bars
- [`session:char`](char.md) — attributes, skills and food
- [`session:world`](world.md#screen-to-world-and-placement-snapping) — `screenToWorld`, the inverse projection
