# hafen.asset: the files your addon ships

**One loader for every file that lives in your addon's folder.** An image, a font, a 3D model, a data file
— they all come through the same door, and the type comes from the file's **extension**, so you never say
which loader you want.

```lua
local icon  = hafen.asset():get("icon.png")        -- an image
local face  = hafen.asset():get("fonts/Inter.ttf") -- a font
local chair = hafen.asset():get("props/chair.glb") -- a mesh
local theme = hafen.asset():get("theme.json")      -- data (its text)
local song  = hafen.asset():get("songs/air.mid")   -- data (its bytes)
```

`hafen.asset()` **is the collection** of the files this addon holds: `:get(path)` loads and interns one,
[`:list(filter)`](collection.md) is all of them, and [`:files(dir)`](collection.md#the-files-you-ship)
names what a folder of yours holds before any of it is loaded.

There is exactly **one flow** for a local file: load, then draw or decorate or stand or read. Load it once,
keep the handle, hand the *handle* to whatever uses it. The use sites take a handle and nothing else —
passing a path string to a [sprite](../virtual/sprites.md) or an [object](../virtual/models.md) is an error
that points you back here.

> **Unprotected.** An asset is a client-side file *you shipped*: it never reaches the server and grants no
> gameplay advantage, so it needs no permission at all, like a [HUD overlay](../ui/overlay.md) or a
> [ghost](../virtual/ghosts.md).

## The types

| Extensions | `a:type()` | What you get | Use it with |
|---|---|---|---|
| `.png` `.jpg` `.jpeg` `.gif` `.bmp` | `"image"` | a GPU texture, alpha preserved | [`g:image`/`g:aimage`](../ui/drawing.md), [a sprite](../virtual/sprites.md), [a button's face](../ui/controls/interactive.md#a-caption-or-a-picture), [a menu entry's icon](../menugrid.md#what-an-entry-draws) |
| `.ttf` `.otf` | `"font"` | a [`FontHandle`](../font.md) whose family is registered, so `$font[…]` works | [`font =`](../font.md#draw-with-it), [`rule:font`](../ui/style/README.md), [`widget:rule()`](../ui/style/README.md#restyle-one-widget) |
| `.glb` `.gltf` | `"mesh"` | parsed glTF 2.0 static geometry and its textures | [an object](../virtual/models.md) |
| any other | `"data"` | the file's **bytes**, and its **text** decoded as UTF-8 | [`hafen.json():parse`](../json.md), `string.byte`, and anything else that takes a string |

PNG is the recommended image format, for transparency, and `.glb` the recommended model format, being a
single file. The three decoded kinds are the ones whose loader has to recognise the format; every other
file is [data](handles.md#data), and what its bytes mean is yours to read.

**A file is read whole into memory, so there is a size limit: 128 MB.** A larger one raises, naming its
size, rather than being read — the caps inside the glTF parser apply to bytes that are already on the
heap, and a file that big is a mistake rather than an asset.

## The loader takes a path and nothing else

`:get(path)` has **one argument, always**; there is no per-type options table. Loading a *file* is
expensive and happens once, while configuring a *use* of it is cheap and happens many times, so the two are
separate calls:

```lua
local face  = hafen.asset():get("fonts/Inter.ttf")   -- the file: read and registered once
local small = face:derive():size(11)                 -- a use: cheap, as many as you like
local big   = face:derive():size(24):bold(true)
```

That split is why the signature is identical for every type, and why [interning](#interning) never depends
on an options table.

## Paths are addon-relative and sandboxed

`path` is relative to **your own addon folder** — `"icon.png"`, `"img/sign.png"`, `"props/tree.glb"`.
Absolute paths and `..` escapes are **rejected**, because an addon reads only its own files. An internal
`a/../b` is fine: it is normalised and still lands inside your folder. A link inside your folder that points
outside it is refused like any other path outside it, because the check is made on the file a name really
reaches rather than on the way it is spelled. External `.gltf` buffer and texture files are resolved relative
to the model file and go through that same check, so a `.gltf` cannot reach out either. The same check
stands in front of [`:files(dir)`](collection.md#the-files-you-ship), so a folder you can list is a folder
you can load from and no other.

Decoding is **synchronous**: call `:get` from setup code — `Load`, `SessionEnteredWorld`, a command —
**never** from inside a draw callback. It is one call from Lua's side however big the file is, so the
[instruction budget](../threading.md) that stops a runaway loop cannot see it coming: a large
model or image is a frame the client drops, and the only thing deciding when it drops is where you
called `:get`.

## Interning

The same path is the **same handle**, for every type:

```lua
local get = function(p) return hafen.asset():get(p) end
get("icon.png") == get("icon.png")                --> true
get("./icon.png") == get("img/../icon.png")       --> true (the key is the RESOLVED path)
```

So repeating the load is free. There is no reason to thread a handle through your own code just to avoid a
second call, and no reason for the use sites to accept a path string. `a:path()` answers the spelling of
the **first** load, since the resolved path is the key rather than the answer.

> **Identity is stable *while the asset is alive*.** [`hafen.asset():remove(a)`](collection.md#the-collection)
> drops it from the cache, so the next `:get(path)` re-reads the file into a **new** object, and the old
> handle is never equal to it again. A freed asset is never served again and never
> [listed](collection.md#the-collection).

## What this door does not open

`hafen.asset` loads **local files only**. A remote asset would mean an async load in a synchronous API, an
untrusted binary going into the font and texture paths, and a per-user tracking channel; fetching *data*
over HTTP is [`hafen.http`](../http.md), which is protected by a host allowlist the user approves. Hand
`:get` a URL and it says exactly that, rather than reporting a missing file.

The client already owns every `.res` in the game, and those are **addressed by name** rather than loaded
from your folder: they have no sandbox to pass, no cache of yours to fill and no lifetime to manage. This
namespace is *your files*; the table below is *the game's*.

| Engine resource | Call |
|---|---|
| a `.res` image — action icons, HUD art | [`g:resource(name, x, y)`](../ui/drawing.md) |
| a `.res` image as a theme's own art | `{res = name}` in a [rule](../ui/style/chrome.md#naming-a-picture)'s `bg`, `border` or `picture` |
| a minimap drawing of ground you explored | [`grid:image(lvl)`](../map/drawings.md) |
| a `.res` sound | [`hafen.sound():get(name)`](../sound.md) |
| a `.res` prop in the world | [`hafen.virtual`](../virtual/ghosts.md) |
| a built-in font | [`hafen.font():get(name)`](../font.md#the-built-ins) |

## Reading order

**The collection** — [collection](collection.md) is loading, listing, freeing, and naming the files a
folder of yours holds, with every refusal the door gives.

**The handles** — [handles](handles.md) is what each kind of loaded file answers: the verbs every asset
has, and the ones an image, a font, a mesh and a data file add.

## See also

- [`hafen.virtual`](../virtual/README.md) — stand an image or a mesh **in the world**
- [drawing](../ui/drawing.md) — `g:image` and `g:aimage` draw an image asset on screen
- [`hafen.font`](../font.md) — what a font asset does once you have it, and the built-ins that are not
  assets
- [`hafen.json`](../json.md) — turning a data asset's `:text()` into a table
- [references](../references.md#asset-a-file-your-addon-ships) — collections, owned resources and teardown
