# 122 — Tasks

Each ships one edge whole. **122.1 first**; 122.2–122.4 are independent of it and of each other.
Every suite is `addons/122-the-edges-of-a-session.<X>/`, run with `:t122`.

- [x] **122.1 — the screen is published on any thread, and the view moves on the frame's.**
      `Sessions.anchor(Member)` splits: the publishing half reads the incoming offset out of
      `placed()`, writes `UILoop.drawn(target)`, `invalidate()`s and coalesces a request (first `cur`,
      last `target`, its offset) into an `AtomicReference`, touching no widget tree — so
      `Sessions.class` becomes a leaf lock. `Sessions.tickview()` consumes it at the top of
      `UILoop.run`'s loop, between `env.render()` and the `uilock` block, walking each tree under its
      **own** monitor through a private walk that leaves `Sessions.mapview`'s contract alone; a
      request whose `cur == target` is dropped. `relinquish` re-reads the anchor under
      `Sessions.class`. Rewrites `multi-session.md`'s camera-adoption order, *Handing the screen over*
      and *One lock direction*; adds `threading.md`'s missing keybinding row.
      *Its suite* declares a window with a `Draw` handler — one that **holds the layer's monitor** —
      and switches the screen from inside it, which is the nesting this task removes; asserts
      `hafen.session():current()` reads the new session on the next line, that naming the session
      already on screen fires no `SessionSelected`, and that an A→B→A inside one step leaves A on
      screen with `s:world():screenToWorld(x, y, fn)` still answering — which it cannot if the pair
      slept the drawn view, since `detachscene` takes the click-map out. With `cam:mode("rts")` and
      `s:world():focus(p)`, asserts `worldToScreen(p)` returns near the screen centre after a round
      trip: the offset read before the screen moves rather than after. `pcall`s
      `current(hafen.session():get("nobody"))`, asserting it names *"the client holds no session for
      the account"*.
      `[manual]`: with two characters live, hold the cycle hotkey down ten seconds — expect: every
      press switches, no freeze.
      `[manual]`: run `:session drop <the alt on screen>` — expect: the screen moves to the other live
      character, and the client keeps drawing.

- [ ] **122.2 — a background session is silent from its first sound.**
      `ActAudio.RootChannel.mute(boolean)` and `setvolume(double)` become `synchronized` — the
      happens-before `mixer()`'s double-checked block has never had against them. Without it a channel
      built after the first `Sessions.applymute` reads a stale `muted`, sets itself audible, and every
      later `applymute` returns on `m == muted`. `ActAudio.Root.clear()` gains the `aui.clear()` its
      `pos` and `amb` siblings get, so a session ending stops leaving its interface `VolAdjust` on the
      shared mixer. Updates `services.md`'s channel row and `multi-session.md`'s *It is silent*.
      *Its suite* round-trips `hafen.client():options():audio():uiVolume(v)`, asserts the value reads
      back and restores it — the guard that a `synchronized` setter still writes — and asserts a value
      outside `0.0`..`1.0` is refused naming the range.
      `[manual]`: log an alt in, leave it in the background from before it enters the world, then tab
      to it and back — expect: nothing audible from it while it is not on screen.

- [ ] **122.3 — an account name is reserved before the connection, not checked before it.**
      `Sessions.add` and `adopt` take a private `claiming` monitor — a leaf, never nested under a tree
      or under `Sessions.class` — and reserve the account name atomically with the `byuser` check,
      before `connect(user)`'s two blocking round-trips; `members.add(m)` relieves it and a `finally`
      releases it, so an add dying on an `InterruptedException` or an `Error` still leaves the name
      usable. `Member.start` unwinds a failed `Thread.start()`: it clears `ui` and takes the UI down
      rather than leaving one live, ticked by nothing. Rewrites the two comments on those lines — they
      name `RemoteUI.init` asking `Sessions.ismember`, which it does not do — and
      `multi-session.md`'s *Where a session comes from*, which repeats it.
      *Its suite* asserts the invariant the reservation defends and nothing else can: no two entries
      of `hafen.session():list()` share a `:user()`, `hafen.session():count()` equals `#list`, and
      `hafen.session():get(u)` is identical to the entry carrying that name.
      `[manual]`: `:session add <an account already live>` — expect: one refusal naming it, and
      `:session list` unchanged.
      `[manual]`: `:session add <an account with no saved token>`, then the same line again — expect:
      the second says the token is missing, never *"already a live session"*.

- [ ] **122.4 — the layout is written when the screen leaves a character.**
      `GameUI.savewndpos` splits into the `onscreen()` guard and a `savewndpos0()` body, and a
      package-visible `leavingscreen()` calls the body — the one path that may skip the guard, since by
      the time it runs the guard is false by construction. `MapView.dormant(boolean)` calls it in its
      `d == true` branch, before `endcamdrag()`/`detachscene()`: `Sessions` is that method's only
      caller, so it covers every exit from the screen — a switch, `relinquish`, the last session
      falling, the login screen — and nothing else. The dead call in `GameUI.dispose()` goes. Rewrites
      `gameui-windows.md`'s *at logout AND every 60 s* row, which describes a write that has not
      happened there since the multi-session seam landed, and `multi-session.md`'s *It does not fight
      over window geometry*.
      *Its suite* cannot drive this — `AddonWidgets.stockc` answers the coordinate the **user** last
      placed, so a window a suite moved is written back at its pre-suite value — so it asserts the
      seam is reachable alone: `s:ui():find("@Inventory")` answers for the drawn session and its
      `widget:position()` round-trips, which is the handle the checks below are read with.
      `[manual]`: drag the inventory somewhere new, switch character, switch back — expect: it is
      where you dragged it.
      `[manual]`: do that again and restart the client without waiting a minute — expect: still there.
      <!-- extra context: src/haven/AddonWidgets.java (stockc, and why the suite may not drive this) -->
