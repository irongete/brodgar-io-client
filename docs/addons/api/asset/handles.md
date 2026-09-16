# hafen.asset: The Handles

What a loaded file answers: two verbs on every asset, and each [type](README.md#the-types) adds its own. Unprotected, and none of it reads the disk again: a handle is the file as it was loaded.

```lua
local icon = hafen.asset():get("icon.png")
hafen.log():write(icon:type() .. " " .. icon:path() .. " " .. icon:size().w .. " wide")
```

---

## Every asset

| Method | Returns | Permission | Description |
|---|---|---|---|
| `asset:type()` | `string` | Unprotected | `"image"`, `"font"`, `"mesh"` or `"data"`: what the extension dispatched to. |
| `asset:path()` | `string` | Unprotected | The addon-relative path it was loaded from. |

| Rule | Detail |
|---|---|
| Freeing is the collection's verb | [`hafen.asset():remove(asset)`](collection.md#the-collection). Every asset is bridge-owned and freed on reload, disable and relogin, so nothing leaks a GPU texture; remove early only to release a large asset. |
| An object, not a table | Verbs are called with `:`, nothing can be written to it, and a name it does not answer raises where you wrote it: `icon:sizes()` names `:type`, `:path` and `:size`; `icon.size = nil` raises. `tostring(icon)` is `Asset(image, icon.png)`. |
| Nothing but a handle resolves to a file | A look-alike table is refused by the verbs that take a picture, naming this loader; the [draw verbs](../ui/drawing.md), which never throw, draw nothing for it. |
| An image you did not load | One from another addon's [rule](../ui/style/README.md), read back through `widget:style()`, answers `:type()`, `:path()` and `:size()` as your own does; `hafen.asset():remove(it)` is refused naming the addon that loaded it. |

## Image

| Method | Returns | Permission | Description |
|---|---|---|---|
| `image:size()` | `{w=, h=}` | Unprotected | The file's own dimensions, in [design pixels](../ui/pixels.md). |
| `image:info()` | `{width, height}` | Unprotected | The same two, as a flat snapshot. |

| Rule | Detail |
|---|---|
| Pixels are design pixels | A 32×32 PNG answers `32, 32` and covers 32×32 wherever drawn, beside the client's own art at every interface scale. |
| Where it goes | [`resource:layers():add`](../resource/writes.md) takes an image asset as an `image` or `tex` picture. A [stylesheet](../ui/style/chrome.md#naming-a-picture) may name the file by path, `{asset = "img/panel.png"}`, loading through this door and interning to this object: the one [path exception](../conventions.md#a-table-is-a-value-never-named-arguments) in the API. |
| Freed | Draws nothing thereafter; the draw verbs never throw. |

## Font

A font asset is a [`FontHandle`](../font.md) with the two asset verbs on top: [`hafen.font`](../font.md#the-variant) has `:derive()`, `:family()`, `:size()` and the surfaces to install it on. Removing one frees nothing (a font holds no releasable resource); it drops the cache entry, so the next load re-reads and re-registers the file. A [stylesheet](../ui/style/text.md#font) may name it by path, `{asset = "fonts/Inter.ttf", size = 12}`, under the same path exception.

## Mesh

| Method | Returns | Permission | Description |
|---|---|---|---|
| `mesh:bounds()` | `{min={x=,y=,z=}, max={x=,y=,z=}, extent={x=,y=,z=}}` | Unprotected | Axis-aligned bounds in world units; the span is `extent`, since a [size](../shapes.md#the-anonymous-shapes) is two numbers. |
| `mesh:info()` | `{prims, textured, lit, textures, verts, tris}` | Unprotected | What the parser produced. |

The supported subset is on [`hafen.virtual`](../virtual/models.md#the-gltf-subset); a malformed file or an unsupported feature raises naming the feature.

> **Disposing a mesh an object is standing does not break that object.** It keeps drawing, textured and unchanged, having captured its samplers when built. What you forfeit is the freeing: the memory stays until that object is destroyed, and the handle is dead, so the mesh cannot be stood again; a later `:get(path)` re-reads into a new one. Remove a mesh only when nothing stands it; the automatic teardown gets the order right.

## Data

| Method | Returns | Permission | Description |
|---|---|---|---|
| `data:bytes()` | `string` | Unprotected | The file's contents as a string of bytes, exactly as on disk: `string.byte` reads them, `#data:bytes()` is the file's size. |
| `data:text()` | `string` | Unprotected | The contents decoded as UTF-8; a leading BOM is stripped. |
| `data:info()` | `{bytes}` | Unprotected | The file's size, without reading either string. |

```lua
local theme = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(theme.rules):install()    -- a stylesheet that is data
```

| Rule | Detail |
|---|---|
| What is data | Every file that is not a picture, a font or a model: a `.json` or `.txt` read as text, a `.mid` or any binary read as bytes. An `.ogg` data asset is what [`resource:layers():add`](../resource/writes.md) takes as an `audio2` clip, a `.res` one what [`resource:layers(file)`](../resource/writes.md#whole-files) takes. |
| Bytes or text, not a parsed table | Reading a file is `hafen.asset`, parsing JSON is [`hafen.json`](../json.md), parsing a binary format is the addon that knows it. A parsed table would be mutable shared state handed to every re-load of the path; a string cannot be edited behind your back, which keeps [interning](README.md#interning) honest. |
| Binary goes through `:bytes()` | One Lua character per byte, read with `string.byte` and `string.sub`. `:text()` replaces a byte that is not UTF-8, and its length is the decoded string's, not the file's. |
| Cached on first call | Each string is made once and held by the handle. An edit to the file takes effect on `:reload`, which drops the cache with the addon's environment. Removing a data asset drops that cache entry only. |

---

## See Also

- [`hafen.asset`](README.md) — the door: the types, the sandbox, interning.
- [Collection](collection.md) — loading, listing, freeing, and the files a folder of yours holds.
- [`hafen.font`](../font.md) — what a font asset does once you have it.
- [`hafen.virtual`](../virtual/models.md) — standing a mesh, and the glTF subset it accepts.
- [`hafen.json`](../json.md) — turning a data asset's `:text()` into a table.
