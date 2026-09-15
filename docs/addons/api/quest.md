# session:quest: Quests

Inspect active personal quests, conditions, objectives, and completion status.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- List all active quests
for _, active_quest in ipairs(session:quest():list()) do
  local quest_title = active_quest:title() or "Untitled Quest"
  local quest_status = active_quest:status() or "pending"
  hafen.log():write(string.format("Quest: %s [%s]", quest_title, quest_status))

  -- Inspect conditions
  for _, condition_item in ipairs(active_quest:conditions():list()) do
    local description = condition_item:description() or ""
    local condition_status = condition_item:status() or "pending"
    hafen.log():write(string.format("  [%s] %s", condition_status, description))
  end
end
```

---

## Methods on `session:quest()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Quest[]` | All active quests matching the filter. |
| `:get(quest_id)` | `number` | `Quest \| nil` | Finds a quest by its server ID. |
| `:count(filter?)` | `[string \| function]` | `number` | Total number of active quests. |
| `:find(filter)` | `string \| function` | `Quest \| nil` | First quest matching the title or resource filter. |
| `:selected()` | None | `Quest \| nil` | Currently selected quest open in the quest log. |

---

## Methods on `Quest`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Unique quest identifier. |
| `:title()` | `string \| nil` | Quest display name. |
| `:res()` | `string \| nil` | Underlying resource path identifier. |
| `:status()` | `string \| nil` | Completion status (`"pending"`, `"done"`, `"failed"`). |
| `:modified()` | `number \| nil` | Timestamp when the quest was last updated by the server. |
| `:conditions()`| `ConditionCollection` | Collection of objective conditions for this quest. |
| `:exists()` | `boolean` | `true` if quest is still present in the player's quest log. |
| `:info()` | `table \| nil` | Plain table snapshot `{ id, title, res, status, modified, conditions }`. |

---

## Methods on `Condition`

| Method | Returns | Description |
|---|---|---|
| `:description()` | `string \| nil` | Text describing the objective (e.g. "Catch 3 Perch"). |
| `:status()` | `string \| nil` | Status of this condition (`"pending"`, `"done"`, `"failed"`). |
| `:tooltip()` | `string \| nil` | Extra progress or tooltip text (e.g. count progress). |
| `:quest()` | `Quest` | The parent `Quest` this objective belongs to. |
| `:exists()` | `boolean` | `true` if this objective is currently loaded. |
| `:info()` | `table \| nil` | Plain table snapshot `{ description, status, tooltip }`. |
