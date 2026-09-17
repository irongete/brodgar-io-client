# hafen.resource: The Client's Resources

A `.res` is the unit of what the client draws, plays and names: an icon, a chime, a tree, an item's name. `hafen.resource()` addresses one by name, reads what it carries as a collection of [layers](layers.md), and [writes](writes.md) to it. Unprotected: a write lives in this client's memory and reaches neither the server nor the disk.

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

## Read

`hafen.resource()` is the [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of the resources the client holds, keyed by name. `:get(name)` mints a `Resource` for any well-formed name and fetches nothing. The enumerating verbs walk the resources the client holds, the ones something has already fetched.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.resource():get(name)` | `Resource` | Unprotected | The interned handle for `name`. A name with an empty segment, a `..` segment or a leading `/` raises naming the rule. |
| `hafen.resource():list(filter)` | `Resource[]` | Unprotected | The resources the client holds. |
| `hafen.resource():count(filter)` | `number` | Unprotected | How many the client holds. |
| `hafen.resource():find(filter)` | `Resource \| nil` | Unprotected | The first held resource that matches. |

| Rule | Detail |
|---|---|
| `filter` | The canonical [filter](../conventions.md#the-filter-argument): a string matches the name as a substring, a function is called with the `Resource`. |
| A well-formed name | A path: segments split on `/`, none empty, none `..`, none leading. `"gfx/hud/chr/agi"` is one. `"gfx//hud"`, `"gfx/../hud"` and `"/gfx/hud"` raise. |
| The pools are the client's | A resource fetched by one addon is held for everyone. `:count()` grows as the client runs. A fresh client holds only what the login screen drew. |
| Interned per addon | `hafen.resource():get(name)` hands back the same object every call, so `==` compares handles and a handle works as a table key. A `Layer` is interned on the layer object, not on its key ([layers](layers.md)). |

## The Resource object

A live handle. Holding it fetches nothing. A content read is any verb below but `:name()` and `:release()`. It makes the client fetch the resource through its own pools if it has not already, and answers what it can until the fetch lands. Nothing blocks.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `resource:name()` | `string` | Unprotected | The name the handle addresses. |
| `resource:loaded()` | `boolean` | Unprotected | `true` once the client holds the resource. `false` while fetching and after a failed fetch. |
| `resource:version()` | `number \| nil` | Unprotected | The server's version number. `nil` until loaded. |
| `resource:error()` | `string \| nil` | Unprotected | The client's own message for a fetch that failed. `nil` while fetching and once loaded. |
| `resource:info()` | `table \| nil` | Unprotected | `{name, version, layers}`, where `layers` is the array of layer keys in wire order. `nil` until loaded. |
| `resource:layers()` | collection | Unprotected | The resource's [layers](layers.md), one `Layer` per wire layer. Empty until loaded. |
| `resource:layers(file)` | collection | Unprotected | A [write](writes.md#whole-files): makes the resource's layers the ones in `file`, a `.res` data asset. |
| `resource:release()` | the resource | Unprotected | Drops every [write](writes.md) your addon made on this resource. |

| State | `:loaded()` | `:version()` / `:info()` | `:error()` |
|---|---|---|---|
| fetching | `false` | `nil` | `nil` |
| loaded | `true` | the values | `nil` |
| failed | `false` | `nil` | the client's message |

| Rule | Detail |
|---|---|
| A fetch fails | For a name the server has no resource for. The message names the resource and the source that last refused it. |
| Nothing blocks | A content read on a resource the client does not hold starts the fetch and answers the fetching row. Poll `:loaded()` on a [timer](../timer.md). |

---

## See Also

- [Layers](layers.md) — the layer collection, the `Layer` handle, and what `:info()` decodes per type.
- [Writes](writes.md) — `layers():add(spec)`, `layers():remove(key)`, `layers(file)`, `resource:release()`.
- [`hafen.sound`](../sound.md) — playing a resource's clip.
- [`hafen.timer`](../timer.md) — polling `:loaded()`.
- [Conventions](../conventions.md) — collection verbs and filters.
