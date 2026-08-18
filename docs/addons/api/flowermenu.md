# hafen.flowermenu: the radial menu

The **radial menu** is the ring of petals a right-click puts up: the game's main context gesture, and the
way almost every interaction with an object starts. `hafen.flowermenu()` **is** the open menu — what it
offers, how many petals that is, and which one to pick.

```lua
hafen.event():on("FlowerMenuOpened", function(petals)
  hafen.log():write("menu: " .. table.concat(petals, ", "))   -- {"Chop", "Pick branch", …}
end)

hafen.event():on("FlowerMenuClosed", function(label)
  hafen.log():write(label and ("picked " .. label) or "cancelled")
end)
```

## Read

| Call | Returns |
|---|---|
| `hafen.flowermenu():list()` | the petal captions, as strings, in ring order — an empty array when no menu is open |
| `hafen.flowermenu():count()` | how many petals are on the ring; `0` when no menu is open |
| `hafen.flowermenu():gob()` | the object the ring was opened on, or `nil` |

None of them throws, ever: no menu being open is the ordinary state of the game rather than an error, and
all three answer before you have entered the world. All three are unprotected.

## Which object the menu belongs to

`hafen.flowermenu():gob()` is the [Gob](gob.md) you right-clicked to put the ring up — the tree you are
about to chop, the animal you are about to butcher. It is what turns a list of captions into a decision an
addon can make.

Where that answer comes from is worth knowing, because it is what decides when there is none. **The menu
carries no object of its own**: what arrives is a list of captions and nothing else, so the client works
out which object a ring belongs to by matching it against the click that opened it. The match is exact —
it is the press the ring is drawn at — but it can only vouch for a menu that a click on an object put up.
Everything else answers `nil`:

- a menu opened from an item in your inventory, which is a click on a window and not on the world;
- the Kin window's own menu, and every other menu the client puts up for itself;
- a menu that was not opened by the click it would have been matched to — you clicked something else in
  between, or the menu arrived long after the click that asked for it.

Read it from inside a `FlowerMenuOpened` handler, which is where you need it. Like the other two reads it
keeps answering while the ring fades, and it is `nil` once the ring is gone.

```lua
hafen.event():on("FlowerMenuOpened", function(petals)
  local gob = hafen.flowermenu():gob()
  local name = gob and gob:name()
  if name and name:find("tree") then
    hafen.log():write("a tree offers: " .. table.concat(petals, ", "))
  end
end)
```

## Petals are labels, not objects

Everywhere else in this API a member of a set is a live object you keep and re-read. A petal is not. A menu
is put up by the server, its petals are fixed from the moment it appears, and it lives for about as long as
it takes to decide — so there is nothing for a handle to track, and `:list()` hands you plain strings.

That has one consequence worth stating: the array is a **reading**, not a subscription. Take it while the
menu is up, and take it again next time.

A petal's **position** in that array is real identity here, not an artefact of one call's ordering: it is
the number the client sends when you pick that petal, and it is the `1`–`9` key the menu itself accepts
from the keyboard. This is the opposite of [`hafen.menugrid`](menugrid.md), where a position means nothing
and is refused — there the catalogue grows as you play, and here the ring is frozen the instant it opens.

`:list()` takes **no filter**. There is no field to match on, and a string argument would read as *pick
this one*; passing anything raises an error pointing at `:select`, which is how a petal is chosen.

> **The menu answers while it is on screen, closing animation included.** A pick or an Esc starts a
> quarter-to-three-quarter-second fade, and the ring is still there for it — so a `:count()` read from
> inside a `FlowerMenuClosed` handler is not yet `0`. Read what you need from the event's own payload.

**Two menus at once** should not happen — an open menu grabs the mouse and the keyboard, which is what
makes "the open menu" a well-defined thing. If it ever does, these verbs answer for the first one the
client is holding.

## Write (protected)

| Method | Key | Description |
|---|---|---|
| `hafen.flowermenu():select(label)` | `flowermenu.select` | pick the petal captioned `label`, matched whole and case-insensitively |
| `hafen.flowermenu():select(n)` | `flowermenu.select` | pick the petal at position `n` on the ring, counting from `1` |
| `hafen.flowermenu():cancel()` | `flowermenu.cancel` | close the menu with nothing chosen, exactly as Esc does |

Picking and dismissing are separate keys, so an addon may declare one without the other; the group
`flowermenu.*` covers both. Called from an addon that did not declare the key it needs, each raises an error
naming that key; see [the permission model](conventions.md#the-permission-model).

A string is always a caption and a number is always a position, so
`hafen.flowermenu():select("3")` picks the petal captioned `3` and never the third one.

Both verbs go through the client's own selection, which is what makes them exact rather than
approximate: a petal the **client** handles by itself — the Kin window's entries, the mute toggle on
another player — is handled locally, and nothing is sent to the server for it.

Where the reads answer, these **raise**. A menu is up for about a second, so *there was nothing to pick*
is a race you have to hear about rather than a value you might forget to test. `:select` raises when no
menu is open, when no caption matches, when the position is outside `1`..the petal count, and when the
key is neither a string nor a number; `:cancel()` raises when no menu is open. Each of those errors
lists the ring that **is** open, numbered, so the spelling you missed is in the message.

You can pick from inside a `FlowerMenuOpened` handler, and that is the usual place. The ring is still
animating open at that moment — the one window a real click cannot use, because the menu swallows mouse
input until the animation finishes.

## The two events

| Event | Payload | Fires |
|---|---|---|
| `FlowerMenuOpened` | `string[]` — the petal captions, in ring order | a radial menu appears |
| `FlowerMenuClosed` | `string` \| nil — the label picked | that menu goes away |

**Every `FlowerMenuOpened` is followed by exactly one `FlowerMenuClosed`.** That holds however the menu
ended: you picked a petal, you pressed Esc, you clicked away, or it simply died under you when the
connection dropped. The payload is the label on a pick and `nil` on everything else.

`FlowerMenuOpened` fires at the one moment the petal set is complete, so the array it carries is the whole
ring — the same array `hafen.flowermenu():list()` answers with if you call it from inside the handler.

Both events cover the menus the **client** puts up as well as the server's. The Kin window's right-click
menu is one of those: it never reaches the server at all, and it still opens and closes here.

```lua
local pending
hafen.event():on("FlowerMenuOpened", function(petals)
  pending = petals
end)
hafen.event():on("FlowerMenuClosed", function(label)
  if not label and pending then
    hafen.log():write("walked away from: " .. table.concat(pending, ", "))
  end
  pending = nil
end)
```

An open menu holds the mouse and the keyboard, so nothing you type reaches the client while one is up: a
console command or a hotkey cannot be the thing that reacts to a menu. A handler on these events, or a
[timer](timer.md) armed from one, is how an addon acts on a menu at all.

## See also

- [Gob](gob.md) — what `:gob()` hands you, and `gob:click(3)`, the right-click that puts the ring up
- [`hafen.menugrid`](menugrid.md) — the *other* menu: the catalogue of everything your character can do
- [`hafen.event`](event/bus.md#the-radial-menu) — the bus these two events sit on, and every other key
