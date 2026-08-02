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

**The wrappers are hidden from birth.** `maininv` exists from login; the `Hidewnd` around it does not become
visible until the user asks. So *"put the inventory back"* is **not** `show()` — restoring it means replaying
the visibility it actually had, or you hand the user a window they never opened.

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
the mouse button working**, and vice versa. Everything funnels into `togglewnd`, which is why one edit there
covers all seven call sites.

**`state()` is polled every frame, per checkbox.** `ACheckBox.draw` calls `state.get()`, and there are six such
suppliers on the HUD. Anything hung off `wndstate` is on the frame path and must be allocation-free.

**[`show(boolean)`](src/haven/Widget.java:2059) returns whether it changed.** `togglewnd` only does `raise`/`fitwdg`/`setfocus` when
`wnd.show(...)` reports a real transition — do not assume the follow-up always runs.

## The addon seam (031)

Two `// addon:` one-liners **inside the two method bodies** — no visibility change, no new call site:
`togglewnd` asks [`AddonWidgets.toggleWnd(wnd)`](src/haven/AddonWidgets.java) first (`true` = handled, leave the
window alone), `wndstate` asks [`wndState(wnd)`](src/haven/AddonWidgets.java) (`null` = not owned, read the window
as usual). Both delegate to `io.brodgar.addon.AddonManager`, so `haven` keeps exactly one file that knows the
addon layer exists. Ownership is 029's hide record (`Addon.hiddenNative`), matched by **widget identity** — which
also disposes of the fading-corpse case from [widgets.md](widgets.md#core-tree): a closing window lingers in the
tree, but it is not the object `GameUI` still holds.
