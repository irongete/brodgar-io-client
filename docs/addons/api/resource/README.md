# hafen.resource: The Client's Resources

A `.res` is the unit of what the client draws, plays and names: an icon, a chime, a tree, an item's name. `hafen.resource()` addresses one by name and reads what it carries as a collection of [layers](layers.md).

```lua
local resources = hafen.resource()
local agility = resources:get("gfx/hud/chr/agi")

-- A content read makes the client fetch the resource; poll until it lands.
local poll
poll = hafen.timer():every(0.2, function()
  if not agility:loaded() then return end
  poll:cancel()
  local tooltip = agility:layers():get("tooltip")
  hafen.log():write(agility:name() .. " v" .. agility:version() .. ": " .. tooltip:info().text)
end)
```

---

## Methods on `hafen.resource()`

The section object is the collection. `:get(name)` mints a `Resource` for any well-formed name and fetches nothing; the three enumerating verbs walk the resources the client **holds** — the ones something has already fetched — and match a string filter against the name.

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:get(name)` | `string` | `Resource` | Unprotected | The interned handle for `name`. Refuses a name with an empty segment, a `..` segment or a leading `/`, naming the rule. |
| `:list([filter])` | `string \| function` | `Resource[]` | Unprotected | The resources the client holds, by name substring or predicate. |
| `:count([filter])` | `string \| function` | `number` | Unprotected | How many the client holds. |
| `:find(filter)` | `string \| function` | `Resource \| nil` | Unprotected | The first held resource that matches. |

A well-formed name is a path: segments split on `/`, none empty, none `..`, none leading. `"gfx/hud/chr/agi"` is one; `"gfx//hud"`, `"gfx/../hud"` and `"/gfx/hud"` are refused.

---

## Methods on `Resource`

A `Resource` is a live handle. Holding it fetches nothing. **A content read** — any verb below but `:name()` — makes the client fetch the resource through its own pools if it has not already, and answers what it can until the fetch lands. Nothing blocks.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `resource:name()` | `string` | Unprotected | The name the handle addresses. |
| `resource:loaded()` | `boolean` | Unprotected | `true` once the client holds the resource; `false` while fetching and after a failed fetch. |
| `resource:version()` | `number \| nil` | Unprotected | The server's version number; `nil` until loaded. |
| `resource:error()` | `string \| nil` | Unprotected | The client's own message for a fetch that failed; `nil` while fetching and once loaded. |
| `resource:info()` | `table \| nil` | Unprotected | `{name, version, layers}` — `layers` is the array of layer keys in wire order; `nil` until loaded. |
| `resource:layers()` | `LayerCollection` | Unprotected | The resource's [layers](layers.md), one `Layer` per wire layer; empty until loaded. |

### Loading and failure

| State | `:loaded()` | `:version()` / `:info()` | `:error()` |
|---|---|---|---|
| fetching | `false` | `nil` | `nil` |
| loaded | `true` | the values | `nil` |
| failed | `false` | `nil` | the client's message |

A name the server has no resource for fails; the message names the resource and the source that last refused it. The client's pools are the client's: a resource fetched by one addon is held for everyone, so `:count()` grows as the client runs and a fresh client holds only what the login screen drew.

### Identity

`hafen.resource():get(name)` hands back the same object every call, so `==` compares handles and a handle works as a table key. A `Layer` is interned on the layer object, not on its key — see [layers](layers.md).

---

## See Also

- [Layers](layers.md) — the layer collection, the `Layer` handle, and what `:info()` decodes per type.
- [`hafen.sound`](../sound.md) — playing a resource's clip.
- [`hafen.timer`](../timer.md) — polling `:loaded()`.
- [Conventions](../conventions.md) — collection verbs and filters.
