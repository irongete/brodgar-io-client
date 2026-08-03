# Subsystem: the chrome — `Window.Deco` and `IBox`

> The two things that draw a **frame** in this client, split out of
> [gameui-windows.md](gameui-windows.md) when 035.3 pushed it past its line budget. Lines are indicative; the
> **class + method/field name is the stable anchor**. Max 70 lines.

## `Window.Deco` — a window's chrome is a swappable CHILD

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
(`Window.c` is public); skipping the last one drifts every window by the change.
`Window.draw` renders children into `gbuf` and blits, clearing it to `FColor.BLACK_T` — so anything the chrome
does not paint is transparent black, not a background (D-079).

## `IBox` — the 9-slice, and the window-LESS panels that draw one

[`IBox`](src/haven/IBox.java:29) is an **interface**: `draw(g, tl, sz)` plus six measuring methods —
`btloff`/`ctloff` (top-left border / corner offsets), `bbroff`/`cbroff`, `bisz` (total border size),
`cisz` (total corner size). [`IBox.Images`](src/haven/IBox.java:38) holds the eight textures and derives all six
from them; [`IBox.Scaled`](src/haven/IBox.java:89) is the only `draw` — corners at their own size, edges
stretched between them, **centre never painted**.

| Who draws one | Where |
|---|---|
| The window chrome | [`Window.wbox`](src/haven/Window.java:63) — used by `DefaultDeco.drawframe` and by everything below (they share the object) |
| **`Frame`** — by far the widest reach | [`Frame`](src/haven/Frame.java:31) — `public final IBox box`, [`drawframe`](src/haven/Frame.java:113). Built by `SAttrWnd`, `BAttrWnd`, `SkillWnd`, `QuestWnd`, `WoundWnd`, `FightWnd`, `BuddyWnd`, `MapWnd`, `Charlist`, `GobIcon`, `Fightview`, and `GameUI`'s portrait ([:304](src/haven/GameUI.java:304)) |
| Its subclasses | [`ProxyFrame`](src/haven/ProxyFrame.java:43) and [`Partyview.MemberView`](src/haven/Partyview.java:82) **override `drawframe`** (`g.chcolor(tint)` + `box.draw`); [`MapWnd.ViewFrame`](src/haven/MapWnd.java:156) overrides `draw` but calls `super.draw` |
| The rest | [`FlowerMenu.Petal.draw`](src/haven/FlowerMenu.java:109) (`pbox`) · [`SListMenu.draw`](src/haven/SListMenu.java:119) (`obox`, dropdowns) · [`ISBox.draw`](src/haven/ISBox.java:72) (its box also fills `bgcol`) · [`DynresWindow.Image.draw`](src/haven/DynresWindow.java:375) · [`Speaking.draw`](src/haven/Speaking.java:81) (`sb` — a `GAttrib` in the 3D view, **not a widget**) · [`GItem.HoverDeco`](src/haven/GItem.java:352) (a `Window.Deco`, not a panel) |

**Two traps, both cost time in 035.3.**

1. **`Frame.around` does NOT put the content inside the frame.**
   [`around`](src/haven/Frame.java:43) does `parent.add(new Frame(...))` — the framed widgets stay children of
   `parent` and the frame is appended **after** them, so it draws **on top** of what it appears to contain.
   [`with`](src/haven/Frame.java:69) is the opposite (a real child); [`addin`](src/haven/Frame.java:126) is a
   third shape (resizes the child, adds it to `parent`). A stock `IBox` never noticed — its middle is
   transparent — but anything that *fills* the interior buries the rows on an `around` frame. Both spellings
   are in constant use, often in one window. **Never assume a `Frame`'s visual children are its tree children.**
2. **The six measuring methods are read at CONSTRUCTION**, not per frame: `Frame`'s ctor (`sz.add(box.bisz())`),
   `getpos`, `xlate`, `checkhit`, `addin`, and `SListMenu`'s ctor + `tick` all consume them once. So a box
   swapped in later may paint differently but must **measure identically**, or the frame moves and its contents
   do not (D-084). Exact mirror of `Deco`, where `iresize`/`contarea` *are* re-run and a replacement therefore
   can change the geometry (D-080).

## The addon seam (035)

`Window.tick` calls `AddonWidgets.chrome(wnd)` ([:525](src/haven/Window.java:525)) — install/drop the sheet-fed
deco; in `tick` because `chdeco` destroys a widget and re-lays out. Its only other `haven` edit is an
**extraction**: `DefaultDeco.drawframe`'s caption block became `protected checkcap()`
([:253](src/haven/Window.java:253)) so a replacement deco renders the caption through F3a's routed furnace
instead of duplicating it. **035.2 added no core edit at all** (it overrides `iresize` in the addon's own
subclass; `chdeco`/`resize`/`tlm`/`dsmrgn`/`c` are already public).
035.3 routes the panels the same way fonts are routed — `IBox box = Fonts.box("panel", this, stock)` plus
`Fonts.drawbg(box, g, tl, sz)`, one `// addon:` line each, both returning the **stock** box / `false` when no
rule applies, so an addon-less client is byte-for-byte unchanged.
