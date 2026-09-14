# session:menugrid: Action Menu (Paginae)

Read the character's main action menu tree (Adventure, Craft, Build actions), invoke actions, or register custom addon entries into the menu.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local menu_catalog = session:menugrid()

-- Look up action by display name or resource path
local dig_action = menu_catalog:get("Dig") or menu_catalog:get("paginae/act/dig")

if dig_action and dig_action:exists() then
  hafen.log():write("Found action: " .. (dig_action:name() or "Dig"))

  -- Trigger the action (requires "menugrid.use" permission)
  -- dig_action:use()
end
```

---

## Methods on `session:menugrid()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:get(name_or_resource)`| `string` | `Pagina \| nil` | Finds an action by exact resource path (e.g. `"paginae/act/dig"`) or display name (`"Dig"`). |
| `:list(filter?)` | `[string \| function]` | `Pagina[]` | All unlocked actions in the menu grid. |
| `:count(filter?)` | `[string \| function]` | `number` | Count of unlocked actions matching the filter. |
| `:roots()` | None | `PaginaCollection` | Top-level root category actions (e.g. Adventure, Craft, Build). |
| `:add(addon_action_id)` | `string` | `CustomPagina` | Registers a custom action button into the character's menu grid. |

---

## Methods on `Pagina` (Action Entry)

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Display name shown on the menu button. |
| `:res()` | `string` | Unique resource identifier path. |
| `:tooltip()` | `string \| nil` | Lore description or instructions. |
| `:parent()` | `Pagina \| nil` | Enclosing category node, or `nil` if root. |
| `:children()` | `PaginaCollection` | Sub-actions contained within this category. |
| `:exists()` | `boolean` | `true` if this action is unlocked and present in the menu. |
| `:info()` | `table \| nil` | Plain table snapshot. |

---

## Protected Actions

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `action_entry:use()` | None | `menugrid.use` | Invokes the action, exactly as if clicked by the player. |
