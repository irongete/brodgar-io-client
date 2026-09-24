# hafen.ui: Owned and Borrowed Writes

Every read on a [Widget](widget.md) answers on every widget. Which writes answer is decided by provenance — owned (your addon built it) or borrowed (the client's, or another addon's).

```lua
local harvest_window = hafen.ui():window():title("Harvest"):position(80, 120)   -- owned: every write answers
local inventory = hafen.session():current():ui():inventory()                    -- borrowed: reads, and a few writes
inventory:position(40, 40)          -- a level over the client's own place; restored on reload
harvest_window:enabled(false)       -- greyed out whole: no press, no key, dimmed
inventory:enabled(false)            -- raises: its state is the client's
```

---

## Owned vs borrowed

`:owned()` and `:info().owned` answer which you hold. Provenance comes from the tree, not from how you obtained the handle. A surface your addon built is owned through every handle to it. A client widget is borrowed however deep inside your own window you re-homed it. Another addon's window is borrowed.

| Method | Owned | Borrowed |
|---|---|---|
| `:position(x, y)` | Moves it. Chains. | Works: [a level over the client's place, restored on reload](native.md). |
| `:size(w, h)` | Resizes the content, the chrome refits. Chains. The box is yours from then on, where a [pack](custom.md#packing-a-surface-around-what-is-inside-it) had it following the content. | Works, same. |
| `:size(w)` | Sets the width. The height is a [control](controls/README.md#sizing)'s own art or a [column](column.md#the-box-follows-the-content)'s children. | Raises: the client's widget has no art of yours to ask. |
| `:pack()` | Sizes it to what is inside it and [follows it from then on](custom.md#packing-a-surface-around-what-is-inside-it). Chains. A [column](column.md#the-box-follows-the-content) refuses: it is packed by construction. | Works on a window: [it refits, as a level that restores](edit.md#your-own-controls-inside-one-of-the-clients-windows). A control refuses. |
| `:parent(w)` | Chooses what it hangs under while it is being built, [a client window included](edit.md#your-own-controls-inside-one-of-the-clients-windows). | Works: [takes it into a surface of yours, `nil` gives it back](native.md#taking-one-into-a-surface-of-your-own-unprotected). A window keeps the id the client tracks it by. |
| `:destroy()` | Removes it and everything in it. Chains. | Raises. |
| `:revert()` | Gives back everything your addon holds on it and inside it. | Works, same: [the one undo for a whole edit](edit.md#taking-the-whole-edit-back). |
| `:enabled(b)` | Greys it out or brings it back. Chains — [below](#enabled-and-disabled). | Raises: the client keeps driving its own state. |
| `:text(s)` | Writes a [control](controls/README.md)'s caption. A key button refuses: its caption is the key it shows. | Works: [a level over what it says, restored](edit.md#what-a-window-says). |
| `:title(s)` | Writes a window's caption. | Works, same. |
| `:tooltip(s)` | Writes the line shown when the pointer rests on it. | Raises: the client's words about its own button. |
| `:image(up, down [, hover])` | Gives a [control](controls/interactive.md#a-caption-or-a-picture) being built its pictures. | Raises. |
| `:value(v)` | Writes what a [control](controls/README.md#setters) holds. A key button refuses: its key is its binding's. | Works, **protected** (`widget.value`): [drives the client's control as the user would](edit.md#driving-one-protected). The server sees it. |
| `:source(h)` | Gives a [picture control](controls/display.md#picture) its content. | Raises. |
| `:rows(t)` | Gives a [radio](controls/interactive.md#radio) or a [row-source control](lists.md#rows-listbox-dropdown-menu) its rows. | Raises. |
| `:range(min, max)` | Sets a [slider's or scrollbar's](controls/interactive.md#slider) bounds. | Raises. |
| `:bind(opt)`, `:bind(binding)` | Joins a control to [an option your addon declared](../client/addon.md#binding-a-control-shows-the-option), or a [key button](controls/interactive.md#key-button) to a hotkey of yours. The control takes the option's value, or shows the hotkey's key. The user moving it writes the option, and the user's press on a key button assigns the key. A write to the option moves it. `:bind(nil)` unbinds. | Raises. `:bind()` reads `nil`. Driving a client control is `:value(v)`. |
| `:rowHeight(n)` | Sets a [listbox, dropdown, menu or table](lists.md)'s row height while it is being built. | Raises. |
| `:cellSize(w, h)` | Sets a [grid](lists.md#grid)'s cell box while it is being built. | Raises. |
| `:columns(t)` | Names a [table](lists.md#table)'s columns while it is being built. | Raises. |
| `:visible(b)` | Shows or hides it. Chains. | Works: [hiding carries a restore](native.md#hiding-a-native-widget-carries-a-restore). Refused on a [radial menu](../flowermenu.md#drawn-or-not-unprotected), naming `s:flowermenu():visible(b)`. One window has one owner: `:visible(true)` or `:visible(false)` on a window another addon holds raises, naming that addon. `:visible(true)` on a window you [replaced](replace.md) ends the substitution. |
| `:draggable(h)` | Hands the move to the user, by a handle they press. | Works: [a drag writes your position level](native.md#letting-the-user-drag-it-unprotected). |
| `:resizable(h)` | Hands the box to the user, by a handle they press. `true` on a window of yours is the [client's grip](custom.md#letting-the-user-resize-a-window-of-yours). | Works with a handle: [a resize writes your size level](native.md#letting-the-user-resize-it-unprotected). `true` is refused. |
| `:remember(name)` | Keeps its place and box under a name of yours, and puts them back on the call. | Works, same — [remember](native.md#remembering-where-the-user-put-it-unprotected). |
| `:replace(view)` | Raises: a window you created is not one to stand in for. | Works: [puts your own window in its place](replace.md). |
| `:rule()` | Restyles it and its subtree through your own level. | Works, same. |

| Rule | Detail |
|---|---|
| The one protected write | `:value(v)` on a borrowed control needs `widget.value` ([permissions](../../guides/permissions.md)), like [`:send`](widget.md#send-a-message-protected). The gate is the verb's first statement: an undeclared key raises whether or not the widget is still in the tree. Every other write here changes your own client only, and every one restores. |
| Secret fields | A password entry and the Kin window's hearth secret answer `nil` to `:text()`, `:value()` and `:info()`, however reached. No permission reads them. Writing one is `:value(v)` under `widget.value`. The [Kin window](../kin.md#the-row-is-one-way) also stops a `:parent()` walk from inside. |
| Arity is the verb | `w:position()` reads, `w:position(x, y)` writes, `w:position(nil)` drops your write. `:size`, `:visible`, `:enabled`, `:draggable`, `:resizable`, `:remember` and every `w:rule()` setter have the same shape. There is no `:move`, `:show`, `:hide` or `:disable`, and nothing beside `:remember(name)` to apply what it kept: the call applies it. |
| Replacement | The read has its own noun: `w:replacement()` reads, `w:replace(view)` installs, `w:replace(nil)` undoes. |
| A place is not a Position | `:position()` and `:rootPos()` hand back `{x=, y=}` in [design pixels](pixels.md), never a [Position](../position.md). [`s:player():move`](../player.md#write-protected) refuses one. A [panel standing in the world](../virtual/widgets.md) is a second object whose place is a Position. |

---

## Enabled and disabled

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:enabled()` | `boolean` | Unprotected | Its own flag: `true` from birth, `true` on every client widget, a child of a disabled column reads `true` while the column reads `false`. `:info().enabled` is the same flag. |
| `widget:enabled(false)` | `self` | Unprotected | Greys out a widget you built, with everything under it. |
| `widget:enabled(true)` | `self` | Unprotected | Brings it back. Rows you disabled on their own stay disabled until you enable them. |

```lua
local panel = hafen.ui():column():gap(4):parent(harvest_window):position(0, 0)
local advanced_switch = hafen.ui():check():parent(panel):text("Advanced")
local group = hafen.ui():column():gap(4):parent(panel)
hafen.ui():check():parent(group):text("Only ripe")
hafen.ui():entry():parent(group):size(120)
group:enabled(false)                                                        -- greyed, and the entry takes no key
advanced_switch:on("Changed", function(checked) group:enabled(checked) end)
```

While a widget, or any widget you built above it, is disabled:

| Effect | Detail |
|---|---|
| Drawn dimmed | It and everything under it: a control's caption and art, a surface's [stock](custom.md#naming-and-dressing-your-own-surfaces), every colour a `Draw` handler names. A button and a checkbox also take the `disabled` [face](style/chrome.md#a-face-per-state) a rule names on [`button`](style/surfaces.md#button) or [`checkbox`](style/surfaces.md#checkbox-scrollbar-and-slider). |
| Presses swallowed | Neither it nor what lies beneath sees the press, release or wheel: `Pressed`, `Changed`, `Submitted`, a `MouseDown` of yours and a [`Drop`](custom.md#drop-makes-a-widget-a-drop-target) never fire. A window does not drag from a greyed button. It keeps its place: the press does not fall through to a widget behind it. |
| No key reaches it | A disabled entry loses the keyboard at once, and Tab passes over it. A hotkey a widget under it would answer goes on to the rest of the screen. |
| Your writes still land | `:value(v)`, `:text(s)`, `:visible(b)` and the geometry apply, and `:value()` reads what you wrote. Its tooltip still shows. |
| A window disabled whole | Its frame dims with its content. The caption does not drag and the close button does not close. `:visible(false)` and `:destroy()` still work. |
| A borrowed widget | Reads `true` (a client widget) or the flag its addon set. The write raises, naming the client. To stop a client control, subscribe on it and cancel ([edit](edit.md)). To keep it off the screen, [`:visible(false)`](native.md). |

---

## See Also

- [Widget](widget.md) — every read, subscribing, and `:send`.
- [Native](native.md) — what the borrowed half of the table does, and the restore behind it.
- [Edit](edit.md) — taking over what a client control does.
- [Controls](controls/README.md) — the setters a control you built takes.
- [Column](column.md) — the surface a group of controls is disabled through.
