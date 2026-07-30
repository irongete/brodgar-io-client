# hafen.ghost — client-only world ghosts

Place **virtual props in the 3D world** — "ghosts" rendered at arbitrary world coordinates, optionally
**clickable** (V2), with a **look & orientation** — facing, translucency and colour tint (V3) — and a uniform
**scale** (V6), and manipulable with a **[transform gizmo](#transform-gizmo-move--rotate--scale)**. A ghost is
**client-only**: it is a game object with **no server id**, so it is never
sent to the server, the server never learns it exists, and it grants no gameplay advantage — it is a
visualization, exactly like a HUD overlay. The motivating use is **city / base planning**: lay out ghost
buildings over the real terrain and iterate.

> **Safe-tier — not gated.** Because nothing reaches the server, ghosts need **no** `actions` permission
> and no consent dialog. They sit alongside [`hafen.ui.overlay`](ui.md#overlays), not
> [`hafen.act`](actions.md). Committing a *real* build is still the gated `hafen.act.place`.

Everything here is **bridge-owned**: every ghost your addon creates is torn down automatically on
reload / disable / relogin (the scene slot is removed and the sprite freed), leaking nothing.

## Functions

| Function | Returns | Description |
|---|---|---|
| `hafen.ghost.new{res, x, y [, a, sdt, alpha, tint, scale, clickable, onClick, follow, offset]}` | [ghost handle](#ghost-handle) \| nil | create a client-only prop; nil if not in the world yet. `follow` = a gob to [anchor](#anchoring-to-a-gob) to |
| `hafen.ghost.list([filter])` | [ghost handle](#ghost-handle)`[]` | this addon's live ghosts |

`new` options:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `res` | string | — (required) | resource name — a game object, e.g. `"gfx/terobjs/arch/logcabin"` (resolved via the game resource pool, so any server or client resource works) |
| `x`, `y` | number | — (required) | world coordinates (login-relative, the same as [`gob:pos()`](gob.md)) |
| `a` | number | `0` | facing, in radians |
| `sdt` | byte array | — | spawn-data bytes (resource variant/state), e.g. `{0x01, 0x00}` — advanced, rarely needed (V3) |
| `alpha` | number | `1` | opacity `0..1`; `< 1` gives the translucent "ghost" look (V3) — see [Look & orientation](#look--orientation-v3) |
| `tint` | `{r, g, b [, a]}` | — | colour overlay, `0..255` (`a` = blend strength, default `255`) (V3) |
| `scale` | number | `1` | uniform scale — `1` is original size (V6); see [Scale](#scale-v6) |
| `clickable` | boolean | `false` | opt-in pick surface (V2) — see [Clickability](#clickability--the-ghostclicked-event-v2) |
| `onClick` | function | — | `fn(g, button, x, y)` fired on click (V2); also delivered as the [`GhostClicked`](events.md#world-ghosts) event |

For `list`, `filter` is the canonical [filter](conventions.md#the-filter-argument), adapted to handles:
`nil` = all; a **string** = substring match on the ghost's `res`; a **function** is called with the ghost
**handle** (so it can call `g:pos()` etc.), truthy keeps it.

## Ghost handle

| Method | Description |
|---|---|
| `g:move(x, y [, a])` | reposition to world `x, y` (and optionally set facing `a`) |
| `g:rotate(a)` | set facing to `a` radians, keeping position (V3) |
| `g:setRes(res [, sdt])` | swap the visual to another resource (streams in like `new`); optional `sdt` bytes (V3) |
| `g:alpha(a)` | set opacity `0..1` (`1` = opaque) (V3) — see [Look & orientation](#look--orientation-v3) |
| `g:tint(color)` | set the colour overlay `{r, g, b [, a]}` (`0..255`), or `nil` to clear (V3) |
| `g:scale(s)` | set the uniform scale (`1` = original size) (V6) — see [Scale](#scale-v6) |
| `g:show()` / `g:hide()` | add / remove the ghost from the 3D scene, keeping it (V3) |
| `g:follow(gob [, {x=,y=,z=}])` | **anchor** to a gob so it follows automatically; `g:follow(nil)` detaches — see [Anchoring](#anchoring-to-a-gob) |
| `g:offset{x=, y=, z=}` | change the world offset from the followed gob (keeps following) |
| `g:pos()` | `{x, y, a, scale}` (+ `following` = the anchored gob id, if any) — the ghost's live transform |
| `g:res()` | the resource name (string) |
| `g:clickable(bool)` | toggle the pick surface (V2) — see [Clickability](#clickability--the-ghostclicked-event-v2) |
| `g:destroy()` | remove it now (also automatic on reload/disable). Idempotent. |

Every method returns the handle (except `:pos`/`:res`), so calls chain. A plain `g:move` **detaches** any follow.

## Anchoring to a gob

A ghost (like a [sprite](render.md#anchoring-to-a-gob)) can be **anchored to a gob** so it follows it every frame,
with no per-tick code of your own — the world analog of a [`hafen.ui.gobOverlay`](ui.md#overlays). Pass a `follow`
target (a [Gob object](gob.md)) to `new`, or call `g:follow(gob)` later; an optional world `offset`
`{x=, y=, z=}` (`z` = up) places it relative to the gob. It keeps its own facing/scale, `g:offset{…}` adjusts the
offset while it keeps following, and a manual `g:move` detaches it.

```lua
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", follow = hafen.player():gob(), offset = { z = 20 } }
-- the cabin now floats over your head and follows you; g:follow(nil) drops it in place
```

```lua
local p = hafen.player():gob():pos()
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", x = p.x, y = p.y }
g:move(p.x + 33, p.y)                       -- 3 tiles east (a tile is 11 world units)
g:setRes("gfx/terobjs/arch/timberhouse"):rotate(math.pi):alpha(0.5)  -- V3, chained
print(#hafen.ghost.list())     -- 1
g:destroy()
```

> **The prop appears a beat after `new`.** The resource resolves on a loader thread (dodging the engine's
> `Loading` state, the same way [`hafen.sound.play`](audio.md) does), so `new` returns a working handle
> immediately while the visual streams in shortly after. Every handle method works meanwhile — a `:move`
> before the prop is visible just sets where it will appear.

> **Ghost coordinates are login-relative**, like all world coords — not shareable or persistent as-is.
> To save a layout across sessions, anchor on **grid ids** via [`hafen.map.gridPos()`](map.md) and re-resolve
> on load with [`hafen.map.fromGridPos()`](map.md#saving-a-world-position-across-sessions) (the same rule
> [markers](markers.md) follow). The [`planner`](../../../addons/planner) example addon does this for a whole
> layout — see [conventions](conventions.md#coordinates).

## Look & orientation (V3)

A ghost can be given a **facing**, a **translucent** appearance, and a **colour tint** — either at `new` or live
via the handle. These are pure client-side render states on the virtual gob; they change only how it looks.

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  a     = math.pi / 4,                        -- rotated 45°
  alpha = 0.5,                                -- half-translucent — the "ghost" look
  tint  = { r = 120, g = 180, b = 255 },      -- bluish overlay (a defaults to 255)
}
g:rotate(math.pi)                             -- face the other way (position kept)
g:setRes("gfx/terobjs/arch/timberhouse")      -- morph into a different building
g:alpha(1)                                    -- fully opaque again
g:tint(nil)                                   -- clear the tint
g:hide()                                      -- take it out of the scene...
g:show()                                      -- ...and put it back
```

- **`alpha`** is opacity `0..1`: `1` is fully opaque (the default), and below `1` the prop becomes see-through —
  the translucent "ghost" look. (A translucent 3D object does not self-occlude — you see its far faces through its
  near ones, the usual hologram/x-ray appearance.)
- **`tint`** is a colour overlay in `{r, g, b [, a]}`, `0..255` — the **same colour shape** as
  [`hafen.markers`](markers.md) and the colours returned by [`hafen.party`](party.md)/[`hafen.kin`](kin.md). Its
  `a` is the blend strength (how strongly the colour is mixed in), defaulting to `255`. It is a colour overlay
  only, independent of `alpha`. `g:tint(nil)` clears it.
- **`:setRes`** swaps the resource; like `new`, the new visual resolves on a loader thread and streams in a beat
  later, so the call returns immediately.
- **`:show`/`:hide`** remove and re-add the ghost from the 3D scene while keeping it alive (its handle, position,
  look and clickability are all preserved) — cheaper than destroy + recreate when you just want to toggle it.

All of these are safe to call before the prop has finished streaming in — the value you set is applied the moment
it appears.

## Scale (V6)

A ghost can be **uniformly scaled** — `scale = 1` is its original size, `> 1` grows it, `< 1` shrinks it:

```lua
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", x = wx, y = wy, scale = 1.5 }  -- 50% bigger
g:scale(0.5)                                  -- ...now half size (live)
local t = g:pos()                             -- { x, y, a, scale } — scale is part of the transform
```

- The scale is **in place** — the model grows/shrinks around its own footprint (its feet), keeping its position
  and facing. Values are clamped to a sane positive range.
- `g:pos()` returns the current `scale` alongside `x, y, a`, so a saved layout (or a
  [gizmo](#transform-gizmo-move--rotate--scale)) can read and restore it.
- Scale rides the same render path as `tint`/`alpha` (a client-only state on the virtual gob). A few special
  resource types that reset their own transform (curio-style sprites that already ignore ghost rotation) will
  ignore scale too — building/terobj props, the planner's use case, scale correctly.

## Clickability & the `GhostClicked` event (V2)

A ghost is **opt-in clickable** — pass `clickable = true` to `new`, or call `g:clickable(true)` later (a
planner typically flips its ghosts clickable only in an "edit mode"). A clickable ghost gains a pick
surface, so a click on it in the 3D view is detected and **consumed** — it fires your handlers and the
character does **not** walk or interact.

```lua
local g = hafen.ghost.new{
  res = "gfx/terobjs/arch/logcabin", x = wx, y = wy,
  clickable = true,
  onClick = function(g, button, x, y)      -- 1 = left, 3 = right; x,y = clicked world point
    hafen.log("clicked my ghost with button " .. button)
  end,
}
-- or globally, for every clickable ghost this addon owns:
hafen.events.on("GhostClicked", function(ev)
  ev.ghost:destroy()                       -- ev = { ghost, button, x, y }
end)
g:clickable(false)                          -- back to decorative / click-through
```

Both the per-ghost `onClick` and the [`GhostClicked`](events.md#world-ghosts) event fire on every click of
a clickable ghost; `GhostClicked` reaches only *your* addon (a ghost is private to the addon that made it).

> **Still safe-tier.** Clickability is **pure client-side detection** — the engine's pick pass returns the
> ghost and the bridge calls you; **nothing is sent to the server**, so it needs no `actions` permission.
> A **non-clickable** ghost carries no pick surface and never wins a pick, so it is click-through — clicks
> pass straight through it to the real object (or the ground) behind it, and ordinary play is unaffected.

## Layouts & persistence (V4)

Ghost coordinates are login-relative, so a **saved layout** anchors each ghost on a **grid id** and re-resolves it
on load — see [`hafen.map.gridPos`](map.md) / [`hafen.map.fromGridPos`](map.md#saving-a-world-position-across-sessions).
The [`planner`](../../../addons/planner) example addon is a small base planner built on exactly this: it places
clickable blueprint ghosts, saves them grid-anchored via [`hafen.store`](store.md), and reloads them at the same
physical spot after a relog (retrying as the map streams in). Use it as the reference for persisting your own ghosts.

## Moving ghosts on the ground (V5)

You can drag a ghost along the terrain, snapping **exactly like placing a real building** (the `:placegrid` setting),
using three primitives — [`hafen.hook.grab`](hooks.md#hafenhookgrab) (capture the mouse; camera stays put),
[`hafen.map.screenToWorld`](map.md#screen--world--placement-snapping-v5) (cursor pixel → ground coord), and
[`hafen.map.snapPlace`](map.md#screen--world--placement-snapping-v5) (snap to the placegrid, SHIFT = fine) — then
`ghost:move`. The [`planner`](../../../addons/planner) example addon wires these into a move-mode: select a ghost,
`:planner grab`, and it follows the cursor snapped to the placegrid until you click to drop it. See
[`hafen.hook.grab`](hooks.md#hafenhookgrab) for the drag pattern.

## Transform gizmo (move / rotate / scale)

A **Unity-style transform gizmo** lets you move, rotate and scale a ghost by dragging on-screen handles:

- **Move** — red = world **X**, green = world **Y** axis arrows + a yellow **centre** square for a free
  ground-plane move. Drag an arrow and the ghost moves **along that axis only** (the centre moves freely),
  snapped to the `:placegrid` (SHIFT = fine).
- **Rotate** (V6) — a cyan **ring** around the ghost. Drag it and the facing snaps to the `:placeangle`
  (45° by default, SHIFT = the fine grid) — identical to rotating a real building.
- **Scale** (V6) — a magenta **box** handle above the ring. Drag it **out** to grow / **in** to shrink
  (uniform `g:scale`).

The **camera stays put** while you drag. Handles are drawn with [`g:draw`](ui.md#the-g-draw-wrapper) on a HUD
overlay — so they're always **on top** of the 3D scene — and the grab targets are a **constant screen size** at any
zoom (only the axis shafts foreshorten with the camera, anchoring the arrows in the world). No game resource needed.

Per [D-031](../../../specs/addons/decisions/virtual-entities.md), the gizmo is a **bundled Lua library over the ghost/map/hook
primitives** ([`hafen.ui.overlay`](ui.md#overlays) to draw, [`hafen.hook.input`](hooks.md#hafenhookinput) to pick a
handle, [`hafen.hook.grab`](hooks.md#hafenhookgrab) + [`hafen.map.screenToWorld`](map.md#screen--world--placement-snapping-v5)
+ [`hafen.map.snapPlace`](map.md#screen--world--placement-snapping-v5) / [`hafen.map.snapAngle`](map.md#screen--world--placement-snapping-v5)
to drag, and `g:move`/`g:rotate`/`g:scale` to apply) — **not** a built-in `hafen.*` function. It ships as
[`planner/gizmo.lua`](../../../addons/planner/gizmo.lua); the shape is:

```lua
-- gizmo.lua installs one global: gizmo(target, opts) -> a gizmo handle.
local gz = gizmo(myGhost, {                       -- target = anything with :pos()/:move (ideally :rotate/:scale too)
  mode = "all",                                   -- "move" | "rotate" | "scale" | "all" (default "all")
  onChange = function(t) --[[ t = {x,y,a,scale} during the drag ]] end,
  onCommit = function(t) --[[ t = {x,y,a,scale} on release; re-anchor + persist here ]] end,
})
gz:setMode("rotate")   -- switch which handles show
gz:mode()              -- the current mode string
gz:isDragging()        -- bool
gz:detach()            -- remove the handles + input hooks + any active grab (idempotent); :destroy() is an alias
```

Try it live: in the [`planner`](../../../addons/planner) addon, `:planner place` a blueprint, click it to select, then
`:planner gizmo` — drag the arrows (move), the ring (rotate) or the box (scale). `:planner gizmo rotate|scale|move`
focuses one handle group; `:planner scale <s>` and `:planner rotate <deg>` set them directly. The facing and scale
persist with the layout, so a relog restores the full transform. (`:planner grab`, V5a, is the simpler
drag-by-the-body move-mode.)
