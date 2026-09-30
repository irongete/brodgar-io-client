# session:fight: Combat Schools and the Fight in Progress

One character's manoeuvre-deck builder (its Martial Arts and Combat Schools tab) and the fight it is in, reached through its [session](session.md). It answers what the character knows, what its loaded school has dealt to each hotkey and what that costs, and, while it fights, every opponent, the numbers the fight paints between you and the buffs drawn beside each side.

```lua
local session = hafen.session():current()                      -- the character on screen
local target = session:fight():opponent():current()            -- nil out of a fight
if target then
  local ip = target:ip()                                       -- { mine = …, theirs = … }
  hafen.log():write("IP " .. ip.mine .. " against " .. ip.theirs .. " on " .. target:id())
end
hafen.event():on("OpponentAdded", function(opponent, fight_session)
  hafen.log():write(fight_session:character() .. " is fighting " .. opponent:id())
end)
```

---

| Rule | Detail |
|---|---|
| One character's | Every character configures its own deck against its own budget and fights its own fight: `hafen.session():get("alt"):fight():opponent():current()` answers about the alt. A hotkey slot and an opponent's gob id count inside one character, so a `DeckCard` and an `Opponent` carry their character beside their key. |
| `opponent:gob()` answers in the login asked through | That character's own world, where the id came from: `opponent:gob():exists()` is that character's line of sight, not the screen's. |
| Before the tab has built, and out of a fight | `:maneuver():list()` and `:deck():list()` are empty and `:summary()` is `nil` until the tab has built. Out of a fight, `:opponent()` and `:opening()` are empty and `:opponent():current()` is `nil`. |
| Unprotected, no write side | Nothing throws. The opponents fire [the fight events](event/bus/fight.md), a fight's buffs the [opening events](event/bus/character.md#character-and-status), and the schools are read on demand. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():maneuver():list(filter)` | `Maneuver[]` | Unprotected | Every manoeuvre and attack that character knows. |
| `session:fight():maneuver():count(filter)` | `number` | Unprotected | How many match. |
| `session:fight():maneuver():find(filter)` | `Maneuver \| nil` | Unprotected | The first that matches. |
| `session:fight():deck()` | collection | Unprotected | The loaded school's layout, the filled hotkey slots: `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |
| `session:fight():summary()` | `FightSummary \| nil` | Unprotected | The action-point budget and the saved-school slots. |

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

## The fight in progress

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():opponent()` | collection | Unprotected | Every opponent that character is fighting, in the order the fight keeps: `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(gobId)`. |
| `session:fight():opponent():current()` | `Opponent \| nil` | Unprotected | The target: the opponent the fight has picked. `nil` out of a fight. |
| `session:fight():opening()` | collection | Unprotected | Your buffs in the fight: the row of icons the client paints over the map, to the left of that character. Not the buff bar. [Buff](buff.md) objects, `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |

| Rule | Detail |
|---|---|
| One object | `session:fight():opponent()` and `session:fight():opening()` are the same object every call. Both are empty out of a fight. |
| `:get(gobId)` | Takes a gob id, the one thing the server publishes about an opponent. A miss is `nil`. A string [filter](conventions.md#the-filter-argument) is refused naming the function form: an opponent has no name of its own, and the creature's is `opponent:gob():name()`. |
| The order | The fight's own: a new opponent joins at the front and the target is moved to the front, so `:list()[1]` is usually the target but not always. The client's "Switch targets" key reorders the list without a message, and no event says so. |
| `:current()` | The distinguished member, compared with `==`: `session:fight():opponent():current() == opponent`. `:current(x)` raises: it reads. |
| Only while the client draws them | The icon rows exist while the fight lasts. A debuff of yours that outlasts the fight is no longer drawn once it is over: it is not listed, `buff:exists()` is `false`, and `OpeningRemoved` fires for it when the fight ends. If it is still there when the next fight starts, it is drawn again and fires `OpeningAdded` again. |
| The same objects as the events | A buff of a fight fires [`OpeningAdded`, `OpeningChanged` and `OpeningRemoved`](event/bus/character.md#character-and-status), and the doors here hand the same interned `Buff`. When the fight with an opponent ends, each of its buffs fires `OpeningRemoved` once. |
| Whose it is | Each opening event hands the buff, then the opponent it is drawn beside, then the session: `nil` where the buff is yours. `OpeningRemoved` still names the opponent after the fight with them has ended, when [`buff:opponent()`](buff.md) answers `nil`. |
| Not on the bar | [`session:buff()`](buff.md) is the buff bar alone, and `BuffAdded`, `BuffChanged` and `BuffRemoved` are its events. A buff of a fight never fires them. |

```lua
hafen.event():on("OpeningAdded", function(buff, opponent, session)
  local whose = opponent and ("on opponent " .. opponent:id()) or "on you"
  hafen.log():write((buff:name() or buff:res()) .. " " .. whose)
end)
```

## An opponent

| Method | Returns | Permission | Description |
|---|---|---|---|
| `opponent:id()` | `number` | Unprotected | The creature's gob id. Always answers. |
| `opponent:gob()` | [Gob](gob.md) | Unprotected | The creature, in the login whose fight this is. Never `nil`. |
| `opponent:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |
| `opponent:ip()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The initiative the fight paints between you, as whole numbers: `mine` beside your character, `theirs` beside them. |
| `opponent:give()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The give button beside their portrait, as its two halves: `mine` the left, `theirs` the right. Booleans. |
| `opponent:opening()` | collection | Unprotected | Their openings: the row of icons the client paints over the map, to the right of your character while they are the target. [Buff](buff.md) objects, a view minted per call. |
| `opponent:info()` | [`Opponent`](types/fight.md#opponent) | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| The creature is the gob | Name, health and position belong to the gob. What the fight holds about the relation is read here. |
| Once the fight with them ends | `:ip()` and `:give()` read `nil`, `:opening()` is empty, `:exists()` is `false` and `:info()` is `{ id }`. `:id()` and `:gob()` go on answering. |
| `opponent:gob()` is never `nil` | Like [`session:world():gob():get(id)`](gob.md): ask `opponent:gob():exists()`. |
| Identity | Interned on character and gob id: `session:fight():opponent():get(id) == session:fight():opponent():get(id)` and `seen[opponent] = true` work. A later fight with the same creature hands back the same object. |
| Events | [`OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`](event/bus/fight.md) hand this object. |

---

## See Also

- [`hafen.session`](session.md) — the address every read here is reached through.
- [Gob](gob.md) — what `opponent:gob()` hands back, and every read on it.
- [The fight](types/fight.md) — the snapshot shapes `:info()` returns.
- [The fight events](event/bus/fight.md) — an opponent coming and going, its numbers moving, the target changing.
- [`session:buff`](buff.md) — the buff bar, and the `Buff` a fight's buffs are too.
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable.
- [`session:char`](char.md) — the skills that unlock manoeuvres.
