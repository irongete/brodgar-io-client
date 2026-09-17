# hafen.ui: The Mouse

`hafen.ui():mouse()` is the pointer. It answers where it is, what is under it and which modifiers are down. It answers the cursor picture, and the object and ground it points at. It hands out the grab that makes a drag yours.

```lua
local mouse = hafen.ui():mouse()
hafen.log():write(("cursor at %d, %d"):format(mouse:x(), mouse:y()))
local hovered = mouse:over()
if hovered then hafen.log():write("over a " .. hovered:type()) end
if mouse:shift() then mouse:cursor("hand") else mouse:cursor(nil) end
```

There is one pointer. However many characters the client holds, there is one place the pointer is, one set of modifiers and one widget under it. So `:mouse()` hangs off `hafen.ui()` and names no [Session](../session.md). It is not a [Widget](widget.md).

---

## Read Methods

| Method | Returns | Permission | Description |
|---|---|---|---|
| `mouse:x()`, `mouse:y()` | `number` | Unprotected | The cursor position in root [design pixels](pixels.md). |
| `mouse:over()` | [`Widget`](widget.md) `\| nil` | Unprotected | The deepest widget under the cursor: exactly `hafen.ui():hit(mouse:x(), mouse:y())`. |
| `mouse:shift()`, `mouse:ctrl()`, `mouse:alt()` | `boolean` | Unprotected | Whether that modifier is down now. |
| `mouse:cursor()` | `string \| nil` | Unprotected | The cursor name your addon forced, or `nil` — [below](#the-cursor). |
| `mouse:pick()` | [`Gob`](../gob.md) `\| nil` | Unprotected | The object under the cursor — [below](#the-pick). |
| `mouse:ground()` | [`Position`](../position.md) `\| nil` | Unprotected | The place under the cursor, from the same pass. |
| `mouse:on("PickChanged", fn)` | `Sub` | Unprotected | `fn(gob)` when the object under the pointer changes, `nil` for nothing. The pointer's only key. Any other name raises. |
| `mouse:grab()` | `Grab` | Unprotected | Takes the pointer — [below](#the-grab). |

---

## The cursor

| Method | Returns | Permission | Description |
|---|---|---|---|
| `mouse:cursor(name)` | `self` | Unprotected | Forces the pointer's picture. A short name is one of the game's under `gfx/hud/curs` (`arw`, `hand`, `flag`, `wrench`, `study`, `dig`, `harvest`, `atk`). A name with a slash is a resource path as written, resolved in the whole resource pool [`g:resource`](drawing.md) draws from. Not a string: raises, naming both spellings. |
| `mouse:cursor(nil)` | `self` | Unprotected | Drops your own override, never another addon's. |
| `mouse:cursor()` | `string \| nil` | Unprotected | Your forced name. `nil` while you force nothing, whoever else may. |

| Rule | Detail |
|---|---|
| It wins over everything | A forced cursor shows wherever the pointer is, over a widget with a cursor of its own included. |
| One override | The last addon to force one holds it. |
| A name that resolves to nothing | Puts the pointer back and writes a log line. A name still loading changes nothing for that frame. |
| Teardown | `:reload` and disable drop it. |

---

## The pick

`mouse:over()` answers which widget is under the pointer. `mouse:pick()` answers which object in the world is. It goes through the client's own pick pass (ID-buffer picking, the pass a right-click goes through), so it never disagrees with what a click reaches.

```lua
local subscription = mouse:on("PickChanged", function(gob)     -- holding this is what runs the pass
  hafen.log():write(gob and (gob:name() or "?") or "nothing")
end)
mouse:pick()                                                    -- the same answer, on demand
mouse:ground()                                                  -- the place under the pointer
subscription:off()                                              -- the pass stops
```

| Rule | Detail |
|---|---|
| The subscription arms the pass | A pick is a render pass and a GPU readback, so nothing runs while nobody listens and `mouse:pick()` answers `nil` then. The last `sub:off()` stops it. So does `:reload`. |
| One pass, two answers | The ground point and the object come from the same submission and the same instant. `mouse:ground()` costs nothing beyond `mouse:pick()`, and its Position goes into [`s:world():tile(p)`](../world.md#terrain-and-coordinates), `:height(p)` and `:grid():at(p)`. |
| Only the object is an event | `mouse:ground()` moves with every pixel. Read it where you use it. `PickChanged` fires on the object. |
| `nil` from `mouse:pick()` | The pointer is on nothing pickable: bare ground, the sky, a window, the minimap. `mouse:ground()` tells bare ground (a place) from the rest (`nil`). A pointer off the map view publishes `nil` on both. |
| One frame behind | The readback is answered on its own thread and published, as [`screenToWorld`](../world.md#the-screen-and-the-world) is. One pick in flight at a time: re-run when the pointer moves, a few times a second while it holds still. |
| Whose Gob | The drawn character's, like every read against the one screen. No permission: the client looks at its own scene. |

---

## The grab

A modal press-drag-release capture. While held, the map view neither pans nor clicks, and no other widget sees the pointer. Every move reaches you wherever the cursor goes, off-window included. It is the capture [`widget:draggable(h)`](native.md#letting-the-user-drag-it-unprotected) and [`widget:resizable(h)`](native.md#letting-the-user-resize-it-unprotected) take for you. Take it yourself for a drag-on-the-ground tool.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `mouse:grab()` | `Grab` | Unprotected | Takes the pointer at once. No arguments. |
| `grab:on("Move", fn)` | `Sub` | Unprotected | `fn(event)` on every move: `event:x()`, `event:y()` in root design pixels. `event:shift()`, `event:ctrl()`, `event:alt()`. |
| `grab:on("Up", fn)` | `Sub` | Unprotected | `fn(event)` once, on release, with `event:button()` as well. The grab ends. |
| `grab:release()` | `self` | Unprotected | Ends it early. A grab still open at `:reload` is released by teardown. |

Any key but `Move` and `Up` raises.

```lua
local ghost = hafen.virtual():ghost():add("gfx/terobjs/arch/logcabin", hafen.session():current():player():gob():position())
local grab = hafen.ui():mouse():grab()
grab:on("Move", function(event)
  local fine = event:shift()                                     -- Shift picks the fine grid
  local world = hafen.session():current():world()                -- the pointer is the screen's
  world:screenToWorld({x = event:x(), y = event:y()}, function(position)   -- a frame later
    if position then ghost:position(world:snapPlace(position, fine)) end
  end)
end)
grab:on("Up", function(event) hafen.log():write("dropped with button " .. event:button()) end)
```

`event:x()`/`event:y()` go into [`screenToWorld`](../world.md#the-screen-and-the-world) as they come: root design pixels are the space that door takes, wherever the map view sits. Coalesce the raycasts: `screenToWorld` answers a frame later and a move fires every frame.

---

## See Also

- [Pixels](pixels.md) — the unit the pointer, the hit test and a widget's box share.
- [Widget](widget.md) — what `mouse:over()` hands you.
- [Selectors](selectors.md#hit-testing) — hit-testing and naming the widget under a point.
- [Native](native.md#letting-the-user-drag-it-unprotected) — the same capture, wrapped as a draggable or resizable widget.
- [Screen to world](../world.md#the-screen-and-the-world) — turning a drag into a place on the ground.
