# hafen.ghost: client-only world props

A **ghost** is one of the game's own `.res` props rendered at a place you choose — a log cabin, a timber
house, a fence — with a facing, a translucency, a colour tint and a scale of its own. Reach for it to lay
something out over the real terrain before you build it. To stand your *own* image or model there instead,
use [`hafen.render`](render/README.md).

`hafen.ghost()` **is the collection** of the ghosts your addon has placed: `:add(res)` places one and hands
it back, `:list(filter)` reads them, `:remove(g)` ends one.

```lua
local p = hafen.player():gob():position()
local g = hafen.ghost():add("gfx/terobjs/arch/logcabin", p):alpha(0.5)
g:position(p:offset(33, 0))                 -- 3 tiles east; a tile is 11 world units
g:rotate(math.pi)
```

A ghost is **client-only**: a game object with **no server id**, so it is never sent to the server, the
server never learns it exists, and it grants no gameplay advantage. It is a visualization, exactly like a
HUD overlay.

> **Ungated.** Because nothing reaches the server, ghosts need no `actions` permission and no consent
> dialog. They sit alongside [a HUD overlay](ui/custom.md#overlays), not [`hafen.act`](act.md).
> Committing a *real* build is still the gated [`hafen.act():place`](act.md).

Everything here is **bridge-owned**: every ghost your addon creates is torn down automatically on reload,
disable and relogin, leaking nothing.

## The collection (ungated)

| Call | Returns | Description |
|---|---|---|
| `hafen.ghost():add(res, p)` | [ghost](#the-ghost) | stand a client-only prop at `p` and hand it back, ready to configure |
| `hafen.ghost():list(filter)` | [ghost](#the-ghost)`[]` | this addon's live ghosts, the ones it stood at a world point |
| `hafen.ghost():count(filter)` | number | how many, without building the array |
| `hafen.ghost():find(filter)` | [ghost](#the-ghost) \| nil | the first one that matches |
| `hafen.ghost():remove(g)` | the collection | end one now; also automatic on reload, disable and relogin |

`res` is a resource name, e.g. `"gfx/terobjs/arch/logcabin"`, resolved through the game resource pool, so
any server or client resource works, and `p` is a [Position](world.md#the-position-type). Both are required:
the scene resolves the tile under a prop as it enters it, so **a prop with no place cannot be built at all**
— which is why the place is an argument rather than a setter with a default. Everything else about it — how
it looks, whether it can be clicked, and where it moves to next — is a setter on what `:add` hands back.

`filter` is the canonical [filter](conventions.md#the-filter-argument): `nil` is all of them, a **string** is
a substring match on the ghost's resource name, and a **function** is called with the ghost itself, with a
truthy return keeping it.

The collection holds the ghosts **this section stands**. A `.res` prop hung on a game object belongs to that
object instead — it is one of its [overlays](gob.md#overlays), owned by the record the gob keeps — so it is
not a member here and has no separate handle to end. Read it back through `gob:overlay():get(key)`, and end
it by removing that key.

> **`:add` raises when you are not in the world.** A prop standing in the 3D scene needs that scene, so
> placing one before you have entered the world is an error rather than a `nil` you would only discover one
> setter later. Place from `OnEnterWorld` onward. Ground you have walked but that has not streamed back in
> is *not* an error: the prop waits, and appears as soon as its tiles arrive.

The prop appears a beat after `:add`: the resource resolves on a loader thread, so `:add` returns a working
ghost immediately while the visual streams in shortly after. Every verb works meanwhile — a `:position`
before the prop is visible simply sets where it will appear.

## The ghost

Every property is one name: calling it bare **reads**, calling it with a value **writes** and returns the
ghost, so a whole placement is one chain.

| Method | Description |
|---|---|
| `g:position()` | where it stands, as a [Position](world.md#the-position-type) |
| `g:position(p, a)` | stand it at `p`, optionally setting facing |
| `g:rotate()` / `g:rotate(a)` | facing in radians, keeping position |
| `g:res()` | the resource name it draws |
| `g:res(name, spawnData)` | swap the visual to another resource, streaming in like `:add`; `spawnData` is optional |
| `g:alpha()` / `g:alpha(a)` | opacity `0..1`, where `1` is opaque |
| `g:tint()` / `g:tint(r, g, b, a)` | colour overlay `0..255`; `nil` clears it |
| `g:scale()` / `g:scale(k)` | uniform scale, `1` being original size |
| `g:visible()` / `g:visible(b)` | whether it is in the 3D scene; `false` takes it out and keeps the ghost |
| `g:clickable()` / `g:clickable(b)` | the pick surface — see [clickability](#clickability) |
| `g:onClick()` / `g:onClick(fn)` | `fn(g, button, x, y)` fired on click, also delivered as [`GhostClicked`](event.md#world-ghosts-and-sprites) |
| `g:exists()` | is it still in the world? `false` once the collection removed it |

`spawnData` is an optional byte array selecting a resource variant or state, e.g. `{0x01, 0x00}` — rarely
needed.

## Look and orientation

A ghost's facing, translucency and tint are pure client-side render states on the virtual gob: they change
only how it looks, and all of them are safe to set before the prop has finished streaming in — the value is
applied the moment it appears.

```lua
local g = hafen.ghost():add("gfx/terobjs/arch/logcabin", p)
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
- **`:tint`** is a colour overlay in the **same shape** [`marker:color`](map/markers.md),
  [`hafen.party`](party.md) and [`hafen.kin`](kin.md) use. Its fourth component is the blend strength, how
  strongly the colour is mixed in, and it is independent of `:alpha`.
- **`:res(name)`** swaps the resource; like `:add`, the new visual resolves on a loader thread and streams
  in a beat later, so the call returns immediately.
- **`:visible(false)`** removes the ghost from the scene while keeping it alive, with its position, look and
  clickability all preserved. That is cheaper than removing and re-placing it just to toggle it.

## Scale

`:scale(1)` is a prop's original size, above that grows it and below that shrinks it. The scale is **in
place**: the model grows and shrinks around its own footprint, keeping its position and facing, and values
are clamped to a sane positive range.

```lua
local g = hafen.ghost():add("gfx/terobjs/arch/logcabin", p):scale(1.5)
g:scale(0.5)                                  -- now half size, live
local k = g:scale()                           -- reads back what the last write set
```

A saved layout, or the [gizmo](#the-transform-gizmo), reads `g:position()`, `g:rotate()` and `g:scale()`
and writes them back. A few special resource types reset their own transform — curio-style sprites, which
already ignore ghost rotation — and those ignore scale too. Building and terrain props scale correctly.

## Clickability

A ghost is **opt-in clickable**: call `g:clickable(true)`. A planner typically flips its ghosts clickable
only in an edit mode. A clickable ghost gains a pick surface, so a click on it in the 3D view is detected
and **consumed** — it fires your handlers and the character does **not** walk or interact.

```lua
local g = hafen.ghost():add("gfx/terobjs/arch/logcabin", p)
  :clickable(true)
  :onClick(function(g, button, x, y)     -- 1 = left, 3 = right; x, y = the clicked world point
    hafen.log():write("clicked my ghost with button " .. button)
  end)
-- or globally, for every clickable ghost this addon owns:
hafen.event():on("GhostClicked", function(ev)
  hafen.ghost():remove(ev.ghost)         -- ev = { ghost, button, x, y }
end)
```

Both the per-ghost `:onClick` and the [`GhostClicked`](event.md#world-ghosts-and-sprites) event fire on
every click, and `GhostClicked` reaches only *your* addon, since a ghost is private to the addon that made
it.

> **Still ungated.** Clickability is pure client-side detection: the engine's pick pass returns the ghost
> and the bridge calls you, and nothing is sent to the server. A **non-clickable** ghost carries no pick
> surface and never wins a pick, so it is click-through — clicks pass straight through it to the real object
> or the ground behind it, and ordinary play is unaffected.

## A ghost on a gob is an overlay

`hafen.ghost()` stands a prop at a **fixed world point**. To hang one on a *game object* so it tracks that
object every frame, the verb is [`gob:overlay()`](gob.md#overlays) — it keys the thing per addon, reads
back through that same collection, and **dies with the gob**, so a felled tree takes the prop on it with it.

```lua
hafen.player():gob():overlay():add("hat"):ghost("gfx/terobjs/arch/logcabin"):offset(0, 0, 20)
```

`hafen.ghost():add` takes no anchor of its own, and there is nowhere to pass one: anchoring to a game
object is the gob's own collection, and standing at a fixed point is this one.

## Layouts and persistence

To save a layout across sessions, keep each ghost's [Position](world.md#the-position-type) — it is durable
by construction, so [`hafen.store`](store.md) keeps it and hands the same place back next session — and
re-place the ghost there once it resolves. That is the same rule [markers](map/markers.md) follow. The
bundled **`planner`** addon is a small base planner built on exactly this: it places clickable blueprint
ghosts, saves them through [`hafen.store`](store.md), and reloads them at the same physical spot after a
relog, retrying as the map streams in.

## Moving ghosts on the ground

You can drag a ghost along the terrain, snapping exactly as placing a real building does, using three
primitives and then `g:position(p)`:

1. [`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) captures the mouse, so the camera stays put.
2. [`hafen.world():screenToWorld`](world.md#screen-to-world-and-placement-snapping) turns the cursor
   pixel into a ground Position.
3. [`hafen.world():snapPlace`](world.md#screen-to-world-and-placement-snapping) snaps it to the placement
   grid, with Shift for the fine grid.

`planner` wires these into a move mode: select a ghost, start the grab, and it follows the cursor snapped
to the grid until you click to drop it. See [`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) for the drag
pattern in full.

## The transform gizmo

A transform gizmo lets you move, rotate and scale a ghost by dragging on-screen handles:

- **Move** — red and green arrows for the world X and Y axes, plus a yellow centre square for a free
  ground-plane move. Drag an arrow and the ghost moves **along that axis only**, snapped to the placement
  grid, with Shift for the fine grid.
- **Rotate** — a cyan ring around the ghost. Drag it and the facing snaps to the placement angle, Shift
  giving the fine grid, identical to rotating a real building.
- **Scale** — a magenta box handle above the ring. Drag it out to grow, in to shrink.

The **camera stays put** while you drag. Handles are drawn with [`g`](ui/drawing.md) on a HUD overlay, so
they are always on top of the 3D scene, and the grab targets are a constant screen size at any zoom; only
the axis shafts foreshorten with the camera, which is what anchors the arrows in the world. No game
resource is needed.

The gizmo is a **bundled Lua library over the ghost, world and hook primitives**, not a built-in `hafen.*`
function: [a HUD overlay](ui/custom.md#overlays) to draw,
[`hafen.hook():input`](hook.md#hafenhookinputtarget-event-fn) to pick a handle,
[`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) with
[`screenToWorld`](world.md#screen-to-world-and-placement-snapping),
[`snapPlace`](world.md#screen-to-world-and-placement-snapping) and
[`snapAngle`](world.md#screen-to-world-and-placement-snapping) to drag, and `:position`, `:rotate` and
`:scale` to apply. It ships inside the `planner` addon, and its shape is:

```lua
-- gizmo.lua installs one global: gizmo(target, opts) -> a gizmo handle.
local gz = gizmo(myGhost, {           -- target = anything with :position(), ideally :rotate/:scale too
  mode = "all",                       -- "move" | "rotate" | "scale" | "all"
  onChange = function(t) --[[ t = {x, y, a, scale} during the drag ]] end,
  onCommit = function(t) --[[ t = {x, y, a, scale} on release; re-anchor and persist here ]] end,
})
gz:setMode("rotate")   -- switch which handles show
gz:mode()              -- the current mode string
gz:isDragging()        -- bool
gz:detach()            -- remove the handles, input hooks and any active grab; idempotent
```

Because the gizmo drives anything with `:position`, `:rotate` and `:scale`, it moves a
[sprite](render/sprites.md) and an [object](render/models.md) exactly as it moves a ghost.

## See also

- [`hafen.render`](render/README.md) — the same world entity for your own images and models
- [`hafen.world`](world.md#the-position-type) — the Position type, and the snapping the gizmo uses
- [`hafen.hook`](hook.md#hafenhookgrabmove-up) — the mouse-capture primitive behind a drag
- [`hafen.act():place`](act.md) — committing a real build, which is gated
- [events](event.md#world-ghosts-and-sprites) — `GhostClicked`
