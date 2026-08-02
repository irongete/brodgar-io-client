# 031-window-lifecycle — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **031.1 — the seam, and the swallowed toggle.**
      The two `// addon:` one-liners **inside** `GameUI.togglewnd` ([:1481](src/haven/GameUI.java:1481)) and
      `GameUI.wndstate` ([:1475](src/haven/GameUI.java:1475)) — bodies only, no visibility change, no new call
      sites — plus `AddonWidgets.toggleWnd(Window)` / `wndState(Window)` behind a **global-empty fast path**
      (`wndstate` is a per-frame supplier on six checkboxes and must allocate nothing). Ownership is looked up
      on 029's `Addon.hiddenNative` by widget identity. With a window hidden and **no** view bound: the toggle
      is swallowed, `wndState` answers `false`, and a **stale owner falls through to stock behaviour** — a dead
      Tab is worse than a stock Tab. Teardown still replays the recorded original (031.2 makes it the one rule).
      **Verify:** in-game — hide the inventory wrapper from `:lua`, press Tab and click the menu checkbox:
      nothing appears and the tick stays off; Equipment/Character/Kin/Options/map still toggle exactly as
      stock; with no addon loaded the client is unchanged; `:reload` gives the window back.

- [ ] **031.2 — `replace` drives the view, and the one teardown rule.**
      `LuaWidget.Hidden` gains a nullable **view** field; `replace` fills it with the widget its builder
      returned — the only place that knows both halves. With a view bound, `toggleWnd` does
      `view.show(!view.visible())` + `raise`/`fitwdg`/`setfocus` and `wndState` answers `view.visible()`:
      **no bookkeeping boolean**, so the checkbox cannot drift. **Assert** that the window `replace` hides
      (`nativeWindowOf(wdg)`, the `Hidewnd "Inventory"`) is the same object `togglewnd` is called with
      (`invwnd`) rather than assuming it. Teardown becomes **one rule — the window ends up as the user was
      seeing it**: restore visibility = the view was open. A second addon trying to own an already-owned
      window gets a clear error naming the first.
      **Verify:** in-game with `bags` — Tab and the menu button open and close the **custom** window, the tick
      follows it; disable with the view OPEN ⇒ the stock inventory is open; disable with it CLOSED ⇒ nothing
      appears; `:reload` and a relog both behave; a fading/closing window never answers for a live one.

- [ ] **031.3 — docs, harness, close.**
      `docs/addons/api/ui.md`, under the existing *"Hiding a native widget carries a restore"* heading: hiding
      now also **takes that window's toggle**; with `replace`, the client's own key and menu button drive your
      view and the menu tick reads it; and the teardown rule in one sentence — *the window ends up as the user
      was seeing it*. State plainly that there is **no verb for this** and why (`replace` knows both halves).
      Sweep `getting-started.md` and `bags`' manifest description (it may now drop its own toggle hotkey).
      `hello` version bump + the once-per-login contract check: a swallowed toggle, the double-owner error, and
      **both halves of the teardown rule** driven from Lua.
      **Verify:** one login re-checks 031 and every prior feature; `bags` and `widgetstack` clean; the
      link/anchor checker over `docs/addons/` reports 0 broken.
