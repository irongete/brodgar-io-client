# hafen.ui: Placing, Hiding and Handing Over the Client's Widgets

The unprotected writes that answer on a widget you do not own: `:position(x, y)`, `:size(w, h)`, `:parent(p)`, `:visible(false)`, `:draggable(h)`, `:resizable(h)` and `:remember(name)`. Each records what it found, so everything is given back when your addon goes.

```lua
local session = hafen.session():current()
local inventory = session:ui():match("window[title=Inventory]")
local grip, corner = hafen.ui():widget():size(24, 24), hafen.ui():widget():size(12, 12)
inventory:position(40, 200)        -- move it
inventory:size(300, 220)           -- resize its content; the chrome refits around it
inventory:position(nil)            -- drop your move: back to where the user had it
inventory:draggable(grip)          -- let the user move it, by pressing a widget of yours
inventory:resizable(corner)        -- and size it, by pressing another
inventory:remember("bag")          -- and have it come back there next session
```

`:destroy()` stays refused on a widget you do not own — [owned vs borrowed](writes.md#owned-vs-borrowed). Changing what a client widget says or does is [edit](edit.md). Standing one in the 3D world is [`hafen.virtual`](../virtual/widgets.md), restoring under the same rule.

---

## Moving and resizing (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:position(x, y)` | `self` | Unprotected | Moves it, writing the field the user's own drag writes: what you place is what you click. |
| `widget:size(w, h)` | `self` | Unprotected | Resizes its content. A window's chrome refits. |
| `widget:position(nil)`, `widget:size(nil)` | `self` | Unprotected | Drop your level of that half. The widget lands on a rule that still names it, else on the stock: what the client recorded on your first touch, or the builder's default place and box on a [surface of yours](custom.md). |
| `widget:position()` | `{x=, y=}` | Unprotected | Within the parent, in [design pixels](pixels.md). The HUD is not the root, so [`:rootPos()`](widget.md#read-methods) is the screen coordinate. |
| `widget:size()` | `{w=, h=}` | Unprotected | The box `:size(w, h)` writes — a window's content area — so writing a size back is a no-op. The frame is [`:chrome().frame`](widget.md#read-methods). |

| Rule | Detail |
|---|---|
| A layer, never a write into the client | The first touch records what the widget was. `:reload` and disable give back everything you held. A relog restores nothing: that session's widgets are gone. |
| The disk is the user's | The client persists a few window positions of its own: inventory, equipment, character sheet, kin, map, windows tracked by id. It always writes what the user last placed, never your level. Uninstalling your addon leaves the HUD as its owner arranged it. |
| A position always lands. A size may not | A window that packs itself around its contents (the main inventory) honours `:size(w, h)` and undoes it before the call returns: inert, never an error. Read `:size()` back to tell. The sheet's [`padding`](style/chrome.md#padding) follows the same rule. |
| Two addons | Each may hold a layer on one widget. The last write wins on screen, each restores what it found. |
| The cascade | The verb is the top level over a sheet's [`position` and `size` rules](style/geometry.md). `:position(nil)` drops your level and falls back to a rule that still names the widget. It reaches the stock value only when none does. A [surface of yours](custom.md) runs the same cascade, its stock the builder's default place and box. |

---

## Letting the user drag it (unprotected)

The client gives the drag gesture to windows alone, by their caption. `widget:draggable(h)` gives it to any widget, by a handle the user presses.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:draggable(h)` | `self` | Unprotected | Arms it: pressing `h` drags `widget`. Arming again changes the handle. |
| `widget:draggable()` | `Widget \| nil` | Unprotected | The handle your addon armed, the same object you passed. Never another addon's. |
| `widget:draggable(nil)` | `self` | Unprotected | Drops your binding. |

```lua
local chat = hafen.session():current():ui():match("@ChatUI")
local grip = hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat)
chat:draggable(grip)      -- pressing the grip drags the chat
chat:draggable()          -- the grip back
chat:draggable(nil)       -- the chat stops being draggable
```

| Rule | Detail |
|---|---|
| The handle is a widget | The target itself drags the whole thing. A grip [adopted](edit.md) into it with `:parent(w)` drags from there alone. A button of yours anywhere works too. A window refuses itself as its handle (its caption already drags it). Any other handle on a window is accepted. |
| A drag writes your `:position` level | `:position()` reads where the user dropped it. `:position(nil)`, `:reload` and disable put the stock place back. The client's own store still records what the user placed. |
| The clamp | At least 100 design pixels of the widget, or the whole of it when smaller, stays inside its parent. That is the client's own rule for its windows. |
| The gesture holds the pointer | From press to release, off-window included. Nothing underneath is clicked, and the widget follows a pointer that outruns the handle. |
| The press belongs to the drag | A widget armed as a handle stops answering clicks of its own while the binding stands. Give a widget with clicks a grip of its own. |
| Re-layout | Resizing the game window re-places the client's panels. A place you or the user named goes back on top, and `:position(nil)` still yields the stock value. |
| Two addons | May arm one widget: one drag moves it once, both levels take the landing, each `nil` drops its own. Both `Dragged` handlers fire. |
| Endings | The arming ends when the target or the handle leaves the tree. A grip pressing three targets keeps pressing the other two. `widget:revert()` drops it too. A handle that has left the tree arms nothing and chains, with `:draggable()` reading `nil`. A non-widget raises. A stale target is a no-op. |

### Knowing when one was dragged

`widget:on("Dragged", fn)` fires once, on release, with `event:x()`/`event:y()` where the widget landed — the numbers `:position()` reads in that frame, clamp included. Not cancelable. Does not fire for your own `:position(x, y)`. A press that never moved says nothing.

```lua
chat:on("Dragged", function(drag_event)
  hafen.log():write("chat dropped at " .. drag_event:x() .. ", " .. drag_event:y())
end)
```

---

## Letting the user resize it (unprotected)

The client gives the resize gesture to one window in the game, the map, by its corner grip. `widget:resizable(h)` gives it to any widget by a handle of yours. `:resizable(true)` gives a window of yours the client's own grip.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:resizable(h)` | `self` | Unprotected | Arms it: pressing `h` resizes `widget`. Arming again changes the handle. |
| `widget:resizable(true)` | `self` | Unprotected | On a window [you built](custom.md#letting-the-user-resize-a-window-of-yours): the client's own corner grip, drawn by the frame. |
| `widget:resizable(false)` | `self` | Unprotected | The client's grip off again. |
| `widget:resizable()` | `boolean \| Widget \| nil` | Unprotected | `true` while the client's grip is on a window of yours, else the handle your addon armed, else `nil`. |
| `widget:resizable(nil)` | `self` | Unprotected | Drops your handle binding. |

```lua
local corner = hafen.ui():image():source(hafen.asset():get("corner.png")):parent(inventory)
inventory:resizable(corner)     -- pressing the corner resizes the window
inventory:resizable()           -- the corner back
inventory:resizable(nil)        -- the window stops being resizable
```

| Rule | Detail |
|---|---|
| The top-left stays put | A resize moves no origin, the client's own rule. The widget grows away from its corner. |
| The floor | One design pixel each way. |
| A resize writes your `:size` level | `:size()` reads the box it landed at — a window's content box, the value the pointer drives. `:size(nil)`, `:reload` and disable put the stock box back. On a window that packs itself around its contents the gesture is inert. The box is undone as fast as the pointer writes it, and nothing raises. |
| Handles | The press, the pointer and the re-layout behave as a drag's. A window is accepted as its own resize handle (nothing in the client's chrome resizes it from the frame). Where the client's own sizer is live, both gestures run and the last write wins. Two addons may arm one widget. |
| The client's grip | A window your addon built alone. A bare widget has no frame to draw it on, and a client window keeps its own chrome, so both refuse naming `:resizable(h)`. What the grip drives is the same content box, so `Resized`, `:remember` and a size level answer the same. |
| Endings | As a drag's: the target or handle leaving the tree ends the arming, `widget:revert()` drops both bindings, a stale handle arms nothing, a non-widget raises. |

### Knowing when one was resized

`widget:on("Resized", fn)` fires once, on release, from a handle of yours and from the client's grip alike. `event:w()`/`event:h()` is the box the widget landed at, the numbers `:size()` reads in that frame. Not cancelable. Does not fire for your own `:size(w, h)`. A press that never moved says nothing. Both addons hear it when two armed one widget.

```lua
inventory:on("Resized", function(resize_event)
  hafen.log():write("window is now " .. resize_event:w() .. " by " .. resize_event:h())
end)
```

---

## Remembering where the user put it (unprotected)

`widget:remember(name)` keeps a widget's place and box under a name of yours. It puts back what the name holds the moment you call it, and saves where the widget stands from then on. No handler of yours, nothing in your manifest.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:remember(name)` | `self` | Unprotected | Remembers it, and puts back what that name holds. |
| `widget:remember()` | `string \| nil` | Unprotected | The name your addon remembers it under. |
| `widget:remember(nil)` | `self` | Unprotected | Stops remembering it and deletes what was saved. |

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.session():current():ui():match("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")     -- back where it was, and saved again after every drag
end)
```

| Rule | Detail |
|---|---|
| It applies on the call | The only correct moment to put a place back is the moment you name it. What it writes is your [`:position` and `:size` levels](#moving-and-resizing-unprotected), exactly as the verbs write them. A `:position(x, y)` written after it wins. `:position(nil)` still gives the stock place back. |
| What is saved | Where your levels stand, written to the client's own file, never your store. It is written when a gesture lands, when the screen changes and when the widget goes. A window [you built](custom.md) is saved where it stands and at the box it has. Your own writes or the user's title bar and corner may have put it there. The one box not saved is a [packed](custom.md#packing-a-surface-around-what-is-inside-it) surface's. A client widget's box is saved only where you named a size. |
| Whose row | A session's own window is filed under that character, like [a character's var](../store/vars.md). One you built under your [addon's own scope](../store/vars.md#where-a-widget-sits-is-saved-for-you). Called before that session is in the world it has nothing to put back, says so in the log, and remembers the name anyway. |
| One name, one widget | A second widget under a name your addon holds raises. Renaming a remembered widget is accepted. A name that is not a string raises. A stale widget is a no-op. |
| Dropping is not forgetting | `widget:revert()`, `:reload` and disable drop the binding and leave the record. `remember(nil)` is the one thing that deletes it. |

---

## Hiding a native widget carries a restore

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:visible()` | `boolean` | Unprotected | Whether it is drawn. |
| `widget:visible(false)` | `self` | Unprotected | Hides it and records the restore. |
| `widget:visible(true)` | `self` | Unprotected | Gives it back yourself and drops the record. |

| Rule | Detail |
|---|---|
| The restore | On disable or `:reload` the widget ends up as the user was seeing it. It is visible exactly when whatever you put in its place was on screen. Hidden with nothing put there, it stays hidden, and [its toggle](#hiding-a-native-window-takes-its-toggle) reopens it. A relog skips the restore. |
| A widget with no toggle | The action bar, a HUD panel, the chat: teardown leaves it hidden with nothing to bring it back. Put it back from [`Disable`](../event/bus/lifecycle.md#lifecycle), which fires before the teardown: keep the handles and `:visible(true)` each. |
| One widget, one owner | A widget another addon hid refuses, naming that addon. [`replace`](replace.md) logs the same collision instead of raising, since it runs on the client's placement path. A [radial menu](../flowermenu.md#drawn-or-not-unprotected) refuses both writes, naming `s:flowermenu():visible(b)`. |
| Live while hidden | A hidden server widget stays bound to its id, receives updates and fills with items, so a hidden grid still [reads](items.md). |

### Hiding a native window takes its toggle

Hiding a window the client itself can open (inventory, equipment, character sheet, kin, options, map, action search) takes its toggle. The key and the menu button stop reopening it, and the button's tick goes off. Otherwise the keybinding, which fires the menu button's click, would flip the window straight back on.

```lua
session:ui():inventory():parent():visible(false)   -- the window around the grid: Tab opens nothing
session:ui():inventory():visible(false)            -- the grid alone: the window and Tab stay the client's
```

| Rule | Detail |
|---|---|
| What you own is what you point at | The toggle belongs to the window. Hiding a widget inside it leaves the window and its key stock. |
| Swallowed, then handed back | The key does nothing while nothing stands in for the window. `:visible(true)`, disable and `:reload` hand it back. That is the escape from a `:visible(false)` typed at the `:lua` console: `:reload`, not a relog. |
| With [`widget:replace(view)`](replace.md) | The toggle opens and closes your view, and the tick reads your view's visibility. Nothing to wire. |
| Per window | Hiding the inventory leaves equipment, kin, options and the map stock. There is no verb: ownership follows the hide. |

---

## Taking one into a surface of your own (unprotected)

`widget:parent(p)` makes a client widget hang under a surface of yours. `widget:parent(nil)` gives it back whole. It is the building-time [`:parent(w)`](custom.md#builders) of a control of yours, from the other side.

```lua
local hud = session:ui():match("@GameUI")
local minimap = session:ui():match("@CornerMap")                       -- the client's own
local frame = hafen.ui():widget():parent(hud)                          -- a surface of yours, in that tree
  :size(minimap:size().w + 16, minimap:size().h + 16):position(300, 200)
frame:stock{bg = {color = {43, 51, 44, 127}}, border = {box = "gfx/hud/wnd", mode = "tile"}}
minimap:parent(frame)                                                  -- the map is now inside it
minimap:position(8, 8)
-- later: minimap:parent(nil)
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:parent()` | `Widget \| nil` | Unprotected | What it hangs under. `nil` at a root. |
| `widget:parent(p)` | `self` | Unprotected | Takes it into `p`, a surface your addon built in that character's tree. It lands at `0, 0`. `:position(x, y)` places it from there. |
| `widget:parent(nil)` | `self` | Unprotected | Gives back the parent, the sibling order, the place and the box the client had. The layout goes first, then the widget, so a parent that packs around its children is not left fitted to your size. |

| Rule | Detail |
|---|---|
| A move, not a copy | The widget stays the client's: it ticks, draws itself, answers its clicks and tooltips, and a bound one keeps filling. This is the reach for a surface whose value is its picture — the minimap, the portrait, a meter's fill — which [`replace`](replace.md) cannot redraw. To show that picture somewhere the widget cannot go, a [mirror](mirror.md) draws it again and moves nothing. |
| Painting order | Your surface's `bg` is a field under the client's widget and its `border` a frame over both — [naming and dressing](custom.md#naming-and-dressing-your-own-surfaces). Your surface does not resize around it. |
| The surface must be in that character's tree | A client widget reads the login behind it. The [addon layer](custom.md#your-windows-live-in-the-layer) has none, so a widget taken there would go dark. Build the surface into the HUD (`hafen.ui():widget():parent(session:ui():match("@GameUI"))`). The refusal says so. A [mirror](mirror.md) in the layer shows the widget's picture instead. |
| One widget hangs in one place | A widget another addon holds is refused naming it, and so is one [standing in the 3D world](../virtual/widgets.md). `hafen.virtual():widget():add` refuses one you hold here. |
| A destination that has left the tree | Moves nothing: the client's widget stays where it is, the call chains, nothing is recorded. A non-widget raises, naming the two surface builders. |
| The parent it left keeps its box | A parent that packs around its children is not told a child walked out. The room stays reserved until the client adds the next one. |
| The client rebuilds its widgets | The corner minimap is re-made when the map file changes, and a rebuilt widget never left home. Take it with [`session:ui():on(sel, "Added", fn)`](replace.md#watching-for-a-widget) rather than once at login. |
| Destroying your surface | `widget:destroy()` on a surface holding a client widget sends that widget home before anything is disposed. |

---

## The client reuses its windows

Everything you hold on a client widget is held on that widget, not on what it currently shows. The frame one container came in is the frame the next gets. A place, a size or a [caption](edit.md#what-a-window-says) you wrote is still there when it returns as something else. Watch for the widget rather than holding it. Dropping a level you do not hold is a no-op.

```lua
hafen.session():current():ui():on("window", "Added", function(reused_window)
  reused_window:title(nil)       -- whatever this frame was last used for, it is not that any more
end)
```

---

## See Also

- [Writes](writes.md#owned-vs-borrowed) — which writes answer on which widget.
- [Edit](edit.md) — changing what a client control does.
- [Replace](replace.md) — hiding a whole window and standing yours in its place.
- [Geometry](style/geometry.md) — the same placement as a rule.
- [Selectors](selectors.md) — naming the widget you are about to move.
- [Chrome](style/chrome.md#padding) — `padding`, the other property that moves a window.
- [Pixels](pixels.md) — the unit of `:position(x, y)` and `:size(w, h)`.
