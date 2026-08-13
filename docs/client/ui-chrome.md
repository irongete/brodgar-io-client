# Chrome: `Window.Deco` and `IBox`

> The two things that draw a **frame** in this client, split out of
> [gameui-windows.md](gameui-windows.md) when it outgrew that page.

## `Window.Deco` — a window's chrome is a swappable CHILD

| What | Where |
|---|---|
| The field + the live swap | `Window.deco` · `chdeco(Deco)` — public; `uimsg "dhide"`  already drives it |
| The contract, and the stock one | `Window.Deco` — `z(-100)`, `abstract iresize(Coord)` + `contarea()`; `DragDeco` adds the caption drag; `DefaultDeco` adds `drawbg`/`drawframe`, the close `IButton`, the sizer, `checkhit` and the margins (`dlmrgn`/`dsmrgn` + `tlm`/`brm`) |
| Who builds their own | `Window.makedeco()` (**`protected`**) · `MapWnd` `DefaultDeco(true).dragsize(true)`, and `compact()` sets it **null** · `GItem.ContentsWindow` swaps `HoverDeco`/`DefaultDeco` per state |
| Geometry flows one way | `resize2` — `deco.iresize(sz)`, `deco.c = contarea().ul.inv()`, `this.sz = deco.sz`. **The ctor's `sz` is the CONTENT size**; `ca()`/`csz()`/`xlate` all read `contarea()` |
| The stock layout formula | `DefaultDeco.iresize` — `content + mrgn*2 + tlm + brm`: an inner **margin** (`dlmrgn` 23x14 / `dsmrgn` 9x9, all `UI.scale`d) and outer **frame insets** (`tlm` 18x30, `brm` 13x22). `ca` = the bg box, `aa` = `contarea()`, `cbtn` pinned to the top right |
| What the close button actually does | `DefaultDeco.cbtn` is a plain `IButton` whose `action` is `((Window)parent).reqclose()` — so the X runs the ordinary button path (`mouseup` → `click()` → `action`), and `Window.reqclose()` runs a **swappable `Runnable` field**, `wdgmsg("close")` by default and `Window.reqclose(Runnable)` to replace it. A client-side window overrides it to destroy itself; nothing about the X is special-cased in `Window` |
| **The origin a CHILD of a window sees** | `Window.xlate` adds `deco.contarea().ul` on the way in and subtracts it on the way out, so a child's `(0,0)` is the **content area's** top-left — `tlm + mrgn`, i.e. `UI.scale(27, 39)` with the small deco — while an event dispatched to the `Window` itself carries the OUTER coordinate. Both `PointerEvent.propagation` and the draw loop go through `xlate`, so a child is drawn and hit-tested in the same space; the gap is between the window and its child, never within one |
| Packing one | `Widget.pack()` is `resize(contentsz())`, and `Window.contentsz()` is the same max-child-bottom-right as the base one **with the `deco` skipped** — so packing a window measures its children, feeds that to `resize` as the CONTENT size, and the outer `sz` is re-derived by `iresize`. The deco's own `c` is negative (`contarea().ul.inv()`), which is why it cannot be one of the children measured |
| The corner sizer — the client's ONE user-driven resize | `DefaultDeco.dragsize` (off by default; `MapWnd` alone turns it on) + `szdrag`/`szdragc`. `mousedown` hit-tests a **triangle** at `ca.br` (`c.y >= ca.br.y - UI.scale(25) + (ca.br.x - c.x)`), takes `ui.grabmouse(this)` and stores `szdragc = aa.sz().sub(c)`; `mousemove` is then `((Window)parent).resize(ev.c.add(szdragc))` — the pointer plus a constant, so the window's own `c` is never touched and **a resize moves no origin**. `drawframe` paints `sizer` at `ca.br.sub(sizer.sz())` |

**A window has TWO boxes and `Widget.sz` is the outer one.** `resize2` ends with `this.sz = deco.sz`, so a
`Window`'s own `sz` is the whole decorated box while `csz()` (`= ca().sz() = deco.contarea().sz()`) is the
content — and `resize`/the constructor take the *content* size. So on a window, and on a window alone, the
size you write is not the field you read; measuring the frame means differencing the deco's box against what
it frames, never against `Window.sz`.

**`chdeco` destroys what it displaces** (`reqdestroy()`), so a swap can never put the *same* object back; it reads
the old `contarea()` first, re-runs the layout, and folds the difference into the **window's own `c`** — so a
swap anchors the *content*, not the window's corner, and an equal-geometry swap leaves `sz`, `c` and the content
area untouched. A deco that re-lays itself out **while installed** must copy those three steps
(`Window.c` is public); skipping the last one drifts every window by the change.
`Window.draw` renders children into `gbuf` and blits, clearing it to `FColor.BLACK_T` — so anything the chrome
does not paint is transparent black, not a background.

## `IBox` — the 9-slice, and the window-LESS panels that draw one

`IBox` is an **interface**: `draw(g, tl, sz)` plus six measuring methods —
`btloff`/`ctloff` (top-left border / corner offsets), `bbroff`/`cbroff`, `bisz` (total border size),
`cisz` (total corner size). `IBox.Images` holds the eight textures and derives all six
from them; `IBox.Scaled` is the only `draw` — corners at their own size, edges
stretched between them, **centre never painted**.

**The eight pieces are named by suffix, and the client spells the edges two ways.**
`IBox.Images(String base, ctl, ctr, cbl, cbr, bl, br, bt, bb)` is `Resource.loadtex(base + "/" + suffix)`
eight times, and every call site passes the corners as `tl`/`tr`/`bl`/`br`. The edges are
`el`/`er`/`et`/`eb` in `ISBox.box` (`gfx/hud/bosq`) and `Speaking.sb` (`gfx/hud/emote`), but
`extvl`/`extvr`/`extht`/`exthb` in `Window.wbox` (`gfx/hud/wnd`) — the folders carry one set or the other,
not both, so anything resolving a box from a folder name has to try each. `loadtex` is
`loadrimg(name).tex()`, i.e. `Resource.Image.scaled()`, so all eight arrive **device-sized**; see
[ui-scaling.md](ui-scaling.md).

| Who draws one | Where |
|---|---|
| The window chrome | `Window.wbox` — used by everything below (they share the object). **Not** by `DefaultDeco.drawframe`, which draws its own art — see below |
| **`Frame`** — by far the widest reach | `Frame` — `public final IBox box`, `drawframe`. Built by `SAttrWnd`, `BAttrWnd`, `SkillWnd`, `QuestWnd`, `WoundWnd`, `FightWnd`, `BuddyWnd`, `MapWnd`, `Charlist`, `GobIcon`, `Fightview`, and `GameUI`'s portrait |
| Its subclasses | `ProxyFrame` and `Partyview.MemberView` **override `drawframe`** (`g.chcolor(tint)` + `box.draw`); `MapWnd.ViewFrame` overrides `draw` but calls `super.draw` |
| The rest | `FlowerMenu.Petal.draw` (`pbox`) · `SListMenu.draw` (`obox`, dropdowns) · `ISBox.draw` (its box also fills `bgcol`) · `DynresWindow.Image.draw` · `Speaking.draw` (`sb` — a `GAttrib` in the 3D view, **not a widget**) · `GItem.HoverDeco` (a `Window.Deco`, not a panel) |

**`Window.wbox` measures smaller than it paints.** It is an anonymous subclass that subtracts `UI.scale(3, 3)`
from `ctloff`/`cbroff` and `UI.scale(2, 2)` from `btloff`/`bbroff` (and twice that from `bisz`/`cisz`), so the
insets every `Frame` reserves are a few pixels inside its own corner art. A box built plainly from the same
eight textures measures its corners at their full size and therefore reserves more room.

## The stock window decoration is NOT an `IBox`

`DefaultDeco` paints `gfx/hud/wnd/lg/*` itself, and everything that separates it from a 9-slice is worth
knowing before deciding a frame is one.

| What | Where |
|---|---|
| The runs **repeat**, they do not stretch | `DefaultDeco.drawframe` — every edge is a `for` loop of `g.image(tex, mdo, Coord.z, cbr)`, one blit per tile with the run's own clip rectangle. `IBox.Scaled.draw` is the same geometry with `g.image(tex, c, sz)` instead, i.e. one scaled blit. The clip is what keeps the last tile from overrunning a corner |
| Its background is **three layers** | `DefaultDeco.drawbg` — `bg` tiled over `ca` under `bgblend`, then `bgl` down the left edge and `bgr` down the right, each tiled vertically and clipped to `ca` |
| A piece pinned at the end of a run | `lb`, dropped at the **foot** of the left run before `lm` tiles the rest — the only such piece in the frame |
| The caption plate is part of the frame art | `cl` / `cm` / `cr`, with `cm` tiled across `cmw`; `checkcap` computes `cmw` from `max(caption width, sz.x / 4)` and then **shortens it** by `cl.sz().x - cpo.x + UI.scale(5)`, so the run is `cl + cmw + cr` wide from `x = 0` |
| One set of art, two margins | The statics are all `gfx/hud/wnd/lg/*` and `drawframe` uses them unconditionally; `DefaultDeco.lg` picks only between `dlmrgn` and `dsmrgn` in `iresize` |

**Three traps, all cost time.**

1. **`cptl`/`cpsz` is the caption's DRAG box, not the caption art's rectangle.** `checkhit` uses the pair to
   test a click against `cm`'s own alpha, so it starts at `ca.ul.x` — one frame inset in from `x = 0`, where
   `cl` actually begins — and ends at `cpo.x + cmw` *before* `cmw` is shortened, which stops short of `cr`'s
   right edge. Painting anything that stands in for the title-bar art at that pair leaves a bare notch at
   each end of the run. The art's own box is `(0, 0)` to `cl.sz().x + cmw + cr.sz().x`, by the tallest of the
   three, which is what `plsz` holds.
2. **`Frame.around` does NOT put the content inside the frame.**
   `around` does `parent.add(new Frame(...))` — the framed widgets stay children of
   `parent` and the frame is appended **after** them, so it draws **on top** of what it appears to contain.
   `with` is the opposite (a real child); `addin` is a
   third shape (resizes the child, adds it to `parent`). A stock `IBox` never noticed — its middle is
   transparent — but anything that *fills* the interior buries the rows on an `around` frame. Both spellings
   are in constant use, often in one window. **Never assume a `Frame`'s visual children are its tree children.**
3. **The six measuring methods are read at CONSTRUCTION**, not per frame: `Frame`'s ctor (`sz.add(box.bisz())`),
   `getpos`, `xlate`, `checkhit`, `addin`, and `SListMenu`'s ctor + `tick` all consume them once. So a box
   swapped in later may paint differently but must **measure identically**, or the frame moves and its contents
   do not. Exact mirror of `Deco`, where `iresize`/`contarea` *are* re-run and a replacement therefore
   can change the geometry.

## Boxes the client draws in CODE, not from a resource

A few framed surfaces are neither a `Deco` nor an `IBox`: the client computes a rectangle and fills it with
literal colours. Nothing about them is swappable, so anything standing in for one has to reproduce the
geometry as well as the paint.

| What | Where |
|---|---|
| **The tooltip's box** | `UILoop.drawtooltip` — `m = UI.scale(2, 2)`, then `g.rect2(pos - m - (1,1), br + m)` in `(244, 247, 21, 192)` **first** and `g.frect2(pos - m, br + m)` in `(35, 35, 35, 192)` over it, so the outline reads as a 1 px ring outside the fill. `pos` is the tip texture's own top-left, `br = pos + tex.sz()` |
| **The inventory square** | `Inventory.invsq`, a `TexI` over a `WritableRaster` filled in the class's **static initialiser**: a 1 px ring of `(20, 28, 21, 167)` around a field of `(36, 52, 38, 125)` |

**Three things about that one.** `pos` is clamped to `>= 0` but the box is not, so a tip at the left or top
edge already paints its outline off-screen — widening the box widens that overhang rather than pushing the
tip inward. The rendered tip is cached on the tooltip **object** (`Utils.eq(tooltip, prevtooltip)`, dropped
when `Fonts.gen()` moves), and the box is painted **outside** that cache, every frame — so a box that reads a
style needs no invalidation at all. And `drawtooltip` runs from `UILoop.display` after `ui.draw(g)`, i.e.
outside every widget's draw, so there is no widget in hand and no per-widget style frame in force.

**And two about the square.** It is drawn **one pixel larger than the grid's pitch** (`sqsz` is
`UI.scale(32,32) + (1,1)`, the raster `sqsz + (1,1)`), so adjacent cells overlap and share one ring — place
at `c.mul(sqsz)`, size at `invsq.sz()`, never at `sqsz`. And it is blitted from **six** classes, only two of
them an inventory: `Inventory.draw` and `Equipory.drawslots`, against `GameUI`'s two belt widgets,
`MenuGrid.bg`, `Makewindow.SpecWidget.drawbg` and `FightWnd`'s manoeuvre row, which take the field for its
pixels rather than for what it means. A `static final` from a static initialiser is never *replaced* — the
JVM does not re-run one — so a site that wants another square asks, and falls back to this.

## The addon seam

Every `// addon:` edit in the chrome; everything else the sheet needs was already public.

| Edit | Where |
|---|---|
| Install or drop the sheet-fed deco | `Window.tick` calls `AddonWidgets.chrome(wnd)` — in `tick` because `chdeco` destroys a widget and re-lays the window out |
| An **extraction**, so a replacement deco renders the caption through the same routed furnace instead of duplicating it | `DefaultDeco.drawframe`'s caption block became `protected checkcap()` |
| The panels, routed the way the fonts are | `IBox box = Fonts.box("panel", this, stock)` plus `Fonts.drawbg(box, g, tl, sz)` at each site, both handing back the **stock** box / `false` when no rule applies |
| Where the two placed ornaments go, one method each | `DefaultDeco.capc()` (stock `cpo`) and `sizerc()` (stock `ca.br - sizer.sz()`), both `public` and both read by `drawframe`; `drawsizer(GOut)` wraps the blit so a subclass can supply its own art. A replacement deco overrides them rather than copying `drawframe` |
| The close button, buildable and swappable | `DefaultDeco.mkcbtn()` is the constructor's own line — the stock `IButton` over `Window.cbtni` with `((Window)parent).reqclose()` on it — so anything replacing the button can build the client's own back, action included. `chcbtn(IButton)` swaps it and `reqdestroy()`s what it displaces, `chdeco`'s discipline one level down; `cbtn` is not `final`, because an `IButton`'s three faces are and a face change is therefore a whole new button |
| The inventory square, one lookup for a whole grid | `Inventory.draw` and `Equipory.drawslots` open with `Fonts.chrome("inventory.slot", this)` and blit `invsq` when it answers `null`. Resolved **outside** the loop: a grid is one box per cell, and asking per cell would take the registry lock per cell per frame |
| The caption plate, at the box the client sizes | `DefaultDeco.drawplate(GOut)` → `Fonts.drawchrome("window.title", wnd, g, Coord.z, plsz)`, and the stock `cl`/`cm`/`cr` run is skipped when it answers `true`. `plsz` is computed beside `cmw` in `checkcap`; the answer is kept in `DefaultDeco.platestyled` for the read-back |

`Fonts.Chromes` is the third source interface beside `Fonts.Boxes` and `Fonts.TreeStyles`, and it is the
shape for **a box the site sizes itself**: no `IBox` to stand in for, so the site hands over a rectangle and
asks whether a rule painted it. It answers three ways for the three moments a site needs it —
`drawchrome(scope, wdg, g, ul, sz)` resolves and paints in one breath, `chromepad(scope, wdg)` gives the
room a padding asks for *before* there is a rectangle, and `chrome(scope, wdg)` hands the paint back as a
`Fonts.Chrome` for a site that draws the same box many times. All three are behind the same `active`
volatile read, so a client with no addon runs the same code it always did.

**Replacing the geometry costs no core edit**: a deco subclass overrides `iresize` itself, and `chdeco`,
`resize`, `tlm`, `dlmrgn`/`dsmrgn` and `Window.c` are already public. So a client with no addon runs the same
code it always did, byte for byte.
