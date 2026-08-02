# 031-window-lifecycle — Plan

## Approach

**Two `// addon:` one-liners, one ownership record, one teardown rule. No new public API.**

1. **The seam.** `MenuCheckBox` calls `setgkey(gkey)`, so the keybinding fires the checkbox's own click — which
   means **Tab and the menu button both land in `GameUI.togglewnd`** ([:1481](src/haven/GameUI.java:1481)), and
   the tick is read by `GameUI.wndstate` ([:1475](src/haven/GameUI.java:1475)). Both are `private`, and both are
   edited **inside the body**, so unlike 027 no visibility has to change:
   - `togglewnd`: `if(AddonWidgets.toggleWnd(wnd)) return;` at the top.
   - `wndstate`: `Boolean s = AddonWidgets.wndState(wnd); if(s != null) return s.booleanValue();`
   Everything else — the seven call sites, the bindings, the checkboxes — is untouched.
2. **The record is 029's, extended.** `Addon.hiddenNative` already holds a `LuaWidget.Hidden` per hidden native
   widget (the widget, its server id, its original visibility). It gains one nullable field: **the view** that
   stands in for it. Lookup is by widget identity, and `AddonWidgets` asks the addon layer, never the reverse.
3. **`replace` binds it.** `replace` is the only place that knows both halves — the window it hid
   (`nativeWindowOf(wdg)`) and the view the builder returned — so it fills that field itself. An addon that
   hides by hand and never returns a view gets the swallow, which is the honest half of the contract.
4. **Toggle = drive the view.** With a view bound, `toggleWnd` does `view.show(!view.visible())` +
   `raise/fitwdg/setfocus` exactly as `togglewnd` would have; `wndState` answers `view.visible()`. **No
   bookkeeping boolean anywhere** — the view *is* the state, so the checkbox cannot drift out of sync.
   Without a view, `toggleWnd` returns `true` having done nothing (swallowed) and `wndState` answers `false`.
5. **Teardown: one rule — leave the window as the user was seeing it.** Restore visibility =
   `(view != null && view.visible())`, i.e. the view was open ⇒ the stock window opens, nothing was on screen ⇒
   it stays closed. This *replaces* 029's replay-the-original for the bound case and keeps it (as the same
   expression) for the unbound one. Guard, staleness and relog-vs-`:reload` behaviour are 029's two-branch
   check, unchanged.

## Files to create / modify

- `src/haven/GameUI.java` — the **two** `// addon:` one-liners (`togglewnd`, `wndstate`). The only core edit.
- `src/haven/AddonWidgets.java` — `toggleWnd(Window)` / `wndState(Window)`: the `haven`-side facade, with a
  **global-empty fast path** (see risks — `wndstate` is polled per frame).
- `src/io/brodgar/addon/LuaWidget.java` — `Hidden` gains the view field; `hide`/`show` unchanged in contract.
- `src/io/brodgar/addon/UiApi.java` — `replace` fills the view in; `teardownHidden` applies the one rule.
- `src/io/brodgar/addon/Addon.java` — no new list; `hiddenNative` is the ownership record.
- `docs/addons/api/ui.md` — under the existing "Hiding a native widget carries a restore" heading: hiding now
  also **takes the window's toggle**, `replace` makes the client's key and menu button drive your view, and the
  teardown rule in one sentence. `bags`' manifest description follows.
- `addons/hello/main.lua` (contract check), `addons/bags/main.lua` only if its own toggle hotkey now duplicates
  the client's — likely it can drop it.
- **`specs/codebase/`**: `GameUI`'s window-toggle path (`togglewnd`/`wndstate`/`MenuCheckBox.setgkey`) is read
  here; if no subsystem file covers it, `/end` extends the one that owns `GameUI`.

## Risks & gotchas

*(prior art: `learnings/widget-replacement.md`, `learnings/ui-widgets.md` — grepped, not read whole)*

- **`wndstate` is polled every frame, per checkbox.** `MenuCheckBox(...).state(() -> wndstate(invwnd))` is a
  supplier the checkbox reads each frame, and there are six of them. `AddonWidgets.wndState` must therefore be
  an identity lookup behind a global-empty fast path and must allocate nothing — the same discipline the
  placement seam already follows.
- **`replace` hides the WRAPPER, not the widget.** It hides `nativeWindowOf(wdg)` — the `Hidewnd "Inventory"`
  around `maininv` — so the window whose toggle we take is that wrapper, which is also exactly what `togglewnd`
  is called with (`invwnd`). They must be the same object; assert it rather than assume it.
- **The wrapper is hidden by default** (the client only shows it on Tab). That is the half of the teardown rule
  a blind `show()` gets wrong.
- **A stale owner must not eat the key.** If the owning addon is gone (disabled between frames, a relog), the
  facade returns `false`/`null` and the client's stock behaviour runs — a dead Tab is worse than a stock Tab.
- **`Window.reqdestroy` starts a fade-out** (030's `disappear` finding): a closing window lingers in the tree.
  Ownership is keyed on the widget, so a lingering corpse must not keep answering for a live window.
- **Do not touch the keybinding registry.** Rebinding `"inv"` would also disable the menu button's key and edit
  the user's own config; this feature owns the window, and the key keeps doing what it always did.

## Discarded alternatives

- **A public `w:onToggle(fn)` / `w:toggle(view)` verb** — rejected: `replace` already knows both halves, so a
  verb would be API for something nothing has to ask for. Add it only if a real case appears that `replace`
  cannot serve.
- **The engine keeping an open/closed boolean per owned window** — rejected: two states that can drift, and the
  checkbox is what lies when they do. The view's own `visible()` is the single source.
- **Unbinding/rebinding `kb_inv` from Lua** — rejected: the menu button bypasses the keybinding entirely, and
  it would mutate the user's keybind config to fix a window problem.
- **Hooking `Window.show()`** — rejected: far broader than the toggle path, and it would fight every internal
  `show()` the client makes for its own reasons.
- **Folding `replace(selector, fn)` in here** — rejected: B3b, independently verifiable, and a semantic change.
