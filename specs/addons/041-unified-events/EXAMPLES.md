# 041 — the complete reactive surface, before → after

> **What this is.** Every emitter, every key, every payload, with its spelling before and after this
> feature, plus worked examples of the finished API. It does three jobs: the **design the maintainer
> validates before implementation**, the **completeness checklist** for `plan.md`/`tasks.md`, and the
> **source of the `Retired` table** that makes an old spelling throw naming its replacement.
>
> **It is also the anti-hallucination reference.** A fresh `/implement` session reads this file and
> writes the spellings in it — not a plausible-looking key it inferred. Every name below was read off
> the source or the shipped docs, never recalled: the 26 bus names are the exact strings the engine
> fires, the 8 own-widget callbacks are `AddonWidget`'s own slot array, and the control capabilities
> are `Controls.java`'s interfaces.
>
> **What this is NOT: a contract.** `AREA.md` is explicit — the docs tier IS the contract. This file
> is measured against while the feature is built and then frozen as history, exactly as
> [039's `API.md`](../039-uniform-api/API.md) was. `docs/addons/api/` stays the only published surface.

## The rule

```lua
local sub = X:on(key, fn)     -- N subscribers, registration order within an addon
sub:off()                     -- idempotent
```

> **¿Tienes el objeto? `obj:on(...)`. ¿No? `hafen.event()`.**

---

# 1. Key catalogue

## 1.1 Every widget — the universal keys

Answered by **any** widget handle: one you built, one you found with a selector, one handed to you by
an event. This is the half that does not exist today for native widgets.

| key | handler receives | cancelable | before |
|---|---|---|---|
| `MouseDown` | `ev` | ✅ | `w:onClick(fn)` *(own)* / `hafen.hook():input(t, "mousedown", fn)` *(3 tokens)* |
| `MouseUp` | `ev` | ✅ | `w:onMouseUp(fn)` / `hafen.hook():input(t, "mouseup", fn)` |
| `MouseMove` | `ev` | ✅ | `w:onMouseMove(fn)` / `hafen.hook():input(t, "mousemove", fn)` |
| `Wheel` | `ev` | ✅ | `w:onWheel(fn)` / `hafen.hook():input(t, "mousewheel", fn)` |
| `Destroy` | — | ❌ | `w:onDestroy(fn)` |

`ev` for the four input keys answers `:x()`, `:y()` (widget-local pixels), `:button()` (1 left /
3 right, on down and up only), `:amount()` (wheel only) and `:preventDefault()`.

> **`onClick` is renamed, not merely re-spelled.** It binds `MouseDownEvent` today — the name has
> always said the wrong thing. `MouseDown` is what it is.

## 1.2 Containers

Any widget whose items are readable (`w:items()` — an inventory, an equipment slot, a chest, a
cupboard, the study).

| key | handler receives | cancelable | before |
|---|---|---|---|
| `ItemAdded` | `Item` | ❌ | `w:onItemAdded(fn)` |
| `ItemRemoved` | `Item` | ❌ | `w:onItemRemoved(fn)` |

## 1.3 A widget of your own (`hafen.ui():widget()` / `:window()`)

The four extra slots an `AddonWidget` carries, on top of §1.1.

| key | handler receives | cancelable | before |
|---|---|---|---|
| `Draw` | `ev` — `:g()` `:w()` `:h()` | ❌ | `w:onDraw(fn)` |
| `Tick` | `dt` | ❌ | `w:onTick(fn)` |
| `Drop` | `ev` | ✅ | `w:onDrop(fn)` |
| `Close` | — | ❌ | `w:onClose(fn)` |

`Draw` says three things, so it says them in an object; `Tick` says one, so it says it directly. That
is the whole rule (§2), applied — not a judgement about which of them is "an event".

## 1.4 Controls — one key per capability

The 16 builders of [040](../040-ui-controls/). A control is a Widget, so it answers §1.1 as well.

| builder | engine class | key | handler receives | before |
|---|---|---|---|---|
| `button()` | `Button` / `IButton` | `Pressed` | — | `:onPress(fn)` |
| `check()` | `CheckBox` / `ICheckBox` | `Changed` | `v` (boolean) | `:onChange(fn)` |
| `radio()` | `RadioGroup` composite | `Changed` | `v` (label) | `:onChange(fn)` |
| `slider()` | `HSlider` | `Changed` | `ev` — `:value()` `:final()` | `:onChange(fn)` |
| `scrollbar()` | `Scrollbar` | `Changed` | `v` | `:onChange(fn)` |
| `scroll()` | `Scrollport` composite | `Changed` *(on its bar)* | `v` | `:onChange(fn)` |
| `entry()` | `TextEntry` | `Changed` | `s` (per keystroke) | `:onChange(fn)` |
| `entry()` | `TextEntry` | `Submitted` | `s` (on Enter) | `:onSubmit(fn)` |
| `list()` | `SListBox` | `Changed` | the picked row | `:onChange(fn)` |
| `dropdown()` | `SDropBox` | `Changed` | the picked row | `:onChange(fn)` |
| `menu()` | `SListMenu` | `Selected` | the picked row | `:onSelect(fn)` |
| `grid()` | `GridList` | `Cell` | `ev` — `:g()` `:item()` `:w()` `:h()` | `:onCell(fn)` |
| `label()` `image()` `separator()` `progress()` `table()` | — | *(none)* | — | — |

`Changed` fires **from a real user interaction only** — a programmatic `:value(v)` never re-enters
it ([D-153](../decisions/architecture-api.md), 040.4). Unchanged by this feature.

Only two rows carry an object, and both for the same reason: a slider's `Changed` has two things to
say (the value and whether the drag ended) and `Cell` has four. Every other `Changed` has one.

## 1.5 The bus — `hafen.event()`

All 26, read off the engine's own fire sites. **No new events, and — because PascalCase is already
the bus's own spelling — 22 of the 26 keys below are not a rename at all**: they are the exact
string the corpus calls today. Only the four lifecycle keys change, by dropping the redundant `On`
(`:on` already says *on*).

| group | key | payload | before |
|---|---|---|---|
| lifecycle | `Load` | — | `OnLoad` |
| | `EnterWorld` | — | `OnEnterWorld` |
| | `Update` | `dt` | `OnUpdate` |
| | `Disable` | — | `OnDisable` |
| world | `GobAdded` | `Gob` | *(unchanged)* |
| | `GobRemoved` | `Gob` *(only `:id()` answers)* | *(unchanged)* |
| | `GobOverlayAdded` | `ev` — `:gob()` `:key()` `:native()` | *(unchanged spelling; payload becomes an object)* |
| | `GobOverlayRemoved` | `ev` — `:gob()` `:key()` `:native()` | *(unchanged spelling; payload becomes an object)* |
| character | `MeterAdded` | `Meter` | *(unchanged)* |
| | `MeterRemoved` | `Meter` | *(unchanged)* |
| | `MeterChanged` | `Meter` | *(unchanged)* |
| | `BuffAdded` | `Buff` | *(unchanged)* |
| | `BuffRemoved` | `Buff` | *(unchanged)* |
| | `BuffChanged` | `Buff` | *(unchanged)* |
| | `FepChanged` | `Food` | *(unchanged)* |
| | `StudyChanged` | `StudySlot[]` | *(unchanged)* |
| | `EquipChanged` | `Item[]` | *(unchanged)* |
| | `ActionbarChanged` | `Slot` | *(unchanged)* |
| | `WoundChanged` | `Wound[]` | *(unchanged)* |
| roster | `KinChanged` | `Kin[]` | *(unchanged)* |
| | `QuestAdded` | `Quest` | *(unchanged)* |
| | `QuestDone` | `Quest` | *(unchanged)* |
| | `MarkersChanged` | `n` (number) | *(unchanged spelling; payload loses its wrapper)* |
| own entities | `GhostClicked` | `ev` — `:ghost()` `:button()` `:x()` `:y()` | *(unchanged spelling; payload becomes an object)* |
| | `SpriteClicked` | `ev` — `:sprite()` `:button()` `:x()` `:y()` | *(unchanged spelling; payload becomes an object)* |
| | `ObjectClicked` | `ev` — `:object()` `:button()` `:x()` `:y()` | *(unchanged spelling; payload becomes an object)* |

> **The composite payloads follow the same rule as everything else, independent of the key's own
> spelling.** These five are plain Lua tables with fields today (`{gob, key, native}`,
> `{ghost, button, x, y}`, `{count = n}`) — shapes that predate 039 and that 039 did not reach, since
> it converted the *reads* and left the reactive half alone. The four that say several things become
> event objects with verbs; `MarkersChanged` says one thing and so hands over the number itself,
> losing the wrapper it never needed. The entity payloads (`Gob`, `Buff`, `Meter`, `Item`, `Kin`,
> `Quest`, `Slot`, `Wound`, `StudySlot`, `Food`) were already single objects and are untouched.
>
> **Measured, not assumed**: of the corpus's 88 `hafen.event():on(...)` call sites, 44 already name
> one of the 22 unchanged keys above and need no text edit at all for the rename; the other 44 are on
> the four lifecycle keys and lose their `On`.

> **`ChatMessage` does not exist.** `STATE.md` lists it among the bus events and
> [design/09](../design/09-events-catalog.md) has it as a planned Phase-2 one-liner, but nothing
> fires it and no addon uses it. It is **not** in this catalogue and `STATE.md`'s line is corrected
> at the close. (Adding it is a new event, which §4 of the spec puts out of scope.)

## 1.6 The two message streams

Open key sets — any string is accepted ([D-129](../decisions/architecture-api.md)).

```lua
hafen.event():action():on(msg, fn)     -- outbound wdgmsg, before it reaches the server
hafen.event():message():on(msg, fn)    -- inbound uimsg, before the widget applies it
```

| | `action` | `message` |
|---|---|---|
| before | `hafen.hook():action(msg, fn)` | `hafen.hook():message(msg, fn)` |
| `ev:msg()` | the message name | the message name |
| `ev:sender()` / `ev:target()` | **the sending Widget** | **the receiving Widget** |
| `ev:args()` | 1-based arg array | 1-based arg array |
| `ev:preventDefault()` | do not send | swallow the update |
| `ev:resend()` | re-send verbatim, bypassing hooks | — |
| `ev:send(t)` | send new args | — |
| `ev:rewrite(t)` | — | apply with new args |

Common `action` names: `click` · `itemact` · `drop` · `place` · `sel` · `act` · `use` · `take` ·
`transfer` · `iact` · `invxf` · `cl`. Common `message` names: `set` · `add` · `del`.

## 1.7 The mouse — `hafen.ui():mouse()`

`hafen.ui():mouse()` stops being a `{x=, y=}` table read and becomes **the pointer entity** — the
same shape `hafen.player()` has, where the section's one thing *is* the object.

```lua
local m = hafen.ui():mouse()

m:x()  m:y()          -- where the cursor is, in root coords (UI.mc)
m:over()              -- the deepest Widget under it, or nil
m:shift() m:ctrl() m:alt()   -- the live modifier keys (UI.modflags)
m:grab()              -- take the pointer; see below
```

| verb | before |
|---|---|
| `m:x()` `m:y()` | `hafen.ui():mouse()` → `{x=, y=}` |
| `m:over()` | `hafen.ui():at(hafen.ui():mouse().x, hafen.ui():mouse().y)` |
| `m:shift()` `m:ctrl()` `m:alt()` | **NEW** — `UI.modflags()` is public and was reachable from Lua nowhere |
| `m:grab()` | `hafen.hook():grab{move, up}` |

**`hafen.ui():at(x, y)` stays where it is.** It takes arbitrary coordinates, so it is not about the
mouse; `m:over()` is the cursor case, which is the one the docs kept spelling out by hand.

**Modifiers are flat verbs, not a `mods` table.** Three booleans in a table would be three dot-reads,
which is what §2 exists to remove.

### The grab

A modal press-drag-release capture: while it is held the map view neither pans nor clicks, so a drag
leaves the camera put. It is what a [ghost](../../../docs/addons/api/ghost.md) gizmo is built on, and
what `planner` uses.

```lua
local g = hafen.ui():mouse():grab()   -- from here the pointer is yours

g:on("Move", function(ev) end)        -- ev:x() ev:y() ev:shift() ev:ctrl() ev:alt()
g:on("Up",   function(ev) end)        -- …plus ev:button(); fires once and auto-releases

g:release()                           -- hand it back early
```

The instant you take it: every move reaches you wherever the cursor goes (even off-window), the map
stops panning, clicks stop reaching the game, and no other widget sees the pointer. A grab still open
when the addon reloads is released by teardown.

**`mouse():grab()` and not `hafen.ui():grab()`** — the receiver already says what is being grabbed, so
the verb keeps the engine's own word ([`UI.grab`](../../codebase/widgets.md), `UI.grabmouse`,
`interface Grab` — [D-061](../decisions/architecture-api.md)) without the *"grab what?"* ambiguity
that would otherwise have forced a compound name.

**Why the config table is gone.** `hafen.hook():grab{move = fn, up = fn}` passed two named callbacks
in one table. A grab **constructs something with a lifetime** — you hold it and `:release()` it — so
[design/25](../design/25-uniform-api.md) R4 applies squarely: *a builder is constructed bare and
configured by chained setters; no `opts` table survives.* And once it is a thing you hold, the rule
this whole feature is built on answers the rest: **¿tienes el objeto? `obj:on(...)`.**

> An earlier draft of this spec claimed grab's table was per-call scope like `g:text`'s
> ([D-143](../decisions/architecture-api.md)) and therefore exempt. That was wrong: `g:text`'s table
> configures one call and dies with it, while a grab outlives the call that made it.

There is no race between taking the pointer and subscribing: Lua runs on the UI thread and
synchronously, so nothing can be dispatched between two statements of the same handler.

`hafen.ui()` and not `hafen.world()`: what you capture is **the pointer**, which is the UI's. Dragging
something across the ground is the common use, not the mechanism — and it still pairs with
[`hafen.world():screenToWorld`](../../../docs/addons/api/world.md) exactly as before.

## 1.8 Delegation — unchanged

```lua
hafen.ui():on(selector, "appear"|"disappear", fn)
```

The one `:on` with three arguments, and that arity **is** the delegation marker: you do not hold the
handle yet. `appear` also fires for what is already open ([D-068](../decisions/widgets-ui.md)).

---

# 2. What a handler receives — the one-axis rule

> **One thing to say → the thing itself. More than one → an event object.**

One axis, mechanical, no exceptions and no judgement call. It is not a new shape: it is the one the
API already follows almost everywhere, stated and then applied without exceptions.

```lua
-- one thing: handed over directly
btn:on("Pressed",   function() end)                 -- nothing to say
chk:on("Changed",   function(v) end)                -- the value
entry:on("Submitted", function(s) end)              -- the text
menu:on("Selected", function(row) end)              -- the row
inv:on("ItemAdded", function(item) end)             -- the Item
w:on("Tick",        function(dt) end)               -- the delta
hafen.event():on("GobAdded", function(gob) end)     -- the Gob
hafen.event():on("MarkersChanged", function(n) end) -- the count

-- more than one: an event object
w:on("MouseDown", function(ev) end)                 -- x, y, button, cancel
w:on("Draw",      function(ev) end)                 -- g, w, h
grid:on("Cell",   function(ev) end)                 -- g, item, w, h
slider:on("Changed", function(ev) end)              -- value, final
hafen.event():action():on("click", function(ev) end)
hafen.event():on("GobOverlayAdded", function(ev) end)
```

## The event object

**Every member is a colon verb.** There is no `ev.button`: one object never mixes `.` and `:`, and
[design/25](../design/25-uniform-api.md) R2 already decides it — *a read is the bare noun `:x()`*.

```lua
ev:x() ev:y()           -- pixels: widget-local (input keys) or window (grab)
ev:button()             -- 1 left / 3 right           (MouseDown, MouseUp, grab Up, *Clicked)
ev:amount()             -- wheel delta                (Wheel)
ev:shift() ev:ctrl() ev:alt()   -- modifier keys      (grab Move/Up)
ev:g()                  -- the LuaGOut brush          (Draw, Cell)
ev:w() ev:h()           -- the area to paint          (Draw, Cell)
ev:item()               -- the row being painted      (Cell)
ev:value() ev:final()   -- the value, drag-ended      (slider Changed)
ev:gob() ev:key() ev:native()          -- (GobOverlayAdded/Removed)
ev:ghost() ev:sprite() ev:object()     -- (the three *Clicked)
ev:msg()                -- message name               (action, message)
ev:sender() ev:target() -- a Widget handle            (action / message)
ev:args()               -- 1-based array              (action, message)
ev:preventDefault()     -- cancel; every other handler still runs
ev:resend()             -- action only; implies preventDefault
ev:send(t)              -- action only; implies preventDefault
ev:rewrite(t)           -- message only
```

> **This is a correction, not a change of direction.** The fields (`ev.msg`, `ev.sender`, `ev.args`)
> are what `LuaActionHook` builds today — a plain Lua table, written before 039 and never reached by
> it. Carrying that shape forward would have made `ev` the one object in the API where a reader has
> to remember which members take a dot and which take a colon.

**No handler's return value is ever read.** Today `AddonWidget`'s input callbacks consume by
`return true`; that becomes `ev:preventDefault()`, so a stray `return` can no longer change
behaviour by accident.

**Cancelability is a property of the key, not of the shape.** `Draw` hands over an `ev` and answers
no `:preventDefault()`; `MouseDown` does. Which keys cancel is the table in §1.1 — the object is
there because there are three things to say, not because there is something to cancel.

---

# 3. Worked examples

## 3.1 The shape the whole feature exists for

Declarations carry no logic; every reaction is in one block below them.

```lua
-- ============================ declarations ============================
local win   = hafen.ui():window():title("Bag Watch"):size(240, 160)
local label = hafen.ui():label():text("waiting…"):position(8, 8):parent(win)
local live  = hafen.ui():check():text("Live"):value(true):position(8, 76):parent(win)
local clear = hafen.ui():button():text("Clear"):position(8, 112):parent(win)

local seen = {}

-- ============================== wiring ================================
clear:on("Pressed", function()
  seen = {}
  label:text("cleared")
end)

live:on("Changed", function(v)
  label:text(v and "live" or "paused")
end)

hafen.event():on("EnterWorld", function()
  local inv = hafen.ui():inventory()

  inv:on("ItemAdded", function(item)
    if not live:value() then return end
    seen[#seen + 1] = item:name()
    label:text(#seen .. " items seen")
  end)

  inv:on("Destroy", function()
    label:text("inventory closed")
  end)
end)

hafen.event():on("GobAdded", function(gob)
  if gob:name() == "gfx/kritter/bear/bear" then
    hafen.log():write("bear!")
  end
end)
```

## 3.2 Input on a native widget — what is impossible today

```lua
local locked = true

hafen.ui():on("inventory[title=Cupboard]", "appear", function(cup)

  cup:on("MouseDown", function(ev)
    if locked and ev:button() == 3 then
      ev:preventDefault()            -- right-click disabled on THIS cupboard only
      hafen.log():write("cupboard is locked")
    end
  end)

  cup:on("ItemAdded", function(item)
    hafen.log():write("in: " .. item:name())
  end)

end)
```

Today the `MouseDown` half cannot be written at all: `hafen.hook():input` takes `"mapview"`,
`"gameui"` or `"root"` and nothing else.

## 3.3 Intercepting an action

```lua
local danger = { x1 = 100, y1 = 100, x2 = 200, y2 = 200 }

hafen.event():action():on("click", function(ev)
  local dest = ev:args()[2]                  -- world coords, already resolved

  if inside(dest, danger) then
    ev:preventDefault()
    hafen.log():write("move blocked")
    return
  end
  -- doing nothing: the click is sent normally
end)
```

"Do something, then move" — `resend` bypasses the hook chain, so it cannot loop:

```lua
hafen.event():action():on("click", function(ev)
  equipBoots()
  ev:resend()
end)
```

Reading the sender as a **handle**, which is new:

```lua
hafen.event():action():on("click", function(ev)
  hafen.log():write(ev:sender():type())      -- "MapView"  (was a bare string)
  local w = ev:sender():parent()             -- navigable  (was impossible)
end)
```

## 3.4 Filtering a server update

```lua
local frozen = false

hafen.event():message():on("set", function(ev)
  if frozen and ev:target():type() == "IMeter" then
    ev:preventDefault()                      -- the widget never applies it
  end
end)
```

## 3.5 Two independent modules on one widget

The case single-slot callbacks could not express — and the reason WoW had to bolt `HookScript`
beside `SetScript`.

```lua
-- modules/audit.lua
function audit.attach(w)
  return w:on("MouseDown", function(ev)
    log[#log + 1] = { x = ev:x(), y = ev:y() }
  end)
end

-- modules/highlight.lua  (knows nothing about audit)
function highlight.attach(w)
  return w:on("MouseDown", function(ev)
    flash(w)
  end)
end

-- main.lua
local inv = hafen.ui():inventory()
local a = audit.attach(inv)
local h = highlight.attach(inv)

a:off()        -- auditing stops; the flash keeps working
```

## 3.6 Dragging something across the ground

The grab's real use, and the one thing that cannot be written with ordinary input subscriptions:
while it is held the map neither pans nor sends the click to the server.

```lua
local ghost = hafen.ghost():new{ res = "gfx/terobjs/arch/timberhouse" }
local pending = false

local g = hafen.ui():mouse():grab()

g:on("Move", function(ev)
  if pending then return end                       -- one raycast in flight at a time
  pending = true
  hafen.world():screenToWorld(ev:x(), ev:y(), function(w)
    pending = false
    if w then
      local s = hafen.world():snapPlace(w.x, w.y, ev:shift())   -- Shift = fine grid
      ghost:move(s.x, s.y)
    end
  end)
end)

g:on("Up", function(ev)
  hafen.log():write("placed with button " .. ev:button())        -- auto-releases here
end)
```

## 3.7 Reading the pointer without grabbing it

```lua
hafen.event():on("Update", function()
  local m = hafen.ui():mouse()
  local w = m:over()
  if w and m:ctrl() then
    hafen.log():write("ctrl-hovering " .. w:type() .. " at " .. m:x() .. "," .. m:y())
  end
end)
```

`m:ctrl()` is new: modifier state was reachable only from inside a handler that happened to be
handed it.

## 3.8 Ending a subscription from inside itself

```lua
local sub
sub = hafen.event():on("GobAdded", function(gob)
  if gob:name() == "gfx/terobjs/vehicle/wheelbarrow" then
    hafen.log():write("found it")
    sub:off()
  end
end)
```

## 3.9 Cancel with two handlers — the OR rule

```lua
local w = hafen.ui():inventory()

w:on("MouseDown", function(ev) hafen.log():write("A ran") end)
w:on("MouseDown", function(ev) ev:preventDefault() end)
w:on("MouseDown", function(ev) hafen.log():write("C ran") end)

-- a click logs "A ran" and "C ran", and the inventory does not see the click.
-- Cancelling in ANY handler cancels; every handler still runs, so the outcome
-- never depends on registration order.
```

---

# 4. Refusals — the exact messages

A refusal is a check ([TESTING.md](../TESTING.md)), so these are asserted, not described.

```lua
btn:on("Presed", fn)
-- widget:on(key, fn): a Button has no event 'Presed' — it has: Pressed, MouseDown,
--   MouseUp, MouseMove, Wheel, Destroy

hafen.event():on("OnLoad", fn)
-- hafen.event():on(key, fn): 'OnLoad' is now 'Load' (041: `:on` already says "on")

hafen.event():on("GobAdded ", fn)
-- hafen.event():on(key, fn): unknown event 'GobAdded ' — see docs/addons/api/event.md
--   for the catalogue

hafen.hook():action("click", fn)
-- hafen.hook() is now hafen.event():action():on(msg, fn)

btn:onPress(fn)
-- widget:onPress(fn) is now widget:on("Pressed", fn)

label:on("Pressed", fn)
-- widget:on(key, fn): a Label has no event 'Pressed' — it has: MouseDown, MouseUp,
--   MouseMove, Wheel, Destroy

hafen.event():action():on("anythingatall", fn)
-- accepted: a wdgmsg name is protocol, not a catalogue the client owns (D-129)
```

---

# 5. The complete port map

The `Retired` source. Every row throws naming its replacement.

## 5.1 Sections

| before | after |
|---|---|
| `hafen.hook()` | *(gone)* |
| `hafen.hook():input(target, ev, fn)` | `handle:on(key, fn)` — **any** widget |
| `hafen.hook():action(msg, fn)` | `hafen.event():action():on(msg, fn)` |
| `hafen.hook():message(msg, fn)` | `hafen.event():message():on(msg, fn)` |
| `hafen.hook():grab{move, up}` | `hafen.ui():mouse():grab()` + `g:on("Move", fn)` / `g:on("Up", fn)` — the table is **cut** (R4) |
| `g:release()` | *(unchanged)* |
| `hafen.ui():mouse()` → `{x=, y=}` | `hafen.ui():mouse():x()` / `:y()` — the section's one thing IS the object |
| `hafen.ui():at(m.x, m.y)` *(the cursor case)* | `hafen.ui():mouse():over()` |
| `hafen.ui():at(x, y)` *(any point)* | *(unchanged)* |
| *(nothing)* | `hafen.ui():mouse():shift()` / `:ctrl()` / `:alt()` — **NEW** |
| `hafen.event():on(OnX, fn)` *(4 lifecycle keys)* | `hafen.event():on(X, fn)` — `On` prefix dropped |
| `hafen.event():on(Name, fn)` *(other 22 keys)* | *(unchanged — already PascalCase)* |
| `sub:off()` | *(unchanged)* |
| `handle:remove()` *(hook handles)* | `sub:off()` |

## 5.2 Widget verbs — all 16

| before | after |
|---|---|
| `w:onPress(fn)` | `w:on("Pressed", fn)` |
| `w:onPress()` *(read)* | *(cut — a subscription is not a property)* |
| `w:onChange(fn)` | `w:on("Changed", fn)` |
| `w:onChange()` *(read)* | *(cut)* |
| `w:onSubmit(fn)` | `w:on("Submitted", fn)` |
| `w:onSelect(fn)` | `w:on("Selected", fn)` |
| `w:onCell(fn)` | `w:on("Cell", fn)` |
| `w:onDraw(fn)` | `w:on("Draw", fn)` |
| `w:onTick(fn)` | `w:on("Tick", fn)` |
| `w:onClick(fn)` | `w:on("MouseDown", fn)` — **renamed, it was never a click** |
| `w:onMouseUp(fn)` | `w:on("MouseUp", fn)` |
| `w:onMouseMove(fn)` | `w:on("MouseMove", fn)` |
| `w:onWheel(fn)` | `w:on("Wheel", fn)` |
| `w:onDrop(fn)` | `w:on("Drop", fn)` |
| `w:onClose(fn)` | `w:on("Close", fn)` |
| `w:onItemAdded(fn)` | `w:on("ItemAdded", fn)` |
| `w:onItemRemoved(fn)` | `w:on("ItemRemoved", fn)` |
| `w:onDestroy(fn)` | `w:on("Destroy", fn)` |

## 5.3 Bus keys — 4 renamed, 22 unchanged

Renamed (the `On` prefix is redundant once `:on` says it): `OnLoad`→`Load` · `OnEnterWorld`→
`EnterWorld` · `OnUpdate`→`Update` · `OnDisable`→`Disable`.

**Unchanged** (already PascalCase, `Retired` carries no entry for these): `GobAdded` · `GobRemoved` ·
`GobOverlayAdded` · `GobOverlayRemoved` · `MeterAdded` · `MeterRemoved` · `MeterChanged` ·
`BuffAdded` · `BuffRemoved` · `BuffChanged` · `FepChanged` · `StudyChanged` · `EquipChanged` ·
`ActionbarChanged` · `WoundChanged` · `KinChanged` · `QuestAdded` · `QuestDone` · `MarkersChanged` ·
`GhostClicked` · `SpriteClicked` · `ObjectClicked`.

## 5.4 Behaviour changes that are not renames

| what | before | after |
|---|---|---|
| cardinality | 1 slot on widgets, N on the bus | **N everywhere** |
| installing twice on one key | second replaces the first | **both fire**, in order |
| reading the handler back | `btn:onPress()` | *(cut)* |
| consuming an input | `return true` from the handler | `ev:preventDefault()` |
| unknown bus key | accepted, never fires | **throws** |
| unknown widget key | *(no such concept)* | **throws, listing the keys** |
| unknown `action`/`message` key | accepted | accepted *(unchanged)* |
| the event object | fields: `ev.msg`, `ev.args` | **verbs: `ev:msg()`, `ev:args()`** — never a mix of `.` and `:` |
| `sender` / `target` | class-name string | **Widget handle** — `ev:sender():type()` gives the string |
| composite bus payloads | tables: `{gob, key, native}` | **objects: `e:gob()` `e:key()` `e:native()`** |
| the grab's callbacks | `grab{move = fn, up = fn}` | `g:on("Move", fn)` / `g:on("Up", fn)`, each given an `ev` |
| the mouse | a `{x=, y=}` table read | **an entity**: `:x()` `:y()` `:over()` `:shift()` `:ctrl()` `:alt()` `:grab()` |
| modifier keys | only inside a grab handler's `mods` table | readable any time — `hafen.ui():mouse():shift()` |

---

# 6. Rules a `/implement` session must not re-derive

1. **Registration order within an addon; undefined between addons.** The OR cancel rule is what
   makes that safe rather than merely unspecified.
2. **Every handler runs even after one cancels.** Never short-circuit the loop on `prevented`.
3. **`sub:off()` is idempotent** — a second call, or one after the widget died, is a no-op.
4. **A subscription is owned by its addon** and released on `:reload`/disable. Listeners on
   *native* widgets must be `deafen`ed on teardown: a native widget survives a `:reload` while the
   Lua layer is rebuilt, so a surviving listener would fire into a torn-down env
   (`learnings/hooks-hotkeys.md`, the 2c rule — it applied to three widgets and now applies to any).
5. **`hasSub` stays.** Per-addon payload minting is gated on that addon having a live subscription
   to that key.
6. **Nothing moves threads.** UI thread or under `synchronized(ui)`, always through
   `AddonManager.callLua`.
7. **One thing to say → the thing itself; more than one → an `ev`.** One axis, no exceptions. Do not
   re-introduce "is this cancelable?" as a second axis: `Draw` carries an `ev` and cancels nothing.
8. **Every event object member is a colon verb.** No member of any payload is read with a dot. A
   single value handed to a handler (`Changed`'s `v`, `Tick`'s `dt`, `MarkersChanged`'s count) stays
   a bare value.
9. **A programmatic `:value(v)` never fires `Changed`** ([D-153](../decisions/architecture-api.md)).
10. **`GobRemoved`'s payload answers only `:id()`.** Unchanged.
11. **Owner-scoping is unchanged** ([D-104](../decisions/architecture-api.md)): an overlay event
    whose `:native()` is false, and the three `*Clicked` events, go only to the owning addon;
    everything else broadcasts.
12. **A grab is an emitter, not a config table.** `hafen.ui():mouse():grab()` bare, then
    `:on("Move")` / `:on("Up")`, then `:release()`. `Up` still auto-releases, and teardown releases
    a grab left open.
13. **`hafen.ui():at(x, y)` is not absorbed.** It takes any point; only the *cursor* case moves, to
    `mouse():over()`.
14. **Modifiers are three flat verbs, never a `mods` table** — on the mouse and on any `ev` that
    reports them.
