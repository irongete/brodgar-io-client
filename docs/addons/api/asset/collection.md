# hafen.asset: The Collection

`hafen.asset()` is the collection of the files this addon has loaded, plus the one read that looks at your folder rather than at what is loaded. Every verb is unprotected; every path is [addon-relative and sandboxed](README.md#paths-are-addon-relative-and-sandboxed).

```lua
for _, path in ipairs(hafen.asset():files("songs")) do
  hafen.log():write(path .. ": " .. hafen.asset():get(path):info().bytes .. " bytes")
end
```

---

## The collection

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.asset():get(path)` | asset | Unprotected | Load and intern one file, typed by its extension. |
| `hafen.asset():list(filter)` | asset`[]` | Unprotected | This addon's live assets, in load order. |
| `hafen.asset():count(filter)` | `number` | Unprotected | How many, without building the array. |
| `hafen.asset():find(filter)` | asset `\| nil` | Unprotected | The first that matches. |
| `hafen.asset():remove(asset)` | the collection | Unprotected | Free one now rather than at teardown. |
| `hafen.asset():files(dir)` | `string[]` | Unprotected | The addon-relative paths of the files inside one folder of yours ([below](#the-files-you-ship)). |

| Rule | Detail |
|---|---|
| `filter` | The canonical [filter](../conventions.md#the-filter-argument); a string matches the addon-relative path an asset was loaded from. |
| No `:add` | An asset is a file you shipped. |
| `:remove(asset)` | Takes the handle. Frees the memory on the spot and drops the intern entry, so the next `:get(path)` re-reads the file as a new object. Your own files only: a built-in font, a derived variant, a [map drawing](../map/drawings.md) and a file another addon loaded are each refused, saying which. |
| Only loaded files are listed | A [built-in font](../font.md#the-built-ins) has no file, path or lifetime; a derived variant is not listed either. A freed asset is gone and never resurrected. |

```lua
for _, asset in ipairs(hafen.asset():list()) do
  hafen.log():write(("%-5s %s"):format(asset:type(), asset:path()))
end
```

## The files you ship

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.asset():files(dir)` | `string[]` | Unprotected | The regular files directly inside `dir`, as a 1-based array of addon-relative paths spelled with `/`, sorted by name, each a path `:get` takes as it is. `dir` is relative to your addon folder (`"songs"`, `"img/icons"`); omitted, it names the addon folder itself. |

```lua
local songs = hafen.asset():files("songs")            -- { "songs/air.mid", "songs/reel.mid" }
if #songs == 0 then hafen.log():write("no songs shipped") end
```

| Rule | Detail |
|---|---|
| Reads the folder every call | A file dropped in while the client runs is seen on the next call without a `:reload`. |
| Exactly what `:get` would load | The [containment check](README.md#paths-are-addon-relative-and-sandboxed) resolves `dir` and every entry: an entry whose real path leaves your folder (a link out) is not listed. A folder inside `dir` is not listed; name it to see under it. An empty folder is an empty array. |
| Raises | A `dir` that is absolute or climbs out, as `:get` does; one that is not a folder of yours, naming it. |

## Errors

Every refusal is a `pcall`-able error naming `hafen.asset`.

| Call | Refusal names |
|---|---|
| `:get("/etc/passwd")` | The path is absolute; every path is relative to your own folder. |
| `:get("../other/icon.png")` | The path is not inside your addon folder. |
| `:get("link/icon.png")`, `link` pointing out | The same refusal: the check follows the link. |
| `:get("nope.png")` | No such file in this addon's folder, checked before any decode. |
| `:get("broken.png")` | Not a decodable image, not a valid font, or the glTF parser's own message. |
| `:get(1)` | The key is a path string, not a number. |
| `:get({})`, `:get(fn)` | Expected a path string, got a table or a function. |
| `:get("")` | The path must be a non-empty string. |
| `:get(nil)` | The key is required: arity is the verb, so a `nil` variable is refused rather than read as the list. |
| `:get("icon.png")` from `:lua` | The console has no addon folder; an asset path is relative to the loading addon's folder. |
| `:files("nope")` | No such folder in this addon's folder. |
| `:files("../other")` | The path is not inside your addon folder. |
| `:remove("icon.png")` | Pass the handle, not a path. |
| `:remove(handle)` on a built-in font or a variant | Neither was loaded from a file, so this collection does not hold it. |
| `:remove(image)` on another addon's image | The addon that loaded it: freeing a file is that addon's job. |
| `:remove(image)` on a [map drawing](../map/drawings.md) | A picture of the database, not a file you shipped; it ends with `image:dispose()`. |

## Example

```lua
local icon, face, chair            -- upvalues; a reload rebuilds the env, so they are nil again

hafen.event():on("Load", function()
  icon  = hafen.asset():get("icon.png")
  face  = hafen.asset():get("fonts/Inter.ttf"):derive():size(12)
  chair = hafen.asset():get("props/chair.glb")
  local icon_size, chair_bounds = icon:size(), chair:bounds()
  hafen.log():write(("icon %dx%d, chair %.1f tiles tall"):format(icon_size.w, icon_size.h, chair_bounds.extent.z / 11))
end)

local window = hafen.ui():window():title("My addon"):size(160, 80):font(face)
window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  graphics:image(icon, 4, 4, 16, 16)             -- the handle, not the path
  graphics:text("mine", 26, 6)
end)

hafen.console():on("stand", function()
  local position = hafen.session():current():player():gob():position()
  hafen.virtual():object():add(chair, position)               -- the handle, again
end)
```

---

## See Also

- [`hafen.asset`](README.md) — the door: the types, the sandbox, interning.
- [Handles](handles.md) — what each kind of loaded file answers.
- [`hafen.font`](../font.md) — the built-ins that are not assets, and why they are not listed here.
- [Map drawings](../map/drawings.md) — the one picture with an asset's verbs that is not one of your files.
