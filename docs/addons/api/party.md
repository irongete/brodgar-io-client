# hafen.party: the party roster

Read the party you are in. `hafen.party()` **is** the roster: the members come back in party sequence
order, and each one hands back its live [Gob](gob.md).

```lua
for _, m in ipairs(hafen.party():list()) do
  hafen.log():write(m:gob():name() .. (m:leader() and " (leader)" or ""))
end
```

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.party():list(filter)` | `PartyMember[]` | every member, in party sequence order |
| `hafen.party():count(filter)` | number | how many match |
| `hafen.party():find(filter)` | `PartyMember` \| nil | the first that matches |
| `hafen.party():get(gobId)` | `PartyMember` \| nil | one member, by gob id |
| `hafen.party():leader()` | `PartyMember` \| nil | the member leading the party |

Outside a party `:list()` is an empty array, `:count()` is `0`, and `:leader()` and `:get(id)` are `nil`;
so is `:get(id)` for an id that is not in the party. Nothing here throws and nothing is gated. There is
no write side: joining and leaving a party is a menu action, reachable through [`hafen.act`](act.md).

The [filter](conventions.md#the-filter-argument) has to be a function here. A string is refused, because
a party member has nothing to match it against — see below.

## A member

| Method | Returns | Description |
|---|---|---|
| `member:id()` | number | the member's gob id — always answers |
| `member:gob()` | [Gob](gob.md) | the member's live object — never `nil` |
| `member:position()` | [Position](world.md#the-position-type) \| nil | where they are |
| `member:color()` | [Color](types.md#color) \| nil | the party colour drawn for them |
| `member:leader()` | boolean | whether they lead the party |
| `member:exists()` | boolean | whether they are still in the party — always answers |
| `member:info()` | [`PartyMember`](types.md#partymember) \| nil | a plain-table **snapshot** |

> A party member has **no name**: the client is never sent one. A member is an id, a position, a colour
> and the leader flag. The name over their head belongs to the creature, so it is `member:gob():name()`.

That is also why `:get` takes a gob id and a string filter is refused: a name is the one thing the roster
cannot match on, and matching nothing quietly would be worse than saying so.

`member:gob()` is never `nil`, exactly like [`hafen.world():gob():get(id)`](gob.md) — a member whose
object the world does not currently hold answers a Gob whose `:exists()` is `false`. So the way to ask
whether you can see someone is `member:gob():exists()`, not a `nil` test.

`member:position()` and `member:gob():position()` are **not** the same read. The member's own position is
the live one while they are in view and the **last-known** one once they walk out of it, so it keeps
answering where the gob has stopped existing; it is `nil` only for a member the server has never placed.
It is a Position like any other, so `:durable()` is true wherever that ground is explored, and you can
save it, offset it and measure with it.

A member is interned on the gob id, so `:list()[1] == :get(<that id>)` and `seen[member] = true` work.
A stashed member goes `:exists() == false` when they leave the party and comes back to life if they
rejoin, because the id is what it holds.

## See also

- [Gob](gob.md) — what `member:gob()` hands back, and every read on it
- [`PartyMember`](types.md#partymember) — the snapshot shape `member:info()` returns
- [`hafen.kin`](kin.md) — the other roster, the one that does carry names
- [`hafen.fight`](fight.md) — combat, whose target resolves its gob the same way
