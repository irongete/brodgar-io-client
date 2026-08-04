# hafen.map: icon categories

Read and toggle the minimap icon registry — the same categories the client's Icon settings window edits.
A **category** is one kind of gob icon (a boar, a fir tree, a player) with two flags: `show`, draw it on
the minimap, and `notify`, play a sound and a chat message when one appears.

```lua
-- stop drawing boars, then put it back
local boars = hafen.map():icon():get("gfx/terobjs/mm/boar")
if boars then boars:show(false) end
-- …
if boars then boars:show(true) end
```

| Call | Returns | Description |
|---|---|---|
| `hafen.map():icon():get(res)` | `IconCat` \| nil | one category, by its icon **resource name** — the identity |
| `hafen.map():icon():list(filter)` | `IconCat[]` | every category, in resource-name order |
| `hafen.map():icon():find(filter)` | `IconCat` \| nil | the first match |
| `hafen.map():icon():count(filter)` | number | how many match |

**Addressing and searching are different verbs.** `:get` takes the resource name, which is what a category
*is*; `:list`, `:find` and `:count` take the canonical [filter](../conventions.md#the-filter-argument), and
a string one matches the **display name** — the words a player reads in the settings window. A number is
refused either way: the registry grows as the character sees new icon types, so there is no position to
address by.

A category's identity is its resource name, so `hafen.map():icon():get(res)` hands back the same interned
object every time and `seen[cat] = true` works.

## The IconCat object

| Method | Returns | Description |
|---|---|---|
| `cat:res()` | string | the icon resource name — the identity; answers from the handle alone |
| `cat:name()` | string \| nil | the icon's tooltip, falling back to the resource name |
| `cat:exists()` | bool | is the registry still carrying this resource? |
| `cat:show()` / `cat:show(on)` | bool \| nil / self | draw it on the minimap — read, or write and chain |
| `cat:notify()` / `cat:notify(on)` | bool \| nil / self | sound and chat line when one appears |
| `cat:info()` | [`IconCategory`](../types.md#iconcategory) \| nil | the snapshot escape hatch |

**Arity is the verb**: no argument reads, an argument writes and returns the category itself, so writes
chain — `cat:show(true):notify(true)`. A write to a resource the registry does not carry is an error,
not a silent no-op; `cat:exists()` is how you ask first.

```lua
for _, c in ipairs(hafen.map():icon():list(function(c) return c:res():find("borka") end)) do
  c:notify(true)                                   -- announce every player-type icon
end
```

> **These writes need no permission.** They change a client-local display setting, nothing the
> server sees. They persist per character and take effect immediately, exactly as the settings window's
> checkboxes do — so a broad sweep rewrites configuration the user set by hand.

The registry is empty until the HUD is up, and it grows as the character sees new icon types. It changes
rarely, so there is no `*Changed` event — read it on demand.

> **A category is a resource.** Where an icon resource publishes several variants of itself, they are
> one category here and a write reaches all of them: the engine keys them by resource *plus* an opaque
> sub-id that no name could address, and on the minimap they are one thing to a player anyway.

## See also

- [`IconCategory`](../types.md#iconcategory) — what `cat:info()` hands back
- [Gob](../gob.md) — `gob:icon()`, the category name on a live object
- [the map database](README.md) — interning, which is why a stashed category never goes stale
- [`hafen.menugrid`](../menugrid.md) — the other registry addressed by resource name
