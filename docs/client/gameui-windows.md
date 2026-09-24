# GameUI's own windows: wrappers, menu bars and the toggle path

> Covers the windows **the client itself opens and closes**. Distinct from [widgets.md](widgets.md) (generic tree + create/place/destroy), [ui-chrome.md](ui-chrome.md) (`Window.Deco`, `IBox`, the `iresize`/`contarea`/`csz` geometry) and [window-positions.md](window-positions.md) (where each window stands: the `wndc-*` store and the screen resize). The **class + method name is the stable anchor**.

## The windows GameUI owns — fields on `GameUI`: `invwnd`, `equwnd`, `makewnd`, `srchwnd`, `iconwnd`, `chrwdg`, `zerg`, `opts`, `mapfile`

| What | Where |
|---|---|
| **`Hidewnd`** — a `Window` whose close **hides** instead of destroying | `GameUI.Hidewnd` — `reqclose() { hide(); }`. **The contrast is what the close button means**: `Window.reqclose` defaults to `wdgmsg("close")`, so a server-put-up container window's close is a *request* the server answers with a widget destroy — the window, its `Inventory` and every `GItem` in it die through `rdispose` and never through `remove` ([widgets.md](widgets.md)) — while a `Hidewnd` answers itself and destroys nothing, so `maininv`'s items outlive every close |
| Inventory / equipment / character sheet: **a close that hides**, twice by wrapper and once by the window itself | `GameUI.addchild` `place == "inv"` — `new Hidewnd(…, "Inventory")`, `add(maininv)`, `pack()`, **`hide()`**; `"equ"` is the same shape. `"chr"` has no wrapper and answers for itself: `chrwdg = add((CharWnd)child, …)`, then `chrwdg.reqclose(chrwdg::hide).hide()` — so `CharWnd` and every tab under it (`battr`/`BAttrWnd`, `sattr`/`SAttrWnd` with its study inventory, `fight`/`FightWnd`) is put up once and outlives every close; only a server destroy ends it (`GameUI.cdestroy` nulls `chrwdg`) |
| Action search |  `place == "menu"` — `srchwnd`, `reqclose(srchwnd::hide).hide()` |
| Crafting: an anonymous wrapper the CONTENT ends | `place == "craft"` — `new Window(…, ((Makewindow)child).rcpnm)`, `add(mkwdg)`, `pack()`. Its `cdestroy(w)` runs `ui.destroy(this)` and `makewnd = null` when `w == mkwdg` |

**The crafting pair dies content-first, and only the content dies at once.** The server destroys the
`Makewindow`, a plain `Widget` whose `reqdestroy()` is `destroy()`: it unlinks immediately and
`hasparent(root)` goes false on the same call. Its `cdestroy` then destroys the anonymous wrapper, and *that*
is a `Window`, whose `reqdestroy` starts a fade — so for a while the wrapper is reachable with nothing in it.
Ask the **content** widget whether a recipe is open, never the wrapper and never `makewnd`, which is one
field for whichever recipe is newest. See the two-branch liveness rule in [widgets.md](widgets.md).

**The wrappers are hidden from birth** — `maininv` exists from login, the `Hidewnd` around it does not, so *"put
the inventory back"* is **never** a blind `show()`. **And only the GRID is the server's**: the wrapper is
constructed here rather than sent, so `Widget.wdgid()` on it is `-1` and it appears in no `UI.rwidgets` entry —
address the inventory by id through `maininv` (`GameUI.equwnd`'s `Equipory` likewise), and reserve the wrapper
for what it is, a frame the client hangs around it. **TRAP — that wrapper PACKS AROUND ITS GRID and cannot be
resized from outside**: it is anonymous, with `cresize(ch) { pack(); }`, and `Widget.resize` notifies
`parent.cresize(this)` — so `resize2`'s `deco.iresize` makes the deco call back and the window re-packs to its
content **before your call returns** (`pack()` = `resize(contentsz())`). `equwnd` has none, and resizes.

## The HUD itself: seven `Hidepanel`s, and the plates they blit

| What | Where |
|---|---|
| The panel | `GameUI.Hidepanel` — an id, an `Indir<Coord> base` and a `g` gravity; `add(T)` **`pack()`s and `move()`s on every child added**, so a panel is exactly its contents. `move(double a)` slides it off along `g`, `show(boolean)` is remembered as `<id>-visible` |
| The seven | built in the ctor, in order: `blpanel`, `mapmenupanel`, `brpanel`, `menupanel`, `ulpanel`, `umpanel`, `urpanel`. The two menu panels take an `Indir` base that chases the corner panel beside them |
| The plates blitted **in a widget's own `draw`** | `MainMenu.draw` → `menubg` (`gfx/hud/rbtn-bg`), `MapMenu.draw` → `mapmenubg` (`gfx/hud/lbtn-bg`), `NKeyBelt.draw` → `nkeybg` (`gfx/hud/hb-main`), each a `static final Tex` and each `g.image(…, Coord.z)` before the rest of the draw |
| The plates that are **`Img` children** | `gfx/hud/blframe` in `blpanel` (the minimap's frame), `gfx/hud/csearch-bg` and `gfx/hud/brframe` in `brpanel`. Each is a plain `Img`, so it is a widget in the tree rather than a blit |
| What sits **on** them | `menugridc` is `brframe.c` plus a constant and `menubuttons` places the search button at `rbtnimg.c` — the `Img`'s own box decides, once, at construction. `minimapc` beside them is a bare constant |

**The size of a panel is the size of its plate**: `MainMenu` and `MapMenu` both call `super(<tex>.sz())`
and `Hidepanel.add` packs around them. Nothing re-measures on a redraw, so a plate painted at another size
is drawn into the box the client's art gave it.

⚠️ **It re-packs on two events and re-anchors on one**: `add` does `pack()` **and** `move()`, `cresize` (a
child's `resize`) does `sz = contentsz()` and no `move()`, and a child moved, hidden or unlinked reaches
neither. `move()` derives `c` from `sz`, so a child resized *after* it was added leaves the panel anchored
to its old box — a gap at the screen edge — until the next `add` or fold. Size a widget before adding it.

⚠️ **`resetui()` persists the visibility of all seven and every fold click runs it** (`updfold(reset)`): it
loops `p.cshow(p.tvis)`, and `cshow` writes `<id>-visible` **before** comparing. `toggleui` (`kb_hide`,
unbound) slides them all off setting `tvis` and writing nothing — so one fold click while the UI is toggled
off records *all seven hidden*, the next start comes up bare, and cycling `toggleui` back does not undo it.

⚠️ **`beltwdg` is not in a panel, and there are two of it.** It is `add`ed to `GameUI` itself and placed by
hand in `resize`/`updfold`; `NKeyBelt` (`super(nkeybg.sz())`) blits that plate, `FKeyBelt` — the Options
belt setting's other half — draws **no background**, and both blit `Inventory.invsq` for their squares.

## Where a server-placed HUD widget actually HANGS

A widget's own `ui` says which session it belongs to; **which HUD it belongs to is the upward
`getparent` walk**, so what it is parented to is the whole of what that walk can find. `GameUI.addchild`
does not add most of them to `GameUI`.

| Widget | Its parent |
|---|---|
| `IMeter` (`place == "meter"`) | **`ulpanel`**, not `GameUI` — `GameUI.meters` is a separate ordered record, not a child list |
| `Buff` (`place == "buff"`) | `GameUI.buffs`, and `Bufflist.addchild` is a plain `add` — so a buff IS a direct child of the `Bufflist` |
| `BAttrWnd` / `SAttrWnd` / `SkillWnd` / `FightWnd` / `QuestWnd` / `WoundWnd` | the matching `Tabs.Tab` inside `CharWnd` (`CharWnd.battrtab.add(...)` and its siblings), not `CharWnd` itself |
| The study inventory | **`SAttrWnd`** — `SAttrWnd.addchild`'s `place == "study"` branch does `add(child)` on itself and then builds a `StudyInfo` **beside** it, handing the inventory in as a constructor argument |

⚠️ **`StudyInfo` does not contain the study inventory it reports on** — the two are siblings under
`SAttrWnd`, and `StudyInfo.study` is a reference. `getparent(StudyInfo.class)` from one of its `GItem`s
finds nothing; the walk is up to `SAttrWnd` and back down through `children(StudyInfo.class)`. The same
shape holds for `CharWnd`: a walk from `BAttrWnd` reaches `CharWnd` only because `Tabs.Tab` is a widget
in between, so the hop count is never one.

## The toggle path (one private method, seven call sites)

| What | Where |
|---|---|
| **The toggle** ← addon seam | `GameUI.togglewnd(Window)` — `wnd.show(!wnd.visible())` then `raise`/`fitwdg`/`setfocus` |
| **The tick** ← addon seam | `GameUI.wndstate(Window)` — just `wnd.visible()` |
| The buttons + their bindings | `MenuButton` / `MenuCheckBox` — both `setgkey(gkey)` in the ctor; `kb_inv` (Tab), `kb_equ`, `kb_chr`, `kb_bud`, `kb_opt`, `kb_map`, `kb_srch` |
| The five main-menu checkboxes | `MainMenu` — `.state(() -> wndstate(x)).click(() -> togglewnd(x))`; plumbing is `ACheckBox.state` `Supplier<Boolean>` + `click` `Runnable` |
| The map menu | `MapMenu` — `mapfile` (also writes `wndvis-map`) and `iconwnd` (which does **not** use `togglewnd`: it creates/`reqclose`s) |
| The odd one out | `menubuttons` — `srchwnd`: focus it if visible-but-unfocused, else `togglewnd` |
| Key → the button's own click | `Widget.globtype` matches `kb_gkey.key()` → `gkeytype`, which `ACheckBox` overrides to `click()` |

**The key and the button are ONE click.** `setgkey(KeyBinding)` (`Widget`) stores `kb_gkey`; a matching `GlobKeyEvent` reaches the widget's own `gkeytype`, which for an `ACheckBox` calls `click()` — no separate keyboard path, so one edit covers all seven call sites.
**`state()` is polled every frame, per checkbox** (six) ⇒ allocation-free. **`show(boolean)` returns its ARGUMENT**, not whether anything changed.

## `Window`'s visibility is a small state machine (it wraps show/hide around a fade `Transition`)

| What | Where |
|---|---|
| `visible()` — **animation-aware** |  — `visible && ((animst == null) \|\| (animst == "show"))` |
| `hide()` does **not** clear `visible` |  — starts `animst = "hide"`; `tick` calls `super.hide()` when the anim ends |
| `reqdestroy()` → `animst = "dest"`; `trans` set on attach |  — `tick` destroys at the end (the 030 fading corpse); `added()` → `initanim()`, and it also `setfocus`es a visible window |
| **The fade is NOT a `Widget.Anim`** | `Window.anim` + `animst` are **private fields of `Window`**, ticked in `Window.tick` and nulled when `Animation.tick` returns true — nothing of it is in `Widget.anims`/`nanims`. `FadeAnim.time` = **0.1 s**. Fork: `Window.animating()` (`// addon:`) — anything asking "is this widget still moving?" must ask BOTH |
| **`Window.draw` is itself a render-to-texture** |  — every frame it makes a `GOut` over `gbasic()` (a `Texture2D` `gbuf` + `FragColor` + `Ortho2D`, rebuilt only when `sz` changes), clears it, draws the window into it, then `drawfin` blits `gbuf` back — through `anim.draw(g, buf)` while fading. So a window drawn inside another render target is a **nested** one, and `gbuf` is a `TexRaw(…, invert=true)` |

**A fading-out window already reads `visible() == false`**, so a menu tick reads one directly, no debounce; and
`RootWidget` sets `focusctl` — why `parent.setfocus(w)` terminates.

## The caption is a public field with ONE post-construction writer

| What | Where |
|---|---|
| `public String cap` |  — set by the ctor from `args[1]` (`create`), so a window built with a caption has it before any child is placed |
| `chcap(String)` — the **only** later write |. Both paths funnel here: the server's `uimsg "cap"` (which maps `""` → `null`) and any client-side title change. No subclass overrides it, and no other class assigns `cap` (`BAttrWnd`'s and `Polity`'s `cap` are unrelated fields) |
| It fires **outside** the `ui` monitor, and nothing else announces it | `uimsg` is applied on a Loader thread and `UI` closes `synchronized(this)` before the addon tap, so anything hung here may only record — no widget read, no tree walk. There is no tree event for a caption; the fork's `// addon:` line at the end of `chcap` is the seam |

`cap` is **not** the `Deco`-rendered `Text` — that is `Deco.cap`, re-rendered by
`checkcap` on `cap.text != wnd.cap` ([ui-chrome.md](ui-chrome.md)).

## The addon seams (031, 035)

All `// addon:` edits sit **inside existing method bodies** — no visibility change, no new call site — and go
through `AddonWidgets`, so `haven` keeps one file that knows the addon layer exists. `togglewnd` asks
`toggleWnd(wnd)` first (`true` = handled), `wndstate` asks `wndState(wnd)` (`null` = not owned); ownership is
029's hide record (`Addon.hiddenNative`) by **widget identity**, which also disposes of the fading corpse from
[widgets.md](widgets.md#core-tree), and that record carries the view, so `wndState` answers `view.visible()`.
035's `chrome(wnd)` is in `Window.tick` ([ui-chrome.md](ui-chrome.md)). The seams on where a window stands
and on `GameUI.resize` are [window-positions.md](window-positions.md#the-forks-seams).
