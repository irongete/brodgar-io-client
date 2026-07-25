# hafen.quests — quest log

Read the quest log (both the Current and Completed tabs). Read-only.

| Function | Returns | Description |
|---|---|---|
| `hafen.quests.list([filter])` | [`Quest`](types.md#quest--condition)`[]` | all quests matching the [filter](conventions.md#the-filter-argument) |
| `hafen.quests.selected()` | [`Quest`](types.md#quest--condition) \| nil | the quest currently open in the log, with its `conds` objectives, or nil |

A quest's `status` is `"pending"`, `"done"`, `"failed"`, or `"disabled"`. Only the **selected** quest
loads its conditions, so `conds` (an array of [`Condition`](types.md#quest--condition)) appears only on
`selected()`.

```lua
-- active quests only:
for _, q in ipairs(hafen.quests.list(function(q) return q.status == "pending" end)) do
  hafen.log(q.name)
end

local sel = hafen.quests.selected()
if sel then
  for _, c in ipairs(sel.conds) do hafen.log(" - [" .. c.status .. "] " .. (c.desc or "")) end
end
```

Subscribe to [`QuestAdded`](events.md#roster-quests-markers) (a new active quest) and
[`QuestDone`](events.md#roster-quests-markers) (an active quest completed/failed). Each carries the
quest snapshot.
