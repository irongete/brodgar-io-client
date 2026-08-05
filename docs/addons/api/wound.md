# hafen.wound: wounds

Read the character's wounds, the Health and Wounds tab. `hafen.wound()` **is** the wound list. Read-only
— healing is an item or a menu action, not something this namespace does.

```lua
if hafen.wound():find("Infection") then hafen.log():write("infected!") end

for _, w in ipairs(hafen.wound():list()) do
  hafen.log():write(("  "):rep(w:level()) .. (w:name() or w:res()) .. "  " .. (w:severity() or ""))
end
```

Wounds form a **tree**: a complication hangs off the wound that caused it. `:list()` returns them flat
and in tree order, with `w:level()` as the indent depth, so the loop above prints the shape.

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.wound():list(filter)` | `Wound[]` | every wound, in tree order |
| `hafen.wound():count(filter)` | number | how many match |
| `hafen.wound():find(needle)` | `Wound` \| nil | the first whose name or resource contains it |
| `hafen.wound():get(id)` | `Wound` \| nil | one wound, by its id |

Before the tab has built — a beat after `OnEnterWorld` — `:list()` is an empty array and `:find()` is
`nil`. Nothing here throws and nothing is gated.

`:find` is the presence test: it answers `nil` on a miss, so `if hafen.wound():find("Infection") then`
reads exactly as it looks, and what it hands back on a hit is the wound itself.

## A wound

| Method | Returns | Description |
|---|---|---|
| `w:id()` | number | the wound's id — always answers |
| `w:name()` | string \| nil | its display name |
| `w:res()` | string \| nil | its resource name |
| `w:severity()` | string \| nil | the magnitude the client shows beside it |
| `w:parent()` | `Wound` \| nil | the wound this one complicates; `nil` at a root |
| `w:level()` | number \| nil | how deep the tree draws it; `0` at a root |
| `w:exists()` | boolean | whether it is still on the character — always answers |
| `w:info()` | [`Wound`](types.md#wound) \| nil | a plain-table **snapshot** |

> A wound's `severity` is the magnitude string the client paints beside it — usually a number, but
> content-defined, and **not** seconds. It arrives a beat after the wound itself, so it is `nil` for that
> beat.

A wound is interned on its id, so `:list()[1] == :get(<that id>)` and `seen[w] = true` work. The client
rewrites a wound **in place** as it worsens, so a stashed handle is the right way to watch one; healing
takes it off the list, which is what `:exists()` reads.

`w:parent()` is the tree link resolved for you — the wound above this one, rather than an id you have to
look up. It is `nil` at a root, which is where `:level()` is `0`.

## Events

Subscribe to [`WoundChanged`](event.md#character-and-status) to react to a wound being added, healed or
worsening. Its payload is the new list of Wound objects — the same ones `:list()` hands out, so you can
compare them with `==` against what you kept last time.

## See also

- [`Wound`](types.md#wound) — the snapshot shape `w:info()` returns
- [`hafen.char`](char.md) — the rest of the character sheet
- [`hafen.meter`](meter.md) — the HUD bars a wound pulls down
- [events](event.md#character-and-status) — `WoundChanged`
