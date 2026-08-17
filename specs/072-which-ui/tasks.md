# 072 — tasks

Three tasks, one per question the old field was answering. The order matters only in that `072.3`
deletes the fields, so it must be last: after it, a site that was not converted does not compile, and
that failure is the feature's proof.

Read `spec.md` and `plan.md` first. Every file a task may open is listed in `spec.md` under **Context
files**, tagged with the task that needs it.

**The whole feature must change no behaviour.** `screen()`, `host()` and `w.ui` all resolve to the
one bound `UI` while one session is drawn, so every site answers exactly what it answered before. A
suite here proves *sameness*, which is why each one re-asserts a claim an earlier feature made rather
than inventing a new one: if this conversion broke something, it broke something that already worked.

---

- [x] **072.1 — The monitor is the widget's own, not the screen's.**
      About twenty-two sites across `src/io/brodgar/addon/` read the ambient field only to lock it —
      `UI u = AddonManager.ui; synchronized(u) { …mutate w… }` — with `LuaWidget` holding the largest
      cluster and `UiApi`, `Layout` and `Controls` the rest. Each becomes `synchronized(w.ui)` on the
      widget the block actually mutates. **This is a correctness fix, not a rename**: locking the
      drawn session's monitor while mutating a widget that belongs to another session is wrong the
      moment two sessions exist, and wrong without a symptom. `Widget.ui` is a public non-final field
      set by `Widget.attach(UI)`, which recurses the subtree; it is **null before a widget is
      attached**, so each site carries its existing `u == null` guard over as a guard on the widget's
      own, which is the same state `UiApi.dropPending` exists for. Where a block mutates two widgets
      from different trees it needs a deliberate choice, not a sweep: the lock direction is anchor
      then member and never two at once on the tick, and this is the one place a mechanical
      conversion can deadlock. The field itself is untouched here and dies in `072.3`.
      *Its suite* proves the widget layer still behaves, through the verbs whose implementations this
      task rewrote: it builds a window with `hafen.ui():window()`, sets `:title`, `:position`,
      `:size` and `:visible(false)` then `true`, reads each back and asserts the round trip; it adds
      a child, reads `:children()` and asserts the count; it hides a **native** widget and restores
      it, which is the borrowed path through `recordHidden`. It `pcall`s `:position(nil, 10)` and
      asserts the refusal names the missing argument rather than reading as a getter. Every
      assertion existed before this task and must still hold — that is the claim.
      `[manual]`: with the suite's window on screen, drag it by its caption and resize it by its
      corner. Expect: both follow the mouse exactly as any client window does, with no stutter or
      snap-back.

**Carried from `072.1`, because re-deriving it costs a re-read of thirty files.** The monitor group is
**57 sites in 22 files**, not the ~22 in four this plan estimated: the count above was taken over
`AddonManager.ui` and missed the files that read the same field as a bare `ui` through
`import static io.brodgar.addon.AddonManager.*` (`UiApi`, `CharApi`, `AddonRegistry`), and the whole
model-backed-control and window-reader cluster (`CDropdown`, `CGrid`, `CList`, `CMenu`, `CRadio`,
`CTable`, `LuaQuest`, `LuaItem`, `LuaCraft`, `LuaWound`, `LuaOpponent`, `LuaDeckCard`, `LuaManeuver`,
`LuaFightSummary`, `LuaContents`, `Sheet`). All 57 are converted; the rule `072.1` applied is its own first
sentence — **the field is read ONLY to lock**. Three things it therefore left standing, each one an
argument the task after it has to settle rather than sweep:

- **`Layout.apply` is the one real widget WRITE still on the field**, because `u` does not stop at the lock
  there: it is handed to `applyHalf` → `Anchor.resolve(u, w)` and `fit(u, w, …)`. Criterion 2 is discharged
  only when that one takes its `UI` from `w` as well — `host()` would be the drawn session, and the widget
  being written is not always the drawn session's. `w.ui` answers both questions in one read.
- **`UiApi.teardownGobOverlays` and `teardownGobScales` are lock-only with no widget in hand**: they take the
  monitor to serialise a walk of the object cache against `ctick`. That is a question about a session's
  world, not about a widget's tree, and it has no `w.ui` to ask.
- **`UiApi.teardownHidden`/`releaseHidden`/`teardownMoved` and `LuaWidget.recordHidden`/`recordMoved`** read
  the field for `u.getwidget`/`u.widgetid`/`u.root` — the two-branch liveness test — so all five are tree
  sites and land here.

- [x] **072.2 — The tree says whose it is.**
      Adds `AddonManager.host()` — the `UI` whose widget tree this addon layer works in: where an
      addon's own windows live and where a selector searches. It answers `Sessions.anchor()` and is
      **derived, never stored**, because a second copy of "which session is drawn" kept in sync by
      hand is what `071` spent three tasks deleting one layer down. Every site reading `u.root` to
      search or place in the tree becomes `host()`. Read each rather than sweeping: a walk driven by
      the **pointer** is a question about the screen and belongs to `072.3`, so `LuaMouse`'s hit test,
      which reads `u.root` and `u.mc` in one expression, stays where it is. `host()` exists as its own
      name precisely because it is the one question that will take a session argument later, and its
      callers are then exactly the list of sites that must grow one.
      *Its suite* proves the tree is still found: `hafen.ui():find()` locates a client window by
      selector — the suite opens one it can name, or reports the selector it could not match — and
      `hafen.ui():on(sel, "appear", fn)` fires for a window already open, which is the path that scans
      the live tree at registration and therefore reads the root this task rewired. It asserts
      `widget:parent()` walks from a found widget up to a root, and `pcall`s `hafen.ui():find()` with
      a malformed selector, asserting the refusal names the selector grammar.
      `[manual]`: open the Inventory. Expect: the suite's line for it reports found, with its title.

**Carried from `072.2`, because re-deriving it costs a second read of thirty files.** The tree group was
**56 sites in 19 files**, and all of them are converted, `AddonManager.host()` answering `Sessions.anchor()`.
The rule it applied is the plan's: a `u.root` reader names the tree it searches, and only `Layout.apply`
takes its `UI` from `w` instead — that one hands `u` on to `Anchor.resolve` and `fit`, so `w.ui` is the
answer to the geometry as well as to the lock, and `fit` grew the null guard the unattached case then needs.
`UiApi.rebuild` was the one mixed site: its monitor is now the widget's own (072.1's rule) and its fallback
parent is `host().root`. Four things `072.3` inherits rather than discovers:

- **The pointer group left standing is FIVE reads, not four**: `LuaMouse`'s `:x`, `:y`, `over()` and `mods()`,
  plus `SurfaceInput.refreshOrigin`'s own `u.mc` — which this plan's task list does not name, and which is a
  `screen()` site by exactly the same argument as the other four.
- **The `view` group is 22 reads in 9 files, not seven.** The count was taken over `AddonManager.view` and
  missed every file reading the same field as a bare `view` through `import static AddonManager.*`
  (`CharApi` ×3, `VrApi` ×6, `WorldApi` ×3) and `AddonManager`'s own three (`glob`, `gui`, and the gob-position
  read below them).
- **`docs/client/multi-session.md` owes a row too**, beside the `boot-and-loop.md` one already charged: *"It
  does not take the addon engine"* says that hub's `ui` and `view` "are the client's one live pair", which is
  true right up to the line that deletes them.
- **The `Docs impact` set is discharged there**, by the task that revises the page it names.

- [ ] **072.3 — The screen is one, and the fields are gone.**
      Adds `AddonManager.screen()` (the drawn `UI`) and `AddonManager.screenView()` (the drawn
      `MapView`), both derived from `Sessions.anchor()`. The pointer sites — `u.mc` and
      `u.modflags()` in `LuaMouse`, and the hit test that reads `u.root` beside them — become
      `screen()`; the seven `AddonManager.view` reads in `CameraFacing`, `CameraOptions`, `LuaGob`,
      `LuaHand` and `SurfaceInput` become `screenView()`. Neither ever takes a session: there is one
      pointer and one drawn view however many sessions are live, and saying so is the point of giving
      them names distinct from `host()`. **Then both fields are deleted** — `AddonManager.ui` and
      `AddonManager.view` — with `attach`/`detach` and their `MapView` constructor and `dispose`
      seams if nothing else writes them. `rm -rf build/classes` first: the entire claim of this task
      is that the build fails without the two before it, and an incremental build hides exactly that.
      Also settles `RemoteUI.init`'s guarded `AddonManager.init(ui)` call, which since `071` — every
      game session a `Member`, registered before its `UI` exists — appears never to fire, leaving
      `Sessions.tickrebind` the only binder. **Confirm before deleting**: log the branch through one
      login and one `:session add`; if it does fire, its comment is what changes instead. Writes
      `docs/client/boot-and-loop.md`'s **Threading and locks** row, which says a widget tree is
      "serialized by the `UI` monitor" as though there were one — 109 lines, room under the ceiling.
      *Its suite* proves the screen still answers: `hafen.ui():mouse()` reads a pointer position
      whose x and y are within the client's own size, and reports the modifier flags; a
      `hafen.world():screenToWorld(sx, sy, fn)` burst still delivers a Position for pixels over drawn
      ground and leaves `placedRebuiltOffTick` at **0**, which is `070.2`'s guarantee re-proved
      because this task rewrote who reads the drawn view. It reads `hafen.client():camera():mode()`
      and asserts it names a camera, since `CameraOptions` is one of the converted files. It `pcall`s
      `hafen.world():screenToWorld(0, 0)` with no callback and asserts the refusal says the answer
      comes back a frame later.
      `[manual]`: move the mouse to a corner of the window and re-run. Expect: the reported pointer
      position is that corner, in design pixels, not the centre and not zero.
      `[manual]`: `:session add <a second account>`, let it reach the world, tab between the two with
      the switcher. Expect: the addon windows behave exactly as before this feature — no window
      vanishing, no click landing on the wrong character.
