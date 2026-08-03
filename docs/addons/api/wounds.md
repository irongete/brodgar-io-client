# hafen.wounds: wounds

Read the character's wounds, the Health and Wounds tab. Read-only: healing is an item or a menu action,
not something this namespace does.

```lua
if hafen.wounds.has("Infection") then hafen.log("infected!") end

for _, w in ipairs(hafen.wounds.list()) do
  hafen.log(string.rep("  ", w.level) .. (w.name or w.res) .. "  " .. (w.severity or ""))
end
```

Wounds form a **tree**: a complication nests under its parent wound via `parentid`, where `-1` marks a
root, and `level`, the indent depth. `list` returns them flat, in tree order, so the indent above is
all it takes to print the shape.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.wounds.list(filter)` | [`Wound`](types.md#wound)`[]` | every wound matching the [filter](conventions.md#the-filter-argument) |
| `hafen.wounds.has(needle)` | bool | whether any wound's name or res contains `needle` |

`list` answers an empty array and `has` answers `false` before the tab has built, which is a beat after
`OnEnterWorld`. Neither throws and neither is gated.

> A wound's `severity` is the magnitude string the client shows beside it — usually a number, but
> content-defined, and **not** seconds. It may be absent for a beat while the wound's data resolves.

Subscribe to [`WoundChanged`](events.md#character-and-status), whose payload is the new
list, to react to a wound being added, healed or worsening.

## See also

- [`Wound`](types.md#wound) — the snapshot shape `list` returns
- [`hafen.char`](char.md) — the rest of the character sheet
- [events](events.md#character-and-status) — `WoundChanged`
