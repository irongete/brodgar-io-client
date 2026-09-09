# hafen.virtual: a shape lying on the ground

A **patch** is a shape drawn flat on the terrain — a highlighted field, an object's own footprint, the reach
of something you are about to build. It is the one kind here that lies **down**: a [ghost](ghosts.md), a
[sprite](sprites.md), a [model](models.md) and a [standing widget](widgets.md) all stand up, and a patch is on
the ground under them.

It lies on the ground **exactly**: over a slope, a ridge or a tile boundary it follows the relief with no
float, no gap and no shimmer, and whatever stands on that ground occludes it, your own character included —
until you say [the world may not hide it](#drawing-through-the-world). Its outline is the shape's own at
every zoom, rather than a staircase of square tiles, and [a line can be drawn along it](#the-border).

The shape is the union of convex [pieces](pieces.md), and `:add(ring, anchor)` lays a patch of one. That is
the whole of the difference between a patch and a ring: a footprint or a field is one piece and is the ring
it was laid with, and anything that is not convex is the same patch carrying more of them.

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

## The anchor

The [anchor](README.md#the-anchor-is-an-argument) is the same argument every other kind takes, and it means
the same two things:

- a **[Position](../position.md)** — the patch lies there and stays there. On ground this character cannot
  locate it waits whole, `:exists()` true and `:drawn()` false, rather than drawing part of itself.
- a **[Gob](../gob.md)** — it follows that object over the ground and ends with it.

A patch that follows is re-laid where the object moved, and only where it moved: it is under the feet the
whole way, and an object standing still leaves it lying where it lies at no cost to the frame. It keeps its
own shape while it goes: its pieces are offsets, and the object's own turning does not turn it —
`patch:rotate(a)` is what turns a patch.

## The patch

The [shared vocabulary](README.md#one-vocabulary-every-kind), plus the verbs only a patch has. Two of
the shared ones answer differently here, and both because a patch is on the ground rather than above it:

| Method | Permission | Description |
|---|---|---|
| `patch:piece()` | unprotected | the convex [pieces](pieces.md) this shape is the union of |
| `patch:border()` / `patch:border(c [, w])` | unprotected | [the line round the shape](#the-border); `nil` clears it |
| `patch:occluded()` / `patch:occluded(b)` | unprotected | [may the world hide it](#drawing-through-the-world); `true` by default |
| `patch:offset()` / `patch:offset(x, y)` | unprotected | where it sits relative to the object it follows — **two** numbers, on the ground |
| `patch:drawn()` | unprotected | is it on the terrain being drawn right now? |

`patch:offset(x, y, z)` raises naming that a patch has no height. A patch held *above* the ground is a
different thing and this is not it — what a raised one would mostly have been for is being seen over what
stands around it, and [`patch:occluded(b)`](#drawing-through-the-world) is that without leaving the ground.

Everything else reads exactly as it does for the kinds that stand up. `:scale(k)` takes the shape out from
its own place, `:rotate(a)` turns it there, `:tint(c)` and `:alpha(a)` colour it, `:visible(b)` takes it off
the ground and puts it back, and `:position(p)` moves a planted one — on one that follows, that raises
naming `patch:offset` instead. Each of them moves every [piece](pieces.md) of the shape together, since a
piece is held as offsets from the patch's own anchor.

**On a patch the tint is the fill**, and so its fourth component is the fill's own opacity rather than a
blend strength: there is no picture underneath for a strength to be measured against. `:alpha(a)` is the
whole shape's and multiplies fill and border alike, which is what lets a solid line stand round ground you
can see through.

> **Turning, scaling, tinting, bordering and switching occlusion cost no terrain work.** They re-carve the
> silhouette and push new colours through the ground that is already laid; no mesh is rebuilt. Moving one far
> enough to leave the tiles it covers does re-cut those tiles, so a patch dragged across the map every frame
> is the one shape of this that is not free.

### The snapshot

`patch:info()` carries the [shared keys](README.md#the-snapshot) — `kind` (`"patch"`), `scale`, `rotate`,
`alpha`, `visible`, `clickable`, `exists`, `drawn`, `position`, `tint` when one is laid over it, and
`anchor` with `offset` only on one that follows — plus the keys a patch has of its own:

| Key | What it holds |
|---|---|
| `border` | `{color = c, width = w}` — the pair [`patch:border()`](#the-border) hands back |
| `occluded` | whether the world may hide it, the boolean [`patch:occluded()`](#drawing-through-the-world) reads |
| `pieces` | the shape: one ring per [piece](pieces.md) in the order they were laid, each an array of the `{gridId, x, y}` tables a [Position](../position.md) answers with |

`pieces` is the whole shape rather than one ring of it, because a patch **is** the union of its pieces: a
single ring would be whichever one it happened to be laid with. One piece's own is
[`piece:info()`](pieces.md#the-piece), in the same form.

`border` is absent while no line is laid, and `pieces` while the character on screen cannot locate the ground
the patch lies on — `hafen.virtual()` stands its things in the scene being drawn, so `pieces` is that
character's reading of the ground, whichever character laid the patch. Either is absent exactly as a key is
absent everywhere in this API when the thing it names is not known, and a patch
[holding no pieces](pieces.md#taking-one-up) has an **empty** `pieces`, which is a shape known and empty
rather than one not known. `occluded` is always there, since a boolean property has a value at every moment.
The snapshot names the two halves of a border where the call counts them, because a snapshot is a document
and a call is not.

```lua
local i = patch:info()
hafen.log():write(i.kind .. ": " .. #i.pieces .. " piece(s), alpha " .. i.alpha)
```

## The border

`patch:border(c, w)` draws a line at one colour and one thickness all the way round the shape. Its centre is
left to whatever fills the patch, and a patch with no border laid has none drawn — the property is left out
rather than set to something invisible. On a patch of several [pieces](pieces.md) it runs round their union,
so an internal join carries no line.

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
takes the shape out around a line that stays the thickness it was told. `0` is the default and means
the thinnest line the screen can draw. Below that it cannot go: whatever `w` says, the line is never drawn
thinner **on screen** than the one pixel the shape's own edge already is, so a border does not fade out as
the camera pulls back. A `w` outside `0..100` is brought to the nearer end, the way
[`:scale`](README.md#one-vocabulary-every-kind) and `:alpha` are, rather than refused.

**A border is a colour and a width, and nothing else.** The word is the one a
[stylesheet rule](../ui/style/chrome.md#border) uses for the same thing, but a rule is a document and says
it as `{color = …, width = …}`, while out here it is a call and says it as two arguments —
`patch:border(c, w)`, as [`g:line(x1, y1, x2, y2, width)`](../ui/drawing.md) has them. So a rule's own value
raises, naming that; so does a picture spelling (`box`, `slice`, an image), which frames a rectangle out of
art and has nothing to draw along an outline. A `c` that is not a colour raises naming the two colour spellings.

```lua
local ok, err = pcall(function() patch:border{box = "gfx/hud/wnd"} end)
hafen.log():write(tostring(err))          -- ...says a border out here is two arguments
```

## Drawing through the world

`patch:occluded(false)` says the world may not hide the shape: a wall, a hill or a house between you and it
stops cutting it, and it is drawn whole. `patch:occluded(true)` is the default and is what every patch does
until it is told otherwise — the ground, the buildings and your own character are all in front of it, and it
is drawn where they are not.

| Written | Does |
|---|---|
| `patch:occluded()` | reads it back: `true` while the world may hide it |
| `patch:occluded(b)` | says whether it may, and hands the patch back |

`b` must be `true` or `false`. Anything else raises naming the verb, because in Lua `0` is true and a patch
told `0` would quietly read as one the world hides. Switching it is a **look**, not a shape: nothing is
re-cut, nothing is re-carved, and the tint, the border, the offset and the ground it is laid on all come
through the flip exactly as they were.

**Three things it does not change**, and each is one a reader expects it to:

- **The interface still covers it.** A patch is drawn inside the world, and your windows, your chat and
  every other 2D thing are drawn after the world is finished. A ring under a window is under it either way.
- **It still lies on the ground.** The shape follows the slope, the ridge and the tile boundary as it always
  did — this is not a flat shape drawn over the screen, it is the same shape on the same relief with one test
  switched off.
- **It is still only where the ground is.** A patch exists on terrain the client has built; ground that has
  not arrived yet has nothing to draw a shape on, so `patch:drawn()` still answers what it did and a patch on
  ground this character cannot reach still waits.

The other kinds are unchanged: a [ghost](ghosts.md), a [sprite](sprites.md) and a
[standing widget](widgets.md) stand in the world and are hidden by what stands in front of them, with no
verb of their own for this.

```lua
local me = hafen.session():current():player():gob()
local mine
for _, ring in ipairs(me:hitbox() or {}) do
  if mine then mine:piece():add(ring) else
    mine = hafen.virtual():patch():add(ring, me)
      :tint({255, 200, 40, 90}):border({255, 200, 40}, 0.4)
      :occluded(false)                     -- still readable from the far side of the barn
  end
end
```

> **With the world hiding it off, two rings stack in draw order.** Depth is what decides which of two
> overlapping patches is on top, so a pair that both draw through the world are ordered by whichever the
> client happens to draw last, and that order is not yours to set. Two of them on the same ground is a
> picture you cannot predict; give them different ground, or let one of the two be occluded.

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
`border` and `piece` among them.

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

- [pieces](pieces.md) — the convex rings a patch is the union of: what a ring may be, and the edge budget
- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) — the rings a patch takes unchanged
- [Position](../position.md) — the place every point of a ring is, and the offset verb that builds one
- [ghosts](ghosts.md) — the same core, standing up instead
- [events](../event/bus/world.md#world-ghosts-and-sprites) — `PatchClicked`
