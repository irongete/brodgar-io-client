# session:wound: Wounds

One character's wounds, the Health and Wounds tab, read through its [session](session.md). `session:wound()` is that character's wound list. Read-only: healing is an item or a menu action.

```lua
local session = hafen.session():current()                    -- the character on screen
if session and session:wound():find("Infection") then hafen.log():write("infected!") end

for _, wound in ipairs(session and session:wound():list() or {}) do
  hafen.log():write(("  "):rep(wound:depth()) .. (wound:name() or wound:res()) .. "  " .. (wound:label() or ""))
end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:wound():list(filter)` | `Wound[]` | Unprotected | Every wound, flat, in tree order. |
| `session:wound():count(filter)` | `number` | Unprotected | How many match. |
| `session:wound():find(needle)` | `Wound \| nil` | Unprotected | The first whose name or resource contains it. |
| `session:wound():get(id)` | `Wound \| nil` | Unprotected | One wound, by its id. |
| `session:wound():roots()` | collection | Unprotected | The wounds nothing complicates: [the top of the tree](#a-wound). |

| Rule | Detail |
|---|---|
| A tree | A complication hangs off the wound that caused it. `:list()` is flat in tree order with `wound:depth()` as the indent, so the loop above prints the shape. |
| Whose wounds | A wound is on one body and its id counts within that character's list: `hafen.session():get("alt"):wound():count()` answers for that character. |
| One object | `session:wound()` is the same object every call, minted once per session. A session the client no longer holds answers an empty array. |
| Before the tab has built | Just after `SessionEnteredWorld`, `:list()` is empty and `:find()` is `nil`. Nothing throws. Nothing is protected. |
| `:find` as presence test | `nil` on a miss, the wound itself on a hit: `if session:wound():find("Infection") then`. |

## A wound

| Method | Returns | Permission | Description |
|---|---|---|---|
| `wound:id()` | `number` | Unprotected | The wound's id. Always answers. |
| `wound:name()` | `string \| nil` | Unprotected | Its display name. |
| `wound:res()` | `string \| nil` | Unprotected | Its resource name. |
| `wound:severity()` | `number \| nil` | Unprotected | The magnitude beside it, as a number. |
| `wound:label()` | `string \| nil` | Unprotected | That magnitude spelled as the client paints it. |
| `wound:parent()` | `Wound \| nil` | Unprotected | The wound this one complicates. `nil` at a root. |
| `wound:children()` | collection | Unprotected | The wounds that complicate this one. Empty at a leaf. A [view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many), like `:roots()`. |
| `wound:depth()` | `number \| nil` | Unprotected | How deep the tree draws it. `0` at a root. |
| `wound:exists()` | `boolean` | Unprotected | Whether it is still on the character. Always answers. |
| `wound:info()` | [`Wound`](types/character.md#wound) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Two reads of the magnitude | `wound:label()` is the string the client paints. `wound:severity()` is that string read as a number. The content chooses the string, so where it is not a number `:label()` answers and `:severity()` is `nil`. Neither is a client [unit](shapes.md#units). Both arrive a beat after the wound. |
| Identity | Interned on session and id: `session:wound():list()[1] == session:wound():get(<that id>)` and `seen[wound] = true` work. The same id on two characters is two objects. |
| Watching one | The client rewrites a wound in place as it worsens, so a stashed handle tracks it. Healing takes it off the list, which `:exists()` reads. |
| The tree walks both ways | `session:wound():roots()` is the top of the list as the window draws it. `wound:children()` is what hangs under one, so "this wound and everything under it" is a recursion, not a scan per wound. A complication whose parent healed out from under it is a root, as the window draws it. A wound is still addressed by id on the whole list wherever it hangs. |
| `wound:parent()` | The wound above this one, resolved. `nil` at a root, where `:depth()` is `0`. |

## Events

[`WoundChanged`](event/bus/character.md#character-and-status) fires when a wound is added, healed or worsens. Its payload is the new list of Wound objects, the same ones `:list()` hands out, so `==` against what you kept last time works.

---

## See Also

- [`Wound`](types/character.md#wound) — the snapshot shape `wound:info()` returns.
- [`session:char`](char.md) — the rest of the character sheet.
- [`session:meter`](meter.md) — the HUD bars a wound pulls down.
- [Events](event/bus/character.md#character-and-status) — `WoundChanged`.
