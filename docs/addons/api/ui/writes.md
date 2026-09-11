# hafen.ui: which writes answer on which widget

Every read on the [Widget object](widget.md) answers on every widget; the **writes** are what provenance
decides. A widget is **owned** if *your* addon created it — a surface you [painted](custom.md), a
[column](column.md) or a [control](controls/README.md) you built — and **borrowed** otherwise: a native
client widget, or another addon's. This page is the table of which write answers on which, and the one
write that has no borrowed half at all: [greying a widget out](#enabled-and-disabled).

```lua
local win = hafen.ui():window():title("Harvest"):position(80, 120)   -- owned: every write answers
local inv = hafen.session():current():ui():inventory()      -- borrowed: the reads, and a few writes
inv:position(40, 40)                     -- a level over the client's own place, and it restores
win:enabled(false)                       -- greyed out, whole: no press, no key, dimmed
inv:enabled(false)                       -- error: its state is the client's
```

## Owned vs borrowed

Reads answer on both; `:info().owned` and [`:owned()`](widget.md#read) tell you which you are holding, so
you can ask rather than provoke the error.

| Method | Owned | Borrowed |
|---|---|---|
| `:position(x, y)` | move, and chain | **works** — [it is a layer, and it restores](native.md) |
| `:size(w, h)` | resize the content, chrome repacks around it, and chain — and the box is yours from then on, where a [pack](custom.md#packing-a-surface-around-what-is-inside-it) had it following the content | **works**, same |
| `:size(w)` | set the width and keep the height a [control](controls/README.md#sizing)'s own art gives it, or a [column](column.md#the-box-follows-the-content)'s children | **error** — the client's widget has no art of yours to ask |
| `:pack()` | size it to what is inside it — a window's chrome or a bare widget alike — and [follow it from then on](custom.md#packing-a-surface-around-what-is-inside-it); chains. A [column](column.md#the-box-follows-the-content) refuses, being packed by construction | **works on a window** — [it refits, as a level that restores](edit.md#your-own-controls-inside-one-of-the-clients-windows); a control refuses |
| `:parent(w)` | choose what it hangs under while it is being built — [one of the client's own windows included](edit.md#your-own-controls-inside-one-of-the-clients-windows) | **works** — [take it into a surface of yours, and `nil` gives it back](native.md#taking-one-into-a-surface-of-your-own-unprotected); a window you take keeps the id the client tracks it by, and gets it back with the window |
| `:destroy()` | remove it and everything in it, and chain | **error**, same reason |
| `:revert()` | give back everything your addon holds on it and on what is inside it | **works**, same — [the one undo for a whole edit](edit.md#taking-the-whole-edit-back) |
| `:enabled(b)` | grey it out, or bring it back, and chain — [what a disabled widget is](#enabled-and-disabled) | **error** — its state is the client's, and the client keeps driving it |
| `:text(s)` | write the caption of a [control](controls/README.md) you built | **works** — [a level over what it says, and it restores](edit.md#what-a-window-says) |
| `:title(s)` | write the caption of a window you built | **works**, same — a window's caption is this verb wherever it came from |
| `:tooltip(s)` | write the line that appears when the pointer rests on it | **error** — those are the client's own words about its own button |
| `:image(up, down [, hover])` | give a [control](controls/interactive.md#a-caption-or-a-picture) you are building its pictures | **error**, same reason |
| `:value(v)` | write what a [control](controls/README.md#setters) holds | **works, and it is the one PROTECTED write here** — [driving the client's own control](edit.md#driving-one-protected) is what the user would have done, and the server sees it |
| `:source(h)` | give a [picture control](controls/display.md#picture) its content | **error**, same reason |
| `:rows(t)` | give a [radio](controls/interactive.md#radio) or a [listbox, dropdown, menu, grid or table](lists.md#rows-listbox-dropdown-menu) its rows | **error**, same reason |
| `:range(min, max)` | set the bounds of a [slider or scrollbar](controls/interactive.md#slider) you built | **error**, same reason |
| `:bind(opt)` | join a [control](controls/README.md) you built to [an option your addon declared](../client/addon.md#binding-a-control-shows-the-option): it takes the option's value, the user moving it writes the option, and a write to the option moves it; `:bind(nil)` unbinds | **error** — what it holds is the client's, and [driving it](edit.md#driving-one-protected) is `:value(v)`; `:bind()` still reads, as `nil` |
| `:rowHeight(n)` | set a [listbox, dropdown, menu or table](lists.md)'s row height while it is being built | **error**, same reason |
| `:cellSize(w, h)` | set a [grid](lists.md#grid)'s cell box while it is being built | **error**, same reason |
| `:columns(t)` | name a [table](lists.md#table)'s columns while it is being built | **error**, same reason |
| `:visible(b)` | show or hide it, and chain | **works** — [see hiding](native.md#hiding-a-native-widget-carries-a-restore), except on a [radial menu](../flowermenu.md#drawn-or-not-unprotected), where both writes refuse naming `s:flowermenu():visible(b)` and the read still answers. **One window, one owner, in both directions**: `:visible(true)` on a window another addon is holding raises, naming that addon, exactly as `:visible(false)` does — and on a window you [replaced](replace.md) it ends the substitution, so the stand-in goes with the record |
| `:draggable(h)` | hand the move to the user, by a handle they press | **works** — [and what a drag writes is your position level](native.md#letting-the-user-drag-it-unprotected) |
| `:resizable(h)` | hand the box to the user, by a handle they press | **works** — [and what a resize writes is your size level](native.md#letting-the-user-resize-it-unprotected) |
| `:remember(name)` | keep its place and box under a name of yours | **works**, same — [and it puts them back on the call](native.md#remembering-where-the-user-put-it-unprotected) |
| `:replace(view)` | **error** — a window you created is not one to stand in for | **works** — [put your own window in its place](replace.md) |
| `:rule()` | restyle it and its subtree through your own level | **works**, same |

One of these writes is protected, and it is the one that is not client-side state: `:value(v)` on a
**borrowed** control drives it as the user would, so the server sees it, and it needs the `widget.value`
[key](../../guides/permissions.md) — like [`:send`](widget.md#send-a-message-protected). Every other write
here changes only your own client, and every one of them restores.

**That refusal does not depend on the widget still being there.** `:value(v)` on a borrowed control asks for
the key first and answers the stale-receiver no-op second, so an addon that did not declare it hears the same
thing whether or not the widget is in the tree — as [`:send`](widget.md#send-a-message-protected) and
`s:console():run` already did. A protected verb's gate is always its first statement.

**A field the client hides what you type into reads `nil`.** A password entry, and the *hearth secret* the
Kin window holds, answer `nil` to `:text()`, `:value()` and `:info()` — whatever they contain, and however
you reached them. They are the user's own secrets, put there by the user or by the server; nothing in this
API reads one back, and no permission buys it. Writing one is still `:value(v)` under
[`widget.value`](../../guides/permissions.md), because typing into a field is exactly what that key is for.
The [Kin window](../kin.md#the-row-is-where-the-walk-ends) also stops a `:parent()` walk from inside, so the
row `kin:widget()` hands you cannot be climbed out of.

**Arity is the verb.** `w:position()` reads, `w:position(x, y)` writes and `w:position(nil)` drops your write;
`w:size()` (`{w=, h=}`), `w:visible()`, `w:enabled()`, `w:draggable()`, `w:resizable()` and `w:remember()`
are the same shape — with `w:size(w)` as the arity a [control](controls/README.md#sizing)'s own art earns it
— and so is every setter on `w:rule()`. That is why there is no `:move()`, no `:show()`, no `:hide()` and no
`:disable()`: a value belongs in the argument, not the verb's name. It is also why nothing stands beside
[`w:remember(name)`](native.md#remembering-where-the-user-put-it-unprotected) to *apply* what it kept — the
only moment that would be correct is the moment you name it, which is a step rather than a verb.

**Replacement is the one place where the read has a name of its own.** `w:replace(view)` is an *act* and
the thing standing in is a *replacement*, so the two do not share a spelling: `w:replacement()` reads,
`w:replace(view)` installs and `w:replace(nil)` undoes.

Provenance comes from the tree, not from how you obtained the object: a surface your addon built reads as
owned through every handle to it, and one of the client's own reads as borrowed however deep inside your own
window you built it. Addon B looking at addon A's window holds a *borrowed* widget, which is the correct
answer.

**A widget's place is on the screen, not in the world.** `:position()` and `:rootPos()` answer in
[design pixels](pixels.md) and hand back a plain `{x=, y=}` table, never a [Position](../position.md). The
verb is the same word because the question is the same one — *where is this thing, in the space it lives in*
— and the object says which space, so [`s:player():move`](../player.md#write-protected) refuses a widget's
coordinates instead of walking a character somewhere that merely has the same two numbers. It holds out in
the world too: the [**panel**](../virtual/widgets.md) standing there is a second object, answering to `panel`,
whose place is a Position — so a typo on either is answered in the vocabulary of the one in hand.

## Enabled and disabled

`w:enabled()` reads whether a widget takes input, `true` from birth; `w:enabled(false)` greys it out and
chains, and `w:enabled(true)` brings it back. Reach for it where a group of controls depends on a switch:
disable the [column](column.md) the group stands in, and every row in it is greyed and silent until the
switch is flipped.

```lua
local panel = hafen.ui():column():gap(4):parent(win):position(0, 0)
local sw    = hafen.ui():check():parent(panel):text("Advanced")
local group = hafen.ui():column():gap(4):parent(panel)
hafen.ui():check():parent(group):text("Only ripe")
hafen.ui():entry():parent(group):size(120)
group:enabled(false)                                       -- greyed, and the entry takes no key
sw:on("Changed", function(on) group:enabled(on) end)       -- the switch outside it brings it back
```

While a widget is disabled — or any widget you built above it is — it is one thing on four counts:

- **It is drawn dimmed**, and so is everything under it: a control's caption and art, a bare surface's
  [stock](custom.md#naming-and-dressing-your-own-surfaces), and every colour a `Draw` handler of yours names
  is drawn at a fraction of itself. A button and a checkbox also wear the `disabled`
  [face](style/chrome.md#a-face-per-state) a rule names on [`button`](style/surfaces.md#button) or
  [`checkbox`](style/surfaces.md#checkbox-scrollbar-and-slider), and a button the client's own grey with it.
- **A press on it is swallowed.** Neither it nor what lies beneath sees the press, the release or the wheel
  turn: `Pressed`, `Changed` and `Submitted` never fire, nor a `MouseDown` of yours on it, nor a
  [`Drop`](custom.md#drop-makes-a-widget-a-drop-target), and a window does not drag from a greyed button in
  it. It still occupies its place: the press does not fall through to a widget behind it.
- **No key reaches it.** A disabled entry loses the keyboard the moment it is disabled, Tab passes over it,
  and a hotkey a widget under it would have answered goes on to the rest of the screen.
- **Every write of yours still lands.** `:value(v)`, `:text(s)`, `:visible(b)`, the geometry — a disabled
  widget is inert to the user, not to you — and `:value()` reads what you wrote. Its tooltip still shows: a
  query is not an input.

**The read is the widget's own flag**, as `:visible()` reads its own: a child of a disabled column reads
`true`, and it is the column that reads `false`. `:info().enabled` is the same flag. Disabling the column
changes nothing in the rows, so enabling it again brings every one of them back exactly as it was — a row
you disabled on its own stays disabled until you enable it.

**A window you built is disabled whole.** Its frame dims with its content, and nothing on it takes a press
— the caption does not drag and its close button does not close. `:visible(false)` and `:destroy()` still do
what they say, being writes of yours.

**A borrowed widget answers the read and refuses the write.** One of the client's own reads `true` —
nothing disables the client's widgets through this API, because the client's own logic keeps driving them,
and a grey of yours over one would go on changing underneath — and another addon's reads the flag that addon
set. The write raises on both, naming the client. To stop what one of the client's controls does, subscribe
on it and cancel ([edit](edit.md)); to keep one off the screen, [`:visible(false)`](native.md).

## See also

- [widget](widget.md) — every read, subscribing, and the one protected message
- [native](native.md) — what the borrowed half of the table actually does, and the restore behind it
- [edit](edit.md) — taking over what one of the client's own controls does
- [controls](controls/README.md) — the setters a control you built takes
- [column](column.md) — the surface a group of controls is disabled through
