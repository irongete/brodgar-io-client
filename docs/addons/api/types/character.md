# Character Type Snapshots

Table schemas for character sheet, vitals, buffs, wounds, and stats snapshots.

---

## `Attr` (Attribute Snapshot)

Returned by `attr:info()`:

```lua
{
  name = "str",          -- string: attribute code
  base = 25,             -- number: unbuffed base value
  composite = 32         -- number: total effective value including buffs
}
```

---

## `Skill` (Skill Snapshot)

Returned by `skill:info()`:

```lua
{
  name = "Carpentry",    -- string: display name
  res = "skills/carp",   -- string: resource path
  cost = 500,            -- number: learning point price
  known = true           -- boolean: unlocked status
}
```

---

## `Food` (FEP & Hunger Snapshot)

Returned by `food:info()`:

```lua
{
  fep = {
    cap = 150,           -- number: required FEP to level up
    total = 84,          -- number: current accumulated FEP
    entries = {          -- table[]: active FEP bar segments
      { name = "Strength", amount = 42, res = "gfx/hud/fep/str" }
    }
  },
  hunger = {
    level = 1.25,        -- number: raw fullness level
    label = "Stuffed",   -- string: display hunger label
    efficacy = 0.85      -- number: food multiplier (0.0..1.0)
  }
}
```

---

## `Meter` (Vital Meter Snapshot)

Returned by `meter:info()`:

```lua
{
  name = "stamina",      -- string: meter identifier
  value = 0.85,          -- number: fill ratio (0.0..1.0)
  res = "gfx/hud/m/stm"  -- string: resource path
}
```

---

## `Buff` (Status Effect Snapshot)

Returned by `buff:info()`:

```lua
{
  name = "Full Belly",   -- string: display name
  res = "gfx/hud/buff/f",-- string: resource path
  duration = 120.5       -- number | nil: seconds remaining
}
```
