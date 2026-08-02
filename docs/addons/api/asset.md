# `hafen.asset` — the files your addon ships

**One loader for every file that lives in your addon's folder.** An image, a font, a 3D model — they all
come through the same door:

```lua
local icon = hafen.asset("icon.png")              -- an image
local face = hafen.asset("fonts/Inter.ttf")       -- a font
local chair = hafen.asset("props/chair.glb")      -- a mesh
```

`hafen.asset` is **callable**, and the arity is the verb: `hafen.asset(path)` is **one** asset,
`hafen.asset()` is the array of the assets this addon currently holds. The type comes from the file's
**extension** — you never say which loader you want.

> **Safe-tier — not gated.** An asset is a client-side file *you shipped*: it never reaches the server and
> grants no gameplay advantage, so it needs no `actions` permission — like a
> [HUD overlay](ui.md#overlays) or a [ghost](ghost.md).

There is exactly **one flow** for a local file: **load → draw / decorate / stand**. Load it once, keep the
handle, hand the *handle* to whatever uses it. The use sites take a handle and nothing else — passing a path
string to [`hafen.render.sprite`](render.md#standing-an-image-in-the-world) or
[`object`](render.md#standing-a-3d-model-in-the-world) is an error that points you back here.

## The three types

| Extensions | `a:type()` | What you get | Use it with |
|---|---|---|---|
| `.png` `.jpg` `.jpeg` `.gif` `.bmp` | `"image"` | a GPU texture (alpha preserved) | [`g:image`/`g:aimage`](ui.md#the-g-draw-wrapper), [`hafen.render.sprite`](render.md#standing-an-image-in-the-world) |
| `.ttf` `.otf` | `"font"` | a [`FontHandle`](fonts.md) (its family is AWT-registered, so `$font[…]` works) | [`font =`](fonts.md#draw-with-it--your-own-widgets-f2), [`hafen.font.setFont`](fonts.md#restyle-a-global-surface--owned-overrides), [`widget:setFont`](fonts.md#restyle-one-widget--widgetsetfonth-f5) |
| `.glb` `.gltf` | `"mesh"` | parsed glTF 2.0 static geometry + its textures | [`hafen.render.object`](render.md#standing-a-3d-model-in-the-world) |

PNG is the recommended image format (transparency), and `.glb` the recommended model format (single file).
Any other extension is an error listing these.

## The loader takes a path and nothing else

`hafen.asset(path)` has **one argument, always** — there is no per-type options table. Loading a *file* is
expensive and happens once; configuring a *use* of it is cheap and happens many times, so the two are
separate calls:

```lua
local face = hafen.asset("fonts/Inter.ttf")   -- the file: read + registered once
local small = face:derive{ size = 11 }        -- a use: cheap, non-mutating, as many as you like
local big   = face:derive{ size = 24, bold = true }
```

That is AWT's own split (`Font.createFont` returns a 1 pt font, `deriveFont` makes the variants), and it is
why the signature is identical for all three types and why [interning](#interning) never depends on an
options table.

## Paths are addon-relative and sandboxed

`path` is relative to **your own addon folder** — `"icon.png"`, `"img/sign.png"`, `"props/tree.glb"`.
Absolute paths and `..` escapes are **rejected**: an addon reads only its own files
([D-017](../../../specs/addons/decisions/security-sandbox.md)). An internal `a/../b` is fine — it is
normalised and still lands inside your folder.

External `.gltf` buffer/texture files are resolved **relative to the model file** and re-checked against
your folder, so a `.gltf` cannot reach out either.

Decoding is **synchronous**: call `hafen.asset` from setup code — `OnLoad`, `OnEnterWorld`, a command —
**never** from inside a draw callback.

## Interning

The same path is the **same handle**, for every type:

```lua
hafen.asset("icon.png") == hafen.asset("icon.png")   --> true
hafen.asset("./icon.png") == hafen.asset("img/../icon.png")   --> true (the key is the RESOLVED path)
```

So repeating the load is free — there is no reason to thread a handle through your own code just to avoid a
second call, and no reason for the use sites to accept a path string.

`a:path()` answers the spelling of the **first** load (the resolved path is the key, not the answer).

> **Identity is stable *while the asset is alive*.** `:dispose()` drops it from the cache, so the next
> `hafen.asset(path)` re-reads the file into a **new** object: `old == hafen.asset(old:path())` is `false`.
> A disposed asset is never served again and never [listed](#the-collection-form).

## Every asset

| Method | Description |
|---|---|
| `a:type()` | `"image"` \| `"font"` \| `"mesh"` — what the extension dispatched to |
| `a:path()` | the addon-relative path it was loaded from |
| `a:dispose()` | free it **now** (also automatic on reload / disable / relogin); returns the handle |

You rarely need `:dispose()` — every asset is **bridge-owned** and freed for you on `:reload`, disable and
relogin, so nothing leaks a GPU texture. Use it only to release a large asset early.

### Image — `a:size()`

| Method | Description |
|---|---|
| `img:size()` | `{w, h}` — pixel dimensions |

A disposed image simply **draws nothing** thereafter (`g:image` is forgiving — the draw verbs never throw).

### Font — `:derive` / `:family` / `:size`

A font asset **is** a [`FontHandle`](fonts.md) with the three asset verbs on top. See
[`hafen.font`](fonts.md) for `:derive(opts)`, `:family()` and `:size()`, and for the scopes you can install
it on. Disposing a font asset frees nothing (a font holds no releasable resource) — it only drops the cache
entry, so the next load re-reads and re-registers the file.

### Mesh — `:bounds()` / `:info()`

| Method | Description |
|---|---|
| `mdl:bounds()` | `{min={x,y,z}, max={x,y,z}, size={x,y,z}}` — axis-aligned bounds in **world units** |
| `mdl:info()` | `{prims, textured, lit, textures, verts, tris}` — what the parser produced |

The supported glTF subset (static, textured, lit) is documented in
[`hafen.render`](render.md#the-gltf-subset). A malformed file — or one using an unsupported feature — raises
a clear error that **names** the feature.

> **Disposing a mesh an object is still standing does *not* break that object.** The object keeps drawing,
> textured and unchanged: it captured its texture samplers when it was built. What you forfeit is the
> *freeing* — the memory is not reclaimed until that object is destroyed. So `:dispose()` a mesh only when
> nothing is standing it; the automatic teardown already gets the order right.

## The collection form

`hafen.asset()` returns a 1-based array of **this addon's live assets**, in load order:

```lua
for _, a in ipairs(hafen.asset()) do
  hafen.log(("%-5s %s"):format(a:type(), a:path()))
end
```

An explicit `nil` is the same as no argument — `hafen.asset(maybePath)` with a `nil` variable gives you the
list, not an error, so check the variable before you pass it.

Only *loaded files* appear. A [built-in font](fonts.md#the-built-ins--hafenfontname) (`hafen.font("serif")`)
is not an asset — it has no file, no path and no lifetime — so it is never listed, and neither is a
`:derive`d variant. A disposed asset is gone from the list, never resurrected.

## Errors

Everything below raises a `pcall`-able error naming `hafen.asset`, and each shape is distinguishable:

| What you did | What you get |
|---|---|
| `hafen.asset("/etc/passwd")` | the path *is absolute* — an addon loads only its own files |
| `hafen.asset("../other/icon.png")` | the path *climbs out of the addon folder with `..`* |
| `hafen.asset("nope.png")` | *no such file* in this addon's folder (checked before any decode) |
| `hafen.asset("notes.txt")` | *no supported extension* — the message lists all of them |
| `hafen.asset("broken.png")` | *not a decodable image* / *not a valid TrueType/OpenType font* / the glTF parser's own message |
| `hafen.asset(1)` | the key is a **path string**, not a number |
| `hafen.asset({})`, `hafen.asset(fn)` | expected a path string, `got table` / `got function` |
| `hafen.asset("")` | path must be a **non-empty** string |
| `hafen.asset("icon.png")` from `:lua` | the console **has no addon folder** — an asset path is relative to the folder of the addon loading it |

## Why there are no URLs

`hafen.asset` loads **local files only**. A remote asset would mean an async load in a synchronous API, an
untrusted binary going into the AWT font and GL texture paths, and a per-user tracking channel. If it is ever
wanted it will be its own gated, async `fetch` — not this entry point. Fetching *data* over HTTP is
[`hafen.http`](http.md), which is gated by a manifest allowlist.

## Why engine resources are addressed, not loaded

The client already owns every `.res` in the game. Those are **addressed by name**, not loaded from your
folder, and they never enter this cache:

| Engine resource | Call |
|---|---|
| a `.res` image (action icons, HUD art) | [`g:resource(name, x, y)`](ui.md#the-g-draw-wrapper) |
| a `.res` sound | [`hafen.sound(name)`](audio.md) |
| a `.res` prop in the world | [`hafen.ghost`](ghost.md) |
| a built-in font | [`hafen.font(name)`](fonts.md#the-built-ins--hafenfontname) |

They have no sandbox to pass, no cache of yours to fill and no lifetime to manage. The boundary is
deliberate: `hafen.asset` is *your files*, everything above is *the game's*.

## Example

```lua
local icon, face, chair                     -- upvalues (a reload rebuilds the env -> nil again)

hafen.events.on("OnLoad", function()
  icon  = hafen.asset("icon.png")
  face  = hafen.asset("fonts/Inter.ttf"):derive{ size = 12 }
  chair = hafen.asset("props/chair.glb")
  local s, b = icon:size(), chair:bounds()
  hafen.log(("icon %dx%d, chair %.1f tiles tall"):format(s.w, s.h, b.size.z / 11))
end)

hafen.ui.window{
  title = "My addon", size = {160, 80}, font = face,
  onDraw = function(g, w, h)
    g:image(icon, 4, 4, 16, 16)             -- the handle, not the path
    g:text("mine", 26, 6)
  end,
}

hafen.slash.register("stand", function()
  local p = hafen.player():gob():pos()
  hafen.render.object{ model = chair, x = p.x, y = p.y }   -- the handle, again
end)
```

## See also

- [`hafen.render`](render.md) — stand an image or a mesh **in the world** (`sprite` / `object`).
- [`hafen.ui`](ui.md#the-g-draw-wrapper) — `g:image`/`g:aimage` draw an image asset on screen.
- [`hafen.font`](fonts.md) — what a font asset does once you have it (and the built-ins that are *not* assets).
- [conventions](conventions.md#asset--a-file-your-addon-ships) — callable namespaces, owned resources & teardown.
