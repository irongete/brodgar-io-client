# Session Manager

One row per login the client holds. Each row is the character's name — the account before that
character is in the world — and the row for the session on screen is marked `*`.

| You do | It does |
|---|---|
| press a row's name | hands the screen to that character |
| press a row's `X` | logs that character out |
| press `New session` | goes to the client's login screen, with every character still logged in behind it |
| press the `Select next session` hotkey | goes to the next login, and round |
| press the `Select character` hotkey, then click | the pointer becomes a hand; click a character (its model or its base) to go to it |
| press the `Focus selection` hotkey | centres the view on your character — on the `rts` camera, which is the only one with a centre to move |
| look at the map | a disc of coloured ground with a solid line round it under every character: bright green under the one you are looking at, fainter under the others |
| type `:sessions` | opens the window, or closes it |

## Logging another account in

`New session` shows the client's own login screen, which is live behind every session. **Nothing is logged
out**: your characters go on running, and a row of this window brings you back to one whether or not you
logged anything in. Whatever you do log in there joins the list as a session like any other and takes the
screen, so this is the door for an account this client has never logged in — `:session add`, the console's
own, can only reach an account whose token the login screen has already saved.

*Suggested keys — assign them in Options ▸ Keybindings ▸ Session Manager:* `Ctrl+Tab` for **Select next session**,
`Ctrl+Q` for **Select character**, `Ctrl+Space` for **Focus selection**.

An addon hotkey starts unbound, because the client gives one key to one action and an addon that
claimed a key already in use would lose it and leave you with a hotkey that never fires. So the keys
above are suggestions, and yours is the assignment.

## What it asks for

`session.close`, the permission behind the `X` — *log out any of your characters*. The client asks
you to approve it the first time you enable the addon; nothing else here leaves your client.

## Where it keeps its place

Where you drag the window is saved for the **account**, not for the character on screen, so the
switcher stands in the same place whichever character you are looking at and after a restart. The
window itself lives above every session, so moving the screen does not move it, rebuild it, or make
it blink.

## Picking a character with the mouse

The client owns no gesture for this — no modifier, no button. **Select character** arms a pick instead: the
pointer becomes a hand, and the next click on the map names whoever it landed on. Two things count as
naming somebody, and the base is asked first — not by an arrangement here, but because the client
hit-tests a base against its own shape inside the press itself, before the pick pass an ordinary click
rides on has even started:

- **the base under a character** — the very ground the disc is coloured in, whether or not you can see that
  ground: behind a hill, under a house, on the far side of a wall
- **the character's own model** — the client's own pick pass, so it is the model that has to be under the
  cursor and not a radius around its feet

Whichever it was, the click is **consumed**: an armed pick that also walked your character somewhere is a
pick nobody would use. A click that names nobody disarms and is let through, so a miss costs one click and
never a stuck mode — and pressing the key again while armed is *never mind*.

A base takes clicks **only while a pick is armed**. A base that took them all the time would eat every
click on the ground a character happens to be standing on, which is not a marker anybody wants.

Because nothing of this is a modifier the client holds, `Alt`+click and every other combination over the
map stay yours to use for something else.

## The base under each character

A disc of coloured ground with a solid line all the way round it at the character's feet, the way a figure
stands on a plinth: bright green under the one you are looking at, fainter under the others.

It is not drawn *over* the ground — it **is** the ground, re-laid in its own colour. So it follows a slope,
a ridge and a tile boundary exactly, with no gap and no shimmer; whatever stands on it hides it, your own
character included; and its edge is the disc's own shape at every zoom rather than a staircase of tiles.
Nothing about it names a camera, which is why it is right in every camera the client has.

**The line is that edge, drawn.** It is carved out of the disc rather than laid over it, so it is the same
shape at every zoom, and it is never thinner on screen than a pixel — a base zoomed all the way out is
still ringed. The ground inside stays see-through and the line stays solid, which is the whole difference
between a plinth and a smudge: you can read the terrain the character is standing on and still see where
the character is.

It is laid on the **body** rather than at a place, so it is re-laid wherever that body walks and it goes
when the body does. And it is the shape it was laid as: the faint one is the same disc scaled down, because
what a base is made from cannot be changed afterwards.

The client draws none of it. It holds the selection and knows whose screen this is; what that *looks* like
is the block in the middle of `main.lua` — two colours, the opacity of the ground under each, the line's
thickness and the radius. The last two are in **world** units rather than pixels, so a base keeps its size
on the ground as you zoom.

It appears only with two or more logins, and only for a character the one on screen can see — which is also
the only character a base could be drawn for. The scene on screen carries every session's own ground, so
that is normally all of them; one that arrives late is laid within a quarter-second of arriving.
