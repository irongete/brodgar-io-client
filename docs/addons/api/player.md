# session:player: One of Your Characters

`session:player()` is the Player object for the character one of your [sessions](session.md) is playing. It is the anchor for that character's own [Gob](gob.md), the walk order, and the cursor it carries an item on.

```lua
local session = hafen.session():current()       -- the character on screen
local my_gob = session and session:player():gob()   -- nil until that character's HUD is up
local position = my_gob and my_gob:position()
if position then hafen.log():write("standing on grid " .. position:info().gridId) end
```

---

| Rule | Detail |
|---|---|
| Nothing forwarded from the Gob | Position, health, movement and facing are read on `session:player():gob()`: one way to reach each. Player holds only what has no per-gob equivalent. |
| Which character | `session:player()` answers for the session you name and no other. `hafen.session():current():player()` is the one on screen, `hafen.session():get("alt"):player()` another. The same object every call, so a per-frame read allocates nothing. |
| The character's name | [`session:character()`](session.md#read), on the Session: one account plays one character at a time. There is no `:name()` here. |
| What belongs to the screen | Every read and the walk answer for the session named. [`hand:use`](#the-hand) is a gesture with the pointer and is the drawn character's. Projecting a place onto the screen is [`session:world():worldToScreen(position)`](world.md#the-screen-and-the-world). |
| The HUD bars | Hp, stamina and energy are a HUD slot the server fills: [`session:meter`](meter.md). |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:player():gob()` | [Gob](gob.md) `\| nil` | Unprotected | That character's own game object. `nil` before that character's HUD is up. |

| Rule | Detail |
|---|---|
| Before the world | The HUD is up before the world is, and the client mints the Gob on the id the HUD carries. From the moment `session:character()` answers you hold a Gob whose `:exists()` is `false` and whose `:position()` is `nil`. Ask `:exists()`, not `nil`. |
| Identity | `session:player():gob()` is the same object as `session:world():gob():get(<that character's id>)`, so `gob == session:player():gob()` tells "is this that character" with no id comparison. |
| No `exists()` or `id()` on Player | `session:player():gob()`, `nil` or not, and `gob:id()` answer both. |

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:player():move(position)` | the Player | `player.move` | Walk that character to a [Position](position.md): the click a left-click on that ground sends. |
| `session:player():hand():use(target, mods)` | the Hand | `player.hand.use` | Apply what the character carries to `target` ([The Hand](#the-hand)). |

### `session:player():move(position)`

```lua
-- send everyone else to where the character on screen is standing
local current_session = hafen.session():current()
local here = current_session:player():gob():position()
for _, session in ipairs(hafen.session():list()) do
  if session ~= current_session then session:player():move(here) end
end
```

| Rule | Detail |
|---|---|
| Reaches the session named, drawn or not | The one write on the API that does. An off-screen destination is fine. |
| Permission | `player.move` [declared](../guides/permissions.md) in your manifest. Without it the call raises naming the key before anything is sent. |
| `position` | Required. Anything that is not a Position raises, a plain `{x, y}` table and a [widget's pixel position](ui/widget.md) included. A Position that character cannot locate raises naming it. The destination is worked out against the addressed character's map, so another of them may reach it. Before that session is in the world it raises. Nothing is sent in any of those cases. |
| Your action handlers | An order to [`hafen.session():current()`](session.md) leaves by the door a real click does, so a `hafen.event():action():on("click", fn)` handler ([action streams](event/streams.md)) intercepts, rewrites or cancels it. An order to any other session bypasses that chain: a handler would read a destination named in another session's frame. |
| No `gob:move()` | The server accepts a walk command for the character's own body only. [`gob:moving()`](gob.md#read) is a property of any gob, not an order. |

> **A character not on screen takes walk orders and nothing else.** [Clicking an object](world.md#write-protected), [placing](world.md#write-protected) and an area select belong to the character on screen. So does [applying a held item](#the-hand). They raise naming `hafen.session():current()` for any other. An order carries a destination and never a target.

## The Hand

`session:player():hand()` is that character's cursor while it carries something, and `nil` whenever it does not: `if hand then hand:use(target) end` is the guard.

```lua
local session = hafen.session():current()
local hand = session:player():hand()
if hand then
  hafen.log():write("carrying " .. (hand:item():name() or hand:item():res() or "?"))
  hand:use(session:world():gob():nearest("terobjs/plants"))     -- apply it to that plant
end
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:player():hand()` | `Hand \| nil` | Unprotected | That character's cursor while something is on it. `nil` while empty. |
| `hand:item()` | [`Item`](ui/items.md#the-item-object) `\| nil` | Unprotected | What it is carrying. |
| `hand:info()` | `table` | Unprotected | A snapshot `{ item = <that Item's own shape> }`. `item` absent on an emptied cursor. |
| `hand:use(target, mods)` | the Hand | `player.hand.use` | Apply what it is carrying to `target`. |

| Rule | Detail |
|---|---|
| Per character | A character you are not looking at can be carrying something. Reading is addressed like everything here, `use` sends and is the drawn character's. |
| The permission key | The one nested key in the catalogue: `player.hand.use` grants it exactly, `player.*` grants it with `player.move`, `player.hand.*` grants the held-item gesture alone. |
| The reads | Unprotected, never throw. `session:player():hand()` is the same object every call while you keep the Player, so `==` works and there is nothing to release. It is the cursor, not a snapshot: one kept across a drop answers `nil` from `:item()`. Read it again rather than holding one. |
| Taking an item | The client destroys the container's item and builds a new one in the hand. The [`Item`](ui/items.md#the-item-object) you held goes stale. `hand:item()` is a different object. |

### `session:player():hand():use(target, mods)`

| `target` | Effect |
|---|---|
| An [`Item`](ui/items.md#the-item-object) | Apply onto that item, wherever it is. |
| A [Position](position.md) | Apply to the ground there. |
| A [Gob](gob.md) | Apply to that object (the waterskin onto the plant, not the dirt beside it). The message names the object by id. It carries the server's last point for it, not the client's guess at where a walking one has got to. |

| Rule | Detail |
|---|---|
| `mods` | Optional, default `0`: Shift = 1, Ctrl = 2, Alt = 4, added together. Anything that is not a [whole, finite number](conventions.md#a-number-is-finite-and-an-index-is-whole) raises naming the verb and the parameter. A numeric string is [still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number). |
| No target | Raises naming the types it takes. Activating what you hold is [`session:player():hand():item():use()`](ui/items.md#write-protected). |
| Refusals | An empty cursor. No map view. A session not on screen. A target that has gone: an item moved, used or consumed, or a gob that left view. Nothing is sent in any of those cases. |

---

## See Also

- [`hafen.session`](session.md) — the address this hangs off, and the character it is playing.
- [Gob](gob.md) — everything positional about that character.
- [Items](ui/items.md) — the Item the hand carries, and the verbs on one in a container.
- [`session:meter`](meter.md) — the HUD bars.
- [`session:char`](char.md) — attributes, skills and food.
- [`session:world`](world.md#the-screen-and-the-world) — `worldToScreen` and `screenToWorld`.
