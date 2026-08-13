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
| Packing one | `Widget.pack()` is `resize(contentsz())`, and `Window.contentsz()` is the same max-child-bottom-right as the base one **with the `deco` skipped** — so packing a window measures its children, feeds that to `resize` as the CONTENT size, and the outer `sz` is re-derived by `iresize`. The deco's own `c` is negative (`contarea().ul.inv()`), which is why it cannot be one of the children measured |
| The corner sizer — the client's ONE user-driven resize | `DefaultDeco.dragsize` (off by default; `MapWnd` alone turns it on) + `szdrag`/`szdragc`. `mousedown` hit-tests a **triangle** at `ca.br` (`c.y >= ca.br.y - UI.scale(25) + (ca.br.x - c.x)`), takes `ui.grabmouse(this)` and stores `szdragc = aa.sz().sub(c)`; `mousemove` is then `((Window)parent).resize(ev.c.add(szdragc))` — the pointer plus a constant, so the window's own `c` is never touched and **a resize moves no origin**. `drawframe` paints `sizer` at `ca.br.sub(sizer.sz())` |

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

| Who draws one | Where |
|---|---|
| The window chrome | `Window.wbox` — used by `DefaultDeco.drawframe` and by everything below (they share the object) |
| **`Frame`** — by far the widest reach | `Frame` — `public final IBox box`, `drawframe`. Built by `SAttrWnd`, `BAttrWnd`, `SkillWnd`, `QuestWnd`, `WoundWnd`, `FightWnd`, `BuddyWnd`, `MapWnd`, `Charlist`, `GobIcon`, `Fightview`, and `GameUI`'s portrait |
| Its subclasses | `ProxyFrame` and `Partyview.MemberView` **override `drawframe`** (`g.chcolor(tint)` + `box.draw`); `MapWnd.ViewFrame` overrides `draw` but calls `super.draw` |
| The rest | `FlowerMenu.Petal.draw` (`pbox`) · `SListMenu.draw` (`obox`, dropdowns) · `ISBox.draw` (its box also fills `bgcol`) · `DynresWindow.Image.draw` · `Speaking.draw` (`sb` — a `GAttrib` in the 3D view, **not a widget**) · `GItem.HoverDeco` (a `Window.Deco`, not a panel) |

**Two traps, both cost time.**

1. **`Frame.around` does NOT put the content inside the frame.**
   `around` does `parent.add(new Frame(...))` — the framed widgets stay children of
   `parent` and the frame is appended **after** them, so it draws **on top** of what it appears to contain.
   `with` is the opposite (a real child); `addin` is a
   third shape (resizes the child, adds it to `parent`). A stock `IBox` never noticed — its middle is
   transparent — but anything that *fills* the interior buries the rows on an `around` frame. Both spellings
   are in constant use, often in one window. **Never assume a `Frame`'s visual children are its tree children.**
2. **The six measuring methods are read at CONSTRUCTION**, not per frame: `Frame`'s ctor (`sz.add(box.bisz())`),
   `getpos`, `xlate`, `checkhit`, `addin`, and `SListMenu`'s ctor + `tick` all consume them once. So a box
   swapped in later may paint differently but must **measure identically**, or the frame moves and its contents
   do not. Exact mirror of `Deco`, where `iresize`/`contarea` *are* re-run and a replacement therefore
   can change the geometry.

## The addon seam (035)

`Window.tick` calls `AddonWidgets.chrome(wnd)`  — install/drop the sheet-fed
deco; in `tick` because `chdeco` destroys a widget and re-lays out. Its only other `haven` edit is an
**extraction**: `DefaultDeco.drawframe`'s caption block became `protected checkcap()`
 so a replacement deco renders the caption through F3a's routed furnace
instead of duplicating it. **added no core edit at all** (it overrides `iresize` in the addon's own
subclass; `chdeco`/`resize`/`tlm`/`dsmrgn`/`c` are already public).
routes the panels the same way fonts are routed — `IBox box = Fonts.box("panel", this, stock)` plus
`Fonts.drawbg(box, g, tl, sz)`, one `// addon:` line each, both returning the **stock** box / `false` when no
rule applies, so an addon-less client is byte-for-byte unchanged.
