# session:study: curiosities being studied

Read one character's study window: the curiosities in it, and the learning-point and attention totals
across them. You reach it through the [session](session.md) whose character you mean. The rest of that
character's sheet is next door, in [`session:char`](char.md).

```lua
local s = hafen.session():current()                    -- the character on screen
for _, slot in ipairs(s and s:study():curiosity():list() or {}) do
  hafen.log():write((slot:name() or slot:res()) .. "  lp=" .. (slot:lp() or 0))
end
```

Like the rest of the sheet, the study window builds after login, so a read right at `SessionEnteredWorld`
answers an empty array.

## Whose study it is

Every character studies its own curiosities, so the read says which one it is about:

```lua
hafen.session():current():study():summary()      -- the totals of the character on screen
hafen.session():get("alt"):study():summary()     -- that character's, while you watch someone else
```

`s:study()` and its `:slot()` collection are the same objects every call, minted once for that session.
A session the client no longer holds answers an empty array and a `nil` summary rather than raising.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:study():curiosity():list(filter)` | `StudySlot[]` | the curiosities currently in the window |
| `s:study():curiosity():count(filter)` | number | how many match |
| `s:study():curiosity():find(filter)` | `StudySlot` \| nil | the first that matches |
| `s:study():summary()` | `StudySummary` \| nil | the totals across the curiosities in the window |

A string [filter](conventions.md#the-filter-argument) matches the resource name **and** the display name.
`:summary()` answers `nil` until that character's study tab has built, and takes no arguments: the three
totals are verbs on what it hands back. Nothing here is protected, and nothing else here throws.

**There is no `:get`, and that is the shape rather than an omission.** A study slot has no key: the same
curiosity can sit in two slots at once, and the window has no index the server addresses. So a string is
a *search*, and asking for the address says which verb that is:

```lua
s:study():curiosity():get("bar")
-- session:study():curiosity() has no verb 'get' — a slot has no key, since the same curiosity can
-- sit in two of them: session:study():curiosity():find(needle) is the search and
-- session:study():curiosity():list()[n] takes a position
```

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
| `slot:exists()` | boolean | whether it is still in a study window — always answers |
| `slot:info()` | [`StudySlot`](types.md#studyslot-and-studysummary) \| nil | a plain-table **snapshot** |

> A slot's `time` is the total study time for that curiosity in seconds, not what is left and not a
> [fraction](shapes.md#units). The client is not sent a per-item countdown, so there is none to read.

Just after a curiosity appears it is often resource-only for a beat, because the study profile arrives in
a second server message — so `:lp()`, `:attention()`, `:cost()` and `:time()` answer `nil` together. That
is normal, not an error, and the moment they resolve is itself a `StudyChanged`.

A slot is interned on the item in the window, so `:list()[1] == :list()[1]` and `seen[slot] = true` work,
and **a curiosity taken out of study keeps answering**: `:exists()` is `false` while `:res()` and the
numbers still read what it had. It carries its own character with it, so `:exists()` is about the window
that curiosity was taken from, whichever session that is.

Subscribe to [`StudyChanged`](event/bus.md#character-and-status), whose payload is the array of slots.

```lua
hafen.event():on("StudyChanged", function(slots)
  hafen.log():write(#slots .. " curiosit(ies) in study")
end)
```

## The summary

| Method | Returns | Description |
|---|---|---|
| `sum:lp()` | number \| nil | learning points the curiosities in the window will yield |
| `sum:attention()` | number \| nil | the mental weight they spend |
| `sum:cost()` | number \| nil | what they cost in experience |
| `sum:exists()` | boolean | whether that character's study tab is still up |
| `sum:info()` | [`StudySummary`](types.md#studyslot-and-studysummary) \| nil | a plain-table **snapshot** |

`sum:attention()` is the numerator the window draws; the cap it is drawn against is that character's
Intelligence, [`s:char():attr():get("int"):composite()`](char.md#attributes) — a game fact rather than an
API one, and one namespace away. The three totals are read together, so they always add up against the
same set of curiosities.

The summary is interned on that character's study tab, so `s:study():summary() == s:study():summary()` and
`seen[sum] = true` work, and one you keep goes on reading the live totals as curiosities go in and out. It
is the same kind of thing [`s:fight():summary()`](fight.md#the-summary) is, down to answering `nil` rather
than an empty object while there is no tab.

## See also

- [`session:char`](char.md) — attributes, learning points and skills
- [`StudySlot`](types.md#studyslot-and-studysummary) — the snapshot shape `slot:info()` returns
- [events](event/bus.md#character-and-status) — `StudyChanged`
