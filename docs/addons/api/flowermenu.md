# session:flowermenu: the radial menu

The **radial menu** is the ring of petals a right-click puts up: the game's main context gesture, and the
way almost every interaction with an object starts. `s:flowermenu()` **is** the menu one character has
open — what it offers, how many petals that is, which one to pick, and whether the client paints it at
all. You reach it through the [session](session.md) whose character you mean. Picking and dismissing are
protected and their keys are in [permissions](../guides/permissions.md);
[painting the ring](#drawn-or-not-unprotected) is not.

```lua
hafen.event():on("FlowerMenuAdded", function(petals, s)
  local names = {}
  for i, p in ipairs(petals) do names[i] = p:label() end
  hafen.log():write(s:user() .. " menu: " .. table.concat(names, ", "))   -- Chop, Pick branch, …
end)

hafen.event():on("FlowerMenuRemoved", function(label)
  hafen.log():write(label and ("picked " .. label) or "cancelled")
end)
```

## A menu is one character's, not the screen's

A right-click is a mouse gesture and the client has one pointer, so a ring only ever goes **up** on the
character you are looking at. But the section is the open **menu**, and a menu is a widget in one
character's own window tree — not the gesture that raised it. Tab to another character with a ring still
up and it is still up: still readable, and still pickable.

```lua
local menu = hafen.session():get("alt"):flowermenu()     -- the ring that character left open
if menu:count() > 0 then
  for _, p in ipairs(menu:list()) do hafen.log():write("the alt is offered: " .. p:label()) end
end
```

Every other character answers exactly what it answers with nothing open, which is the ordinary state: an
empty array and `0`. A handler that wants the ring the event is about needs no lookup at all: the two
events hand it that character's session as their last argument.

## Read

| Call | Returns |
|---|---|
| `s:flowermenu():list(filter)` | the [Petals](#a-petal), in ring order — empty when that character has no menu open |
| `s:flowermenu():count()` | how many petals are on the ring; `0` when none is open |
| `s:flowermenu():get(n)` | the petal at that **1-based** ring position; `nil` for a number past the ring, and an error for a number that is not a whole one |
| `s:flowermenu():find(filter)` | the first petal whose caption matches the [filter](conventions.md#the-filter-argument); `nil` for none |
| `s:flowermenu():gob()` | the object the ring was opened on, or `nil` |

None of them throws for an empty ring: no menu being open is the ordinary state of the game rather than an
error, and every one of them answers before that character has entered the world. `:get(n)` is the one that
can raise, and only on an argument that is not a whole number — the position is the same one `petal:index()`
answers with and the ring's own `1`–`9` keys take. Every read here is unprotected.

## Which object the menu belongs to

`s:flowermenu():gob()` is the [Gob](gob.md) that was right-clicked to put the ring up — the tree you are
about to chop, the animal you are about to butcher. It is what turns a list of captions into a decision an
addon can make, and it **answers in the login you asked through** — that character's own world, which is
where the click happened and where the id came from.

Where that answer comes from is worth knowing, because it is what decides when there is none. **The menu
carries no object of its own**: what arrives is a list of captions and nothing else, so the client works
out which object a ring belongs to by matching it against the click that opened it. The match is exact —
it is the press the ring is drawn at — but it can only vouch for a menu that a click on an object put up.
Everything else answers `nil`:

- a menu opened from an item in your inventory, which is a click on a window and not on the world;
- the Kin window's own menu, and every other menu the client puts up for itself;
- a menu that was not opened by the click it would have been matched to — you clicked something else in
  between, or the menu arrived long after the click that asked for it.

Read it from inside a `FlowerMenuAdded` handler, which is where you need it. Like the other two reads it
keeps answering while the ring fades, and it is `nil` once the ring is gone.

```lua
hafen.event():on("FlowerMenuAdded", function(petals)
  local gob = hafen.session():current():flowermenu():gob()
  local name = gob and gob:name()
  if name and name:find("tree") then
    for _, p in ipairs(petals) do hafen.log():write("a tree offers: " .. p:label()) end
  end
end)
```

## A petal

A petal is an object like every other member of a set here, and it **belongs to the ring it came off**: a
petal carries that menu, so one held past the close reports `:exists()` false rather than pointing at
whatever is on screen now, and picking it raises rather than committing another ring's petal at the same
place. A right-click puts a new menu up about a second later, so that difference is a whole second wide.

| Method | Returns | Description |
|---|---|---|
| `petal:label()` | string \| nil | the caption its ring paints; `nil` once that ring has closed |
| `petal:index()` | number | its **1-based** place on the ring — always answers |
| `petal:wire()` | number | the **0-based** number the menu itself sends for it — always answers |
| `petal:select()` | the petal | pick it — **protected**, `flowermenu.select`; raises once its ring has closed |
| `petal:exists()` | boolean | whether the ring this petal is on is still the open one |
| `petal:info()` | [`Petal`](types/ui.md#petal) | a plain-table **snapshot** |

A petal's **position** is real identity here, not an artefact of one call's ordering: it is the `1`–`9` key
the menu itself accepts from the keyboard, and the number
[`s:flowermenu():select(n)`](#write-protected) takes. The wire counts the ring from zero, so
`petal:wire()` is the number the client puts in the message and `petal:index()` is the one everything in
this API is written in. This is the opposite of
[`session:menugrid`](menugrid.md), where a position means nothing and is refused — there the catalogue grows
as you play, and here the ring is frozen the instant it opens.

The ring is fixed from the moment it appears and lives about as long as it takes to decide, so a petal is
worth reading rather than keeping — but it is a handle, so keeping one is safe and says so.

> **The menu answers while it is open, closing animation included.** A pick or an Esc starts a
> quarter-to-three-quarter-second fade, and the ring is still there for it — so a `:count()` read from
> inside a `FlowerMenuRemoved` handler is not yet `0`. Read what you need from the event's own payload.

**Two menus at once on one character** should not happen — an open menu grabs the mouse and the keyboard,
which is what makes "the open menu" a well-defined thing for that character. If it ever does, these verbs
answer for the first one that character's tree is holding.

## Write (protected)

A write goes out **once per frame at most**; a second in the same frame raises. The client sends only
shapes a player could compose, and what the server does with more than that is the server's.

| Method | Key | Description |
|---|---|---|
| `s:flowermenu():select(label)` | `flowermenu.select` | pick the petal captioned `label`, matched whole and case-insensitively, and hand the section back |
| `s:flowermenu():select(n)` | `flowermenu.select` | pick the petal at position `n` on the ring, counting from `1`, and hand the section back |
| `s:flowermenu():cancel()` | `flowermenu.cancel` | close the menu with nothing chosen, exactly as Esc does, and hand the section back |

Picking and dismissing are separate keys, so an addon may declare one without the other; the group
`flowermenu.*` covers both. Called from an addon that did not declare the key it needs, each raises an error
naming that key; see [the permission model](conventions.md#the-permission-model). **One key covers every
character**: a key names the action, and the player could have tabbed to that character and picked the
petal themselves.

A string is always a caption and a number is always a position, so
`s:flowermenu():select("3")` picks the petal captioned `3` and never the third one.

**A caption is the client's own English**, whatever the ring is painting. A [catalogue](locale.md) lands at
the render and nowhere above it, so `petal:label()`, the captions [the two events](#the-two-events) carry
and the spelling `:select(label)` matches all go on naming what you wrote, on a client an addon has
translated and on one it has not.

Both verbs go through the client's own selection, which is what makes them exact rather than
approximate: a petal the **client** handles by itself — the Kin window's entries, the mute toggle on
another player — is handled locally, and nothing is sent to the server for it.

Where the reads answer, these **raise**. A menu is up for about a second, so *there was nothing to pick*
is a race you have to hear about rather than a value you might forget to test. `:select` raises when that
character has no menu open, when no caption matches, when the position is outside `1`..the petal count, and
when the key is neither a string nor a number; `:cancel()` raises when no menu is open. Each of those
errors names the character asked about and lists the ring that **is** open, numbered, so the spelling you
missed is in the message.

You can pick from inside a `FlowerMenuAdded` handler, and that is the usual place. The ring is still
animating open at that moment — the one window a real click cannot use, because the menu swallows mouse
input until the animation finishes.

> **One ring takes one pick, and nothing marks it as taken.** Every addon subscribed to `FlowerMenuAdded`
> hears the same menu and may pick from it, the order between them is not defined, and the client sends
> each pick as it is made. So an addon that picks unconditionally decides the ring for every other one.
> Pick on a ring you recognise — `:gob()` says what it was opened on and the payload says what is in it —
> and read `FlowerMenuRemoved` for the label that was actually committed.

## Drawn or not (unprotected)

`s:flowermenu():visible(b)` says whether the client paints that character's open ring. It is the read/write
pair [`gob:visible(b)`](look.md#drawn-or-not-unprotected) is, with the radial menu where that one has an
object — and it is the one verb here that changes something and needs no key: it draws, or does not draw,
and the server is told nothing.

| Method | Returns | Description |
|---|---|---|
| `s:flowermenu():visible()` | bool \| nil | whether the open ring is painted; `nil` when that character has no menu open |
| `s:flowermenu():visible(b)` | the section | paint it, or stop painting it |

An addon that acts on [`FlowerMenuAdded`](#the-two-events) decides before the ring's first frame, so a ring
it was always going to pick from need never be painted at all:

```lua
hafen.event():on("FlowerMenuAdded", function(petals, s)
  for _, p in ipairs(petals) do
    if p:label() == "Pick" then
      s:flowermenu():visible(false):select("Pick")   -- it hands the section back, so this chains
      return
    end
  end
end)
```

> **A hidden ring is still open.** It is the same menu on the same character: `:list()`, `:count()` and
> `:gob()` answer what they answer painted, `:select(label)` still picks from it, and it still ends with a
> `FlowerMenuRemoved`. What changed is that nothing is drawn for it.

What it may not do is spend a click on a petal nobody could see. A hidden ring
[holds the mouse and the keyboard](#the-two-events) exactly as a painted one does — that is what leaves the
player their own way out of it — but a click on it can only ever **end** it, never pick from it: the ring
closes with nothing chosen, as clicking away from a painted one does. Its `1`–`9` keys do nothing at all,
and Esc is unchanged. So a ring an addon hid and then did not decide is never a trap: the two gestures a
player would have used anyway still dismiss it, and no click of theirs is spent blind.

That dismissal is not immediate. The ring [swallows mouse input while it animates open](#write-protected),
hidden or painted, so a click inside the first quarter second of a ring's life does nothing at all — hiding
one does not make it dismissible any sooner.

`b` must be `true` or `false`, and anything else raises naming the argument — a number most of all, since in
Lua `0` is a true value and would quietly paint a ring you meant to hide. The read answers `nil` with no menu
open; the **write raises** there, naming the character, exactly as [picking and cancelling](#write-protected)
do — there is nothing to hide, and a write that quietly did nothing is a race an addon never hears about.

`:visible(true)` paints a ring mid-life, from the next frame. The flag is the ring's own and dies with it, so
there is nothing to put back: the next menu that character opens is painted, and so is every menu on every
other character. Picking, though, is a message to the server rather than a close, so a ring you picked from
is still up while that goes out and comes back — `:visible(true)` straight after a `:select()` paints
exactly that round trip.

## The two events

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuAdded` | [`Petal`](#a-petal)`[]` — the ring, in ring order | a radial menu appears |
| `FlowerMenuRemoved` | `string` \| nil — the label picked | that menu goes away |

**Every `FlowerMenuAdded` is followed by exactly one `FlowerMenuRemoved`.** That holds however the menu
ended: you picked a petal, you pressed Esc, you clicked away, or it simply died under you when the
connection dropped. The payload is the label on a pick and `nil` on everything else.

`FlowerMenuAdded` fires at the one moment the petal set is complete, so the array it carries is the whole
ring — the same Petals `s:flowermenu():list()` answers with inside the handler, where `s` is the
[session the event carries](event/bus/README.md#whose-character-it-was).

Both events cover the menus the **client** puts up as well as the server's. The Kin window's right-click
menu is one of those: it never reaches the server at all, and it still opens and closes here.

```lua
local pending
hafen.event():on("FlowerMenuAdded", function(petals)
  pending = petals
end)
hafen.event():on("FlowerMenuRemoved", function(label)
  if not label and pending then
    local names = {}
    for _, p in ipairs(pending) do names[#names + 1] = p:label() or "?" end
    hafen.log():write("walked away from: " .. table.concat(names, ", "))
  end
  pending = nil
end)
```

An open menu holds the mouse and the keyboard, so nothing you type reaches the client while one is up: a
console command or a hotkey cannot be the thing that reacts to a menu. A handler on these events, or a
[timer](timer.md) armed from one, is how an addon acts on a menu at all.

## See also

- [`hafen.session`](session.md) — the address the section is reached through
- [Gob](gob.md) — what `:gob()` hands you
- [`session:world`](world.md#write-protected) — `s:world():click(gob, 3)`, the right-click that puts the ring
  up
- [`session:menugrid`](menugrid.md) — the *other* menu: the catalogue of what a character can do
- [`hafen.event`](event/bus/character.md#the-radial-menu) — the bus these two events sit on, and every other
  key
