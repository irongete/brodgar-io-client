# hafen.ghost — client-only world ghosts

Place **virtual props in the 3D world** — "ghosts" rendered at arbitrary world coordinates, optionally
**clickable** (V2). A ghost is **client-only**: it is a game object with **no server id**, so it is never
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
| `clickable` | boolean | `false` | opt-in pick surface (V2) — see [Clickability](#clickability--the-ghostclicked-event-v2) |
| `onClick` | function | — | `fn(g, button, x, y)` fired on click (V2); also delivered as the [`GhostClicked`](events.md#world-ghosts) event |

For `list`, `filter` is the canonical [filter](conventions.md#the-filter-argument), adapted to handles:
`nil` = all; a **string** = substring match on the ghost's `res`; a **function** is called with the ghost
**handle** (so it can call `g:pos()` etc.), truthy keeps it.

## Ghost handle

| Method | Description |
|---|---|
| `g:move(x, y [, a])` | reposition to world `x, y` (and optionally set facing `a`) |
| `g:pos()` | `{x, y, a}` — current world position and facing |
| `g:res()` | the resource name (string) |
| `g:clickable(bool)` | toggle the pick surface (V2) — see [Clickability](#clickability--the-ghostclicked-event-v2) |
| `g:destroy()` | remove it now (also automatic on reload/disable). Idempotent. |

Every method returns the handle (except `:pos`/`:res`), so calls chain.

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

## Coming next

V1+V2 ship create / move / destroy and opt-in clickability. Later slices add: **look & orientation**
(`:rotate`, `:setRes`, `alpha`/`tint`), grid-anchored **layouts**, and a **transform gizmo**
(`hafen.ghost.gizmo`) that snaps exactly like placing a real building. These are not available yet.
