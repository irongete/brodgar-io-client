# Subsystem: GameUI's own windows (the HUD wrappers, the menu bars, the toggle path)

> `file:line` anchors for the windows **the client itself opens and closes** — distinct from
> [widgets.md](widgets.md), which covers the generic tree and the server's create/place/destroy seams. Lines are
> indicative; the **class + method/field name is the stable anchor**. Max 70 lines — currently over, because 035
> added the `Deco` contract; split it out if it grows again.

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

## The `Deco` contract — a window's chrome is a swappable CHILD (035)

| What | Where |
|---|---|
| The field + the live swap | [`Window.deco`](src/haven/Window.java:77) · [`chdeco(Deco)`](src/haven/Window.java:131) — public; `uimsg "dhide"` ([:429](src/haven/Window.java:429)) already drives it |
| The contract, and the stock one | [`Window.Deco`](src/haven/Window.java:152) — `z(-100)`, `abstract iresize(Coord)` + `contarea()`; [`DragDeco`](src/haven/Window.java:161) adds the caption drag; [`DefaultDeco`](src/haven/Window.java:177) adds `drawbg`/`drawframe`, the close `IButton`, the sizer, `checkhit` and the margins (`dlmrgn`/`dsmrgn` + `tlm`/`brm`) |
| Who builds their own | [`Window.makedeco()`](src/haven/Window.java:116) (**`protected`**) · [`MapWnd`](src/haven/MapWnd.java:827) `DefaultDeco(true).dragsize(true)`, and `compact()` sets it **null** · [`GItem.ContentsWindow`](src/haven/GItem.java:453) swaps `HoverDeco`/`DefaultDeco` per state |
| Geometry flows one way | [`resize2`](src/haven/Window.java:407) — `deco.iresize(sz)`, `deco.c = contarea().ul.inv()`, `this.sz = deco.sz`. **The ctor's `sz` is the CONTENT size**; `ca()`/`csz()`/`xlate` all read `contarea()` |
| The stock layout formula | [`DefaultDeco.iresize`](src/haven/Window.java:214) — `content + mrgn*2 + tlm + brm`: an inner **margin** (`dlmrgn` 23x14 / `dsmrgn` 9x9, all `UI.scale`d) and outer **frame insets** (`tlm` 18x30, `brm` 13x22). `ca` = the bg box, `aa` = `contarea()`, `cbtn` pinned to the top right |

**`chdeco` destroys what it displaces** (`reqdestroy()`), so a swap can never put the *same* object back; it reads
the old `contarea()` first, re-runs the layout, and folds the difference into the **window's own `c`** — so a
swap anchors the *content*, not the window's corner, and an equal-geometry swap leaves `sz`, `c` and the content
area untouched (D-078). A deco that re-lays itself out **while installed** must copy those three steps
(`Window.c` is public); skipping the last one drifts every window by the change. `Window.draw` renders children into `gbuf` and blits, clearing it to `FColor.BLACK_T` — so
anything the chrome does not paint is transparent black, not a background (D-079).

## The addon seam (031, 035)

Three `// addon:` one-liners **inside method bodies** — no visibility change, no new call site — all delegating
to `io.brodgar.addon.AddonManager` through [`AddonWidgets`](src/haven/AddonWidgets.java), so `haven` keeps exactly
one file that knows the addon layer exists. `togglewnd` asks `toggleWnd(wnd)` first (`true` = handled, leave the
window alone) and `wndstate` asks `wndState(wnd)` (`null` = not owned, read the window as usual); ownership is
029's hide record (`Addon.hiddenNative`) matched by **widget identity**, which also disposes of the fading-corpse
case from [widgets.md](widgets.md#core-tree), and since 031.2 that record carries the addon's **view**, so
`wndState` answers `view.visible()`. 035 adds `chrome(wnd)` at the top of
[`Window.tick`](src/haven/Window.java:525) — install/drop the sheet-fed deco, in `tick` because `chdeco` destroys
a widget and re-lays out. Its only other `haven` edit is an **extraction**: `DefaultDeco.drawframe`'s caption
block became `protected checkcap()` ([:253](src/haven/Window.java:253)) so a replacement deco renders the caption
through F3a's routed furnace instead of duplicating it. **035.2 added no core edit at all** — the geometry half
overrides `iresize` in the addon's own subclass, and `chdeco`/`resize`/`tlm`/`dsmrgn`/`c` are already public.
