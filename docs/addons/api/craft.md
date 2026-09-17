# session:craft: Crafting

The recipe one character has open, and its Craft button, reached through the [session](session.md) of the character you mean. A view of one window, not of everything a character can make: nothing to read while no recipe is open.

```lua
local session = hafen.session():current()                      -- the character on screen
if session:craft():exists() then
  hafen.log():write("recipe: " .. session:craft():recipe())
  for _, spec in ipairs(session:craft():inputs():list()) do
    hafen.log():write("  needs " .. (spec:name() or spec:res()) .. " x" .. spec:count())
  end
end
```

---

| Rule | Detail |
|---|---|
| The section is the recipe | `session:craft()` is the open recipe, as [`session:buff()`](buff.md) is the bar and [`session:flowermenu()`](flowermenu.md) the open menu. Every read asks the window again. The server builds a fresh window per recipe, so opening a different one ends this recipe rather than changing it. |
| Nothing open | `:exists()` is `false`, `:recipe()` is `nil`, the slot collections are empty. A session the client no longer holds answers the same, so one guard covers both. |
| The character that opened it | A recipe window belongs to the character it was opened on and stays open while you look at another. `hafen.session():get("alt"):craft():recipe()` reads, and `make` crafts, on a character you are not watching. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:craft():recipe()` | `string \| nil` | Unprotected | The recipe's name, as the server titled the window. |
| `session:craft():inputs()` | collection of [specs](#a-spec) | Unprotected | The ingredient slots, in window order. |
| `session:craft():outputs()` | collection of [specs](#a-spec) | Unprotected | The product slots. |
| `session:craft():qualityInputs()` | collection of [specs](#a-spec) | Unprotected | The ingredients whose quality carries into the product. |
| `session:craft():tools()` | collection of [specs](#a-spec) | Unprotected | The tools you must have with you. |
| `session:craft():exists()` | `boolean` | Unprotected | Whether a recipe is open. Always answers, drawn or not. |
| `session:craft():info()` | [`Craft`](types/ui.md#craft-and-craftspec) `\| nil` | Unprotected | A plain-table snapshot, the slot lists copied out as tables. |

| Rule | Detail |
|---|---|
| The slot collections | `:list(filter)` is the array, `:find(needle)` the first match on name or resource, `:count(filter)` how many. Each empty rather than `nil` when nothing is open. A [view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) minted per call, so `:inputs() ~= :inputs()`. |
| No `:get` | A slot has no key: the server rebuilds a recipe's slots wholesale. `:list()[n]` takes a position. |
| No `CraftChanged` | A recipe changes only when the player opens one. Watch for the window with [`session:ui():on`](ui/replace.md): `session:ui():on("window", "Added", fn)`. |

## A spec

One slot of the open recipe: an ingredient, a product, a quality input or a tool. One type covers them. An ingredient and a product carry a count and an optional flag. A quality input and a tool answer `nil` for both.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `spec:res()` | `string \| nil` | Unprotected | The displayed resource: the constraint category where the recipe accepts one (any board), else the concrete item. `nil` while loading. |
| `spec:name()` | `string \| nil` | Unprotected | Its display name, from the resource's tooltip. `nil` while loading. |
| `spec:count()` | `number \| nil` | Unprotected | How many are required or produced. `nil` on a quality input and a tool. |
| `spec:optional()` | `boolean \| nil` | Unprotected | Whether it is an optional ingredient or a chance byproduct. `nil` on a quality input and a tool. |
| `spec:info()` | [`CraftSpec`](types/ui.md#craft-and-craftspec) | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Holds what it was minted from | The one object here that does not re-resolve: a recipe's slots are data the server rebuilds whole, with no key to track. Read the recipe again rather than holding a slot across an update. |
| Never throws | Every read is unprotected. |
| The unspecified count | The wire's `-1`. `spec:count()` answers `1` for it, the snapshot keeps the server's number. |
| The slot's icon draws an item | `spec:res()` is what the recipe accepts. The slot on screen is an [item icon](ui/items.md), so [`widget:item()`](ui/widget.md#read-methods) on it answers the concrete item painted and `:res()` on that is the concrete resource. Quality inputs and tools have no icon (bare pictures), so `session:craft()` is the only read for them. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:craft():make(all)` | the section | `craft.make` | Craft the open recipe once. With `all = true`, press Craft All. |

| Rule | Detail |
|---|---|
| Presses the recipe's own button | Consumes the ingredients as a click would, on the character whose window it is, watched or not. |
| Refusals | An addon that did not declare `craft.make` raises naming the key ([the permission model](conventions.md#the-permission-model)). No recipe open refuses. |
| One key, every character | `craft.make` presses the button on any of your logins ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). |

---

## See Also

- [Session](session.md) — the address every read here goes through.
- [`Craft` and `CraftSpec`](types/ui.md#craft-and-craftspec) — the snapshot shapes.
- [Items](ui/items.md#write-protected) — moving the ingredients into the window.
- [`session:menugrid`](menugrid.md) — how a recipe window gets opened.
