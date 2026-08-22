# session:wound: wounds

Read one character's wounds, the Health and Wounds tab. You reach it through the [session](session.md)
whose character you mean, and `s:wound()` **is** that character's wound list. Read-only — healing is an
item or a menu action, not something this namespace does.

```lua
local s = hafen.session():current()                    -- the character on screen
if s and s:wound():find("Infection") then hafen.log():write("infected!") end

for _, w in ipairs(s and s:wound():list() or {}) do
  hafen.log():write(("  "):rep(w:depth()) .. (w:name() or w:res()) .. "  " .. (w:label() or ""))
end
```

Wounds form a **tree**: a complication hangs off the wound that caused it. `:list()` returns them flat
and in tree order, with `w:depth()` as the indent depth, so the loop above prints the shape.

## Whose wounds they are

A wound is on one body, and its id counts within that character's own list. So the read says which
character it is about:

```lua
hafen.session():current():wound():count()      -- the wounds of the character on screen
hafen.session():get("alt"):wound():count()     -- that character's, while you watch someone else
```

`s:wound()` is the same object every call, minted once for that session. A session the client no longer
holds answers an empty array rather than raising.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:wound():list(filter)` | `Wound[]` | every wound, in tree order |
| `s:wound():count(filter)` | number | how many match |
| `s:wound():find(needle)` | `Wound` \| nil | the first whose name or resource contains it |
| `s:wound():get(id)` | `Wound` \| nil | one wound, by its id |

Before the tab has built — a beat after `SessionEnteredWorld` — `:list()` is an empty array and `:find()` is
`nil`. Nothing here throws and nothing is protected.

`:find` is the presence test: it answers `nil` on a miss, so `if s:wound():find("Infection") then`
reads exactly as it looks, and what it hands back on a hit is the wound itself.

## A wound

| Method | Returns | Description |
|---|---|---|
| `w:id()` | number | the wound's id — always answers |
| `w:name()` | string \| nil | its display name |
| `w:res()` | string \| nil | its resource name |
| `w:severity()` | number \| nil | the magnitude beside it, as a number |
| `w:label()` | string \| nil | that magnitude spelled the way the client paints it |
| `w:parent()` | `Wound` \| nil | the wound this one complicates; `nil` at a root |
| `w:depth()` | number \| nil | how deep the tree draws it; `0` at a root |
| `w:exists()` | boolean | whether it is still on the character — always answers |
| `w:info()` | [`Wound`](types.md#wound) \| nil | a plain-table **snapshot** |

> **A wound's magnitude has two reads.** `w:label()` is the string the client paints beside the wound and
> `w:severity()` is that string read as a number. The content chooses the string, so nothing guarantees
> it is one: where it is not, `:label()` answers it and `:severity()` is `nil`. Neither is any
> [unit](shapes.md#units) of the client's, and both arrive a beat after the wound itself.

A wound is interned on its session and its id, so `s:wound():list()[1] == s:wound():get(<that id>)` and
`seen[w] = true` work, while the same id on two characters is two objects. The client
rewrites a wound **in place** as it worsens, so a stashed handle is the right way to watch one; healing
takes it off the list, which is what `:exists()` reads.

`w:parent()` is the tree link resolved for you — the wound above this one, rather than an id you have to
look up. It is `nil` at a root, which is where `:level()` is `0`.

## Events

Subscribe to [`WoundChanged`](event/bus.md#character-and-status) to react to a wound being added, healed or
worsening. Its payload is the new list of Wound objects — the same ones `:list()` hands out, so you can
compare them with `==` against what you kept last time.

## See also

- [`Wound`](types.md#wound) — the snapshot shape `w:info()` returns
- [`session:char`](char.md) — the rest of the character sheet
- [`session:meter`](meter.md) — the HUD bars a wound pulls down
- [events](event/bus.md#character-and-status) — `WoundChanged`
