# session:fight: Combat Schools and the Current Fight

One character's manoeuvre-deck builder (its Martial Arts and Combat Schools tab) and who it is fighting, reached through its [session](session.md). It answers what the character knows, what its loaded school has dealt to each hotkey, what it costs, and the opponent.

```lua
local session = hafen.session():current()                      -- the character on screen
for _, card in ipairs(session:fight():deck():list()) do
  hafen.log():write(card:key() .. ": " .. (card:name() or card:res()))
end
local summary = session:fight():summary()
if summary then
  hafen.log():write("used " .. summary:used() .. "/" .. summary:maxActions() .. " action points")
end
```

---

| Rule | Detail |
|---|---|
| One character's | Every character configures its own deck against its own budget and fights its own fight: `hafen.session():get("alt"):fight():target()` answers about the alt. A hotkey slot and an opponent's gob id count inside one character, so a `DeckCard` and an `Opponent` carry their character beside their key. |
| `target:gob()` answers in the login asked through | That character's own world, where the id came from: `target:gob():exists()` is that character's line of sight, not the screen's. |
| Before the tab has built | `:maneuver():list()` and `:deck():list()` are empty, `:summary()` is `nil`. `:target()` is `nil` whenever the character is not in a fight. |
| Unprotected, no write side, no event | Nothing throws. Read on demand. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():maneuver():list(filter)` | `Maneuver[]` | Unprotected | Every manoeuvre and attack that character knows. |
| `session:fight():maneuver():count(filter)` | `number` | Unprotected | How many match. |
| `session:fight():maneuver():find(filter)` | `Maneuver \| nil` | Unprotected | The first that matches. |
| `session:fight():deck()` | collection | Unprotected | The loaded school's layout, the filled hotkey slots: `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |
| `session:fight():summary()` | `FightSummary \| nil` | Unprotected | The action-point budget and the saved-school slots. |
| `session:fight():target()` | `Opponent \| nil` | Unprotected | Who that character is fighting. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the resource name and the display name. On the deck it matches the manoeuvre in the slot, so one needle finds the same manoeuvre through either door. |
| No `:get` | A manoeuvre is addressed by nothing you hold: a string is a search, a position is `:list()[n]`. `#deck()`, `deck()[n]` and `ipairs(deck())` are [refused](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). `deck():list()` is the array. |
| Empty slots are left out | `card:index()` is the position in that list (`deck():list()[n]:index()` is `n` whatever the gaps). `card:wire()` is the hotkey's place in the school, gaps counted. |

## A manoeuvre

| Method | Returns | Permission | Description |
|---|---|---|---|
| `maneuver:res()` | `string \| nil` | Unprotected | The resource name, its identity. |
| `maneuver:name()` | `string \| nil` | Unprotected | The display name. |
| `maneuver:dealable()` | `number` | Unprotected | How many copies that character may deal into a deck. |
| `maneuver:used()` | `number` | Unprotected | How many the loaded school has dealt. |
| `maneuver:exists()` | `boolean` | Unprotected | Whether that character still knows it. Always answers. |
| `maneuver:info()` | [`Maneuver`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## A deck card

A card is a place in the layout, not the manoeuvre in it. It keeps answering `:wire()` and `:key()` when the hotkey is emptied, while the manoeuvre half goes `nil` and `:exists()` goes `false`.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `card:index()` | `number \| nil` | Unprotected | Its 1-based position in `:deck():list()`. `nil` for an emptied slot, which that list leaves out. |
| `card:wire()` | `number` | Unprotected | The raw 0-based deck index the write path takes. Always answers. |
| `card:key()` | `string \| nil` | Unprotected | The hotkey label the window paints. `nil` for a slot past the labels the window has, since the window paints a fixed set. |
| `card:maneuver()` | `Maneuver \| nil` | Unprotected | The manoeuvre dealt here. |
| `card:res()` | `string \| nil` | Unprotected | That manoeuvre's resource name. |
| `card:name()` | `string \| nil` | Unprotected | That manoeuvre's display name. |
| `card:used()` | `number \| nil` | Unprotected | How many copies the deck holds. |
| `card:exists()` | `boolean` | Unprotected | Whether the slot is filled. Always answers. |
| `card:info()` | [`DeckCard`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## The summary

| Method | Returns | Permission | Description |
|---|---|---|---|
| `summary:used()` | `number \| nil` | Unprotected | Action points the loaded school spends: the total the window paints beside the cap, the sum of `maneuver:used()` over every known manoeuvre. |
| `summary:maxActions()` | `number \| nil` | Unprotected | The action-point budget. |
| `summary:deckSize()` | `number \| nil` | Unprotected | How many hotkey slots the deck has. |
| `summary:saveCount()` | `number \| nil` | Unprotected | How many saved-school slots that character keeps. |
| `summary:activeSave()` | `number \| nil` | Unprotected | Which of them is loaded, 0-based. |
| `summary:exists()` | `boolean` | Unprotected | Whether that character's tab is still up. |
| `summary:info()` | [`FightSummary`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

[`session:study():summary()`](study.md#the-summary) is a summary of the same kind. It is a live object with its own `:exists()` and `:info()`, interned on its window, `nil` while the window is not up.

## The target

| Method | Returns | Permission | Description |
|---|---|---|---|
| `target:id()` | `number` | Unprotected | The creature's gob id. Always answers. |
| `target:gob()` | [Gob](gob.md) | Unprotected | The creature, in the login whose fight this is. Never `nil`. |
| `target:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |
| `target:info()` | `{ id } \| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Who, and nothing else | Name, health and position belong to the gob. The relation, initiative and openings are on the client and this API does not publish them. |
| `target:gob()` is never `nil` | Like [`session:world():gob():get(id)`](gob.md): ask `target:gob():exists()`. |
| Identity | Interned on character and gob id: `session:fight():target() == session:fight():target()` and `seen[target] = true` work. A stashed one goes `:exists() == false` when the fight ends. |

---

## See Also

- [`hafen.session`](session.md) — the address every read here is reached through.
- [Gob](gob.md) — what `target:gob()` hands back, and every read on it.
- [The fight](types/fight.md) — the snapshot shapes `:info()` returns.
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable.
- [`session:char`](char.md) — the skills that unlock manoeuvres.
