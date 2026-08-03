# hafen.render: your own images and models in the world

**Stand your addon's own files in the 3D world.** `hafen.render.sprite` stands a PNG there, as a fixed
quad or a camera-facing billboard; `hafen.render.object` stands a glTF model. Both are the non-`.res`
sibling of [`hafen.ghost`](../ghost.md), which places the *game's* own props, and both sit on the same
transform handle.

```lua
local icon = hafen.asset("icon.png")
local p = hafen.player():gob():pos()
hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }
```

> **Ungated.** A custom image or mesh is a client-side texture that never reaches the server and grants no
> gameplay advantage, so it needs no `actions` permission — exactly like a
> [HUD overlay](../ui/custom.md#overlays) or a ghost. Committing a *real* build is still the gated
> [`hafen.act.place`](../act.md).

Everything here is **bridge-owned**: every sprite, object and asset is disposed automatically on reload,
disable and relogin, so it never leaks a GPU texture.

To draw an image on **screen** rather than in the world, use [`g:image`](../ui/drawing.md) inside a draw
callback.

## Handle-only

Both world builders take a [`hafen.asset`](../asset.md) handle, never a path:

```lua
local icon = hafen.asset("icon.png")                       -- load once...
hafen.render.sprite{ image = icon, x = wx, y = wy }        -- ...pass the handle
hafen.render.sprite{ image = "icon.png", x = wx, y = wy }  -- ERROR: that is a path, not a handle
```

A path string raises an error naming `hafen.asset` as the way in. There is no shortcut because there is
nothing to save: assets are [interned](../asset.md#interning), so `hafen.asset("icon.png")` at the call
site is free, and a second spelling would only be a second way to say the same thing. A **disposed** handle
gets its own error — re-load it, since the same path is a new asset.

## Pages

| Page | What it covers |
|---|---|
| [sprites](sprites.md) | an image in the world: fixed or billboard, clicks, anchoring to a gob |
| [models](models.md) | glTF: the supported subset, the object handle, clicks |

## See also

- [`hafen.asset`](../asset.md) — the one door for the files both builders take
- [`hafen.ghost`](../ghost.md) — the same idea for the game's own `.res` props
- [drawing](../ui/drawing.md) — the same images, drawn on screen instead
- [events](../events.md#world-ghosts-and-sprites) — `SpriteClicked` and `ObjectClicked`
