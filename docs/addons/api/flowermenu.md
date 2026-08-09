# hafen.flowermenu: the radial menu

The **radial menu** is the ring of petals a right-click puts up: the game's main context gesture, and the
way almost every interaction with an object starts. `hafen.flowermenu()` **is** the open menu — what it
offers, and how many petals that is.

```lua
hafen.event():on("FlowerMenuOpened", function(petals)
  hafen.log():write("menu: " .. table.concat(petals, ", "))   -- {"Chop", "Pick branch", …}
end)

hafen.event():on("FlowerMenuClosed", function(label)
  hafen.log():write(label and ("picked " .. label) or "cancelled")
end)
```

| Call | Returns |
|---|---|
| `hafen.flowermenu():list()` | the petal captions, as strings, in ring order — an empty array when no menu is open |
| `hafen.flowermenu():count()` | how many petals are on the ring; `0` when no menu is open |

Neither throws, ever: no menu being open is the ordinary state of the game rather than an error, and both
answer before you have entered the world. Both are ungated.

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
this one*; passing anything raises an error pointing at [`hafen.act():flower`](act.md#hafenactflowerlabel),
which is how a petal is chosen.

> **The menu answers while it is on screen, closing animation included.** A pick or an Esc starts a
> quarter-to-three-quarter-second fade, and the ring is still there for it — so a `:count()` read from
> inside a `FlowerMenuClosed` handler is not yet `0`. Read what you need from the event's own payload.

**Two menus at once** should not happen — an open menu grabs the mouse and the keyboard, which is what
makes "the open menu" a well-defined thing. If it ever does, these verbs answer for the first one the
client is holding.

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

- [`hafen.act`](act.md#hafenactflowerlabel) — picking a petal of the menu these events tell you about
- [`hafen.menugrid`](menugrid.md) — the *other* menu: the catalogue of everything your character can do
- [`hafen.event`](event.md#the-radial-menu) — the bus these two events sit on, and every other key
