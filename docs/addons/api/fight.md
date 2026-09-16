# session:fight: combat schools and the fight you are in

Read one character's maneuver-deck builder — its Martial Arts and Combat Schools tab — and who it is
currently fighting. You reach it through the [session](session.md) whose character you mean. The deck side
is the configuration editor: what that character knows, what its loaded school has dealt to each hotkey,
and what it costs.

```lua
local s = hafen.session():current()                      -- the character on screen
for _, card in ipairs(s:fight():deck():list()) do
  hafen.log():write(card:key() .. ": " .. (card:name() or card:res()))
end

local sum = s:fight():summary()
if sum then
  hafen.log():write("used " .. sum:used() .. "/" .. sum:maxActions() .. " action points")
end
```

## A school is one character's, and so is a fight

Every character configures its own deck against its own budget, and every character fights its own fight.
So the reads below are all about the character you named, and a character you are not looking at answers
about itself:

```lua
local alt = hafen.session():get("alt"):fight()           -- the other login
local target = alt:target()
if target then
  hafen.log():write("the alt is fighting " .. (target:gob():name() or target:id()))
end
```

A hotkey slot and an opponent's gob id both count inside one character alone — slot 3 on two characters is
two different places, and the same id in two fights is two different creatures — so a `DeckCard` and an
`Opponent` each carry their character beside their key. `target:gob()` **answers in the login you asked
through** — that character's own world, which is the one the id came out of — so `target:gob():exists()` is
that character's line of sight and not the screen's.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:fight():maneuver():list(filter)` | `Maneuver[]` | every maneuver and attack that character knows |
| `s:fight():maneuver():count(filter)` | number | how many match |
| `s:fight():maneuver():find(filter)` | `Maneuver` \| nil | the first that matches |
| `s:fight():deck()` | collection | the loaded school's layout: the filled hotkey slots — `:list(filter)`, `:count(filter)` and `:find(filter)`, and no `:get` |
| `s:fight():summary()` | `FightSummary` \| nil | the action-point budget and the saved-school slots |
| `s:fight():target()` | `Opponent` \| nil | who that character is fighting |

`:maneuver():list()` and `:deck():list()` answer empty and `:summary()` answers `nil` until that character's tab
has built; `:target()` is `nil` whenever it is not in a fight. Nothing throws and nothing is protected;
there is no write side, and no combat event — read on demand.

A string [filter](conventions.md#the-filter-argument) over the maneuvers matches the resource name **and**
the display name. **There is no `:get`**: a maneuver is addressed by nothing you have, so a string is a
*search* and a position is `:list()[n]`.

The deck is a **collection** like the maneuvers, and addressed the same way: no `:get`, a `:find` over the
resource name **and** the display name of the maneuver in the slot — the same needle the maneuvers take, so
one string finds the same maneuver through either door — and `deck():list()` as the array — so `#deck()`, `deck()[n]` and `ipairs(deck())` are
[refused](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) here too. Empty slots are
left out, and the two numbers say so between them: `card:index()` is the position **in that list**, so
`deck():list()[n]:index()` is `n` whatever the gaps, and `card:wire()` is the hotkey's own place in the
school, gaps counted.

## A maneuver

| Method | Returns | Description |
|---|---|---|
| `man:res()` | string \| nil | the maneuver's resource name, its identity |
| `man:name()` | string \| nil | the display name |
| `man:dealable()` | number | how many copies of it that character may deal into a deck |
| `man:used()` | number | how many the loaded school has dealt |
| `man:exists()` | boolean | whether that character still knows it — always answers |
| `man:info()` | [`Maneuver`](types/fight.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

## A deck card

A card is a **place** in the layout, not the maneuver in it. It keeps answering `:wire()` and `:key()`
when the hotkey is emptied, while the maneuver half goes `nil` and `:exists()` goes `false`. The window
paints a fixed set of hotkey labels, so a deck longer than that set has slots with no label at all and
`card:key()` says so with `nil` rather than inventing a number nobody can press.

| Method | Returns | Description |
|---|---|---|
| `card:index()` | number \| nil | its **1-based** position in `:deck():list()`; `nil` for an emptied slot, which that list leaves out |
| `card:wire()` | number | the raw 0-based deck index the write path takes — always answers |
| `card:key()` | string \| nil | the hotkey label the window paints; `nil` for a slot past the labels the window has |
| `card:maneuver()` | `Maneuver` \| nil | the maneuver dealt here |
| `card:res()` | string \| nil | that maneuver's resource name |
| `card:name()` | string \| nil | that maneuver's display name |
| `card:used()` | number \| nil | how many copies the deck holds |
| `card:exists()` | boolean | whether the slot is filled — always answers |
| `card:info()` | [`DeckCard`](types/fight.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

## The summary

| Method | Returns | Description |
|---|---|---|
| `sum:used()` | number \| nil | action points the loaded school spends |
| `sum:maxActions()` | number \| nil | the action-point budget it spends them against |
| `sum:deckSize()` | number \| nil | how many hotkey slots the deck has |
| `sum:saveCount()` | number \| nil | how many saved-school slots that character keeps |
| `sum:activeSave()` | number \| nil | which of them is loaded, 0-based |
| `sum:exists()` | boolean | whether that character's tab is still up |
| `sum:info()` | [`FightSummary`](types/fight.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

`sum:used()` is the same total the window paints beside the cap, and it is the sum of `man:used()` over
every maneuver that character knows.

[`s:study():summary()`](study.md#the-summary) is a summary of the same kind — a live object with its own
`:exists()` and `:info()`, interned on its window, `nil` while that window is not up.

## The target

| Method | Returns | Description |
|---|---|---|
| `target:id()` | number | the creature's gob id — always answers |
| `target:gob()` | [Gob](gob.md) | the creature itself, **in the login whose fight this is** — never `nil` |
| `target:exists()` | boolean | whether that character is still fighting them — always answers |
| `target:info()` | `{ id }` \| nil | a plain-table **snapshot** |

> The target says **who**, and nothing else. Everything readable about the creature — its name, its
> health, where it is — belongs to the gob and is read there. There is no per-moment combat state here:
> the relation, the initiative and the openings are on the client, and this API does not publish them.

`target:gob()` is never `nil`, exactly like [`s:world():gob():get(id)`](gob.md) — ask
`target:gob():exists()` rather than testing for `nil`. The target is interned on that character and the gob
id, so `s:fight():target() == s:fight():target()` and `seen[target] = true` work, and a stashed one goes
`:exists() == false` when the fight ends.

## See also

- [`hafen.session`](session.md) — the address every read here is reached through
- [Gob](gob.md) — what `target:gob()` hands back, and every read on it
- [the fight](types/fight.md) — the snapshot shapes `:info()` returns
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable
- [`session:char`](char.md) — the skills that unlock maneuvers
