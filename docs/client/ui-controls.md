# The client's own controls

> The catalogue of interactive widgets `haven` already ships, and what each one's construction and
> value spine actually does. The tree itself is [the widget system](widgets.md); what draws a window
> *frame* is [chrome](ui-chrome.md).

## The image-backed base: `SIWidget`

`Button` and `IButton` extend `SIWidget`, which is one idea: **rasterise once,
blit thereafter**. `CheckBox`/`ICheckBox` do **not** — see below.

| What | Where |
|---|---|
| The cache | `SIWidget.surf` — a `Tex`, built lazily in `draw(GOut)` from the subclass's `draw(BufferedImage)` |
| The only invalidation | `redraw()` — disposes `surf` and nulls it; `dispose()` is teardown |

> **`Widget.resize(Coord)` does NOT call `redraw()`.** `Widget.resize` sets
> `sz`, presizes the children and tells the parent — nothing more. So resizing an `SIWidget` moves its box
> and keeps its old picture, at the old dimensions, until something else happens to invalidate it. Anything
> that sizes one of these controls has to `redraw()` itself; the symptom reads as a layout bug.

## `Button`

`Button` — the text push button. Height is **fixed by its images**:
`hs`/`hl`, short or "large", never the caller's.

| What | Where |
|---|---|
| The activation | `click()` runs `action` (public field, or the `action(Runnable)` chainer). `gkeytype` calls it too — **it is not a mouse event** |
|...and the order that matters | `mouseup` does `d.remove(); redraw();` and calls `click()` **last**, so a handler may destroy the window it is sitting in |
| The caption, post-construction | `change(String)` / `change(String, Color)` — re-render + `redraw()` |
| A caption is **three** fields, not a string | fork: `rtext` + `rcol` + `rwrap` (public), and `change(String)` sets `rcol = null`, `rwrap = 0` — so a coloured caption, or a wrapped one (`wrapped(w, text)`, the `ltbtn` factory), put back through it comes back rendered wrong. `caption(String, Color, int)` (`// addon:`) is the one write that takes all three |
| A caption never resizes the button | `sz` is the constructor's `w` × `hs`/`hl`; `change`/`render` re-rasterise the face and nothing else, so a longer caption is centred and clipped rather than widening the box |
| A caption may be **absent** | `Button(int, Text)` and `Button(int, BufferedImage)` set `cont` directly and leave `rtext` null — the face was rendered by the caller, and there is nothing to render back |
| Short vs large | `largep(w)` — `w >= bl+bm+br` **on the UI-scaled images**, so the same width is not the same button on every client. The `lg` constructors () say it outright |
| The server-sending default | `Button(int, String)` → sets `action = () -> wdgmsg("activate")`. The `Runnable` overloads () do not |
| The font seam | `checkfont`/`render` — the caption goes through the `"button"` scope provider and re-renders in `draw(GOut)` when `Fonts.gen()` moves |

An **empty caption is safe**: `Text.Foundry.render` widens a zero-width string to
1 px before allocating the buffer, so a button built with `""` does not blow up on `new BufferedImage(0, …)`.

## `IButton`, and where a face comes from

`IButton` — the picture push button. Its faces are **`final`**
 and its box is `Utils.imgsz(up)`: a face is chosen at construction, never after.

| What | Where |
|---|---|
| The faces, and the two-image default | `IButton(up, down)` → `hover = up`; the three-image form is |
| Activation, and the server-sending default | `click()` runs `action` and `gkeytype` calls it too — the ctors without a `Runnable` () set `action = () -> wdgmsg("activate")`; the `Runnable` overload does not |
| The hit test reads PIXELS | `checkhit` bounds the point by **`sz`** and then samples `up`'s alpha there, so a box wider than the picture samples off the raster and throws **from the input pass** |
| A face from the game's own art | `Resource.loadrimg` = `local().loadwait(name).layer(imgc)`, **null** when the resource has no image layer; `loadsimg` is that plus `.scaled()` (the UI scale: 56×56 art reads 14×14 at the default). A name that does not exist throws `Resource.NoSuchResourceException` — on the **local** pool in ~10 ms, so it is safe on the UI thread |

## `ACheckBox`'s value spine, `CheckBox` and `ICheckBox`

Neither subclass extends `SIWidget` — both blit/draw fresh every frame, no `redraw()`-on-resize fix needed.

| What | Where |
|---|---|
| State + click | `ACheckBox.a` `public boolean`; `click` → `set(!state())`, which flips `a` and calls `changed(a)` **only on an actual flip** (stock `changed` sends `wdgmsg` only `if(canactivate)`, `false` off a bare ctor) |
| `CheckBox.lbl` | was package-private, no setter — `// addon:` made it `public` + added `settext(String)` (mirrors `Label.settext`) |
| `ICheckBox` faces | `up/down/hoverup/hoverdown` are `Tex`, not `BufferedImage` — it blits, doesn't rasterise; `checkhit` samples `up`'s alpha bounded by `sz` |
| The three activation sites | `CheckBox.mousedown` and `ICheckBox.mousedown` each call `click()`; `ACheckBox.gkeytype` is the keyboard's, shared by both **and by `RadioGroup.RadioButton`**, which overrides `mousedown` but not this |
| `state`/`set`/`changed`/`click` are all **public fields** | so a caller may replace any of them — `SDropBox` replaces `state`/`set` on its own drop arrow, which is why that arrow never runs `changed` at all |

> **A checkbox's click runs during `mousedown`** (unlike `Button`, whose activation is the last thing
> `mouseup` does). `Window.mousedown` still runs `parent.setfocus(this)` on ITSELF after `ev.propagate`
> returns, so destroying the checkbox's own window from the click is not automatically safe — a window
> destroyed mid-propagation has a null `parent` there. Defer such a destroy a tick from Lua.

## A native control that is always in the tree

`Window.DefaultDeco.cbtn` — the close box, a real `IButton` added by the deco
 and owned by nobody. Every window carries one, which makes it the
reliable answer to *"find a control this addon did not build"* without depending on which client windows
happen to be open. The rest of the deco is [ui-chrome.md](ui-chrome.md).

## `OptWnd` — panels built on first visit, and one that rebuilds itself

The other window always reachable, and the one with a control of every kind in it. Two things about it cost
time before they are known:

| What | Where |
|---|---|
| A panel is not in the tree until it is opened | `OptWnd.PButton.click` does `actual = add(tgt.get())` the **first** time and caches it; a panel nobody has visited matches no selector at all, and after one visit it stays added with `visible = false` while another shows |
| `VideoPanel` throws its whole column away whenever a graphics preference moves | `VideoPanel.draw` runs `if((curcf == null) \|\| (ui.gprefs != curcf.prefs)) resetcf(ui)`, and `resetcf` destroys `curcf` and builds a fresh one — so the very checkbox a click just flipped is a **different widget** on the next frame, and anything held on the old one is holding a widget that has left the tree |
| Its own controls override the value hook, not the input | e.g. `new CheckBox("Vertical sync") {public void set(boolean val) {…}}` — an anonymous subclass replacing `set`, which is exactly what a seam placed on an overridable hook would have missed |

## Tree operations a control adapter uses

| What | Where |
|---|---|
| Resize / move / pack | `Widget.resize` (no-op when equal) · `move` · `pack` = `resize(contentsz())` |
| Unlink + destroy | `remove()` is **null-parent safe**, so a double destroy is harmless; `destroy()` is `remove()` + `rdispose()` and cascades to children by unlinking the subtree's root |
| Visibility | `hide`/`show` also touch the parent's focus list; `visible()` is the widget's **own** flag, `tvisible()` walks up |

**Every control needs a thin subclass anyway**, which is why the ownership contract costs nothing extra: the
hooks the engine offers are `protected`/overridable methods (`Button.click`, `SIWidget.draw`) or public
fields taking a lambda, not a settable callback slot. The split between the two decides how much an adapter
does — a lambda-taking control needs the subclass only to carry ownership.

## The four display controls, and one false-friend name

`Label`, `ILabel`, `Img`, `Progress` and `HRuler` are plain `Widget` subclasses — none extend `SIWidget`, so
none need the `redraw()`-on-resize fix above.

| What | Where |
|---|---|
| `Img`'s content | `setimg(Tex)` is a live, public, post-construction setter — unlike an `IButton` face, replacing it needs no D-113 rebuild |
| `Label`'s caption | `settext(String)` returns early when the text is equal, re-renders through the label's own `f` and **`resize`s to the new raster** — so unlike a `Button`, a `Label`'s box follows what it says. It renders through `f.render`, never `renderwrap`, so a **wrapped** label (`new Label(text, w)`, whose width is kept in the fork's `fontwrapw`) comes back on one line through it; `settext(String, int)` + `wrapw()` (`// addon:`) are the pair that keep the wrap |
| `Progress`'s fraction | `Progress.a`, `public float`, read directly by `draw` when no `Supplier` is installed |

> **`ILabel` is NOT an image variant, despite the `I` prefix `IButton`/`ICheckBox` set.**
> `ILabel(String, Text.Furnace)` carries no picture at all — its `Furnace` is a
> font baked once and never live-restyled, the opposite of `Label`'s live restyle on a stylesheet override.
> A control adapter that needs the stylesheet to keep dressing it wants `Label`, never `ILabel`.

## `RadioGroup` — a coordinator, not a `Widget`

`RadioGroup` never joins the tree itself; `add(lbl, c)`
mints a `RadioButton` (its non-static inner class, package-private ctor) and adds it straight into the
`parent` the constructor was given — that `parent` IS the row's tree parent.

| What | Where |
|---|---|
| The one overridable hook | `changed(int, String)` — empty by default, fired only from `check(RadioButton)` |
| Every path funnels through one method | `check(int)`/`check(String)` both call `check(RadioButton)`, which ALWAYS fires the hook — no lower-level "just flip the visual" entry point exists |
| A user click | `RadioButton.mousedown` calls `check(this)` directly, bypassing `CheckBox.mousedown`/`click()` |

> **A programmatic write cannot go through `check()`** — it fires the same hook a click fires, with no
> `ACheckBox`-style `set()`/`state()` seam to exploit instead. The adapter calls
> `RadioButton.changed(boolean)` directly on the two affected buttons (old
> off, new on), the method `check()` itself calls, skipping only `check()`'s own hook dispatch.

