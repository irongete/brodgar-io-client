# hafen.ghost — client-only world ghosts

Place **virtual props in the 3D world** — "ghosts" rendered at arbitrary world coordinates, optionally
**clickable** (V2) and with a **look & orientation** — facing, translucency and colour tint (V3). A ghost is
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
| `hafen.ghost.new{res, x, y [, a, sdt, alpha, tint, clickable, onClick]}` | [ghost handle](#ghost-handle) \| nil | create a client-only prop; nil if not in the world yet |
| `hafen.ghost.list([filter])` | [ghost handle](#ghost-handle)`[]` | this addon's live ghosts |

`new` options:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `res` | string | — (required) | resource name — a game object, e.g. `"gfx/terobjs/arch/logcabin"` (resolved via the game resource pool, so any server or client resource works) |
| `x`, `y` | number | — (required) | world coordinates (login-relative, the same as [`hafen.gob.pos`](gob.md)) |
| `a` | number | `0` | facing, in radians |
| `sdt` | byte array | — | spawn-data bytes (resource variant/state), e.g. `{0x01, 0x00}` — advanced, rarely needed (V3) |
| `alpha` | number | `1` | opacity `0..1`; `< 1` gives the translucent "ghost" look (V3) — see [Look & orientation](#look--orientation-v3) |
| `tint` | `{r, g, b [, a]}` | — | colour overlay, `0..255` (`a` = blend strength, default `255`) (V3) |
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
| `g:show()` / `g:hide()` | add / remove the ghost from the 3D scene, keeping it (V3) |
| `g:pos()` | `{x, y, a}` — current world position and facing |
| `g:res()` | the resource name (string) |
| `g:clickable(bool)` | toggle the pick surface (V2) — see [Clickability](#clickability--the-ghostclicked-event-v2) |
| `g:destroy()` | remove it now (also automatic on reload/disable). Idempotent. |

Every method returns the handle (except `:pos`/`:res`), so calls chain.

```lua
local p = hafen.gob.pos("player")
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

## Transform gizmo (V5b)

A **Unity-style transform gizmo** lets you drag a ghost **by its arrows**: red = world **X**, green = world **Y**,
a yellow **centre** square for a free ground-plane move. Press an arrow and drag — the ghost moves **along that axis
only** (the centre moves freely), snapped to the `:placegrid` (SHIFT = fine), and the **camera stays put**. The
arrows are drawn with [`g:draw`](ui.md#the-g-draw-wrapper) as filled triangles and re-projected every frame, so
they track the ghost and foreshorten with the camera — no game resource needed.

Per [D-031](../../../specs/addons/decisions.md), the gizmo is a **bundled Lua library over the V5 primitives**
([`hafen.ui.overlay`](ui.md#overlays) to draw, [`hafen.hook.input`](hooks.md#hafenhookinput) to pick a handle,
[`hafen.hook.grab`](hooks.md#hafenhookgrab) + [`hafen.map.screenToWorld`](map.md#screen--world--placement-snapping-v5)
+ [`hafen.map.snapPlace`](map.md#screen--world--placement-snapping-v5) to drag) — **not** a built-in `hafen.*`
function. It ships as [`planner/gizmo.lua`](../../../addons/planner/gizmo.lua); the shape is:

```lua
-- gizmo.lua installs one global: gizmo(target, opts) -> a gizmo handle.
local gz = gizmo(myGhost, {                       -- target = anything with :pos() and :move(x,y[,a])
  mode = "move",                                  -- V5b: "move" (rotate/scale = V6)
  onCommit = function(p) --[[ p = {x,y,a} on release; re-anchor + persist here ]] end,
})
gz:setMode("move")     -- V6 will add "rotate"/"scale"/"all"
gz:isDragging()        -- bool
gz:detach()            -- remove the arrows + input hook + any active grab (idempotent); :destroy() is an alias
```

Try it live: in the [`planner`](../../../addons/planner) addon, `:planner place` a blueprint, click it to select, then
`:planner gizmo` — and drag it by its arrows. (`:planner grab`, V5a, is the simpler drag-by-the-body move-mode.)

## Coming next

Still to come (V6): the gizmo's **rotate** ring (snapping on the [`:placeangle`](map.md)) and uniform **scale**
(`g:scale`), plus polish — draw-on-top and constant screen-size handles.
