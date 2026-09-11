# hafen.asset: the collection

`hafen.asset()` is the **collection** of the files this addon has loaded — the same section you load with
is the one you read — plus the one read that looks at your folder rather than at what is loaded. Every
verb here is unprotected, and every path is
[addon-relative and sandboxed](README.md#paths-are-addon-relative-and-sandboxed).

```lua
for _, path in ipairs(hafen.asset():files("songs")) do
  hafen.log():write(path .. ": " .. hafen.asset():get(path):info().bytes .. " bytes")
end
```

## The collection

| Call | Returns | Description |
|---|---|---|
| `hafen.asset():get(path)` | an asset | load and intern one file, typed by its extension |
| `hafen.asset():list(filter)` | asset`[]` | this addon's live assets, in load order |
| `hafen.asset():count(filter)` | number | how many, without building the array |
| `hafen.asset():find(filter)` | an asset \| nil | the first one that matches |
| `hafen.asset():remove(a)` | the collection | free one **now** rather than at teardown; removals chain |
| `hafen.asset():files(dir)` | string`[]` | the addon-relative paths of the files inside one folder of yours — [below](#the-files-you-ship) |

`filter` is the canonical [filter](../conventions.md#the-filter-argument), and a **string** matches the
addon-relative path an asset was loaded from. There is no `:add` — an asset is a file you shipped, not
something you make here.

`:remove(a)` takes the **handle**, like every other place an asset is used. It frees the memory on the spot
and drops the intern entry, so the next `:get(path)` re-reads the file as a new object. It holds **your own
files only**: a built-in font, a derived variant, a [map drawing](../map/drawings.md) and a file another
addon loaded are each refused, and each says which of the four it is.

```lua
for _, a in ipairs(hafen.asset():list()) do
  hafen.log():write(("%-5s %s"):format(a:type(), a:path()))
end
```

Only *loaded files* appear. A [built-in font](../font.md#the-built-ins) has no file, no path and no lifetime,
so it is never listed, and neither is a derived variant. A freed asset is gone from the list and never
resurrected.

## The files you ship

### `hafen.asset():files(dir)`

The **regular files** directly inside `dir`, a folder of your own, as a 1-based array of addon-relative
paths spelled with `/` and sorted by name — each one a path `:get` takes as it is. `dir` is a folder
relative to your addon folder, `"songs"` or `"img/icons"`, and leaving it out names the addon folder
itself. It is unprotected, and it reads the folder on every call, so a file dropped in while the client
runs is seen on the next call without a `:reload`.

```lua
local songs = hafen.asset():files("songs")            -- { "songs/air.mid", "songs/reel.mid" }
if #songs == 0 then hafen.log():write("no songs shipped") end
```

What it lists is exactly what `:get` would load: the
[containment check](README.md#paths-are-addon-relative-and-sandboxed) resolves `dir` and then every entry
inside it, so an entry whose real path leaves your folder — a link pointing out of it — is not a file you
ship and is not listed. A folder inside `dir` is not listed either; name it to see what is under it. An
empty folder is an empty array, not an error. It raises on a `dir` that is absolute or climbs out, like
`:get`, and on one that is not a folder of yours, naming it.

## Errors

Everything below raises a `pcall`-able error naming `hafen.asset`, and each shape is distinguishable:

| What you did | What you get |
|---|---|
| `:get("/etc/passwd")` | the path *is absolute* — every path here is relative to your own folder |
| `:get("../other/icon.png")` | the path *is not inside* your addon folder |
| `:get("link/icon.png")`, where `link` points out of your folder | the same refusal: the check follows the link |
| `:get("nope.png")` | *no such file* in this addon's folder, checked before any decode |
| `:get("broken.png")` | *not a decodable image*, *not a valid font*, or the glTF parser's own message |
| `:get(1)` | the key is a **path string**, not a number |
| `:get({})`, `:get(fn)` | expected a path string, got a table or a function |
| `:get("")` | the path must be a **non-empty** string |
| `:get(nil)` | the key is **required**: arity is the verb here, so a `nil` variable is refused rather than read as the list |
| `:get("icon.png")` from `:lua` | the console **has no addon folder**, and an asset path is relative to the folder of the addon loading it |
| `:files("nope")` | *no such folder* in this addon's folder |
| `:files("../other")` | the path *is not inside* your addon folder — the same check `:get` makes |
| `:remove("icon.png")` | pass the **handle**, not a path — every use site of an asset takes the handle |
| `:remove(h)` on a built-in font or a variant | neither was loaded from a file, so this collection does not hold it |
| `:remove(img)` on another addon's image | it **names the addon that loaded it**: freeing a file is that addon's job |
| `:remove(img)` on a [map drawing](../map/drawings.md) | it is a picture of the database, not a file you shipped, and it ends with `img:dispose()` |

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

- [`hafen.asset`](README.md) — the door: the types, the sandbox, interning
- [handles](handles.md) — what each kind of loaded file answers
- [`hafen.font`](../font.md) — the built-ins that are not assets, and why they are not listed here
- [map drawings](../map/drawings.md) — the one picture with an asset's verbs that is not one of your files
