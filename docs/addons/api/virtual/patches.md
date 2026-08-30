# hafen.virtual: a shape lying on the ground

A **patch** is a convex ring of places drawn flat on the terrain — a highlighted field, an object's own
footprint, the reach of something you are about to build. It is the one kind here that lies **down**: a
[ghost](ghosts.md), a [sprite](sprites.md), a [model](models.md) and a [standing widget](widgets.md) all
stand up, and a patch is on the ground under them.

It lies on the ground **exactly**: over a slope, a ridge or a tile boundary it follows the relief with no
float, no gap and no shimmer, and whatever stands on that ground occludes it, your own character included.
Its edge is the ring's own shape at every zoom, rather than a staircase of square tiles, and
[a line can be drawn along it](#the-border).

`hafen.virtual():patch()` **is the collection** of the patches your addon has laid: `:add(ring, anchor)` lays
one and hands it back, `:list(filter)` reads them, `:remove(p)` takes one up — the whole
[collection shape](README.md#the-collections-unprotected).

```lua
local s = hafen.session():current()
local here = s:player():gob():position()
local ring = { here:offset(-6, -6), here:offset(6, -6),      -- a 12x12 square, in world units
               here:offset(6, 6),   here:offset(-6, 6) }     -- a tile is 11 of them
local patch = hafen.virtual():patch():add(ring, here):tint{40, 200, 120}
```

A patch is **client-only**: it has no server id, is never sent, and grants nothing — see
[the section's permission note](README.md).

## The ring

`ring` is an array of [Position](../position.md)s, in order around the shape, wound either way. Each point is
a real place in the world, so the rings [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) hands back go in
**unchanged** — no projection, no conversion, one patch per ring:

```lua
local me = hafen.session():current():player():gob()
for _, ring in ipairs(me:hitbox() or {}) do            -- nil until the object's resource resolves
  hafen.virtual():patch():add(ring, me):tint{255, 80, 80}   -- lit up on that object's own footprint
end
```

What a patch keeps is the ring as **offsets from its anchor**, not as points, which is why the same ring
means the same shape at a point and on an object, and why it survives
[the numbers moving under it](README.md#the-ground-under-one-that-stands-still).

The ring cannot be changed afterwards: it is what the patch was made from, and a different shape is a
different patch. There is no `:ring()` verb — `patch:info().ring` reads back the shape it holds.

## What a ring may be

Each rule below is a **refusal** naming itself, because a ring drawn as some other shape is the one outcome
a drawing verb must not have.

| The ring | What `:add` does |
|---|---|
| fewer than three points | raises: a ring of two is a line, and a line has no ground under it |
| three or more points enclosing nothing — all the same place, or all on one line | raises, naming how many edges it actually found |
| concave | raises: the silhouette is the intersection of the ring's edge half-planes, so a concave ring would be drawn as its hull |
| more than **32** edges | raises, naming that number — refused rather than truncated to the wrong shape |
| an element that is not a Position | raises, naming which one and the three verbs that make a place |
| a point with no way to reach the anchor — another grid, and neither end located this session | raises: a ring one point short is the wrong shape, not a smaller one |

The edge limit is the length the half-planes are declared at in the fragment stage. Split a bigger shape
into convex rings and lay one patch each; that is also the answer for a concave one.

```lua
local ok, err = pcall(function()
  hafen.virtual():patch():add({ here, here:offset(6, 0) }, here)   -- two points
end)
hafen.log():write(tostring(err))                              -- ...says a line has no ground under it
```

## The anchor

The [anchor](README.md#the-anchor-is-an-argument) is the same argument every other kind takes, and it means
the same two things:

- a **[Position](../position.md)** — the patch lies there and stays there. On ground this character cannot
  locate it waits whole, `:exists()` true and `:drawn()` false, rather than drawing part of itself.
- a **[Gob](../gob.md)** — it follows that object over the ground and ends with it.

A patch that follows is re-laid where the object moved, so it is under the feet the whole way. It keeps its
own shape while it goes: the ring is offsets, and the object's own turning does not turn it —
`patch:rotate(a)` is what turns a patch.

## The patch

The [shared vocabulary](README.md#one-vocabulary-every-kind), plus the one verb only a patch has. Two of
the shared ones answer differently here, and both because a patch is on the ground rather than above it:

| Method | Permission | Description |
|---|---|---|
| `patch:border()` / `patch:border(c [, w])` | unprotected | [the line round the ring](#the-border); `nil` clears it |
| `patch:offset()` / `patch:offset(x, y)` | unprotected | where it sits relative to the object it follows — **two** numbers, on the ground |
| `patch:drawn()` | unprotected | is it on the terrain being drawn right now? |

`patch:offset(x, y, z)` raises naming that a patch has no height. A patch held *above* the ground is a
different thing and this is not it — on the ground it is already occluded by whatever stands on it, which
is most of what a raised one would have been for.

Everything else reads exactly as it does for the kinds that stand up. `:scale(k)` takes the ring out from
its own place, `:rotate(a)` turns it there, `:tint(c)` and `:alpha(a)` colour it, `:visible(b)` takes it off
the ground and puts it back, and `:position(p)` moves a planted one — on one that follows, that raises
naming `patch:offset` instead.

**On a patch the tint is the fill**, and so its fourth component is the fill's own opacity rather than a
blend strength: there is no picture underneath for a strength to be measured against. `:alpha(a)` is the
whole shape's and multiplies fill and border alike, which is what lets a solid line stand round ground you
can see through.

> **Turning, scaling, tinting and bordering cost no terrain work.** They re-carve the silhouette and push
> new colours through the ground that is already laid; no mesh is rebuilt. Moving one far enough to leave
> the tiles it covers does re-cut those tiles, so a patch dragged across the map every frame is the one
> shape of this that is not free.

### The snapshot

`patch:info()` carries the [shared keys](README.md#the-snapshot) — `kind` (`"patch"`), `scale`, `rotate`,
`alpha`, `visible`, `clickable`, `exists`, `drawn`, `position`, `tint` when one is laid over it, and
`anchor` with `offset` only on one that follows — plus the one a patch has of its own:

| Key | What it holds |
|---|---|
| `border` | `{color = c, width = w}` — the pair [`patch:border()`](#the-border) hands back |
| `ring` | the shape, as an array of the `{gridId, x, y}` tables a [Position](../position.md) answers with |

`border` is absent while no line is laid and `ring` while this character cannot locate the patch at all,
exactly as a key is absent everywhere in this API when the thing it names is not known. The snapshot names
the two halves of a border where the call counts them, because a snapshot is a document and a call is not.

```lua
local i = patch:info()
hafen.log():write(i.kind .. ": " .. #i.ring .. " point(s), alpha " .. i.alpha)
```

## The border

`patch:border(c, w)` draws a line at one colour and one thickness all the way round the ring. Its centre is
left to whatever fills the patch, and a patch with no border laid has none drawn — the property is left out
rather than set to something invisible.

| Written | Does |
|---|---|
| `patch:border(c)` | a hairline in [colour](../shapes.md#colours) `c` — the same as a `w` of `0` |
| `patch:border(c, w)` | a line `w` **world units** thick, and hands the patch back |
| `patch:border()` | reads **both** back: the colour keyed, then the width |
| `patch:border(nil)` | takes the line off; the read answers `nil` again |

The read hands back two values in the order the write takes them, so one patch's edge goes straight onto
another's:

```lua
local one = hafen.virtual():patch():add(ring, here)
  :tint({40, 200, 120, 70}):border({255, 255, 255}, 0)   -- see-through green under a solid white hairline
local two = hafen.virtual():patch():add(other, there):border(one:border())
```

**The width is world units, like every other length on a patch** — `w` of `11` is a tile, and `:scale(k)`
takes the ring out around a line that stays the thickness it was told. `0` is the default and means
the thinnest line the screen can draw. Below that it cannot go: whatever `w` says, the line is never drawn
thinner **on screen** than the one pixel the ring's own edge already is, so a border does not fade out as
the camera pulls back. A `w` outside `0..100` is brought to the nearer end, the way
[`:scale`](README.md#one-vocabulary-every-kind) and `:alpha` are, rather than refused.

**A border is a colour and a width, and nothing else.** The word is the one a
[stylesheet rule](../ui/style/chrome.md#border) uses for the same thing, but a rule is a document and says
it as `{color = …, width = …}`, while out here it is a call and says it as two arguments —
`patch:border(c, w)`, as [`g:line(x1, y1, x2, y2, width)`](../ui/drawing.md) has them. So a rule's own value
raises, naming that; so does a picture spelling (`box`, `slice`, an image), which frames a rectangle out of
art and has nothing to draw along a ring. A `c` that is not a colour raises naming the two colour spellings.

```lua
local ok, err = pcall(function() patch:border{box = "gfx/hud/wnd"} end)
hafen.log():write(tostring(err))          -- ...says a border out here is two arguments
```

## Naming and filtering

**A patch is a shape, not a picture of something, so it has no name.** A string
[filter](README.md#the-collections-unprotected) on `hafen.virtual():patch()` therefore *raises* rather than
matching nothing, and says to pass a function or nothing at all. `hafen.virtual():entity()` does take a
string, since it is over the kinds that have names, and no patch ever matches one.

```lua
hafen.virtual():patch():list()                                  -- all of them
hafen.virtual():patch():find(function(one) return one:drawn() end)  -- the first one on drawn ground
```

`tostring(patch)` is `Patch`, and a verb no patch has answers naming `patch` and listing what it does have,
`border` among them.

## Clickability

A patch is **opt-in clickable**: `patch:clickable(true)`. A click inside the ring fires `patch:onClick(fn)`
and
[`PatchClicked`](../event/bus/world.md#world-ghosts-and-sprites) on the bus, and is **consumed** — the
character does not walk. A click outside the ring passes straight through to whatever is behind it.

```lua
local patch = hafen.virtual():patch():add(ring, here)
  :clickable(true)
  :onClick(function(patch, button, x, y)  -- 1 = left, 3 = right; x, y = the clicked world point
    hafen.log():write("clicked my patch with button " .. button)
  end)
hafen.event():on("PatchClicked", function(ev)
  hafen.virtual():patch():remove(ev:patch())   -- ev:patch() ev:button() ev:x() ev:y()
end)
```

Both fire on every click, and `PatchClicked` reaches only *your* addon, since a patch is private to the
addon that laid it.

> **The hit test is the ring, not the picture.** A patch answers a click on the screen area its ring covers
> whether or not you can see that ground — behind a hill, under a house, on the far side of a wall. The
> test runs inside the click that asked, which is what lets it consume one; asking the drawn image instead
> would answer a frame later, by which time the character has walked. So a clickable patch you cannot see
> is still a clickable patch: switch `:clickable(false)` on the ones you are not using.

## See also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) — the rings this collection takes unchanged
- [Position](../position.md) — the place every point of a ring is, and the offset verb that builds one
- [ghosts](ghosts.md) — the same core, standing up instead
- [events](../event/bus/world.md#world-ghosts-and-sprites) — `PatchClicked`
