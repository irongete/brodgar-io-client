# The client's own controls

> The catalogue of interactive widgets `haven` already ships, and what each one's construction and
> value spine actually does. The tree itself is [the widget system](widgets.md); what draws a window
> *frame* is [chrome](ui-chrome.md); the pages `OptWnd` swaps between are [panels](ui-panels.md).

## The image-backed base: `SIWidget`

`Button` and `IButton` extend `SIWidget`, which is one idea: **rasterise once,
blit thereafter**. `CheckBox`/`ICheckBox` do **not** — see below.

| What | Where |
|---|---|
| The cache | `SIWidget.surf` — a `Tex`, built lazily in `draw(GOut)` from the subclass's `draw(BufferedImage)` |
| The only invalidation | `redraw()` — disposes `surf` and nulls it; `dispose()` is teardown |

The raster is built **inside** `draw(GOut)` — it calls `draw()` → `draw(BufferedImage)` on the spot when
`surf` is null — so whatever the composition reads, it reads in the same frame context as the blit. That is
what makes `Fonts.gen()`, whose value carries the ambient per-widget frame, safe to record from within one.

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
| The server's own caption write | `uimsg "ch"` → `change(String)`, or `change(String, Color)` with a second argument. `ACheckBox` takes the same message name for its **state**, so a consumer keying on the name alone catches both |
| A caption is **three** fields, not a string | fork: `rtext` + `rcol` + `rwrap` (public), and `change(String)` sets `rcol = null`, `rwrap = 0` — so a coloured caption, or a wrapped one (`wrapped(w, text)`, the `ltbtn` factory), put back through it comes back rendered wrong. `caption(String, Color, int)` (`// addon:`) is the one write that takes all three |
| A caption never resizes the button | `sz` is the constructor's `w` × `hs`/`hl`; `change`/`render` re-rasterise the face and nothing else, so a longer caption is centred and clipped rather than widening the box |
| A caption may be **absent** | `Button(int, Text)` and `Button(int, BufferedImage)` set `cont` directly and leave `rtext` null — the face was rendered by the caller, and there is nothing to render back |
| Short vs large | `largep(w)` — `w >= bl+bm+br` **on the UI-scaled images**, so the same width is not the same button on every client. The constructors taking an explicit `lg` say it outright; the rest derive it from `w` |
| The server-sending default | `Button(int, String)` and `Button(int, String, boolean)` set `action = () -> wdgmsg("activate")`. The overloads taking a `Runnable` do not |
| The font seam | `checkfont`/`render` — the caption goes through the `"button"` scope provider and re-renders in `draw(GOut)` when `Fonts.gen()` moves |
| The face is **seven** statics | `bl`/`br`, the full-height end caps; `bt`/`bb`, stretched between them; `ut`/`dt`, the released and held centre textures; `bm`, the ear a large button wears above its frame. `hs`/`hl` are the two heights, and `yo = (hl - hs) / 2` is where the frame sits inside a large one |
| ...composed in one pass, in this order | `draw(BufferedImage)`: the centre texture inset `UI.scale(4)` on each side, then `cont` centred (shifted `(1,1)` while `a`), then the four edges **over** it, then `bm`. `dis` monochromises the whole raster last, so it greys whatever that pass drew and nothing painted around it |
| The pointer | `h` (`// addon:`) is set from `mousemove`, which the UI broadcasts to **every** visible widget rather than only the one under the pointer, so the flag falls back to false when the pointer leaves. Stock `Button` has no hover picture at all — `IButton.h` is the same field on a control that does |
| The chrome seam | `face()`/`fbox()` (`// addon:`) — the `"button"` rule's fill under the raster and its frame over it, each replacing the statics above only where the rule names it |
| Disabling | `disable(boolean)` is public, `dis` private, and every call `redraw()`s. While `dis`: `face()` answers `"disabled"` ahead of `pressed`/`hover`, `draw(BufferedImage)` monochromises the raster last, and `mousedown` drops a left press on its own. ⚠️ **`gkeytype` does not look at `dis`** — a keybound button fires `click()` from the keyboard however greyed it is; anything disabling one that has a key stops the key itself |

An **empty caption is safe**: `Text.Foundry.render` widens a zero-width string to
1 px before allocating the buffer, so a button built with `""` does not blow up on `new BufferedImage(0, …)`.

## `IButton`, and where a face comes from

`IButton` — the picture push button. Its faces are **`final`** and its box is `Utils.imgsz(up)`: a face is
chosen at construction, never after, so re-facing one is building another and destroying the first.

| What | Where |
|---|---|
| The faces, and the two-image default | `IButton(up, down)` → `hover = up`. Each form comes twice, over three `BufferedImage`s or over a `base` folder plus three suffixes (`IButton(String base, up, down, hover)`, where a null `hover` falls back to `up`) |
| Activation, and the server-sending default | `click()` runs `action` and `gkeytype` calls it too — the ctors without a `Runnable` set `action = () -> wdgmsg("activate")`, the ones taking one do not, and `action(Runnable)` is the chainer that replaces it either way |
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
| `CheckBox`'s face is **four** statics, in two sizes | `lbox`/`lmark` and `sbox`/`smark`, picked by the ctor's `lg` flag (`loff` is where the caption sits beside them). `draw(GOut)` blits `box` vertically centred on `sz.y`, then `mark` over it **only while `state()`** — so the tick has no resting art at all, and neither piece is ever drawn scaled |
| The chrome seam, and why only one of the two has one | `CheckBox.draw(GOut)` (`// addon:`) asks `"checkbox"` for the box and `"checkbox.mark"` for the tick, each in the state `chromeState()` names (`// addon:`, protected — `"checked"` or null on a stock box, overridable to `"disabled"`), and caches neither. `ICheckBox` is **not** routed: `GameUI.MenuCheckBox` stacks five of them at `(0, 0)`, each holding the whole menu panel's art with only its own button opaque and `checkhit` sampling `up`'s alpha to route the click — so anything filling one's `sz` paints the entire panel, once per button |

> **A checkbox's click runs during `mousedown`** (unlike `Button`, whose activation is the last thing
> `mouseup` does). `Window.mousedown` still runs `parent.setfocus(this)` on ITSELF after `ev.propagate`
> returns, so destroying the checkbox's own window from the click is not automatically safe — a window
> destroyed mid-propagation has a null `parent` there. Defer such a destroy a tick from Lua.

## A native control that is always in the tree

`Window.DefaultDeco.cbtn` — the close box, a real `IButton` added by the deco and owned by nobody. Every
window carries one, which makes it the
reliable answer to *"find a control this addon did not build"* without depending on which client windows
happen to be open. The rest of the deco is [ui-chrome.md](ui-chrome.md).

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
| `Img`'s content | `setimg(Tex)` is a live, public, post-construction setter — unlike an `IButton` face, replacing it needs no D-113 rebuild. The `Tex` itself is a **private** field, and the server re-points it (`uimsg "ch"`), so nothing may cache what an `Img` is showing; `img()` (`// addon:`) is the read. ⚠️ **`setimg` also `resize`s the widget to the picture's own box, and `draw` blits at that box whatever `sz` says** (`g.image(img, Coord.z)`, never the scaled `image(tex, c, sz)`): a bigger box is empty room, a smaller one clips, and a `resize` made before `setimg` is undone by it |
| ...and its picture seam, which is a **read** and not that setter | `Img.draw(GOut)` (`// addon:`) opens with `Fonts.picture(this)` and blits its own `img` when that answers `null`. Going through `setimg` instead would be clobbered by the next `uimsg "ch"` and would leave the restore fighting the server; drawing from the answer survives a re-point and needs no undo. `Fonts.picture(Widget)` is the one member of `Fonts.Chromes` with no scope in it: a plate is one widget's own art, so it takes the per-widget half of the cascade alone |
| `Label`'s caption | `settext(String)` returns early when the text is equal, re-renders through the label's own `f` and **`resize`s to the new raster** — so unlike a `Button`, a `Label`'s box follows what it says. It renders through `f.render`, never `renderwrap`, so a **wrapped** label (`new Label(text, w)`, whose width is kept in the fork's `fontwrapw`) comes back on one line through it; `settext(String, int)` + `wrapw()` (`// addon:`) are the pair that keep the wrap |
| `Label`'s server write | `uimsg "set"` → `settext(Utils.sv(args[0]))` — the **plain** arm, so the server rewriting a wrapped label unwraps it; `"col"` → `setcolor` is the only other one it takes |
| `Progress`'s fraction | `Progress.a`, `public float`, is what `draw` reads **only while no `Supplier` is installed**: `val(Supplier<Float>)` installs one and from then on `a` is dead weight, the supplier being re-read every frame — so a value written into either field is overwritten before it is next drawn. `fraction()` (`// addon:`) is that fold, the line `draw` itself opens with |

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

