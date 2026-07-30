# hafen.party — party roster

Read the current party. Members are ordered by their party sequence. A member snapshot carries the
member's gob `id`, so `hafen.gob(m.id)` gives you that member's [Gob](gob.md) — `:pos()` on it reads
their live position (nil while they are out of view).

| Function | Returns | Description |
|---|---|---|
| `hafen.party.members()` | [`PartyMember`](types.md#partymember)`[]` | all members, in sequence order |
| `hafen.party.leader()` | [`PartyMember`](types.md#partymember) \| nil | the party leader, or nil |
| `hafen.party.member(id)` | [`PartyMember`](types.md#partymember) \| nil | one member by gob id |

```lua
for i, m in ipairs(hafen.party.members()) do
  hafen.log("party" .. i .. (m.leader and " (leader)" or ""))
end
```

> A party member has **no name** field (a client/protocol limitation) — only `id`, position, colour,
> and leader flag. A member's position (`x`,`y`) is the live gob position if in view, otherwise the
> last-known one, and may be absent.
