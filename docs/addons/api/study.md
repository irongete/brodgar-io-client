# hafen.study: curiosities being studied

Read the study window: the curiosities in it, and the learning-point and attention totals across them.
The rest of the character sheet is next door, in [`hafen.char`](char.md).

```lua
for _, slot in ipairs(hafen.study():slot():list()) do
  hafen.log():write((slot:name() or slot:res()) .. "  lp=" .. (slot:lp() or 0))
end
```

Like the rest of the sheet, the study window builds after login, so a read right at `SessionEnteredWorld`
answers an empty array.

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.study():slot():list(filter)` | `StudySlot[]` | the curiosities currently in the window |
| `hafen.study():slot():count(filter)` | number | how many match |
| `hafen.study():slot():find(filter)` | `StudySlot` \| nil | the first that matches |
| `hafen.study():summary()` | `{lp, attention, cost}` \| nil | live totals across the slots |

A string [filter](conventions.md#the-filter-argument) matches the resource name **and** the display name.
`:summary()` answers `nil` until the window has built. Neither throws and neither is protected.

**There is no `:get`, and that is the shape rather than an omission.** A study slot has no key: the same
curiosity can sit in two slots at once, and the window has no index the server addresses. So a string is
a *search*, and a position is `hafen.study():slot():list()[n]`.

## A slot

| Method | Returns | Description |
|---|---|---|
| `slot:res()` | string \| nil | the curiosity's resource name, its identity |
| `slot:name()` | string \| nil | the display name |
| `slot:lp()` | number \| nil | learning points |
| `slot:attention()` | number \| nil | mental weight |
| `slot:cost()` | number \| nil | experience cost |
| `slot:time()` | number \| nil | study time in seconds |
| `slot:progress()` | number \| nil | `0..1` study progress; best-effort |
| `slot:exists()` | boolean | whether it is still in the window — always answers |
| `slot:info()` | [`StudySlot`](types.md#studyslot) \| nil | a plain-table **snapshot** |

> A slot's `time` is the **total** study time for that curiosity, not what is left. The client is not
> sent a per-item countdown, so there is none to read.

Just after a curiosity appears it is often resource-only for a beat, because the study profile arrives in
a second server message — so `:lp()`, `:attention()`, `:cost()` and `:time()` answer `nil` together. That
is normal, not an error, and the moment they resolve is itself a `StudyChanged`.

A slot is interned on the item in the window, so `:list()[1] == :list()[1]` and `seen[slot] = true` work,
and **a curiosity taken out of study keeps answering**: `:exists()` is `false` while `:res()` and the
numbers still read what it had.

Subscribe to [`StudyChanged`](event/bus.md#character-and-status), whose payload is the array of slots.

```lua
hafen.event():on("StudyChanged", function(slots)
  hafen.log():write(#slots .. " curiosit(ies) in study")
end)
```

## See also

- [`hafen.char`](char.md) — attributes, learning points and skills
- [`StudySlot`](types.md#studyslot) — the snapshot shape `slot:info()` returns
- [events](event/bus.md#character-and-status) — `StudyChanged`
