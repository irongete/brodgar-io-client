# hafen.ui: the mouse

`hafen.ui():mouse()` is the pointer — where it is, what is under it, which modifiers are down, and the
capture that makes a drag yours. It is not a [Widget](widget.md): `:mouse()` hands back the pointer
**itself**, the same shape a [session's Player](../player.md) has.

**There is one pointer.** A Player is one character's and is reached through that character's
[Session](../session.md); the cursor is not. However many characters the client holds, there is one
place the pointer is, one set of modifier keys held over it and one widget under it — the screen's —
so `:mouse()` names no character and hangs off `hafen.ui()` alone.

```lua
local m = hafen.ui():mouse()
m:x()  m:y()                  -- where the cursor is, in root coords (design pixels)
m:over()                      -- the deepest Widget under it, or nil
m:shift() m:ctrl() m:alt()    -- the live modifier keys
m:cursor("hand")              -- the pointer's picture, while a mode of yours wants a click
```

## Read

Reading the mouse is unprotected client-side data.

| Verb | Returns |
|---|---|
| `m:x()` / `m:y()` | the cursor position, in root coords — [design pixels](pixels.md), like every other coordinate here |
| `m:over()` | the deepest [Widget](widget.md#read) under the cursor, or `nil` |
| `m:shift()` / `m:ctrl()` / `m:alt()` | whether that modifier key is down, right now |
| `m:cursor()` | the cursor name YOU have forced, or `nil` — see [the cursor](#the-cursor) |
| `m:grab()` | take the pointer — see [the grab](#the-grab) |

`hafen.ui():hit(x, y)` still answers for an arbitrary point; `m:over()` is exactly `hafen.ui():hit(m:x(),
m:y())`, kept as one call for the case every addon reaches for. The two are the same widget by
construction, not by coincidence: `m:over()` answers for the point this object *reports*, so the pair you
read and the pair you hit-test with are one pair.

## The cursor

`m:cursor(name)` forces the pointer's picture, and `m:cursor(nil)` puts it back. Reach for it when a mode of
yours is waiting for the user to click something: the pointer is where they are looking, so it is where a
mode says what it wants.

```lua
local m = hafen.ui():mouse()
m:cursor("hand")                    -- ...the user now sees a hand wherever they point
m:cursor()                          -- "hand"
m:cursor(nil)                       -- and back to whatever the client would have drawn
```

A short name is one of the client's own under `gfx/hud/curs` — `arw`, `hand`, `flag`, `wrench` — and a name
with a slash in it is a resource path taken as written. Anything that is not a string raises, naming both
spellings.

- **It wins over everything.** A forced cursor is the answer wherever the pointer is, over a widget with a
  cursor of its own included — a targeting mode that lost its picture over the inventory would be saying
  what it wants everywhere except where the user is aiming.
- **There is one pointer, so there is one override**, and the last addon to force one holds it. `m:cursor()`
  answers **yours**: `nil` while you are forcing nothing, whoever else may be. `m:cursor(nil)` drops your own
  and never somebody else's.
- **A name that resolves to nothing puts the pointer back** and writes a line, rather than leaving an
  invisible cursor on screen. A name still loading changes nothing for that frame.
- **Your teardown drops it.** A `:reload` or disabling your addon in the middle of a mode leaves the user
  with an ordinary pointer, not a hand nothing answers.

## The grab

A modal press-drag-release capture: while it is held the map view neither pans nor clicks, so a drag
leaves the camera put. It is what a drag-on-the-ground tool is built on, and it is the same capture
[`w:draggable(h)`](native.md#letting-the-user-drag-it-unprotected) and
[`w:resizable(h)`](native.md#letting-the-user-resize-it-unprotected) take for you — reach for the grab when
you want the whole press-move-release loop in your own hands, and for those two when what you want is a
widget the user can move or size.

```lua
local g = hafen.ui():mouse():grab()   -- bare: from here the pointer is yours

g:on("Move", function(ev) end)        -- ev:x() ev:y() in root coords; ev:shift() ev:ctrl() ev:alt()
g:on("Up",   function(ev) end)        -- …plus ev:button(); fires once and auto-releases

g:release()                           -- hand it back early
```

`:grab()` takes no arguments and hands back an emitter with the same `:on(key, fn)`/`sub:off()` shape as
everything else, closed to `Move` and `Up`. The instant you take it: every move reaches you wherever the
cursor goes, even off-window; the map stops panning; clicks stop reaching the game; and no other widget
sees the pointer. `g:release()` ends it early and hands the grab back, and `Up` ends it automatically. A grab still open when your
addon reloads is released by teardown.

```lua
local g = hafen.ui():mouse():grab()

g:on("Move", function(ev)
  local fine = ev:shift()                                  -- SHIFT picks the fine grid
  local w = hafen.session():current():world()              -- the pointer is the screen's
  w:screenToWorld({x = ev:x(), y = ev:y()}, function(p)    -- p is a Position, a frame later
    if p then ghost:position(w:snapPlace(p, fine)) end
  end)
end)

g:on("Up", function(ev) hafen.log():write("dropped with button " .. ev:button()) end)
```

Pair it with [`s:world():screenToWorld`](../world.md#the-screen-and-the-world) and
`snapPlace` to drag something along the ground.

**`ev:x()`/`ev:y()` go into `screenToWorld` as they come** — a grab reports the pointer in root
[design pixels](pixels.md), which is the space that door takes, so there is nothing to convert and nothing
to add for where the map view happens to sit; `{x = ev:x(), y = ev:y()}` is the whole of the plumbing. Coalesce the raycasts, though: `screenToWorld` answers a
frame later, and a move fires every frame.

## See also

- [the pixel](pixels.md) — the unit the pointer, the hit test and a widget's box share
- [the Widget object](widget.md) — what `m:over()` hands you, and the keys you can subscribe on it
- [selectors](selectors.md#hit-testing) — hit-testing, and naming the widget under a point
- [native](native.md#letting-the-user-drag-it-unprotected) — the same capture, wrapped as a widget the
  user can drag and size
- [screen to world](../world.md#screen-to-world-and-placement-snapping) — turning a drag into a place
  on the ground
