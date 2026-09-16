# session:party: The Party Roster

The party one character is in, read through its [session](session.md); `session:party()` is that character's roster, in party sequence order, each member handing back its live [Gob](gob.md).

```lua
local session = hafen.session():current()                     -- the character on screen
for _, member in ipairs(session and session:party():list() or {}) do
  hafen.log():write(member:gob():name() .. ((session:party():leader() == member) and " (leader)" or ""))
end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:party():list(filter)` | `PartyMember[]` | Unprotected | Every member, in party sequence order. |
| `session:party():count(filter)` | `number` | Unprotected | How many match. |
| `session:party():find(filter)` | `PartyMember \| nil` | Unprotected | The first that matches. |
| `session:party():get(gob_id)` | `PartyMember \| nil` | Unprotected | One member, by gob id. |
| `session:party():leader()` | `PartyMember \| nil` | Unprotected | The member leading the party. |

| Rule | Detail |
|---|---|
| Whose party | Two of your characters in one party are two rosters: each holds the colours and positions the server sent that login. `hafen.session():get("alt"):party():leader()` answers for that character. |
| One object | `session:party()` is the same object every call, minted once per session. A character in no party and a session the client no longer holds both answer an empty roster. |
| Outside a party | `:list()` is empty, `:count()` is `0`, `:leader()` and `:get(id)` are `nil`; so is `:get(id)` for an id not in the party. Nothing throws; nothing is protected. |
| No write side | Joining and leaving is a menu action through [`session:menugrid`](menugrid.md#use-protected). |
| No event | Nothing on [the bus](event/bus/README.md) fires for a member joining, leaving, moving or being made leader: poll on a [timer](timer.md) or on the frame you already read. [`KinChanged`](event/bus/character.md#roster-quests-markers) belongs to [`session:kin`](kin.md). |
| `filter` | Must be a function. A string is refused: a member has no name to match it against. |

## A member

| Method | Returns | Permission | Description |
|---|---|---|---|
| `member:id()` | `number` | Unprotected | The member's gob id; always answers. |
| `member:gob()` | [Gob](gob.md) | Unprotected | The member's live object in that character's view; never `nil`. |
| `member:position()` | [Position](position.md) `\| nil` | Unprotected | Where they are. |
| `member:color()` | [colour](shapes.md#colours) `\| nil` | Unprotected | The party colour drawn for them; `nil` only once they are out of the party. |
| `member:exists()` | `boolean` | Unprotected | Whether they are still in the party; always answers. |
| `member:info()` | [`PartyMember`](types/world.md#partymember) `\| nil` | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| No name | The client is never sent one: a member is an id, a position, a colour and the leader flag. The name over their head is the creature's, `member:gob():name()`. That is why `:get` takes a gob id and a string filter is refused. |
| Leader | `session:party():leader() == member`; the members are interned, so the comparison is exact and there is no per-member flag. |
| `member:color()` | Always answers for a member in the party: the client starts a joiner black and paints them when the server names a colour, so black is "not coloured yet". `nil` is a stashed member who has left, which `member:exists()` asks directly. |
| The way back | [`gob:party()`](gob.md#read): the member standing at a gob in the party of the character that read it, or `nil`. |
| `member:gob()` is never `nil` | Like [`session:world():gob():get(id)`](gob.md): a member whose object that character's world does not hold answers a Gob whose `:exists()` is `false`. "Can that character see them" is `member:gob():exists()`. |
| The Gob answers in the login asked through | `member:gob():exists()` is that character's own line of sight; `member:gob():position()` is where they stand in that character's frame. Another of your characters is a separate reading through its own `session:party()`. |
| `member:position()` vs `member:gob():position()` | The member's own position is live while in view and last-known once they walk out, so it answers where the gob has stopped existing; `nil` only for a member the server has never placed. A Position like any other: `:durable()` where explored, savable, offsettable. |
| Live means interpolated | `member:position()` is the point the client draws them at this frame: the one read in the API that moves between server updates, where [`gob:position()`](gob.md#read) stands at the last place the server named. Read the member for a smooth line, the gob for the authoritative one. |
| Identity | Interned on character and gob id: `:list()[1] == :get(<that id>)` and `seen[member] = true` work; one id through two sessions is two members. A stashed member goes `:exists() == false` on leaving and comes back to life on rejoining, since the id is what it holds. |

---

## See Also

- [Gob](gob.md) — what `member:gob()` hands back, and every read on it.
- [`PartyMember`](types/world.md#partymember) — the snapshot shape `member:info()` returns.
- [`session:kin`](kin.md) — the other roster, the one that carries names.
- [`session:fight`](fight.md) — combat, whose target resolves its gob the same way.
