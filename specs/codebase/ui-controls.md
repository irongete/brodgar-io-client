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
