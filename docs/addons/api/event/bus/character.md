# Character and Status Events

Events fired when character vital meters, buffs, food points, equipment, quests, or study curiosities change state.

## Events Reference

### `MeterAdded` & `MeterRemoved`
* **Triggered**: When a vital status meter is created or removed from the HUD.
* **Arguments**: `meter` ([`Meter`](../../meter.md)).

---

### `MeterChanged`
* **Triggered**: When health, stamina, energy, or water meters update their fill levels.
* **Arguments**: `changed_meter` ([`Meter`](../../meter.md)).

```lua
hafen.event():on("MeterChanged", function(changed_meter)
  if changed_meter:res() and changed_meter:res():find("stamina") then
    local fill_ratio = changed_meter:value() or 0
    -- Stamina updated
  end
end)
```

---

### `BuffAdded`, `BuffRemoved` & `BuffChanged`
* **Triggered**: When a status buff or debuff is acquired, expires, or updates remaining duration.
* **Arguments**: `buff_item` ([`Buff`](../../buff.md)).

```lua
hafen.event():on("BuffAdded", function(buff_item)
  hafen.log():write("Buff applied: " .. (buff_item:name() or "Unknown"))
end)
```

---

### `FepChanged`
* **Triggered**: When the character's food event points (FEP) or hunger meter values change.
* **Arguments**: `food_info` ([`Food`](../../char.md#food)).

---

### `EquipChanged`
* **Triggered**: When equipment worn on the character's paperdoll changes.
* **Arguments**: None.

---

### `ActionbarChanged`
* **Triggered**: When an actionbar slot is assigned, cleared, or cooldown progress changes.
* **Arguments**: `slot` ([`Slot`](../../actionbar.md)).

---

### `WoundChanged`
* **Triggered**: When the character suffers a physical injury, heals, or an existing wound's severity updates.
* **Arguments**: `wound` ([`Wound`](../../wound.md)).

---

### `KinChanged`
* **Triggered**: When a buddy/kin entry changes online status, color group, or name.
* **Arguments**: `kin_entry` ([`KinEntry`](../../kin.md)).

---

### `QuestAdded`, `QuestCompleted` & `QuestFailed`
* **Triggered**: When a personal quest or credo task is accepted, finished, or failed.
* **Arguments**: `quest` ([`Quest`](../../quest.md)).

---

### `StudyChanged`
* **Triggered**: When an item completes study or curiosity slots change on the study desk.
* **Arguments**: None.

---

### `AchievementUnlocked`
* **Triggered**: When a Steamworks achievement is unlocked by the player.
* **Arguments**: `achievement_name` (`string`).

---

### `SteamStatsLoaded`
* **Triggered**: When Steam user statistics and achievements are fully loaded from the Steam client.
* **Arguments**: None.
