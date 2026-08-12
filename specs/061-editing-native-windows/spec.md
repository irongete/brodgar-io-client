# 061 — editing a native window

## What & why

The borrowed half of `hafen.ui` observes and *places*. Everything past that is `:replace(view)`, which
asks an addon to re-implement a whole window to change one button. This feature makes the small edit cost
a small addon: change a caption, put your own button inside the client's frame, take over what one of its
controls does, and drive one as the user would.

**It adds no new section, no new object and — outside the protected verb — no new name.** Every verb
below already exists on a widget your addon built; what changes is that it answers on a **borrowed** one
too. The one page that says which verb answers where is the *owned vs borrowed* table in
[`ui/widget.md`](../../docs/addons/api/ui/widget.md), and this feature flips cells in it. A developer who
learned `hafen.ui()` by building their own window has nothing new to learn but the tier line below.

### The five writes

- **W1 — content.** `widget:text(s)` / `:text(nil)` writes a native `Label`, a `Button` caption or a
  `CheckBox` label over the stock value, and gives it back. A **window's caption is `widget:title(s)` /
  `:title(nil)`**, the verb that already writes one on a window you built: the API's split is *a title is
  a window's `cap`, text is everything else*, and 061 widens both verbs rather than growing a second
  spelling for either. The two refusals that already point at each other keep pointing.
- **W2 — the level survives the server.** When the server rewrites that text (`Label` `set`, `Button`
  `ch`, `Window` `cap`) the addon's level is **re-applied**, and what is recorded for the restore becomes
  the server's *new* value — so dropping the level later gives the user the current text, never a stale
  one. One record serves both verbs. The level belongs to the **widget**, so a window the client later
  reuses for something else keeps it; the page says so, and `hafen.ui():on(sel, "appear", …)` is how an
  addon that cares drops it.
- **W3 — behaviour.** The **capability key** of a control answers on a borrowed control too, and there it
  is cancelable, because there something sits underneath to cancel. `Pressed` on a native button,
  `Changed` on a native checkbox, radio, list or dropdown, `Selected` on a native menu, `Submitted` on a
  native text entry, `Cell` on a native grid — the same keys, same `:on(key, fn)`, same `sub:off()`.
  `ev:preventDefault()` stops the client's own handling; `ev:resend()` runs it, from this frame or a
  later one.
- **W4 — structure.** `widget:parent(nativeWindow)` puts a control **you** built inside one of the
  client's windows — the code already allows it, nothing documents or proves it — and `widget:pack()`
  answers on a native window, so the frame refits around what is now in it.
- **W5 — one undo for the whole edit.** `widget:revert()` gives back everything **this addon** holds on a
  widget and its subtree: the text, the place, the size, the hide, its `:rule()` level, every
  subscription, and every control it adopted, destroyed. Each verb keeps its own undo; this is the one an
  addon needs to turn an edit **off mid-session** without reloading, which is the shape the bundled
  `bags` already has. The subtree is the scope because an edit is never confined to one widget — the
  worked example writes a caption, adopts a button and takes over the **close button**, which is neither.
  It does not undo `:value(v)` (an act has nothing to give back) and it does not end a `:replace(view)`
  (that has its own undo).

**And the one read they are all stated against.** `widget:value()` answers `nil` on a borrowed control
today — it routes through the owned adapter and a native widget has none — so nothing above can be
*checked* without it: what a cancelled tick left the box at, what value a drag arrived at, what a driven
control now holds. It starts answering on a native checkbox, radio, slider, scrollbar, list, dropdown and
text entry, through the same best-effort class switch `:text()`'s read has always been. It is a read:
unprotected, no layer, nothing to restore, `nil` where a widget holds nothing.

### The tier line: what it says, what it holds

`:text(s)` is what a widget **says**; `:value(v)` is what it **holds**. That distinction is already in
the API (`widget.md` lists them as two reads) and it is exactly where the permission line falls:

- Writing what a widget **says** — `:text(s)` on a control, `:title(s)` on a window — is decoration. It
  never leaves the client, it layers, it restores, and it is **unprotected** — like `:position` and
  `:visible` before it.
- Writing what a control **holds** is doing what the user would do. Ticking a native checkbox runs
  `ACheckBox.set` → `changed` → `wdgmsg("ch", …)`: the server sees it. So `widget:value(v)` on a
  borrowed control is **protected** by a new per-verb key, `widget.value`, and it is an **act, not a
  layer** — there is nothing to restore, exactly as `item:drop` has nothing to restore.

That is why `:text(s)` refuses on a native `TextEntry` naming `:value(v)`: typing into the client's field
is not decoration. It mirrors the refusal an owned entry already gives (`Retired.message("entry:text")`).

**`ev:resend()` reaches the server, and stays unprotected — the page says why rather than leaving it
implied.** A resent `Pressed` runs `click()`, which for most native buttons ends in a `wdgmsg`. It is not
the addon acting: the seam only exists because the **user just made that gesture**, `resend` cannot run
without one, and it can only re-issue the action the control already had. That is the same rule the L3
`hafen.event():action()`'s `resend` runs under, and it is the whole difference from `:value(v)`, which
acts from nothing and is therefore keyed.

### Layers, not one owner

Two addons may each write a text or hold a `Pressed` handler on one widget. The last write wins, either
handler cancels, both handlers still run, and each addon restores what *it* found — `:position`'s rule
rather than `:visible`'s, because none of these carries anything indivisible. `widget:value(v)`, being an
act, has no ownership question at all.

### Where cancelling is a lie, and the column that already says so

A slider and a scrollbar tell the client their value **after** it has moved: `HSlider.changed()` and
`Scrollbar.changed()` are hooks that run once `val` is already written, and a drag emits a stream of
them. So `Changed` fires on those two and is **not cancelable** — `ev:preventDefault()` raises, naming
that the value has already moved. The subscribing table in `ui/widget.md` has had a *Cancelable* column
since 041, so this is a shape the reader already knows, not an exception.

## Acceptance criteria

Each is verifiable in-game through the owning task's own suite.

1. `:text(s)` writes a native `Label`, `Button` caption and `CheckBox` label, and `:title(s)` a native
   `Window` caption; `:text()` and `:title()` read back what was written. `:text(s)` on a native `Window`
   refuses naming `:title(s)`, exactly as it already does on a window you built.
2. `:text(nil)` / `:title(nil)` restores the stock value, and so do `:reload` and disable. A second write
   replaces the level rather than stacking, so one `nil` is always enough. A `Button` whose stock caption
   carries a colour or a wrap comes back with **both** — the caption is three fields, not one string.
3. A server overwrite of a text this addon holds does not win: the addon's value is back within a bounded
   window, and `:text(nil)` / `:title(nil)` afterwards yields the **server's latest** value, not the one
   from before it.
4. `Pressed` fires on a native `Button` and `IButton`, from a click and from its keybinding; two handlers
   both fire; either one's `ev:preventDefault()` stops the client's own action.
5. `ev:resend()` runs the control's own action, does **not** re-enter any handler for that key, and
   answers from a later frame. On a widget that has left the tree it raises, naming that.
6. `widget:value()` reads back what a **borrowed** control holds — a native checkbox's boolean, a radio's
   row, a slider's and scrollbar's number, a list's and dropdown's row, a text entry's string — and `nil`
   on a widget that holds nothing. Every criterion below that says *what the control is left at* is
   checked through it.
7. `Changed` fires on a native `CheckBox`, `ICheckBox` and radio button, from a click and from the
   keyboard, carrying the value the control is **about to** take; cancelling leaves the control unflipped
   and sends nothing. A radio's mouse and keyboard paths carry the **same** value, though the client
   reaches them by two different methods.
8. `Changed` fires on a native list and dropdown, and `Selected` on a native menu, carrying the row;
   cancelling leaves the selection untouched. One seam serves all three, and the handler is called on the
   widget the addon holds — the dropdown, not its dropped-down list.
9. `Cell` fires on a native grid **for the selecting button only**, so a right-click that opens the
   client's own menu is not a selection and is not cancelable as one; cancelling leaves the selection
   untouched.
10. `Submitted` fires on a native text entry when the user presses Enter, carrying the line; cancelling
    sends nothing to the server. It fires on the **chat** entry, which overrides `activate` and never
    calls `super` — proof the seam sits where the client receives the key rather than where a subclass
    may replace it.
11. `Changed` fires on a native slider and scrollbar carrying the new value — on a scrollbar from a drag
    of the thumb **and** from the wheel, which are two separate value writes — and `ev:preventDefault()`
    there **raises**, naming that the value has already moved.
12. A control the addon `:parent()`ed into a native window while it is being **built** is drawn and
    clickable inside that window's content area; `:pack()` on that window refits it around the control;
    `:size(nil)` gives the stock box back.
13. That control's `Destroy` fires when the native window closes, `:exists()` is then false, and a
    `:reload` afterwards raises nothing.
14. `widget:value(v)` drives a native checkbox, radio, slider, scrollbar, list, dropdown and text entry
    the way the user would, and without the `widget.value` key declared it raises **naming that key**
    before it looks at anything else.
15. `widget:revert()` gives back, in one call, a text, a size, an adopted control and a subscription held
    anywhere in that widget's subtree — the window reads exactly as it did before the addon touched it.
    On a widget this addon holds nothing on it is a no-op that chains and raises nothing.
16. `revert()` leaves a `:replace(view)` standing and leaves a `:value(v)` write alone, and the page says
    which verb undoes each.
17. Refusals, each naming what to do instead: `:text(s)` on a native `TextEntry` (→ `:value(v)`),
    `:text(s)` on a native `Window` (→ `:title(s)`), `:title(s)` on anything that is not a window
    (→ `:text(s)`), `:text(s)` on a widget with nothing to say, `:value(v)` on a native `Progress` (its
    value is a `Supplier` the client re-reads every frame), a capability key on a widget that does not
    have it, and `ev:send(t)` (there is no message here to rewrite).

## Out of scope

- Making a **non-window** native widget floating or draggable — a re-home past `getparent(GameUI.class)`,
  which ~30 sites dereference and `Inventory.mousewheel` dereferences unguarded. Its own feature.
- `:rows(t)`, `:range(min, max)`, `:columns(t)`, `:rowHeight(n)`, `:cell(w, h)` and `:image(…)` on a
  borrowed control: that is the client's own data model and its own art, which it rebuilds on its own
  schedule, so a write there is overwritten rather than honoured.
- A `text` property in the stylesheet — content is not style, so `:text(s)` has one level and no rule
  beneath it.
- Rearranging the client's **own** children inside a window; that stays `:replace(view)`.
- Item slots, the belt and the map view: they are not controls, and each already has its own section.

## Docs impact

**Created**: `ui/edit.md` — the **sister page of `replace.md`**. Editing and replacing are the two things
an addon can do to one of the client's windows, so the pairing the reader expects lives in the page names
rather than in a second API surface (`plan.md` records why an `edit()` handle was refused). It carries
the story and the reference for the editing verbs, and links rather than repeats: the owned/borrowed table
stays in `widget.md`, the key roster in `controls/README.md`.

**Written**: `ui/native.md` (stays *placing and hiding*, and points at its sibling) · `ui/widget.md` (the
owned/borrowed table, the subscribing table, the `:value()` read row, and the `:text`/`:value` pair) ·
`ui/controls/README.md` (the capability keys answer on a borrowed control; the cancelable column, which
becomes per family **and** per provenance) · `ui/custom.md` (`:title(s)` may name a native window, and
`:parent(w)` too) · `ui/replace.md` (*Where replacing ends* gains the line that sends the reader to
`edit.md`) · `guides/permissions.md` (the `widget.value` key).

**Derived impact set** — the stale sentence sits in a page this feature would not otherwise open:

- `grep -rn "not yours to do\|caption is the client" docs/addons/` → `ui/widget.md:166`,
  `ui/widget.md:168` — two of the table cells that flip from **error** to a write (`:pack()` and
  `:text(s)`); the `:value(v)` row below them flips too, and the `:value()` **read** row
  (`ui/widget.md:68`) stops being true of an owned control alone.
- `grep -rn "title" docs/addons/api/ui/custom.md` → `custom.md:37` — the builder-setter table says the
  thirteen setters are owned-only; `:title(s)` and `:parent(w)` leave that group, and the row must say
  where each now answers rather than the block statement above it.
- `grep -rn "read-only" docs/addons/api/ui/` → `ui/replace.md:107-109`: *"A widget's own state is
  otherwise read-only … `:text()` is a best-effort read rather than a write"* — stated in the negative,
  and false after 061.5. `ui/replace.md:113-116` (*Where replacing ends*) splits restyle / place /
  rearrange and now needs its fourth line.
- `grep -rln "Pressed" docs/addons/` → `ui/controls/README.md`, `ui/controls/interactive.md`,
  `ui/custom.md`, `ui/widget.md`, `vr/widgets.md` — the last says a standing widget answers the same
  callbacks, which stays true and must keep saying so.
- `grep -rn "exactly \*\*one\*\* capability key\|plus its own capability keys" docs/addons/` →
  `ui/controls/README.md:108`, `ui/widget.md:141` — both say a capability key belongs to a control you
  built; both now say *a control*, owned or not.
- `grep -rn "widget.send" docs/addons/guides/permissions.md` → the catalogue table `widget.value` joins.
- ROADMAP defect folded into 061.5, being the same comment that task edits: *`UiApi`'s comment gives
  `:text()`'s roster without the `CheckBox` arm (filed: 055)*.

## Context files

`/implement` may load these, and nothing else. Untagged lines are read by every task.

**Always** — `DOCUMENTATION.md`, `docs/addons/api/ui/widget.md`, `docs/addons/api/ui/native.md`,
`src/io/brodgar/addon/LuaWidget.java`

- `src/io/brodgar/addon/WidgetSubs.java` — 1, 2, 3, 4, 9
- `src/io/brodgar/addon/LuaEvent.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/Subs.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/LuaMarshal.java` — 2, 3, 4, 8 (the one Java→Lua marshal `ev:value()` and a
  borrowed control's own `:value()` go through, a native list's arbitrary row included)
- `src/io/brodgar/addon/Controls.java` — 1, 2, 3, 4, 5, 8, 9
- `src/io/brodgar/addon/CCheck.java`, `CICheck.java`, `CRadio.java` — 2, 8
- `src/io/brodgar/addon/CList.java`, `CDropdown.java`, `CMenu.java`, `CGrid.java` — 3, 8
- `src/io/brodgar/addon/CEntry.java`, `CSlider.java`, `CScrollbar.java`, `CProgress.java` — 4, 8
- `src/io/brodgar/addon/Addon.java` — 1, 5, 6, 9
- `src/io/brodgar/addon/AddonManager.java` — 1, 6, 8
- `src/io/brodgar/addon/Permission.java`, `PermissionSet.java` — 8
- `src/io/brodgar/addon/Layout.java` — 5, 6, 7, 9
- `src/io/brodgar/addon/Sheet.java` — 9
- `src/io/brodgar/addon/UiApi.java` — 5, 7, 9
- `src/haven/AddonWidgets.java` — 1, 2, 3, 4
- `src/haven/Button.java`, `src/haven/IButton.java` — 1
- `src/haven/ACheckBox.java`, `CheckBox.java`, `ICheckBox.java`, `RadioGroup.java` — 2, 8
- `src/haven/SListWidget.java`, `SListBox.java`, `SDropBox.java`, `SListMenu.java`, `GridList.java` — 2, 3, 8
- `src/haven/TextEntry.java`, `HSlider.java`, `Scrollbar.java`, `Scrollport.java`, `Progress.java` — 2, 4, 8
- `src/haven/ChatUI.java` — 4 (its entry overrides `activate` without `super`: the seam's proof)
- `src/haven/OptWnd.java` — 2, 4, 5, 7, 8, 9 (every suite's native target, and which panel holds it)
- `src/haven/Label.java` — 5
- `src/haven/Window.java` — 5, 6, 7
- `src/haven/UI.java` — 6
- `src/haven/Widget.java` — 7
- `docs/addons/api/ui/controls/README.md`, `controls/interactive.md` — 1, 2, 3, 4, 8
- `docs/addons/api/ui/lists.md` — 3, 8
- `docs/addons/api/ui/edit.md` — 5, 7, 8, 9 (created by 1; each of these adds its own section)
- `docs/addons/api/ui/replace.md` — 1, 5, 7, 9
- `docs/addons/api/ui/custom.md` — 5, 7
- `docs/addons/guides/permissions.md` — 8
- `docs/client/ui-controls.md` — 1, 2, 3, 4, 8
- `docs/client/ui-lists.md` — 3, 4, 8 (every list, slider and scrollbar funnel this feature seams, and where
  the one always-reachable native list is)
- `docs/client/ui-chrome.md` — 9 (what a window's close button actually runs, which its suite takes over)
- `docs/client/widget-input.md` — 3
- `docs/client/widgets.md` — 7
- `docs/client/network.md` — 6
