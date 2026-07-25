# hafen.buffs — buffs & debuffs

Read the active buffs/debuffs shown on the HUD.

| Function | Returns | Description |
|---|---|---|
| `hafen.buffs.list()` | [`Buff`](types.md#buff)`[]` | all active buffs/debuffs |
| `hafen.buffs.has(nameOrRes)` | bool | whether any buff's name or res contains the string |

```lua
if hafen.buffs.has("poison") then hafen.log("poisoned!") end
```

Subscribe to [`BuffAdded`, `BuffRemoved`, `BuffChanged`](events.md#character--status-widget-tree-backed)
for updates.

> A buff's `amount`/`cooldown`/`number` fields are content-defined 0..1 fractions / integers published
> by the resource — they are often absent and are **not** seconds. There is no seconds-based buff timer
> in the client.
