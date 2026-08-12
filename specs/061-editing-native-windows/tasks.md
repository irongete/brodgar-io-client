# 061 — tasks

Nine tasks, in build order. 061.1 builds the interception machinery; 061.2 adds the checkbox seams **and**
the borrowed `:value()` read, which is what makes every later suite able to check anything at all; 061.3
and 061.4 are seams plus one dispatch each, over families the machinery already carries; 061.5–061.6 are
the text level; 061.7 is structure; 061.8 is the one protected act; 061.9 gives back everything the eight
before it install, so it goes last.

**The suites' native targets.** Every suite here needs a control the addon did not build, without
depending on which game windows happen to be open. Two are always reachable: **every window's close
button** (`Window.DefaultDeco.cbtn`, a real `IButton` that `ownedContent` reads as borrowed even on the
addon's own window — `docs/client/ui-controls.md` names it as exactly this), and the **Options window**,
whose checkboxes, sliders and "Reset to defaults" button are anonymous subclasses that override
`changed()`/`click()` — the very case a base-class hook would miss, so they prove the seam choice as well
as the verb. **Options is panelled, and its panels are built on first visit** — `OptWnd.PButton.click()`
does `actual = add(tgt.get())` the first time and caches it — so a panel nobody has opened is **not in
the tree at all** and no selector finds it; after one visit it stays added but `visible = false` while
another panel shows. So a suite here does not merely want Options open, it wants **that panel opened at
least once**, and every `[manual]` line names which: "Vertical sync" and "Reset to defaults" are on
**Video settings**, "Master audio volume" on **Audio settings** (the voice panel has its own, differently
named "Master volume" — the audio one is meant), and the one scrollbar in the window is **Keybindings**'
`Scrollport`. A suite that cannot find its target reports `[fail] … panel not opened`, never a silent
pass.

---

- [x] **061.1 — `Pressed` answers on a native button.** Adds the interception machinery the next three
      tasks reuse: `AddonWidgets.activate(owner, key, value)` → `boolean proceed` (fires every addon
      holding `key` on that widget, in registration order, all of them, `preventDefault` winning by OR —
      `Subs.fire`'s existing rule), the per-(widget, key) re-entrancy flag that makes `ev:resend()` skip
      the seam while it replays, and the `ev` in `LuaEvent`: `:preventDefault()`, `:resend()`, no
      `:send(t)`. Four `// addon:` lines: `Button.mouseup` (before `click()`, after `d.remove()`),
      `Button.gkeytype`, `IButton.mouseup`, `IButton.gkeytype`. `Controls` gains the borrowed-key roster
      so `:on` on a key a widget does not have keeps naming the ones it does, and the seam **skips an
      addon whose `ownedContent` on that widget is non-null**, so an addon that owns the control keeps its
      existing dispatch and never receives both.
      **Creates `docs/addons/api/ui/edit.md`**, the sister page of `replace.md`: what editing a native
      window is, when it beats replacing, and the capability keys on a borrowed control — including the
      line that a key is cancelable on a control you **borrowed** and not on one you built, because only
      the borrowed one has something underneath it to cancel (`Controls.fire` passes no `Subs.Cancel`,
      and 061 does not change that). `replace.md`'s *Where replacing ends* gains the line that sends the
      reader here — rearranging what a window puts inside itself is replacing, changing a part of it is
      editing. The later tasks add their own sections; the page is complete for what has shipped at every
      commit. **The worked example is 061.7's**, the first commit at which all four writes exist.
      *Its suite* builds one window titled `061.1`, finds its close button with `win:find("@IButton")`
      and asserts `:info().owned` is false — the target is native even though the window is not. It
      registers two `Pressed` handlers, the first cancelling; after the first click it asserts **both**
      ran and `win:exists()` is still true (the cancel reached the client, not just the other handler),
      then swaps in a handler that calls `ev:resend()` and keeps the `ev`. After the second click it
      asserts `win:exists()` is false, and then calls `ev:resend()` again on the now-dead widget and
      asserts it raised naming that the widget has left the tree. Fully automated besides the two clicks:
      `:on("Pressed", fn)` on a native `Label` must raise listing `MouseDown, MouseUp, MouseMove, Wheel,
      Destroy`; `ev:send(t)` must raise as a retired spelling naming `resend`.
      `[manual]`: click the X on the `061.1` window — expect it to stay open.
      `[manual]`: click the X again — expect it to close.

- [x] **061.2 — `Changed` answers on a native checkbox and radio button, and `widget:value()` reads one.**
      Both halves of the same spine, which is why they are one task: `Changed` is already defined as *the
      notification half of the `:value()` spine*, and today the read half answers `nil` on every native
      control (it routes through `ownedContent`, and a borrowed widget has no owned adapter) — so without
      it *nothing this feature does to a control is observable*. `:value()` gains the fallback `:text()`
      has always had: a best-effort class switch (`ACheckBox.state()`, `HSlider`/`Scrollbar`'s `val`,
      `TextEntry.text()`, `SListWidget.sel`, `Progress`) tried when there is no owned adapter, `nil` on a
      widget that holds nothing, never throwing.
      Four `// addon:` lines: `CheckBox.mousedown` and `ICheckBox.mousedown` (before `click()`),
      `ACheckBox.gkeytype` (the base, so both families get the keyboard), and
      `RadioGroup.RadioButton.mousedown`, which calls `check(this)` directly and is the one site the
      checkbox path does not cover. `ev` carries the value the control is **about to** take (`!state()`
      for a checkbox, the button's row for a radio) — the seam runs before the flip, which is what makes
      cancelling mean *it did not happen* rather than *it happened and was undone*. **A `RadioButton` is
      a `CheckBox` and does not override `gkeytype`**, so it arrives at the `ACheckBox` seam too: that
      seam dispatches on the widget, or one key would mean two shapes on one widget depending on whether
      the player used the mouse. `resend` runs `click()` / `check(this)`.
      *Its suite* asserts, with **Video settings** opened at least once, that a native `CheckBox` there
      reads borrowed and that `:value()` reads its boolean; that a cancelled tick leaves `:value()`
      reading what it read before and nothing sent; and that a resent one flips it — the whole point
      being that the same option is on and then off with the same click. It also builds its own
      `:dropdown()` and asserts the `ICheckBox` arrow inside it reads borrowed while the dropdown itself
      reads owned, and that clicking the arrow with a `Changed` handler on **the dropdown** fires that
      handler exactly once — the no-double-dispatch rule, which is the assertion most likely to fail
      first. `:value()` on a native `Label` must read `nil` rather than raise.
      `[manual]`: open Options → **Video settings** before running; tick "Vertical sync" — expect the box
      not to change.
      `[manual]`: tick it again — expect it to change this time.

- [x] **061.3 — `Changed` and `Selected` answer on a native list, dropdown, menu and grid.** One
      `// addon:` line at `SListWidget.ItemWidget.mousedown` (before `list.change(item)`) serves all
      three list families, because `SDropBox`'s popup rows and `SListMenu.InnerList`'s rows both wrap
      through that same `ItemWidget`; a second at `SListBox.unselect`'s `change(null)` path, which is a
      real interaction (`docs/client/ui-lists.md` says so) and would otherwise be a selection change no
      handler saw; a third at `GridList.mousedown` for `Cell` — **on the selecting button only**, since
      that method routes through `itemclick(item, ev.b)` and only button 1 moves `sel`, so a seam above
      it would fire `Cell` for a right-click that selects nothing and let a cancel eat the client's own
      context menu. A grid's click-away (`change(null)` on empty space) fires, the same call the list's
      does one row up. Adds `// addon:` `SListWidget.slistowner()`
      returning `this`, overridden in `SDropBox.SDropList` and `SListMenu.InnerList` to return the
      enclosing control — the handler must be called on the widget an addon **holds**, and a dropdown's
      popup is not even its child (it adds itself to `ui.root`).
      *Its suite* subscribes `Changed` on the client's action-search list, asserts the row arrives as the
      event's value and that cancelling leaves the search's own selection untouched. It then builds its
      own `:dropdown()`, opens it, and asserts that a `Changed` handler on the popup list fires **zero**
      times for this addon while the one on the dropdown fires once — the owner mapping and the
      no-double-dispatch rule in one check. A `Selected` subscription on a native list must raise naming
      `Changed`, and `Cell` on a native list must raise naming the keys a list has.
      `[manual]`: open the action search, type a letter and click a result — expect nothing to be picked.

- [x] **061.4 — `Submitted` on a native text entry, and an uncancelable `Changed` on a native slider and
      scrollbar.** Two `// addon:` lines at `TextEntry.done(ReadLine)` (Enter, through `ReadLine`) and
      `TextEntry.gkeytype`, each before its `activate(buf.line())` — **not** at `activate(String)`, which
      looks like the funnel and is not one: it is `public`, and `ChatUI.EntryChannel`'s entry overrides
      it without calling `super`, so a seam there would be silent on the one text entry every player uses
      — this suite's own target. `done`/`gkeytype` are where the client receives the key and neither is
      overridden there; the seam still sits before the `canactivate` gate and the `wdgmsg`, so cancelling
      means the server hears nothing, and `resend` calls `activate(text)` **virtually**, so the chat's
      own override does the sending.
      Three more where the client writes a slider's or a scrollbar's value: `HSlider`'s drag path, and
      **both** of `Scrollbar`'s — `update` (the thumb drag, reached from `mousedown` and `mousemove`) and
      `ch(int)` (wheel and step; `ch(double)` delegates to it), because a seam on `ch` alone would say
      nothing while the user drags the bar. They fire `Changed` with the new value and **uncancelable**:
      `ev:preventDefault()` there raises, naming that the value has already moved and that
      `HSlider.changed()` is a report rather than a question. The subscribing table's *Cancelable* column
      is where this is written down, not a new concept.
      *Its suite* subscribes `Submitted` on the chat entry and cancels, so a line typed into chat never
      leaves the client — a server-side effect the maintainer confirms by seeing nothing appear. It
      subscribes `Changed` on the **Audio settings** panel's "Master audio volume" slider, collects the
      values a drag produces, and asserts they arrive in order and that the last equals `:value()` read
      back afterwards. `ev:preventDefault()` inside that handler must raise, and the suite asserts the
      drag still completed — a refusal that cancels nothing is the whole claim. It also subscribes on
      the **Keybindings** panel's `Scrollport` scrollbar and asserts a drag of its thumb fires `Changed`
      at all, which is the half a seam on `ch` alone would have missed.
      `[manual]`: type `061` in the chat and press Enter — expect nothing to appear in the chat.
      `[manual]`: open Options → **Audio settings** and drag "Master audio volume" a little.
      `[manual]`: open Options → **Keybindings** and drag its scrollbar a little.

- [x] **061.5 — `widget:text(s)` writes a native caption, and `widget:title(s)` a native window's.**
      Two verbs, because the API already splits them and the split stands: *a title is a window's `cap`,
      text is everything else*, and the two refusals in the tree already point at each other. `:text(s)`
      answers on a native `Label` (`Label.settext`), a `Button` caption (`Button.change(String)`, which
      re-renders **and** `redraw()`s — an `SIWidget` keeps its old raster otherwise) and a `CheckBox`
      label (`CheckBox.settext`, already public from an earlier fork edit); `:title(s)` answers on a
      native `Window` (`Window.chcap`). **An `IButton` has no caption** — three `BufferedImage` faces and
      no text — so it refuses like any widget with nothing to say.
      One more slot on `LuaWidget.Moved`, with the apply-order stamp `position` already uses, restored by
      the same sweep on `:text(nil)` / `:title(nil)`, `:reload` and disable, and dropped from the record
      when nothing of this addon's is left. **The stock half is not a string**: a `Button`'s caption is
      `rtext` + `rcol` + `rwrap`, and `change(String)` zeroes the last two, so a coloured or wrapped
      (`ltbtn`) caption restored through it comes back rendered wrong. The record keeps the triple.
      `:text(s)` on a native `TextEntry` refuses naming `:value(v)` and on a native `Window` naming
      `:title(s)`, both mirroring refusals the owned half already gives. Fixes the ROADMAP defect in the
      same breath: `UiApi`'s comment gives `:text()`'s roster without the `CheckBox` arm. Adds the
      **content** section to `edit.md` — `:text` is what a widget *says* — updates `custom.md`'s
      builder-setter table, where `:title(s)` leaves the owned-only block, and leaves `native.md` as
      *placing and hiding*, pointing at its sibling.
      *Its suite* writes `:title(s)` onto the Options window and `:text(s)` onto a label and the "Reset
      to defaults" button, reads each back, drops each with the matching `nil` and asserts the stock
      value returned exactly — the round trip is the claim, and it must hold for a `Button` whose picture
      is cached. It asserts the button's `:size()` came back with it — a caption restored without its
      wrap re-renders at a different width, which is the one part of the lossy restore a program can
      see. A second write then one `nil` must return the stock value, not the first write,
      proving the level replaces rather than stacks. `:text(s)` on the chat entry must raise naming
      `:value(v)`; `:text(s)` on the Options window must raise naming `:title(s)`; `:text(s)` on a
      `Scrollbar` must raise naming what has text; `:title(s)` on a `Label` must raise naming `:text(s)`.
      `[manual]`: open Options → **Video settings** before running, then read the window's title bar —
      expect `061.5 was here`, in the client's own caption font, and the stock title back at the end of
      the run.

- [x] **061.6 — a text level survives the server rewriting it.** `AddonManager.onUimsg(wdg, msg)` already
      runs post-apply, after an L3 `hafen.event():message()` handler could have swallowed the update (a
      swallowed one never reaches the tap, and never landed either — the right answer); it runs on a
      **Loader thread** outside the `ui` monitor, so this task marks there and re-applies in the
      UI-thread drain beside `drainRemovedWidgets`/`drainResolveQueue`/`drainBeltSet` — one frame, the
      pattern three off-thread taps already use. Two writes at that moment: the record's **stock** half
      becomes the server's new value, and this addon's level goes back on top. **The tap carries no
      args** — its signature is `(Widget, String msg)` — so the stock half is refreshed by *reading the
      widget back* at the drain, before the level goes on: at that one instant the widget is showing the
      server's value and nothing else, which is what makes the re-read equivalent to having had them.
      Covers `Label` `set`, `Button` `ch` and `Window` `cap`. The level belongs to the widget and the
      client reuses windows, so `native.md` states plainly that a caption can outlive what the window
      meant, and points at `hafen.ui():on(sel, "appear", …)` as the way to drop it.
      *Its suite* subscribes `hafen.ui():on("window", "appear", …)`, writes `061.6` onto every window it
      sees, and waits on a bounded timer for a container window whose caption the server sends after the
      window itself. It asserts the caption still reads `061.6` after that message landed — the whole
      claim — and then that `:title(nil)` yields the **server's** caption (`Cupboard`, not whatever the
      window said before it arrived), which is the half that makes the restore honest. It scores over
      what the run reached: no container opened in the window means those lines report `[fail] no
      server-captioned window appeared within 20s`, never a silent pass.
      `[manual]`: open a cupboard, a chest or any container while the suite is waiting.

- [x] **061.7 — your own controls inside a native window, and `widget:pack()` on one.** `:pack()` answers
      on a borrowed window through the same `Moved.size` record, so `:size(nil)` still restores the stock
      outer box, and it inherits `native.md`'s existing rule verbatim: a window that packs itself around
      its own contents makes this **inert, never an error**. Closes the destroy gap — `Widget.destroy()`
      is `remove()` on itself plus `rdispose()`, which recurses `dispose()` only, so a control adopted
      into a native window never runs `remove()` and its `Destroy` never fires today; the addon's owned
      widgets route through `dispose()`, which `rdispose` does reach on every descendant. Documents
      `:parent(nativeWindow)`, which already works — with the constraint the verb already carries:
      `parent(w)` requires the child to be **still being built** (`c.pending()`), so **adoption is a
      build-time verb**. You build a control *into* one of the client's windows; you do not re-home one
      that is already on screen, and the existing refusal says so and names `:position(x, y)`. Points at
      the sheet's geometry rules for anchoring an adopted control to a native sibling rather than nailing
      it to a pixel the client may move. Adds the **structure** section to `edit.md`, and with it the
      page's worked example — the first commit at which it can be written at all: one window renamed,
      given a button of the addon's own, refitted around it, and its close button taken over — the four
      writes in twelve lines, which is the whole argument for editing over replacing.
      *Its suite* builds a button with `:parent(optionsWindow)`, asserts `hafen.ui():at()` at the button's
      `:rootPos()` returns that very button (it is inside the content area and hit-testable, not merely
      drawn), calls `:pack()` on the window and asserts `:size()` grew to contain it, then `:size(nil)`
      and asserts the stock box came back. It subscribes `Destroy` on the adopted button, and after the
      window it is in is destroyed asserts the handler ran **and** `:exists()` is false — the two halves
      that are not the same thing here. **The Options window cannot answer that pair**: `OptWnd.reqclose`
      hides it and `GameUI` builds it once, so closing it destroys nothing. The suite asks it twice
      instead — deterministically on a window it builds and destroys itself (the same chrome, the same
      adopted child, the same `Widget.destroy()` recursion), and on a **server-placed** window over a
      bounded timer, scored over what the run reached. `:pack()` on the main inventory must be inert and
      raise nothing, and `:parent(w)` on a control the addon already armed must raise naming
      `:position(x, y)` — the build-time rule, asserted rather than only written down. And the move must
      not read as a death: adopting a control fires no `Destroy` of its own.
      `[manual]`: run with the Options window open; open a container and close it while the suite waits.

- [ ] **061.8 — `widget:value(v)` drives a native control (protected).** The one act in this feature:
      it writes through the very method the client calls, so `canactivate` and the outgoing `wdgmsg`
      behave exactly as a real interaction — `ACheckBox.set`, `RadioGroup.check`, `HSlider`'s value write,
      `Scrollbar`'s (`ch` is *relative*, so driving to an absolute value writes the step it needs),
      `SListWidget.change`, `TextEntry.settext`. Because the interception seams sit at the
      *input* sites, driving the funnel never re-enters them. Adds `WIDGET_VALUE` to `Permission`
      (`"widget.value"` · `widget:value` · *flip the client's own controls — a box it ticks, a field it
      types into — which the server sees*), gated **first**, before the widget or the value is looked at
      (D-213). It is an act, so there is no `:value(nil)`, nothing recorded and nothing restored — the
      page says so beside `:text`, which is the opposite in every one of those respects. `:value(v)` on a
      native `Progress` refuses, naming that `Progress.val` is a `Supplier` the client re-reads every
      frame. The **read** half already answers on a borrowed control (061.2); what lands here is the
      write, and `Controls.value`'s refusal — which today lists the *builders* that hold a value — is
      rewritten to name what holds one, owned or not. Adds the **tier line** to `edit.md` — `:text` is
      what a widget says and never leaves the client, `:value` is what it holds and the server sees it —
      which is the one thing on that page a reader must not get wrong, and where `ev:resend()`'s place on
      the unprotected side is stated too: it reaches the server, but only ever by re-issuing a gesture
      the user just made, which is what `:value(v)` does not have.
      *Its suite* declares `widget.value`, ticks a **Video settings** checkbox, reads `:value()` back,
      ticks it again to leave it exactly as found, and does the same with a slider on that same panel by
      value — one panel, so the run has one precondition — asserting the read round-trips and that a
      `Changed` handler the suite also holds fired **zero** times, since a programmatic write is not an
      interaction. `:value(v)` on a native `Progress` must raise naming the
      `Supplier`; `:value(v)` on a `Label` must raise naming what holds a value.
      `[manual]`: enable the suite and read the consent dialog — expect one line for `widget.value`
      reading *flip the client's own controls…*.
      `[manual]`: delete `widget.value` from the suite's `manifest.json`, `:reload`, re-run — expect
      every drive line to fail with an error naming `widget.value`, and no line to fail on its arguments.

- [ ] **061.9 — `widget:revert()` undoes a whole edit at once.** Last, because it gives back what all
      eight tasks before it install. A verb on `Widget`, not a handle: an object whose only method is
      `revert()` is an object standing in for a verb. Its scope is **this widget and its subtree, as the
      tree stands when it is called**, which is what makes the scope answerable at all — an edit is never
      confined to one widget, and the page's own example writes a caption, adopts a button and takes over
      the close button, which is neither of the other two. It drops, for this addon only: the text level,
      the position and size levels (falling back to a sheet rule that still names the widget, exactly as
      `:text(nil)`/`:size(nil)` do), the hide record under its own restore rule, this addon's `w:rule()`
      level, every subscription it holds anywhere in that subtree, and every widget it adopted into it,
      destroyed. It does **not** undo `:value(v)` and does **not** end a `:replace(view)`; the page names
      the verb that undoes each. On a widget this addon holds nothing on it is a no-op that chains. Adds
      the closing section of `edit.md`, where the hotkey-armed toggle is the worked example and the
      contrast with `:reload` is the point: teardown gives everything back when the addon goes, `revert()`
      is how an addon gives it back while it stays.
      *Its suite* rebuilds the page's own example against the Options window — a caption, a size, an
      adopted button, and a `Pressed` handler on the window's close button — reads all four back to prove
      they took, then calls `win:revert()` once and asserts each is gone: `:title()` is the stock
      caption, `:size()` is the stock box, the adopted button's `:exists()` is false, and its `Destroy`
      fired. It then asserts `win:revert()` a second time raises nothing and still chains. Two things it
      must prove it did **not** do: with a `:replace(view)` standing on a **second** native window — the
      inventory, which is always there, so the run does not depend on which windows are open —
      `revert()` on the Options window leaves that one's `:replacement()` non-nil; and a checkbox driven
      by `:value(v)` before the revert still reads the driven value afterwards — the tier line, asserted
      rather than asserted-about.
      `[manual]`: run with the Options window open; after the run, click its X — expect it to close
      normally, which is how the reverted `Pressed` handler proves it is gone.
