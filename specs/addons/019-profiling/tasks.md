# 019-profiling — Tasks

<!-- Line ceilings waived for 019 by the maintainer. One task = one session: self-contained,
     compiles, in-game verifiable on its own. -->

- [x] **019.1 — Client options panel + the master switch.**
      `io.brodgar.ui.ClientPanel extends OptWnd.Panel` (the `AddonPanel`/`VoiceChatPanel`
      clone) with one **Enable profiling** checkbox; a `// addon:` `PButton` "Client" in `OptWnd`'s
      main list; `ClientOptions` wired into `options():client()` with `profiling()` as an
      `OptionsMethod` (arity is the verb); `Prof.on` (`static volatile boolean`) + `Prof.arm()`, the
      `Utils.setprefb` persistence, and the write-both-in-one-statement rule so the pref and the
      switch never diverge. Arming also sets `UILoop.profile`. **No data surface yet.**
      **Verify:** Options → Client → tick the box → `Profwnd` (the client's own profile windows)
      shows live frames; untick → they stop. The box survives a full client restart.
      `:lua hafen.client:options():client():profiling(true)` moves the checkbox in the open panel;
      `:lua print(hafen.client:options():client():profiling())` reads it back.
      <!-- extra context: `src/haven/OptWnd.java` (~:871-880 button list, ~:458 VoiceChatPanel),
           `src/io/brodgar/addon/ui/AddonPanel.java`, `src/io/brodgar/addon/CameraOptions.java`
           (the simplest OptionsMethod subsystem to copy) -->

- [x] **019.2 — Prof core + frame sampling.**
      The `// addon:` end-of-frame handoff in `UILoop` (finished `uprof`/`rprof`/`gprof` parts plus
      `fps`/`uidle`/`framelag`, passed as arguments — do not widen the private fields); the
      preallocated primitive ring (~600 frames, no per-frame allocation); the fold (walk the frame
      parts, roll up `ui`/`scene`/`addons`, accept late GPU writes **by frame number**);
      `hafen.client:profiling()` with `:frame()`, `:history(n)` and `:reset()`. Off ⇒ the handoff
      returns on the switch check before doing anything.
      **Verify:** with profiling on, `:lua` prints fps + the phase breakdown and the numbers track
      `:stats on` / `Profwnd` for the same frames; `:history(60)` returns 60 samples oldest→newest;
      with profiling off, `:frame()` is empty and `:history()` returns nothing. Toggling on gives a
      valid frame from the **second** frame onward (arming is next-frame — expected, not a bug).

- [ ] **019.3 — System + graphics counters (pull-only, answer even when off).**
      `:memory()`, `:net()`, `:loader()`, `:render()`. The work is `// addon:` **structured getters
      beside the existing `stats()` strings** — `Connection.Stats` (private `ptx`/`prx`/`btx`/`brx`/
      `pretx`/`prerx`/`prorx`/`srtt`/`rttv`), `InstanceList` (`nuinst`/`nbatches`/`ninst`/`ninvalid`/
      `nbypass`), `GLDrawList` (`btsubsize`), `GLEnvironment` (`stats_obj`/`stats_mem`/`numprogs`),
      `MapView`/`RenderTree`, `State.Slot.numslots`, `Loader.stats`, `Defer.gstats`. **The counters
      already exist — only their formatting is new. Leave every `stats()` method untouched.**
      **Verify:** with profiling **off**, every number matches its `:stats on` HUD field, item by
      item. Placing a building moves instances/batches; panning to a dense area moves draw slots;
      toggling Video → Shadows changes the draw-slot count; alt-tabbing moves the net counters.
      <!-- extra context: `src/haven/Connection.java` (Stats ~:43), `src/haven/Loader.java` (~:245),
           `src/haven/Defer.java` (~:321), `src/haven/render/InstanceList.java` (~:881),
           `src/haven/render/gl/GLDrawList.java` (~:1089), `src/haven/render/gl/GLEnvironment.java`
           (~:171, ~:1018), `src/haven/MapView.java` (~:956-970, ~:1398) -->

- [ ] **019.4 — Per-addon accounting + custom scopes.**
      Category argument on `callLua` (events / timers / draw / hooks / widgets) at every call site;
      per-addon `long[]` accumulators + call counts, added **inside the existing `finally`** so
      `tickLuaNanos` stays byte-for-byte what the D-018 watchdog reads; `:addons()` with per-addon
      ms/avg/peak/share/calls and a reconciling `total` row; `ProfScope` + `p:scope(name)` /
      `p:measure(name, fn)`, per-addon namespaced, torn down with the addon, a shared no-op
      singleton when off.
      **Verify:** `:addons()` lists every loaded addon; arming `hogtest` makes it the top row by a
      wide margin **and the watchdog still auto-disables it at the same point as before 019**; a
      `p:measure("scan", fn)` in `hello` (or a scratch `:lua` snippet) shows up as a scope under the
      right addon; the `total` row matches the `addons` figure in `:frame()`; `:reload` clears the
      scopes of the reloaded addon.
      <!-- extra context: `src/io/brodgar/addon/AddonManager.java` (callLua ~:643, tick ~:317,
           soft-budget sweep ~:398-412), `src/io/brodgar/addon/Addon.java` (~:244),
           `src/io/brodgar/addon/Sandbox.java` (SOFT_BUDGET_NANOS / SOFT_STRIKE_LIMIT) -->

- [ ] **019.5 — Per-widget cost.**
      The `// addon:` `long[] prof` field on `Widget` (lazily allocated, only while armed) plus the
      tick/draw probes; **inclusive** nanos in the array, **self** nanos via the stack-local
      child-sum subtraction in the traversal; per-type roll-up and the `top` list computed at
      snapshot time (never on the hot path); addon-owned widgets attributed to their owner via
      `LuaWidget`. `:widgets()`.
      **Verify:** opening the inventory and character windows makes those types appear and moves
      their rows; closing them drops the rows; the per-type totals reconcile with the `utick`/`draw`
      phases in `:frame()`; an addon window shows up under both `:widgets()` and that addon's
      `:addons()` row. **Budget gate:** if the field cannot be shown free when profiling is off,
      drop it and ship type-level totals gathered at the `UI.tick`/`UI.draw` roots only — recorded
      as a decision, not silently.
      <!-- extra context: `src/haven/Widget.java` (tick ~:748, the child traversal), `src/haven/UI.java`
           (tick ~:371, draw ~:386), `src/io/brodgar/addon/LuaWidget.java` (owner attribution) -->

- [ ] **019.6 — Named render passes + armed GL counters.**
      A fixed, short pass list via `GPUProfile.part(out, nm)` with CPU and GPU time side by side:
      `shadow` (wrapping `smap.update(out, slist)` in `MapView.updsmap`), `scene` (the MapView draw
      boundary), `ui2d` (the `UI.draw` boundary in `UILoop.display`). Plus the armed-only counters
      that need **new** counting: program/shader binds and vertices/triangles per frame. `:passes()`
      and `:gl()`.
      **Verify — the headline check:** Video → Shadows **off** makes the `shadow` pass drop to zero
      and the GPU frame time fall by roughly the cost it had been reporting; turning shadows back on
      restores both. The three passes sum to sensibly less than the GPU frame time, and their cost
      appears in `:overhead()` as `gpuQueryMs`. **Budget gate:** if the query cost misses the ≤5%
      budget, this tier ships behind its own checkbox in the Client panel.
      <!-- extra context: `src/haven/GPUProfile.java` (part/Frame/late arrival), `src/haven/ShadowMap.java`
           (update ~:240), `src/haven/MapView.java` (updsmap ~:978-1029), `src/haven/UILoop.java`
           (display ~:280, Frame ~:433-490) -->

- [ ] **019.7 — Overhead accounting + the cost proof (the gate).**
      The directly-timed aggregator (one `nanoTime` pair per frame), the one-shot probe calibration
      at arm time, the 1-in-64 **control frames**, and `:overhead()` reporting aggregator / probe /
      GPU-query / total as ms and as a share of frame, with `method` saying whether the number is
      measured (control) or modelled (calibration). **Attribute cost per tier** (frame, addons,
      widgets, passes) — without that, a tier that misses the budget cannot be identified, let alone
      moved behind its own checkbox.
      **Verify — this task's whole point is the two measured numbers, both recorded in the
      verification note:** (1) FPS with profiling **off** vs a pre-019 build, same spot, same
      settings, same session length — must be statistically indistinguishable; (2) FPS **off vs on**
      — must be within the ≤5% budget (target ≤2%). If a tier blows the budget, it moves behind its
      own checkbox in the Client panel and that becomes a recorded decision rather than a silent
      regression.

- [ ] **019.8 — `addons/profiler` + docs.**
      The "Brodgar.io Profiler" addon: a window (behind a hotkey, **dormant** by default per the
      `hogtest`/`bags` rule) drawing the frame graph over the history ring, the phase breakdown, the
      pass table (CPU vs GPU), the widget table, the addon cost table sorted by cost, and the scope
      list; plus the counter panels from 019.3.
      Extend `docs/addons/api/client.md` with `profiling()` and the `client()` options subsystem
      (the docs tier is the contract — no second copy).
      **Verify:** hotkey opens/closes the window; the graph moves when the client is stressed
      (spin the camera, open a busy window, arm `hogtest`); the numbers agree with `:stats on`; and
      a routine `hello` regression login with profiling **off** is completely undisturbed —
      `hello`, `optionstest`, `bags`, `planner`, `widgetstack`, `netdemo`, `walker` all behave as
      before, off and on.
