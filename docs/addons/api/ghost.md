# hafen.ghost: client-only world props

A **ghost** is one of the game's own `.res` props rendered at a world coordinate you choose — a log cabin,
a timber house, a fence — with a facing, a translucency, a colour tint and a scale of its own. Reach for it
to lay something out over the real terrain before you build it. To stand your *own* image or model there
instead, use [`hafen.render`](render/README.md).

```lua
local p = hafen.player():gob():position()
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", x = p:x(), y = p:y(), alpha = 0.5 }
g:move(p.x + 33, p.y)                       -- 3 tiles east; a tile is 11 world units
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

## Create (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.ghost.new(opts)` | [ghost handle](#the-ghost-handle) \| nil | create a client-only prop; `nil` if you are not in the world yet |
| `hafen.ghost.list(filter)` | [ghost handle](#the-ghost-handle)`[]` | this addon's live ghosts, the ones it stood at a world point |

`new` options:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `res` | string | *required* | resource name, e.g. `"gfx/terobjs/arch/logcabin"`, resolved through the game resource pool, so any server or client resource works |
| `x`, `y` | number | *required* | world coordinates, login-relative, the same as [`gob:position()`](gob.md) |
| `a` | number | `0` | facing, in radians |
| `sdt` | byte array | *none* | spawn-data bytes selecting a resource variant or state, e.g. `{0x01, 0x00}`; rarely needed |
| `alpha` | number | `1` | opacity `0..1`; below `1` gives the translucent ghost look |
| `tint` | `{r, g, b, a}` | *none* | colour overlay, `0..255`, where `a` is blend strength |
| `scale` | number | `1` | uniform scale; `1` is original size |
| `clickable` | boolean | `false` | opt-in pick surface — see [clickability](#clickability) |
| `onClick` | function | *none* | `fn(g, button, x, y)` fired on click, also delivered as the [`GhostClicked`](event.md#world-ghosts-and-sprites) event |

For `list`, `filter` is the canonical [filter](conventions.md#the-filter-argument) adapted to handles:
`nil` is all of them, a **string** is a substring match on the ghost's `res`, and a **function** is called
with the ghost **handle**, so it can call `g:pos()`, with a truthy return keeping it.

`list` answers the ghosts **this namespace stands**. A `.res` prop hung on a game object belongs to that
object instead — it is one of its [overlays](gob.md#overlays), owned by the record the gob keeps — so it
is not in `list` and has no handle of its own to `:destroy()`. Read it back through
`gob:overlay():get(key)`, and end it by removing that key.

> **The prop appears a beat after `new`.** The resource resolves on a loader thread, so `new` returns a
> working handle immediately while the visual streams in shortly after. Every handle method works
> meanwhile: a `:move` before the prop is visible simply sets where it will appear.

## The ghost handle

Every method returns the handle except `:pos()` and `:res()`, so calls chain.

| Method | Description |
|---|---|
| `g:move(x, y, a)` | reposition to world `x, y`, optionally setting facing |
| `g:rotate(a)` | set facing in radians, keeping position |
| `g:setRes(res, sdt)` | swap the visual to another resource, streaming in like `new`; `sdt` is optional |
| `g:alpha(a)` | opacity `0..1`, where `1` is opaque |
| `g:tint(color)` | colour overlay `{r, g, b, a}`, `0..255`; `nil` clears it |
| `g:scale(s)` | uniform scale, `1` being original size |
| `g:show()` / `g:hide()` | add to or remove from the 3D scene, keeping the ghost |
| `g:pos()` | `{x, y, a, scale}` |
| `g:res()` | the resource name |
| `g:clickable(bool)` | toggle the pick surface |
| `g:destroy()` | remove it now; also automatic on reload and disable. Idempotent |

## Look and orientation

A ghost's facing, translucency and tint are pure client-side render states on the virtual gob: they change
only how it looks, and all of them are safe to set before the prop has finished streaming in — the value is
applied the moment it appears.

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  a     = math.pi / 4,                        -- rotated 45 degrees
  alpha = 0.5,                                -- half-translucent: the ghost look
  tint  = { r = 120, g = 180, b = 255 },      -- bluish overlay
}
g:setRes("gfx/terobjs/arch/timberhouse")      -- morph into a different building
g:alpha(1):tint(nil)                          -- fully opaque again, tint cleared
g:hide()                                      -- take it out of the scene...
g:show()                                      -- ...and put it back
```

- **`alpha`** is opacity `0..1`. Below `1` the prop becomes see-through; a translucent 3D object does not
  self-occlude, so you see its far faces through its near ones, the usual hologram appearance.
- **`tint`** is a colour overlay in the **same shape** [`marker:color`](map/markers.md),
  [`hafen.party`](party.md) and [`hafen.kin`](kin.md) use. Its `a` is the blend strength, how strongly the
  colour is mixed in, and it is independent of `alpha`.
- **`:setRes`** swaps the resource; like `new`, the new visual resolves on a loader thread and streams in a
  beat later, so the call returns immediately.
- **`:show` and `:hide`** remove and re-add the ghost while keeping it alive, with its handle, position,
  look and clickability all preserved. That is cheaper than destroying and recreating it just to toggle it.

## Scale

`scale = 1` is a prop's original size, above that grows it and below that shrinks it. The scale is **in
place**: the model grows and shrinks around its own footprint, keeping its position and facing, and values
are clamped to a sane positive range.

```lua
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", x = wx, y = wy, scale = 1.5 }
g:scale(0.5)                                  -- now half size, live
local t = g:pos()                             -- {x, y, a, scale}: scale is part of the transform
```

`g:pos()` returns the current `scale` alongside `x`, `y` and `a`, so a saved layout, or the
[gizmo](#the-transform-gizmo), can read and restore it. A few special resource types reset their own
transform — curio-style sprites, which already ignore ghost rotation — and those ignore scale too. Building
and terrain props scale correctly.

## Clickability

A ghost is **opt-in clickable**: pass `clickable = true` to `new`, or call `g:clickable(true)` later. A
planner typically flips its ghosts clickable only in an edit mode. A clickable ghost gains a pick surface,
so a click on it in the 3D view is detected and **consumed** — it fires your handlers and the character
does **not** walk or interact.

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  clickable = true,
  onClick = function(g, button, x, y)      -- 1 = left, 3 = right; x, y = the clicked world point
    hafen.log():write("clicked my ghost with button " .. button)
  end,
}
-- or globally, for every clickable ghost this addon owns:
hafen.event():on("GhostClicked", function(ev)
  ev.ghost:destroy()                       -- ev = { ghost, button, x, y }
end)
```

Both the per-ghost `onClick` and the [`GhostClicked`](event.md#world-ghosts-and-sprites) event fire on every
click, and `GhostClicked` reaches only *your* addon, since a ghost is private to the addon that made it.

> **Still ungated.** Clickability is pure client-side detection: the engine's pick pass returns the ghost
> and the bridge calls you, and nothing is sent to the server. A **non-clickable** ghost carries no pick
> surface and never wins a pick, so it is click-through — clicks pass straight through it to the real object
> or the ground behind it, and ordinary play is unaffected.

## A ghost on a gob is an overlay

`hafen.ghost` stands a prop at a **fixed world point**. To hang one on a *game object* so it tracks that
object every frame, the verb is [`gob:overlay()`](gob.md#overlays) — it keys the thing per addon, reads
back through that same collection, and **dies with the gob**, so a felled tree takes the prop on it with it.

```lua
hafen.player():gob():overlay():add("hat"):ghost("gfx/terobjs/arch/logcabin"):offset(0, 0, 20)
```

`hafen.ghost.new` takes no anchor of its own: a `follow` or an `offset` key in its table **raises**,
naming the gob's own collection, rather than standing the prop somewhere you did not ask for.

## Layouts and persistence

> **Ghost coordinates are login-relative**, like all world coords, so they are neither shareable nor
> persistent as they stand.

To save a layout across sessions, anchor each ghost on a **grid id** with
[`gob:position()`](world.md#the-position-type) — which persists as a grid anchor and comes back as a
Position — and place the ghost at its `:x()`/`:y()` once it resolves. The same rule
[markers](map/markers.md) follow. The bundled **`planner`** addon is a small base planner built on exactly
this: it places clickable blueprint ghosts, saves them grid-anchored through
[`hafen.store`](store.md), and reloads them at the same physical spot after a relog, retrying as the map
streams in.

## Moving ghosts on the ground

You can drag a ghost along the terrain, snapping exactly as placing a real building does, using three
primitives and then `ghost:move`:

1. [`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) captures the mouse, so the camera stays put.
2. [`hafen.world():screenToWorld`](world.md#screen-to-world-and-placement-snapping) turns the cursor
   pixel into a ground coordinate.
3. [`hafen.world():snapPlace`](world.md#screen-to-world-and-placement-snapping) snaps it to the placement grid,
   with Shift for the fine grid.

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

The gizmo is a **bundled Lua library over the ghost, map and hook primitives**, not a built-in `hafen.*`
function: [a HUD overlay](ui/custom.md#overlays) to draw,
[`hafen.hook():input`](hook.md#hafenhookinputtarget-event-fn) to pick a handle,
[`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) with
[`screenToWorld`](world.md#screen-to-world-and-placement-snapping),
[`snapPlace`](world.md#screen-to-world-and-placement-snapping) and
[`snapAngle`](world.md#screen-to-world-and-placement-snapping) to drag, and `g:move`, `g:rotate` and
`g:scale` to apply. It ships inside the `planner` addon, and its shape is:

```lua
-- gizmo.lua installs one global: gizmo(target, opts) -> a gizmo handle.
local gz = gizmo(myGhost, {           -- target = anything with :pos()/:move, ideally :rotate/:scale too
  mode = "all",                       -- "move" | "rotate" | "scale" | "all"
  onChange = function(t) --[[ t = {x, y, a, scale} during the drag ]] end,
  onCommit = function(t) --[[ t = {x, y, a, scale} on release; re-anchor and persist here ]] end,
})
gz:setMode("rotate")   -- switch which handles show
gz:mode()              -- the current mode string
gz:isDragging()        -- bool
gz:detach()            -- remove the handles, input hooks and any active grab; idempotent
```

Because the gizmo drives any handle with `:pos`, `:move`, `:rotate` and `:scale`, it moves a
[sprite](render/sprites.md) and an [object](render/models.md) exactly as it moves a ghost.

## See also

- [`hafen.render`](render/README.md) — the same world entity for your own images and models
- [`hafen.world`](world.md#the-position-type) — grid anchoring, and the snapping
  the gizmo uses
- [`hafen.hook`](hook.md#hafenhookgrabmove-up) — the mouse-capture primitive behind a drag
- [`hafen.act():place`](act.md) — committing a real build, which is gated
- [events](event.md#world-ghosts-and-sprites) — `GhostClicked`
