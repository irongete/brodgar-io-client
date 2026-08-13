# Reading the world

Everything around your character is a **game object** — a tree, a boulder, an animal, another player's
body — and reading them is unprotected: no permission, no declaration, nothing to ask for. This guide finds
objects, reads one, and asks what the ground under them is.

## Find the objects you want

[`hafen.world`](../api/world.md) scans what the client has loaded, and every one of its verbs takes the
same [filter](../api/conventions.md#the-filter-argument): nothing, a substring of the resource name, or a
predicate.

```lua
local trees   = hafen.world():gob():count("terobjs/tree")            -- how many, by name
local nearest = hafen.world():gob():nearest("terobjs/tree")          -- the closest one, or nil
local players = hafen.world():gob():within(50, function(g)      -- everything matching, in a radius
  return g:isPlayer()
end)
```

A **name is a resource path**, not a display name: `"gfx/borka/body"` is any player body and
`"terobjs/tree"` is every kind of tree, because the match is a substring. When you do not know the name,
log what is around you once and read the list:

```lua
hafen.slash():register("what", function()
  for _, g in ipairs(hafen.world():gob():within(15)) do
    hafen.log():write(g:name() or "?")
  end
end)
```

## Read one

What you get back is a [Gob](../api/gob.md), and a Gob is a **live object**: it holds an id and re-resolves
on every call, so one you keep in a variable tracks its object as it moves and answers `nil` once the
object is gone. Nothing goes stale, and nothing has to be refreshed.

```lua
local tree = hafen.world():gob():nearest("terobjs/tree")
if tree and tree:exists() then
  local p = tree:position()
  hafen.log():write(("tree at %.0f, %.0f, %.1f away"):format(p:x(), p:y(), tree:distance()))
end
```

Two Gobs for the same object are the **same value**, so `==` compares them and a table can be keyed by one
directly — which is how you remember what you have already seen without juggling ids.

## Do not scan every frame

A sweep of every loaded object is cheap once and expensive sixty times a second. Prefer the
[events](../api/event.md#world) `GobAdded` and `GobRemoved`, which hand you the Gob as it arrives, and
keep your own index:

```lua
local boars = {}

hafen.event():on("GobAdded", function(gob)
  if (gob:name() or ""):find("boar") then boars[gob] = true end
end)

hafen.event():on("GobRemoved", function(gob)
  boars[gob] = nil                     -- the gob is already gone here: only :id() answers
end)
```

When you do have to poll — a value with no event behind it — poll on a [timer](events-and-timers.md), not
in `Update`.

## Your own character

[`hafen.player()`](../api/player.md) is the anchor, and everything positional about you is read on your own
Gob, exactly as it is on any other object:

```lua
local me = hafen.player():gob()          -- nil until you are in the world
if me then
  local p = me:position()
  hafen.log():write(("standing at %.0f, %.0f"):format(p:x(), p:y()))
end
```

`gob == hafen.player():gob()` is how a filter or a handler tells "is this me?" without comparing ids.

## The ground

[`hafen.world`](../api/world.md#terrain-and-coordinates) answers for terrain at a world point, and
converts between the coordinate spaces — world units, tiles, grids and screen pixels. Terrain reads
answer `nil` while that part of the map is still streaming in, which is normal rather than an error.

```lua
local p = hafen.player():gob():position()
local t = hafen.world():tile(p)
hafen.log():write(t and (t.name or t.id) or "not loaded yet")
```

> **World coordinates do not hold.** The client re-bases them whenever the server drops the map — every
> login does it, and so does a walk into a cave — and they mean nothing to another player, which is why a
> place is a [Position](../api/world.md#the-position-type) rather than a pair of numbers:
> it anchors itself to a map grid, so it goes into [`hafen.store`](../api/store.md) and comes back the
> same place next session.

## The map you explored

Everything above is the world **streamed around you**. The ground you walked over last month is a different
thing entirely — it is on disk, it outlives the session, and it is [`hafen.map`](../api/map/README.md):
segments and grids, the claims and provinces that covered them, your markers, and the drawings the corner
minimap paints. A [Position](../api/world.md#the-position-type) is the door between the two halves, in
both directions:

```lua
local gp = hafen.player():gob():position():info()      -- where I am, as {gridId, x, y}
local g  = hafen.map():grid():get(gp.gridId)           -- ...the same Grid, from the recorded side
local t  = g and g:tile({ x = 0, y = 0 })              -- nil until the grid is read off the disk
hafen.log():write(t and t.name or "not loaded yet — ask again next tick")
```

A read that needs a grid the client has not loaded off the disk **starts the load and answers `nil`** — call
again next tick and it answers. There is no callback and no ready event: re-asking is the whole protocol, the
same way a rebuilt Position resolves as the map streams in. A minimap panel is that loop and little else: a
few-times-a-second timer that is both the retry and the "did the picture change?" test.

## What the client does not know

A gob's name is its *type*, so there is no display name for an arbitrary player; `gob:isPlayer()` is the
test, and [`gob:kin()`](../api/kin.md) names the ones on your roster. Beyond that, reading tells you what
the client itself has been told: an object outside your view has not been loaded and does not exist as far
as your addon is concerned.

**Next:** [events and timers](events-and-timers.md) — when your code runs, and what to hang it off.
