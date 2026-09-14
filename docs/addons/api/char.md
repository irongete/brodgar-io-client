# session:char: Character Sheet

Read character attributes, learning points, skills, credos, lore, and food/hunger status.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local character_sheet = session:char()

-- Read strength attribute
local strength_attr = character_sheet:attr():get("str")
if strength_attr then
  hafen.log():write(string.format(
    "Strength: Base %d, Buffed %d",
    strength_attr:base() or 0, strength_attr:composite() or 0
  ))
end

-- Read current Learning Points (LP)
local current_lp = character_sheet:lp() or 0
hafen.log():write("Learning Points: " .. current_lp)
```

---

## Character Sheet Overview (`session:char()`)

| Method | Returns | Description |
|---|---|---|
| `:attr()` | `AttrCollection` | Character base attributes (`str`, `agi`, `int`, `con`, `prc`, `csm`, `dex`, `wil`, `psy`). |
| `:skill()` | `SkillCollection` | Known and buyable character skills. |
| `:credo()` | `CredoCollection` | Credos completed or currently in pursuit. |
| `:experience()` | `ExperienceCollection` | Lore and discovery experience entries. |
| `:food()` | `Food \| nil` | Food Event Points (FEP) and hunger levels. |
| `:lp()` | `number \| nil` | Total available learning points. |
| `:weight()` | `number \| nil` | Carried encumbrance weight. |

---

## Attributes Subsystem (`char:attr()`)

Valid attribute names: `"str"`, `"agi"`, `"int"`, `"con"`, `"prc"`, `"csm"`, `"dex"`, `"wil"`, `"psy"`.

| Method on `Attr` | Returns | Description |
|---|---|---|
| `:name()` | `string` | Attribute code. |
| `:base()` | `number \| nil` | Unbuffed base attribute value. |
| `:composite()` | `number \| nil` | Total effective value including equipment and food buffs. |

---

## Skills Subsystem (`char:skill()`)

| Method on `Skill` | Returns | Description |
|---|---|---|
| `:name()` | `string` | Display name of the skill. |
| `:cost()` | `number \| nil` | Cost in Learning Points to unlock. |
| `:known()` | `boolean` | `true` if already unlocked; `false` if buyable. |

---

## Food & Hunger Subsystem (`char:food()`)

Listen for the `FepChanged` event on `hafen.event()`:

```lua
hafen.event():on("FepChanged", function(food_info)
  local fep_total = food_info:fep():total()
  local fep_cap = food_info:fep():cap()
  local hunger_label = food_info:hunger():label() or "Unknown"

  hafen.log():write(string.format("FEP: %.0f/%.0f | Hunger: %s", fep_total, fep_cap, hunger_label))
end)
```
