# Subsystem: GameUI's own windows (the HUD wrappers, the menu bars, the toggle path)

> `file:line` anchors for the windows **the client itself opens and closes** — distinct from
> [widgets.md](widgets.md), which covers the generic tree and the server's create/place/destroy seams, and from
> [ui-chrome.md](ui-chrome.md), which owns `Window.Deco` and `IBox` (split out at 035.3, when this file went
> over budget). Lines are indicative; the **class + method/field name is the stable anchor**. Max 70 lines.

## The windows GameUI owns — fields on [`GameUI`](src/haven/GameUI.java:55): `invwnd`, `equwnd`, `makewnd`, `srchwnd`, `iconwnd`, `chrwdg`, `zerg`, `opts`, `mapfile`

| What | Where |
|---|---|
| **`Hidewnd`** — a `Window` whose close **hides** instead of destroying | [`GameUI.Hidewnd`](src/haven/GameUI.java:570) — `reqclose() { hide(); }` |
| Inventory / equipment: server grid → a **wrapper created hidden** | [`GameUI.addchild`](src/haven/GameUI.java:957) `place == "inv"` — `new Hidewnd(…, "Inventory")`, `add(maininv)`, `pack()`, **`hide()`**; `"equ"` at [:967](src/haven/GameUI.java:967) is the same shape |
| Action search | [:950](src/haven/GameUI.java:950) `place == "menu"` — `srchwnd`, `reqclose(srchwnd::hide).hide()` |
| Position + visibility prefs | [`savewndpos`](src/haven/GameUI.java:896) writes `wndc-inv/equ/chr/zerg/…`; `wndvis-map` at the map toggle ([:1553](src/haven/GameUI.java:1553)) |

**The wrappers are hidden from birth.** `maininv` exists from login; the `Hidewnd` around it does not — so *"put
the inventory back"* is **never** a blind `show()` (D-070: as the user was seeing it).

## The toggle path (one private method, seven call sites)

| What | Where |
|---|---|
| **The toggle** ← addon seam | [`GameUI.togglewnd(Window)`](src/haven/GameUI.java:1482) — `wnd.show(!wnd.visible())` then `raise`/`fitwdg`/`setfocus` |
| **The tick** ← addon seam | [`GameUI.wndstate(Window)`](src/haven/GameUI.java:1475) — just `wnd.visible()` |
| The buttons + their bindings | [`MenuButton`](src/haven/GameUI.java:1492) / [`MenuCheckBox`](src/haven/GameUI.java:1500) — both `setgkey(gkey)` in the ctor; [`kb_inv`](src/haven/GameUI.java:1507) (Tab), `kb_equ`, `kb_chr`, `kb_bud`, `kb_opt`, [`kb_map`](src/haven/GameUI.java:1529), `kb_srch` |
| The five main-menu checkboxes | [`MainMenu`](src/haven/GameUI.java:1516-1520) — `.state(() -> wndstate(x)).click(() -> togglewnd(x))`; plumbing is [`ACheckBox.state`](src/haven/ACheckBox.java:37) `Supplier<Boolean>` + [`click`](src/haven/ACheckBox.java:59) `Runnable` |
| The map menu | [`MapMenu`](src/haven/GameUI.java:1550) — `mapfile` (also writes `wndvis-map`) and `iconwnd` (which does **not** use `togglewnd`: it creates/`reqclose`s) |
| The odd one out | [`menubuttons`](src/haven/GameUI.java:328) — `srchwnd`: focus it if visible-but-unfocused, else `togglewnd` |
| Key → the button's own click | [`Widget.globtype`](src/haven/Widget.java:1478) matches `kb_gkey.key()` → [`gkeytype`](src/haven/Widget.java:1431), which [`ACheckBox`](src/haven/ACheckBox.java:63) overrides to `click()` |

**The key and the button are ONE click.** `setgkey(KeyBinding)` ([`Widget`](src/haven/Widget.java:1492)) stores
`kb_gkey`; a matching `GlobKeyEvent` reaches the widget's own `gkeytype`, which for an `ACheckBox` calls
`click()`. So there is no separate keyboard path to intercept, and everything funnels into `togglewnd` — one edit
covers all seven call sites. **`state()` is polled every frame, per checkbox** (six on the HUD), so anything hung
off `wndstate` is on the frame path and must be allocation-free.
**[`show(boolean)`](src/haven/Widget.java:2059) returns its own ARGUMENT**, not whether anything changed (031.2):
`togglewnd`'s `raise`/`fitwdg`/`setfocus` run whenever it is *showing*.
**[`fitwdg`](src/haven/GameUI.java:1471)** is `private` — it clamps `wdg.c` so at least `fitmarg = UI.scale(100)`
px stays inside `GameUI.sz`; cheaper re-derived (`UiApi.fitView`) than widened.

## `Window`'s visibility is a small state machine (it wraps show/hide around a fade `Transition`)

| What | Where |
|---|---|
| `visible()` — **animation-aware** | [:556](src/haven/Window.java:556) — `visible && ((animst == null) \|\| (animst == "show"))` |
| `hide()` does **not** clear `visible` | [:591](src/haven/Window.java:591) — starts `animst = "hide"`; [`tick`](src/haven/Window.java:524) calls `super.hide()` when the anim ends |
| `reqdestroy()` → `animst = "dest"` | [:609](src/haven/Window.java:609) — `tick` destroys at the end (the 030 fading corpse) |
| `trans` is set on attach | [`added()`](src/haven/Window.java:119) → `initanim()`; `added()` also `setfocus`es a visible window |

**A fading-out window already reads `visible() == false`**, so a menu tick can read one directly, no debounce.
[`RootWidget`](src/haven/RootWidget.java:41) sets `focusctl`, which is why `parent.setfocus(w)` terminates.

**The chrome itself is [ui-chrome.md](ui-chrome.md)** — `Window.deco`/`chdeco`, the `Deco` contract and its
`iresize`/`contarea` geometry, plus `IBox` and the window-less panels that draw one.

## The addon seam (031, 035)

Three `// addon:` one-liners **inside method bodies** — no visibility change, no new call site — all delegating
to `io.brodgar.addon.AddonManager` through [`AddonWidgets`](src/haven/AddonWidgets.java), so `haven` keeps exactly
one file that knows the addon layer exists. `togglewnd` asks `toggleWnd(wnd)` first (`true` = handled, leave the
window alone) and `wndstate` asks `wndState(wnd)` (`null` = not owned, read the window as usual); ownership is
029's hide record (`Addon.hiddenNative`) matched by **widget identity**, which also disposes of the fading-corpse
case from [widgets.md](widgets.md#core-tree), and since 031.2 that record carries the addon's **view**, so
`wndState` answers `view.visible()`. The third is 035's `chrome(wnd)` in
[`Window.tick`](src/haven/Window.java:525) — see [ui-chrome.md](ui-chrome.md) for it and for the chrome edits.
