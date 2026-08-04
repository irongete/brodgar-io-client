# hafen.ui: placing and hiding the client's own widgets

Three writes answer on a widget you do not own: `:position(x, y)` moves it, `:size(w, h)` resizes it and
`:visible(false)` takes it off screen. All three are **ungated** — they are client-side placement, not an
action — and all three record what they found, so everything is given back when your addon goes away.

```lua
local inv = hafen.ui():find("window[title=Inventory]")
inv:position(40, 200)     -- move it
inv:size(300, 220)        -- resize its CONTENT; the chrome repacks around it
inv:position(nil)         -- drop YOUR move: back to where the user had it
```

`:pack()` and `:destroy()` stay refused on a widget you do not own: those destroy the client's work rather
than sit on top of it. See [owned vs borrowed](widget.md#owned-vs-borrowed) for the whole table.

## Moving and resizing (ungated)

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

**Reading back.** `w:position()` answers within the parent, in widget-local px — a window's coordinate is
relative to whatever contains it, and the HUD is not the root, so use
[`:rootPos()`](widget.md#read) when you need screen coords. `w:size()` reads a window's **outer** box while
`w:size(w, h)` sets its **content** size, which is the same asymmetry a window you built has: the chrome is
derived, not set.

**A position always lands; `size` does not overrule a window that owns its own.** Some of the client's windows
pack themselves around their contents whenever anything resizes them — the main inventory is one — so
`w:size(w, h)` on those is honoured and then undone by the client before the call returns. That is
**inert, never an error**, and it leaves nothing behind; read `:size()` back if you need to know which kind
you are holding. It is the same rule the sheet's [`pad`](style/chrome.md#pad) follows: a size applies where
the surface can re-lay itself out, and a surface that fixes its own size cannot.

Two addons may each hold a layer over the same widget — unlike
[hiding](#hiding-a-native-widget-carries-a-restore), a position is not a toggle. The last write wins on
screen, and each addon restores what *it* found.

**The verb is the top of a cascade, not the only way in.** The sheet says the same two things with
[`pos` and `size` rules](style/geometry.md), matched rather than named, and the verb sits above whatever a
rule resolved — so `w:position(nil)` drops *your* level and falls back to the rule when one still names
the widget, reaching the stock value only when nothing does.

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
hafen.ui():inventory():parent():visible(false)  -- the window around the grid: Tab no longer opens it
hafen.ui():inventory():visible(false)           -- the grid alone: the window is still the client's
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

## See also

- [widget](widget.md#owned-vs-borrowed) — which writes answer on which widget
- [replace](replace.md) — hiding a whole window and standing yours in its place
- [style/geometry](style/geometry.md) — saying the same placement as a rule instead of a verb
- [selectors](selectors.md) — naming the widget you are about to move
- [style/chrome](style/chrome.md#pad) — `pad`, the other property that moves a window
