# GameUI's own windows: wrappers, menu bars, the toggle path and the position store

> Covers the windows **the client itself opens, closes and remembers**. Distinct from [widgets.md](widgets.md) (generic tree + create/place/destroy) and [ui-chrome.md](ui-chrome.md) (`Window.Deco`, `IBox`, the `iresize`/`contarea`/`csz` geometry).
> The **class + method name is the stable anchor**.

## The windows GameUI owns — fields on `GameUI`: `invwnd`, `equwnd`, `makewnd`, `srchwnd`, `iconwnd`, `chrwdg`, `zerg`, `opts`, `mapfile`

| What | Where |
|---|---|
| **`Hidewnd`** — a `Window` whose close **hides** instead of destroying | `GameUI.Hidewnd` — `reqclose() { hide(); }` |
| Inventory / equipment: server grid → a **wrapper created hidden** | `GameUI.addchild` `place == "inv"` — `new Hidewnd(…, "Inventory")`, `add(maininv)`, `pack()`, **`hide()`**; `"equ"` at is the same shape |
| Action search |  `place == "menu"` — `srchwnd`, `reqclose(srchwnd::hide).hide()` |

**The wrappers are hidden from birth** — `maininv` exists from login, the `Hidewnd` around it does not, so *"put
the inventory back"* is **never** a blind `show()`. **TRAP — that wrapper
PACKS AROUND ITS GRID and cannot be resized from outside**: it is anonymous, with
`cresize(ch) { pack(); }`, and `Widget.resize`
notifies `parent.cresize(this)` — so `resize2`'s `deco.iresize` makes the deco call back and the window re-packs
to its content **before your call returns** (`pack()` = `resize(contentsz())`). `equwnd` has none, and resizes.

## The HUD itself: seven `Hidepanel`s, and the plates they blit

| What | Where |
|---|---|
| The panel | `GameUI.Hidepanel` — an id, an `Indir<Coord> base` and a `g` gravity; `add(T)` **`pack()`s and `move()`s on every child added**, so a panel is exactly its contents. `move(double a)` slides it off along `g`, `show(boolean)` is remembered as `<id>-visible` |
| The seven | built in the ctor, in order: `blpanel`, `mapmenupanel`, `brpanel`, `menupanel`, `ulpanel`, `umpanel`, `urpanel`. The two menu panels take an `Indir` base that chases the corner panel beside them |
| The plates blitted **in a widget's own `draw`** | `MainMenu.draw` → `menubg` (`gfx/hud/rbtn-bg`), `MapMenu.draw` → `mapmenubg` (`gfx/hud/lbtn-bg`), `NKeyBelt.draw` → `nkeybg` (`gfx/hud/hb-main`), each a `static final Tex` and each `g.image(…, Coord.z)` before the rest of the draw |
| The plates that are **`Img` children** | `gfx/hud/blframe` in `blpanel` (the minimap's frame), `gfx/hud/csearch-bg` and `gfx/hud/brframe` in `brpanel`. Each is a plain `Img`, so it is a widget in the tree rather than a blit |
| What sits **on** them | `menugridc` is `brframe.c` plus a constant and `menubuttons` places the search button at `rbtnimg.c` — the `Img`'s own box decides, once, at construction. `minimapc` beside them is a bare constant |

**The size of a panel is the size of its plate**: `MainMenu` and `MapMenu` both call `super(<tex>.sz())`,
and `Hidepanel.add` packs around them. Nothing re-measures on a redraw, so a plate painted at another size
is drawn into the box the client's art gave it.

⚠️ **`beltwdg` is not in a panel, and there are two of it.** It is `add`ed to `GameUI` itself and placed by
hand in `resize`/`updfold`; `NKeyBelt` (`super(nkeybg.sz())`) blits that plate, while `FKeyBelt` — the other
half of the Options belt setting — draws **no background at all**, only its twelve squares. Both blit
`Inventory.invsq` for those, the same raster the action grid uses, so neither is an `Inventory` draw.

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

## The client's own position store (`wndc-*`, and every place it is written)

| What | Where |
|---|---|
| The main writer — **at logout AND every 60 s** | `savewndpos`: `wndc-inv/-equ/-chr/-zerg/-map` + `wndsz-map` (`mapfile.csz()`, the CONTENT size). From `dispose()` **and** from `tick` on a `lastwndsave` clock |
| Two more writers, same shape | `cdestroy` → `wndc-misc/<wndid>` for any server window carrying an `"id"` opt ( reads it back); the crafting window's own `destroy()` → `makewndc` |
| The reads + the clamp | `Utils.getprefc(key, default)` **at construction** — //// — most through the `private` `fitwdg`, which clamps `wdg.c` so ≥ `UI.scale(100)` px stays inside `GameUI.sz` (cheaper re-derived — `UiApi.fitView` — than widened) |

**This store belongs to what the USER placed** — the one thing an addon cannot undo in memory, since a `setprefc` outlives it. The writes are *unconditional*: no dirty flag, no "only if it changed".

## `GameUI.resize` re-places four of its children, unconditionally, on every screen resize

| What | Where |
|---|---|
| The four it writes | `GameUI.resize(Coord)` — `chat.resize(w)` + `chat.move(bottom-left)`, `map.resize(sz)`, `prog.move(centred)`, and `beltwdg.c` **assigned directly**. No condition, no memory of where anything was: whatever placed them before is gone |
| ⚠️ **`ChatUI` overrides `move(Coord)`** | `ChatUI.move` — `this.c = (this.base = base).add(0, visible ? -sz.y : 0)`. So the argument is the chat's **base** (its bottom edge) and `c` is derived from it: `move(c)` is **not** the identity there, and anything that moves the chat by writing `c` and reads it back gets a different pair while the chat is expanded |
| The fork's seam | `AddonWidgets.relayout(this)` (`// addon:`), the **last** line of `resize` — so a place the addon layer holds goes back on top of what the four writes above just did, rather than under it |

`Widget.move` itself is **not** hooked anywhere, and deliberately: it is on every drag of every window in the
client. This one method is the whole of the screen-resize case.

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

## The addon seams (031, 035, 036, 062)

All `// addon:` edits sit **inside existing method bodies** — no visibility change, no new call site — and go
through `AddonWidgets`, so `haven` keeps one file that knows the addon
layer exists. `togglewnd` asks `toggleWnd(wnd)` first (`true` = handled), `wndstate` asks `wndState(wnd)` (`null` =
not owned); ownership is 029's hide record (`Addon.hiddenNative`) by **widget identity**, which also disposes of
the fading corpse from [widgets.md](widgets.md#core-tree), and that record carries the view, so
`wndState` answers `view.visible()`. 035's `chrome(wnd)` is in `Window.tick`
([ui-chrome.md](ui-chrome.md)). **is a SUBSTITUTION, not a call**: every `Utils.setprefc(key, w.c)` in the
three writers above reads `AddonWidgets.stockc(w)` instead (`stockcsz` for `wndsz-map`) — the widget's own value
unless an addon's layout stands on it. It substitutes rather than restores because `savewndpos` also runs on that
60 s tick — putting the widgets back around the write would snap a laid-out HUD once a minute. 062's
`relayout(this)` is the fourth, and the only one that is a plain **call** rather than a question or a
substitution: it goes last in `resize` so it overwrites, and it is idempotent so a `resize` it makes
cannot come back round through the same line.
