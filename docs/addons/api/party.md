# session:party: Party Subsystem

Inspect active party members, party leadership, and shared health stats.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local party_subsystem = session:party()
local party_leader = party_subsystem:leader()

if party_leader then
  hafen.log():write("Party Leader: " .. (party_leader:name() or "Unknown"))
end

for _, party_member in ipairs(party_subsystem:list()) do
  local member_name = party_member:name() or "Member"
  local health_percentage = math.floor((party_member:hp() or 1.0) * 100)
  hafen.log():write(string.format("Member: %s (HP: %d%%)", member_name, health_percentage))
end
```

---

## Methods on `session:party()`

| Method | Returns | Description |
|---|---|---|
| `:list(filter?)` | `PartyMember[]` | List of all characters in the active party. |
| `:leader()` | `PartyMember \| nil` | The designated party leader. |
| `:count()` | `number` | Total number of members in the party. |

---

## Methods on `PartyMember`

| Method | Returns | Description |
|---|---|---|
| `:name()` | `string \| nil` | Character name. |
| `:hp()` | `number \| nil` | Shared health fraction `0.0..1.0`. |
| `:gob()` | `Gob \| nil` | Game object handle if in render distance. |
| `:info()` | `table` | Plain table snapshot `{ name, hp }`. |
