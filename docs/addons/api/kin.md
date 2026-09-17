# session:kin: The Kin Roster

One character's Kin window (its buddy list), read and managed through its [session](session.md). `session:kin()` is that character's roster.

```lua
local session = hafen.session():current()                      -- the character on screen
for _, kin in ipairs(session and session:kin():list() or {}) do
  hafen.log():write(kin:name() .. " [" .. kin:group() .. "]" .. (kin:online() and " online" or ""))
end
session:kin():get("Bob"):group(3):rename("Bobby")              -- protected, chainable
```

---

| Rule | Detail |
|---|---|
| Whose roster | Every character carries its own list and the server numbers each on its own: buddy id 7 on two characters is two people. `hafen.session():get("alt"):kin():find("Bob")` answers for that character, drawn or not. |
| One object | `session:kin()` is the same object every call, minted once per session, so a panel reading it every frame allocates nothing. A session the client no longer holds answers an empty roster. |
| Interned per addon | On character and id: `session:kin():get(7) == session:kin():get(7)`, `session:kin():list()[1] == session:kin():get(<that id>)`, `seen[kin] = true` works. The same number through two sessions is two objects. A `Kin` re-reads the roster on every call, so a stashed one tracks renames, regroups and online flips ([snapshots vs handles](conventions.md#snapshots-vs-handles)). |
| The array is a snapshot, the objects are live | `:list()` builds the array at call time. A kin added afterwards is not in it. Every `Kin` inside stays current while held. |
| No Kin window yet | Before the character is in the world, and briefly after a reload, the roster is empty and a name lookup answers `nil`. `:get(id)` hands back an object whose `:exists()` is `false`. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:kin():get(id)` | `Kin` | Unprotected | The kin with that buddy id. Always an object, even for an id that character does not have. |
| `session:kin():get(name)` | `Kin \| nil` | Unprotected | The kin with that exact, case-insensitive name. |
| `session:kin():list(filter)` | `Kin[]` | Unprotected | The roster in Kin-window sort order. A function filter receives a `Kin`, a string matches the name. |
| `session:kin():count(filter)` | `number` | Unprotected | How many match, without building the array. |
| `session:kin():find(filter)` | `Kin \| nil` | Unprotected | The first that matches the ordinary [filter](conventions.md#the-filter-argument): a partial name is `session:kin():find("Bo")`. |
| `kin:id()` | `number` | Unprotected | The buddy id. Answers even for a forgotten kin. |
| `kin:name()` | `string \| nil` | Unprotected | The nickname shown in the Kin window. |
| `kin:group()` | `number \| nil` | Unprotected | The kin's group, `0..254`. |
| `kin:color()` | [colour](shapes.md#colours) `\| nil` | Unprotected | The group's palette colour. `nil` for a group of 8 or more. |
| `kin:online()` | `boolean \| nil` | Unprotected | Whether the kin is online. |
| `kin:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The list row that draws them. `nil` when the Kin window is closed or the row is scrolled out of view. |
| `kin:exists()` | `boolean` | Unprotected | Whether this id is still on that character's roster. |
| `kin:gob()` | [`Gob`](gob.md) `\| nil` | Unprotected | The kin's gob in the world, their body when loaded. |
| `kin:info()` | [`KinEntry`](types/world.md#kinentry) `\| nil` | Unprotected | A plain-table snapshot, for logging and serialising. |

| Rule | Detail |
|---|---|
| Off the roster | Every reader answers `nil` except `:id()` and `:exists()`. No reader throws. None is protected. |
| `:get` addresses, `:find` searches | A number is a buddy id and always hands back an object. An id from a saved file can be held before the roster streams in. `:exists()` is the liveness test. A string is an exact, case-insensitive name and answers `nil` when nobody carries it. |
| Event | [`KinChanged`](event/bus/character.md#roster-quests-markers): a kin added, removed, renamed, regrouped or flipping online. |

### The row is one-way

`kin:widget()` crosses from a roster entry to the widget tree one way. The row reads, styles and draws like any [Widget](ui/widget.md). `:parent()` on it and on anything under it answers `nil`. The Kin window holds your character's hearth secret in an ordinary text entry two levels below, filled by the server. So the window is not walkable from inside. A field the client hides what you type into answers `nil` to `widget:text()`, `widget:value()` and `widget:info()` wherever you reached it from.

### Kin and gob

```lua
local session = hafen.session():current()
local kin = session:kin():get("Bob")
local kin_gob = kin and kin:gob()
if kin_gob then
  hafen.log():write(string.format("Bob is %s away", math.floor(kin_gob:distance() * 10 + 0.5) / 10))
  hafen.log():write(tostring(kin_gob:kin() == kin))                      -- true: the same interned Kin
end
```

| Rule | Detail |
|---|---|
| Server-side link | The game marks a kinned player's gob with their buddy id, so neither direction guesses from a name. `gob:kin()` is a single attribute read, answering that gob's own character's `Kin`. `kin:gob()` scans the objects that character has loaded: fine on demand, not for every kin every frame. |
| The hearth fire carries the mark | `gob:kin()` answers on it too, and an offline kin whose hearth fire is in view has a `kin:gob()`. `kin:gob()` prefers their body when loaded. Every gob marked as theirs is `session:world():gob():list(function(gob) return gob:kin() == kin end)`. |
| `nil` is ambiguous both ways | `kin:gob()` is `nil` for a kin offline, out of view or not yet streamed in. `gob:kin()` is `nil` for a gob not on that character's roster and for one that is not a player. |

## Write (protected)

The client sends only shapes a player could compose. Every verb on a `Kin` returns that `Kin`, so they chain.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:kin():add(secret)` | nothing | `kin.add` | Add a kin by the other player's hearth secret, the string the "Add kin" field takes. The server decides whether the secret names anyone, and the roster changes when the server answers, as a `KinChanged`. `local kin = session:kin():add(secret)` is `nil`. |
| `kin:rename(name)` | the `Kin` | `kin.rename` | Set the nickname: one typed line, 1 to 64 characters, no newline or tab. |
| `kin:group(group)` | the `Kin` | `kin.group` | Move the kin to group `0..254`. The write half of `kin:group()`. |
| `kin:endKin()` | the `Kin` | `kin.end` | End the kinship. The kin stays memorised in the list. Raises on an entry already un-kinned. |
| `kin:forget()` | the `Kin` | `kin.forget` | Drop a memorised, un-kinned kin from the list. Raises while the kinship is live. |

| Rule | Detail |
|---|---|
| Permission | Each key [declared](../guides/permissions.md) in your manifest, or the group `kin.*`. An undeclared key raises naming it ([the permission model](conventions.md#the-permission-model)). A key covers every character you address, drawn or not: one grant, not one per login. |
| Kept by the server | Disabling, reloading or uninstalling your addon undoes none of these, and neither does the session ending. Put one behind a choice the player made, not behind a load. |
| Groups go to 254, colours stop at 8 | The server accepts `0..254`. The client's palette holds eight colours. A group of 8 or more has no colour. `kin:color()` is `nil` and `kin:info()` carries no `color`. The client draws the kin in the ungrouped colour in the window and over their gob. `kin:group()` answers the true number. The window's colour row selects `0..7`, so a group past the palette is one only an addon sets, cleared by picking a colour. |
| Removing is two steps | `kin:endKin()` ends the kinship (memorised, still listed), then `kin:forget()` drops the entry. The client message is the same for both and the server picks the stage from the entry's state, so each verb refuses the other's step. Called on the wrong stage it raises naming the other, which also tells you the stage. |
| No add-by-name | Kinning needs a shared hearth secret, or the right-click "Add as kin" petal: [`session:world():click(gob, 3)`](world.md#write-protected) then [`session:flowermenu():select`](flowermenu.md#write-protected). |

---

## See Also

- [Gob](gob.md) — the object side of `kin:gob()`.
- [Permissions](../guides/permissions.md) — the keys these writes share, and what a key covers.
- [`KinEntry`](types/world.md#kinentry) — the snapshot shape `:info()` returns.
- [`session:party`](party.md) — the other roster, which carries no names.
- [Events](event/bus/character.md#roster-quests-markers) — `KinChanged`.
