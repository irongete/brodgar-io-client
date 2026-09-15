# Character and Status Events

Events fired when character vital meters, buffs, food points, or study curiosities change state.

## Events Reference

### `MeterChanged`
* **Triggered**: When health, stamina, energy, or water meters update.
* **Arguments**: `changed_meter` ([`Meter`](../../meter.md)).

```lua
hafen.event():on("MeterChanged", function(changed_meter)
  if changed_meter:name() == "stamina" then
    local fill_ratio = changed_meter:value() or 0
    -- Stamina updated
  end
end)
```

---

### `BuffAdded` & `BuffRemoved`
* **Triggered**: When a status buff or debuff is acquired or expires.
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

### `WoundAdded` & `WoundRemoved`
* **Triggered**: When the character suffers a physical injury or an existing wound heals.
* **Arguments**: `wound_item` ([`Wound`](../../wound.md)).

---

### `StudyChanged`
* **Triggered**: When an item completes study or curiosity slots change on the study desk.
* **Arguments**: None.
