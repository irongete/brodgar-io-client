# hafen.steam: Steam Client and Achievements

Steam connection status, user identity, and Steam achievements. Reach for it to inspect Steam integration, query unlocked achievements, or react when an achievement unlocks.

## Quick Example

```lua
local steam = hafen.steam()

if steam:available() and steam:ready() then
  hafen.log():write("Steam user: " .. tostring(steam:user()))
  
  -- Query a specific achievement
  local swan = steam:achievement():get("paginae/exp/swanlake")
  if swan and swan:unlocked() then
    hafen.log():write("Swan Lake achievement is unlocked!")
  end
end
```

---

## Read

### `hafen.steam():available()`
Returns `true` if the client was launched via Steam and the Steamworks SDK initialized successfully, `false` otherwise.

| Parameters | Returns |
|---|---|
| None | `boolean` |

### `hafen.steam():ready()`
Returns `true` if user stats and achievements have finished downloading from Steam servers, `false` while loading or when Steam is unavailable.

| Parameters | Returns |
|---|---|
| None | `boolean` |

### `hafen.steam():user()`
The active Steam player's persona / display name, or `nil` if Steam is unavailable. `hafen.steam():username()` is an alias.

| Parameters | Returns |
|---|---|
| None | `string \| nil` |

### `hafen.steam():id()`
The active Steam account ID (32-bit account identifier), or `nil` if Steam is unavailable.

| Parameters | Returns |
|---|---|
| None | `number \| nil` |

### `hafen.steam():refresh()`
Requests an asynchronous reload of user stats and achievements from Steam servers. Returns `hafen.steam()`.

| Parameters | Returns |
|---|---|
| None | `self` |

### `hafen.steam():achievement()`
The collection of Steam achievements. Supports standard collection query methods:

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list([filter])` | `[string \| function]` | `Achievement[]` | Array of all achievement handles (all 99 achievements). |
| `:count([filter])` | `[string \| function]` | `number` | Total number of achievements matching filter (or 99 total). |
| `:get(name)` | `string` | `Achievement` | Achievement handle by API name (e.g. `"paginae/exp/swanlake"`). |
| `:find(filter)` | `string \| function` | `Achievement \| nil` | First achievement matching filter. |
| `:unlocked([filter])` | `[string \| function]` | `Achievement[]` | Array of achievements currently unlocked by the player. |

---

## Methods on `Achievement`

An `Achievement` is an interned handle addressing one Steam achievement.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:name()` | None | `string` | The technical Steam API identifier (e.g. `"paginae/exp/haven"`). |
| `:unlocked()` | None | `boolean` | `true` if achieved on Steam, `false` if locked or not loaded. |
| `:achieved()` | None | `boolean` | Alias for `:unlocked()`. |
| `:exists()` | None | `boolean` | `true` if the achievement is registered in the Steam schema. |
| `:info()` | None | `table` | Snapshot table: `{name = string, unlocked = boolean}`. |

---

## Events

Subscribe to Steam events on `hafen.event()`:

| Event | Payload | Description |
|---|---|---|
| `"SteamStatsLoaded"` | None | Fired when stats and achievements finish initial loading from Steam. |
| `"AchievementUnlocked"` | `Achievement` | Fired when an achievement is stored as unlocked. |

```lua
hafen.event():on("AchievementUnlocked", function(ach)
  hafen.log():write("Achievement unlocked: " .. ach:name())
end)
```

---

## See also

* [conventions](conventions.md) — Uniform API calling conventions and collection patterns
* [event/bus](event/README.md) — Event bus subscription rules and event catalogue
* [char](char.md) — In-game character sheet lore and experience tracking (`session:char():experience()`)
