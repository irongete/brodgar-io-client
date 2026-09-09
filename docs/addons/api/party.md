# session:party: the party roster

Read the party one character is in. You reach it through the [session](session.md) whose character you
mean, and `s:party()` **is** that character's roster: the members come back in party sequence order, and
each one hands back its live [Gob](gob.md).

```lua
local s = hafen.session():current()                     -- the character on screen
for _, m in ipairs(s and s:party():list() or {}) do
  hafen.log():write(m:gob():name() .. ((s:party():leader() == m) and " (leader)" or ""))
end
```

## Whose party it is

A party belongs to the character that is in it. Two of your characters in one party are two rosters, not
one seen twice: each holds the colours and the positions the server sent *that* login, so a member is read
through the character whose party it is.

```lua
hafen.session():current():party():count()       -- the party of the character on screen
hafen.session():get("alt"):party():leader()     -- that character's, while you watch someone else
```

`s:party()` is the same object every call, minted once for that session. A character in no party has an
empty roster, and so does a session the client no longer holds — neither raises.

## Read

| Call | Returns | Description |
|---|---|---|
| `s:party():list(filter)` | `PartyMember[]` | every member, in party sequence order |
| `s:party():count(filter)` | number | how many match |
| `s:party():find(filter)` | `PartyMember` \| nil | the first that matches |
| `s:party():get(gobId)` | `PartyMember` \| nil | one member, by gob id |
| `s:party():leader()` | `PartyMember` \| nil | the member leading the party |

Outside a party `:list()` is an empty array, `:count()` is `0`, and `:leader()` and `:get(id)` are `nil`;
so is `:get(id)` for an id that is not in that party. Nothing here throws and nothing is protected. There
is no write side: joining and leaving a party is a menu action, reachable through
[`session:menugrid`](menugrid.md#use-protected).

**The party has no event.** Nothing on [the bus](event/bus/README.md) fires for a member joining, leaving,
moving or being made leader, so a roster an addon tracks is one it polls — on a
[timer](timer.md), or on the frame you already read. The other roster does have one:
[`KinChanged`](event/bus/character.md#roster-quests-markers) is [`session:kin`](kin.md), not this.

The [filter](conventions.md#the-filter-argument) has to be a function here. A string is refused, because
a party member has nothing to match it against — see below.

## A member

| Method | Returns | Description |
|---|---|---|
| `member:id()` | number | the member's gob id — always answers |
| `member:gob()` | [Gob](gob.md) | the member's live object, in that character's view — never `nil` |
| `member:position()` | [Position](position.md) \| nil | where they are |
| `member:color()` | [colour](shapes.md#colours) \| nil | the party colour drawn for them — `nil` only once they are out of the party |
| — | — | whether they lead is `s:party():leader() == member`: the members are interned, so the comparison is exact and there is no per-member flag |
| `member:exists()` | boolean | whether they are still in the party — always answers |
| `member:info()` | [`PartyMember`](types/world.md#partymember) \| nil | a plain-table **snapshot** |

> A party member has **no name**: the client is never sent one. A member is an id, a position, a colour
> and the leader flag. The name over their head belongs to the creature, so it is `member:gob():name()`.

`member:color()` always answers for a member who is in the party: the client starts a joiner **black** and
paints them the moment the server names a colour, so black is "not coloured yet" and there is no `nil` to
guard. The `nil` is the other question — a member you stashed and who has since left, which
`member:exists()` asks directly.

That is also why `:get` takes a gob id and a string filter is refused: a name is the one thing the roster
cannot match on, and matching nothing quietly would be worse than saying so.

[`gob:party()`](gob.md#read) is the way back: the member standing at a gob, in the party of the character
that read it, or `nil`. The kin pair went both ways and this one did not.

`member:gob()` is never `nil`, exactly like [`s:world():gob():get(id)`](gob.md) — a member whose object
that character's world does not currently hold answers a Gob whose `:exists()` is `false`. So the way to
ask whether that character can see someone is `member:gob():exists()`, not a `nil` test.

The Gob **answers in the login you asked through** — the character whose party this is — so
`member:gob():exists()` is that character's own line of sight, and `member:gob():position()` is where they
stand in that character's frame. Another of your characters looking at the same person is a separate
reading, reached through its own `s:party()`.

`member:position()` and `member:gob():position()` are **not** the same read. The member's own position is
the live one while they are in view and the **last-known** one once they walk out of it, so it keeps
answering where the gob has stopped existing; it is `nil` only for a member the server has never placed. It
is a Position like any other, so `:durable()` is true wherever that ground is explored, and you can save
it, offset it and measure with it.

And "live" means **interpolated**: `member:position()` is the point the client is drawing them at this
frame, so it is the one read in this API that moves between server updates, where
[`gob:position()`](gob.md#read) stands still at the last place the server named. Read the member for a
smooth line and the gob for the authoritative one.

A member is interned on the character and the gob id, so `:list()[1] == :get(<that id>)` and
`seen[member] = true` work, while one id reached through two sessions gives you two members — which is
right, because each answers with what its own login was told. A stashed member goes `:exists() == false`
when they leave the party and comes back to life if they rejoin, because the id is what it holds.

## See also

- [Gob](gob.md) — what `member:gob()` hands back, and every read on it
- [`PartyMember`](types/world.md#partymember) — the snapshot shape `member:info()` returns
- [`session:kin`](kin.md) — the other roster, the one that does carry names
- [`session:fight`](fight.md) — combat, whose target resolves its gob the same way
