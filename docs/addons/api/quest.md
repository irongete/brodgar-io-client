# session:quest: Quests

Inspect active personal quests, tasks, conditions, and completion progress.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- List all active quests
for _, active_quest in ipairs(session:quest():list()) do
  local quest_title = active_quest:title() or "Untitled Quest"
  hafen.log():write("Quest: " .. quest_title)

  -- Inspect conditions
  for _, condition_item in ipairs(active_quest:conditions():list()) do
    local description = condition_item:desc() or ""
    local is_done = condition_item:done()
    hafen.log():write(string.format("  [%s] %s", is_done and "X" or " ", description))
  end
end
```

---

## Methods on `session:quest()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Quest[]` | All active quests matching the filter. |
| `:get(quest_id)` | `number` | `Quest \| nil` | Finds a quest by its numerical ID. |
| `:count()` | None | `number` | Total number of active quests. |

---

## Methods on `Quest`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Unique quest identifier. |
| `:title()` | `string \| nil` | Quest display name. |
| `:desc()` | `string \| nil` | Quest lore text and description. |
| `:conditions()`| `ConditionCollection`| List of objective conditions required for completion. |
| `:info()` | `table` | Plain table snapshot `{ id, title, desc }`. |

---

## Methods on `QuestCondition`

| Method | Returns | Description |
|---|---|---|
| `:desc()` | `string \| nil` | Objective text (e.g. "Catch 3 Perch"). |
| `:done()` | `boolean` | `true` if this objective has been satisfied. |
