# session:craft: Crafting Subsystem

Inspect the currently open crafting recipe, view ingredient and tool requirements, and trigger crafting actions.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local craft_subsystem = session:craft()

if craft_subsystem:exists() then
  local recipe_name = craft_subsystem:recipe() or "Unknown Recipe"
  hafen.log():write("Active crafting recipe: " .. recipe_name)

  -- Inspect required ingredients
  for _, ingredient_spec in ipairs(craft_subsystem:inputs():list()) do
    local ingredient_name = ingredient_spec:name() or ingredient_spec:res() or "Item"
    local required_count = ingredient_spec:count() or 1
    hafen.log():write(string.format("  Needs %dx %s", required_count, ingredient_name))
  end

  -- Trigger crafting action (requires "craft.make" permission)
  -- craft_subsystem:make(false) -- set to true for "Craft All"
end
```

---

## Read Methods on `session:craft()`

| Method | Returns | Description |
|---|---|---|
| `:exists()` | `boolean` | `true` if a crafting recipe window is currently open for this character. |
| `:recipe()` | `string \| nil` | Display name of the active recipe. |
| `:inputs()` | `SpecCollection` | List of required ingredient slots. |
| `:outputs()` | `SpecCollection` | List of produced item slots. |
| `:qualityInputs()` | `SpecCollection` | Ingredients whose quality affects the finished product. |
| `:tools()` | `SpecCollection` | List of required crafting tools (e.g. hammer, anvil). |
| `:info()` | `table \| nil` | Plain table snapshot. |

---

## Methods on `CraftSpec` (Ingredient Slot)

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Display name of the required item. |
| `:res()` | `string \| nil` | Resource path of the item or category. |
| `:count()` | `number \| nil` | Quantity required or produced. |
| `:optional()` | `boolean \| nil` | `true` if this is an optional additive or chance byproduct. |

---

## Protected Actions

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:make([craft_all])` | `[boolean]` | `self` | `craft.make` | Triggers the Craft button. If `craft_all` is `true`, presses Craft All. |
