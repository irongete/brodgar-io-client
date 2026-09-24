# Where GameUI's windows stand: the position store and the screen resize

> Covers **where the client puts its own windows**: the `wndc-*` preference keys, every place one is read
> and written, the clamp, how the screen's size arrives and what `GameUI.resize` re-places. Which windows
> `GameUI` owns and how they open and close is [gameui-windows.md](gameui-windows.md). The **class + method
> name is the stable anchor**.

## The keys and their windows

| Key | Window | Read | Written |
|---|---|---|---|
| `wndc-inv` | `invwnd`, the `Hidewnd` around `maininv` | `GameUI.addchild`, `place == "inv"` | the drop |
| `wndc-equ` | `equwnd` | `place == "equ"` | the drop |
| `wndc-chr` | `chrwdg` (`CharWnd`) | `place == "chr"` | the drop |
| `wndc-zerg` | `zerg` (kin) | the `GameUI` constructor | the drop |
| `wndc-map` + `wndsz-map` | `mapfile` (`MapWnd`) | `place == "mapview"`; the size is the CONTENT size, `csz()` | the drop; the box when a grip is let go |
| `wndc-srch` | `srchwnd` (`MenuSearch.Main`) | `place == "menu"` | the drop |
| `wndc-icon` | `iconwnd` (`GobIcon.SettingsWindow`) | `MapMenu`'s icon button, on each open | the drop |
| `makewndc` | the crafting window's anonymous wrapper | `place == "craft"`, on each opening | the drop |
| `wndc-misc/<id>` | a server window added with an `"id"` opt | `place == "misc"`, the `"id"` case | the drop |
| `cont-wndc/<id>` + `cont-wndvis/<id>` | `GItem.ContentsWindow`, an item's contents | its constructor and `wndshow` | the drop; `cont-wndvis` by `chstate` |

`Utils.getprefc` reads a `wndc-*` value as `NxM`: a value with no `x` gives the default, and one that
does not parse **throws `NumberFormatException`** — it catches only `SecurityException`. So a value in any
other shape must carry no `x`, or an older build reading it fails instead of defaulting. `Utils.setprefc`
writes `null` as the empty string, which reads back as the default.

## The one write: the drop

| What | Where |
|---|---|
| A place is written **when the user puts the window down**, and at no other moment | `Window.drag` notes where the drag started (`// addon:`). `Window.mouseup`, dropping the grab, calls the fork's `WndPos.dropped` when `c` moved; a press on the title bar that moved nothing writes nothing. `dropped` writes the key the window was loaded under (`WndPos.load`, or `WndPos.key` for a `ContentsWindow`, which has an id before it has a place) |
| The map's box | `Window.resizedByHand(true)`, from the frame's grip on release and from `MapWnd.ViewFrame.mouseup` for the compact one (`// addon:`), writes the place, whose free space the new box changed. `GameUI`'s anonymous `MapWnd` subclass writes `wndsz-map` beside it |
| ⚠️ **The upstream writers are cut** | Upstream writes on a 60 s clock in `GameUI.tick` (`savewndpos`), in `cdestroy` for `wndc-misc/<id>`, in the crafting wrapper's `destroy()` for `makewndc`, and in `ContentsWindow.tick` on every change of `c`. This client removes all four (`// addon:`): a stored fraction does not change with the screen, so nothing has to catch up on a clock, a character switch or the client closing, and a session nobody touches writes nothing. A merge that brings one back must cut it again |

**The store belongs to what the USER placed**: only the user's drop writes it, and a place an addon's
layout holds is never written (`AddonManager.posHeld`).

## The reads, the default placement and the clamp

| What | Where |
|---|---|
| Read **once, at construction or `addchild`** | Each window is added at its stored `c` and never re-read in the session. `makewndc` is read each time a crafting window opens |
| ⚠️ **`zerg` is placed before `GameUI` has a size** | The constructor adds it; `GameUI()` is built at `Coord.z`, and the first real size arrives in `added()` → `resize(parent.sz)`. Anything that needs the parent's size to place a window must wait for that call |
| ⚠️ **`ContentsWindow` reads its place before it has a parent** | The constructor sets `c` from `cont-wndc/<id>` when `cont-wndvis/<id>` is true and calls `chstate("wnd")`; otherwise the window starts `"hide"`. `wndshow(true)` reads the key again, falling back to the pointer (`cont.rootxlate(ui.mc)`) |
| ⚠️ **`ContentsWindow` has three states** | `"hide"`, `"hover"` (a `HoverDeco` beside the hovered item, `move`d there on every `ckhover`) and `"wnd"` (pinned, `DefaultDeco`). Only `"wnd"` is a place the user chose. It hangs under `GameUI`, or `ui.root` when there is none (`GItem.contparent`) |
| The server's own rule | `place == "misc"` with a `Coord2d` argument: `c = a.mul(sz − child.sz).round()`, then `optplacement`. A fraction of the free space per axis — 0 the left or top edge, 1 the right or bottom |
| The clamp | `GameUI.fitwdg(wdg, c)` keeps `UI.scale(100)` px of the window (or the whole of a smaller one) inside `GameUI.sz`. Called for `srchwnd`, `makewnd`, `iconwnd` and the first window under a `misc` id, and by `togglewnd` on every open |
| A second window under a known `misc` id | goes through `optplacement` instead of the clamp |

## `GameUI.resize` re-places four of its children, unconditionally, on every screen resize

| What | Where |
|---|---|
| The four it writes | `GameUI.resize(Coord)` — `chat.resize(w)` + `chat.move(bottom-left)`, `map.resize(sz)`, `prog.move(centred)`, and `beltwdg.c` **assigned directly**. No condition, no memory of where anything was: whatever placed them before is gone. Each is guarded by `mine(w)` (`// addon:`), so a widget taken out of the HUD is left alone |
| ⚠️ **`ChatUI` overrides `move(Coord)`** | `ChatUI.move` — `this.c = (this.base = base).add(0, visible ? -sz.y : 0)`. So the argument is the chat's **base** (its bottom edge) and `c` is derived from it: `move(c)` is **not** the identity there, and anything that moves the chat by writing `c` and reads it back gets a different pair while the chat is expanded |
| ⚠️ **`ChatUI.resize(int)` floors its width** | `Math.max(w, selw + …)`, so a HUD narrower than both corner panels (`blpw` + `brpw`, `UI.scale(142)` each) still gives the chat a positive box |
| Windows | **None upstream.** A window keeps its pixels through a screen resize, so one left at the bottom or right edge ends up inside the screen, or off it |
| How the screen's size arrives | `UILoop.Frame.tick` resizes each root to the frame's `sz` when it differs: the addon layer's `layer.root` under that tree's monitor, then the session's `ui.root` under its own. `Widget.resize` calls `presize()` on every child, and `GameUI.presize` is `resize(parent.sz)`: that is how the HUD hears it. `Window` has no `presize`, so a window directly on a root is never told |

`Widget.move` itself is **not** hooked anywhere, and deliberately: it is on every drag of every window in
the client. `Window` moves `c` from `mousemove` while its own grab (`dm`) stands, with no clamp; `mouseup`
drops the grab and is the one place the end of a drag is known.

## The fork's seams

Every read and write in the tables above is an `// addon:` line that routes the place through the fork's
window-position rule, which keeps a place as **where the window's centre stands, as a fraction of the
screen** per axis; a window touching an edge, or dropped partly past it, is stored glued to that edge
(`0` or `1`, values a centre can never take), and a window that fits is always placed whole:

- The keys carry `cfx/fy`, each number written by `Double.toString`: locale-free, with no `x`, so a
  pre-fork `Utils.getprefc` reads it as its default, and the leading `c` makes an earlier fork build's
  parser fail inside its own catch and default too. Two older values still load: `fx/fy` with no `c`, a
  fraction of the free space (the server's `Coord2d` rule above), placed as it was written and held as a
  centre from then on; and `NxM`, pixels. `wndsz-map` stays pixels.
- Every key above is written by the drop, `wndc-srch` and `wndc-icon` included, which upstream never
  writes.
- `zerg` waits for the first `resize`. `ContentsWindow` resolves its place in `added()` and `wndshow`,
  and `ContentsWindow.pinned()` (`// addon:`) is true in `"wnd"`.
- `GameUI.resize` keeps the old size and re-places every top-level `Window` with its centre at its fraction
  of the new size, skipping an unpinned `ContentsWindow`, **before** `AddonWidgets.relayout(this)`. That call goes
  last so a place the addon layer holds overwrites the client's placement, and it is idempotent so a
  `resize` it makes cannot come back round through the same line.
- `UILoop.Frame.tick` keeps `layer.root`'s old size and runs the same re-placement over it right after
  `layer.root.resize(sz)`. `ui.root` needs none: the HUD under it re-places its own windows.
- `AddonWidgets.stockcsz` substitutes the map's box when an addon has resized it, so `wndsz-map` records
  the user's box. It substitutes rather than restores: the write runs with the addon's level standing, and
  moving the window back around it would snap a laid-out HUD.
