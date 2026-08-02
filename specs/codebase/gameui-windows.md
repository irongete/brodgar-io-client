# Subsystem: GameUI's own windows (the HUD wrappers, the menu bars, the toggle path)

> `file:line` anchors for the windows **the client itself opens and closes** — distinct from
> [widgets.md](widgets.md), which covers the generic tree and the server's create/place/destroy seams. Lines are
> indicative; the **class + method/field name is the stable anchor**. Max 70 lines.

## The windows GameUI owns

| What | Where |
|---|---|
| The fields | [`GameUI`](src/haven/GameUI.java:55) — `invwnd, equwnd, makewnd, srchwnd, iconwnd`; plus `chrwdg`, `zerg`, `opts`, `mapfile` |
| **`Hidewnd`** — a `Window` whose close **hides** instead of destroying | [`GameUI.Hidewnd`](src/haven/GameUI.java:570) — `reqclose() { hide(); }` |
| Inventory: server grid → a **wrapper created hidden** | [`GameUI.addchild`](src/haven/GameUI.java:957) `place == "inv"` — `new Hidewnd(…, "Inventory")`, `add(maininv)`, `pack()`, **`hide()`** |
| Equipment: same shape | [:967](src/haven/GameUI.java:967) `place == "equ"` |
| Action search | [:950](src/haven/GameUI.java:950) `place == "menu"` — `srchwnd`, `reqclose(srchwnd::hide).hide()` |
| Position + visibility prefs | [`savewndpos`](src/haven/GameUI.java:896) writes `wndc-inv/equ/chr/zerg/…`; `wndvis-map` is written at the map toggle ([:1553](src/haven/GameUI.java:1553)) |

**The wrappers are hidden from birth.** `maininv` exists from login; the `Hidewnd` around it does not. So *"put
the inventory back"* is **never** a blind `show()` — that hands the user a window they never opened (what it IS:
D-070, as the user was seeing it).

## The toggle path (one private method, seven call sites)

| What | Where |
|---|---|
| **The toggle** ← addon seam | [`GameUI.togglewnd(Window)`](src/haven/GameUI.java:1482) — `wnd.show(!wnd.visible())` then `raise`/`fitwdg`/`setfocus` |
| **The tick** ← addon seam | [`GameUI.wndstate(Window)`](src/haven/GameUI.java:1475) — just `wnd.visible()` |
| The buttons | [`GameUI.MenuButton`](src/haven/GameUI.java:1492) / [`MenuCheckBox`](src/haven/GameUI.java:1500) — both call `setgkey(gkey)` in the ctor |
| The bindings | [`kb_inv`](src/haven/GameUI.java:1507) (Tab), `kb_equ`, `kb_chr`, `kb_bud`, `kb_opt`; [`kb_map`](src/haven/GameUI.java:1529), `kb_srch` |
| The five main-menu checkboxes | [`MainMenu`](src/haven/GameUI.java:1516-1520) — `.state(() -> wndstate(x)).click(() -> togglewnd(x))` |
| The map menu | [`MapMenu`](src/haven/GameUI.java:1550) — `mapfile` (also writes `wndvis-map`) and `iconwnd` (which does **not** use `togglewnd`: it creates/`reqclose`s) |
| The odd one out | [`menubuttons`](src/haven/GameUI.java:328) — `srchwnd`: focus it if visible-but-unfocused, else `togglewnd` |
| Checkbox plumbing | [`ACheckBox.state`](src/haven/ACheckBox.java:37) `Supplier<Boolean>` · [`state()`](src/haven/ACheckBox.java:39) · [`click`](src/haven/ACheckBox.java:59) `Runnable` |
| Key → the button's own click | [`Widget.globtype`](src/haven/Widget.java:1478) matches `kb_gkey.key()` → [`gkeytype`](src/haven/Widget.java:1431), which [`ACheckBox`](src/haven/ACheckBox.java:63) overrides to `click()` |

**The key and the button are ONE click.** `setgkey(KeyBinding)` ([`Widget`](src/haven/Widget.java:1492)) stores
`kb_gkey`; a `GlobKeyEvent` matching it reaches the widget's own `gkeytype`, which for an `ACheckBox` simply calls
`click()`. So there is no separate keyboard path to intercept: **rebinding or consuming the key would still leave
the mouse button working**, and vice versa. Everything funnels into `togglewnd` — one edit there covers all seven
call sites. **`state()` is polled every frame, per checkbox** (`ACheckBox.draw` calls `state.get()`, six of them
on the HUD), so anything hung off `wndstate` is on the frame path and must be allocation-free.

**[`show(boolean)`](src/haven/Widget.java:2059) returns its own ARGUMENT**, not whether anything changed (031.2
correction): `togglewnd`'s `raise`/`fitwdg`/`setfocus` run whenever it is *showing*, never when hiding.
**[`fitwdg`](src/haven/GameUI.java:1471)** is `private` — it clamps `wdg.c` so at least `fitmarg = UI.scale(100)`
px (or the widget's own extent) stays inside `GameUI.sz`; four lines of arithmetic over public `c`/`sz`, cheaper
re-derived (`UiApi.fitView`, against the widget's own parent) than widened.

## `Window`'s visibility is a small state machine (it wraps show/hide around a fade `Transition`)

| What | Where |
|---|---|
| `visible()` — **animation-aware** | [:556](src/haven/Window.java:556) — `visible && ((animst == null) \|\| (animst == "show"))` |
| `hide()` does **not** clear `visible` | [:591](src/haven/Window.java:591) — starts `animst = "hide"`; [`tick`](src/haven/Window.java:524) calls `super.hide()` when the anim ends |
| `reqdestroy()` → `animst = "dest"` | [:609](src/haven/Window.java:609) — `tick` destroys at the end (the 030 fading corpse) |
| `trans` is set on attach | [`added()`](src/haven/Window.java:119) → `initanim()`; `added()` also `setfocus`es a visible window |

**A fading-out window already reads `visible() == false`**, so a menu tick can read one directly, with no
debounce — *drawn* and *real* are different axes and this answers the first.
[`RootWidget`](src/haven/RootWidget.java:41) sets `focusctl`, which is why `parent.setfocus(w)` terminates.

## The addon seam (031)

Two `// addon:` one-liners **inside the two method bodies** — no visibility change, no new call site:
`togglewnd` asks [`AddonWidgets.toggleWnd(wnd)`](src/haven/AddonWidgets.java) first (`true` = handled, leave the
window alone), `wndstate` asks [`wndState(wnd)`](src/haven/AddonWidgets.java) (`null` = not owned, read the window
as usual). Both delegate to `io.brodgar.addon.AddonManager`, so `haven` keeps exactly one file that knows the
addon layer exists. Ownership is 029's hide record (`Addon.hiddenNative`), matched by **widget identity** — which
also disposes of the fading-corpse case from [widgets.md](widgets.md#core-tree). Since 031.2 that record carries
the addon's **view**, so `toggleWnd` drives it and `wndState` answers `view.visible()`.
