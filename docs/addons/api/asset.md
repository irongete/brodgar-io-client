# hafen.asset: the files your addon ships

**One loader for every file that lives in your addon's folder.** An image, a font, a 3D model, a data file
— they all come through the same door, and the type comes from the file's **extension**, so you never say
which loader you want.

```lua
local icon  = hafen.asset():get("icon.png")        -- an image
local face  = hafen.asset():get("fonts/Inter.ttf") -- a font
local chair = hafen.asset():get("props/chair.glb") -- a mesh
local theme = hafen.asset():get("theme.json")      -- data (its text)
```

`hafen.asset()` **is the collection** of the files this addon holds: `:get(path)` loads and interns one,
and [`:list(filter)`](#the-collection) is all of them.

There is exactly **one flow** for a local file: load, then draw or decorate or stand or read. Load it once,
keep the handle, hand the *handle* to whatever uses it. The use sites take a handle and nothing else —
passing a path string to a [sprite](virtual/sprites.md) or an [object](virtual/models.md) is an error that
points you back here.

> **Unprotected.** An asset is a client-side file *you shipped*: it never reaches the server and grants no
> gameplay advantage, so it needs no permission at all, like a [HUD overlay](ui/overlay.md) or a
> [ghost](virtual/ghosts.md).

## The types

| Extensions | `a:type()` | What you get | Use it with |
|---|---|---|---|
| `.png` `.jpg` `.jpeg` `.gif` `.bmp` | `"image"` | a GPU texture, alpha preserved | [`g:image`/`g:aimage`](ui/drawing.md), [a sprite](virtual/sprites.md), [a button's face](ui/controls/interactive.md#a-caption-or-a-picture), [a menu entry's icon](menugrid.md#what-an-entry-draws) |
| `.ttf` `.otf` | `"font"` | a [`FontHandle`](font.md) whose family is registered, so `$font[…]` works | [`font =`](font.md#draw-with-it), [`rule:font`](ui/style/README.md), [`widget:rule()`](ui/style/README.md#restyle-one-widget) |
| `.glb` `.gltf` | `"mesh"` | parsed glTF 2.0 static geometry and its textures | [an object](virtual/models.md) |
| `.json` `.txt` | `"data"` | the file's **text**, read as UTF-8 | [`hafen.json():parse`](json.md), and anything else that takes a string |

PNG is the recommended image format, for transparency, and `.glb` the recommended model format, being a
single file. Any other extension is an error listing the ones above.

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
`a/../b` is fine: it is normalised and still lands inside your folder. External `.gltf` buffer and texture
files are resolved relative to the model file and re-checked against your folder, so a `.gltf` cannot reach
out either.

Decoding is **synchronous**: call `:get` from setup code — `Load`, `SessionEnteredWorld`, a command —
**never** from inside a draw callback.

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

> **Identity is stable *while the asset is alive*.** [`hafen.asset():remove(a)`](#the-collection) drops it
> from the cache, so the next `:get(path)` re-reads the file into a **new** object, and the old handle is
> never equal to it again. A freed asset is never served again and never [listed](#the-collection).

## Every asset

| Method | Description |
|---|---|
| `a:type()` | `"image"` \| `"font"` \| `"mesh"` \| `"data"` — what the extension dispatched to |
| `a:path()` | the addon-relative path it was loaded from |

**Freeing one is the collection's verb**, [`hafen.asset():remove(a)`](#the-collection): the set that owns
your files is what ends one of them. You rarely need it — every asset is **bridge-owned** and freed for you
on reload, disable and relogin, so nothing leaks a GPU texture. Reach for it only to release a large asset
early.

A handle is an **object, not a table**. You call its verbs with `:`, you cannot write to it, and a name it
does not answer raises where you wrote it rather than reading `nil` and failing a call later:

```lua
local icon = hafen.asset():get("icon.png")
tostring(icon)      --> Asset(image, icon.png)   -- the type and the path, for every type
icon:sizes()        -- an error naming :type, :path and :size
icon.size = nil     -- an error: a handle is an object, and its verbs are not fields you can rewrite
```

Nothing but a handle resolves to a file, either. A table you build to look like one is not one: the verbs
that take a picture refuse it naming this loader, and the [draw verbs](ui/drawing.md), which never throw,
draw nothing for it.

An image you did **not** load — one from another addon's [rule](ui/style/README.md), read back through
`widget:style()` — answers `:type()`, `:path()` and `:size()` exactly as your own does. What it will not do
is leave your collection: `hafen.asset():remove(it)` is refused, **naming the addon that loaded it**. Freeing
a file is the job of the addon that loaded it.

### Image

| Method | Description |
|---|---|
| `img:size()` | `{w=, h=}` — the file's own dimensions, in [design pixels](ui/pixels.md) |

The file's pixels **are** design pixels: a 32×32 PNG answers `32, 32` and covers 32×32 wherever it is
drawn, so it stands beside the client's own art at the same size at every interface scale.

A freed image simply **draws nothing** thereafter; the [draw verbs](ui/drawing.md) are forgiving and
never throw.

A [stylesheet](ui/style/chrome.md#naming-a-picture) may name the file by path instead, `{asset =
"img/panel.png"}`, which loads through this same door and interns to this same object — the one
[path exception](conventions.md#a-table-is-a-value-never-named-arguments) in the API.

### Font

A font asset **is** a [`FontHandle`](font.md) with the two asset verbs on top. See
[`hafen.font`](font.md#the-variant) for `:derive()`, `:family()` and `:size()`, and for the surfaces you
can install it on. Removing a font asset frees nothing, since a font holds no releasable resource; it only
drops the cache entry, so the next load re-reads and re-registers the file.

A [stylesheet](ui/style/text.md#font) may name it by path too, `{asset = "fonts/Inter.ttf", size = 12}`,
under the same [path exception](conventions.md#a-table-is-a-value-never-named-arguments) an image gets.

### Mesh

| Method | Description |
|---|---|
| `mdl:bounds()` | `{min={x=,y=,z=}, max={x=,y=,z=}, extent={x=,y=,z=}}` — axis-aligned bounds in **world units**; the span is `extent`, because a [size](shapes.md#the-anonymous-shapes) is two numbers |
| `mdl:info()` | `{prims, textured, lit, textures, verts, tris}` — what the parser produced |

The supported glTF subset is documented in [`hafen.virtual`](virtual/models.md#the-gltf-subset). A malformed
file, or one using an unsupported feature, raises a clear error that **names** the feature.

> **Disposing a mesh an object is still standing does not break that object.** The object keeps drawing,
> textured and unchanged, because it captured its texture samplers when it was built. What you forfeit is
> the *freeing*: the memory is not reclaimed until that object is destroyed. So remove a mesh only when
> nothing is standing it; the automatic teardown already gets the order right.

### Data

| Method | Description |
|---|---|
| `d:text()` | the file's contents as a **string**, decoded as UTF-8; a leading BOM is stripped |

A `.json` or `.txt` file your addon ships — a config, a word list, a **theme**. It is how an addon's
*content* stops being written in Lua:

```lua
local theme = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(theme.rules):install()    -- a stylesheet that is data
```

**It hands back the text, not a parsed table**, and that is on purpose: reading a file is `hafen.asset`,
parsing JSON is [`hafen.json`](json.md), and gluing them is one line. It also keeps
[interning](#interning) honest, since a parsed table would be mutable shared state handed to every re-load
of the path, where a string cannot be edited behind your back.

The text is read once and held by the handle, so `:text()` is free to call repeatedly — and, like every
other type, an **edit to the file takes effect on `:reload`**, which drops the cache with the addon's
environment. Removing a data asset frees nothing; it only drops that cache entry.

## The collection

| Call | Returns | Description |
|---|---|---|
| `hafen.asset():get(path)` | an asset | load and intern one file, typed by its extension |
| `hafen.asset():list(filter)` | asset`[]` | this addon's live assets, in load order |
| `hafen.asset():count(filter)` | number | how many, without building the array |
| `hafen.asset():find(filter)` | an asset \| nil | the first one that matches |
| `hafen.asset():remove(a)` | the collection | free one **now** rather than at teardown; removals chain |

`filter` is the canonical [filter](conventions.md#the-filter-argument), and a **string** matches the
addon-relative path an asset was loaded from. There is no `:add` — an asset is a file you shipped, not
something you make here.

`:remove(a)` takes the **handle**, like every other place an asset is used. It frees the memory on the spot
and drops the intern entry, so the next `:get(path)` re-reads the file as a new object. It holds **your own
files only**: a built-in font, a derived variant, a [map drawing](map/drawings.md) and a file another addon
loaded are each refused, and each says which of the four it is.

```lua
for _, a in ipairs(hafen.asset():list()) do
  hafen.log():write(("%-5s %s"):format(a:type(), a:path()))
end
```

Only *loaded files* appear. A [built-in font](font.md#the-built-ins) has no file, no path and no lifetime,
so it is never listed, and neither is a derived variant. A freed asset is gone from the list and never
resurrected.

## Errors

Everything below raises a `pcall`-able error naming `hafen.asset`, and each shape is distinguishable:

| What you did | What you get |
|---|---|
| `:get("/etc/passwd")` | the path *is absolute* — an addon loads only its own files |
| `:get("../other/icon.png")` | the path *climbs out of the addon folder* |
| `:get("nope.png")` | *no such file* in this addon's folder, checked before any decode |
| `:get("theme.yaml")` | *no supported extension* — the message lists all of them |
| `:get("broken.png")` | *not a decodable image*, *not a valid font*, or the glTF parser's own message |
| `:get(1)` | the key is a **path string**, not a number |
| `:get({})`, `:get(fn)` | expected a path string, got a table or a function |
| `:get("")` | the path must be a **non-empty** string |
| `:get(nil)` | the key is **required**: arity is the verb here, so a `nil` variable is refused rather than read as the list |
| `:get("icon.png")` from `:lua` | the console **has no addon folder**, and an asset path is relative to the folder of the addon loading it |
| `:remove("icon.png")` | pass the **handle**, not a path — every use site of an asset takes the handle |
| `:remove(h)` on a built-in font or a variant | neither was loaded from a file, so this collection does not hold it |
| `:remove(img)` on another addon's image | it **names the addon that loaded it**: freeing a file is that addon's job |
| `:remove(img)` on a [map drawing](map/drawings.md) | it is a picture of the database, not a file you shipped, and it ends with `img:dispose()` |

## What this door does not open

`hafen.asset` loads **local files only**. A remote asset would mean an async load in a synchronous API, an
untrusted binary going into the font and texture paths, and a per-user tracking channel; fetching *data*
over HTTP is [`hafen.http`](http.md), which is protected by a host allowlist the user approves.

The client already owns every `.res` in the game, and those are **addressed by name** rather than loaded
from your folder: they have no sandbox to pass, no cache of yours to fill and no lifetime to manage. This
namespace is *your files*; the table below is *the game's*.

| Engine resource | Call |
|---|---|
| a `.res` image — action icons, HUD art | [`g:resource(name, x, y)`](ui/drawing.md) |
| a `.res` image as a theme's own art | `{res = name}` in a [rule](ui/style/chrome.md#naming-a-picture)'s `bg`, `border` or `picture` |
| a minimap drawing of ground you explored | [`grid:image(lvl)`](map/drawings.md) |
| a `.res` sound | [`hafen.sound():get(name)`](sound.md) |
| a `.res` prop in the world | [`hafen.virtual`](virtual/ghosts.md) |
| a built-in font | [`hafen.font():get(name)`](font.md#the-built-ins) |

## Example

```lua
local icon, face, chair            -- upvalues; a reload rebuilds the env, so they are nil again

hafen.event():on("Load", function()
  icon  = hafen.asset():get("icon.png")
  face  = hafen.asset():get("fonts/Inter.ttf"):derive():size(12)
  chair = hafen.asset():get("props/chair.glb")
  local s, b = icon:size(), chair:bounds()
  hafen.log():write(("icon %dx%d, chair %.1f tiles tall"):format(s.w, s.h, b.extent.z / 11))
end)

local win = hafen.ui():window():title("My addon"):size(160, 80):font(face)
win:on("Draw", function(ev)
  local g = ev:g()
  g:image(icon, 4, 4, 16, 16)             -- the handle, not the path
  g:text("mine", 26, 6)
end)

hafen.console():on("stand", function()
  local p = hafen.session():current():player():gob():position()
  hafen.virtual():object():add(chair, p)               -- the handle, again
end)
```

## See also

- [`hafen.virtual`](virtual/README.md) — stand an image or a mesh **in the world**
- [drawing](ui/drawing.md) — `g:image` and `g:aimage` draw an image asset on screen
- [`hafen.font`](font.md) — what a font asset does once you have it, and the built-ins that are not assets
- [`hafen.json`](json.md) — turning a data asset's `:text()` into a table
- [references](references.md#asset-a-file-your-addon-ships) — collections, owned resources and teardown
