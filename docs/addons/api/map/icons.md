# hafen.map: Icon Categories

Read and toggle the minimap icon registry the client's Icon settings window edits. A category is one kind of gob icon (a boar, a fir tree, a player) with two flags. `show` draws it on the minimap. `notify` plays a sound and a chat message when one appears.

```lua
local boars = hafen.map():icon():get("gfx/terobjs/mm/boar")   -- stop drawing boars, then put it back
if boars then boars:show(false) end
if boars then boars:show(true) end
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.map():icon():get(res)` | `IconCat \| nil` | Unprotected | One category, by its icon resource name, the identity. |
| `hafen.map():icon():list(filter)` | `IconCat[]` | Unprotected | Every category, in resource-name order. |
| `hafen.map():icon():find(filter)` | `IconCat \| nil` | Unprotected | The first match. |
| `hafen.map():icon():count(filter)` | `number` | Unprotected | How many match. |

| Rule | Detail |
|---|---|
| Addressing and searching differ | `:get` takes the resource name. `:list`, `:find` and `:count` take the canonical [filter](../conventions.md#the-filter-argument), a string matching the display name the settings window shows. A number is refused either way: the registry grows as the character sees new icon types, so there is no position. |
| Interned | `hafen.map():icon():get(res)` hands back the same object every time. `seen[category] = true` works. |
| The registry | Empty until the HUD is up, growing as the character sees new icon types. No `*Changed` event: read on demand. |

## The IconCat object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `category:res()` | `string` | Unprotected | The icon resource name, the identity. Answers from the handle alone. |
| `category:name()` | `string \| nil` | Unprotected | The icon's tooltip, falling back to the resource name. |
| `category:exists()` | `boolean` | Unprotected | Whether the registry still carries this resource. |
| `category:show()` / `category:show(flag)` | `boolean \| nil` / the category | Unprotected | Draw it on the minimap. |
| `category:notify()` / `category:notify(flag)` | `boolean \| nil` / the category | Unprotected | Sound and chat line when one appears. |
| `category:info()` | [`IconCategory`](../types/map.md#iconcategory) `\| nil` | Unprotected | The snapshot. |

```lua
for _, category in ipairs(hafen.map():icon():list(function(candidate) return candidate:res():find("borka") end)) do
  category:notify(true)                                   -- announce every player-type icon
end
```

| Rule | Detail |
|---|---|
| Arity is the verb | No argument reads, an argument writes and returns the category, so `category:show(true):notify(true)` chains. A write to a resource the registry does not carry raises. `category:exists()` asks first. |
| No permission | A client-local display setting the server never sees. Persists per character and takes effect immediately, as the settings window's checkboxes do, so a broad sweep rewrites configuration the user set by hand. |
| A category is a resource | Where an icon resource publishes several variants, they are one category and a write reaches all. The client keys them by resource plus an opaque sub-id no name could address. |

---

## See Also

- [`IconCategory`](../types/map.md#iconcategory) — what `category:info()` hands back.
- [Gob](../gob.md) — `gob:icon()`, the category name on a live object.
- [The map database](README.md) — interning, which is why a stashed category never goes stale.
- [`session:menugrid`](../menugrid.md) — the other registry addressed by resource name.
