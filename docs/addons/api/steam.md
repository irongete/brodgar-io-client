# hafen.steam: The Steam Client and Its Achievements

Whether the client runs under Steam, who is logged in there, and the achievements Steam keeps for the game. Read them, and hear when one unlocks. Unprotected: nothing here reaches the game server or writes to Steam.

```lua
local steam = hafen.steam()
if steam:available() and steam:ready() then
  hafen.log():write("Steam user: " .. tostring(steam:user()))
  local swan_lake = steam:achievement():get("paginae/exp/swanlake")
  if swan_lake and swan_lake:unlocked() then hafen.log():write("Swan Lake is unlocked") end
end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.steam():available()` | `boolean` | Unprotected | Whether the client was started through Steam and the Steamworks SDK initialised. `false` on a client started any other way, and every other verb here answers as for no Steam. |
| `hafen.steam():ready()` | `boolean` | Unprotected | Whether the player's stats and achievements have arrived from Steam. `false` while they load and without Steam. |
| `hafen.steam():user()` | `string \| nil` | Unprotected | The Steam persona name of the account the client runs under. `nil` without Steam. |
| `hafen.steam():id()` | `number \| nil` | Unprotected | That account's Steam id, the 32-bit account number. `nil` without Steam and while the id is not valid. |
| `hafen.steam():refresh()` | the section | Unprotected | Ask Steam for the stats and achievements again. They arrive later, announced by [`SteamStatsLoaded`](#events). Inert without Steam. |
| `hafen.steam():achievement()` | collection | Unprotected | The achievements Steam keeps for the game ([below](#the-achievements)). |

| Rule | Detail |
|---|---|
| After the start | The stats arrive from Steam's servers after the client is up: read them from `SteamStatsLoaded`, or check `ready()` first. Until then the collection is empty and every achievement reads as locked. |
| Not a character's | Steam knows the account the client runs under, not the character on screen: nothing here takes or answers for a [session](session.md). |
| No write side | Steam unlocks an achievement when the game reports it. An addon reads and listens. |

## The achievements

`hafen.steam():achievement()` is the [collection](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of the achievements in Steam's schema for the game, keyed by the name Steam gives each.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.steam():achievement():list(filter)` | `Achievement[]` | Unprotected | Every achievement in the schema, in Steam's order. Empty until `ready()`. |
| `hafen.steam():achievement():count(filter)` | `number` | Unprotected | How many match. `0` until `ready()`. |
| `hafen.steam():achievement():find(filter)` | `Achievement \| nil` | Unprotected | The first that matches. |
| `hafen.steam():achievement():get(name)` | `Achievement \| nil` | Unprotected | The achievement named `name`, an object for any string: `:exists()` says whether the schema has it. `nil` without Steam. |
| `hafen.steam():achievement():unlocked(filter)` | `Achievement[]` | Unprotected | The ones the player has unlocked, in the same order. Empty until `ready()`. |

| Rule | Detail |
|---|---|
| `filter` | The canonical [filter](conventions.md#the-filter-argument): a string is a substring match on the name, a function is called with the `Achievement`. |
| A name is Steam's | The identifier Steam's schema spells, `"paginae/exp/swanlake"`. Read the real ones off `:list()`. |
| Interned per addon | On the name: `:get(name) == :get(name)`, `:list()[1] == :get(:list()[1]:name())` and `seen[achievement] = true` work. |

## The Achievement object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `achievement:name()` | `string` | Unprotected | The name Steam's schema spells. Always answers. |
| `achievement:unlocked()` | `boolean` | Unprotected | Whether the player has unlocked it. `false` while locked, until `ready()`, and for a name Steam has not got. |
| `achievement:exists()` | `boolean` | Unprotected | Whether Steam's schema has an achievement of this name. `false` until `ready()` and without Steam. |
| `achievement:info()` | `table` | Unprotected | A plain-table snapshot, `{name=, unlocked=}`. |

| Rule | Detail |
|---|---|
| Live | A kept handle re-reads Steam on every call. One taken before the stats arrived answers `:exists()` and `:unlocked()` as Steam has them once they have. `AchievementUnlocked` hands the same object. |
| An object, not a table | Verbs are called with `:`. A name it does not answer raises naming the ones it has. `tostring(achievement)` is `Achievement(<name>)`. |

## Events

Both are on [the bus](event/bus/character.md#steam), the Steam client's moments and not a character's. They fire once for the client, whichever session is on screen, and never on a client started outside Steam.

| Event | Payload | Fires |
|---|---|---|
| `SteamStatsLoaded` | — | The stats and achievements have arrived from Steam: at the start, and again after every `refresh()`. |
| `AchievementUnlocked` | `Achievement` | Steam has stored an achievement as unlocked. |

```lua
hafen.event():on("SteamStatsLoaded", function()
  hafen.log():write(#hafen.steam():achievement():unlocked() .. " achievements unlocked")
end)
hafen.event():on("AchievementUnlocked", function(achievement)
  hafen.log():write("unlocked: " .. achievement:name())
end)
```

---

## See Also

- [Events](event/bus/character.md#steam) — the keys, among the character's own.
- [Conventions](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) — the collection verbs and the filter.
- [`session:char`](char.md) — the character's own sheet, which Steam knows nothing of.
