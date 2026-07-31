# hafen.kin — kin / buddy roster

Read and manage the Kin window (your buddy list). `hafen.kin` is **callable, and nothing else** — the
arity is the verb:

| Call | Returns |
|---|---|
| `hafen.kin()` | the **roster** — an array of `Kin` objects in Kin-window sort order |
| `hafen.kin(id)` | the `Kin` with that buddy id (always an object, even for an id you don't have) |
| `hafen.kin(name)` | the `Kin` with that **exact** (case-insensitive) name, or `nil` |

```lua
for _, k in ipairs(hafen.kin()) do                       -- the roster IS the array
  hafen.log(k:name() .. " [" .. k:group() .. "]" .. (k:online() and " online" or ""))
end
hafen.kin("Bob"):setGroup(3):rename("Bobby")             -- gated, chainable
```

Kin objects are **interned per addon**, so `hafen.kin(7) == hafen.kin(7)`, `hafen.kin()[1] ==
hafen.kin(<that id>)`, and `seen[k] = true` works as a table key. A Kin wraps **only the buddy id** and
re-reads the roster on every call, so a stashed one tracks renames, regroups and online/offline flips —
see [snapshots vs handles](conventions.md#snapshots-vs-handles).

## The roster

`hafen.kin()` is a plain array — `#roster`, `roster[1]`, `ipairs` — with three extra methods:

| Method | Returns | Description |
|---|---|---|
| `roster:find(nameOrId)` | `Kin` \| nil | one kin — a number by id, a string by exact (case-insensitive) name; `nil` if nobody matches |
| `roster:list([filter])` | `Kin[]` | the roster matching the [filter](conventions.md#the-filter-argument) — a **function filter receives a `Kin`** |
| `roster:add(secret)` | the roster | *(gated)* add a kin by the other player's **hearth secret** |

> **The array is a snapshot, the objects are live.** `hafen.kin()` builds the array at call time, so a kin
> added afterwards is not in it — call it again. Every `Kin` *inside* it stays current for as long as you
> hold it.

Before you are in the world (and briefly after `:reload`) there is no Kin window: the roster is **empty**
and `find` answers `nil`, while `hafen.kin(id)` still hands back an object whose `:exists()` is `false`.

## Reading a Kin

| Method | Returns | Description |
|---|---|---|
| `kin:id()` | number | the buddy id — answers even for a forgotten kin |
| `kin:name()` | string \| nil | the nickname shown in the Kin window |
| `kin:group()` | number \| nil | the kin's group, `0..254` |
| `kin:color()` | [`Color`](types.md#color) \| nil | the group's palette colour — **`nil` for a group ≥ 8** (the client has 8 colours; the group is the identity, the colour is presentation) |
| `kin:online()` | boolean \| nil | is the kin online right now |
| `kin:exists()` | boolean | is this id still on your roster |
| `kin:info()` | [`KinEntry`](types.md#kinentry) \| nil | a plain-table **snapshot** — the escape hatch for logging/serialising |

Every reader answers `nil` once the kin is off the roster (`:id()` and `:exists()` excepted).

Subscribe to [`KinChanged`](events.md#roster-quests-markers) to react to a kin being added, removed,
renamed, regrouped, or flipping online/offline.

## Writing *(gated — requires the `actions` permission)*

Each verb returns **the Kin itself**, so they chain.

| Method | Description |
|---|---|
| `kin:rename(name)` | set the kin's nickname |
| `kin:setGroup(group)` | move the kin to group `0..254` |
| `kin:endkin()` | **End kinship** — ends the kinship; the kin stays *memorized* in the list |
| `kin:forget()` | **Forget** — drops a memorized (un-kinned) kin from the list entirely |
| `hafen.kin():add(secret)` | add a kin by the other player's **hearth secret** (the "Add kin" field) |

> **Groups go to 254, colours stop at 8.** The server accepts `0..254`, and `setGroup` validates that
> range — but the client only draws eight kin colours, so a group ≥ 8 has no colour (`kin:color()` is
> `nil`) and the Kin window cannot currently display or select it. Stick to `0..7` unless you know what
> you are doing.

> **Removing is two steps.** The game drops a kin in two stages: `kin:endkin()` ends the kinship (the kin
> becomes memorized but stays listed), then `kin:forget()` drops the memorized entry. To fully remove an
> active kin, call both. There is no add-by-name — kinning needs a shared hearth secret (or the
> right-click "Add as kin" petal via [`hafen.act.clickGob`](actions.md#hafenactclickgob) +
> [`flower`](actions.md#hafenactflower)).
