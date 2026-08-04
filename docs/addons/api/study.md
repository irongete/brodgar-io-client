# hafen.study: curiosities being studied

Read the study window: the curiosities in it, and the learning-point and attention totals across them.
The rest of the character sheet is next door, in [`hafen.char`](char.md).

```lua
for _, s in ipairs(hafen.study.slots()) do
  hafen.log():write((s.name or s.res) .. "  lp=" .. (s.lp or 0))
end
```

Like the rest of the sheet, the study window builds after login, so a read right at `OnEnterWorld`
answers an empty array.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.study.slots()` | [`StudySlot`](types.md#studyslot)`[]` | the curiosities currently in the study window |
| `hafen.study.summary()` | `{lp, attention, cost}` \| nil | live totals across the slots |

`slots()` answers an empty array when the window has not built or holds nothing; `summary()` answers
`nil` until it has. Neither throws and neither is gated.

> A slot's `time` is the **total** study time for that curiosity, not what is left. The client is not
> sent a per-item countdown, so there is none to read.

Subscribe to [`StudyChanged`](event.md#character-and-status) for updates.

## See also

- [`hafen.char`](char.md) — attributes, learning points and skills
- [`StudySlot`](types.md#studyslot) — the snapshot shape `slots()` returns
- [events](event.md#character-and-status) — `StudyChanged`
