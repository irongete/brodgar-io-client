# Subsystem: GameUI's own windows (the HUD wrappers, the menu bars, the toggle path, the position store)

> `file:line` anchors for the windows **the client itself opens, closes and remembers**. Distinct from
> [widgets.md](widgets.md) (generic tree + create/place/destroy) and [ui-chrome.md](ui-chrome.md) (`Window.Deco`,
> `IBox`, the `iresize`/`contarea`/`csz` geometry). The **class + method name is the stable anchor**. Max 70.

## The windows GameUI owns — fields on [`GameUI`](src/haven/GameUI.java:55): `invwnd`, `equwnd`, `makewnd`, `srchwnd`, `iconwnd`, `chrwdg`, `zerg`, `opts`, `mapfile`

| What | Where |
|---|---|
| **`Hidewnd`** — a `Window` whose close **hides** instead of destroying | [`GameUI.Hidewnd`](src/haven/GameUI.java:570) — `reqclose() { hide(); }` |
| Inventory / equipment: server grid → a **wrapper created hidden** | [`GameUI.addchild`](src/haven/GameUI.java:957) `place == "inv"` — `new Hidewnd(…, "Inventory")`, `add(maininv)`, `pack()`, **`hide()`**; `"equ"` at [:967](src/haven/GameUI.java:967) is the same shape |
| Action search | [:950](src/haven/GameUI.java:950) `place == "menu"` — `srchwnd`, `reqclose(srchwnd::hide).hide()` |

**The wrappers are hidden from birth** — `maininv` exists from login, the `Hidewnd` around it does not, so *"put
the inventory back"* is **never** a blind `show()` (D-070: as the user was seeing it). **TRAP — that wrapper
PACKS AROUND ITS GRID and cannot be resized from outside** (036.1): it is anonymous, with
`cresize(ch) { pack(); }` ([:961](src/haven/GameUI.java:961)), and [`Widget.resize`](src/haven/Widget.java:1534)
notifies `parent.cresize(this)` — so `resize2`'s `deco.iresize` makes the deco call back and the window re-packs
to its content **before your call returns** (`pack()` = `resize(contentsz())`). `equwnd` has none, and resizes.

## The client's own position store (`wndc-*`, and every place it is written)

| What | Where |
|---|---|
| The main writer — **at logout AND every 60 s** | [`savewndpos`](src/haven/GameUI.java:896): `wndc-inv/-equ/-chr/-zerg/-map` + `wndsz-map` (`mapfile.csz()`, the CONTENT size). From [`dispose()`](src/haven/GameUI.java:475) **and** from [`tick`](src/haven/GameUI.java:1336) on a `lastwndsave` clock |
| Two more writers, same shape | [`cdestroy`](src/haven/GameUI.java:1140) → `wndc-misc/<wndid>` for any server window carrying an `"id"` opt ([:1061](src/haven/GameUI.java:1061) reads it back); the crafting window's own `destroy()` → `makewndc` ([:1002](src/haven/GameUI.java:1002)) |
| The reads + the clamp | `Utils.getprefc(key, default)` **at construction** — [:965](src/haven/GameUI.java:965)/[:971](src/haven/GameUI.java:971)/[:978](src/haven/GameUI.java:978)/[:946](src/haven/GameUI.java:946)/[:310](src/haven/GameUI.java:310) — most through the `private` [`fitwdg`](src/haven/GameUI.java:1471), which clamps `wdg.c` so ≥ `UI.scale(100)` px stays inside `GameUI.sz` (cheaper re-derived — `UiApi.fitView` — than widened) |

**This store belongs to what the USER placed** — the one thing an addon cannot undo in memory, since a `setprefc` outlives it. The writes are *unconditional*: no dirty flag, no "only if it changed".

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
`click()` — no separate keyboard path, so one edit covers all seven call sites. **`state()` is polled every frame, per checkbox** (six) ⇒ allocation-free. **[`show(boolean)`](src/haven/Widget.java:2059) returns its ARGUMENT**, not whether anything changed (031.2).

## `Window`'s visibility is a small state machine (it wraps show/hide around a fade `Transition`)

| What | Where |
|---|---|
| `visible()` — **animation-aware** | [:556](src/haven/Window.java:556) — `visible && ((animst == null) \|\| (animst == "show"))` |
| `hide()` does **not** clear `visible` | [:591](src/haven/Window.java:591) — starts `animst = "hide"`; [`tick`](src/haven/Window.java:524) calls `super.hide()` when the anim ends |
| `reqdestroy()` → `animst = "dest"`; `trans` set on attach | [:609](src/haven/Window.java:609) — `tick` destroys at the end (the 030 fading corpse); [`added()`](src/haven/Window.java:119) → `initanim()`, and it also `setfocus`es a visible window |

**A fading-out window already reads `visible() == false`**, so a menu tick reads one directly, no debounce.
[`RootWidget`](src/haven/RootWidget.java:41) sets `focusctl` — why `parent.setfocus(w)` terminates.

## The addon seams (031, 035, 036)

All `// addon:` edits sit **inside existing method bodies** — no visibility change, no new call site — and go
through [`AddonWidgets`](src/haven/AddonWidgets.java), so `haven` keeps one file that knows the addon layer
exists. `togglewnd` asks `toggleWnd(wnd)` first (`true` = handled), `wndstate` asks `wndState(wnd)` (`null` =
not owned); ownership is 029's hide record (`Addon.hiddenNative`) by **widget identity**, which also disposes of
the fading corpse from [widgets.md](widgets.md#core-tree), and since 031.2 that record carries the view, so
`wndState` answers `view.visible()`. 035's `chrome(wnd)` is in [`Window.tick`](src/haven/Window.java:525)
([ui-chrome.md](ui-chrome.md)). **036.1 is a SUBSTITUTION, not a call**: every `Utils.setprefc(key, w.c)` in the
three writers above reads `AddonWidgets.stockc(w)` instead (`stockcsz` for `wndsz-map`) — the widget's own value
unless an addon's layout stands on it. It substitutes rather than restores because `savewndpos` also runs on that
60 s tick — putting the widgets back around the write would snap a laid-out HUD once a minute.
