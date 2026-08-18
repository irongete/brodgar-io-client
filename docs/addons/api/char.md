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
immediate read answers `nil` or an empty array. Read on a short timer, or on the matching event.

## Whose sheet it is

There is no such thing as *the* character sheet. `s:char()` is the sheet of the character that session is
playing, and two of your characters agree about none of it. So the read says which one it is about:

```lua
hafen.session():current():char():lp()        -- the learning points of the character on screen
hafen.session():get("alt"):char():lp()       -- that character's, whether or not you are looking at it
```

`s:char()` is the same object every call, and so is each of its four collections — minted once for that
session — so a panel that reads the sheet every frame allocates nothing to do it. A session the client no
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
| `attr:info()` | [`Attr`](types.md#attr) \| nil | a plain-table **snapshot** |

`:get(name)` always hands back the attribute, whether or not the server has sent anything for it yet; it
is `:base()` and `:composite()` that answer `nil` until it has, and `:list()` that holds only the
published ones. An `Attr` is interned on its session and its name, so `:get("str") == :get("str")` on one
session and `seen[attr] = true` work, while the same name on two characters is two objects — which is what
keeps two strengths from being one number.

## Skills

| Call | Returns | Description |
|---|---|---|
| `s:char():skill():list(filter)` | `Skill[]` | the skills the character knows |
| `s:char():skill():available(filter)` | `Skill[]` | the skills that can be bought, each with a `:cost()` |
| `s:char():skill():count(filter)` | number | how many known ones match |
| `s:char():skill():find(filter)` | `Skill` \| nil | the first known skill that matches |

| Method | Returns | Description |
|---|---|---|
| `skill:name()` | string | the display name |
| `skill:res()` | string \| nil | the icon resource name, once it has resolved |
| `skill:cost()` | number \| nil | the learning-point price |
| `skill:known()` | boolean | learnt, rather than merely buyable |
| `skill:exists()` | boolean | whether it is still listed |
| `skill:info()` | [`Skill`](types.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

A string [filter](conventions.md#the-filter-argument) matches the display name **and** the resource name.
`:find(name)` hands back the skill itself, which is truthy, so `if s:char():skill():find("x") then`
reads as a membership test and also gives you the thing.

**One type covers both groups.** Buying a skill moves it from `:available()` to `:list()` without making
it a different skill: the handle you stashed goes on reading it, and `:known()` flips.

## Credos

| Call | Returns | Description |
|---|---|---|
| `s:char():credo():list(filter)` | `Credo[]` | every credo the tab lists, acquired and available |
| `s:char():credo():pursuing()` | `Credo` \| nil | the one being pursued, if any |
| `s:char():credo():cost()` | number \| nil | the learning-point price of beginning one |
| `s:char():credo():count(filter)` | number | how many match |
| `s:char():credo():find(filter)` | `Credo` \| nil | the first that matches |

| Method | Returns | Description |
|---|---|---|
| `credo:name()` | string | the display name |
| `credo:res()` | string \| nil | the icon resource name |
| `credo:acquired()` | boolean | whether the character has completed it |
| `credo:pursuing()` | boolean | whether it is the one being pursued |
| `credo:level()`, `credo:levelTotal()` | number \| nil | pursuit progress — only on the pursued credo |
| `credo:quest()`, `credo:questTotal()` | number \| nil | quest progress within the current level |
| `credo:questId()` | number \| nil | the id of the credo quest, for [`session:quest`](quest.md) |
| `credo:exists()` | boolean | whether it is still listed |
| `credo:info()` | [`Credo`](types.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

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
| `exp:info()` | [`Experience`](types.md#skill-credo-experience) \| nil | a plain-table **snapshot** |

A lore entry is addressed by its resource, so it appears in `:list()` once that resource has resolved — a
beat after the tab builds, like everything else on the sheet.

Skills, credos and lore change only on a buy, a pursue or quest progress, and have no event: read them on
demand, after your own action.

## Food

`s:char():food()` is the one place in the client with **absolute** numbers about a character —
everywhere else, a bar is a fraction. See [`session:meter`](meter.md). It answers `nil` until that
character's sheet is up.

| Method | Returns | Description |
|---|---|---|
| `food:cap()` | number \| nil | how much the FEP bar holds |
| `food:total()` | number \| nil | the sum of the current food-event amounts |
| `food:feps()` | table | the food-event groups: `{res?, name?, amount}` each |
| `food:hunger()` | number \| nil | the hunger level |
| `food:label()` | string \| nil | the client's own word for that level |
| `food:efficacy()` | number \| nil | the multiplier on what you eat next at this hunger |
| `food:exists()` | boolean | whether this is still that character's live sheet |
| `food:info()` | [`Food`](types.md#food) \| nil | a plain-table **snapshot** |

Subscribe to [`FepChanged`](event/bus.md#character-and-status), whose payload is the `Food` object itself.

```lua
hafen.event():on("FepChanged", function(food)
  hafen.log():write(("fep %.0f/%.0f, %s"):format(food:total(), food:cap(), food:label() or "?"))
end)
```

## See also

- [`session:study`](study.md) — the curiosities and their learning-point totals
- [`session:meter`](meter.md) — the HUD bars, which are fractions rather than numbers
- [`session:wound`](wound.md) — the other half of the Health and Wounds tab
- [types](types.md#attr) — `Attr`, `Food`, `Skill`, `Credo` and `Experience`
- [events](event/bus.md#character-and-status) — `FepChanged`
