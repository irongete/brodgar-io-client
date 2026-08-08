# The transform gizmo

A transform gizmo lets you move, rotate and scale a thing standing in the world by dragging on-screen
handles. It works on any of the kinds — a [ghost](ghosts.md), a [sprite](sprites.md), an
[object](models.md) — because all three answer the same `:position`, `:rotate` and `:scale`.

- **Move** — red and green arrows for the world X and Y axes, plus a yellow centre square for a free
  ground-plane move. Drag an arrow and the thing moves **along that axis only**, snapped to the placement
  grid, with Shift for the fine grid.
- **Rotate** — a cyan ring around it. Drag the ring and the facing snaps to the placement angle, Shift
  giving the fine grid, identical to rotating a real building.
- **Scale** — a magenta box handle above the ring. Drag it out to grow, in to shrink.

The **camera stays put** while you drag. Handles are drawn with [`g`](../ui/drawing.md) on a HUD overlay, so
they are always on top of the 3D scene, and the grab targets are a constant screen size at any zoom; only
the axis shafts foreshorten with the camera, which is what anchors the arrows in the world. No game
resource is needed.

## It is a library, not a section

The gizmo is a **bundled Lua library over the world, overlay and widget-input primitives**, not a
`hafen.*` function: [a HUD overlay](../ui/custom.md#overlays) to draw,
[input subscriptions](../ui/widget.md#subscribing) to pick a handle,
[the grab](../ui/widget.md#the-grab) with
[`screenToWorld`](../world.md#screen-to-world-and-placement-snapping),
[`snapPlace`](../world.md#screen-to-world-and-placement-snapping) and
[`snapAngle`](../world.md#screen-to-world-and-placement-snapping) to drag, and `:position`, `:rotate` and
`:scale` to apply. It ships inside the `planner` addon, and its shape is:

```lua
-- gizmo.lua installs one global: gizmo(target, opts) -> a gizmo handle.
local gz = gizmo(myGhost, {      -- target = anything with :position(), ideally :rotate/:scale
  mode = "all",                       -- "move" | "rotate" | "scale" | "all"
  onChange = function(t) --[[ t = {x, y, a, scale} during the drag ]] end,
  onCommit = function(t) --[[ t = {x, y, a, scale} on release; re-anchor and persist here ]] end,
})
gz:setMode("rotate")   -- switch which handles show
gz:mode()              -- the current mode string
gz:isDragging()        -- bool
gz:detach()            -- remove the handles, input subscriptions and any active grab; idempotent
```

Because it drives anything with `:position`, `:rotate` and `:scale`, it moves a sprite and an object exactly
as it moves a ghost. A thing that **follows a gob** has no `:position` to write — its place is the gob's —
so give the gizmo one that stands at a point.

## See also

- [`hafen.vr`](README.md) — the section whose entities the gizmo drives
- [`hafen.world`](../world.md#screen-to-world-and-placement-snapping) — the snapping every handle uses
- [the mouse and its grab](../ui/widget.md#the-mouse) — the capture primitive behind a drag
- [drawing](../ui/drawing.md) — how the handles themselves are painted
