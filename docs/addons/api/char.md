# session:char: the character sheet

Read one of your characters' sheets: attributes, learning points, carried weight, food and hunger, skills,
credos and lore. You reach it through the [session](session.md) whose character you mean. The curiosities
that character is studying are next door, in [`session:study`](study.md).

```lua
local s = hafen.session():current()                  -- the character on screen
local str = s and s:char():attr():get("str")
if str then
  hafen.log():write("strength " .. (str:base() or 0) .. " (" .. (str:composite() or 0) .. " buffed)")
end

if s and s:char():skill():find("Alchemy") then hafen.log():write("I know Alchemy") end
```

The sheet lives in HUD widgets that build after login, so it streams in a beat after `SessionEnteredWorld`: an
immediate read answers `nil` or an empty array. Read on a short timer. **Two reads on this page have an
event and the rest have none**: `FepChanged` for food and hunger, and `StudyChanged` for the curiosities
next door, [both on the bus](event/bus/character.md#character-and-status). Attributes, learning points,
weight, skills, credos and lore change only on an action of yours, so they are read on demand, after it.

## Whose sheet it is

There is no such thing as *the* character sheet. `s:char()` is the sheet of the character that session is
playing, and two of your characters agree about none of it. So the read says which one it is about:

```lua
hafen.session():current():char():lp()        -- the learning points of the character on screen
hafen.session():get("alt"):char():lp()       -- that character's, whether or not you are looking at it
```

`s:char()` is the same object every call, and so is each of the collections in the table below — minted
once for that session — so a panel that reads the sheet every frame allocates nothing to do it.
`s:char():skill():buyable(f)` is the exception: it is a partition rather than one of the sheet's own
collections, so [it is a view](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) —
two calls are two objects, and the skills inside them are the identity. A session the client no
longer holds answers `nil`-shaped rather than raising, the same shape as before entering the world;
[`s:exists()`](session.md#read) tells the two apart.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:char():attr()` | collection | the base attributes |
| `s:char():skill()` | collection | the skills the character knows |
| `s:char():credo()` | collection | the Credos tab |
| `s:char():experience()` | collection | the Lore tab |
| `s:char():food()` | [`Food`](#food) \| nil | FEP and hunger, once the sheet is up |
| `s:char():lp()` | number \| nil | current learning points |
| `s:char():weight()` | number \| nil | carried weight, the encumbrance figure |

Nothing on this page throws once the sheet is up, and nothing is protected: the character sheet is a display
of server state, and every change to it is an action taken elsewhere.

## Attributes

| Call | Returns | Description |
|---|---|---|
| `s:char():attr():get(name)` | `Attr` | one attribute — **never nil** for a real name |
| `s:char():attr():list(filter)` | `Attr[]` | every attribute the server has published |
| `s:char():attr():count(filter)` | number | how many match |
| `s:char():attr():find(filter)` | `Attr` \| nil | the first that matches |

The base attribute names are `str`, `agi`, `int`, `con`, `prc`, `csm`, `dex`, `wil` and `psy`. **That set
is closed**, so a name outside it is refused with an error listing the nine rather than answered with
something whose every read is `nil` — a misspelt attribute can never come to mean anything, so it is a
typo and nothing else.

| Method | Returns | Description |
|---|---|---|
| `attr:name()` | string | which attribute this is |
| `attr:base()` | number \| nil | the raw base value |
| `attr:composite()` | number \| nil | the computed value: base plus food, gear and buffs |
| `attr:info()` | [`Attr`](types/character.md#attr) \| nil | a plain-table **snapshot** |

`:get(name)` always hands back the attribute, whether or not the server has sent anything for it yet; it
is `:base()` and `:composite()` that answer `nil` until it has, and `:list()` that holds only the
published ones. An `Attr` is interned on its session and its name, so `:get("str") == :get("str")` on one
session and `seen[attr] = true` work, while the same name on two characters is two objects — which is what
keeps two strengths from being one number.

## Skills

| Call | Returns | Description |
|---|---|---|
| `s:char():skill():list(filter)` | `Skill[]` | the skills the character knows |
| `s:char():skill():buyable(filter)` | collection | the skills that can be bought, each with a `:cost()` — a partition minted per call, so `:count()`, `:find()` and `:list()` all answer on it but `:buyable() == :buyable()` is false |
| `s:char():skill():count(filter)` | number | how many known ones match |
| `s:char():skill():find(filter)` | `Skill` \| nil | the first known skill that matches |

| Method | Returns | Description |
|---|---|---|
| `skill:name()` | string | the display name |
| `skill:res()` | string \| nil | the icon resource name, once it has resolved |
| `skill:cost()` | number \| nil | the learning-point price |
| `skill:known()` | boolean | learnt, rather than merely buyable |
| `skill:exists()` | boolean | whether it is still listed |
| `skill:info()` | [`Skill`](types/character.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

A string [filter](conventions.md#the-filter-argument) matches the display name **and** the resource name.
`:find(name)` hands back the skill itself, which is truthy, so `if s:char():skill():find("x") then`
reads as a membership test and also gives you the thing.

**One type covers both groups.** Buying a skill moves it from `:buyable()` to `:list()` without making
it a different skill: the handle you stashed goes on reading it, and `:known()` flips.

## Credos

| Call | Returns | Description |
|---|---|---|
| `s:char():credo():list(filter)` | `Credo[]` | every credo the tab lists, acquired and available |
| `s:char():credo():pursuing()` | `Credo` \| nil | the one being pursued, if any |
| `s:char():credo():count(filter)` | number | how many match |
| `s:char():credo():find(filter)` | `Credo` \| nil | the first that matches |

| Method | Returns | Description |
|---|---|---|
| `credo:name()` | string | the display name |
| `credo:res()` | string \| nil | the icon resource name |
| `credo:acquired()` | boolean | whether the character has completed it |
| `credo:rank()`, `credo:levelTotal()` | number \| nil | pursuit progress — only on the pursued credo |
| `credo:questsDone()`, `credo:questTotal()` | number \| nil | quest progress within the current level — both are **counts**; `credo:questId()` is the quest itself |
| `credo:questId()` | number \| nil | the id of the credo quest, for [`session:quest`](quest.md) |
| `credo:exists()` | boolean | whether it is still listed |
| `credo:info()` | [`Credo`](types/character.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

The credo being pursued is a member of the collection like any other, so it compares equal to the same
credo found in `:list()`. The five progress reads answer `nil` on every credo but that one.

## Lore

| Call | Returns | Description |
|---|---|---|
| `s:char():experience():list(filter)` | `Experience[]` | the lore the character has seen |
| `s:char():experience():count(filter)` | number | how many match |
| `s:char():experience():find(filter)` | `Experience` \| nil | the first that matches |

| Method | Returns | Description |
|---|---|---|
| `exp:name()` | string \| nil | the display name |
| `exp:res()` | string | the lore resource name, its identity |
| `exp:score()` | number \| nil | the experience points it is worth |
| `exp:modified()` | number \| nil | the server's own time field for this entry |
| `exp:exists()` | boolean | whether it is still listed |
| `exp:info()` | [`Experience`](types/character.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

A lore entry is addressed by its resource, so it appears in `:list()` once that resource has resolved — a
beat after the tab builds, like everything else on the sheet.

Skills, credos and lore change only on a buy, a pursue or quest progress, and have no event: read them on
demand, after your own action.

## Food

`s:char():food()` is where the client's **absolute** numbers about a character live: `fep():cap()` and
`fep():total()` are FEP points, and everywhere else a bar is a fraction. See [`session:meter`](meter.md).
It answers `nil` until that character's sheet is up.

The two hunger reads are **not** points. `hunger:level()` is the raw fullness figure the client's own
tooltip prints in per-mille, `level() * 1000`, and the bar draws only its fractional part — so it is **not
capped at 1** and a very full character reads above it. `hunger:efficacy()` is a `0..1` multiplier the
client paints as a percentage. Neither carries a [unit](shapes.md#units) of the client's.

| Method | Returns | Description |
|---|---|---|
| `food:fep()` | `Fep` \| nil | the FEP bar: `:cap()`, `:total()` and `:entry()` |
| `food:fep():entry()` | collection | the food events, each answering `:res()`, `:name()`, `:amount()` and `:info()` |
| `food:fep():info()` | table \| nil | a plain-table **snapshot** of the bar — the `fep` half of [`Food`](types/character.md#food), entries included |
| `food:hunger()` | `Hunger` \| nil | the hunger meter: `:level()`, `:label()` and `:efficacy()` |
| `food:hunger():level()` | number \| nil | how full it is |
| `food:hunger():label()` | string \| nil | the client's own word for that level |
| `food:hunger():efficacy()` | number \| nil | the multiplier on what you eat next at this hunger |
| `food:hunger():info()` | table \| nil | a plain-table **snapshot** of the meter — the `hunger` half of [`Food`](types/character.md#food) |
| `food:fep():exists()`, `food:hunger():exists()` | boolean | whether that half is still up — always answers |
| `food:exists()` | boolean | whether this is still that character's live sheet |
| `food:info()` | [`Food`](types/character.md#food) \| nil | a plain-table **snapshot**, the two halves in one table |

Each half snapshots on its own as well as inside `food:info()`, and an entry's own `:info()` is one row of
the `entries` array — the same tables, addressed at whichever level you are holding. Each is `nil` when
its meter is not up.

Subscribe to [`FepChanged`](event/bus/character.md#character-and-status), whose payload is the `Food` object
itself.

```lua
hafen.event():on("FepChanged", function(food)
  hafen.log():write(("fep %.0f/%.0f, %s"):format(food:fep():total(), food:fep():cap(),
                                                 food:hunger():label() or "?"))
end)
```

## See also

- [`session:study`](study.md) — the curiosities and their learning-point totals
- [`session:meter`](meter.md) — the HUD bars, which are fractions rather than numbers
- [`session:wound`](wound.md) — the other half of the Health and Wounds tab
- [the character sheet](types/character.md) — `Attr`, `Food`, `Skill`, `Credo` and `Experience`
- [events](event/bus/character.md#character-and-status) — `FepChanged`
