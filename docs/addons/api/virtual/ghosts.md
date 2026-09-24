# hafen.virtual: One of the Game's Own Props

A ghost is one of the game's `.res` props rendered at a place you choose: a log cabin, a timber house, a fence. It has a facing, translucency, tint and scale of its own. Lay something out over the terrain before you build it. Your own image or model is a [sprite](sprites.md) or a [model](models.md).

```lua
local position = hafen.session():current():player():gob():position()
local ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", position):alpha(0.5)
ghost:position(position:offset(33, 0))                 -- 3 tiles east; a tile is 11 world units
ghost:rotate(math.pi)
```

---

| Rule | Detail |
|---|---|
| The collection | `hafen.virtual():ghost()` holds the ghosts your addon placed: `:add(res, anchor)`, `:list(filter)`, `:remove(ghost)`, the whole [collection shape](README.md#the-collections-unprotected). A string `filter` matches the resource name. |
| `res` | A resource name (`"gfx/terobjs/arch/logcabin"`) resolved through the game resource pool: any server or client resource. |
| The anchor | A [Position](../position.md) to stand it at a point or a [Gob](../gob.md) to follow one ([the anchor](README.md#the-anchor-is-an-argument)). |
| Client-only | A game object with no server id: never sent, no gameplay advantage, unprotected ([the section's note](README.md)). |
| Streams in | The resource resolves on a loader thread: `:add` returns a working ghost at once and the visual appears once the resource has loaded. Every verb works meanwhile. A `:position` before it is visible sets where it will appear. |

> **Any resource name is accepted, a body or a critter included.** A ghost of a player body at full opacity looks like somebody standing there. No read about the world lists it: not a [Gob](../gob.md), not in `session:world()`, unknown to `session:party()` and the kin roster. It deceives nobody but the person running your addon. Say what yours stands. Keep an alpha or a tint on anything a player could mistake for real.

## The ghost

The [shared vocabulary](README.md#one-vocabulary-every-kind) (`:position`, `:offset`, `:rotate`, `:scale`, `:alpha`, `:tint`, `:outline`, `:visible`, `:clickable`, `:onClick`, `:exists`) plus its own.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `ghost:res()` | `string` | Unprotected | The resource name it draws. |
| `ghost:res(name, spawn_data)` | the ghost | Unprotected | Swap the visual to another resource, streaming in like `:add`. `spawn_data` is an optional byte array selecting a variant or state (`{0x01, 0x00}`). Rarely needed. |

## Look and orientation

Facing, translucency and tint are client-side render states, safe to set before the prop has streamed in.

```lua
local ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", position)
  :rotate(math.pi / 4)                        -- rotated 45 degrees
  :alpha(0.5)                                 -- half-translucent: the ghost look
  :tint{120, 180, 255}                        -- bluish overlay
ghost:res("gfx/terobjs/arch/timberhouse")     -- morph into a different building
ghost:alpha(1):tint(nil)                      -- fully opaque again, tint cleared
ghost:visible(false)                          -- take it out of the scene...
ghost:visible(true)                           -- ...and put it back
```

| Verb | Detail |
|---|---|
| `:alpha` | Opacity `0..1`. Below `1` the prop is see-through, and a translucent 3D object does not self-occlude: far faces show through near ones. |
| `:tint` | A colour overlay in the shape [`marker:color`](../map/markers.md), [`session:party`](../party.md) and [`session:kin`](../kin.md) use. The fourth component is the blend strength, independent of `:alpha`. |
| `:res(name)` | Swaps the resource. The new visual appears once it has loaded. |
| `:visible(false)` | Removes the ghost from the scene keeping position, look and clickability: cheaper than removing and re-placing. |
| `:scale` | `1` is original size. In place, around its own footprint, keeping position and facing. Clamped to a positive range. A saved layout or a drag handle reads `ghost:position()`, `ghost:rotate()` and `ghost:scale()` and writes them back. The special resource types that reset their transform (curio-style sprites, which ignore ghost rotation) ignore scale too. Building and terrain props scale. |

## Clickability

Opt-in with `ghost:clickable(true)`. A clickable ghost gains a pick surface. A click on it in the 3D view is detected and consumed: your handlers fire, the character does not walk or interact. A planner flips its ghosts clickable in an edit mode.

```lua
local ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", position)
  :clickable(true)
  :onClick(function(clicked_ghost, button, world_x, world_y)     -- 1 = left, 3 = right; then the clicked world point
    hafen.log():write("clicked my ghost with button " .. button)
  end)
-- or globally, for every clickable ghost this addon owns:
hafen.event():on("GhostClicked", function(event)
  hafen.virtual():ghost():remove(event:ghost())      -- event:ghost() event:button() event:x() event:y()
end)
```

| Rule | Detail |
|---|---|
| Both fire | The per-ghost `:onClick` and the [`GhostClicked`](../event/bus/world.md#world-ghosts-and-sprites) event, which reaches only your addon. |
| Still unprotected | Client-side detection: the pick pass returns the ghost and the bridge calls you. Nothing is sent. A non-clickable ghost has no pick surface and is click-through. |

## Layouts and persistence

Keep each ghost's [Position](../position.md), durable by construction, in [`hafen.store`](../store/README.md), and re-place the ghost there once it resolves: the rule [markers](../map/markers.md) follow. A base planner is clickable blueprint ghosts saved this way and reloaded at the same spot after a relog, retrying as the map streams in.

## Moving ghosts on the ground

Drag a ghost along the terrain, snapping as a real building placement does, with three primitives and `ghost:position(position)`.

| Step | Primitive |
|---|---|
| 1 | [The mouse's grab](../ui/mouse.md#the-grab) captures the pointer, so the camera stays put. |
| 2 | [`session:world():screenToWorld`](../world.md#the-screen-and-the-world) turns the cursor pixel into a ground Position. It reads a pixel, so `session` is [`hafen.session():current()`](../session.md). |
| 3 | [`session:world():snapPlace`](../world.md#the-screen-and-the-world) snaps it to the placement grid, Shift for the fine grid. |

Wired into a move mode. Select a ghost and take the grab. It follows the cursor snapped to the grid until you click to drop it. [The grab](../ui/mouse.md#the-grab) has the drag pattern in full.

---

## See Also

- [`hafen.virtual`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch.
- [Sprites](sprites.md) — your own image in the world, on the same core.
- [Position](../position.md) — the place an anchor is given, and the durable form it keeps.
- [`session:world():place`](../world.md#write-protected) — committing a real build, protected.
- [Events](../event/bus/world.md#world-ghosts-and-sprites) — `GhostClicked`.
