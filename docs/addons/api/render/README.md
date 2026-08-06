# hafen.render: your own images and models in the world

**Stand your addon's own files in the 3D world.** `hafen.render():sprite()` is the collection of the PNGs
you have stood there, as fixed quads or camera-facing billboards; `hafen.render():object()` is the same for
glTF models. Both are the non-`.res` sibling of [`hafen.ghost`](../ghost.md), which places the *game's* own
props, and all three speak the same verbs.

```lua
local icon = hafen.asset():get("icon.png")
local p = hafen.player():gob():position()
hafen.render():sprite():add(icon, p):scale(3)
```

> **Ungated.** A custom image or mesh is a client-side texture that never reaches the server and grants no
> gameplay advantage, so it needs no `actions` permission — exactly like a
> [HUD overlay](../ui/custom.md#overlays) or a ghost. Committing a *real* build is still the gated
> [`hafen.act():place`](../act.md).

Everything here is **bridge-owned**: every sprite, object and asset is disposed automatically on reload,
disable and relogin, so it never leaks a GPU texture.

To draw an image on **screen** rather than in the world, use [`g:image`](../ui/drawing.md) inside a draw
callback.

## The two collections

| Call | Returns | Description |
|---|---|---|
| `hafen.render():sprite()` | collection | the PNGs this addon has stood in the world |
| `hafen.render():object()` | collection | the glTF models it has stood in the world |

Each carries the standard collection verbs — `:add(asset, p)`, `:list(filter)`, `:count(filter)`,
`:find(filter)` and `:remove(x)` — and each is the same object every call, so you can keep it in an upvalue.
A string `filter` matches the addon-relative path of the file the thing draws.

`:add` takes **two** things, and both are required: the asset it draws, and the
[Position](../world.md#the-position-type) it stands at. The scene resolves the tile under a thing as it
enters it, so one with no place cannot be built at all. It **raises** when you are not in the world yet;
place from `EnterWorld` onward. Ground you have walked but that has not streamed back in is *not* an
error — the thing waits and appears as its tiles arrive. Ending one is `:remove(x)` on the collection that
placed it.

## Handle-only

Both `:add` calls take a [`hafen.asset`](../asset.md) handle, never a path:

```lua
local icon = hafen.asset():get("icon.png")     -- load once...
hafen.render():sprite():add(icon, p)           -- ...pass the handle
hafen.render():sprite():add("icon.png", p)     -- ERROR: that is a path, not a handle
```

A path string raises an error naming `hafen.asset` as the way in. There is no shortcut because there is
nothing to save: assets are [interned](../asset.md#interning), so `hafen.asset():get("icon.png")` at the
call site is free, and a second spelling would only be a second way to say the same thing. A **disposed**
handle gets its own error — re-load it, since the same path is a new asset.

## Pages

| Page | What it covers |
|---|---|
| [sprites](sprites.md) | an image in the world: fixed or billboard, clicks, anchoring to a gob |
| [models](models.md) | glTF: the supported subset, the object's verbs, clicks |

## See also

- [`hafen.asset`](../asset.md) — the one door for the files both collections take
- [`hafen.ghost`](../ghost.md) — the same idea for the game's own `.res` props
- [drawing](../ui/drawing.md) — the same images, drawn on screen instead
- [events](../event.md#world-ghosts-and-sprites) — `SpriteClicked` and `ObjectClicked`
