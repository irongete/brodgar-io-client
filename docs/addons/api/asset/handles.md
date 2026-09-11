# hafen.asset: the handles

What a loaded file answers. Two verbs are on every asset, and each [type](README.md#the-types) adds its
own. All of it is unprotected, and none of it reads the disk again: a handle is the file as it was loaded.

```lua
local icon = hafen.asset():get("icon.png")
hafen.log():write(icon:type() .. " " .. icon:path() .. " " .. icon:size().w .. " wide")
```

## Every asset

| Method | Description |
|---|---|
| `a:type()` | `"image"` \| `"font"` \| `"mesh"` \| `"data"` — what the extension dispatched to |
| `a:path()` | the addon-relative path it was loaded from |

**Freeing one is the collection's verb**, [`hafen.asset():remove(a)`](collection.md#the-collection): the set
that owns your files is what ends one of them. You rarely need it — every asset is **bridge-owned** and
freed for you on reload, disable and relogin, so nothing leaks a GPU texture. Reach for it only to release
a large asset early.

A handle is an **object, not a table**. You call its verbs with `:`, you cannot write to it, and a name it
does not answer raises where you wrote it rather than reading `nil` and failing a call later:

```lua
local icon = hafen.asset():get("icon.png")
tostring(icon)      --> Asset(image, icon.png)   -- the type and the path, for every type
icon:sizes()        -- an error naming :type, :path and :size
icon.size = nil     -- an error: a handle is an object, and its verbs are not fields you can rewrite
```

Nothing but a handle resolves to a file, either. A table you build to look like one is not one: the verbs
that take a picture refuse it naming this loader, and the [draw verbs](../ui/drawing.md), which never throw,
draw nothing for it.

An image you did **not** load — one from another addon's [rule](../ui/style/README.md), read back through
`widget:style()` — answers `:type()`, `:path()` and `:size()` exactly as your own does. What it will not do
is leave your collection: `hafen.asset():remove(it)` is refused, **naming the addon that loaded it**. Freeing
a file is the job of the addon that loaded it.

## Image

| Method | Description |
|---|---|
| `img:size()` | `{w=, h=}` — the file's own dimensions, in [design pixels](../ui/pixels.md) |
| `img:info()` | `{width, height}` — the same two, as a flat snapshot |

The file's pixels **are** design pixels: a 32×32 PNG answers `32, 32` and covers 32×32 wherever it is
drawn, so it stands beside the client's own art at the same size at every interface scale.

A freed image simply **draws nothing** thereafter; the [draw verbs](../ui/drawing.md) are forgiving and
never throw.

A [stylesheet](../ui/style/chrome.md#naming-a-picture) may name the file by path instead, `{asset =
"img/panel.png"}`, which loads through this same door and interns to this same object — the one
[path exception](../conventions.md#a-table-is-a-value-never-named-arguments) in the API.

## Font

A font asset **is** a [`FontHandle`](../font.md) with the two asset verbs on top. See
[`hafen.font`](../font.md#the-variant) for `:derive()`, `:family()` and `:size()`, and for the surfaces you
can install it on. Removing a font asset frees nothing, since a font holds no releasable resource; it only
drops the cache entry, so the next load re-reads and re-registers the file.

A [stylesheet](../ui/style/text.md#font) may name it by path too, `{asset = "fonts/Inter.ttf", size = 12}`,
under the same [path exception](../conventions.md#a-table-is-a-value-never-named-arguments) an image gets.

## Mesh

| Method | Description |
|---|---|
| `mdl:bounds()` | `{min={x=,y=,z=}, max={x=,y=,z=}, extent={x=,y=,z=}}` — axis-aligned bounds in **world units**; the span is `extent`, because a [size](../shapes.md#the-anonymous-shapes) is two numbers |
| `mdl:info()` | `{prims, textured, lit, textures, verts, tris}` — what the parser produced |

The supported glTF subset is documented in [`hafen.virtual`](../virtual/models.md#the-gltf-subset). A
malformed file, or one using an unsupported feature, raises a clear error that **names** the feature.

> **Disposing a mesh an object is still standing does not break that object.** The object keeps drawing,
> textured and unchanged, because it captured its texture samplers when it was built. What you forfeit is
> the *freeing*: the memory is not reclaimed until that object is destroyed, and the handle is dead, so
> that mesh can never be stood again — a later `:get(path)` re-reads the file into a new one. So remove
> a mesh only when nothing is standing it; the automatic teardown already gets the order right.

## Data

| Method | Description |
|---|---|
| `d:bytes()` | the file's contents as a **string of bytes**, exactly as they are on disk — `string.byte` reads them one at a time, `#d:bytes()` is the file's size |
| `d:text()` | the file's contents as a **string**, decoded as UTF-8; a leading BOM is stripped |
| `d:info()` | `{bytes}` — the file's size, without reading either string |

Every file that is not a picture, a font or a model is data — a `.json` or `.txt` you read as text, a
`.mid` or any other binary format you read as bytes. It is how an addon's *content* stops being written in
Lua:

```lua
local theme = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(theme.rules):install()    -- a stylesheet that is data
```

**It hands back the bytes or the text, not a parsed table**, and that is on purpose: reading a file is
`hafen.asset`, parsing JSON is [`hafen.json`](../json.md), parsing a binary format is the addon that knows
it, and gluing them is one line. It also keeps [interning](README.md#interning) honest, since a parsed table
would be mutable shared state handed to every re-load of the path, where a string cannot be edited behind
your back.

`:bytes()` is the file, one Lua character per byte, so a binary format is read with `string.byte` and
`string.sub` and never through `:text()`: decoding a byte that is not UTF-8 replaces it, and a length read
off the text is the length of the decoded string rather than of the file. `:text()` is for a file that is
text. Each string is made on the first call that asks for it and held by the handle after that, so both
are free to call repeatedly — and, like every other type, an **edit to the file takes effect on
`:reload`**, which drops the cache with the addon's environment. Removing a data asset frees nothing; it
only drops that cache entry.

## See also

- [`hafen.asset`](README.md) — the door: the types, the sandbox, interning
- [collection](collection.md) — loading, listing, freeing, and the files a folder of yours holds
- [`hafen.font`](../font.md) — what a font asset does once you have it
- [`hafen.virtual`](../virtual/models.md) — standing a mesh, and the glTF subset it accepts
- [`hafen.json`](../json.md) — turning a data asset's `:text()` into a table
