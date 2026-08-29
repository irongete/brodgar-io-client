# Gob Cache Map

Every tree and boulder any of your characters walks past is written down. When you need one, open the
window, type a name, and pick a row: the map inside the window goes there, over a heatmap of everywhere
else the same thing is standing.

```text
:gobcache
```

Suggested key: `Ctrl+G` — assign it in Options ▸ Game ▸ Keybindings ▸ Gob Cache Map ▸ `toggle`.
An addon hotkey starts unbound, so nothing is claimed until you assign it.

## The window

| | |
|---|---|
| the text field | part of a species or a kind — `fir`, `gneiss`, `boulder`. Empty means everything |
| **Trees**, **Boulders** | which kinds count, in the list and in the heatmap alike |
| **Heat** | the heatmap on or off. Off, nothing is drawn *and* nothing is counted — the picture costs a walk of the whole cache, so switching it off switches that off too |
| the list | the nearest 200 hits, nearest first; `t` is tiles, `far` is another explored area |
| **Here** | look at the character on screen |
| **View** | aim the *3D* view at the picked row — the `rts` camera only, which is the only one with a centre of its own |

On the map itself: **click a mark** to pick it, **click anywhere else** to look there and let the pick go,
and **the wheel** zooms between 1, 2 and 4 tiles to the pixel. The white dot is the character on screen,
the crosshair is where the panel is pointed, and the yellow box is the row you picked.

A hit is drawn as **the game's own minimap icon** for that thing — the same little fir the corner map draws
— so the picture reads without a legend. Where the game draws no icon for something, the mark is a pip in
its kind's colour instead. That is not a guess about which icon belongs to what: the live object is asked
(`gob:icon()`), and the answer is looked up in the client's own icon registry.

The ground under all of it is the client's own minimap art, read out of the map database at its own
colours — so a place you have never explored is simply not drawn, and one you explored a year ago is drawn
as it was a year ago.

## What it writes down, and what it never writes twice

A tree does not move, so a tree **is** its place: the server's grid id plus the offset inside that grid,
which is what `Position:info()` hands back and the one anchor a segment merge never rewrites. The cache is
keyed on exactly that — `entries[<gridId>]["<x>,<y>"] = <kind>` — so the same oak seen on Monday, on
Tuesday, and out of a second character's eyes is one row, and finding that out costs no search at all.

A **different** resource at a place already held overwrites it, which is what a mined-out boulder looks
like from here.

Objects are read by a sweep every three seconds rather than from `GobAdded`, on purpose: subscribing to
that event makes the client hold *every* arriving object out of the scene until the handler has run, and a
cache has no use for immediacy worth that.

## Forgetting a felled tree

Standing within about five tiles of a place it holds and not seeing what it wrote there, three sweeps
running, the addon drops the row. Further off nothing is ever forgotten — an object you cannot see from
where you are is not gone, it is just not streamed in.

## Where the data lives

`savedata/account/gob-cache-map.json`, written by the engine.

An addon has no filesystem — the sandbox never installs `io` — so a file inside the addon's own folder is
not something an addon can write. A **saved variable** *is* that JSON file, and the account scope is the
right one here for the same reason the recorded map has it: there is one map database per world, and every
character you log in there reads and writes it.

The layout is small on purpose: resource names are held once in a `kinds` array and every object is one
number pointing into it, so a row costs about a dozen characters and a well-travelled cache is a few
hundred kilobytes.

## The console

| | |
|---|---|
| `:gobcache` | open and close the window |
| `:gobcache stats` | how much is held, over how many grids, and how many distinct resources |
| `:gobcache scan` | sweep now instead of waiting for the beat |
| `:gobcache clear yes` | throw the whole cache away |

## Widening it

`KINDS`, at the top of `main.lua`, is the whole of what the addon considers. A kind is a substring of the
resource name — that is the only question the client can be asked about a game object — and adding a row
there is enough: the cache, the filter, the list, the marks and the heatmap all read that one table.

```lua
{ key = "bush", label = "Bushes", match = "terobjs/bushes/", color = { 120, 180, 90 } },
```

The list shows the species drawn from the resource name, so anything unexpected the match sweeps in is
visible on its own row rather than hidden in a total.

## What it does not do

It does not move the client's **own** map window. Nothing in the addon API aims that window at a place, so
the map that goes where you point it is the one in this window. `View` is the other half of the answer:
it aims the 3D view, which the API does reach, under the one camera that has a centre to aim.

It needs no permissions — it reads the world and the map database, and writes only its own saved file.
