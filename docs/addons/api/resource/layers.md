# Resource Layers

A resource is a list of typed layers: an `image`, a `tooltip`, an `audio2` clip, a `mesh`. `resource:layers()` is that list as a collection, and a `Layer` reads one of them.

```lua
local sun = hafen.resource():get("gfx/hud/calendar/sun")
local poll
poll = hafen.timer():every(0.2, function()
  if not sun:loaded() then return end
  poll:cancel()
  local layers = sun:layers()
  hafen.log():write("images: " .. layers:count("image"))
  local animation = layers:get("anim")
  hafen.log():write("frames: " .. #animation:info().frames)
  local first_frame = layers:get("image:128")
  hafen.log():write("first frame: " .. first_frame:info().size.w .. "x" .. first_frame:info().size.h)
end)
```

---

## Keys

A layer is addressed by its **type** and, where the type carries one, its **id**:

| Key | Addresses |
|---|---|
| `"<type>"` | the first layer of that type, in wire order |
| `"<type>:<id>"` | the layer of that type whose id prints as `<id>` |

The type is the wire's own word (`image`, `tex`, `audio2`, `tooltip`, `pagina`, `props`, `neg`, `obst`, `anim`, `mesh`, …). An `image` or `tex` id is a number (`"image:-1"`); an `audio2` or `obst` id is a string (`"audio2:cl"`, `"obst:"` for the client's default `""`). A type without an id has no `:` form.

Several layers can share a key — the clip variants of one `audio2`, the images of one animation frame — so `:get(key)` is the first and `:list(key)` is all of them.

---

## Methods on `resource:layers()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:get(key)` | `string` | `Layer \| nil` | Unprotected | The first layer at `key`; `nil` when none is there, or while the resource is still loading. |
| `:list([filter])` | `string \| function` | `Layer[]` | Unprotected | Every layer, in wire order; a string matches the key as a substring. |
| `:count([filter])` | `string \| function` | `number` | Unprotected | How many. |
| `:find(filter)` | `string \| function` | `Layer \| nil` | Unprotected | The first that matches. |

The collection is empty until [`resource:loaded()`](README.md) is `true`.

---

## Methods on `Layer`

| Method | Returns | Permission | Description |
|---|---|---|---|
| `layer:type()` | `string` | Unprotected | The wire type name. |
| `layer:id()` | `number \| string \| nil` | Unprotected | The layer's own id; `nil` for a type without one. |
| `layer:exists()` | `boolean` | Unprotected | `true` while this layer object is one of its resource's. A load that replaces the resource's layers makes every earlier handle `false`. |
| `layer:info()` | `table` | Unprotected | The snapshot: `{type, id}` plus the decoded fields below. |

A `Layer` is interned on the layer object: `get("image") == get("image:-1")` when they are the same layer, and the handle works as a table key.

---

## What `:info()` decodes

Every snapshot carries `type` and `id`. The types below add their fields; every other type — `mesh`, `skel`, `mat2`, `tileset2`, … — is `{type, id}` and nothing more.

| Type | Fields | Notes |
|---|---|---|
| `image` | `z`, `subz`, `nooff`, `offset {x,y}`, `size {w,h}`, `tsz {x,y}`, `scale`, `meta` | `size` is the picture's own pixels; `meta` is the layer's key/value block. |
| `tex` | `size {w,h}` | A texture the renderer samples. |
| `audio2` | `volume`, `meta` | `volume` is the clip's base loudness, `0..1`. |
| `tooltip` | `text` | |
| `pagina` | `text` | |
| `props` | `props` | A string-keyed table of the layer's values. |
| `neg` | `hotspot {x,y}`, `box {x,y,w,h}`, `ep` | `box` is the click-box; `ep` is eight rings of `{x,y}` points, an empty ring where the wire has none. |
| `obst` | `rings` | Rings of `{x,y}` points in world units, model-local. |
| `anim` | `duration`, `frames` | `duration` in milliseconds per frame; `frames` is the image id of each frame, in order. |

A key/value block (`meta`, `props`) spells the wire's values as Lua: a number, a string, a boolean, `{x, y}` for a coordinate, `{r, g, b, a}` for a colour, an array for a list, a table for a map, and a resource reference as its name.

---

## See Also

- [`hafen.resource`](README.md) — addressing a resource and reading its state.
- [Shapes](../shapes.md) — the `{x, y}`, `{w, h}` and `{x, y, w, h}` tables.
