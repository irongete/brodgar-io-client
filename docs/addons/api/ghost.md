# hafen.ghost — client-only world ghosts

Place **virtual props in the 3D world** — translucent, non-interactive "ghosts" rendered at arbitrary
world coordinates. A ghost is **client-only**: it is a game object with **no server id**, so it is never
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
| `hafen.ghost.new{res, x, y [, a]}` | [ghost handle](#ghost-handle) \| nil | create a client-only prop; nil if not in the world yet |
| `hafen.ghost.list([filter])` | [ghost handle](#ghost-handle)`[]` | this addon's live ghosts |

`new` options:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `res` | string | — (required) | resource name — a game object, e.g. `"gfx/terobjs/arch/logcabin"` (resolved via the game resource pool, so any server or client resource works) |
| `x`, `y` | number | — (required) | world coordinates (login-relative, the same as [`hafen.gob.pos`](gob.md)) |
| `a` | number | `0` | facing, in radians |

For `list`, `filter` is the canonical [filter](conventions.md#the-filter-argument), adapted to handles:
`nil` = all; a **string** = substring match on the ghost's `res`; a **function** is called with the ghost
**handle** (so it can call `g:pos()` etc.), truthy keeps it.

## Ghost handle

| Method | Description |
|---|---|
| `g:move(x, y [, a])` | reposition to world `x, y` (and optionally set facing `a`) |
| `g:pos()` | `{x, y, a}` — current world position and facing |
| `g:res()` | the resource name (string) |
| `g:destroy()` | remove it now (also automatic on reload/disable). Idempotent. |

```lua
local p = hafen.gob.pos("player")
local g = hafen.ghost.new{ res = "gfx/terobjs/arch/logcabin", x = p.x, y = p.y }
g:move(p.x + 33, p.y)          -- 3 tiles east (a tile is 11 world units)
print(#hafen.ghost.list())     -- 1
g:destroy()
```

> **The prop appears a beat after `new`.** The resource resolves on a loader thread (dodging the engine's
> `Loading` state, the same way [`hafen.sound.play`](audio.md) does), so `new` returns a working handle
> immediately while the visual streams in shortly after. Every handle method works meanwhile — a `:move`
> before the prop is visible just sets where it will appear.

> **Ghost coordinates are login-relative**, like all world coords — not shareable or persistent as-is.
> To save a layout across sessions, anchor on **grid ids** via [`hafen.map.gridPos()`](map.md) and
> re-resolve on load (the same rule [markers](markers.md) follow). See
> [conventions](conventions.md#coordinates).

## Coming next

V1 ships create / move / destroy. Later slices add: **clickable ghosts** + a `GhostClicked` event
(opt-in, still client-only), **look & orientation** (`:rotate`, `:setRes`, `alpha`/`tint`), grid-anchored
**layouts**, and a **transform gizmo** (`hafen.ghost.gizmo`) that snaps exactly like placing a real
building. These are not available yet.
