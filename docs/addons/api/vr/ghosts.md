# hafen.vr: one of the game's own props

A **ghost** is one of the game's own `.res` props rendered at a place you choose — a log cabin, a timber
house, a fence — with a facing, a translucency, a colour tint and a scale of its own. Reach for it to lay
something out over the real terrain before you build it. To stand your *own* image or model there instead,
use [sprites](sprites.md) and [models](models.md).

`hafen.vr():ghost()` **is the collection** of the ghosts your addon has placed: `:add(res, anchor)` places
one and hands it back, `:list(filter)` reads them, `:remove(g)` ends one — the whole
[collection shape](README.md#the-collections-ungated).

```lua
local p = hafen.player():gob():position()
local g = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p):alpha(0.5)
g:position(p:offset(33, 0))                 -- 3 tiles east; a tile is 11 world units
g:rotate(math.pi)
```

`res` is a resource name, e.g. `"gfx/terobjs/arch/logcabin"`, resolved through the game resource pool, so
any server or client resource works. A ghost has no name of its own, so a string `filter` matches that
resource name. The [anchor](README.md#the-anchor-is-an-argument) is a
[Position](../world.md#the-position-type) to stand it at a point or a [Gob](../gob.md) to make it follow one.

A ghost is **client-only**: a game object with **no server id**, so it is never sent to the server, the
server never learns it exists, and it grants no gameplay advantage. It is a visualization, exactly like a
HUD overlay — see [the section's gating note](README.md).

The prop appears a beat after `:add`: the resource resolves on a loader thread, so `:add` returns a working
ghost immediately while the visual streams in shortly after. Every verb works meanwhile — a `:position`
before the prop is visible simply sets where it will appear.

## The ghost

The [shared vocabulary](README.md#one-vocabulary-four-kinds) — `:position`, `:offset`, `:rotate`, `:scale`,
`:alpha`, `:tint`, `:visible`, `:clickable`, `:onClick`, `:exists` — plus the two verbs only a ghost has.

| Method | Description |
|---|---|
| `g:res()` | the resource name it draws |
| `g:res(name, spawnData)` | swap the visual to another resource, streaming in like `:add`; `spawnData` is optional |

`spawnData` is an optional byte array selecting a resource variant or state, e.g. `{0x01, 0x00}` — rarely
needed.

## Look and orientation

A ghost's facing, translucency and tint are pure client-side render states on the virtual game object: they
change only how it looks, and all of them are safe to set before the prop has finished streaming in — the
value is applied the moment it appears.

```lua
local g = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)
  :rotate(math.pi / 4)                        -- rotated 45 degrees
  :alpha(0.5)                                 -- half-translucent: the ghost look
  :tint(120, 180, 255)                        -- bluish overlay
g:res("gfx/terobjs/arch/timberhouse")         -- morph into a different building
g:alpha(1):tint(nil)                          -- fully opaque again, tint cleared
g:visible(false)                              -- take it out of the scene...
g:visible(true)                               -- ...and put it back
```

- **`:alpha`** is opacity `0..1`. Below `1` the prop becomes see-through; a translucent 3D object does not
  self-occlude, so you see its far faces through its near ones, the usual hologram appearance.
- **`:tint`** is a colour overlay in the **same shape** [`marker:color`](../map/markers.md),
  [`hafen.party`](../party.md) and [`hafen.kin`](../kin.md) use. Its fourth component is the blend strength,
  how strongly the colour is mixed in, and it is independent of `:alpha`.
- **`:res(name)`** swaps the resource; like `:add`, the new visual resolves on a loader thread and streams
  in a beat later, so the call returns immediately.
- **`:visible(false)`** removes the ghost from the scene while keeping it alive, with its position, look and
  clickability all preserved. That is cheaper than removing and re-placing it just to toggle it.

## Scale

`:scale(1)` is a prop's original size, above that grows it and below that shrinks it. The scale is **in
place**: the model grows and shrinks around its own footprint, keeping its position and facing, and values
are clamped to a sane positive range.

```lua
local g = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p):scale(1.5)
g:scale(0.5)                                  -- now half size, live
local k = g:scale()                           -- reads back what the last write set
```

A saved layout, or the [gizmo](gizmo.md), reads `g:position()`, `g:rotate()` and `g:scale()` and writes them
back. A few special resource types reset their own transform — curio-style sprites, which already ignore
ghost rotation — and those ignore scale too. Building and terrain props scale correctly.

## Clickability

A ghost is **opt-in clickable**: call `g:clickable(true)`. A planner typically flips its ghosts clickable
only in an edit mode. A clickable ghost gains a pick surface, so a click on it in the 3D view is detected
and **consumed** — it fires your handlers and the character does **not** walk or interact.

```lua
local g = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)
  :clickable(true)
  :onClick(function(g, button, x, y)     -- 1 = left, 3 = right; x, y = the clicked world point
    hafen.log():write("clicked my ghost with button " .. button)
  end)
-- or globally, for every clickable ghost this addon owns:
hafen.event():on("GhostClicked", function(ev)
  hafen.vr():ghost():remove(ev:ghost())  -- ev:ghost() ev:button() ev:x() ev:y()
end)
```

Both the per-ghost `:onClick` and the [`GhostClicked`](../event.md#world-ghosts-and-sprites) event fire on
every click, and `GhostClicked` reaches only *your* addon, since a ghost is private to the addon that made
it.

> **Still ungated.** Clickability is pure client-side detection: the engine's pick pass returns the ghost
> and the bridge calls you, and nothing is sent to the server. A **non-clickable** ghost carries no pick
> surface and never wins a pick, so it is click-through — clicks pass straight through it to the real object
> or the ground behind it, and ordinary play is unaffected.

## Layouts and persistence

To save a layout across sessions, keep each ghost's [Position](../world.md#the-position-type) — it is
durable by construction, so [`hafen.store`](../store.md) keeps it and hands the same place back next session
— and re-place the ghost there once it resolves. That is the same rule [markers](../map/markers.md) follow.
The bundled **`planner`** addon is a small base planner built on exactly this: it places clickable blueprint
ghosts, saves them through [`hafen.store`](../store.md), and reloads them at the same physical spot after a
relog, retrying as the map streams in.

## Moving ghosts on the ground

You can drag a ghost along the terrain, snapping exactly as placing a real building does, using three
primitives and then `g:position(p)`:

1. [the mouse's grab](../ui/widget.md#the-grab) captures the pointer, so the camera stays put.
2. [`hafen.world():screenToWorld`](../world.md#screen-to-world-and-placement-snapping) turns the cursor
   pixel into a ground Position.
3. [`hafen.world():snapPlace`](../world.md#screen-to-world-and-placement-snapping) snaps it to the placement
   grid, with Shift for the fine grid.

`planner` wires these into a move mode: select a ghost, take the grab, and it follows the cursor snapped
to the grid until you click to drop it. See [the grab](../ui/widget.md#the-grab) for the drag pattern in
full, and [the gizmo](gizmo.md) for drag handles that do the same job with no code of your own.

## See also

- [`hafen.vr`](README.md) — the section: the anchor, the shared verbs, and the whole-section switch
- [sprites](sprites.md) — your own image in the world, on the same core
- [`hafen.world`](../world.md#the-position-type) — the Position type, and the snapping a drag uses
- [`hafen.act():place`](../act.md) — committing a real build, which is gated
- [events](../event.md#world-ghosts-and-sprites) — `GhostClicked`
