# hafen.craft: crafting

Read the recipe the player has open, and press its Craft button. This namespace is a view of one window,
not of everything you can make: there is nothing to read while no recipe is open.

```lua
local c = hafen.craft():current()
if c then
  hafen.log():write("recipe: " .. c:name())
  for _, i in ipairs(c:inputs()) do
    hafen.log():write("  needs " .. (i.name or i.res) .. " x" .. i.num)
  end
end
```

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.craft():current()` | `Craft` \| nil | the open recipe; `nil` when no recipe window is up |

> `:current()` is **`nil` while no recipe is open**, which is why the `if c then` above is the whole
> guard you need. Read it again rather than holding one across recipes: opening a different recipe is a
> different window, so the Craft you were holding reports `:exists() == false` from that moment.

## A recipe

| Method | Returns | Description |
|---|---|---|
| `c:name()` | string \| nil | the recipe's name |
| `c:inputs()` | [`CraftSpec`](types.md#craft-and-craftspec)`[]` | the ingredient slots, in window order |
| `c:outputs()` | [`CraftSpec`](types.md#craft-and-craftspec)`[]` | the product slots |
| `c:qualityInputs()` | [`ResRef`](types.md#craft-and-craftspec)`[]` | the ingredients whose quality carries into the product |
| `c:tools()` | [`ResRef`](types.md#craft-and-craftspec)`[]` | the tools you must have with you |
| `c:exists()` | boolean | whether this is still the open recipe — always answers |
| `c:info()` | [`Craft`](types.md#craft-and-craftspec) \| nil | a plain-table **snapshot** |

The four list reads are plain arrays of plain tables, and they are empty rather than `nil` once the
recipe is gone. A slot is `{res, name, num, opt}`: `res` is the **displayed** resource — the constraint
category when the recipe accepts one, such as any board, else the concrete item — `num` is the required
or produced count, with `-1` meaning unspecified, and `opt` marks an optional ingredient or a chance
byproduct.

There is no `CraftChanged` event, because a recipe changes only when the player opens one. To notice
that, watch for the window with [`hafen.ui():on`](ui/replace.md): `hafen.ui():on("window", "appear", fn)`.

## Write (protected: `actions`)

| Method | Description |
|---|---|
| `c:make(all)` | craft the open recipe once; with `all = true`, press Craft All |

It presses the recipe's own button, so it **consumes the ingredients** exactly as a click would. Called
from an addon that did not declare the permission it raises an error naming that permission, and it also
refuses on a recipe that is no longer the open one. It returns the Craft, so writes chain. See
[the actions permission](conventions.md#the-actions-permission), and the **`walker`** addon for a demo.

## See also

- [`Craft` and `CraftSpec`](types.md#craft-and-craftspec) — the snapshot shapes
- [items](ui/items.md#write-protected-actions) — moving the ingredients into the window
- [`hafen.menugrid`](menugrid.md) — how a recipe window gets opened in the first place
