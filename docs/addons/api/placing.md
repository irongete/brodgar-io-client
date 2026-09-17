# Placing: The Ghost on the Cursor

A Placing is the translucent building on a character's cursor, between the server handing it over and the click that puts it down. It says what is about to be placed, where it sits now and which ground it will take. Read it through the [world](world.md) of the character whose cursor you mean. Nothing here writes or reaches the server.

```lua
local session = hafen.session():current()
local placing = session and session:world():placing()          -- nil when nothing is on the cursor
if placing then
  hafen.log():write("placing " .. (placing:name() or "not resolved yet"))
  local footprint_patch
  for _, ring in ipairs(placing:hitbox() or {}) do
    if footprint_patch then footprint_patch:piece():add(ring)   -- every further ring, into the one shape
    else footprint_patch = hafen.virtual():patch():add(ring, placing:position()):tint{60, 140, 255, 70} end
  end
end
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:world():placing()` | `Placing \| nil` | Unprotected | The ghost on that character's cursor. `nil` when it is placing nothing. |
| `placing:name()` | `string \| nil` | Unprotected | The resource being placed, spelled as [`gob:name()`](gob.md) spells it. |
| `placing:position()` | [Position](position.md) `\| nil` | Unprotected | Where the ghost is now. |
| `placing:facing()` | `number \| nil` | Unprotected | Its angle in radians, which the mouse wheel turns. |
| `placing:hitbox()` | `Position[][] \| nil` | Unprotected | [The ground it will stand on](#the-footprint). |
| `placing:exists()` | `boolean` | Unprotected | Whether that character is placing anything. |
| `placing:info()` | `table \| nil` | Unprotected | The snapshot: `{ name = string?, position = {gridId, x, y}?, facing = number, exists = true }`. |

| Rule | Detail |
|---|---|
| The client's own, not a game object | In no object cache, no server id, never sent: a preview drawn while you aim. Not in [`session:world():gob()`](world.md#objects), reached by no filter, fires no [`GobAdded`](event/bus/world.md#world). This verb is the only door. |
| Not a Gob | No health, state bytes, kin, click or game overlays. A verb it lacks raises naming `placing` and listing what it answers. |
| Reading grants nothing | Committing is [`session:world():place(position, angle)`](world.md#write-protected), protected under `world.place`. |
| Whose cursor | The server hands a placement to one character, so the read answers for the session asked, drawn or not. `hafen.session():get("alt"):world():placing()` is that character's ghost: real, readable and invisible, since the client draws one view at a time. [`place`](world.md#write-protected) refuses any session but the drawn one. |
| `nil` from a read | Nothing on the cursor, or the resource has not resolved yet. `:exists()` tells them apart. The server names the thing before the client has loaded it. An existing ghost can answer `nil` for name, footprint and place for a frame or two. Read again next frame. |
| `:info()` | The [one snapshot](conventions.md#objects-and-the-snapshot-hatch) this object carries. `position` is the durable `{gridId, x, y}` table a [Position](position.md) answers with. `name` is absent while the resource resolves, `position` absent where the ghost stands on ground that character cannot name a durable place on. `exists` is `true` whenever there is a snapshot. A snapshot of nothing is `nil`. No `hitbox` key, for the reason [`gob:info()`](gob.md) has none. |
| One Placing per addon per character | `session:world():placing() == session:world():placing()`, re-resolved on every verb. A kept handle answers about whatever is on that cursor now: put a cabin down, start a barrel, the handle reads the barrel. Ask `:name()` to know which. |

```lua
local placing = hafen.session():current():world():placing()
local first_name = placing and placing:name()
-- ...frames later...
if placing and placing:exists() and (placing:name() ~= first_name) then
  hafen.log():write("something else is on the cursor now")
end
```

## The footprint

`placing:hitbox()` answers in the shape, units and orientation of [`gob:hitbox()`](gob.md#the-ground-it-stands-on). It is an array of rings, each an array of [Positions](position.md), turned by the ghost's facing and placed where it sits.

| Rule | Detail |
|---|---|
| Into a patch unchanged | A ring goes into [`hafen.virtual():patch()`](virtual/patches.md) as a real object's does. A footprint of several rings is one patch of that many [pieces](virtual/pieces.md): one handle to move, one shape. |
| The resource's footprint | What the finished thing will occupy, not its `build` box (the clearance the client checks before letting you place). A ghost and the object it becomes wear the same shape. |
| `nil` | A resource carrying no footprint, and a resource that has not resolved. |
| The ghost moves. A patch does not follow | A patch anchors to a [Position or a Gob](virtual/README.md#the-anchor-is-an-argument), and a ghost is neither. Move it from your own code on [`Update`](event/bus/lifecycle.md), the step the cursor moves on: `patch:position(position)` where the cursor went, `patch:rotate(angle)` where the wheel turned it. |
| Write only what changed | Turning a patch re-carves ground already laid. Moving one re-cuts the tiles under it, the one operation that collection is not free at. A cursor sitting still should cost nothing. |

```lua
hafen.event():on("Update", function()
  local session = hafen.session():current()
  local placing = session and session:world():placing()
  if placing then
    local cursor_position = placing:position()
    -- move the patch you laid to cursor_position, and turn it by placing:facing()
  end
end)
```

---

## See Also

- [`session:world`](world.md) — the address this read goes through, and `place`, which commits one.
- [Gob](gob.md) — the game's own objects, and the footprint verb this one mirrors.
- [Patches](virtual/patches.md) — the collection a ring goes into unchanged.
- [Position](position.md) — what `:position()` hands back, and what a ring is made of.
- [Lifecycle events](event/bus/lifecycle.md) — `Update`, the step a cursor moves on.
