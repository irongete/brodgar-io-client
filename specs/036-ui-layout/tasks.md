# 036-ui-layout — Tasks

<!-- MAX 60 lines. One task = one session: compiles, verifiable through TESTING.md. -->

- [x] **036.1 — the verbs work on a native widget, and the client's memory stays clean.**
      `w:pos(x,y)` / `w:size(w,h)` stop refusing on a BORROWED widget and **move `c`/`sz` for real** — not a
      draw-time offset, or the widget draws where it cannot be clicked. Record the stock value at first touch
      (the `Addon.hiddenNative` shape: *what it was before we touched it*) and restore it on rule-drop,
      teardown, `:reload` and relog. **The seam that matters**: one `// addon:` line at the top of
      `GameUI.savewndpos()` restoring addon-moved widgets **before** the client writes `wndc-*` through
      `Utils.setprefc` — it runs from `dispose()`, at logout, so without this the client persists *our* position
      as the user's and uninstalling leaves those windows displaced forever. Keep 029's rule that a write on a
      **stale** widget is a silent chaining no-op. Correct `ui.md`'s "a later feature" row in this task.
      **Suite `036-ui-layout.1/`** — all numbers, no `[manual]`: a native window moves and reads back; the stock
      numbers return on undo; a stale widget's write chains and changes nothing; `hafen.ui.at()` finds the moved
      widget **at its new place** (hit-testing follows the move). `[manual]` only for the one thing a suite
      cannot do: **log out, disable the addon, log back in — the `wndc-*` windows are where you last dragged
      them**, not where the test put them.

- [x] **036.2 — `pos` and `size` as sheet properties.**
      The three properties join `Sheet`'s validation (an unknown one still errors, D-072) and `Fonts.combine`'s
      per-property fold (D-076), carried as opaque values exactly as `bg`/`border` already are. The verbs from
      036.1 become the **hand-named top level** of that same fold (D-077) — one mechanism, two levels. Re-derive
      on the events that matter (the widget appears — 030's `ui.on` fires for what is already open too, D-068)
      and **never in a draw** (035.1's `chdeco` lesson).
      **Suite `036-ui-layout.2/`**: a sheet rule moves a matched window and only that one; dropping the rule
      restores the exact numbers; with both a rule and a verb on one widget the **verb wins** and removing it
      falls back to the rule; a rule naming nothing is inert. All asserted through `:pos()`/`:size()`.

- [x] **036.3 — anchors, and surviving a rescale.**
      `anchor = {to = "screen"|<widget>, at = <corner>, offset = {dx, dy}}`; **`pos` is the degenerate case** —
      an anchor to the root's top-left — so there is one resolution path, not two. Offsets are logical px
      through `UI.scale`. Mind the coordinate space: a window's `c` is relative to its **parent**, and `GameUI`
      is not the root. Re-derive on root resize, UI-scale change, and when an anchor's target moves. **Let
      `fitwdg` clamp** — measure what it does to an off-screen rule and document that, rather than adding a
      second answer to "is this on screen".
      **Suite `036-ui-layout.3/`**: an anchored widget's `:pos()` tracks a **UI-scale change** (the assertion
      that proves anchors are not decoration); a widget anchored to another follows it; an off-screen rule
      leaves the widget reachable by `hafen.ui.at()`. `[manual]`: resize the game window and see the HUD hold.

- [x] **036.4 — cost, docs, the theme, and the boundary. Close the run.**
      **Measure** (`hafen.client:profiling()`): layout re-derives on events and **not per frame**. Docs:
      the property × key table gains the three; a section on anchors; and — this being the last feature of the
      run — a plain statement of **what the skinning system covers and what would be a new chapter**, so the
      boundary is a decision on the page rather than a gap someone reports as a bug. `theme` gains a layout in
      its `theme.json` **and saves it through `hafen.store`**, the demonstration that profiles are an addon's
      business. Extend `specs/codebase/gameui-windows.md` with `savewndpos`/`getprefc`.
      **Suite `036-ui-layout.4/`**: the whole layout applies from JSON and reverts. `[manual]`: the themed HUD
      looks placed, and the stock client is unchanged with the theme off.
      **Verify:** every prior suite (and the frozen `hello`) passes on the same login; link/anchor check 0 broken.
