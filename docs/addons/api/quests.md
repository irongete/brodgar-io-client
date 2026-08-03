# hafen.quests: the quest log

Read the quest log, both the Current and the Completed tabs. Read-only: there is no way to accept,
abandon or complete a quest from an addon.

```lua
-- active quests only
for _, q in ipairs(hafen.quests.list(function(q) return q.status == "pending" end)) do
  hafen.log(q.name)
end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.quests.list(filter)` | [`Quest`](types.md#quest--condition)`[]` | every quest matching the [filter](conventions.md#the-filter-argument) |
| `hafen.quests.selected()` | [`Quest`](types.md#quest--condition) \| nil | the quest open in the log, with its `conds` objectives |

A quest's `status` is `"pending"`, `"done"`, `"failed"` or `"disabled"`. Only the **selected** quest
loads its conditions, so `conds`, an array of [`Condition`](types.md#quest--condition), appears on
`selected()` alone.

`list` answers an empty array and `selected` answers `nil` before the quest log has built, which is a
beat after `OnEnterWorld`, and `selected` answers `nil` whenever nothing is open. Neither throws and
neither is gated.

```lua
local sel = hafen.quests.selected()
if sel then
  for _, c in ipairs(sel.conds) do hafen.log(" - [" .. c.status .. "] " .. (c.desc or "")) end
end
```

Subscribe to [`QuestAdded`](events.md#roster-quests-markers), a new active quest, and
[`QuestDone`](events.md#roster-quests-markers), an active quest completed or failed. Each carries the
quest snapshot.

## See also

- [`Quest` and `Condition`](types.md#quest--condition) — the snapshot shapes
- [`hafen.markers`](markers.md) — the map pins a quest puts down
- [events](events.md#roster-quests-markers) — `QuestAdded` and `QuestDone`
