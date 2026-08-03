# hafen.craft: crafting

Read the open recipe window and press its Craft button. There is nothing to read while no recipe is
open — this namespace is a view of one window, not of everything you can make.

```lua
local r = hafen.craft.current()
if r then
  hafen.log("recipe: " .. r.recipe)
  for _, i in ipairs(r.inputs) do hafen.log("  needs " .. (i.name or i.res) .. " x" .. i.num) end
end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.craft.current()` | [`Craft`](types.md#craft-and-craftspec) \| nil | the open recipe; `nil` when no craft window is up |

The snapshot is `{ recipe, inputs, outputs, qmod, tools }`. `inputs` and `outputs` are
[`CraftSpec`](types.md#craft-and-craftspec) entries, `{res, name, num, opt}`; `qmod`, the quality-affecting
inputs, and `tools`, the required tools, are `{res, name}` arrays. The reader does not throw and is not
gated.

There is no `CraftChanged` event, because a recipe changes only when the player opens one. To notice
that, watch for the window with
[`hafen.ui.on`](ui/replace.md): `hafen.ui.on("window", "appear", fn)`.

## Write (gated: `actions`)

| Function | Description |
|---|---|
| `hafen.craft.make(all)` | craft the open recipe once; with `all = true`, press Craft All |

It consumes the ingredients. Called from an addon that did not declare the permission it raises an
error, and so does calling it with no crafting window open — check `current()` first. See
[`hafen.act`](act.md).

## See also

- [`Craft` and `CraftSpec`](types.md#craft-and-craftspec) — the snapshot shapes
- [`hafen.act`](act.md) — the permission this write shares, and `item` for moving ingredients
- [`hafen.menugrid`](menugrid.md) — how a recipe window gets opened in the first place
