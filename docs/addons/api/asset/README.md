# hafen.asset: The Files Your Addon Ships

One loader for every file in your addon's folder: an image, a font, a 3D model, a data file come through the same door, typed by the file's extension. `hafen.asset()` is the collection of the files this addon holds. Unprotected: an asset is a client-side file you shipped.

```lua
local icon  = hafen.asset():get("icon.png")        -- an image
local face  = hafen.asset():get("fonts/Inter.ttf") -- a font
local chair = hafen.asset():get("props/chair.glb") -- a mesh
local theme = hafen.asset():get("theme.json")      -- data (its text)
local song  = hafen.asset():get("songs/air.mid")   -- data (its bytes)
```

---

One flow for a local file: load once, keep the handle, hand the handle to whatever uses it. The use sites take a handle and nothing else; a path string passed to a [sprite](../virtual/sprites.md) or an [object](../virtual/models.md) raises pointing back here.

## The types

| Extensions | `asset:type()` | You get | Use it with |
|---|---|---|---|
| `.png` `.jpg` `.jpeg` `.gif` `.bmp` | `"image"` | A GPU texture, alpha preserved. | [`graphics:image`/`graphics:aimage`](../ui/drawing.md), [a sprite](../virtual/sprites.md), [a button's face](../ui/controls/interactive.md#a-caption-or-a-picture), [a menu entry's icon](../menugrid.md#what-an-entry-draws). |
| `.ttf` `.otf` | `"font"` | A [`FontHandle`](../font.md) whose family is registered, so `$font[…]` works. | [`font =`](../font.md#draw-with-it), [`rule:font`](../ui/style/README.md), [`widget:rule()`](../ui/style/README.md#restyle-one-widget). |
| `.glb` `.gltf` | `"mesh"` | Parsed glTF 2.0 static geometry and its textures. | [An object](../virtual/models.md). |
| Any other | `"data"` | The file's bytes, and its text decoded as UTF-8. | [`hafen.json():parse`](../json.md), `string.byte`, anything that takes a string. |

| Rule | Detail |
|---|---|
| Recommended formats | PNG for images (transparency), `.glb` for models (a single file). Every file outside the three decoded kinds is [data](handles.md#data). |
| Size limit | A file is read whole into memory: 128 MB. A larger one raises naming its size before being read. |
| One argument, always | `:get(path)` takes a path and no options table: loading a file is expensive and happens once, configuring a use is cheap and happens many times (`face:derive():size(11)`, `face:derive():size(24):bold(true)`). |
| Synchronous | Call `:get` from setup code (`Load`, `SessionEnteredWorld`, a command), never inside a draw callback. It is one call from Lua's side however big the file, so the [instruction budget](../threading.md) cannot see it: a large model or image is a dropped frame, timed by where you called `:get`. |

## Paths are addon-relative and sandboxed

| Rule | Detail |
|---|---|
| Relative to your addon folder | `"icon.png"`, `"img/sign.png"`, `"props/tree.glb"`. Absolute paths and `..` escapes are rejected; an internal `a/../b` normalises and lands inside. |
| The check is on the file reached | A link inside your folder pointing outside is refused like any other outside path. External `.gltf` buffers and textures resolve relative to the model file through the same check. [`:files(dir)`](collection.md#the-files-you-ship) lists only what the same check passes. |

## Interning

The same path is the same handle, for every type: the key is the resolved path, so `get("./icon.png") == get("img/../icon.png")`, and repeating a load is free. `asset:path()` answers the spelling of the first load.

```lua
local function get(path) return hafen.asset():get(path) end
assert(get("icon.png") == get("icon.png"))
assert(get("./icon.png") == get("img/../icon.png"))       -- the key is the resolved path
```

> **Identity is stable while the asset is alive.** [`hafen.asset():remove(asset)`](collection.md#the-collection) drops it from the cache, so the next `:get(path)` re-reads the file into a new object never equal to the old handle. A freed asset is never served or [listed](collection.md#the-collection) again.

## What this door does not open

| Not here | Where |
|---|---|
| A remote file | [`hafen.http`](../http.md), protected by a host allowlist the user approves: a remote asset would be an async load in a synchronous API, an untrusted binary in the font and texture paths, and a tracking channel. `:get` handed a URL says so rather than reporting a missing file. |
| A `.res` image (action icons, HUD art) | [`graphics:resource(name, x, y)`](../ui/drawing.md). |
| A `.res` image as a theme's own art | `{res = name}` in a [rule](../ui/style/chrome.md#naming-a-picture)'s `bg`, `border` or `picture`. |
| A minimap drawing of ground you explored | [`grid:image(level)`](../map/drawings.md). |
| A `.res` sound | [`hafen.sound():get(name)`](../sound.md). |
| A `.res` prop in the world | [`hafen.virtual`](../virtual/ghosts.md). |
| A built-in font | [`hafen.font():get(name)`](../font.md#the-built-ins). |

The client already owns every `.res` and addresses it by name: no sandbox, no cache of yours, no lifetime. This namespace is your files.

## Pages

| Page | Covers |
|---|---|
| [Collection](collection.md) | Loading, listing, freeing, and naming the files a folder of yours holds, with every refusal. |
| [Handles](handles.md) | What each kind of loaded file answers: the shared verbs, and those an image, a font, a mesh and a data file add. |

---

## See Also

- [`hafen.virtual`](../virtual/README.md) — stand an image or a mesh in the world.
- [Drawing](../ui/drawing.md) — `graphics:image` and `graphics:aimage` draw an image asset on screen.
- [`hafen.font`](../font.md) — what a font asset does once you have it, and the built-ins that are not assets.
- [`hafen.json`](../json.md) — turning a data asset's `:text()` into a table.
- [References](../references.md#asset-a-file-your-addon-ships) — collections, owned resources and teardown.
