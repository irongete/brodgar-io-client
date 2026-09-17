# resource:layers: The Layers of a Resource

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

## The collection

`resource:layers()` is the [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of a resource's layers, in wire order, keyed by type and id ([keys](#keys)).

| Method | Returns | Permission | Description |
|---|---|---|---|
| `resource:layers():get(key)` | `Layer \| nil` | Unprotected | The first layer at `key`. `nil` when none is there, or while the resource is still loading. |
| `resource:layers():list(filter)` | `Layer[]` | Unprotected | Every layer, in wire order. |
| `resource:layers():count(filter)` | `number` | Unprotected | How many. |
| `resource:layers():find(filter)` | `Layer \| nil` | Unprotected | The first that matches. |
| `resource:layers():add(spec)` | `Layer \| nil` | Unprotected | A [write](writes.md): replaces every layer at the spec's address with one built from the spec. |
| `resource:layers():remove(key)` | the collection | Unprotected | A [write](writes.md): drops every layer at the address. `key` is a layer key or a `Layer`. |

| Rule | Detail |
|---|---|
| `filter` | A string matches the key as a substring, a function is called with the `Layer`. |
| Empty until loaded | The collection is empty until [`resource:loaded()`](README.md) is `true`. A write on a resource that is not loaded registers and applies when it loads. |
| Several layers, one key | The clip variants of one `audio2`, the images of one animation frame: `:get(key)` is the first and `:list(key)` is all of them. |

### Keys

A layer is addressed by its type and, where the type carries one, its id.

| Key | Addresses |
|---|---|
| `"<type>"` | The first layer of that type, in wire order. |
| `"<type>:<id>"` | The layer of that type whose id prints as `<id>`. |

| Rule | Detail |
|---|---|
| The type | The wire's own word: `image`, `tex`, `audio2`, `tooltip`, `pagina`, `props`, `neg`, `obst`, `anim`, `mesh` and the rest. |
| The id | An `image` or `tex` id is a number (`"image:-1"`). An `audio2` or `obst` id is a string (`"audio2:cl"`, `"obst:"` for the client's default `""`). A type without an id has no `:` form. |

## The Layer object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `layer:type()` | `string` | Unprotected | The wire type name. |
| `layer:id()` | `number \| string \| nil` | Unprotected | The layer's own id. `nil` for a type without one. |
| `layer:exists()` | `boolean` | Unprotected | `true` while this layer object is one of its resource's. `false` once a load, or a [write](writes.md), has replaced the resource's layers. |
| `layer:info()` | `table` | Unprotected | The snapshot: `{type, id}` plus the decoded fields [below](#what-info-decodes). |

| Rule | Detail |
|---|---|
| Interned on the layer object | `get("image") == get("image:-1")` when they are the same layer, and the handle works as a table key. |
| Replaced, never updated | A load, or a write, that replaces the resource's layers makes every earlier handle answer `false` to `:exists()`. Read the collection again for the new ones. |

## What `:info()` decodes

Every snapshot carries `type` and `id`. The types below add their fields. Every other type (`mesh`, `skel`, `mat2`, `tileset2` and the rest) is `{type, id}` and nothing more.

| Type | Fields | Notes |
|---|---|---|
| `image` | `z`, `subz`, `nooff`, `offset {x,y}`, `size {w,h}`, `tsz {x,y}`, `scale`, `meta` | `size` is the picture's own pixels. `meta` is the layer's key/value block. |
| `tex` | `size {w,h}` | A texture the renderer samples. |
| `audio2` | `volume`, `meta` | `volume` is the clip's base loudness, `0..1`. |
| `tooltip` | `text` | |
| `pagina` | `text` | |
| `props` | `props` | A string-keyed table of the layer's values. |
| `neg` | `hotspot {x,y}`, `box {x,y,w,h}`, `ep` | `box` is the click-box. `ep` is eight rings of `{x,y}` points, an empty ring where the wire has none. |
| `obst` | `rings` | Rings of `{x,y}` points in world units, model-local. |
| `anim` | `duration`, `frames` | `duration` in milliseconds per frame. `frames` is the image id of each frame, in order. |

| Rule | Detail |
|---|---|
| A key/value block | `meta` and `props` spell the wire's values as Lua. A number, a string and a boolean are themselves. A coordinate is `{x, y}`, a colour `{r, g, b, a}`, a list an array, a map a table, and a resource reference its name. |

---

## See Also

- [`hafen.resource`](README.md) — addressing a resource and reading its state.
- [Writes](writes.md) — `add`, `remove` and `release`.
- [Shapes](../shapes.md) — the `{x, y}`, `{w, h}` and `{x, y, w, h}` tables.
