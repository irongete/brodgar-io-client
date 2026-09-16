# session:study: Curiosities Being Studied

One character's study window, read through its [session](session.md): the curiosities in it and the learning-point and attention totals across them. The rest of the sheet is [`session:char`](char.md).

```lua
local session = hafen.session():current()                    -- the character on screen
for _, slot in ipairs(session and session:study():curiosity():list() or {}) do
  hafen.log():write((slot:name() or slot:res()) .. "  lp=" .. (slot:lp() or 0))
end
```

---

| Rule | Detail |
|---|---|
| Builds after login | A read at `SessionEnteredWorld` answers an empty array. |
| Whose study | `hafen.session():get("alt"):study():summary()` answers for that character while you watch another. |
| One object | `session:study()` and its `:curiosity()` collection are the same objects every call, minted once per session. A session the client no longer holds answers an empty array and a `nil` summary. |
| Unprotected | Nothing here is protected; nothing throws. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:study():curiosity():list(filter)` | `StudySlot[]` | Unprotected | The curiosities in the window. |
| `session:study():curiosity():count(filter)` | `number` | Unprotected | How many match. |
| `session:study():curiosity():find(filter)` | `StudySlot \| nil` | Unprotected | The first that matches. |
| `session:study():summary()` | `StudySummary \| nil` | Unprotected | The totals across the curiosities in the window; `nil` until the tab has built. Takes no arguments. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the resource name and the display name. |
| No `:get` | A slot has no key: the same curiosity can sit in two slots, and the window has no index the server addresses. `session:study():curiosity():get("bar")` raises: `session:study():curiosity() has no verb 'get' — a slot has no key, since the same curiosity can sit in two of them: session:study():curiosity():find(needle) is the search and session:study():curiosity():list()[n] takes a position`. |

## A slot

| Method | Returns | Permission | Description |
|---|---|---|---|
| `slot:res()` | `string \| nil` | Unprotected | The curiosity's resource name, its identity. |
| `slot:name()` | `string \| nil` | Unprotected | The display name. |
| `slot:lp()` | `number \| nil` | Unprotected | Learning points. |
| `slot:attention()` | `number \| nil` | Unprotected | Mental weight. |
| `slot:cost()` | `number \| nil` | Unprotected | Experience cost. |
| `slot:time()` | `number \| nil` | Unprotected | Total study time in seconds: not what is left, not a [fraction](shapes.md#units). The client is sent no per-item countdown. |
| `slot:progress()` | `number \| nil` | Unprotected | `0..1` study progress; best-effort. |
| `slot:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that draws it: the crossing back into the tree. |
| `slot:exists()` | `boolean` | Unprotected | Whether it is still in a study window; always answers. |
| `slot:info()` | [`StudySlot`](types/character.md#studyslot-and-studysummary) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Resource-only for a beat | The study profile arrives in a second server message, so `:lp()`, `:attention()`, `:cost()` and `:time()` answer `nil` together just after a curiosity appears. The moment they resolve is itself a `StudyChanged`. |
| Identity | Interned on the item in the window: `:list()[1] == :list()[1]` and `seen[slot] = true` work. |
| A curiosity taken out keeps answering | `:exists()` is `false` while `:res()` and the numbers read what it had. The slot carries its own character, so `:exists()` is about the window it was taken from. |
| Event | [`StudyChanged`](event/bus/character.md#character-and-status), whose payload is the array of slots. |

```lua
hafen.event():on("StudyChanged", function(slots)
  hafen.log():write(#slots .. " curiosit(ies) in study")
end)
```

## The summary

| Method | Returns | Permission | Description |
|---|---|---|---|
| `summary:lp()` | `number \| nil` | Unprotected | Learning points the curiosities in the window will yield. |
| `summary:attention()` | `number \| nil` | Unprotected | The mental weight they spend. |
| `summary:cost()` | `number \| nil` | Unprotected | What they cost in experience. |
| `summary:exists()` | `boolean` | Unprotected | Whether that character's study tab is still up. |
| `summary:info()` | [`StudySummary`](types/character.md#studyslot-and-studysummary) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| The attention cap | `summary:attention()` is the numerator the window draws; the cap is that character's Intelligence, [`session:char():attr():get("int"):composite()`](char.md#attributes). |
| Read together | The totals are read together, so they add up against the same set of curiosities. |
| Identity | Interned on the study tab: `session:study():summary() == session:study():summary()` and `seen[summary] = true` work; a kept one reads the live totals. The same kind of object as [`session:fight():summary()`](fight.md#the-summary), `nil` rather than empty while there is no tab. |

---

## See Also

- [`session:char`](char.md) — attributes, learning points and skills.
- [`StudySlot`](types/character.md#studyslot-and-studysummary) — the snapshot shape `slot:info()` returns.
- [Events](event/bus/character.md#character-and-status) — `StudyChanged`.
