# hafen.wounds — wounds

Read the character's wounds (the Health & Wounds tab). Read-only. Wounds form a **tree** — a
complication nests under its parent wound via `parentid` (`-1` = a root) and `level` (indent depth).

| Function | Returns | Description |
|---|---|---|
| `hafen.wounds.list([filter])` | [`Wound`](types.md#wound)`[]` | all wounds matching the [filter](conventions.md#the-filter-argument) |
| `hafen.wounds.has(needle)` | bool | whether any wound's name or res contains `needle` |

Subscribe to [`WoundChanged`](events.md#character--status-widget-tree-backed) (payload = the new list)
to react to a wound being added, healed, or worsening.

```lua
if hafen.wounds.has("Infection") then hafen.log("infected!") end

for _, w in ipairs(hafen.wounds.list()) do
  hafen.log(string.rep("  ", w.level) .. (w.name or w.res) .. "  " .. (w.severity or ""))
end
```

> A wound's `severity` is the magnitude string the client shows beside it — usually a number, but
> content-defined, and **not** seconds. It may be absent for a beat while the wound's data resolves.
