# hafen.craft — crafting

Read the open recipe/craft window and press its Craft button. `make` is **gated** — it requires the
[`actions` permission](actions.md).

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.craft.current()` | [`Craft`](types.md#craft--craftspec) \| nil | the open recipe, or nil when no craft window is up |

The snapshot is `{ recipe, inputs, outputs, qmod, tools }`. `inputs`/`outputs` are
[`CraftSpec`](types.md#craft--craftspec) entries (`{res, name, num, opt}`); `qmod` (quality-affecting
inputs) and `tools` (required tools) are `{res, name}` arrays.

```lua
local r = hafen.craft.current()
if r then
  hafen.log("recipe: " .. r.recipe)
  for _, i in ipairs(r.inputs) do hafen.log("  needs " .. (i.name or i.res) .. " x" .. i.num) end
end
```

## Write *(gated — requires the `actions` permission)*

| Function | Description |
|---|---|
| `hafen.craft.make([all])` | craft the open recipe once; `all = true` presses **Craft All**. Consumes the ingredients |

There is no `CraftChanged` event — a recipe changes only when the player opens one. To detect that,
watch for the window with [`hafen.ui.on`](ui.md#observing--replacing-the-clients-own-ui):
`hafen.ui.on("window", "appear", fn)`.
