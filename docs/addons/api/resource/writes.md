# Resource Layer Writes

A write changes what a resource carries: `resource:layers():add(spec)` puts a layer at an address, `:remove(key)` drops the layers at one, and `resource:release()` gives the client's own layers back. A write lives in memory, applies to every load of the name and, when the client already holds the resource, at once.

```lua
local agility = hafen.resource():get("gfx/hud/chr/agi")
-- Declared by name: nothing is fetched, and the tooltip is there whenever the client loads the icon.
agility:layers():add({ type = "tooltip", text = "Agility — how fast you swing" })

local chime = hafen.resource():get("sfx/msg")
chime:layers():add({ type = "audio2", volume = 0.2 })   -- the clip is kept, only its loudness changes
hafen.sound():get("sfx/msg"):play()                       -- plays the quieter chime

hafen.event():on("Disable", function()
  agility:release()   -- optional: a disable releases every write anyway
end)
```

---

## Methods

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `resource:layers():add(spec)` | `table` | `Layer \| nil` | Unprotected | Replaces every layer at the spec's [address](#addresses) with one built from the spec over the first of them. Hands back the new `Layer`, or `nil` while the resource is not loaded — the write is registered either way. |
| `resource:layers():remove(key)` | `string \| Layer` | `LayerCollection` | Unprotected | Drops every layer at `key` (a [layer key](layers.md#keys), or a `Layer` for its key). Chains. |
| `resource:release()` | — | `Resource` | Unprotected | Drops every write your addon made on this resource; a loaded copy shows the client's own layers at once. Chains. |

Every write is checked when made and refused naming what is wrong; a write that registers cannot fail when applied.

---

## Addresses

A spec's address is its `type`, or `type:id` when the spec names an `id`. `add` replaces **every** layer there — the clip variants of one `audio2` share an address — with one layer, placed where the first of them stood, or appended when the address is empty.

A field left out of the spec keeps the original's — the first layer at the address. At an empty address the spec must be whole, or it is refused naming the missing field. `id` defaults to the first original's; with none there, the spec names it.

---

## Spec types

Every spec carries `type`. The fields each type takes:

| `type` | Fields | Whole when |
|---|---|---|
| `tooltip` | `text` | `text` is present. |
| `pagina` | `text` | `text` is present. |
| `audio2` | `id`, `clip`, `volume` | `id` and `clip` are present. `clip` is a data asset holding an Ogg Vorbis file (`hafen.asset():get("chime.ogg")`), checked by its `OggS` header. `volume` is the clip's base loudness, `0` for silent, `1` for as served; `volume` alone keeps the clip. |
| `image` | `id`, `image`, `z`, `subz`, `nooff`, `offset`, `tsz`, `scale`, `meta` | `id` and `image` are present. `image` is an [image asset](../asset/handles.md) (`hafen.asset():get("icon.png")`), written as the picture; `id` is a number (`-1` is the client's default). `z`/`subz` order the draw, `nooff` is a boolean, `offset` and `tsz` are `{x, y}`, `scale` is the picture's own scale, `meta` a [value table](#values). At an empty address a field left out is the wire's default: `0`, `false`, `{0, 0}`, the picture's size plus its offset, `1`, `{}`. |
| `tex` | `id`, `image` | `id` and `image` are present. A texture keeps no picture to carry over, so `image` is required at every address; `id` alone is kept. |
| `neg` | `hotspot`, `box` | Both are present. `hotspot` is `{x, y}`, `box` is `{x, y, w, h}`; the original's `ep` rings are kept, an empty address has none. |
| `obst` | `id`, `rings` | Both are present. `id` is a string (`""` is the client's default, addressed as `"obst:"`); `rings` is an array of rings, each an array of `{x, y}` points in world units (a tile is 11), at most 255 of each. |
| `props` | `props` | `props` is present: a [value table](#values), written whole. |

Every other type — `mesh`, `skel`, `mat2`, `tileset2`, … — is refused naming the writable ones: those arrive only inside a whole `.res` file.

A `remove` key naming `code` or `codeentry` is refused: the client's published code is not a layer your addon writes or removes.

### Values

`meta` and `props` are string-keyed tables whose values are the wire's own kinds. What each Lua value writes, and what [`:info()`](layers.md#what-info-decodes) reads it back as:

| Lua value | Written as | Reads back as |
|---|---|---|
| a string | a string | the string |
| a whole number | an integer | the number |
| any other number | a double | the number |
| `{x = 3, y = 4}` | a coordinate | `{x = 3, y = 4}` |
| an array `{ "a", 2 }` | a list | the array |

A boolean, a colour, a map or anything else is refused naming those kinds.

---

## Order and lifetime

- **Writes apply in the order made**, each over the last: a later write at the same address wins, and a `remove` after an `add` drops what the `add` put there.
- **A write lives in memory.** No file, cache entry or jar is written. It reaches the resource on its next load whatever the source, and a loaded resource at once.
- **Every addon's writes on a name apply together**, in the order made across addons.
- **`resource:release()`** drops your addon's writes on that resource; disabling or reloading your addon releases every write it made.
- A loaded resource that cannot be re-read from its source (its cache file was replaced by a newer version since it loaded) raises on the write, which stays registered and applies on the next load.

---

## What is already built keeps its layers

A write swaps the resource's layer list. What was built from the old list keeps it until the client builds it again: a sprite on screen, a texture the renderer uploaded, a static the client read at start (the login screen's own sounds, the stock button art). The doors that read the current layers on every use are:

| Door | Reads |
|---|---|
| [`hafen.sound():get(name):play()`](../sound.md) | the current `audio2` clip and its `volume` |
| [`graphics:resource(name, x, y)`](../ui/drawing.md) | the current `image` |
| [`widget:source(name)`](../ui/controls/display.md) | the current `image`, when the widget is built |

---

## See Also

- [`hafen.resource`](README.md) — addressing a resource and reading its state.
- [Layers](layers.md) — the layer keys and what `:info()` decodes.
- [Asset handles](../asset/handles.md) — the image asset an `image` is, the data asset a `clip` is.
