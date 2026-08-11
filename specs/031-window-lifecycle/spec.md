# 031-window-lifecycle — Spec

## What & why

**A hidden native window stops coming back.** Observed in-game with `bags`: the custom inventory does not
*replace* the stock one, it **coexists** with it — press Tab and the client's inventory opens too. The cause is
exact and it is not a bug in `bags`:

```java
// GameUI.java:1507  the keybinding and :1516 the menu checkbox share one click
public static final KeyBinding kb_inv = KeyBinding.get("inv", KeyMatch.forcode(VK_TAB, 0));
add(new MenuCheckBox("rbtn-inv", kb_inv, "Inventory")).state(() -> wndstate(invwnd)).click(() -> togglewnd(invwnd));
// GameUI.java:1481
private void togglewnd(Window wnd) { if(wnd.show(!wnd.visible())) { … } }
```

`MenuCheckBox` calls `setgkey(gkey)`, so **the key and the button fire the same click**, and both land in
`togglewnd`, which flips `visible` on **the very window the addon hid** — an addon's hide is not authoritative.
The same shape holds for `kb_equ`/`kb_chr`/`kb_bud`/`kb_opt` and the map/search windows: **seven call sites,
one private method.**

**The model follows from 029: a native window you hid is a window you own.** 029 already records every hide on
`Addon.hiddenNative` and gives it back on teardown; this makes the client's own toggle respect that record.

- `w:hide()` alone ⇒ the toggle is **swallowed**: the stock window stays gone. Most of the observed problem.
- `replace(...)` ⇒ the toggle **drives your view** instead, and `wndstate` reads your view, so the menu
  checkbox keeps telling the truth.
- Teardown hands the toggle back **and leaves the window as the user was seeing it** (maintainer, 2026-08-02):
  the addon's view was open ⇒ the stock window is open; nothing was on screen ⇒ it stays closed. One rule.

**No new public API**: `replace` already knows both halves — the window it hid and the view your `fn` returned
— so it binds the toggle itself, and swallowing is simply what hiding a native window now means.

Feature **B3a** (A ✅ → B1 ✅ → B2 ✅ → **B3a the toggle** → B3b `replace(selector)` → C skin → D chrome →
E layout). Server-opened containers (chest, cupboard, barrel) already work through `replace` — no client toggle
competes for them. **`replace(selector, fn)` is B3b**, planned next and deliberately not folded in here: it is a
separate, independently verifiable capability, and this one is the felt bug.

## Acceptance criteria

- [ ] With `bags` active: **Tab no longer opens the stock inventory**, and neither does the menu checkbox.
- [ ] With `bags` active, Tab and the menu button **open and close the addon's view**, and the checkbox's tick
      follows the view, not the hidden window — wired by `replace`, with nothing new for the addon to call.
- [ ] Ownership is **per hidden window**: hiding the inventory leaves Equipment, Character Sheet, Kin, Options
      and the map window behaving exactly as stock.
- [ ] Two addons cannot both own one window's toggle — the second gets a clear error naming the first.
- [ ] **One teardown rule, no branches: the window ends up as the user was seeing it.** Disable / `:reload` /
      relog give the toggle back and leave the stock window **open if the addon's view was open, closed if
      nothing was on screen** — same window, stock styling. (A blind `show()` fails the closed half: the
      inventory wrapper is hidden by default, so it would hand the user a window they never opened.)
- [ ] A toggle on a window whose owner addon has gone stale falls through to stock behaviour, never a dead key.
- [ ] The core edit is **`// addon:` one-liners inside `togglewnd` and `wndstate`** — no visibility changes, no
      new call sites, and with no addon loaded the client behaves byte-for-byte as before.
- [ ] `hello` checks the contract once per login (swallowed toggle, the double-owner error, both halves of the
      teardown rule); `bags` is the real consumer; full regression passes.

## Out of scope

- **`replace(selector, fn)`** — B3b; the descriptor language stays as it is here.
- **Taking over a client keybinding** as such. This owns the *window*, not the key: `kb_inv` still fires its
  normal click, which now asks the window's owner. Rebinding stays `hafen.client:options():keybindings()`.
- Server-opened windows (containers) — no client toggle competes; `replace` already covers them.
- Any restyling or layout of the replaced window — C/D/E. And windows the client opens for its own reasons
  mid-session (a dialog, an error): only the toggle path is owned.

## Context files

- `design/08-widget-replacement.md` — "wrap, don't reimplement" (D-009), the hidden-model lifetime and restore;
  `design/07-ui-and-drawing.md` — the owned-widget registry (P2) the ownership record joins
- `src/haven/GameUI.java:1481` `togglewnd` + `:1475` `wndstate` — **the seam**; `:1507-1520` the bindings,
  checkboxes and their per-frame `state()` supplier; `:328`/`:1551` the other two call sites
- `src/io/brodgar/addon/LuaWidget.java:554` + `Addon.java:116` — 029's `hide`/`show`, the `Hidden` record and
  the `hiddenNative` restore list that becomes the ownership record
- `src/io/brodgar/addon/UiApi.java` — `replace` (which wires the toggle to its view) and teardown
- `src/haven/AddonWidgets.java` — where a `haven`-side facade for the seam belongs (the 027 precedent)
- `docs/addons/api/ui.md` — the entity, `:hide()`/`:show()` and the restore rule this extends; `029-widget-oop/`
  — prior art: the hide record, the two-branch teardown guard, owned vs borrowed
- `addons/bags/main.lua`, `addons/hello/main.lua` — the real consumer and the harness
- `learnings/widget-replacement.md`, `learnings/ui-widgets.md` — **grep, never read whole**;
  `decisions/widgets-ui.md` (D-009, D-024), `decisions/architecture-api.md` (D-011 invasiveness, D-012)
