# Placing: the ghost on the cursor

A **Placing** is the translucent building on your cursor between the moment the server hands you one and the
click that puts it down. Reach for it to know **what** you are about to place, **where** it currently sits and
**which ground it will take** — before any of that is committed. You address it through the
[world](world.md) of the character whose cursor you mean, and nothing here writes or reaches the server.

```lua
local s = hafen.session():current()
local pl = s and s:world():placing()                  -- nil when nothing is on the cursor
if pl then
  hafen.log():write("placing " .. (pl:name() or "not resolved yet"))
  for _, ring in ipairs(pl:hitbox() or {}) do
    hafen.virtual():patch():add(ring, pl:position()):tint{60, 140, 255, 70}
  end
end
```

## What it is, and what it is not

The ghost is the **client's own**, not one of the game's objects. It is in no object cache, it carries no
server id and it was never sent to anybody: it is a preview the client draws while you aim. So it is not in
[`s:world():gob()`](world.md#objects), no filter reaches it, and it fires no
[`GobAdded`](event/bus/world.md#world) — the only door is this verb.

That is also why it is not a [Gob](gob.md) and does not answer like one. Half of a Gob's vocabulary would be
a lie on a ghost: it has no health, no state bytes, no kin standing in it, nothing to click and none of the
game's own overlays. A verb it has not got raises naming `placing` and listing what it does answer.

**Reading it grants nothing.** Committing the placement is
[`s:world():place(p, angle)`](world.md#write-protected), which is protected under `world.place`. Looking at
what is already on your own cursor is not, any more than looking at the screen is.

## Whose cursor it is

Every character has its own view, and the server hands a placement to one of them, so **what is on a cursor
is a fact about that character**. This reads the session you ask it of, drawn or not:

```lua
hafen.session():current():world():placing()            -- what the character on screen is placing
hafen.session():get("alt"):world():placing()           -- what that one is
```

Only the drawn character's ghost is **on screen**, though: the client draws one view at a time, so another
login's ghost is real, readable and invisible. Placing *with* a character is the drawn one's own business,
and [`place`](world.md#write-protected) refuses any other.

## Read

| Method | Returns | Description |
|---|---|---|
| `s:world():placing()` | Placing \| nil | the ghost on that character's cursor; `nil` when it is placing nothing |
| `pl:name()` | string \| nil | the resource being placed, spelled as [`gob:name()`](gob.md) spells it |
| `pl:position()` | [Position](position.md) \| nil | where the ghost is right now |
| `pl:facing()` | number \| nil | its angle in radians, which the mouse wheel turns |
| `pl:hitbox()` | Position`[][]` \| nil | [the ground it will stand on](#the-footprint) |
| `pl:exists()` | boolean | whether that character is placing anything at all |
| `pl:info()` | table \| nil | the snapshot: `name`, `position`, `facing` and `exists` |

`nil` from any of the reads means one of two things, and `:exists()` is what tells them apart: **nothing is
on the cursor**, or **the resource has not resolved yet**. The second is a real moment here rather than a
corner case — the server names the thing before the client has loaded it, so a ghost that exists can still
answer `nil` for its name, its footprint and its place for a frame or two. Come back next frame; nothing
about it is an error.

`pl:info()` is the [one snapshot](conventions.md#objects-and-the-snapshot-hatch) this object carries, and its
`position` is the durable `{gridId, x, y}` table a [Position](position.md) answers with. It has **no
`hitbox`** key, for the reason [`gob:info()`](gob.md) has none: a snapshot holds numbers and strings rather
than objects, and a footprint rebuilt on every call would be paid by every read that only wanted a name.

## It names the cursor, not one placement

There is one Placing per addon per character — `s:world():placing() == s:world():placing()` — and it
re-resolves on every verb. So a handle you keep goes on answering about **whatever is on that cursor now**:
put a cabin down, start a barrel, and the same handle reads the barrel. Ask `:name()` when you need to know
which, rather than trusting a handle to have stayed on one thing.

```lua
local pl = hafen.session():current():world():placing()
local was = pl and pl:name()
-- ...frames later...
if pl and pl:exists() and (pl:name() ~= was) then
  hafen.log():write("something else is on the cursor now")
end
```

## The footprint

`pl:hitbox()` answers in exactly the shape, units and orientation [`gob:hitbox()`](gob.md#the-ground-it-stands-on)
does: an array of rings, each an array of [Positions](position.md), turned by the ghost's own facing and
placed where it currently sits. A ring therefore goes into
[`hafen.virtual():patch()`](virtual/patches.md) unchanged, the same as a real object's.

It is the resource's own footprint — what the finished thing will occupy — and **not** its `build` box, the
clearance the client checks before it will let you put one down. Those are two different questions, and this
verb answers the one `gob:hitbox()` has always answered, so **a ghost and the object it becomes wear the same
shape**.

`nil` where a real object's is `nil`: a resource carrying no footprint at all, and a resource that has not
resolved.

**The ghost moves, and a patch laid on it does not follow by itself.** A patch takes a
[Position or a Gob](virtual/README.md#the-anchor-is-an-argument) as its anchor, and a ghost is neither, so its
box is laid at a **place** and moved from your own code — `patch:position(p)` where the cursor went,
`patch:rotate(a)` where the wheel turned it. Read the cursor on
[`Update`](event/bus/lifecycle.md), which is the beat it moves on:

```lua
hafen.event():on("Update", function()
  local s = hafen.session():current()
  local pl = s and s:world():placing()
  if pl then
    local at = pl:position()
    -- move the patches you laid to `at`, and turn them by pl:facing()
  end
end)
```

Write only what changed. Turning a patch re-carves ground already laid, but **moving** one re-cuts the tiles
under it, which is the one thing that collection is not free at — and a cursor sitting still should cost
nothing.

## See also

- [`session:world`](world.md) — the address this read goes through, and `place`, which commits one
- [Gob](gob.md) — the game's own objects, and the footprint verb this one mirrors
- [patches](virtual/patches.md) — the collection a ring goes into unchanged
- [Position](position.md) — what `:position()` hands back, and what a ring is made of
- [lifecycle events](event/bus/lifecycle.md) — `Update`, the beat a cursor moves on
