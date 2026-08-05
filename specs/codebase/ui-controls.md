# Subsystem: the client's own CONTROLS

> The catalogue of interactive widgets `haven` already ships — the classes an addon builds through
> `hafen.ui()`'s control builders. Lines are indicative; the **class + method/field name is the stable
> anchor**. The tree itself is [widgets.md](widgets.md); what draws a window *frame* is
> [ui-chrome.md](ui-chrome.md). Max 70 lines.

## The image-backed base: `SIWidget`

`Button` and `IButton` extend [`SIWidget`](src/haven/SIWidget.java:31), which is one idea: **rasterise once,
blit thereafter**. `CheckBox`/`ICheckBox` do **not** — see below.

| What | Where |
|---|---|
| The cache | [`SIWidget.surf`](src/haven/SIWidget.java:32) — a `Tex`, built lazily in [`draw(GOut)`](src/haven/SIWidget.java:47) from the subclass's [`draw(BufferedImage)`](src/haven/SIWidget.java:39) |
| The only invalidation | [`redraw()`](src/haven/SIWidget.java:54) — disposes `surf` and nulls it; [`dispose()`](src/haven/SIWidget.java:59) is teardown |

> **`Widget.resize(Coord)` does NOT call `redraw()`.** [`Widget.resize`](src/haven/Widget.java:1534) sets
> `sz`, presizes the children and tells the parent — nothing more. So resizing an `SIWidget` moves its box
> and keeps its old picture, at the old dimensions, until something else happens to invalidate it. Anything
> that sizes one of these controls has to `redraw()` itself; the symptom reads as a layout bug.

## `Button`

[`Button`](src/haven/Button.java:34) — the text push button. Height is **fixed by its images**:
`hs`/`hl` ([:42](src/haven/Button.java:42)), short or "large", never the caller's.

| What | Where |
|---|---|
| The activation | [`click()`](src/haven/Button.java:213) runs [`action`](src/haven/Button.java:50) (public field, or the [`action(Runnable)`](src/haven/Button.java:158) chainer). [`gkeytype`](src/haven/Button.java:218) calls it too — **it is not a mouse event** |
| ...and the order that matters | [`mouseup`](src/haven/Button.java:265) does `d.remove(); redraw();` and calls `click()` **last**, so a handler may destroy the window it is sitting in |
| The caption, post-construction | [`change(String)`](src/haven/Button.java:193) / [`change(String, Color)`](src/haven/Button.java:187) — re-render + `redraw()` |
| Short vs large | [`largep(w)`](src/haven/Button.java:106) — `w >= bl+bm+br` **on the UI-scaled images**, so the same width is not the same button on every client. The `lg` constructors ([:115](src/haven/Button.java:115), [:134](src/haven/Button.java:134)) say it outright |
| The server-sending default | [`Button(int, String)`](src/haven/Button.java:143) → [:134](src/haven/Button.java:134) sets `action = () -> wdgmsg("activate")`. The `Runnable` overloads ([:115](src/haven/Button.java:115), [:139](src/haven/Button.java:139)) do not |
| The font seam | [`checkfont`](src/haven/Button.java:61)/[`render`](src/haven/Button.java:123) — the caption goes through the `"button"` scope provider and re-renders in [`draw(GOut)`](src/haven/Button.java:200) when `Fonts.gen()` moves |

An **empty caption is safe**: [`Text.Foundry.render`](src/haven/Text.java:226) widens a zero-width string to
1 px before allocating the buffer, so a button built with `""` does not blow up on `new BufferedImage(0, …)`.

## `IButton`, and where a face comes from

[`IButton`](src/haven/IButton.java:32) — the picture push button. Its faces are **`final`**
([:33](src/haven/IButton.java:33)) and its box is `Utils.imgsz(up)`: a face is chosen at construction, never after.

| What | Where |
|---|---|
| The faces, and the two-image default | [`IButton(up, down)`](src/haven/IButton.java:72) → `hover = up`; the three-image form is [:67](src/haven/IButton.java:67) |
| Activation, and the server-sending default | [`click()`](src/haven/IButton.java:111) runs `action` and [`gkeytype`](src/haven/IButton.java:116) calls it too — the ctors without a `Runnable` ([:67](src/haven/IButton.java:67), [:80](src/haven/IButton.java:80)) set `action = () -> wdgmsg("activate")`; the `Runnable` overload ([:59](src/haven/IButton.java:59)) does not |
| The hit test reads PIXELS | [`checkhit`](src/haven/IButton.java:103) bounds the point by **`sz`** and then samples `up`'s alpha there, so a box wider than the picture samples off the raster and throws **from the input pass** |
| A face from the game's own art | [`Resource.loadrimg`](src/haven/Resource.java:2050) = `local().loadwait(name).layer(imgc)`, **null** when the resource has no image layer; [`loadsimg`](src/haven/Resource.java:2058) is that plus `.scaled()` (the UI scale: 56×56 art reads 14×14 at the default). A name that does not exist throws `Resource.NoSuchResourceException` — on the **local** pool in ~10 ms, so it is safe on the UI thread |

## `ACheckBox`'s value spine, `CheckBox` and `ICheckBox`

Neither subclass extends `SIWidget` — both blit/draw fresh every frame, no `redraw()`-on-resize fix needed.

| What | Where |
|---|---|
| State + click | [`ACheckBox.a`](src/haven/ACheckBox.java:32) `public boolean`; [`click`](src/haven/ACheckBox.java:59) → [`set(!state())`](src/haven/ACheckBox.java:56), which flips `a` and calls [`changed(a)`](src/haven/ACheckBox.java:43) **only on an actual flip** (stock `changed` sends `wdgmsg` only `if(canactivate)`, `false` off a bare ctor) |
| `CheckBox.lbl` | was package-private, no setter — `// addon:` (040.4) made it `public` + added `settext(String)` (mirrors `Label.settext`) |
| `ICheckBox` faces | [`up/down/hoverup/hoverdown`](src/haven/ICheckBox.java:32) are `Tex`, not `BufferedImage` — it blits, doesn't rasterise; `checkhit` samples `up`'s alpha bounded by `sz` |

> **A checkbox's click runs during `mousedown`** (unlike `Button`, whose activation is the last thing
> `mouseup` does). `Window.mousedown` still runs `parent.setfocus(this)` on ITSELF after `ev.propagate`
> returns, so destroying the checkbox's own window from the click is not automatically safe — a window
> destroyed mid-propagation has a null `parent` there. Defer such a destroy a tick from Lua.

## A native control that is always in the tree

[`Window.DefaultDeco.cbtn`](src/haven/Window.java:195) — the close box, a real `IButton` added by the deco
([:205](src/haven/Window.java:205)) and owned by nobody. Every window carries one, which makes it the
reliable answer to *"find a control this addon did not build"* without depending on which client windows
happen to be open. The rest of the deco is [ui-chrome.md](ui-chrome.md).

## Tree operations a control adapter uses

| What | Where |
|---|---|
| Resize / move / pack | [`Widget.resize`](src/haven/Widget.java:1534) (no-op when equal) · [`move`](src/haven/Widget.java:1530) · [`pack`](src/haven/Widget.java:1526) = `resize(contentsz())` |
| Unlink + destroy | [`remove()`](src/haven/Widget.java:570) is **null-parent safe**, so a double destroy is harmless; [`destroy()`](src/haven/Widget.java:586) is `remove()` + `rdispose()` and cascades to children by unlinking the subtree's root |
| Visibility | [`hide`](src/haven/Widget.java:2048)/[`show`](src/haven/Widget.java:2054) also touch the parent's focus list; [`visible()`](src/haven/Widget.java:2068) is the widget's **own** flag, [`tvisible()`](src/haven/Widget.java:2072) walks up |

**Every control needs a thin subclass anyway**, which is why the ownership contract costs nothing extra: the
hooks the engine offers are `protected`/overridable methods (`Button.click`, `SIWidget.draw`) or public
fields taking a lambda, not a settable callback slot. The split between the two decides how much an adapter
does — a lambda-taking control needs the subclass only to carry ownership.

## The four display controls, and one false-friend name

`Label`, `ILabel`, `Img`, `Progress` and `HRuler` are plain `Widget` subclasses — none extend `SIWidget`, so
none need the `redraw()`-on-resize fix above.

| What | Where |
|---|---|
| `Img`'s content | [`setimg(Tex)`](src/haven/Img.java:58) is a live, public, post-construction setter — unlike an `IButton` face, replacing it needs no D-113 rebuild |
| `Progress`'s fraction | [`Progress.a`](src/haven/Progress.java:35), `public float`, read directly by [`draw`](src/haven/Progress.java:75) when no `Supplier` is installed |

> **`ILabel` is NOT an image variant, despite the `I` prefix `IButton`/`ICheckBox` set.**
> [`ILabel(String, Text.Furnace)`](src/haven/ILabel.java:33) carries no picture at all — its `Furnace` is a
> font baked once and never live-restyled, the opposite of `Label`'s live restyle on a stylesheet override.
> A control adapter that needs the stylesheet to keep dressing it wants `Label`, never `ILabel`.

## `RadioGroup` — a coordinator, not a `Widget`

[`RadioGroup`](src/haven/RadioGroup.java:31) never joins the tree itself; [`add(lbl, c)`](src/haven/RadioGroup.java:64)
mints a `RadioButton` (its non-static inner class, package-private ctor) and adds it straight into the
`parent` the constructor was given — that `parent` IS the row's tree parent.

| What | Where |
|---|---|
| The one overridable hook | [`changed(int, String)`](src/haven/RadioGroup.java:103) — empty by default, fired only from `check(RadioButton)` |
| Every path funnels through one method | [`check(int)`](src/haven/RadioGroup.java:75)/[`check(String)`](src/haven/RadioGroup.java:80) both call [`check(RadioButton)`](src/haven/RadioGroup.java:85), which ALWAYS fires the hook — no lower-level "just flip the visual" entry point exists |
| A user click | [`RadioButton.mousedown`](src/haven/RadioGroup.java:50) calls `check(this)` directly, bypassing `CheckBox.mousedown`/`click()` |

> **A programmatic write cannot go through `check()`** — it fires the same hook a click fires, with no
> `ACheckBox`-style `set()`/`state()` seam to exploit instead. The adapter calls
> [`RadioButton.changed(boolean)`](src/haven/RadioGroup.java:57) directly on the two affected buttons (old
> off, new on), the method `check()` itself calls, skipping only `check()`'s own hook dispatch.

## `HSlider` and `Scrollbar` — public fields, and a two-call vs one-call split

Both are plain `Widget` subclasses (no `SIWidget` cache, nothing to `redraw()`). `val`/`min`/`max` are
`public int` on each — the adapter reads and writes them directly, with its own clamp.

| What | Where |
|---|---|
| The drag-in-progress hook | [`HSlider.changed()`](src/haven/HSlider.java:103) — empty by default, called from `update(Coord)` on every step of a drag that actually moves `val` |
| The release hook | [`HSlider.fchanged()`](src/haven/HSlider.java:104) — called ONCE from [`mouseup`](src/haven/HSlider.java:92) whenever a grab was active (`drag != null`), even if `val` never changed during it |
| `Scrollbar` has only the first half | [`Scrollbar.changed()`](src/haven/Scrollbar.java:115) fires from the same `update(Coord)` shape; `mouseup` only releases the grab — **no `fchanged()` equivalent exists** |

> **`Scrollbar(int h, Scrollable ctl)` — the constructor `Scrollport` uses — makes `draw()` overwrite
> `min`/`max`/`val` from `ctl` EVERY FRAME.** [`Scrollbar.draw`](src/haven/Scrollbar.java:58) starts
> `if(ctl != null) { min = ctl.scrollmin(); … }` before painting. A bare control must use the OTHER
> constructor, `Scrollbar(int h, int min, int max)`, which leaves `ctl` `null` — otherwise an addon's own
> `:range`/`:value` writes read back correctly for one tick and silently revert on the next drawn frame.

## `TextEntry` and its `ReadLine` buffer

[`TextEntry`](src/haven/TextEntry.java:32) is a plain `Widget` (no `SIWidget` cache) that owns a
[`ReadLine`](src/haven/ReadLine.java:35) buffer rather than holding its string directly — `PCLine`/`EmacsLine`
are the two implementations, chosen once by the `"editmode"` pref; either way the notify shape below is
`ReadLine.Base`'s and identical.

| What | Where |
|---|---|
| Per-edit notify | [`Base.key`](src/haven/ReadLine.java:155) calls `owner.changed(this)` only when the edit actually changed the buffer (`seq` moved) — a no-op keypress (e.g. Left at column 0) fires nothing |
| Enter | [`key2`](src/haven/ReadLine.java:279) matches `Widget.key_act` and calls `owner.done(this)` — **not** `changed`; `TextEntry.done` → [`activate(buf.line())`](src/haven/TextEntry.java:172), gated stock-side by `canactivate` (`false` off a bare ctor, so the stock class sends no `wdgmsg` either) |
| The silent write | [`TextEntry.rsettext(String)`](src/haven/TextEntry.java:81) replaces `buf` with a brand-new `ReadLine` (`ReadLine.make`, mirroring the constructor) — `Base`'s plain [`line(String)`](src/haven/ReadLine.java:108) setter it goes through calls nothing, unlike `settext`/`Base.setline` below |
| The noisy write | [`TextEntry.settext(String)`](src/haven/TextEntry.java:76) → `buf.setline(text)` → [`Base.setline`](src/haven/ReadLine.java:171)/[`PCLine.setline`](src/haven/ReadLine.java:241), which calls `owner.changed(this)` whenever the line actually differs |

> **A control's own `:value(v)` write has to go through `rsettext`, never `settext`.** The stock class uses
> `settext` for everything (construction included calls `rsettext`, but every later native caller uses
> `settext`), and that path notifies `changed` — so writing a value the "obvious" way re-enters an adapter's
> own change handler, exactly the feedback loop every other value-bearing control in this catalogue also has
> to avoid, just reached from a buffer object instead of a field.

## `Scrollport` — composition over `Scrollbar` + `Scrollcont`, and a sealed bar

[`Scrollport`](src/haven/Scrollport.java:29) is not extended by an adapter: its constructor builds `bar`
([:30](src/haven/Scrollport.java:30)) as a **fixed anonymous `Scrollbar`** whose only override is
`changed()` (`cont.sy = bar.val`), so a subclass has no seam to make that same object notify Lua too. An
adapter instead rebuilds the shape from `Scrollport`'s own public pieces.

| What | Where |
|---|---|
| The inner container | [`Scrollcont`](src/haven/Scrollport.java:54), `public static` — reusable directly; its clip+scroll draw is [`draw(GOut)`](src/haven/Scrollport.java:76), offsetting each child by `-sy` via [`xlate`](src/haven/Scrollport.java:69) and skipping one whose translated box misses the port entirely |
| The bar's range, auto-derived | [`Scrollcont.update()`](src/haven/Scrollport.java:48) (the constructor's override) sets `bar.max = max(0, contentsz().y + 10 - sz.y)` — runs from [`Scrollcont.add`](src/haven/Scrollport.java:63) only, **not** from a later `resize()` on an existing child, so a child's final size must be set before it is added |
| The wire-protocol redirect | [`Scrollport.addchild`](src/haven/Scrollport.java:96) forwards into `cont.addchild` — **`Widget.add` does NOT call `addchild`**, so any Java caller adding straight into a `Scrollport` (not through this override) drops the child beside the bar instead of inside `cont` |
| Wheel + resize | [`mousewheel`](src/haven/Scrollport.java:91) is `bar.ch(ev.s * UI.scale(15))`; [`resize`](src/haven/Scrollport.java:100) re-anchors `bar` to the right edge and resizes `cont` to `sz` minus the bar's width |

> **The `:parent(w)` write is the one Java call site outside `Scrollport` itself that adds a child into one.**
> It goes through `Widget.add(child, Coord)`, never `addchild`, so a parent-shaped like `Scrollport` needs its
> OWN `instanceof` branch there redirecting into `cont` — the addchild override above does not cover it.

## `SListWidget`/`SListBox` — the model-backed contract

[`SListWidget<I, W>`](src/haven/SListWidget.java:33) demands exactly two overrides —
[`items()`](src/haven/SListWidget.java:40) and [`makeitem(I, int, Coord)`](src/haven/SListWidget.java:41) —
and [`sel`](src/haven/SListWidget.java:34) (`public I`) plus [`change(I)`](src/haven/SListWidget.java:47)
(`this.sel = item`, nothing else) are the whole selection state. `SListBox<I, W>` adds scrolling over that.

| What | Where |
|---|---|
| Row widgets are built LAZILY | [`SListBox.update()`](src/haven/SListBox.java:62), called from [`tick(dt)`](src/haven/SListBox.java:137) every frame — **not** from `items()`/`change()` directly, so a row from `:rows(t)` does not exist as a widget until the next tick |
| The ready-made rows | [`TextItem.of(sz, Supplier<String>)`](src/haven/SListWidget.java:141) and [`IconText.of(sz, Supplier<BufferedImage>, Supplier<String>)`](src/haven/SListWidget.java:265) — both plain `Widget`s, neither wired to `change()` on their own |
| The click-to-select wrapper | [`ItemWidget<I>`](src/haven/SListWidget.java:51) — its [`mousedown`](src/haven/SListWidget.java:61) calls `list.change(item)` directly; `makeitem` must wrap a bare `TextItem`/`IconText` in one (added as its own child) for a click to select anything |
| Deselect on empty click | [`SListBox.unselect(button)`](src/haven/SListBox.java:207) calls `change(null)` for button 1 when [`mousedown`](src/haven/SListBox.java:217) finds no `slotclick` — a REAL interaction, not one an adapter's own `:value(v)` should suppress |

> **A programmatic write must not call `change(I)`.** It is the single hook BOTH a real click
> (`ItemWidget.mousedown`) and the click-away deselect reach, with no lower-level "just set `sel`, don't
> notify" seam — so a control's `:value(v)` writes the `sel` field directly (D-153's rule again) and only a
> real click's `ItemWidget.mousedown` → `change(item)` path fires the Lua `:onChange` handler.

## `SDropBox`/`SListMenu` — neither is an `SListWidget` itself, and neither wants an `ItemWidget` back

Both extend/wrap `SListWidget`'s contract one level removed, which is why the same `makeitem` result
(`SListWidget.TextItem`/`IconText`) is wrapped in an `ItemWidget` for `SListBox` but must NOT be for either
of these — the wrap happens inside their OWN inner list class instead.

| What | Where |
|---|---|
| `SDropBox<I, W>` IS an `SListWidget<I, W>` | but its `makeitem` must return the BARE `W`, not an `ItemWidget` — [`SDropList.Item`](src/haven/SDropBox.java:49) (the popup's own row wrapper) and [`SDropBox.change`](src/haven/SDropBox.java:111) (the closed-box widget) each wrap it themselves |
| `SDropList.makeitem` | [`new Item(item, SDropBox.this.makeitem(item, idx, sz))`](src/haven/SDropBox.java:57) — the outer `makeitem` supplies content, the inner list supplies the click wrapper |
| `change(I)` does DOUBLE duty here | [`SDropBox.change`](src/haven/SDropBox.java:111) sets `sel` **and** rebuilds the closed-box widget (`curitem`, destroyed and re-`makeitem`'d) — unlike `SListBox`, there is no lower-level "just set `sel`" seam at all; a caller must run the SAME method's logic to keep the closed-box display in sync, so a control's own `:value(v)` calls `change(I)` directly and skips only its own notify wrapper (not the field write) |
| `makeitem(null, …)` is a REAL call | [`SDropBox.change`](src/haven/SDropBox.java:117) calls `makeitem(item, -1, …)` with whatever it is given, including `null` (no selection) — an adapter's `makeitem` must handle it |
| `SListMenu` is NOT an `SListWidget` at all | it wraps one, [`InnerList extends SListBox`](src/haven/SListMenu.java:61) as a private field (`box`) — `SListMenu.makeitem`'s result is wrapped in `InnerList.Item` the same one-level-removed way `SDropList` wraps `SDropBox`'s |
| `added()` grabs input UNCONDITIONALLY | [`SListMenu.added()`](src/haven/SListMenu.java:149) — `ui.grab`/`ui.grabkeys`, gated only by the public `grab` field (default `true`); [`nograb()`](src/haven/SListMenu.java:172) is the documented opt-out, meant for exactly this: a menu that is not a modal popup |
| Window raise vs. popup add-order | [`Window.mousedown`](src/haven/Window.java:461) raises itself AFTER `ev.propagate` returns — so a click that opens an `SDropBox`'s popup (added to `ui.root` DURING that propagate) is always followed by the enclosing window re-topping itself over it, same frame |

## `GridList` — DRAWS cells, does not build row widgets

[`GridList<T>`](src/haven/GridList.java:33) is the one model-backed control with no `items()`/`makeitem()` —
its only abstract method is [`drawitem(GOut, T)`](src/haven/GridList.java:136), called straight from `draw`,
so an adapter never touches `SListWidget` at all. Layout is one or more [`Group`](src/haven/GridList.java:57),
a **non-static inner class whose constructor self-registers** (`groups.add(this)`, [:69](src/haven/GridList.java:69))
— there is no removal, so a different `itemsz` needs a whole new `GridList`, not a mutated `Group`.

| What | Where |
|---|---|
| `itemsz`/`marg` are `final` on `Group` | [:58](src/haven/GridList.java:58) — a cell-size change is D-113's "rebuild", the same shape a list's `:rowHeight(n)` already has |
| `marg.x < 0` means EVEN SPREAD | [`adjx`](src/haven/GridList.java:138) spaces items across the full row width instead of a fixed gap when `marg.x` is negative — the engine's own icon-grid shape (`SkillWnd.SkillGrid`/`ExpGrid` both pass `(-1, 5)`) |
| `drawitem` runs for EVERY item, every frame, unconditionally | [`draw(GOut)`](src/haven/GridList.java:142)'s item loop runs `sr*rw` to `items.size()-1` with **no per-item bound check against `sz.y`** — an item below the visible box still gets `drawitem` called, just clipped on screen; only `Group`-level visibility (`grp.ey - yo < 0`) skips a whole group |
| A `Loading` from one cell does not aim the whole draw | `draw` catches `Loading` PER ITEM ([:170](src/haven/GridList.java:170)) and blits a placeholder — a wrapping addon callback (`drawitem` override) that raises anything else propagates to whatever calls it |
| Selection exists but is native-only | [`change(T)`](src/haven/GridList.java:220)/[`itemclick`](src/haven/GridList.java:224) set `sel` and draw a highlight ([`drawsel`](src/haven/GridList.java:130)) on a real click — no Lua verb reads it (spec 040 ships no `:value()` on a grid) |

## `LuaGOut` — the one `g` wrapper, now with a SECOND consumer

[`LuaGOut`](src/io/brodgar/addon/LuaGOut.java:40) was built for [`AddonWidget.draw`](src/io/brodgar/addon/AddonWidget.java:196)'s
`:onDraw(fn)` and is **not** widget-specific: [`bind(GOut, Addon, FontHandle)`](src/io/brodgar/addon/LuaGOut.java:252)
points its one `LuaTable` of closures at whichever `GOut` is live and [`unbind()`](src/io/brodgar/addon/LuaGOut.java:260)
nulls it again — so any draw-time call site can own one and bind/unbind it per callback, which is exactly what
`CGrid.drawitem` (task 040.11) does for `:onCell(g, item, w, h)`, one bind per CELL rather than per widget-frame.
The rendered-text cache ([`Cache`](src/io/brodgar/addon/LuaGOut.java:87)) is keyed per `owner` `Addon`, so two
controls of the same addon calling `g:text` with the same string still share one cache entry.
