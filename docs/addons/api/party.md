# session:party: Party Subsystem

Inspect active party members, party leadership, member positions, and party colors.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local party_subsystem = session:party()
local party_leader = party_subsystem:leader()

if party_leader then
  local leader_gob = party_leader:gob()
  hafen.log():write("Party Leader: " .. (leader_gob and leader_gob:name() or "Unknown"))
end

for _, party_member in ipairs(party_subsystem:list()) do
  local member_gob = party_member:gob()
  local member_name = member_gob and member_gob:name() or "Member"
  local is_leader = (party_leader == party_member)
  hafen.log():write(string.format("Member: %s (Leader: %s)", member_name, tostring(is_leader)))
end
```

---

## Methods on `session:party()`

| Method | Returns | Description |
|---|---|---|
| `:list(filter?)` | `PartyMember[]` | List of all characters in the active party. |
| `:get(gob_id)` | `PartyMember \| nil` | Finds a party member by character Game Object ID. |
| `:leader()` | `PartyMember \| nil` | The designated party leader. |
| `:count()` | `number` | Total count of members in the party. |

---

## Methods on `PartyMember`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Character Game Object ID of the party member. |
| `:gob()` | `Gob` | Character Game Object in this session's world view. |
| `:position()` | `Position \| nil` | Current or last known world position. |
| `:color()` | `{r, g, b, a} \| nil` | Distinct party indicator color assigned to this member. |
| `:exists()` | `boolean` | `true` if this member is still in the active party. |
| `:info()` | `table \| nil` | Plain table snapshot `{ id, x, y, color, leader }`. |
