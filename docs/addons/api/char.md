# hafen.char / hafen.study — character sheet

Read the character sheet: attributes, learning points, food/hunger, skills, credos, lore, and study.
Most of this streams in a beat after `OnEnterWorld` (it lives in HUD widgets that build after login),
so an immediate read may return `nil`/empty — read on a short timer or via the matching event.

## hafen.char

| Function | Returns | Description |
|---|---|---|
| `hafen.char.attr(name)` | [`Attr`](types.md#attr) \| nil | one attribute `{base, comp}` by name |
| `hafen.char.attrs()` | `{[name] = Attr}` | all populated base attributes, keyed by name |
| `hafen.char.lp()` | number \| nil | current learning points |
| `hafen.char.weight()` | number \| nil | carried weight / encumbrance |
| `hafen.char.food()` | [`food`](types.md#food) \| nil | FEP + hunger |
| `hafen.char.skills()` | [`Skill`](types.md#skill--credo--experience)`[]` | known skills `{name, res}` |
| `hafen.char.skill(name)` | bool | whether a known skill's name or res contains the string |
| `hafen.char.skillsAvailable()` | [`Skill`](types.md#skill--credo--experience)`[]` | buyable skills `{name, res, cost}` |
| `hafen.char.credos()` | table \| nil | the Credos tab (`acquired`, `available`, `cost`, `pursuing?`) |
| `hafen.char.experiences()` | [`Experience`](types.md#skill--credo--experience)`[]` | seen lore/experiences |

The base attribute names are `str`, `agi`, `int`, `con`, `prc`, `csm`, `dex`, `wil`, `psy`. An
attribute with no server data yet reads as `nil`.

```lua
local str = hafen.char.attr("str")
if str then hafen.log("strength " .. str.base .. " (" .. str.comp .. " buffed)") end

if hafen.char.skill("Alchemy") then hafen.log("I know Alchemy") end
```

Subscribe to [`FepChanged`](events.md#character--status-widget-tree-backed) for food/hunger. Skills,
credos, and lore change rarely (only on buy/pursue/quest progress) and have no change event — read
them on demand.

## hafen.study

The curiosities being studied.

| Function | Returns | Description |
|---|---|---|
| `hafen.study.slots()` | [`StudySlot`](types.md#studyslot)`[]` | the curiosities currently in the study window |
| `hafen.study.summary()` | `{lp, attention, cost}` \| nil | live totals across the slots |

```lua
for _, s in ipairs(hafen.study.slots()) do
  hafen.log((s.name or s.res) .. "  lp=" .. (s.lp or 0))
end
```

Subscribe to [`StudyChanged`](events.md#character--status-widget-tree-backed) for updates. Note `time`
on a slot is the **total** study time — the client has no per-item countdown.
