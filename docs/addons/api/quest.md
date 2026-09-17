# session:quest: The Quest Log

One character's quest log, read through its [session](session.md). `session:quest()` is that character's log, one collection over the Current and Completed tabs. Read-only: no addon accepts, abandons or completes a quest.

```lua
local session = hafen.session():current()                    -- the character on screen
local pending = session and session:quest():list(function(quest) return quest:status() == "pending" end) or {}
for _, quest in ipairs(pending) do
  hafen.log():write(quest:title())
end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:quest():list(filter)` | `Quest[]` | Unprotected | Every quest, Current tab first. |
| `session:quest():count(filter)` | `number` | Unprotected | How many match. |
| `session:quest():find(filter)` | `Quest \| nil` | Unprotected | The first that matches. |
| `session:quest():get(id)` | `Quest \| nil` | Unprotected | One quest, by its server id. |
| `session:quest():selected()` | `Quest \| nil` | Unprotected | The quest open in that character's log. `nil` when nothing is open. |

| Rule | Detail |
|---|---|
| Whose log | A quest id counts inside one character's log: the same number on two characters is two quests. `hafen.session():get("alt"):quest():count()` answers for that character. |
| One object | `session:quest()` is the same object every call, minted once per session. A session the client no longer holds answers an empty array. |
| Before the log has built | Just after `SessionEnteredWorld`, `:list()` is empty, `:count()` is `0`, `:selected()` is `nil`. Nothing throws. Nothing is protected. |
| `filter` | The [filter](conventions.md#the-filter-argument) matches a quest's title or resource name. A predicate receives the Quest. |

## A quest

| Method | Returns | Permission | Description |
|---|---|---|---|
| `quest:id()` | `number` | Unprotected | The server's quest id. Always answers. |
| `quest:title()` | `string \| nil` | Unprotected | The quest's name. |
| `quest:res()` | `string \| nil` | Unprotected | Its resource name. |
| `quest:status()` | `string \| nil` | Unprotected | `"pending"`, `"done"`, `"failed"` or `"disabled"`. `nil` once the server has dropped the quest, and for a status this client has no word for. |
| `quest:modified()` | `number \| nil` | Unprotected | The server's change stamp. Higher is more recent. |
| `quest:conditions()` | collection | Unprotected | Its objectives: a [view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) minted per call, with the identity on the objectives in it. |
| `quest:exists()` | `boolean` | Unprotected | Whether it is still in the log. Always answers. |
| `quest:info()` | [`Quest`](types/character.md#quest-and-condition) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Selected | `session:quest():selected() == quest`. The quests are interned, so the comparison is exact and there is no per-member flag. |
| Identity | Interned on session and id: `session:quest():list()[1] == session:quest():get(<that id>)` and `seen[quest] = true` work. The same id on two characters is two objects. |
| A stashed quest tracks | The log rewrites a quest in place as it advances and moves it between tabs. `quest:status()` changes from `"pending"` to `"done"` under the handle you hold. A quest the server drops goes `:exists() == false` and every other read answers `nil`. |

## Objectives

`quest:conditions()` is empty on every quest but the selected one. The client is sent the objectives of the quest the character has open and no other. Opening a different quest fills them in. `session:quest():selected() == quest` tells the two cases apart.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `condition:description()` | `string \| nil` | Unprotected | What the objective asks for. |
| `condition:status()` | `string \| nil` | Unprotected | `"pending"`, `"done"` or `"failed"`. `nil` once its quest is deselected. |
| `condition:tooltip()` | `string \| nil` | Unprotected | The line its tooltip states, when the content publishes one. |
| `condition:quest()` | `Quest` | Unprotected | The quest it belongs to. Never `nil`. |
| `condition:exists()` | `boolean` | Unprotected | Whether it is still an objective of the open quest. |
| `condition:info()` | [`Condition`](types/character.md#quest-and-condition) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Identity | Interned on its quest and its own description, what the client matches on across a resend, so `condition:status()` flips under a stashed handle. |
| Deselecting ends the objectives | `condition:exists()` goes false, and comes back true when that quest is opened again. |

```lua
local session = hafen.session():current()
local quest = session and session:quest():selected()
if quest then
  for _, condition in ipairs(quest:conditions():list()) do
    hafen.log():write(" - [" .. condition:status() .. "] " .. (condition:description() or ""))
  end
end
```

## Events

| Event | Payload | Fires |
|---|---|---|
| [`QuestAdded`](event/bus/character.md#roster-quests-markers) | `Quest` | A new active quest. |
| [`QuestCompleted`](event/bus/character.md#roster-quests-markers) | `Quest` | A quest completed. |
| [`QuestFailed`](event/bus/character.md#roster-quests-markers) | `Quest` | A quest failed. |

The outcome picks the key, so neither handler needs a status check. Each carries the Quest itself, readable with the verbs above and holdable afterwards.

---

## See Also

- [`Quest` and `Condition`](types/character.md#quest-and-condition) — the snapshot shapes.
- [`hafen.map():marker()`](map/markers.md) — the map pins a quest puts down.
- [`session:char`](char.md) — credos, whose own quest carries an id you can look up here.
- [Events](event/bus/character.md#roster-quests-markers) — `QuestAdded`, `QuestCompleted`, `QuestFailed`.
