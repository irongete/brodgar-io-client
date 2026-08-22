# session:quest: the quest log

Read one character's quest log. You reach it through the [session](session.md) whose character you mean,
and `s:quest()` **is** that character's log: one collection over both tabs, the Current one and the
Completed one. Read-only — there is no way to accept, abandon or complete a quest from an addon.

```lua
local s = hafen.session():current()                    -- the character on screen
for _, q in ipairs(s and s:quest():list(function(q) return q:status() == "pending" end) or {}) do
  hafen.log():write(q:title())
end
```

## Whose log it is

A quest id counts inside one character's own log, so the same number on two characters is two different
quests. The read says which character it is about:

```lua
hafen.session():current():quest():count()      -- the quests of the character on screen
hafen.session():get("alt"):quest():count()     -- that character's, while you watch someone else
```

`s:quest()` is the same object every call, minted once for that session. A session the client no longer
holds answers an empty array rather than raising.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:quest():list(filter)` | `Quest[]` | every quest, Current tab first |
| `s:quest():count(filter)` | number | how many match |
| `s:quest():find(filter)` | `Quest` \| nil | the first that matches |
| `s:quest():get(id)` | `Quest` \| nil | one quest, by its server id |
| `s:quest():selected()` | `Quest` \| nil | the quest open in that character's log |

Before the log has built — a beat after `SessionEnteredWorld` — `:list()` is an empty array, `:count()` is `0`
and `:selected()` is `nil`. `:selected()` is also `nil` whenever nothing is open. Nothing
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
| — | — | whether this is the one open in the log is `s:quest():selected() == q`: the quests are interned, so the comparison is exact and there is no per-member flag |
| `q:conditions()` | collection | its objectives — see below |
| `q:exists()` | boolean | whether it is still in the log — always answers |
| `q:info()` | [`Quest`](types.md#quest-and-condition) \| nil | a plain-table **snapshot** |

A quest is interned on its session and its id, so `s:quest():list()[1] == s:quest():get(<that id>)` and
`seen[q] = true` work, while the same id on two characters is two objects. That is
also what makes a stashed quest worth holding: the log rewrites a quest **in place** as it advances and
moves it between the two tabs, so `q:status()` changes from `"pending"` to `"done"` under the handle you
already have. A quest the server drops goes `:exists() == false` and every other read answers `nil`.

## Objectives

> `q:conditions()` is **empty on every quest but the selected one**. The client is sent the
> objectives of the quest that character has open in the log and of no other, so opening a different quest is
> what fills them in. `s:quest():selected() == q` is how you tell the two cases apart.

| Method | Returns | Description |
|---|---|---|
| `c:description()` | string \| nil | what the objective asks for |
| `c:status()` | string | `"pending"`, `"done"` or `"failed"` |
| `c:tooltip()` | string \| nil | the line its tooltip states, when the content publishes one — `c:description()` is the objective itself |
| `c:quest()` | `Quest` | the quest it belongs to — never `nil` |
| `c:exists()` | boolean | whether it is still an objective of the open quest |
| `c:info()` | [`Condition`](types.md#quest-and-condition) \| nil | a plain-table **snapshot** |

An objective is interned on its quest **and its own description**, which is what the client itself
matches on when it carries an objective across a resend — so `c:status()` flips under a stashed handle,
which is the whole reason to reach past the quest. Deselecting the quest ends the objectives:
`c:exists()` goes false, and comes back true when that quest is opened again.

```lua
local s = hafen.session():current()
local q = s and s:quest():selected()
if q then
  for _, c in ipairs(q:conditions():list()) do
    hafen.log():write(" - [" .. c:status() .. "] " .. (c:description() or ""))
  end
end
```

## Events

Subscribe to [`QuestAdded`](event/bus.md#roster-quests-markers), a new active quest, and
[`QuestCompleted`](event/bus.md#roster-quests-markers) and
[`QuestFailed`](event/bus.md#roster-quests-markers) — the outcome picks the key, so neither handler needs a
status check. Each carries the
Quest itself, so a handler reads it with the verbs above and can hold it afterwards.

## See also

- [`Quest` and `Condition`](types.md#quest-and-condition) — the snapshot shapes
- [`hafen.map():marker()`](map/markers.md) — the map pins a quest puts down
- [`session:char`](char.md) — credos, whose own quest carries an id you can look up here
- [events](event/bus.md#roster-quests-markers) — `QuestAdded`, `QuestCompleted` and `QuestFailed`
