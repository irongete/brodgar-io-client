# session:kin: Kin & Friends Roster

Inspect kinship entries, friends lists, online statuses, and manage kin relationships.

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

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `Kin[]` | Unprotected | Array of all kin members matching the filter. |
| `:count(filter?)` | `[string \| function]` | `number` | Unprotected | Count of kin members matching the filter. |
| `:get(id_or_name)`| `number \| string` | `Kin \| nil` | Unprotected | Finds a specific kin entry by numerical ID or exact name. |
| `:find(filter)` | `string \| function` | `Kin \| nil` | Unprotected | First matching kin entry. |
| `:add(secret)` | `string` | `nil` | `kin.add` | Adds a player using their hearth secret string. |

---

## Methods on `Kin`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:id()` | None | `number` | Unprotected | Unique kin relation ID. |
| `:name()` | None | `string \| nil` | Unprotected | Custom or given name of the kin character. |
| `:online()` | None | `boolean` | Unprotected | `true` if the character is currently online in the game. |
| `:group()` | None | `number` | Unprotected | Kin group index (`0..254`). |
| `:color()` | None | `{r, g, b, a} \| nil`| Unprotected | Pin/name color if within the client's 8-color palette. |
| `:gob()` | None | `Gob \| nil` | Unprotected | Game object handle if the character body is in render distance. |
| `:widget()` | None | `Widget \| nil` | Unprotected | Roster row widget if the Kin window is currently open. |
| `:exists()` | None | `boolean` | Unprotected | `true` if relation is still active on the roster. |
| `:info()` | None | `table` | Unprotected | Plain table snapshot `{ id, name, group, color, online }`. |
| `:rename(new_name)`| `string` | `self` | `kin.rename` | Updates the local nickname for this kin. Chains. |
| `:group(group_number)`| `number`| `self` | `kin.group` | Sets the color grouping index (`0..254`). Chains. |
| `:endKin()` | None | `self` | `kin.end` | Ends kinship with this player. Chains. |
| `:forget()` | None | `self` | `kin.forget` | Removes player from the kin list. Chains. |
