# Look: How a Gob Is Drawn

The look of a [Gob](gob.md) is what the client draws for it: how big, whether at all, in what colour, and with what ring round it. The verbs on this page change those four. The [materials](materials.md) a model is drawn in have a page of their own, and what stands at a gob is its [overlay](overlay.md) collection.

```lua
local session = hafen.session():current()
local boar = session and session:world():gob():nearest("kritter/boar")
if boar then
  boar:tint{255, 0, 0, 96}:scale(2)    -- a red-washed boar, twice its size; each write hands the Gob back
end
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `gob:scale()` | `number \| nil` | Unprotected | How big it is drawn. `1` for an object nobody resized. |
| `gob:scale(k)` | the Gob | Unprotected | Draw it `k` times its size. |
| `gob:visible()` | `boolean \| nil` | Unprotected | Whether the client draws it. `true` for an object nobody hid. |
| `gob:visible(flag)` | the Gob | Unprotected | Draw it, or stop drawing it. |
| `gob:tint()` | colour `\| nil` | Unprotected | The colour laid over it, keyed. `nil` for an object nobody tinted. |
| `gob:tint(color)` | the Gob | Unprotected | Lay `color` over it. |
| `gob:tint(nil)` | the Gob | Unprotected | Draw it in its own colours again. |
| `gob:outline()` | colour, `number` `\| nil` | Unprotected | The ring round it, keyed, then its width. `nil` for an object nobody outlined. |
| `gob:outline(color, width)` | the Gob | Unprotected | Draw a ring of `color` round it, `width` design pixels wide. `width` is optional, `2` without it. |
| `gob:outline(nil)` | the Gob | Unprotected | Take the ring off. |

Rules shared by every write on this page:

| Rule | Detail |
|---|---|
| Written on the object | Every character that can see it sees the same thing, and one that walks up to it afterwards draws it the same. The write is recorded against the object, not a list of who is looking now. |
| Client-local, purely visual | The object the server knows is untouched, and so is every read on the [Gob](gob.md) page. [Its footprint](gob.md#the-ground-it-stands-on), what it collides with and what a click sends are the game's. Same footing as an [overlay](overlay.md). |
| Ends with the loaded object | Dropped when the object leaves its last character's view, the moment [`GobRemoved`](event/bus/world.md#world) fires. Walk far enough for it to unload and it comes back as the game draws it. Another character still having it in view keeps it. Re-apply from [`GobAdded`](event/bus/world.md#world) to keep it across the unload. |
| `:reload` or disable | Puts back everything you changed, in every character's view. |
| Two addons | Last write wins. |
| Once the gob is gone | The read answers `nil` and a write does nothing. |
| Composition | The four compose: `boar:tint(color):outline(ring_color):scale(2):visible(false):visible(true)` is a boar still tinted, ringed and twice its size. Setting or clearing one leaves the others as they were. |

## Size (unprotected)

`gob:scale(k)` draws the object `k` times its size, the read/write pair every [thing you stand in the world](virtual/README.md) answers.

```lua
local boar = hafen.session():current():world():gob():nearest("kritter/boar")
if boar then
  boar:scale(2)          -- twice the size; hands the Gob back, so this chains
  boar:scale(1)          -- back to the size the game draws it at
end
```

| Rule | Detail |
|---|---|
| Applied in place | The object's feet stay where they were. It still turns, moves and takes a click, and the pick follows the drawn size. |
| `k` | A finite number greater than zero. `0` collapses the object to a point and a negative turns it inside out: both raise naming the rule. `gob:scale(1)` is the original size and leaves nothing behind. |
| Resources that reset their transform | The special resource types that ignore a ghost's rotation ignore scale too. |

## Drawn or not (unprotected)

`gob:visible(flag)` says whether the client draws the object at all.

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
if tree then
  tree:visible(false)     -- out of the scene; hands the Gob back, so this chains
  tree:visible(true)      -- back into it, at whatever size it was being drawn
end
```

> **An object you hide is not there to be clicked.** A click resolves against the scene as drawn. An object with no model draws nothing, so a click where it stood reaches the ground behind it. The verb never hides a model and keeps the click.

| Rule | Detail |
|---|---|
| The model alone | The object is still where it was, still moving, and answers every [Gob](gob.md) read. What is attached at it goes on drawing: the game's own overlays and [yours](overlay.md), standing where the model was. |
| `flag` | `true` or `false`. Anything else raises naming the argument. A number most of all: in Lua `0` is a true value and would show an object you meant to hide. |

## Tint (unprotected)

`gob:tint(color)` lays a [colour](shapes.md#colours) over the object's model, shaded as the model is shaded. The same read/write pair a [virtual entity](virtual/README.md) answers to `:tint`: the colour's `a` is the blend strength, `nil` is none.

```lua
local chest = hafen.session():current():world():gob():nearest("terobjs/chest")
if chest then
  chest:tint{0, 200, 80, 96}          -- a green wash: the lit and shaded sides stay distinct
  local tint_color = chest:tint()     -- {r = 0, g = 200, b = 80, a = 96}
  chest:tint(nil)                     -- plain again
end
```

| Rule | Detail |
|---|---|
| The blend | The game's own damage wash: each pixel is mixed towards your colour by `a / 255`. `{255, 0, 0, 96}` is a red-washed object with its shading kept. `{255, 0, 0}` (`a` defaulting to `255`) is a flat silhouette with the lighting gone. A wash that still reads as the object sits around `96`. |
| Composes with damage | A damaged object keeps its cracks and its red under your colour: separate stages of the same blend, not one slot two writers fight over. |
| The read | The keyed table every colour reader answers: `.r` answers, `[1]` is `nil`. [`gob:info()`](types/world.md#gobinfo) carries the same table under `tint`, and no `tint` key for an object nobody tinted. |
| `gob:tint(nil)` | Legal and leaves nothing behind. |
| Not a colour | A number, a string, a boolean, a table that is not one: raises naming both spellings of a colour. |

## Outline (unprotected)

`gob:outline(color, width)` draws a ring of a [colour](shapes.md#colours) round what the client draws of the object, and paints nothing inside it. A hover highlight is one handler on [`PickChanged`](ui/mouse.md#the-pick):

```lua
local highlighted = nil
hafen.ui():mouse():on("PickChanged", function(gob)
  if highlighted then
    highlighted:outline(nil)             -- the object the pointer left
  end
  highlighted = gob
  if gob then
    gob:outline({255, 220, 0}, 3)        -- a yellow ring, 3 design pixels wide
  end
end)
```

| Rule | Detail |
|---|---|
| What is ringed | Everything the client draws of the object: its model, its equipment, the game's own sprites standing at it. The object keeps its own colours. |
| Not ringed | What is drawn over the scene: [widgets](ui/widget.md), [overlay](overlay.md) labels and painters. What a character carries is an object of its own, ringed through its own Gob. See-through parts, smoke and glass, draw no ring. |
| The visible part only | Where something solid stands between the camera and the object, the ring runs along that thing's edge. It never shows through a wall. What is see-through in front, water or smoke, veils the ring as it veils the object. |
| `width` | Design pixels, the same on screen at any interface scale. A whole number from `1` to `8`. `0`, `9`, `1.5` and `"2"` raise naming the rule. |
| `a` | The ring's strength: `255` (the default) solid, lower see-through. At `0` no ring is drawn, and the read still answers it. |
| The read | Two values: the colour keyed, then the width. `other:outline(gob:outline())` copies a ring. [`gob:info()`](types/world.md#gobinfo) carries the pair as `outline = {color = …, width = …}`, and no `outline` key for an object nobody outlined. |
| `gob:outline(nil)` | Takes the ring off and leaves nothing behind. `gob:outline(nil, width)` raises: taking a ring off takes no width. |
| Refused | A value that is not a colour raises naming both spellings of one. A third argument raises. Each is raised on a gob that is gone too. |
| API `1.2` | Declare `"api_version": "1.2"` in your [manifest](../manifest.md#the-api-version). |

## Overlays

Everything drawn at a gob is the collection [`gob:overlay()`](overlay.md), unprotected. That is the game's own, the labels and painters you attach, and whatever you have [stood in the world](virtual/README.md) anchored to it. An overlay is attached to the object, so every character that can see the object draws it. A `:reload` or a disable takes it off all of them.

---

## See Also

- [Gob](gob.md) — the object itself: finding one, and everything you read off it.
- [Overlay](overlay.md) — the labels, painters and models you attach at a gob.
- [Colours](shapes.md#colours) — the table `gob:tint` and `gob:outline` read and write.
- [The mouse](ui/mouse.md#the-pick) — `PickChanged`, the object under the pointer.
- [Virtual entities](virtual/README.md) — `:scale`, `:visible` and `:tint` on a thing you stood there.
- [Threading](threading.md) — why these writes are safe from a `Draw` handler.
