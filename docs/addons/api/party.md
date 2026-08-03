# hafen.party: the party roster

Read the party you are in. Members come back in party sequence order, and each snapshot carries the
member's gob `id` — so `hafen.gob(m.id)` gives you that member's live [Gob](gob.md).

```lua
for i, m in ipairs(hafen.party.members()) do
  hafen.log("party" .. i .. (m.leader and " (leader)" or ""))
end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.party.members()` | [`PartyMember`](types.md#partymember)`[]` | every member, in sequence order |
| `hafen.party.leader()` | [`PartyMember`](types.md#partymember) \| nil | the party leader |
| `hafen.party.member(id)` | [`PartyMember`](types.md#partymember) \| nil | one member, by gob id |

Outside a party, `members()` is an empty array and the other two are `nil`; so is `member(id)` for an id
that is not in the party. Nothing here throws and nothing is gated. There is no write side: joining and
leaving a party is a menu action, reachable through [`hafen.act`](act.md).

> A party member has **no name**: the client is not sent one. A snapshot carries `id`, position, colour
> and the leader flag, and nothing else.

A member's `x` and `y` are the live gob position while they are in view and the last-known one
otherwise, and they may be absent entirely. For a position you can rely on, go through
`hafen.gob(m.id)` and check `:exists()`.

## See also

- [`PartyMember`](types.md#partymember) — the snapshot shape all three readers return
- [`hafen.gob`](gob.md) — turning a member id into a live object
- [`hafen.kin`](kin.md) — the roster that does carry names
