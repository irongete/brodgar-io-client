# hafen.render — custom images & models (non-`.res`)

Render **custom assets that ship with your addon** — not engine `.res` resources. This is the sibling of
[`hafen.ghost`](ghost.md) (which places the game's own `.res` models in the world): `hafen.render` is for
**your own files**. Today it loads **PNG images** and draws them on screen; standing images in the world
(sprites) and custom 3D models (glTF) join it later on the same handle model.

> **Safe-tier — not gated.** A custom image is a client-side texture that never reaches the server and grants
> no gameplay advantage, so it needs **no** `actions` permission — exactly like [`hafen.ui.overlay`](ui.md#overlays)
> or a [ghost](ghost.md). ([D-034](../../../specs/addons/decisions.md).)

Under the hood a `.res` file is *already* a PNG the client wraps in a GPU texture; `hafen.render` just exposes
that substrate directly, skipping the `.res` container. Everything here is **bridge-owned**: every image your
addon loads is disposed automatically on reload / disable / relogin, so it never leaks a GPU texture.

## Loading an image

| Function | Returns | Description |
|---|---|---|
| `hafen.render.image(path)` | [image handle](#image-handle) | load a PNG from your addon's folder into a cached, bridge-owned texture |

`path` is **relative to your addon's own folder** (e.g. `"icon.png"`, `"img/sign.png"`). Absolute paths and
`..` escapes are **rejected** — an addon reads only its own assets ([D-017](../../../specs/addons/decisions.md)).
PNG (with alpha) is the recommended format; anything `ImageIO` decodes (JPG/GIF/BMP) also works.

Loading is **cached**: repeated `hafen.render.image` of the same path returns the **same handle** (one texture
per path). Call it from **setup code** — `OnLoad`, `OnEnterWorld`, or a command — **not** from inside a draw
callback (v1 decodes the file synchronously). A bad path or an undecodable file raises a clear error.

## Image handle

| Method | Description |
|---|---|
| `img:size()` | `{w, h}` — the image's pixel dimensions |
| `img:dispose()` | free the GPU texture now (also automatic on reload/disable/relogin) |

You rarely need `:dispose()` — teardown frees every image for you. Use it only to release a large image early.
A disposed handle simply draws nothing thereafter.

## Drawing on screen

Inside any draw callback ([`onDraw`](ui.md#custom-windows--widgets), [`hafen.ui.overlay`](ui.md#overlays),
[`hafen.ui.gobOverlay`](ui.md#overlays)), draw an image through the [`g` wrapper](ui.md#the-g-draw-wrapper):

| Method | Description |
|---|---|
| `g:image(img, x, y)` | draw at native size, top-left at `(x, y)` |
| `g:image(img, x, y, w, h)` | draw scaled into a `w × h` box |
| `g:aimage(img, x, y, ax, ay)` | anchored — `ax`/`ay` `0..1` pick which point of the image sits at `(x, y)` (mirrors [`g:atext`](ui.md#the-g-draw-wrapper)) |

A `nil`, wrong-type, or disposed handle **draws nothing** (the draw verbs are forgiving — they never throw).

```lua
local icon                                     -- upvalue for the draw callbacks below

hafen.events.on("OnLoad", function()
  icon = hafen.render.image("icon.png")        -- load once from addons/<me>/icon.png
  local s = icon:size()
  hafen.log(("icon is %dx%d"):format(s.w, s.h))
end)

hafen.ui.window{
  title = "My addon", size = { 120, 60 },
  onDraw = function(g, w, h)
    if icon then
      g:image(icon, 4, 4)                       -- native size
      g:image(icon, 4, 40, 16, 16)              -- the same image scaled to 16×16
    end
  end,
}

hafen.ui.overlay(function(g, w, h)
  if icon then g:aimage(icon, w - 6, 6, 1.0, 0.0) end   -- top-right corner (right-anchored)
end)
```

> **Alpha works.** A PNG's transparency is preserved, so an icon with a transparent background composites over
> whatever is behind it — the same as the client's own art.

## Coming next

The same handle model extends to the world (all built on the shared world-entity core + the
[transform gizmo](ghost.md#transform-gizmo-move--rotate--scale)):

| Planned | What |
|---|---|
| `hafen.render.sprite{image=, x, y, …}` | a PNG standing **in the world** — a camera-facing billboard or a fixed quad (**R2**) |
| `hafen.render.model(path)` + `hafen.render.object{model=, …}` | a custom **glTF** 3D model placed in the world (**R3**) |

See [17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) and
[18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) for the full design.
