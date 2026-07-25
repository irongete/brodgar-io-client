# hafen.radar — minimap icon categories

Read and toggle the minimap/radar icon registry — the same categories the in-client **Icon settings**
window edits. Each **category** is one kind of gob-icon (a boar, a fir tree, a player, …) with two
flags: `show` (draw it on the minimap) and `notify` (play a sound + chat message when one appears).

| Function | Returns | Description |
|---|---|---|
| `hafen.radar.categories([filter])` | [`RadarCategory`](types.md#radarcategory)`[]` | all categories matching the [filter](conventions.md#the-filter-argument) |
| `hafen.radar.setVisible(filter, on)` | number | set `show = on` on every matching category; returns how many matched |
| `hafen.radar.setNotify(filter, on)` | number | set `notify = on` on every matching category; returns how many matched |

Changes are persisted (per character) and take effect immediately, exactly as the settings window's
checkboxes do — so use the setters deliberately; they modify the user's real icon configuration.

```lua
-- Notify me whenever a player-type icon appears:
local n = hafen.radar.setNotify(function(c) return c.res:find("borka") ~= nil end, true)
hafen.log("armed notify on " .. n .. " categories")
```

The registry is empty until the HUD is up and grows as the character sees new icon types. It changes
rarely, so there is no `*Changed` event — read on demand.
