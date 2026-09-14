# session:actionbar: Action Bar & Hotkeys

Inspect character action bar hotbar slots, read cooldowns, turn pages, and activate hotbar actions.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local action_bar = session:actionbar()
local current_page = action_bar:page()

hafen.log():write("Action bar showing page: " .. current_page)

-- Inspect the first 12 slots currently visible on screen
local start_slot_index = (current_page - 1) * 12 + 1
for slot_index = start_slot_index, start_slot_index + 11 do
  local slot = action_bar:get(slot_index)
  if slot and not slot:empty() then
    hafen.log():write(string.format("Slot %d: %s (%s)", slot:index(), slot:name() or "?", slot:res() or "?"))
  end
end
```

---

## Methods on `session:actionbar()`

The action bar contains a fixed array of 144 slots partitioned into 12 pages of 12 slots each.

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:get(slot_index)` | `number` | `ActionSlot` | `-` | Returns the slot at 1-based index `1..144`. |
| `:list(filter?)` | `[string \| function]` | `ActionSlot[]` | `-` | All 144 slots in sequential order. |
| `:page()` | None | `number` | `-` | Currently visible page number (`1..12`). |
| `:page(page_number)` | `number` | `self` | `-` | Flips to the designated hotbar page (`1..12`). |

---

## Methods on `ActionSlot`

| Method | Returns | Description |
|---|---|---|
| `:index()` | `number` | 1-based slot index (`1..144`). |
| `:empty()` | `boolean` | `true` if this hotbar slot has no action assigned. |
| `:name()` | `string \| nil` | Display name of the slotted action or ability. |
| `:res()` | `string \| nil` | Underlying resource path. |
| `:cooldown()` | `number \| nil` | Active cooldown fraction `0.0..1.0`. |
| `:info()` | `table \| nil` | Plain table snapshot `{ index, name, res, empty }`. |

---

## Protected Actions

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `slot:use()` | None | `actionbar.use` | Triggers the action or ability assigned to this slot. |
| `slot:res(resource_name)` | `string` | `actionbar.res` | Assigns an action resource to this slot. |
| `slot:clear()` | None | `actionbar.clear` | Clears the assignment in this slot. |
