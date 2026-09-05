# Actionbars

**This addon replaces the client's action bar.** The client draws one bar and pages it: twelve buttons out
of the 144 belt slots the server keeps for each character, with one key row turning the page. Actionbars
puts that bar away and stands in for it — Actionbar1 *is* the page it started on — and then lets you put
the other eleven pages on screen at the same time, lying flat or standing upright, wherever you want them.

| You do | It does |
|---|---|
| set a row to `flat` or `upright` in **Options ▸ AddOns ▸ Actionbars** | puts that bar on screen, lying that way |
| set a row to `off` | takes that bar away. What is in its slots stays on the server, untouched |
| press `Reset bars position` | puts every bar back in the middle of the screen, one under the next |
| type `:actionbars`, or press **Actionbars** in the action menu | says which bars are on, and where they are managed |
| press `Go to page N` | pages Actionbar1, the way it pages the client's own bar |
| drag an action onto a button | puts it in that slot |
| left-click a button | fires it, modifiers and all |
| right-click a button | empties it — or hands back a slot held for an addon's own menu entry |
| **drag the bar anywhere but a filled button** | moves it; where you drop it is where it stands on every character |
| rest the pointer on a button | names what is in it |

## The settings are the client's own page

**Options ▸ AddOns ▸ Actionbars is the whole of it.** This addon has no window: the page holds twelve rows,
one per bar, and each row says what that bar is — `off`, `flat` or `upright`. There is nothing to open,
nothing to place and nothing to close, and the page is the same page every other setting in the client is
edited on.

A row is a fact about one page of the belt, which is why there are twelve of them rather than a list with an
`Add` button under it: a bar's number is its identity, so turning Actionbar4 on is a different thing from
"add a bar", and a bar you turn off and back on comes back with its own slots, its own keys and the place you
last dragged it to.

Under the rows are `Reset bars position` and one line saying how many bars are up — and what the last press
did, since a page is where somebody who just pressed something is looking.

Neither door the addon still has opens anything. `:actionbars` at the console and the **Actionbars** entry in
the action menu each print which bars are on and where they are managed; the console command is the one that
answers before you are in the world.

## A bar is a page, and its number says which

**Actionbar*N* is slots (*N*−1)×12+1 to *N*×12, permanently — for every *N* but the first.** Actionbar2 is
slots 13–24, Actionbar3 is 25–36, and so on to Actionbar12 at 133–144.

That is why the number is an identity rather than a position in a list. Remove Actionbar2 and Actionbar3
goes on showing the same twelve slots it always showed; add a bar again and it comes back with its own
slots and its own keys. `Actionbar3 slot 5` names one button of the game for as long as the character
exists, which is the only way a hotkey for it can mean anything.

It also fixes the ceiling. **Twelve bars is every slot there is** — 144 of them — which is why the page holds
twelve rows and there is no thirteenth to turn on.

**Actionbar1 is the exception, and it pages.** It stands in for the bar the client draws, so it does what
that bar did: it shows **whichever page you are on**, and `Go to page 3` in Options ▸ Keybindings ▸ Action
bar moves it to slots 25–36. Hover its frame and the tooltip says which page it is showing.

So the main bar is the one that moves and the other eleven are the ones that stay. That is the point of
having both: one bar that follows the page the way the game's own always did, and as many nailed-down ones
as you want beside it.

**Actionbar1 cannot be removed** either: its row offers `flat` and `upright`, and no `off`. It is the page
you are on, and the client's own bar — the one that otherwise shows it — is put away by this addon. A screen
with neither would leave the current page with no way to be pressed.

## The bar is the handle

A bar wears `Window.wbox`, the client's own panel box — the frame around the portrait, the inventory, the
party avatars and the skill lists — on the dark translucent field the client fills a framed box with. So it
sits on the HUD as one of the game's own panels rather than as a row of squares floating over the map.

The frame is drawn from the same eight pieces the client's own `IBox` draws — corners at their own size,
edges stretched between them, centre never painted — so it holds that weight at every interface scale.

**You drag the bar by anything that is not a loaded button.** The frame, the margin, the gutters between
the buttons and any button standing empty all pick it up; only a button with something in it to fire keeps
its click. A bar you have not filled yet therefore drags end to end, which is exactly when you want to be
placing it.

That falls out of the client's own dispatch order rather than out of two widgets negotiating. A widget's
handlers run before the client descends into its children, so the bar sees every press first: one it takes
is the drag's loss, and one it leaves alone reaches the drag handle underneath, which is the whole bar.

## Flat or upright

Each bar's own row says which way it stands, and choosing the other one rotates it.
Bars are independent: a long flat bar under the map and two short upright ones down the side is an ordinary
arrangement. Rotating keeps the bar's number, its slots and its keys — it is the same twelve buttons, laid
out the other way.

## When a bar has gone off the edge

`Reset bars position` on the settings page puts **every bar back in the middle of the screen**, one under the
next in the order of their numbers, and saves them there.

It is there because a bar's place is written in the client's own design pixels, and the screen measured in
those shrinks when you raise the **Interface scale**: the art is drawn larger, so fewer of them fit across
the window. Everything that was near an edge can end up past it — and a bar past the edge cannot be dragged
back, since the whole bar is its own handle and none of it is on screen. A smaller window does the same
thing.

The bars are stacked rather than piled in the same spot, so all of them are visible at once and you can drag
them back where you want them from there. It needs a character in the world: there is no screen to measure
from the login screen, and the line under the rows says so.

## Where the bars live, and why

The bars hang on the **character's HUD**, not in the addon layer. That
is forced rather than chosen: the action menu ends its drag on the *session's* widget tree, and the addon
layer is a tree of its own that the drop never reaches. A bar built there would draw and click perfectly,
and every action you dragged at it would fall straight through into the map.

So a bar is built for each character as it enters the world and goes with it. What you see is one bar per
character, all carrying the same number and the same slots, all standing in the same place — drag one and
the others follow.

## Keys

Every button of every bar gets a hotkey named for what it is:

```
Options ▸ Keybindings ▸ Actionbars
    Actionbar1 slot 1
    Actionbar1 slot 2
    …
```

They start **unbound**, like every addon hotkey: the client gives one key to one action, so an addon that
claimed a key already in use would simply lose it and leave you with a hotkey that never fires. Yours is the
assignment.

A button prints its key in the corner the way the client's own bar does, and its own number while it has
none. The key belongs to the client's registry rather than to this addon, so removing a bar and adding it
back gets the same keys — and a `:reload` never costs you an assignment.

### Nothing is reserved

**No key is claimed by hardcoding, so bind whatever you like.** The row `1` through `0` is twelve ordinary
bindings, listed in **Options ▸ Keybindings ▸ Action bar** with the twelve page keys beside them, and matching
is exact: `Ctrl+3` is a binding of its own rather than button 3 with a modifier ignored.
`F1`–`F12` are free too, with all three modifiers — nothing in the client defaults to a function key.

### Two sections, and which to use

Hiding the client's bar does **not** silence its keys, deliberately: a binding the panel lists should do
what it says. So there are two places that reach Actionbar1's twelve buttons, and both work:

| Options ▸ Keybindings ▸ | Reaches |
|---|---|
| **Action bar** — `Button 1`…`12` | the twelve buttons of the page you are on, which is Actionbar1 |
| **Action bar** — `Go to page 1`…`12` | which page Actionbar1 shows |
| **Actionbars** — `Actionbar1 slot 1`…`12` | the same twelve, by the bar's name |
| **Actionbars** — `Actionbar2 slot 1`… | every other bar, each nailed to its own page |

Both rows that reach Actionbar1 do the same thing, and both already work out of the box: `1`–`0` press its
first ten buttons and `Alt+1`–`Alt+0` turn its page, exactly as they always did, only now on a bar you
placed yourself. Leave `Actionbar1 slot …` unbound if you like — it is there for buttons 11 and 12, which
the number row never reached.

One thing the client still decides for you: **in combat**, `1`–`5` and `Shift+1`–`5` are the combat-move
bindings, and a combat window is offered a key before any bar is. Bind those elsewhere if you fight with
your hotbar.

## What it asks for

Three permissions, all about the action bar and nothing else:

- `actionbar.use` — *press the action-bar buttons*, which is what a click and a hotkey do
- `actionbar.res` — *assign one of the game's own actions to a slot*, which is what a drag does
- `actionbar.clear` — *empty a button*, which is what a right-click does

The client asks you to approve them the first time you enable the addon.

## What it saves

**Where each bar stands** is the addon's own saved variable, kept for the **account**: the same places on
every character, and a bar you drag on one moves on all of them. A bar keeps its place while it is off, so
turning it back on puts it where you left it.

**Which bars are on and which way round they stand** is not saved here at all — it is what the twelve rows
hold, and a row's value belongs to the client, exactly like the interface scale or a volume. It survives a
`:reload`, a disable and a restart, and this addon can neither wipe it nor has to save it.

The *contents* of the slots are neither: they are the server's, kept per character, and this addon neither
copies them nor needs to — a bar is a window onto slots that were already there.

## Turning it off

Disabling or reloading the addon **puts the client's own bar back**, where it was. The addon does that
itself, from its `Disable` handler: the automatic restore that comes with hiding a native widget is written
for a widget you replaced with something of your own, and reads a bare hide as "the user was not seeing it",
which would leave the bar hidden for good — it has no toggle to reopen it with. So it is given back by hand,
and only the bars this addon hid.

Nothing else is left behind: the bars go, the client's bar returns, and the slots have whatever you left in
them.

## What it cannot do

**Take another addon's menu entry.** An entry an addon added belongs to that addon, and only that addon can
hold a slot for it — so dragging one onto a bar here is refused, and the refusal names the owner in the log.
The game's own actions all work, and so do this addon's.
