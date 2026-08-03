# hafen.kin: the kin roster

Read and manage the Kin window, your buddy list. `hafen.kin` is a function, and the arity is the verb.

```lua
for _, k in ipairs(hafen.kin()) do                       -- the roster IS the array
  hafen.log(k:name() .. " [" .. k:group() .. "]" .. (k:online() and " online" or ""))
end
hafen.kin("Bob"):setGroup(3):rename("Bobby")             -- gated, chainable
```

| Call | Returns |
|---|---|
| `hafen.kin()` | the **roster** — an array of `Kin` objects in Kin-window sort order |
| `hafen.kin(id)` | the `Kin` with that buddy id — always an object, even for an id you do not have |
| `hafen.kin(name)` | the `Kin` with that exact, case-insensitive name, else `nil` |

The roster is a plain array, so `#`, `[1]` and `ipairs` all work on it. Kin objects are **interned per
addon**, so `hafen.kin(7) == hafen.kin(7)`, `hafen.kin()[1] == hafen.kin(<that id>)`, and
`seen[k] = true` works as a table key. A `Kin` wraps only the buddy id and re-reads the roster on every
call, so a stashed one tracks renames, regroups and online flips — see
[snapshots vs handles](conventions.md#snapshots-vs-handles).

> **The array is a snapshot, the objects are live.** `hafen.kin()` builds the array at call time, so a
> kin added afterwards is not in it — call it again. Every `Kin` inside it stays current for as long as
> you hold it.

Before you are in the world, and briefly after a reload, there is no Kin window: the roster is empty
and a name lookup answers `nil`, while `hafen.kin(id)` still hands back an object whose `:exists()` is
`false`.

## Read

`find` and `list` are called on the roster — `hafen.kin():find(...)`, or keep the roster in a variable
and call it on that. The rest are called on a `Kin`.

| Method | Returns | Description |
|---|---|---|
| `hafen.kin():find(nameOrId)` | `Kin` \| nil | one kin: a number by id, a string by exact, case-insensitive name; `nil` if nobody matches |
| `hafen.kin():list(filter)` | `Kin[]` | the roster matching the [filter](conventions.md#the-filter-argument) — a function filter receives a `Kin` |
| `kin:id()` | number | the buddy id — answers even for a forgotten kin |
| `kin:name()` | string \| nil | the nickname shown in the Kin window |
| `kin:group()` | number \| nil | the kin's group, `0..254` |
| `kin:color()` | [`Color`](types.md#color) \| nil | the group's palette colour; `nil` for a group of 8 or more |
| `kin:online()` | boolean \| nil | whether the kin is online |
| `kin:exists()` | boolean | whether this id is still on your roster |
| `kin:gob()` | [`Gob`](gob.md) \| nil | the kin's gob in the world, their body if it is loaded |
| `kin:info()` | [`KinEntry`](types.md#kinentry) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every reader answers `nil` once the kin is off the roster, except `:id()` and `:exists()`. No reader
throws, and none is gated.

Subscribe to [`KinChanged`](events.md#roster-quests-markers) to react to a kin being added, removed,
renamed, regrouped, or flipping online.

### Kin and gob

A kin standing in front of you is both a roster entry and a [game object](gob.md), and you can go
either way between them.

```lua
local k = hafen.kin("Bob")
local g = k and k:gob()
if g then
  hafen.log(string.format("Bob is %.1f away", g:distance()))
  hafen.log(tostring(g:kin() == k))                      -- true: the same interned Kin
end
```

The link is **server-side** — the game marks a kinned player's gob with their buddy id — so neither
direction guesses from a name. `gob:kin()` is a single attribute read; `kin:gob()` scans the loaded
objects, which is fine on demand but not something to run for every kin on every frame.

A kin marks more than one gob: their **hearth fire** carries the mark too, which is how it shows their
name in their kin colour. So `gob:kin()` answers on it as well, and an offline kin whose hearth fire is
in view still has a `kin:gob()`. `kin:gob()` prefers their body whenever it is loaded, so it answers
"where is this kin" rather than whichever gob the object cache listed first. To get every gob marked as
theirs, filter the world by the inverse:

```lua
local mine = hafen.world.gobs(function(g) return g:kin() == k end)
```

> **`nil` is ambiguous, both ways.** `kin:gob()` is `nil` for a kin who is offline, out of view, or
> whose gob has not streamed in — you cannot tell which. `gob:kin()` is `nil` for a gob that is not one
> of your kin *and* for one that is not a player at all.

## Write (gated: `actions`)

Each verb returns what it was called on — the `Kin`, or the roster for `add` — so they chain. Called
from an addon that did not declare the permission, each raises an error; see [`hafen.act`](act.md).

| Method | Description |
|---|---|
| `hafen.kin():add(secret)` | add a kin by the other player's hearth secret, the string the "Add kin" field takes |
| `kin:rename(name)` | set the kin's nickname |
| `kin:setGroup(group)` | move the kin to group `0..254` |
| `kin:endkin()` | end the kinship; the kin stays *memorized* in the list |
| `kin:forget()` | drop a memorized, un-kinned kin from the list entirely |

**Groups go to 254, colours stop at 8.** The server accepts `0..254` and `setGroup` validates that
range, but the client draws eight kin colours, so a group of 8 or more has no colour and the Kin window
can neither display nor select it. Stay within `0..7` unless you know what you are doing.

**Removing is two steps.** The game drops a kin in two stages: `kin:endkin()` ends the kinship, after
which the kin is memorized but still listed, then `kin:forget()` drops the memorized entry. To fully
remove an active kin, call both.

There is no add-by-name. Kinning needs a shared hearth secret, or the right-click "Add as kin" petal,
which is [`hafen.act`](act.md)'s `clickGob` followed by `flower`.

## See also

- [`hafen.gob`](gob.md) — the object side of `kin:gob()`
- [`hafen.act`](act.md) — the permission every write verb here shares
- [`KinEntry`](types.md#kinentry) — the snapshot shape `:info()` returns
- [`hafen.party`](party.md) — the other roster, which carries no names
- [events](events.md#roster-quests-markers) — `KinChanged`
