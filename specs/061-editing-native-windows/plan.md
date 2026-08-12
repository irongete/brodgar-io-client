# 061 — plan

## Approach

Four mechanisms, in the order the tasks build them. Nothing here invents a shape: each one is an existing
shape widened to a borrowed widget.

### A. The capability key on a borrowed control (061.1–061.4)

`WidgetSubs` already groups a widget's keys three ways: the four input keys need a `Widget.listen`
listener, the three tree keys ride the placement/removal seams, and **nine keys need nothing installed at
all** — the Java method that already runs fires straight into `subs` via `Addon.widgetSubsOrNull`, which
costs one map lookup and mints nothing for a widget nobody listens to.

A borrowed capability key is that third group, from the other side: a `// addon:` line at the client's own
activation site asks whether any addon holds that key on that widget, fires them all, and answers whether
the client should proceed. One helper carries every family — `AddonWidgets.activate(Widget owner, String
key, Object value)` → `boolean proceed` — so the sixteen call sites are sixteen identical one-liners and
the whole decision lives in one place.

**The seam goes at the INPUT site, never at the overridable hook.** This codebase has learned that rule
twice — `Widget.remove` vs `cdestroy`, `Widget.resize` vs `Window.resize`, both recorded in
`docs/client/widgets.md` — and every family here repeats it: `Button.click()` is overridden by ~35 `haven`
classes, `SListWidget.change` is overridden by `SDropBox` (which does not call `super`), `HSlider.changed`
is an empty hook meant for overriding. A notification a subclass can skip is not a seam. So each site is
where the *client itself receives the input*, immediately before it calls its own method — and, for the two
button families, **after the grab has been released**, so a handler may cancel, defer, or destroy the
window without leaving a `UI.Grab` outstanding.

**And the read the whole group is stated against (061.2).** `widget:value()` routes through
`ownedContent`, which is `null` on a borrowed widget, so today it answers `nil` on every native control —
*what a cancelled tick left the box at* is not observable at all. It gains the second half `:text()` has
always had: a best-effort class switch (`ACheckBox.state()`, `HSlider`/`Scrollbar`'s `val`,
`TextEntry.text()`, `SListWidget.sel`, `Progress`) tried when there is no owned adapter, `nil` on a widget
that holds nothing, never throwing — one method, the same discipline as `LuaWidget.text(Widget)`, so
upstream churn breaks that switch and nothing else. It lands in **061.2**, the first task whose suite
needs it, and it belongs there rather than beside the write: `Changed` is already defined as *the
notification half of the `:value()` spine*, so making both halves answer on a borrowed control is one
claim, not two.

### B. `ev`, and `resend` as a re-entrancy flag (061.1)

The event object is `hafen.event():action()`'s, one level down: `ev:preventDefault()` cancels,
`ev:resend()` implies it and runs the client's own action, and `resend` **bypasses every handler for that
key** so re-issuing cannot loop. `action()` gets that last property from a split path (`UI.rawWdgmsg`);
here the client's own method *is* the path, so it is a re-entrancy flag on the seam, set while the replay
runs and cleared after.

`resend` is legal from a later frame — the seam sits after the grab is released, so no gesture state is in
flight — and **raises on a widget that has left the tree**, the same refusal `widget:send` gives, because
the point of reissuing is that something happens and a silent no-op would lie. It may be called more than
once; each call runs the control's own action once. There is no `ev:send(t)` twin: what is deferred is a
method, not a message, so there are no arguments to rewrite.

### C. The text level (061.5–061.6)

**Two verbs, one level.** `widget:text(s)` writes a control's caption and `widget:title(s)` a window's,
which is the split the API already has and the one the maintainer keeps: *a title is a window's `cap`,
text is everything else*. Both refusals are live today and point at each other (`Controls.text` sends a
window to `:title(s)`, `title(s)` sends a control to `:text(s)`), so 061 widens each verb to a borrowed
widget and adds no spelling. Giving the window's caption to `:text(s)` was rejected — see *Discarded
alternatives*.

`LuaWidget.Moved` already holds, per (addon, widget) in `Addon.movedNative`, a stock half and a wanted half
for position and size plus an apply-order stamp. Text is one more pair on the same record — the stock
value and this addon's level — restored by the same sweep, dropped when nothing of this addon's is left
on the widget. `:text(nil)` / `:title(nil)` drops the level; there is no rule beneath it, because content
is not style.

**The stock half is not always a string.** A `Label` and a `Window` restore from one; a `Button`'s caption
is *three* fields (`rtext`, `rcol`, `rwrap`), and `change(String)` sets `rcol = null`, `rwrap = 0` — so
restoring a coloured or a wrapped (`ltbtn`) button through it would give the caption back rendered wrong.
The record keeps the three, and the restore goes back through the arm the stock caption came from.

**The re-apply rides a tap that already exists.** `AddonManager.onUimsg(wdg, msg)` runs *post-apply*,
after the client has taken the server's update and after an L3 `hafen.event():message()` handler could have
swallowed it (a swallowed one never reaches the tap, and never landed either — which is the right
answer). That is exactly the moment W2 needs. It runs on a **Loader thread** outside the `ui` monitor
(`docs/client/network.md`), so the tap only marks and the write happens in the UI-thread drain beside
`drainRemovedWidgets`/`drainResolveQueue`/`drainBeltSet` — one frame, the house pattern, and no text
rasterised off the UI thread.

**The tap carries no args**, only `(Widget, String msg)` — so "the record's stock half becomes the
server's new value" is done by **reading the widget back** at the drain, before this addon's level goes
on top. At that instant the widget is showing exactly the server's value and nothing else, which is what
makes the re-read equivalent to having had the args, and it is the only place in the frame where that is
true.

### D. Structure, and the protected act (061.7–061.8)

`widget:parent(nativeWindow)` already works — `LuaWidget`'s `parent(w)` requires the child to be owned,
the parent to be live, and the child to be **still being built** (`c.pending()`; an armed widget is moved
with `:position(x, y)` instead, and the verb says so), while `Window.xlate` already offsets a child into
the content area. **Adoption is therefore a build-time verb**, and that is the shape the page teaches:
you build a control *into* one of the client's windows, you do not re-home one that is already on screen.
061.7 documents and proves it, adds `:pack()` on a borrowed window through the same `Moved.size` record so
`:size(nil)` still restores, and closes the destroy gap below.

`widget:value(v)` on a borrowed control is the one **act**: it drives the control through the very method
the client calls, so `canactivate` and the outgoing `wdgmsg` behave exactly as a real interaction. It is
gated by the new `widget.value` permission key, **checked first** (D-213) before the widget or the value is
looked at, and it does not restore — an act has nothing to give back. Because the interception seams sit at
the *input* sites and `:value(v)` drives the *funnel*, the two never re-enter each other.

### E. Undoing every edit at once (061.9)

Each verb already has its own undo — `:text(nil)`, `:size(nil)`, `sub:off()`, `:destroy()` — and teardown
runs all of them on `:reload`, on disable and at exit. What none of them does is undo **a whole edit** at
a moment the addon chooses, which is what an addon that arms its edits by hotkey needs (the bundled `bags`
is exactly that shape). `widget:revert()` is that verb.

**It is a verb, not a handle.** An object whose only method is `revert()` is an object standing in for a
verb, and *arity is the verb* is how this API says the same thing everywhere else.

**Its scope is this widget and its subtree, as the tree stands when it is called** — which is the whole
reason the scope question has an answer at all. An edit is never confined to one widget: the example on
`edit.md` writes a caption on a window, adopts a button into it, and takes over the **close button**,
which is neither of those two. The subtree covers all three, and being per-widget it lets an addon that
edited two windows revert one.

It drops, for **this addon only**: the text level, the position and size levels (falling back to a sheet
rule that still names the widget, exactly as `:text(nil)`/`:size(nil)` do), the hide record under its own
"as the user was seeing it" rule, this addon's `w:rule()` level, every capability and input subscription
it holds anywhere in that subtree, and every widget it **adopted** into it, destroyed. It does **not**
undo a `widget:value(v)` — an act has nothing to give back, which is the tier line stated one more time —
and it does **not** end a `widget:replace(view)`: replacing is the alternative to editing, not a kind of
it, and it has its own read and its own undo. `revert()` on a widget this addon holds nothing on is a
no-op that chains.

## The seam catalogue

| Family | Key | Core site (`// addon:`) | Cancel means | `resend` runs |
|---|---|---|---|---|
| `Button` | `Pressed` | `mouseup`, before `click()`; `gkeytype` | the action never runs | `click()` |
| `IButton` | `Pressed` | `mouseup`, before `click()`; `gkeytype` | same | `click()` |
| `CheckBox` | `Changed` | `mousedown`, before `click()` | the box does not flip, nothing is sent | `click()` |
| `ICheckBox` | `Changed` | `mousedown`, before `click()` | same | `click()` |
| both, keyboard | `Changed` | `ACheckBox.gkeytype` | same | `click()` |
| radio | `Changed` | `RadioGroup.RadioButton.mousedown` | the selection does not move | `check(this)` |
| list · dropdown · menu | `Changed` / `Selected` | `SListWidget.ItemWidget.mousedown`, before `list.change(item)` | `sel` is untouched | `list.change(item)` |
| list, click-away | `Changed` | `SListBox.unselect`'s `change(null)` path | the selection stays | `change(null)` |
| grid | `Cell` | `GridList.mousedown`, before `itemclick(item, b)`, **button 1 only** | `sel` is untouched | `itemclick(item, 1)` |
| text entry | `Submitted` | `TextEntry.done(ReadLine)` **and** `gkeytype`, before `activate(text)` | nothing reaches the server | `activate(text)` |
| slider | `Changed` | where the client writes `val` (`HSlider`'s drag path) | **nothing — uncancelable** | — |
| scrollbar | `Changed` | **both** value writes — `Scrollbar.update` (the thumb drag) and `ch(int)` (wheel and step; `ch(double)` delegates to it) | **nothing — uncancelable** | — |

**Three rows are where a base-class hook would have lied, and each is why the rule is the rule.**

*The text entry is the sharpest.* `TextEntry.activate(String)` looks like the funnel — it holds the
`canactivate` gate and the `wdgmsg` — but it is `public` and **the chat entry overrides it and never
calls `super`** (`ChatUI.EntryChannel`'s anonymous `TextEntry`, which sends the line itself). Seaming
there would have missed the one entry every player types into. `done(ReadLine)` (Enter, through
`ReadLine`) and `gkeytype` are where the client *receives* the key, both call `activate(buf.line())`, and
neither is overridden there. `resend` calls `activate(text)` **virtually**, so it reaches the chat's own
override and the line is sent exactly as the player sent it.

*The grid selects on one button.* `GridList.mousedown` routes through `itemclick(item, ev.b)`, which
changes the selection only for button 1; a right-click reaches the same method and changes nothing. A
seam at the top of `mousedown` would fire `Cell` for a gesture that is not a selection and let a cancel
eat the client's own context menu. The click-away (`change(null)` on an empty cell, button 1) is a real
selection change and fires, the same call `SListBox.unselect` makes one row up.

*A radio button is a `CheckBox` and answers `gkeytype` as one.* `RadioGroup.RadioButton` overrides
`mousedown` (so the `CheckBox.mousedown` seam correctly never sees it) but **not** `gkeytype`, so a
keybound radio arrives at the `ACheckBox` seam. That seam therefore dispatches on the widget: a
`RadioButton` carries the radio's row, everything else carries `!state()`. One key must not mean two
shapes on one widget depending on whether the player used the mouse.

**Who the handler belongs to.** A click funnels through `ItemWidget.mousedown`, whose `list` field is the
`SListWidget` that owns the row — which for a dropdown is the popup `SDropBox.SDropList` and for a menu is
`SListMenu.InnerList`, neither of which is the widget an addon holds, and the dropdown's popup is not even
a child of it (it adds itself to `ui.root`). So `SListWidget` gains a `// addon:` `slistowner()` returning
`this`, overridden in those two inner classes to return the enclosing control. One accessor, three
families, no reflection and no upward walk that a root-parented popup would break.

## Files to create / modify

**`haven` core** — every edit is one `// addon:` line at a call site, plus the one accessor:
`Button.java` (`mouseup`, `gkeytype`) · `IButton.java` (same two) · `CheckBox.java` (`mousedown`) ·
`ICheckBox.java` (`mousedown`) · `ACheckBox.java` (`gkeytype`) · `RadioGroup.java` (`RadioButton.mousedown`)
· `SListWidget.java` (`ItemWidget.mousedown`, and `slistowner()`) · `SListBox.java` (`unselect`) ·
`SDropBox.java` (`SDropList.slistowner`) · `SListMenu.java` (`InnerList.slistowner`) · `GridList.java`
(`mousedown`, the selecting button) · `TextEntry.java` (`done`, `gkeytype` — **not** `activate`) ·
`HSlider.java` (the drag's value write) · `Scrollbar.java` (`update` and `ch(int)`, its two value writes).

`haven.AddonWidgets` is where the one `activate` helper goes — it is already the `haven`-side door the
`035` chrome seam calls, so the sixteen sites reach the bridge the way `Window.tick` already does.

**`io.brodgar.addon`** — `WidgetSubs` (the borrowed capability group) · `LuaEvent` (the `ev`: `preventDefault`, `resend`, the value
it carries) · `Controls` (which key a borrowed control has, and the refusal that names the ones it does) ·
`LuaWidget` (`:text(s)` and `:title(s)` on borrowed, the borrowed `:value()` read switch, `:pack()` on
borrowed, `:value(v)`, and the `Moved` text triple) · `Layout` (the text level's apply/restore/sweep) ·
`AddonManager` (the `onUimsg` mark and the tick drain) · `Permission` + `PermissionSet` (`WIDGET_VALUE`).

**`docs/addons`** — **`api/ui/edit.md` (NEW, the sister page of `replace.md`)** · `api/ui/native.md` (stays
*placing and hiding*, and links to its sibling) · `api/ui/widget.md` (the owned/borrowed table, the
subscribing table, the `:value()` read row, `:text` vs `:value`) · `api/ui/controls/README.md` (the
capability keys on a borrowed control, and the cancelable column) · `api/ui/controls/interactive.md` ·
`api/ui/lists.md` · `api/ui/custom.md` (`:title(s)` and `:parent(w)` leave the owned-only block of
builder setters, so each row says where it now answers) · `api/ui/replace.md` (the two stale paragraphs
in the derived set, and the fourth line of *Where replacing ends*) · `guides/permissions.md`
(`widget.value`).

**`edit.md` is the one structural decision about the docs.** *Editing* and *replacing* are the two things
an addon can do to one of the client's windows, and the reader who has just read one must find the other
next to it — so the pairing lives in the page names, where a paired **API** would have cost a second
vocabulary (see *Discarded alternatives*). It carries the story and the reference for the editing verbs:
what editing is and when it beats replacing, the `:text` says / `:value` holds line, `:text(s)`,
`:pack()` on a borrowed window, adopting your own controls with `:parent(w)`, the capability keys on a
borrowed control, and one worked example that does all four to one window. `replace.md`'s *Where replacing
ends* gains the line that sends the reader here: rearranging what a window puts inside itself is
replacing; changing a part of it is editing. Every page keeps one home per fact — the owned/borrowed table
stays in `widget.md`, the key roster stays in `controls/README.md`, and `edit.md` links rather than
repeats.

**061.1 creates `edit.md`**; 061.5, 061.7, 061.8 and 061.9 each add their own section as they ship, so
the page is complete and current at every commit rather than written once at the end — and 061.9 closes
it, since *how you take an edit back* is the last thing the story has to say. **The worked example lands
with 061.7**, not with the page: it renames a window, gives it a button of the addon's own, refits the
frame and takes over the close button, so 061.7 is the first commit at which it can be written at all.

**`docs/client`** — nothing to create. `ui-controls.md` and `ui-lists.md` already carry every funnel this
plan reads: the `Button`/`IButton` press order, the checkbox's `mousedown` activation, `RadioButton`
bypassing `CheckBox.mousedown`, `HSlider.changed`/`fchanged`, `Scrollbar`'s missing second half,
`TextEntry.done` → `activate` and its `canactivate` gate, `SListWidget.change` + `ItemWidget.mousedown`,
`SListBox.unselect`'s `change(null)`, `SDropBox`/`SListMenu` being one level removed, and `GridList`'s
native-only selection. Any discrepancy found while implementing is fixed in the page by the task that
found it.

## Risks & gotchas

- **`Widget.destroy()` does not remove its children.** It is `remove()` on itself plus `rdispose()`, which
  recurses `dispose()` only. So a control an addon parented into a native window never runs `remove()` when
  that window closes, the `onWidgetRemoved` seam never fires for it, and `widget:on("Destroy")` stays
  silent — while `hasparent(ui.root)` correctly goes false, so reads go stale as they should. 061.7 routes
  the addon's owned widgets through `dispose()`, which `rdispose` *does* reach on every descendant.
- **`SIWidget.resize` does not `redraw()`.** `Button` caches its rasterised face; a caption written by
  `:text(s)` needs `Button.change(String)` (which re-renders and redraws), never a field poke.
  `CheckBox.lbl` is already `public` with a `settext(String)` added by an earlier fork edit; `Label` has
  `settext`; a window caption is `Window.chcap(String)`, and `DefaultDeco` re-renders when `cap.text` no
  longer matches. **An `IButton` has no caption at all** — it is three `BufferedImage` faces and nothing
  else — so `:text(s)` refuses on one naming what does have text. It answers `Pressed` like any button;
  that is the whole of its part in this feature.
- **`Button.change(String)` is lossy.** It sets `rcol = null` and `rwrap = 0`, so a caption restored
  through it comes back uncoloured and unwrapped — invisible on the plain buttons a suite reaches first
  (`rcol` and `rwrap` are already zero there) and wrong on an `ltbtn` or a coloured one. The stock half
  of the record is the triple, not the string, and 061.5 asserts the round trip on a caption that has
  something to lose.
- **Cancelling is asymmetric between owned and borrowed, deliberately.** `Controls.fire` calls
  `subs.fire(key, args)` — the overload with no `Subs.Cancel` — so a capability key on a control the
  addon **built** has never been cancelable, and 061 does not change that: there is nothing underneath
  your own button to cancel. The consequence is that one key on one widget is cancelable for the addons
  that borrowed it and not for the one that owns it, so the *Cancelable* column becomes per family
  **and** per provenance, and the page must say so where it says "either handler cancels".
- **A window that packs itself undoes `:size` and `:pack`.** `native.md` already states this for `:size`
  ("inert, never an error"); `:pack()` inherits it verbatim rather than growing a second rule.
- **A checkbox activates during `mousedown`, and `Window.mousedown` raises itself after propagation
  returns**, so a handler that destroys the checkbox's own window from the click is not automatically safe
  — the page must repeat `ui-controls.md`'s advice to defer such a destroy a tick.
- **`IButton.checkhit` samples the picture's alpha bounded by `sz`**, so a native `IButton` resized past
  its art throws *from the input pass*. Nothing here resizes one, but `:size(w, h)` on a borrowed widget
  can — the risk is named so the docs do not invite it.
- **A native control lives inside your own controls, and that is both the gift and the trap.**
  `ownedContent` reads a widget as owned only when it *is* this addon's `Owned` or has one as a direct
  child, so the `ICheckBox` arrow an `SDropBox` builds for a `:dropdown()`, the `RadioGroup.RadioButton`s
  a `:radio()` mints, a list's own `Scrollbar` and every window's `DefaultDeco.cbtn` all read as
  **borrowed** — `ui-controls.md` already names that close button as the reliable way to find a control
  an addon did not build. Two consequences: every suite here has a native target without depending on
  which game windows happen to be open, and **the seam must not double-dispatch** — it fires the borrowed
  key only for an addon whose `ownedContent` on that widget is `null`, so an addon that owns the control
  keeps the dispatch `Controls` already does for it and never receives both.
- **`ui.lcc` and `Coord` are mutable**; anything the seam stores from an event must be copied.
- **Two addons, one widget**: the handlers are per (addon, widget) as `WidgetSubs` already is, so layering
  costs nothing new. The text level is per addon on `Moved`, with the same apply-order stamp `position`
  uses.
- **A text level outlives what the window meant.** The level is keyed to the widget, and the client reuses
  windows, so a caption an addon wrote can end up over a different container. This is the accepted
  behaviour (see *Discarded alternatives*); `native.md` states it and points at
  `hafen.ui():on(sel, "appear", …)` as the way to drop it.

## Discarded alternatives

- **An `edit()` API paralleling `replace()` — a `hafen.ui():edit(w)` section, or a `w:edit()` facade.**
  Rejected, and the pairing answered with a page instead. Two reasons, and the second is the one that
  decides it. First: every operation here already has a verb on `Widget`, so each method of the facade
  (`e:title` beside `w:text`, `e:add` beside `:parent`, `e:resize` beside `:size`) is a second spelling of
  a verb that exists — the dual style the grammar forbids. Second: **an edit is not one object's business
  the way a replacement is.** `w:replace(view)` earns a handle because it *creates* something with a
  lifetime — the view is born, drawn and destroyed with the substitution, which is why `w:replacement()`
  has something to read. Editing creates nothing; it writes properties of widgets that are already there,
  and the moment an edit touches something that is not the window itself — your own button, the client's
  close button — the facade has to hand back a Widget anyway, so it is a door you walk through to reach
  the verbs you were going to use. The conceptual pairing the reader wants is real, and it is served by
  `edit.md` sitting beside `replace.md`.
- **A revert-only `w:edit()` handle.** The *capability* was kept (061.9) and the *shape* rejected: an
  object whose only method is `revert()` is an object standing in for a verb, which is what `w:revert()`
  is instead. Minting a handle would also have made the scope read as the handle's, when it is the
  widget's subtree.
- **Scoping `revert()` to the addon rather than to a widget** — one call that gives back everything this
  addon holds anywhere. Rejected: it cannot tell an addon that edited two windows how to put back one of
  them, and its blast radius is invisible at the call site. The per-widget verb composes into the
  addon-wide one (`revert()` each window you edited); the reverse does not.
- **Letting `revert()` end a `widget:replace(view)` as well.** Rejected: replacing is the *alternative* to
  editing, not a kind of it — it is why the two pages are siblings — and it already has a read
  (`:replacement()`) and an undo (`:replace(nil)`). A verb that destroys a whole stand-in window as a side
  effect of "undo my edits" is a blast radius nobody would predict from the name.
- **Undoing `widget:value(v)` in `revert()`.** Rejected: the write went to the server as a real
  interaction. Putting the checkbox back is another interaction, not an undo, and pretending otherwise
  would be the one place this feature lied about the tier line.
- **A new `Activate` event key for native buttons.** Rejected: `Pressed` is already defined as *an
  activation, not a mouse position*, one that *also fires from the keyboard* and is *safe for the handler
  to destroy the window the button sits in*. A second spelling for a definition that already fits is how
  an API grows two ways to say one thing.
- **Seaming the overridable hook (`Button.click`, `SListWidget.change`, `HSlider.changed`).** Rejected: a
  notification a subclass can skip is not a seam — ~35 `haven` classes override `click()` and `SDropBox`
  overrides `change` without calling `super`, so the hook would miss exactly the windows worth editing.
- **Seaming `TextEntry.activate(String)`** — the same mistake wearing a funnel's clothes, and the one
  this plan had to be talked out of. It *looks* canonical: both key paths call it, and it holds the
  `canactivate` gate and the `wdgmsg`. But it is `public`, and `ChatUI.EntryChannel`'s entry overrides it
  and never calls `super` — so the seam would have been silent on the one text entry in the client every
  player uses, and 061.4's own suite targets it. The rule survives contact: the seam goes at `done` and
  `gkeytype`, where the client *receives* the key. `resend` still calls `activate(text)`, virtually, so
  the chat's own override does the sending.
- **Giving a native window's caption to `:text(s)`.** Rejected: `widget:title(s)` is already the caption
  verb, and the two refusals in the tree point at each other today — `Controls.text` sends a window to
  `:title(s)`, `title(s)` sends a control to `:text(s)`. Writing a borrowed caption through `:text(s)`
  would give one operation two spellings *chosen by provenance*: the author would have to know whether
  the window was theirs before knowing which verb writes its title. The split is *a title is a window's
  `cap`, text is everything else*, and 061 widens both verbs instead of blurring the line. (The **read**
  `:text()` has always answered on a `Window` as part of its best-effort roster; that is a catch-all
  read, is documented as one, and is left alone.)
- **Swapping `Button.action` (a public `Runnable` field).** Rejected for the same reason and one more: it
  covers only the buttons that *use* the field, and it would have to be restored on teardown, turning an
  interception into a piece of installed state the client can outlive.
- **Cancelling at `MouseUp` with `preventDefault` (what an addon can do today).** Rejected as the shape to
  document: `Button.mousedown` grabs and only `mouseup` releases, so a cancelled `mouseup` orphans a
  `UI.Grab` forever — a client-wide input interceptor until restart.
- **`ev:send(t)` beside `resend`.** Rejected: what is deferred is a method call, not a message with
  arguments. A verb exists when there is something to say with it.
- **Making `Changed` cancelable on a slider or a scrollbar.** Rejected: the client writes `val` before it
  says anything, so "cancel" would mean revert-and-repaint — a different verb — and the seam would sit in
  a drag's per-`mousemove` path. It fires uncancelable instead, using the *Cancelable* column the
  subscribing table already has.
- **Letting the server win a text overwrite (the simple half of W2).** Rejected: an addon's caption would
  vanish minutes later, from a message nobody saw, and read as a bug in the addon.
- **Dropping the text level when the server sends a *different* value** (the clever half of W2). Rejected:
  it makes the level's lifetime depend on a value comparison an addon cannot predict or read back, to
  avoid a case — a reused window — that one `"appear"` subscription already handles explicitly.
- **`widget:value(v)` unprotected for the controls whose write stays client-local** (a native `Progress`, a
  scroll position). Rejected: one verb, one key. A tier that depends on which widget you happen to hold
  means an addon author cannot tell from the call whether they need a permission.
- **`:value(v)` on a native `Progress`.** Rejected outright: `Progress.val` is a `Supplier` the client
  re-reads every frame, so the write would be overwritten before it was seen. It refuses, naming that.
- **`:rows(t)`, `:range`, `:columns`, `:cell`, `:rowHeight` and `:image` on a borrowed control.** Rejected:
  they are the client's own model and art, rebuilt on the client's own schedule — a write there is not a
  layer over anything, it is a value waiting to be discarded.
- **Re-applying the text inside `AddonManager.onUimsg` directly.** Rejected: that tap runs on a Loader
  thread outside the `ui` monitor, and text rasterisation belongs on the UI thread; the existing one-frame
  drain is the pattern three other off-thread taps already use.
- **A `text` property in the stylesheet, so a rule could restyle content.** Rejected: content is not
  style. `pos`/`size` earned a rule because placement is a property of a surface; what a widget *says* is a
  fact about that one widget.
