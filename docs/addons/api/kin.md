# session:kin: Kin & Village Roster

Inspect kinship entries, friends lists, village members, online statuses, and manage kin relationships.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- List all online kin members
for _, kin_member in ipairs(session:kin():list()) do
  if kin_member:online() then
    local character_name = kin_member:name() or "Unknown"
    local kin_group = kin_member:group() or 0
    hafen.log():write(string.format("Online Kin: %s (Group %d)", character_name, kin_group))
  end
end
```

---

## Methods on `session:kin()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Kin[]` | Array of all kin members matching the filter. |
| `:count(filter?)` | `[string \| function]` | `number` | Count of kin members matching the filter. |
| `:get(kin_id)` | `number` | `Kin \| nil` | Finds a specific kin entry by ID. |
| `:find(filter)` | `string \| function` | `Kin \| nil` | First matching kin entry. |

---

## Methods on `Kin`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Unique kin relation ID. |
| `:name()` | `string \| nil` | Custom or given name of the kin character. |
| `:online()` | `boolean` | `true` if the character is currently online in the game. |
| `:group()` | `number` | Kin group index (color classification). |
| `:village()` | `string \| nil` | Village name if affiliated. |
| `:gob()` | `Gob \| nil` | Game object handle if the character is in render distance. |
| `:info()` | `table` | Plain table snapshot `{ id, name, online, group, village }`. |

---

## Protected Actions

Managing kin relationships requires the corresponding permission keys in `manifest.json` (or `kin.*`):

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `session:kin():add(player_gob)` | `Gob` | `kin.add` | Sends or confirms a kinship request. |
| `kin_member:rename(new_name)` | `string` | `kin.rename` | Updates the local nickname for this kin. |
| `kin_member:group(group_number)` | `number` | `kin.group` | Sets the color grouping index for this kin. |
| `kin_member:endKin()` | None | `kin.end` | Ends kinship with this player. |
| `kin_member:forget()` | None | `kin.forget` | Removes player from the kin list. |
