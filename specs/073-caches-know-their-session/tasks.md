# 073 — tasks

Five tasks. `073.1` builds the object and the census; `073.2`–`073.5` convert one cluster each,
working from that census rather than re-deriving it.

Read `spec.md` and `plan.md` first. Every file a task may open is listed in `spec.md` under **Context
files**, tagged with the task that needs it.

**Every task here is inert, and its suite proves sameness.** With one session live a per-session
index holds one entry, so every reachable behaviour is identical before and after. A suite that
observes a difference has found a bug in the conversion, not a feature of it. The cross-session
proof — *session A's cache did not reach session B* — is not observable while `AddonManager.init`
still tears every addon down on a switch, and belongs to the feature after this one. Do not invent a
check that pretends otherwise.

**`AddonManager.init` is not touched by any task here.** It still tears down, still reloads, still
calls each `resetSession`. What changes is where the state those calls clear actually lives.

---

- [x] **073.1 — The engine's state gets a session to belong to.**
      Adds `AddonManager.SessionState` and `AddonManager.state(UI)` — created on first ask, the only
      door, and **no argumentless form**: an accessor that guesses is the ambient field `072`
      deleted. The key is the `UI` because that is what the engine holds at nearly every site after
      `072` (`w.ui`, `host()`, `screen()`) and because a relogin builds a fresh one through
      `UILoop.bgui`, which is exactly when every cached widget id and gob id stops meaning anything —
      not `Member`, which survives a relogin by swapping its `sess` and `ui`. Hangs release on the
      `UI`'s death: `Sessions.Member.run`'s `finally` and its per-runner `u.destroy()`, plus
      `UILoop.newui`'s destroy for the login slot, with a sweep over destroyed `UI`s as the backstop —
      a missed hook is a leak of one entry per relogin and shows as nothing at all. `state(UI)` on a
      destroyed `UI` must not resurrect an entry, or the sweep and the accessor race forever.
      Converts `AddonManager`'s own collections, the nine cross-thread queues included: an enqueue
      resolves its `UI` from the object it was handed, **never from `host()`**, because Loader and
      network threads are not the drawn session's. Adds `states` to the
      `hafen.client():profiling():session()` group. Writes `census.md` in this folder: every static
      collection in `src/io/brodgar/addon/`, its verdict and its one-line reason, under the test *does
      this name something belonging to one login?* — `073.2`–`073.5` work from it.
      *Its suite* asserts `states` reads as a number with the profiler off and **equals `live`**,
      which is criterion 2 and the one thing here a program can check. It re-asserts
      `placedRebuiltOffTick` is `0` after a `screenToWorld` burst, since this task moved the queues
      those callbacks feed. It drives one queue end to end — subscribe to `GobAdded`, wait a bounded
      window, assert the handler ran — proving an enqueue found its session's state. It `pcall`s
      `hafen.client():profiling():session(1)` and asserts the refusal names the group as read-only.
      `[manual]`: `:session add <a second account>`, let it reach the world, re-run. Expect: `live` is
      2 and `states` is 2. Then `:session drop` it and re-run. Expect: both fall back to 1 — the
      release fired, and nothing was left behind.

- [x] **073.2 — The widget caches know whose tree they hold.**
      Converts the cluster named in `census.md` as the widget layer: `UiApi`'s `selectorWatches`,
      `widgetSubsWatching`, `pending` and `capChanged`; `LuaWidget`'s hidden and moved counts;
      `Layout`'s pending captions; `Gesture`'s armed handles. Each keys on the `UI` of the **widget it
      names** — `w.ui`, resolved where the record is made, not `host()` at the time it is read. The
      one awkward case the census flags: `UiApi.resetSession` reaches into `consoleOwner` — the `:lua`
      REPL's addon record, which is process-wide by design — and clears six collections that name one
      session's widgets. That object stays process-wide; those six move.
      *Its suite* proves the widget layer behaves identically: it builds a window with
      `hafen.ui():window()`, round-trips `:title`, `:position`, `:size` and `:visible`; adds a child
      and asserts `:children()` counts it; hides a **native** widget and restores it, which is the
      borrowed path through the moved and hidden records this task rewired;
      `hafen.ui():on(sel, "appear", fn)` fires for a window already open, which is the selector watch.
      It `pcall`s `:position(nil, 10)` and asserts the refusal names the missing argument.
      `[manual]`: with the suite's window on screen, drag it by its caption and resize it by its
      corner. Expect: both follow the mouse exactly as any client window does.

- [x] **073.3 — The character readers are one HUD's, not the client's.**
      Converts `CharApi` and `BeltHold`. `CharApi.treeAdapters` is nine adapters — meters, buffs, fep,
      study, actionbar, equip, kin, quest, wound — each reading **a** `GameUI`; per session that is
      nine per session, **constructed when the state is, not re-added on a switch**, which is what
      `resetSession` does today. `treeDirty` follows them. `BeltHold`'s holds name slots of one
      character's bar, and its flush must go on writing with the scope that owned those slots.
      *Its suite* reads every adapter's surface and asserts each answers or is honestly absent:
      `hafen.meter():list()` non-empty in the world, `hafen.buff():list()`, `hafen.char():food()`,
      `hafen.study():list()`, `hafen.actionbar():get(0)`, `hafen.kin():list()`, `hafen.quest():list()`,
      `hafen.wound():list()`. It subscribes to `MeterChanged`, waits a bounded window, and reports
      whether it fired — a meter moves on its own in the world, and a run that saw none says so
      rather than failing. It holds an action-bar slot, asserts `slot:res()` is the entry's identity,
      releases it and asserts the server's content is back.
      `[manual]`: eat something, or take a step to move stamina. Expect: the `MeterChanged` line
      reports fired.

- [x] **073.4 — The world indexes belong to one world.**
      Converts `VrApi` and `MapApi`. `VrApi`'s two standing-entity indexes name **gob ids**, which
      mean different objects in different sessions, and its ground pass reads `screenView()`, which
      stays as it is — the scene is one. `MapApi`'s per-session marker maps and its overlay holds name
      one session's map file and one session's `MapView`.
      *Its suite* stands a `hafen.vr()` entity, asserts `:exists()` and that its position reads back
      the Position it was given, then ends it and asserts `:exists()` is false; it reads
      `hafen.map():marker():list()` and asserts the list is a list; it subscribes to `MarkersChanged`
      and adds a marker of its own, asserting the event fires with a count and that the marker is
      findable by `:get`, then removes it. It `pcall`s `hafen.vr():ghost()` with a malformed place
      and asserts the refusal names the Position type.
      `[manual]`: the suite leaves nothing standing in the world — confirm no stray marker or ghost
      remains after the run.

- [ ] **073.5 — The rest, and the last copy of the view.**
      Converts `HttpApi` and `StoreApi`. `HttpApi`'s in-flight requests are per session — an addon
      running for one started them, which is why `HttpApi.reset()` already drops them on a switch —
      while its manifest host allowlist is per **addon** and moves nowhere. `StoreApi` is indexed like
      the rest; **what its per-character scope *means* is deliberately not settled here** and belongs
      to the feature that makes the engine outlive a switch, which is what costs that scope its single
      referent. Then `io.brodgar.voice.Voice`'s `private static volatile MapView view` — the last
      hand-written copy of the drawn view in the tree, one package outside what `072` swept — reads
      through the drawn view instead, on the same reasoning: an answer that re-checks itself replaces
      a copy that can go stale.
      *Its suite* round-trips the store: writes a table, reads it back in the same run, asserts
      equality, and asserts a key it never wrote reads `nil`. It issues one `hafen.http()` request to
      a host its own manifest allows, waits a bounded window, and reports the status or that nothing
      came back — a network that did not answer is reported, not failed. It `pcall`s a request to a
      host the manifest does **not** allow and asserts the refusal names the allowlist.
      `[manual]`: with voice enabled and a second character standing beside you, speak. Expect: the
      speaker icon appears over the right character — the view `Voice` reads is the one on screen.
