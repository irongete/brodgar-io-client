# session:flowermenu: Context Radial Flower Menu

Inspect options offered by contextual right-click radial flower menus and programmatically select petals or cancel the menu.

## Quick Example

```lua
-- React whenever a radial flower menu opens
hafen.event():on("FlowerMenuAdded", function(petals, session)
  local target_gob = session:flowermenu():gob()
  local target_name = target_gob and target_gob:name() or "Unknown Target"

  hafen.log():write("Radial menu opened for: " .. target_name)
  for index, petal in ipairs(petals) do
    hafen.log():write(string.format("  [%d] %s", index, petal:label() or ""))
  end
end)

hafen.event():on("FlowerMenuRemoved", function(selected_label)
  hafen.log():write("Radial menu closed. Selected: " .. (selected_label or "Cancelled"))
end)
```

---

## Methods on `session:flowermenu()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Petal[]` | Array of all available petal options in ring order. |
| `:count()` | None | `number` | Total number of petals on the active menu. |
| `:get(index)` | `number` | `Petal \| nil` | The petal at 1-based index `1..N`. |
| `:find(filter)` | `string \| function` | `Petal \| nil` | Finds first petal matching the label filter. |
| `:gob()` | None | `Gob \| nil` | The world game object that was clicked to open this menu. |

---

## Methods on `Petal`

| Method | Returns | Description |
|---|---|---|
| `:label()` | `string \| nil` | Text caption displayed on the petal. |
| `:index()` | `number` | 1-based index on the radial ring (`1..N`). |
| `:exists()` | `boolean` | `true` if this petal belongs to the currently active open menu. |

---

## Protected Actions

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `session:flowermenu():select(target_petal)` | `Petal \| string \| number` | `flowermenu.select` | Selects and activates an option from the radial menu. |
| `petal:select()` | None | `flowermenu.select` | Selects this specific petal. |
| `session:flowermenu():cancel()` | None | `flowermenu.cancel` | Dismisses and closes the active radial menu without picking an option. |
