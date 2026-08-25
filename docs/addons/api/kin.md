# session:kin: the kin roster

Read and manage one character's Kin window, its buddy list. You reach it through the
[session](session.md) whose character you mean, and `s:kin()` **is** that character's roster.

```lua
local s = hafen.session():current()                      -- the character on screen
for _, k in ipairs(s and s:kin():list() or {}) do        -- :list() is the array
  hafen.log():write(k:name() .. " [" .. k:group() .. "]" .. (k:online() and " online" or ""))
end
s:kin():get("Bob"):group(3):rename("Bobby")              -- protected, chainable
```

| Call | Returns |
|---|---|
| `s:kin():get(id)` | the `Kin` with that buddy id — always an object, even for an id that character does not have |
| `s:kin():get(name)` | the `Kin` with that exact, case-insensitive name, else `nil` |
| `s:kin():list(filter)` | the **roster** — an array of `Kin` objects in Kin-window sort order |

## Whose roster it is

Every character carries its own kin list, and the server numbers each one on its own: buddy id 7 on two
characters is two different people. So the read says which character it is about, and it answers for one
you are not looking at exactly as it answers for the drawn one:

```lua
hafen.session():current():kin():count()          -- the roster of the character on screen
hafen.session():get("alt"):kin():find("Bob")     -- that character's, while you watch someone else
```

`s:kin()` is the same object every call, minted once for that session, so a panel reading it every frame
allocates nothing. A session the client no longer holds answers an empty roster rather than raising.

Kin objects are **interned per addon** on the character *and* the id, so `s:kin():get(7) == s:kin():get(7)`,
`s:kin():list()[1] == s:kin():get(<that id>)`, and `seen[k] = true` works as a table key — while the same
number reached through two sessions gives you two objects, because it names two people. A `Kin` carries
the character and the buddy id and re-reads the roster on every call, so a stashed one tracks renames,
regroups and online flips — see [snapshots vs handles](conventions.md#snapshots-vs-handles).

> **The array is a snapshot, the objects are live.** `:list()` builds the array at call time, so a kin
> added afterwards is not in it — call it again. Every `Kin` inside it stays current for as long as you
> hold it.

Before that character is in the world, and briefly after a reload, it has no Kin window: the roster is
empty and a name lookup answers `nil`, while `:get(id)` still hands back an object whose `:exists()` is
`false`.

## Read

The first three are called on the collection, the rest on a `Kin`.

| Method | Returns | Description |
|---|---|---|
| `s:kin():list(filter)` | `Kin[]` | the roster matching the [filter](conventions.md#the-filter-argument) — a function filter receives a `Kin`, a string matches the name |
| `s:kin():count(filter)` | number | how many match, without building the array |
| `s:kin():find(filter)` | `Kin` \| nil | the first that matches |
| `kin:id()` | number | the buddy id — answers even for a forgotten kin |
| `kin:name()` | string \| nil | the nickname shown in the Kin window |
| `kin:group()` | number \| nil | the kin's group, `0..254` |
| `kin:color()` | [colour](shapes.md#colours) \| nil | the group's palette colour; `nil` for a group of 8 or more |
| `kin:online()` | boolean \| nil | whether the kin is online |
| `kin:widget()` | [Widget](ui/widget.md) \| nil | **the list row that draws them**, or `nil` when the Kin window is closed or that row is scrolled out of view |
| `kin:exists()` | boolean | whether this id is still on that character's roster |
| `kin:gob()` | [`Gob`](gob.md) \| nil | the kin's gob in the world, their body if it is loaded |
| `kin:info()` | [`KinEntry`](types/world.md#kinentry) \| nil | a plain-table **snapshot**, the escape hatch for logging and serialising |

Every reader answers `nil` once the kin is off the roster, except `:id()` and `:exists()`. No reader
throws, and none is protected.

**`:get` addresses, `:find` searches.** A **number** is a buddy id and always hands back an object, so an
id you read out of a saved file can be held before that character's roster streams in — `:exists()` is
the liveness test. A **string** is an exact, case-insensitive name and answers `nil` when nobody on that
roster carries it. `:find` takes the ordinary [filter](conventions.md#the-filter-argument) instead, so a
*partial* name is `s:kin():find("Bo")`.

Subscribe to [`KinChanged`](event/bus.md#roster-quests-markers) to react to a kin being added, removed,
renamed, regrouped, or flipping online.

### Kin and gob

A kin standing in front of a character is both a roster entry and a [game object](gob.md), and you can go
either way between them.

```lua
local s = hafen.session():current()
local k = s:kin():get("Bob")
local g = k and k:gob()
if g then
  hafen.log():write(string.format("Bob is %.1f away", g:distance()))
  hafen.log():write(tostring(g:kin() == k))                      -- true: the same interned Kin
end
```

The link is **server-side** — the game marks a kinned player's gob with their buddy id — so neither
direction guesses from a name. `gob:kin()` is a single attribute read, and the `Kin` it hands back is that
gob's own character's, since the mark is a number in that character's roster. `kin:gob()` scans the
objects that character has loaded, which is fine on demand but not something to run for every kin on every
frame.

A kin marks more than one gob: their **hearth fire** carries the mark too, which is how it shows their
name in their kin colour. So `gob:kin()` answers on it as well, and an offline kin whose hearth fire is in
view still has a `kin:gob()`. `kin:gob()` prefers their body whenever it is loaded, so it answers "where
is this kin" rather than whichever gob the object cache listed first. To get every gob marked as theirs,
filter that character's world by the inverse:

```lua
local mine = s:world():gob():list(function(g) return g:kin() == k end)
```

> **`nil` is ambiguous, both ways.** `kin:gob()` is `nil` for a kin who is offline, out of that
> character's view, or whose gob has not streamed in — you cannot tell which. `gob:kin()` is `nil` for a
> gob that is not one of that character's kin *and* for one that is not a player at all.

## Write (protected)

Each verb returns what it was called on — the `Kin`, or the collection for `add` — so they chain. `add`
hands back the collection rather than a new `Kin`, because there is none yet: the server decides whether
the secret is valid and the roster changes a beat later, as a `KinChanged`. Each verb needs its own
permission key declared in your manifest — or the group `kin.*`, which covers all five — and called from
an addon that did not declare it, each raises an error naming that key; see
[the permission model](conventions.md#the-permission-model).

| Method | Key | Description |
|---|---|---|
| `s:kin():add(secret)` | `kin.add` | add a kin by the other player's hearth secret, the string the "Add kin" field takes. It returns **nothing**: the server decides whether that secret names anyone, so there is no `Kin` yet — watch `KinChanged` for the roster |
| `kin:rename(name)` | `kin.rename` | set the kin's nickname |
| `kin:group(group)` | `kin.group` | move the kin to group `0..254` — the write half of `kin:group()` |
| `kin:endKin()` | `kin.end` | end the kinship; the kin stays *memorized* in the list |
| `kin:forget()` | `kin.forget` | drop a memorized, un-kinned kin from the list entirely |

**A key covers every character.** These act on whichever character you addressed, drawn or not, and the
key you declared is the whole of what they need — one grant, not one per login. That is the rule for the
whole [protected tier](../guides/permissions.md), and it is most visible here, because a kin list is the
first thing an alt has of its own.

**Groups go to 254, colours stop at 8.** The server accepts `0..254` and the write validates that range,
but the client draws eight kin colours, so a group of 8 or more has no colour and the Kin window can
neither display nor select it. Stay within `0..7` unless you know what you are doing.

**Removing is two steps.** The game drops a kin in two stages: `kin:endKin()` ends the kinship, after
which the kin is memorized but still listed, then `kin:forget()` drops the memorized entry. To fully
remove an active kin, call both.

There is no add-by-name. Kinning needs a shared hearth secret, or the right-click "Add as kin" petal,
which is [`s:world():click(gob, 3)`](world.md#write-protected) followed by
[`s:flowermenu():select`](flowermenu.md#write-protected).

## See also

- [Gob](gob.md) — the object side of `kin:gob()`
- [permissions](../guides/permissions.md) — the keys these writes share, and what a key covers
- [`KinEntry`](types/world.md#kinentry) — the snapshot shape `:info()` returns
- [`session:party`](party.md) — the other roster, which carries no names
- [events](event/bus.md#roster-quests-markers) — `KinChanged`
