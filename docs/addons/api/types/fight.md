# Combat Type Snapshots

Table schemas for martial arts maneuvers, combat cards, and opponent targets.

---

## `Maneuver` (Combat Technique Snapshot)

Returned by `maneuver:info()`:

```lua
{
  name = "Punch",             -- string: display name
  res = "martial/punch",      -- string: resource path
  dealable = 3,               -- number: max copies allowed in school
  used = 1                    -- number: copies currently dealt
}
```

---

## `DeckCard` (Combat Deck Slot Snapshot)

Returned by `card:info()`:

```lua
{
  key = "1",                  -- string | nil: hotkey button label
  wire = 0,                   -- number: 0-based deck slot index
  name = "Chop",              -- string | nil: maneuver name
  res = "martial/chop",       -- string | nil: maneuver resource
  exists = true               -- boolean: true if slot is filled
}
```

---

## `Opponent` (Combat Target Snapshot)

Returned by `opponent:info()`:

```lua
{
  id = 459102,                -- number: server entity ID
  gob = ...                   -- Gob handle: live game object reference
}
```
