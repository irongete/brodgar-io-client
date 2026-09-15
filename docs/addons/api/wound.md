# session:wound: Wounds & Injuries

Inspect character wounds, afflictions, severity, and wound hierarchies.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- List all active character wounds
for _, active_wound in ipairs(session:wound():list()) do
  local wound_name = active_wound:name() or "Unclassified Wound"
  local severity_value = active_wound:severity() or 0
  local wound_label = active_wound:label() or wound_name

  hafen.log():write(string.format(
    "Wound: %s (Severity: %d, Label: %s)",
    wound_name, severity_value, wound_label
  ))
end
```

---

## Methods on `session:wound()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Wound[]` | All active wounds matching the filter. |
| `:get(wound_id)` | `number` | `Wound \| nil` | Finds a wound by its unique ID. |
| `:count(filter?)`| `[string \| function]` | `number` | Total number of active wounds. |

---

## Methods on `Wound`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Unique wound identifier. |
| `:name()` | `string` | Display name of the wound (e.g. `"Bruise"`, `"Blunt Trauma"`). |
| `:res()` | `string \| nil` | Underlying resource path identifier. |
| `:severity()` | `number \| nil` | Severity / damage magnitude points. |
| `:label()` | `string \| nil` | Full formatted status text (e.g. `"Bruise (5)"`). |
| `:parent()` | `Wound \| nil` | Parent wound if this is a sub-affliction. |
| `:children()` | `WoundCollection` | Nested sub-afflictions under this wound. |
| `:depth()` | `number` | Hierarchy nesting depth (`0` for top-level). |
| `:exists()` | `boolean` | `true` if wound is currently active on the character. |
| `:info()` | `table \| nil` | Plain table snapshot `{ id, name, res, severity, label, depth }`. |

---

## Events

Subscribe to `WoundChanged` on `hafen.event()`:

```lua
hafen.event():on("WoundChanged", function(changed_wound)
  hafen.log():write("Wound updated: " .. (changed_wound:name() or "Unknown"))
end)
```
