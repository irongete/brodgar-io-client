# 062 — tasks

Nothing can make the client deliver a click from Lua, so **the gesture itself is `[manual]` in all three
suites** and everything around it is automated: the binding, the level it writes, the refusals, the restore,
the event that must *not* fire. Each suite stays within the 15-line budget, so assertions are grouped onto
one verdict line where they prove one claim.

Read `plan.md` before starting: it carries the `LuaMouseGrab` shape the gesture copies, the four-line write
through `Layout`, the exact refusal texts, and the `flush` trap.

**Criterion → task**: 1, 3, 5, 12 → 062.1 · 2, 6 → 062.2 · 8, 9 → 062.3 · 4, 7, 10, 11, 13 → split across
the tasks that own each half, named in each below.

- [x] **062.1 — a handle drags one of the client's widgets.**
      Adds `widget:draggable(h)` in three arities to `LuaWidget` beside `position`, and the new
      package-private `Gesture` widget behind it: `ui.grabmouse(this)` for down/up, the `MouseMoveEvent`
      broadcast for the move (hence zero-size and `visible`), `doff` from the press like `Window.drag`, and
      a `Widget.listen(MouseDownEvent)` on the handle to arm. Each move writes **every armed owner's**
      `Moved.wantPos` with a fresh `Layout.nextSeq()` and calls `Layout.apply`, so the `UiApi.fitc` clamp,
      `revert()` and teardown come from the layer already there. Adds the `Dragged` key to `WidgetSubs`'
      no-listener family and `LuaEvent.gesture(owner, x, y)` beside `grabMove`. Adds the `// addon:` line at
      the end of `GameUI.resize` and its `AddonWidgets` entry, re-applying `Layout.apply` for every `Moved`
      whose `wdg.parent` is that `GameUI` — this task owns the seam; 062.2 only rides it.
      *Its suite* (`addons/062-drag-handles.1/`, run as `:t062-1`) declares no permission and no saved
      variables, and drives `@ChatUI` plus one of the client's windows. It asserts: `chat:draggable()` is
      `nil` before arming; that arming with a grip built by `hafen.ui():image():parent(chat)` chains and
      that `chat:draggable() == grip` **by identity**, which is what proves the read hands back the interned
      handle rather than a copy; that `chat:draggable(nil)` leaves the read `nil`; that `chat:draggable(chat)`
      — the target as its own handle — is accepted; that `chat:revert()` drops a live binding from the other
      side. On the level: `chat:position(40, 40)` then `chat:position()` reads `{40, 40}`, and
      `chat:position(nil)` puts back a different pair — the same layer a drag will write. **The clamp
      (criterion 5) is automated on that same layer**: a position far outside the screen reads back clamped,
      with at least `min(100 px, its own size)` still inside the parent, since a gesture writes through the
      identical `Layout.fit` path. On the event: a
      `chat:on("Dragged", fn)` counter is still `0` after a programmatic `chat:position(x, y)` and a tick,
      which is criterion 10's *your own write does not fire it*; and `chat:on("Nope", fn)` refuses naming
      `Dragged` among the keys it has. `pcall` refusals, each asserted to fail **and** to name its remedy: a
      handle that is not a Widget; a handle that has left the tree; and `win:draggable(win)`, whose text must
      name the caption — followed by `win:draggable(grip2)` with a grip of its own, which must be **accepted**,
      since refusing that is the mistake the criterion exists to prevent.
      `[manual]`: drag the chat by its grip, fast, and with the pointer leaving the game window — expect it
      follows one-for-one, nothing in the game is clicked underneath, and it stays where dropped.
      `[manual]`: with the chat dragged, resize the game window — expect the chat stays where you put it,
      and `chat:position(nil)` afterwards still returns it to the stock place.
      `[manual]` (criterion 12, two owners): with the suite's binding live, arm the same chat again from the
      `:lua` console, which is an owner of its own — `hafen.ui():find("@ChatUI"):draggable(...)`. Drag once
      and expect it to move **once**, not twice; then `:draggable(nil)` from the console and expect nothing
      on screen to move, because the suite's level holds the same landed value.
      <!-- extra context: src/haven/Window.java `drag`/`doff`; src/io/brodgar/addon/LuaMouseGrab.java -->

- [x] **062.2 — a handle resizes one, without moving its origin.**
      Adds `widget:resizable(h)`, the same three arities over the same `Gesture` with the mode switched:
      it writes `Moved.wantSize`/`sizeSeq` instead, the target's **top-left stays put**, and it never sizes
      below `(1, 1)`. Adds the `Resized` key beside `Dragged`. Covers the size half of the `GameUI.resize`
      re-apply 062.1 installed. Nothing new in `haven`.
      *Its suite* (`addons/062-drag-handles.2/`, `:t062-2`) **stands alone**: it re-asserts the whole
      arm/read/`nil`/`revert` cycle on `resizable`, by identity, without assuming 062.1 was ever run. On the
      level: `chat:size(w, h)` then `chat:size()` reads back, and `chat:size(nil)` restores the stock outer
      box — asserting the outer/content asymmetry rather than expecting the pair back unchanged. The
      assertion this task exists for is **inert, not error**: armed on the main inventory's `Hidewnd`
      wrapper, whose `cresize` re-packs before the call returns, a written size level leaves `:size()`
      reading the packed box and **raises nothing**; the same written on `equwnd`, which has no such
      override, is honoured and reads back. Running both in one suite is what makes the claim legible.
      `pcall` refusals: a handle that is not a Widget, and one that has left the tree. On the event: a
      `chat:on("Resized", fn)` counter is `0` after a programmatic `chat:size(w, h)` and a tick.
      `[manual]`: drag the corner grip — expect the box follows the pointer, the **top-left does not move**,
      and it cannot be shrunk away to nothing.
      `[manual]`: with the chat resized, resize the game window — expect the box survives, and `chat:size(nil)`
      afterwards still yields the stock outer box.
      <!-- extra context: src/haven/GameUI.java `addchild` (the anonymous Hidewnd and its cresize) -->

- [ ] **062.3 — `widget:remember(name)` makes a place survive the session.**
      Adds the third verb and its per-character slot: `savedata/<genus>_<char>/<id>.layout.json`, beside the
      addon's own store file, outside `saved_variables` and needing no manifest declaration. Loaded in
      `StoreApi.restorePerChar` into a per-addon map; the call applies whatever the name holds **at that
      instant**, through the verbs' own write; `Gesture.mouseup` writes the landed values back. `remember(nil)`
      drops the name **and** deletes the record. `:reload`, disable and `revert()` drop the binding and
      **keep** the record — the one place the teardown path's instinct is wrong, and it needs the comment
      saying so. Relaxes `flush(a)`'s early return on an empty `savedVariables`, which is the bug this task
      is most likely to ship.
      *Its suite* (`addons/062-drag-handles.3/`, `:t062-3`) stands alone and re-arms `draggable` and
      `resizable` itself, since the replay is theirs. It asserts: `chat:remember()` is `nil`; that
      `chat:remember("chat")` chains and reads the name back; that with a position level written and
      `hafen.store():flush()` called, a second `chat:remember("chat")` after `chat:position(nil)` puts the
      saved place back — which is the replay, proved without a gesture; that `chat:remember(nil)` leaves the
      read `nil` and that a `remember("chat")` after it applies nothing. The refusal that matters is the
      collision: remembering a **second** widget under a name this addon already holds must raise, naming
      the widget that holds it. Also `pcall`s a name that is not a string. It asserts `chat:revert()` drops
      the binding, then that `chat:remember("chat")` still finds its record — dropping is not forgetting.
      And the order half of criterion 9: a `chat:position(x, y)` written **after** `:remember` is what
      `chat:position()` reads, because it is the later write to the same level.
      `[manual]`: drag and resize the chat, then `:reload` — expect it comes back where you left it.
      `[manual]`: relog — expect the same place and box; then `chat:remember(nil)`, relog again, and expect
      the stock place.
      <!-- extra context: src/io/brodgar/addon/StoreApi.java `restorePerChar`, `storeFile`, `flush` -->
