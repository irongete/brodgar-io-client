# hafen.asset: the files your addon ships

**One loader for every file that lives in your addon's folder.** An image, a font, a 3D model, a data file
— they all come through the same door, and the type comes from the file's **extension**, so you never say
which loader you want.

```lua
local icon  = hafen.asset("icon.png")              -- an image
local face  = hafen.asset("fonts/Inter.ttf")       -- a font
local chair = hafen.asset("props/chair.glb")       -- a mesh
local theme = hafen.asset("theme.json")            -- data (its text)
```

`hafen.asset` is **callable**, and the arity is the verb: `hafen.asset(path)` is **one** asset,
[`hafen.asset()`](#the-collection-form) is the array of the assets this addon currently holds.

There is exactly **one flow** for a local file: load, then draw or decorate or stand or read. Load it once,
keep the handle, hand the *handle* to whatever uses it. The use sites take a handle and nothing else —
passing a path string to [`hafen.render.sprite`](render/sprites.md) or
[`object`](render/models.md) is an error that points you back here.

> **Ungated.** An asset is a client-side file *you shipped*: it never reaches the server and grants no
> gameplay advantage, so it needs no `actions` permission, like a [HUD overlay](ui/custom.md#overlays) or a
> [ghost](ghost.md).

## The types

| Extensions | `a:type()` | What you get | Use it with |
|---|---|---|---|
| `.png` `.jpg` `.jpeg` `.gif` `.bmp` | `"image"` | a GPU texture, alpha preserved | [`g:image`/`g:aimage`](ui/drawing.md), [`hafen.render.sprite`](render/sprites.md) |
| `.ttf` `.otf` | `"font"` | a [`FontHandle`](font.md) whose family is registered, so `$font[…]` works | [`font =`](font.md#draw-with-it), [`hafen.ui.skin`](ui/style/README.md), [`widget:skin`](ui/style/README.md#restyle-one-widget) |
| `.glb` `.gltf` | `"mesh"` | parsed glTF 2.0 static geometry and its textures | [`hafen.render.object`](render/models.md) |
| `.json` `.txt` | `"data"` | the file's **text**, read as UTF-8 | [`hafen.json():parse`](json.md), and anything else that takes a string |

PNG is the recommended image format, for transparency, and `.glb` the recommended model format, being a
single file. Any other extension is an error listing the ones above.

## The loader takes a path and nothing else

`hafen.asset(path)` has **one argument, always**; there is no per-type options table. Loading a *file* is
expensive and happens once, while configuring a *use* of it is cheap and happens many times, so the two are
separate calls:

```lua
local face  = hafen.asset("fonts/Inter.ttf")   -- the file: read and registered once
local small = face:derive{ size = 11 }         -- a use: cheap, non-mutating, as many as you like
local big   = face:derive{ size = 24, bold = true }
```

That split is why the signature is identical for every type, and why [interning](#interning) never depends
on an options table.

## Paths are addon-relative and sandboxed

`path` is relative to **your own addon folder** — `"icon.png"`, `"img/sign.png"`, `"props/tree.glb"`.
Absolute paths and `..` escapes are **rejected**, because an addon reads only its own files. An internal
`a/../b` is fine: it is normalised and still lands inside your folder. External `.gltf` buffer and texture
files are resolved relative to the model file and re-checked against your folder, so a `.gltf` cannot reach
out either.

Decoding is **synchronous**: call `hafen.asset` from setup code — `OnLoad`, `OnEnterWorld`, a command —
**never** from inside a draw callback.

## Interning

The same path is the **same handle**, for every type:

```lua
hafen.asset("icon.png") == hafen.asset("icon.png")             --> true
hafen.asset("./icon.png") == hafen.asset("img/../icon.png")    --> true (the key is the RESOLVED path)
```

So repeating the load is free. There is no reason to thread a handle through your own code just to avoid a
second call, and no reason for the use sites to accept a path string. `a:path()` answers the spelling of
the **first** load, since the resolved path is the key rather than the answer.

> **Identity is stable *while the asset is alive*.** `:dispose()` drops it from the cache, so the next
> `hafen.asset(path)` re-reads the file into a **new** object and `old == hafen.asset(old:path())` is
> `false`. A disposed asset is never served again and never [listed](#the-collection-form).

## Every asset

| Method | Description |
|---|---|
| `a:type()` | `"image"` \| `"font"` \| `"mesh"` \| `"data"` — what the extension dispatched to |
| `a:path()` | the addon-relative path it was loaded from |
| `a:dispose()` | free it **now**, also automatic on reload, disable and relogin; returns the handle |

You rarely need `:dispose()`: every asset is **bridge-owned** and freed for you, so nothing leaks a GPU
texture. Use it only to release a large asset early.

### Image

| Method | Description |
|---|---|
| `img:size()` | `{w, h}` — pixel dimensions |

A disposed image simply **draws nothing** thereafter; the [draw verbs](ui/drawing.md) are forgiving and
never throw.

### Font

A font asset **is** a [`FontHandle`](font.md) with the three asset verbs on top. See
[`hafen.font`](font.md#the-variant) for `:derive(opts)`, `:family()` and `:size()`, and for the surfaces you
can install it on. Disposing a font asset frees nothing, since a font holds no releasable resource; it only
drops the cache entry, so the next load re-reads and re-registers the file.

### Mesh

| Method | Description |
|---|---|
| `mdl:bounds()` | `{min={x,y,z}, max={x,y,z}, size={x,y,z}}` — axis-aligned bounds in **world units** |
| `mdl:info()` | `{prims, textured, lit, textures, verts, tris}` — what the parser produced |

The supported glTF subset is documented in [`hafen.render`](render/models.md#the-gltf-subset). A malformed
file, or one using an unsupported feature, raises a clear error that **names** the feature.

> **Disposing a mesh an object is still standing does not break that object.** The object keeps drawing,
> textured and unchanged, because it captured its texture samplers when it was built. What you forfeit is
> the *freeing*: the memory is not reclaimed until that object is destroyed. So `:dispose()` a mesh only
> when nothing is standing it; the automatic teardown already gets the order right.

### Data

| Method | Description |
|---|---|
| `d:text()` | the file's contents as a **string**, decoded as UTF-8; a leading BOM is stripped |

A `.json` or `.txt` file your addon ships — a config, a word list, a **theme**. It is how an addon's
*content* stops being written in Lua:

```lua
local theme = hafen.json():parse(hafen.asset("theme.json"):text())
hafen.ui.skin(theme.rules)                        -- a stylesheet that is data
```

**It hands back the text, not a parsed table**, and that is on purpose: reading a file is `hafen.asset`,
parsing JSON is [`hafen.json`](json.md), and gluing them is one line. It also keeps
[interning](#interning) honest, since a parsed table would be mutable shared state handed to every re-load
of the path, where a string cannot be edited behind your back.

The text is read once and held by the handle, so `:text()` is free to call repeatedly — and, like every
other type, an **edit to the file takes effect on `:reload`**, which drops the cache with the addon's
environment. Disposing a data asset frees nothing; it only drops that cache entry.

## The collection form

`hafen.asset()` returns a 1-based array of **this addon's live assets**, in load order:

```lua
for _, a in ipairs(hafen.asset()) do
  hafen.log():write(("%-5s %s"):format(a:type(), a:path()))
end
```

An explicit `nil` is the same as no argument, so `hafen.asset(maybePath)` with a `nil` variable gives you
the list rather than an error. Check the variable before you pass it.

Only *loaded files* appear. A [built-in font](font.md#the-built-ins) has no file, no path and no lifetime,
so it is never listed, and neither is a derived variant. A disposed asset is gone from the list and never
resurrected.

## Errors

Everything below raises a `pcall`-able error naming `hafen.asset`, and each shape is distinguishable:

| What you did | What you get |
|---|---|
| `hafen.asset("/etc/passwd")` | the path *is absolute* — an addon loads only its own files |
| `hafen.asset("../other/icon.png")` | the path *climbs out of the addon folder* |
| `hafen.asset("nope.png")` | *no such file* in this addon's folder, checked before any decode |
| `hafen.asset("theme.yaml")` | *no supported extension* — the message lists all of them |
| `hafen.asset("broken.png")` | *not a decodable image*, *not a valid font*, or the glTF parser's own message |
| `hafen.asset(1)` | the key is a **path string**, not a number |
| `hafen.asset({})`, `hafen.asset(fn)` | expected a path string, got a table or a function |
| `hafen.asset("")` | the path must be a **non-empty** string |
| `hafen.asset("icon.png")` from `:lua` | the console **has no addon folder**, and an asset path is relative to the folder of the addon loading it |

## What this door does not open

`hafen.asset` loads **local files only**. A remote asset would mean an async load in a synchronous API, an
untrusted binary going into the font and texture paths, and a per-user tracking channel; fetching *data*
over HTTP is [`hafen.http`](http.md), which is gated by a manifest allowlist.

The client already owns every `.res` in the game, and those are **addressed by name** rather than loaded
from your folder: they have no sandbox to pass, no cache of yours to fill and no lifetime to manage. This
namespace is *your files*; the table below is *the game's*.

| Engine resource | Call |
|---|---|
| a `.res` image — action icons, HUD art | [`g:resource(name, x, y)`](ui/drawing.md) |
| a minimap drawing of ground you explored | [`grid:image(lvl)`](map/drawings.md) |
| a `.res` sound | [`hafen.sound(name)`](sound.md) |
| a `.res` prop in the world | [`hafen.ghost`](ghost.md) |
| a built-in font | [`hafen.font(name)`](font.md#the-built-ins) |

## Example

```lua
local icon, face, chair                     -- upvalues; a reload rebuilds the env, so they are nil again

hafen.event():on("OnLoad", function()
  icon  = hafen.asset("icon.png")
  face  = hafen.asset("fonts/Inter.ttf"):derive{ size = 12 }
  chair = hafen.asset("props/chair.glb")
  local s, b = icon:size(), chair:bounds()
  hafen.log():write(("icon %dx%d, chair %.1f tiles tall"):format(s.w, s.h, b.size.z / 11))
end)

hafen.ui():window()
  :title("My addon")
  :size(160, 80)
  :font(face)
  :onDraw(function(g, w, h)
    g:image(icon, 4, 4, 16, 16)             -- the handle, not the path
    g:text("mine", 26, 6)
  end)

hafen.slash():register("stand", function()
  local p = hafen.player():gob():position()
  hafen.render.object{ model = chair, x = p:x(), y = p:y() }   -- the handle, again
end)
```

## See also

- [`hafen.render`](render/README.md) — stand an image or a mesh **in the world**
- [drawing](ui/drawing.md) — `g:image` and `g:aimage` draw an image asset on screen
- [`hafen.font`](font.md) — what a font asset does once you have it, and the built-ins that are not assets
- [`hafen.json`](json.md) — turning a data asset's `:text()` into a table
- [conventions](conventions.md#asset-a-file-your-addon-ships) — callable namespaces, owned resources
  and teardown
