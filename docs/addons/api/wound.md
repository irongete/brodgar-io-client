# session:wound: Wounds & Injuries

Inspect character wounds, damage values, afflictions, and applied treatments.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- List all active character wounds
for _, active_wound in ipairs(session:wound():list()) do
  local wound_name = active_wound:name() or "Unclassified Wound"
  local damage_amount = active_wound:damage() or 0
  local is_treated = active_wound:treated()

  hafen.log():write(string.format(
    "Wound: %s (Damage: %d, Treated: %s)",
    wound_name, damage_amount, tostring(is_treated)
  ))
end
```

---

## Methods on `session:wound()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Wound[]` | All active wounds matching the filter. |
| `:count(filter?)`| `[string \| function]` | `number` | Count of wounds matching the filter. |
| `:find(filter)` | `string \| function` | `Wound \| nil` | First matching wound. |
| `:get(name)` | `string` | `Wound \| nil` | Finds a specific wound by name. |

---

## Methods on `Wound`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string` | Name of the wound or affliction (e.g. `"Bruise"`, `"Cut"`). |
| `:damage()` | `number \| nil` | Total damage points inflicted by this wound. |
| `:treated()` | `boolean` | `true` if an effective treatment or poultice is currently applied. |
| `:treatment()`| `string \| nil` | Name of the applied treatment item (or `nil`). |
| `:info()` | `table` | Plain table snapshot `{ name, damage, treated, treatment }`. |

## Events

Subscribe to `WoundAdded` or `WoundRemoved` on `hafen.event()`:

```lua
hafen.event():on("WoundAdded", function(new_wound)
  hafen.log():write("Suffered injury: " .. (new_wound:name() or "Unknown"))
end)
```
