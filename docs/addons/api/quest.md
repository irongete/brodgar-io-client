# hafen.quest: the quest log

Read the quest log. `hafen.quest()` **is** the log: one collection over both tabs, the Current one and
the Completed one. Read-only — there is no way to accept, abandon or complete a quest from an addon.

```lua
for _, q in ipairs(hafen.quest():list(function(q) return q:status() == "pending" end)) do
  hafen.log():write(q:title())
end
```

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.quest():list(filter)` | `Quest[]` | every quest, Current tab first |
| `hafen.quest():count(filter)` | number | how many match |
| `hafen.quest():find(filter)` | `Quest` \| nil | the first that matches |
| `hafen.quest():get(id)` | `Quest` \| nil | one quest, by its server id |
| `hafen.quest():selected()` | `Quest` \| nil | the quest open in the log |

Before the log has built — a beat after `EnterWorld` — `:list()` is an empty array, `:count()` is `0`
and `:selected()` is `nil`. `:selected()` is also `nil` whenever the player has nothing open. Nothing
here throws and nothing is protected.

The [filter](conventions.md#the-filter-argument) matches a quest's title or its resource name, and a
predicate receives the Quest object.

## A quest

| Method | Returns | Description |
|---|---|---|
| `q:id()` | number | the server's quest id — always answers |
| `q:title()` | string \| nil | the quest's name |
| `q:res()` | string \| nil | its resource name |
| `q:status()` | string | `"pending"`, `"done"`, `"failed"` or `"disabled"` |
| `q:modified()` | number \| nil | the server's change stamp; higher is more recent |
| `q:selected()` | boolean | whether this is the quest open in the log |
| `q:conditions()` | `Condition[]` | its objectives — see below |
| `q:exists()` | boolean | whether it is still in the log — always answers |
| `q:info()` | [`Quest`](types.md#quest-and-condition) \| nil | a plain-table **snapshot** |

A quest is interned on its id, so `:list()[1] == :get(<that id>)` and `seen[q] = true` work. That is
also what makes a stashed quest worth holding: the log rewrites a quest **in place** as it advances and
moves it between the two tabs, so `q:status()` changes from `"pending"` to `"done"` under the handle you
already have. A quest the server drops goes `:exists() == false` and every other read answers `nil`.

## Objectives

> `q:conditions()` is an **empty array on every quest but the selected one**. The client is sent the
> objectives of the quest the player has open in the log and of no other, so opening a different quest is
> what fills them in. `q:selected()` is how you tell the two cases apart.

| Method | Returns | Description |
|---|---|---|
| `c:description()` | string \| nil | what the objective asks for |
| `c:status()` | string | `"pending"`, `"done"` or `"failed"` |
| `c:text()` | string \| nil | its extra progress string, when the content publishes one |
| `c:quest()` | `Quest` | the quest it belongs to — never `nil` |
| `c:exists()` | boolean | whether it is still an objective of the open quest |
| `c:info()` | [`Condition`](types.md#quest-and-condition) \| nil | a plain-table **snapshot** |

An objective is interned on its quest **and its own description**, which is what the client itself
matches on when it carries an objective across a resend — so `c:status()` flips under a stashed handle,
which is the whole reason to reach past the quest. Deselecting the quest ends the objectives:
`c:exists()` goes false, and comes back true when that quest is opened again.

```lua
local q = hafen.quest():selected()
if q then
  for _, c in ipairs(q:conditions()) do
    hafen.log():write(" - [" .. c:status() .. "] " .. (c:description() or ""))
  end
end
```

## Events

Subscribe to [`QuestAdded`](event.md#roster-quests-markers), a new active quest, and
[`QuestDone`](event.md#roster-quests-markers), an active quest completed or failed. Each carries the
Quest itself, so a handler reads it with the verbs above and can hold it afterwards.

## See also

- [`Quest` and `Condition`](types.md#quest-and-condition) — the snapshot shapes
- [`hafen.map():marker()`](map/markers.md) — the map pins a quest puts down
- [`hafen.char`](char.md) — credos, whose own quest carries an id you can look up here
- [events](event.md#roster-quests-markers) — `QuestAdded` and `QuestDone`
