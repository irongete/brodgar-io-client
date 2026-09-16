# session:char: The Character Sheet

One character's sheet, read through its [session](session.md): attributes, learning points, carried weight, food and hunger, skills, credos and lore. The curiosities it studies are [`session:study`](study.md).

```lua
local session = hafen.session():current()                  -- the character on screen
local strength = session and session:char():attr():get("str")
if strength then
  hafen.log():write("strength " .. (strength:base() or 0) .. " (" .. (strength:composite() or 0) .. " buffed)")
end
if session and session:char():skill():find("Alchemy") then hafen.log():write("I know Alchemy") end
```

---

| Rule | Detail |
|---|---|
| Streams in after login | The sheet lives in HUD widgets that build a beat after `SessionEnteredWorld`: an immediate read answers `nil` or an empty array. Read on a short timer. |
| Events | `FepChanged` for food and hunger, `StudyChanged` for the curiosities, [both on the bus](event/bus/character.md#character-and-status). Attributes, learning points, weight, skills, credos and lore change only on an action of yours: read them on demand, after it. |
| Whose sheet | `session:char()` is the sheet of the character that session plays: `hafen.session():get("alt"):char():lp()` answers for that character whether or not you look at it. |
| One object | `session:char()` and each collection below are the same object every call, minted once per session, so a panel reading every frame allocates nothing. `session:char():skill():buyable(filter)` is a partition, [a view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many): two calls are two objects, and the skills inside them are the identity. |
| A session the client no longer holds | Answers `nil`-shaped, the shape before entering the world; [`session:exists()`](session.md#read) tells the two apart. |
| Unprotected, never throws | The sheet is a display of server state; every change to it is an action taken elsewhere. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:char():attr()` | collection | Unprotected | The base attributes. |
| `session:char():skill()` | collection | Unprotected | The skills the character knows. |
| `session:char():credo()` | collection | Unprotected | The Credos tab. |
| `session:char():experience()` | collection | Unprotected | The Lore tab. |
| `session:char():food()` | [`Food`](#food) `\| nil` | Unprotected | FEP and hunger, once the sheet is up. |
| `session:char():lp()` | `number \| nil` | Unprotected | Current learning points. |
| `session:char():weight()` | `number \| nil` | Unprotected | Carried weight, the encumbrance figure. |

## Attributes

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:char():attr():get(name)` | `Attr` | Unprotected | One attribute; never `nil` for a real name. |
| `session:char():attr():list(filter)` | `Attr[]` | Unprotected | Every attribute the server has published. |
| `session:char():attr():count(filter)` | `number` | Unprotected | How many match. |
| `session:char():attr():find(filter)` | `Attr \| nil` | Unprotected | The first that matches. |
| `attr:name()` | `string` | Unprotected | Which attribute this is. |
| `attr:base()` | `number \| nil` | Unprotected | The raw base value. |
| `attr:composite()` | `number \| nil` | Unprotected | The computed value: base plus food, gear and buffs. |
| `attr:info()` | [`Attr`](types/character.md#attr) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| Names | `str`, `agi`, `int`, `con`, `prc`, `csm`, `dex`, `wil`, `psy`. The set is closed: a name outside it raises listing them; a misspelt attribute is a typo, never something whose every read is `nil`. |
| `:get(name)` always answers | `:base()` and `:composite()` are `nil` until the server has sent it; `:list()` holds only the published ones. |
| Identity | Interned on session and name: `:get("str") == :get("str")` on one session, and `seen[attr] = true` works; the same name on two characters is two objects. |

## Skills

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:char():skill():list(filter)` | `Skill[]` | Unprotected | The skills the character knows. |
| `session:char():skill():buyable(filter)` | collection | Unprotected | The skills that can be bought, each with a `:cost()`. A partition minted per call: `:count()`, `:find()` and `:list()` answer on it; `:buyable() == :buyable()` is false. |
| `session:char():skill():count(filter)` | `number` | Unprotected | How many known ones match. |
| `session:char():skill():find(filter)` | `Skill \| nil` | Unprotected | The first known skill that matches. |
| `skill:name()` | `string` | Unprotected | The display name. |
| `skill:res()` | `string \| nil` | Unprotected | The icon resource name, once resolved. |
| `skill:cost()` | `number \| nil` | Unprotected | The learning-point price. |
| `skill:known()` | `boolean` | Unprotected | Learnt, rather than buyable. |
| `skill:exists()` | `boolean` | Unprotected | Whether it is still listed. |
| `skill:info()` | [`Skill`](types/character.md#skill-credo-experience) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the display name and the resource name. `:find(name)` hands back the skill, which is truthy: `if session:char():skill():find("x") then` is a membership test that also gives you the thing. |
| One type for both groups | Buying moves a skill from `:buyable()` to `:list()` without making it a different skill: a stashed handle goes on reading it and `:known()` flips. |

## Credos

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:char():credo():list(filter)` | `Credo[]` | Unprotected | Every credo the tab lists, acquired and available. |
| `session:char():credo():pursuing()` | `Credo \| nil` | Unprotected | The one being pursued. |
| `session:char():credo():count(filter)` | `number` | Unprotected | How many match. |
| `session:char():credo():find(filter)` | `Credo \| nil` | Unprotected | The first that matches. |
| `credo:name()` | `string` | Unprotected | The display name. |
| `credo:res()` | `string \| nil` | Unprotected | The icon resource name. |
| `credo:acquired()` | `boolean` | Unprotected | Whether the character has completed it. |
| `credo:rank()`, `credo:levelTotal()` | `number \| nil` | Unprotected | Pursuit progress; only on the pursued credo. |
| `credo:questsDone()`, `credo:questTotal()` | `number \| nil` | Unprotected | Quest progress within the current level, both counts. |
| `credo:questId()` | `number \| nil` | Unprotected | The id of the credo quest, for [`session:quest`](quest.md). |
| `credo:exists()` | `boolean` | Unprotected | Whether it is still listed. |
| `credo:info()` | [`Credo`](types/character.md#skill-credo-experience) `\| nil` | Unprotected | A plain-table snapshot. |

The pursued credo is a member of the collection like any other and compares equal to the same credo found in `:list()`. The progress reads answer `nil` on every credo but that one.

## Lore

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:char():experience():list(filter)` | `Experience[]` | Unprotected | The lore the character has seen. |
| `session:char():experience():count(filter)` | `number` | Unprotected | How many match. |
| `session:char():experience():find(filter)` | `Experience \| nil` | Unprotected | The first that matches. |
| `experience:name()` | `string \| nil` | Unprotected | The display name. |
| `experience:res()` | `string` | Unprotected | The lore resource name, its identity. |
| `experience:score()` | `number \| nil` | Unprotected | The experience points it is worth. |
| `experience:modified()` | `number \| nil` | Unprotected | The server's own time field for this entry. |
| `experience:exists()` | `boolean` | Unprotected | Whether it is still listed. |
| `experience:info()` | [`Experience`](types/character.md#skill-credo-experience) `\| nil` | Unprotected | A plain-table snapshot. |

A lore entry is addressed by its resource, so it appears in `:list()` once that resource has resolved, a beat after the tab builds.

## Food

`session:char():food()` holds the client's absolute numbers about a character: `fep():cap()` and `fep():total()` are FEP points, where every bar elsewhere ([`session:meter`](meter.md)) is a fraction. `nil` until that character's sheet is up.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `food:fep()` | `Fep \| nil` | Unprotected | The FEP bar: `:cap()`, `:total()`, `:entry()`. |
| `food:fep():entry()` | collection | Unprotected | The food events, each answering `:res()`, `:name()`, `:amount()`, `:info()`. |
| `food:fep():info()` | `table \| nil` | Unprotected | Snapshot of the bar: the `fep` half of [`Food`](types/character.md#food), entries included. |
| `food:hunger()` | `Hunger \| nil` | Unprotected | The hunger meter: `:level()`, `:label()`, `:efficacy()`. |
| `food:hunger():level()` | `number \| nil` | Unprotected | How full it is. |
| `food:hunger():label()` | `string \| nil` | Unprotected | The client's own word for that level. |
| `food:hunger():efficacy()` | `number \| nil` | Unprotected | The multiplier on what you eat next at this hunger. |
| `food:hunger():info()` | `table \| nil` | Unprotected | Snapshot of the meter: the `hunger` half of [`Food`](types/character.md#food). |
| `food:fep():exists()`, `food:hunger():exists()` | `boolean` | Unprotected | Whether that half is still up; always answers. |
| `food:exists()` | `boolean` | Unprotected | Whether this is still that character's live sheet. |
| `food:info()` | [`Food`](types/character.md#food) `\| nil` | Unprotected | Snapshot, the two halves in one table. |

| Rule | Detail |
|---|---|
| Hunger is not points | `hunger:level()` is the raw fullness figure the client's tooltip prints in per-mille (`level() * 1000`); the bar draws only its fractional part, so it is not capped at 1 and a very full character reads above it. `hunger:efficacy()` is a `0..1` multiplier the client paints as a percentage. Neither carries a client [unit](shapes.md#units). |
| Snapshots | Each half snapshots on its own and inside `food:info()`; an entry's `:info()` is one row of the `entries` array. Each is `nil` when its meter is not up. |
| An entry is data, not a handle | The client rebuilds the FEP bar whole on every update, so an entry has no key and no `:exists()`: it carries the resource, name and amount the bar had when read. Read `fep():entry()` again rather than keeping one across a meal. |
| Event | [`FepChanged`](event/bus/character.md#character-and-status), whose payload is the `Food` object. |

```lua
hafen.event():on("FepChanged", function(food)
  hafen.log():write(("fep %.0f/%.0f, %s"):format(food:fep():total(), food:fep():cap(),
                                                 food:hunger():label() or "?"))
end)
```

---

## See Also

- [`session:study`](study.md) — the curiosities and their learning-point totals.
- [`session:meter`](meter.md) — the HUD bars, fractions rather than numbers.
- [`session:wound`](wound.md) — the other half of the Health and Wounds tab.
- [The character sheet](types/character.md) — `Attr`, `Food`, `Skill`, `Credo` and `Experience`.
- [Events](event/bus/character.md#character-and-status) — `FepChanged`.
