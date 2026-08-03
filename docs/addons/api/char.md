# hafen.char: the character sheet

Read the character sheet: attributes, learning points, carried weight, food and hunger, skills, credos
and lore. The curiosities being studied are next door, in [`hafen.study`](study.md).

```lua
local str = hafen.char.attr("str")
if str then hafen.log("strength " .. str.base .. " (" .. str.comp .. " buffed)") end

if hafen.char.skill("Alchemy") then hafen.log("I know Alchemy") end
```

The sheet lives in HUD widgets that build after login, so it streams in a beat after `OnEnterWorld`: an
immediate read answers `nil` or an empty array. Read on a short timer, or on the matching event.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.char.attr(name)` | [`Attr`](types.md#attr) \| nil | one attribute, `{base, comp}`, by name |
| `hafen.char.attrs()` | `{[name] = Attr}` | every populated base attribute, keyed by name |
| `hafen.char.lp()` | number \| nil | current learning points |
| `hafen.char.weight()` | number \| nil | carried weight, the encumbrance figure |
| `hafen.char.food()` | [`food`](types.md#food) \| nil | FEP and hunger |
| `hafen.char.skills()` | [`Skill`](types.md#skill-credo-experience)`[]` | known skills, `{name, res}` |
| `hafen.char.skill(name)` | bool | whether a known skill's name or res contains the string |
| `hafen.char.skillsAvailable()` | [`Skill`](types.md#skill-credo-experience)`[]` | buyable skills, `{name, res, cost}` |
| `hafen.char.credos()` | table \| nil | the Credos tab: `acquired`, `available`, `cost`, `pursuing` |
| `hafen.char.experiences()` | [`Experience`](types.md#skill-credo-experience)`[]` | lore and experiences seen |

The base attribute names are `str`, `agi`, `int`, `con`, `prc`, `csm`, `dex`, `wil` and `psy`. An
attribute the server has sent no data for reads as `nil`, and so does an unknown name. The array
readers answer an empty array rather than `nil`. Nothing here throws and nothing is gated.

`hafen.char.food()` is the one place in the client with **absolute** numbers about your character —
everywhere else, a bar is a fraction. See [`hafen.meter`](meter.md).

Subscribe to [`FepChanged`](events.md#character-and-status) for food and hunger. Skills,
credos and lore change only on a buy, a pursue or quest progress, and have no event — read them on
demand.

## See also

- [`hafen.study`](study.md) — the curiosities and their learning-point totals
- [`hafen.meter`](meter.md) — the HUD bars, which are fractions rather than numbers
- [`hafen.wounds`](wounds.md) — the other half of the Health and Wounds tab
- [types](types.md#attr) — `Attr`, `food`, `Skill`, `Credo` and `Experience`
