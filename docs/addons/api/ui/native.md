# hafen.ui: placing, hiding and handing over the client's own widgets

These writes answer on a widget you do not own: `:position(x, y)` moves it, `:size(w, h)` resizes it,
`:visible(false)` takes it off screen, `:draggable(h)` and `:resizable(h)` hand the move and the box to the
**user**, and `:remember(name)` keeps where they left it. Every one of them is **unprotected** —
client-side placement, not an action — and every one records what it found, so everything is given back
when your addon goes away.

```lua
local s = hafen.session():current()                    -- the character whose window it is
local inv = s:ui():match("window[title=Inventory]")
inv:position(40, 200)     -- move it
inv:size(300, 220)        -- resize its CONTENT; the chrome repacks around it
inv:position(nil)         -- drop YOUR move: back to where the user had it
inv:draggable(grip)       -- ...or let the user move it, by pressing a widget of yours
inv:resizable(corner)     -- ...and size it, by pressing another
inv:remember("bag")       -- ...and have it come back there next session
```

`:destroy()` stays refused on a widget you do not own: that destroys the client's work rather than sits on
top of it. See [owned vs borrowed](widget.md#owned-vs-borrowed) for the whole table.

This page is about **where** one of the client's widgets sits, whether it is on screen, and who decides
either — you, or the person playing. Changing what
one of them *says* or *does* — a caption, a control of your own inside one of its windows, or taking over
a button of the client's own — is [editing](edit.md).

[Standing one in the 3D world](../vr/widgets.md) is a fourth write of the same family, restoring under the
same rule.

## Moving and resizing (unprotected)

`w:position(x, y)` and `w:size(w, h)` move the client's own widgets, and they move them for real: the verb
writes the same field your own drag writes, so what you place is what you click. There is no draw-time
offset anywhere, because a widget drawn where it cannot be clicked is worse than one that never moved.

**Your layout is a layer over the client's, never a write into it.** The first time you touch a native
widget the engine records what it was; `w:position(nil)` and `w:size(nil)` give that half back on the spot,
and disabling or `:reload`ing your addon gives back everything you were holding. A relog correctly restores
nothing — that session's widgets are gone.

**The half that is easy to get wrong is the disk.** The client persists a few window positions of its own
— inventory, equipment, the character sheet, kin, the map, and any window it tracks by id — written at
logout *and* while you play. What it writes is always **what the user last placed**, never where your rule
put it. So uninstalling your addon leaves the HUD exactly as its owner had arranged it, which is the whole
point: nothing you do here is a change they have to undo by hand.

**Reading back.** `w:position()` answers within the parent, in widget-local [design pixels](pixels.md) — a window's coordinate is
relative to whatever contains it, and the HUD is not the root, so use
[`:rootPos()`](widget.md#read) when you need screen coords. `w:size()` reads a window's **outer** box while
`w:size(w, h)` sets its **content** size, which is the same asymmetry a window you built has: the chrome is
derived, not set.

**A position always lands; `size` does not overrule a window that owns its own.** Some of the client's windows
pack themselves around their contents whenever anything resizes them — the main inventory is one — so
`w:size(w, h)` on those is honoured and then undone by the client before the call returns. That is
**inert, never an error**, and it leaves nothing behind; read `:size()` back if you need to know which kind
you are holding. It is the same rule the sheet's [`padding`](style/chrome.md#padding) follows: a size applies where
the surface can re-lay itself out, and a surface that fixes its own size cannot.

Two addons may each hold a layer over the same widget — unlike
[hiding](#hiding-a-native-widget-carries-a-restore), a position is not a toggle. The last write wins on
screen, and each addon restores what *it* found.

**The verb is the top of a cascade, not the only way in.** The sheet says the same two things with
[`pos` and `size` rules](style/geometry.md), matched rather than named, and the verb sits above whatever a
rule resolved — so `w:position(nil)` drops *your* level and falls back to the rule when one still names
the widget, reaching the stock value only when nothing does.

## Letting the user drag it (unprotected)

`w:draggable(h)` says **this widget can be dragged, and here is what the user presses to drag it**. The
client gives that gesture to one kind of widget only — a window, by its caption — so almost nothing else
on screen moves at all: not the chat, not the belt, not the panels down the sides of the HUD.

```lua
local chat = hafen.session():current():ui():match("@ChatUI")
local grip = hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat)

chat:draggable(grip)      -- pressing the grip drags the chat
chat:draggable()          -- the grip back, the same Widget object you passed
chat:draggable(nil)       -- the chat stops being draggable
```

**The handle is a widget**, which is what keeps this one verb instead of a vocabulary of edges and zones.
Pass the target itself and the whole thing drags; pass a grip you [adopted](edit.md) into it with
`:parent(w)` and it drags from there alone; pass a button of yours somewhere else entirely and that works
too. The one handle a window refuses is **itself** — its caption already does exactly that, and two drags
on one press would move it twice. Any other handle on a window is accepted.

**A drag writes your `:position` level, and nothing beside it.** So `w:position()` reads where the user
dropped it, `w:position(nil)` puts back the stock place, `:reload` and disable do the same, and the
client's position store still writes down what the *user* placed rather than where a drag of yours left
it. Everything on this page's [first section](#moving-and-resizing-unprotected) is true of a drag, because
a drag is that write with a person's hand on it.

**It cannot be lost off screen.** A dragged widget goes through the client's own graspability rule — at
least 100 [design pixels](pixels.md) of it, or the whole of it when it is smaller, stays inside its
parent — the same clamp the client applies when it places one of its own windows.

**A drag survives the pointer outrunning the handle**, and the pointer leaving the game window: the
gesture holds the pointer from the press to the release, so the widget follows wherever it goes and
nothing underneath is clicked on the way.

**The press belongs to the drag.** Pressing a handle starts the gesture and does nothing else — the same
rule a window's caption follows — so a widget you arm as a handle stops being clickable for anything else
while the binding stands. Give a widget a grip of its own rather than arming it as its own handle when it
has clicks of its own to answer.

**It survives the client re-laying the screen out**, too. Resizing the game window re-places the chat, the
belt and the map; a place you or the user named goes back on top of that, and `w:position(nil)` afterwards
still yields the stock value.

**Two addons may arm one widget**, exactly as two may hold a position on it. One drag moves it **once**,
both levels take the place it landed at — so a `nil` from either addon is invisible on screen — and each
`nil` drops only its own binding.

| Call | Does |
|---|---|
| `w:draggable()` | the handle **your** addon armed on it, or `nil`; never another addon's |
| `w:draggable(h)` | arm it: pressing `h` drags `w`. Arming again is a change of handle, not a second binding; chains |
| `w:draggable(nil)` | drop your binding; chains |

`w:revert()` drops the binding too, along with everything else your addon holds on that widget — see
[taking the whole edit back](edit.md#taking-the-whole-edit-back). A handle that is not a widget, or one
that has left the tree, raises; a target that has left the tree is a silent no-op, like every other write
here.

### Knowing when one was dragged

`w:on("Dragged", fn)` fires **once, on release**, and `ev:x()`/`ev:y()` answer where the widget landed —
the same numbers `w:position()` reads in that frame, the clamp above included.

```lua
chat:on("Dragged", function(ev)
  hafen.log():write("chat dropped at " .. ev:x() .. ", " .. ev:y())
end)
```

It is not cancelable: the gesture is over by the time you hear about it. And it does **not** fire for your
own `w:position(x, y)`, so a handler cannot drive itself. A press that never moved the pointer is a click
rather than a drag, and says nothing. When two addons have armed one widget, both handlers fire.

## Letting the user resize it (unprotected)

`w:resizable(h)` says **this widget can be resized, and here is what the user presses to resize it**. The
client gives that gesture to exactly one window in the game — the map, by the small corner sizer in its
frame — so nothing else on screen can be made bigger or smaller by the person using it.

```lua
local win = hafen.session():current():ui():match("window[title=Inventory]")
local corner = hafen.ui():image():source(hafen.asset():get("corner.png")):parent(win)

win:resizable(corner)     -- pressing the corner resizes the window
win:resizable()           -- the corner back, the same Widget object you passed
win:resizable(nil)        -- the window stops being resizable
```

**The top-left stays put.** A resize moves no origin — the client's own rule, which is why the map's single
sizer is at the bottom right — so the widget grows away from its corner and the place you or the user gave
it is untouched. That is also why there is no vocabulary of corners and edges here: one gesture, one
handle, and [`w:position(x, y)`](#moving-and-resizing-unprotected) is where a place is said.

**It never shrinks away to nothing.** One [design pixel](pixels.md) each way is the floor, because a widget
with no box is one the user can no longer find, let alone grab.

**A resize writes your `:size` level**, exactly as a drag writes your `:position` one. So `w:size()` reads
the box it landed at, `w:size(nil)` puts the stock box back, `:reload` and disable do the same, and
everything in [moving and resizing](#moving-and-resizing-unprotected) is true of a resize — including the
asymmetry that makes `w:size()` a window's **outer** box while the gesture drives its **content**, and
including the windows that pack themselves around their contents. On one of those the whole gesture is
**inert**: the box is undone by the client as fast as the pointer writes it, and nothing raises.

**Everything else the handle does, it does the same way**: the press
[belongs to the gesture](#letting-the-user-drag-it-unprotected), it survives the pointer outrunning the
handle and leaving the game window, it survives the client re-laying the screen out, and two addons may arm
one widget and see it sized once. The one difference is the handle a window refuses — a window is refused as
its own **drag** handle, since its caption already drags it, and accepted as its own **resize** handle,
since nothing in the client's chrome does that. Where the map's own sizer is live, both gestures run and the
last one to write wins.

| Call | Does |
|---|---|
| `w:resizable()` | the handle **your** addon armed on it, or `nil`; never another addon's |
| `w:resizable(h)` | arm it: pressing `h` resizes `w`. Arming again is a change of handle, not a second binding; chains |
| `w:resizable(nil)` | drop your binding; chains |

The two bindings are independent: one grip may drag a widget while another resizes it, and each `nil` drops
only the one it names. `w:revert()` drops both, along with everything else your addon holds on that widget.
The refusals are the drag's, one word along — a handle that is not a widget, or one that has left the tree,
raises; a target that has left the tree is a silent no-op.

### Knowing when one was resized

`w:on("Resized", fn)` fires **once, on release**, and `ev:x()`/`ev:y()` answer the box the widget landed at
— the same numbers `w:size()` reads in that frame, so on a window it is the **outer** box and not the
content size the pointer drove.

```lua
win:on("Resized", function(ev)
  hafen.log():write("window is now " .. ev:x() .. " by " .. ev:y())
end)
```

Like `Dragged` it is not cancelable, it does not fire for your own `w:size(w, h)`, a press that never moved
the pointer says nothing, and both addons hear it when two have armed one widget.

## Remembering where the user put it (unprotected)

`w:remember(name)` says **this widget's place and box are kept, under this name**. It puts back whatever
that name holds the moment you call it, and saves where the widget stands from then on — so a HUD that
comes back the way its owner left it is the four lines below, with no handler of yours and nothing
declared in your manifest.

```lua
hafen.event():on("SessionEnteredWorld", function()
  local chat = hafen.session():current():ui():match("@ChatUI")
  chat:draggable(hafen.ui():image():source(hafen.asset():get("grip.png")):parent(chat))
  chat:remember("chat")     -- back where it was, and saved there again after every drag
end)
```

**It applies on the call, which is why there is nothing to pair it with**: the only correct moment to put a
place back is the moment you say it is remembered. What it writes is your
[`:position` and `:size` levels](#moving-and-resizing-unprotected), exactly as those two verbs write them,
so a `w:position(x, y)` written *after* it wins, being the later write, and `w:position(nil)` still gives
the stock place back.

**What is saved is where your levels stand**, at every write to disk: after a gesture, on the save timer, and
when the screen moves. **The slot belongs to the tree the widget stands in**: a session's own window — the
`@ChatUI` above — is filed under **that** character like a [per-character saved variable](../store.md),
looked at or not, while one you built stands in your layer and is filed under your
[account](../store.md#the-one-thing-saved-without-being-declared). Called before that session is in the
world it has nothing to put back, says so in the log, and remembers the name anyway.

| Call | Does |
|---|---|
| `w:remember()` | the name **your** addon remembers it under, or `nil` |
| `w:remember(name)` | remember it, and put back what that name holds; chains |
| `w:remember(nil)` | stop remembering it, and **delete** what was saved under it; chains |

**One name, one widget.** A second widget under a name your addon already holds raises: two windows sharing
one saved place would each overwrite the other every time the user moved either. Renaming a widget you
already remember is a change of mind, and is accepted. A name that is not a string raises; a widget that
has left the tree is a silent no-op, like every other write here.

**Dropping is not forgetting.** `w:revert()`, `:reload` and disabling your addon drop the binding and leave
the record standing — where the user put a window is theirs, and your addon reloading is not them changing
their mind. `w:remember(nil)` is the one thing that deletes it.

## Hiding a native widget carries a restore

**Visibility is a property, so one verb reads and writes it**: `w:visible()` answers, `w:visible(false)`
hides and `w:visible(true)` shows, and both writes chain. The write is the important line on this page:
**hiding a native widget records the restore.** Disabling your addon or `:reload`ing it gives the widget
back under **one rule — it ends up as the user was seeing it**: visible exactly when whatever you put in
its place was on screen. Hide something and put nothing there, and it stays hidden on teardown; the user
was not seeing it, and the toggle you get back (below) is what opens it again. A relog correctly skips the
restore entirely. `w:visible(true)` gives it back yourself and drops the record.

**One widget, one owner.** A native widget another addon has already hidden is not yours to hide:
`w:visible(false)` refuses with an error naming the addon that holds it. Its toggle can only drive one
thing, so two owners would leave the menu tick lying about both. [`replace`](replace.md) meets the same
rule from the other side and *logs* it rather than throwing, because it runs on the client's own placement
path: that one replacement is skipped, naming the addon that got there first.

A hidden server widget stays fully **live** — still bound to its id, still receiving updates, still filling
with items. That is why you can hide a grid and keep [reading it](items.md).

### Hiding a native window takes its toggle

If what you hid is one of the windows the client itself can open — the inventory, equipment, the character
sheet, kin, options, the map, the action search — **you also own its toggle**. The client's key and its
menu button both stop reopening it, and the menu button's tick goes off:

```lua
local s = hafen.session():current()
s:ui():inventory():parent():visible(false)  -- the window around the grid: Tab opens nothing
s:ui():inventory():visible(false)           -- the grid alone: the window is still the client's
```

**What you own is what you point at.** The toggle belongs to the *window*, so hiding a widget inside one —
the inventory grid, a button — leaves that window, and its key, exactly as stock.

Without this, hiding would not be authoritative: the keybinding fires the menu button's own click, and both
land in one place inside the client that flips the window straight back on, so an addon that hid the stock
inventory would get it back on the next Tab, sitting on top of its replacement.

The toggle is **swallowed** while nothing stands in for the window: pressing the key does nothing, and the
tick tells the truth about what is on screen. Giving the widget back gives the toggle back with it — the
same restore as above, so `w:visible(true)`, disabling your addon and `:reload` all hand the key to the
client again. That is also the escape hatch for a `w:visible(false)` typed into the `:lua` console:
`:reload`, not a relog.

**With [`w:replace(view)`](replace.md), the toggle drives your view instead.** Tab, or the menu button, or
whichever key that window uses, opens and closes the window *you* built, and the menu tick reads your view's
own visibility, so it cannot drift out of sync with what is on screen. There is nothing to wire: the verb is
the only place that knows both halves — the window it hides, and the view you handed it — so it binds them
itself.

**There is no verb for this.** Nothing to register, nothing to release. Ownership follows the hide, and it
is per window: hiding the inventory leaves equipment, the character sheet, kin, options and the map
behaving exactly as stock.

## The client reuses its windows

Everything you hold on one of the client's widgets is held on **that widget**, not on what it currently
means. The client reuses its windows — the frame one container came in is the frame the next one gets — so
a place, a size or a [caption](edit.md#what-a-window-says) you put on one is still there when it comes back
as something else. That is the same rule that makes the restore reliable, seen from its awkward side.

Watch for the widget rather than holding it, and you decide what happens each time it appears:

```lua
hafen.session():current():ui():on("window", "Added", function(win)
  win:title(nil)          -- whatever this frame was last used for, it is not that any more
end)
```

Dropping a level on a widget you are holding nothing on is a no-op, so the callback needs no test of its own.

## See also

- [widget](widget.md#owned-vs-borrowed) — which writes answer on which widget
- [edit](edit.md) — changing what one of the client's controls does
- [replace](replace.md) — hiding a whole window and standing yours in its place
- [style/geometry](style/geometry.md) — saying the same placement as a rule instead of a verb
- [selectors](selectors.md) — naming the widget you are about to move
- [style/chrome](style/chrome.md#padding) — `padding`, the other property that moves a window
- [the pixel](pixels.md) — what the two numbers in `:position(x, y)` and `:size(w, h)` mean
