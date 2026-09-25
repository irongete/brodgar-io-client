# hafen.virtual: A Shape Lying on the Ground

A patch is a shape drawn flat on the terrain: a highlighted field, an object's footprint, the reach of something you are about to build. It is the one kind in the section that lies down. The shape is the union of convex [pieces](pieces.md), and `:add(ring, anchor)` lays a patch of one.

```lua
local session = hafen.session():current()
local here = session:player():gob():position()
local ring = { here:offset(-6, -6), here:offset(6, -6),      -- a 12x12 square, in world units
               here:offset(6, 6),   here:offset(-6, 6) }     -- a tile is 11 of them
local patch = hafen.virtual():patch():add(ring, here):tint{40, 200, 120}
```

---

| Rule | Detail |
|---|---|
| The collection | `hafen.virtual():patch()` holds the patches your addon laid: `:add(ring, anchor)`, `:list(filter)`, `:remove(patch)`, the [collection shape](README.md#the-collections-unprotected). |
| On the ground exactly | Over a slope, a ridge or a tile boundary it follows the relief with no float, gap or shimmer. What stands on that ground occludes it, your character included, until [`:occluded(false)`](#drawing-through-the-world). Its edge is the shape's own at every zoom. |
| A patch and a ring | A footprint or a field is one piece and is the ring it was laid with. Anything not convex is the same patch carrying more pieces. |
| Laid later, drawn on top | Where two patches overlap, the one laid later covers the one laid earlier, and every patch covers the ground's own overlays (a claim, a province). Which addon laid it does not matter. Tinting, moving or laying a piece into a patch keeps its place in the stack. To bring one to the top, lay it again. |
| Client-only | No server id, never sent, grants nothing ([the section's note](README.md)). |

## The anchor

| Anchor | Effect |
|---|---|
| A [Position](../position.md) | The patch lies there and stays. On ground this character cannot locate it waits whole, `:exists()` true and `:drawn()` false. |
| A [Gob](../gob.md) | It follows the object over the ground and ends with it. Re-laid where the object moved and only then, so a still object costs the frame nothing. Its pieces are offsets and the object's turning does not turn it. `patch:rotate(angle)` does. |

## The patch

The [shared vocabulary](README.md#one-vocabulary-every-kind) plus its own. Two shared verbs answer differently because a patch is on the ground, and one raises.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `patch:piece()` | collection | Unprotected | The convex [pieces](pieces.md) this shape is the union of. |
| `patch:border()` / `patch:border(color [, width])` | the patch | Unprotected | [The line round the shape](#the-border). `nil` clears it. |
| `patch:occluded()` / `patch:occluded(flag)` | the patch | Unprotected | [Whether the world may hide it](#drawing-through-the-world). `true` by default. |
| `patch:offset()` / `patch:offset(x, y)` | the patch | Unprotected | Where it sits relative to the object it follows: two numbers, on the ground. `patch:offset(x, y, z)` raises naming that a patch has no height. |
| `patch:drawn()` | `boolean` | Unprotected | Whether it is on the terrain being drawn right now. |

| Rule | Detail |
|---|---|
| The shared verbs | `:scale(k)` takes the shape out from its own place. `:rotate(angle)` turns it there. `:tint(color)` and `:alpha(value)` colour it. `:visible(flag)` takes it off the ground and back. `:position(position)` moves a planted one. On one that follows it raises naming `patch:offset`. Each moves every [piece](pieces.md) together, since a piece is held as offsets from the anchor. |
| No `:outline` | `patch:outline(…)` raises naming `patch:border(color, width)`: a patch's line is a band round the shape in world units. [`:outline`](README.md#one-vocabulary-every-kind) rings what is drawn of a kind standing up. |
| The tint is the fill | Its fourth component is the fill's own opacity, not a blend strength. `:alpha(value)` is the whole shape's and multiplies fill and border alike, so a solid line stands round see-through ground. |
| What costs terrain work | Turning, scaling, tinting, bordering and switching occlusion re-carve the silhouette and push colours through ground already laid. No mesh is rebuilt. Moving one off the tiles it covers re-cuts those tiles: a patch dragged across the map every frame is the one thing here not free. |

### The snapshot

`patch:info()` carries the [shared keys](README.md#the-snapshot) (`kind` is `"patch"`) plus its own.

| Key | Holds |
|---|---|
| `border` | `{color = color, width = width}`, the pair [`patch:border()`](#the-border) hands back. Absent while no line is laid. |
| `occluded` | The boolean [`patch:occluded()`](#drawing-through-the-world) reads. Always present. |
| `pieces` | The whole shape: one ring per [piece](pieces.md) in laid order, each an array of the `{gridId, x, y}` tables a [Position](../position.md) answers with. Absent while the character on screen cannot locate the ground (it is that character's reading, whichever character laid the patch). Empty for a patch [holding no pieces](pieces.md#taking-one-up). One piece's own is [`piece:info()`](pieces.md#the-piece). |

```lua
local snapshot = patch:info()
hafen.log():write(snapshot.kind .. ": " .. #snapshot.pieces .. " piece(s), alpha " .. snapshot.alpha)
```

## The border

`patch:border(color, width)` draws a line at one colour and thickness all the way round the shape. Its centre is left to the fill. On a patch of several [pieces](pieces.md) it runs round their union, so an internal join carries no line.

| Call | Effect |
|---|---|
| `patch:border(color)` | A hairline in [colour](../shapes.md#colours) `color`, a `width` of `0`. |
| `patch:border(color, width)` | A line `width` world units thick. |
| `patch:border()` | Reads both back: the colour keyed, then the width. |
| `patch:border(nil)` | Takes the line off. The read answers `nil`. |

```lua
local field = hafen.virtual():patch():add(ring, here)
  :tint({40, 200, 120, 70}):border({255, 255, 255}, 0)   -- see-through green under a solid white hairline
local there = here:offset(24, 0)
local other_ring = { there:offset(-6, -6), there:offset(6, -6), there:offset(6, 6), there:offset(-6, 6) }
local other_field = hafen.virtual():patch():add(other_ring, there):border(field:border())
```

| Rule | Detail |
|---|---|
| Width is world units | `11` is a tile. `:scale(k)` takes the shape out around a line that keeps its thickness. `0` is the default, the thinnest line the screen draws. A line is never drawn thinner on screen than the one pixel the edge already is. A border does not fade as the camera pulls back. Outside `0..100` is brought to the nearer end, as `:scale` and `:alpha` are. |
| A colour and a width, nothing else | A [stylesheet rule](../ui/style/chrome.md#border)'s `{color = …, width = …}` raises naming the two-argument form (as [`graphics:line(x1, y1, x2, y2, width)`](../ui/drawing.md) has it). A picture spelling (`box`, `slice`, an image) raises too. A `color` that is not a colour raises naming the two colour spellings. |

## Drawing through the world

`patch:occluded(false)` says the world may not hide the shape: a wall, a hill or a house between you and it stops cutting it. `true` is the default.

| Rule | Detail |
|---|---|
| `flag` | `true` or `false`. Anything else raises naming the verb, since `0` is true in Lua. |
| A look, not a shape | Nothing is re-cut or re-carved. Tint, border, offset and ground come through the flip unchanged. |
| The interface still covers it | Windows, chat and every 2D thing are drawn after the world. |
| It still lies on the ground | The same shape on the same relief with one test switched off. |
| It is still only where the ground is | Ground not arrived has nothing to draw on. `patch:drawn()` answers as before, and a patch on ground this character cannot reach waits. |
| The other kinds are unchanged | A [ghost](ghosts.md), a [sprite](sprites.md) and a [standing widget](widgets.md) are hidden by what stands in front, with no verb for this. |

```lua
local my_gob = hafen.session():current():player():gob()
local footprint
for _, ring in ipairs(my_gob:hitbox() or {}) do
  if footprint then footprint:piece():add(ring) else
    footprint = hafen.virtual():patch():add(ring, my_gob)
      :tint({255, 200, 40, 90}):border({255, 200, 40}, 0.4)
      :occluded(false)                     -- still readable from the far side of the barn
  end
end
```

> **With occlusion off, two overlapping patches still stack by when they were laid**: the later one covers the earlier one.

## Naming and filtering

A patch is a shape, not a picture of something, so it has no name. A string [filter](README.md#the-collections-unprotected) on `hafen.virtual():patch()` raises, saying to pass a function or nothing. `hafen.virtual():entity()` takes a string and no patch ever matches it. `tostring(patch)` is `Patch`. A verb no patch has answers naming `patch` and listing what it has.

```lua
local every_patch = hafen.virtual():patch():list()
local first_drawn = hafen.virtual():patch():find(function(candidate) return candidate:drawn() end)
```

## Clickability

Opt-in with `patch:clickable(true)`: a click inside any [piece](pieces.md) fires `patch:onClick(fn)` and [`PatchClicked`](../event/bus/world.md#world-ghosts-and-sprites) and is consumed (the character does not walk). A click inside no piece passes through, the clear ground between two pieces included.

```lua
local patch = hafen.virtual():patch():add(ring, here)
  :clickable(true)
  :onClick(function(clicked_patch, button, world_x, world_y)  -- 1 = left, 3 = right; then the clicked world point
    hafen.log():write("clicked my patch with button " .. button)
  end)
hafen.event():on("PatchClicked", function(event)
  hafen.virtual():patch():remove(event:patch())   -- event:patch() event:button() event:x() event:y()
end)
```

| Rule | Detail |
|---|---|
| Both fire | On every click. `PatchClicked` reaches only your addon. |
| The patch answers, never the piece | `event:patch()` is the shape, `event:x()`/`event:y()` the world point. Which piece that is in you answer from the point against the rings you laid. |
| Overlapping patches | The one in front takes the click, decided at the piece the pointer is on. A long shape running away from you wins a press at its near end. |
| The hit test is the pieces, not the picture | It answers on the screen area the pieces cover, whether or not you can see that ground (behind a hill, under a house). It answers inside the click that asked, so it can consume one. A clickable patch you cannot see is still clickable: `:clickable(false)` the ones not in use. |

---

## See Also

- [Pieces](pieces.md) — the convex rings a patch is the union of: what a ring may be, the budget, taking one up.
- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [`gob:hitbox()`](../gob.md#the-ground-it-stands-on) — the rings a patch takes unchanged.
- [Position](../position.md) — the place every point of a ring is, and the offset verb that builds one.
- [Ghosts](ghosts.md) — the same core, standing up instead.
- [Events](../event/bus/world.md#world-ghosts-and-sprites) — `PatchClicked`.
