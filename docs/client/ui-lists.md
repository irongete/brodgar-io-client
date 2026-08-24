# Controls: lists, text and scrolling

> The model-backed and text controls `haven` ships. The simple ones (buttons, checkboxes, labels)
> are ui-controls.md; the tree itself is widgets.md.

## `HSlider` and `Scrollbar` — public fields, and a two-call vs one-call split

Both are plain `Widget` subclasses (no `SIWidget` cache, nothing to `redraw()`). `val`/`min`/`max` are
`public int` on each — the adapter reads and writes them directly, with its own clamp.

| What | Where |
|---|---|
| The drag-in-progress hook | `HSlider.changed()` — empty by default, called from `update(Coord)` on every step of a drag that actually moves `val` |
| The release hook | `HSlider.fchanged()` — called ONCE from `mouseup` whenever a grab was active (`drag != null`), even if `val` never changed during it |
| `Scrollbar` has only the first half | `Scrollbar.changed()` fires from the same `update(Coord)` shape; `mouseup` only releases the grab — **no `fchanged()` equivalent exists** |
| Where the value is actually WRITTEN | `HSlider.update(Coord)` (the drag) is the only one; a `Scrollbar` has **two** — `update(Coord)` for the thumb, and `ch(int)` for the wheel and the step buttons (`ch(double)` accumulates a fraction and calls `ch(int)`). Each writes `val` and THEN calls `changed()`, so anything riding the hook is told after the fact |
| Who calls `ch` | `Scrollport.mousewheel` (`bar.ch(ev.s * UI.scale(15))`), `SListBox`'s own wheel handling, and the step buttons of the widgets that draw them |
| The art, and the chrome seam | Two statics each: `schain`, the link a rail repeats (`HSlider`'s own static initialiser transposes the vertical raster into a horizontal `TexI`), and `sflarp`, the thumb — `chcut = UI.scale(7)` is how far past the box the run is stretched, and `Scrollbar.width`/`HSlider`'s ctor take their cross-axis size from `sflarp`. `draw(GOut)` (`// addon:`) asks `"scrollbar"`/`"slider"` for the rail over the widget's own box and `"scrollbar.knob"`/`"slider.knob"` for the thumb at the place it just computed; neither caches, and a `Scrollbar` paints nothing at all while `!vis()` |
| Neither has an ABSOLUTE setter | `HSlider.update(Coord)` and `Scrollbar.update(Coord)` are both `private`, so the only public way in is `Scrollbar.ch(int)` — a **delta**, clamped as `val + a` into `min..max` and silent when that does not move. Reaching a given value means writing the step it needs, and since `ch` is also one of the two places `val` is written, anything riding that write sees a programmatic step exactly as it sees the wheel |

> **`Scrollbar(int h, Scrollable ctl)` makes `draw()` overwrite `min`/`max`/`val` from `ctl` EVERY FRAME.**
> `Scrollbar.draw` starts `if(ctl != null) { min = ctl.scrollmin(); … }` before painting. `SListBox` is what
> uses that constructor (`new Scrollbar(0, this)`); `Scrollport`'s bar does **not** — it takes the bare
> `Scrollbar(int h, int min, int max)` and overrides `changed()` to push `bar.val` into `cont.sy`, so its
> `val` stays where a drag left it. A bare control must use the bare constructor too, or an addon's own
> `:range`/`:value` writes read back correctly for one tick and silently revert on the next drawn frame.

## `TextEntry` and its `ReadLine` buffer

`TextEntry` is a plain `Widget` (no `SIWidget` cache) that owns a
`ReadLine` buffer rather than holding its string directly — `PCLine`/`EmacsLine`
are the two implementations, chosen once by the `"editmode"` pref; either way the notify shape below is
`ReadLine.Base`'s and identical.

| What | Where |
|---|---|
| Per-edit notify | `Base.key` calls `owner.changed(this)` only when the edit actually changed the buffer (`seq` moved) — a no-op keypress (e.g. Left at column 0) fires nothing |
| Enter | `key2` matches `Widget.key_act` and calls `owner.done(this)` — **not** `changed`; `TextEntry.done` → `activate(buf.line())`, gated stock-side by `canactivate` (`false` off a bare ctor, so the stock class sends no `wdgmsg` either) |
| The keybinding's half | `TextEntry.gkeytype` calls the same `activate(buf.line())` — so the two paths into a submission are `done` and `gkeytype`, and **neither is overridden anywhere in `haven`** |
| ...but `activate(String)` IS | `public`, and `ChatUI.EntryChannel`'s anonymous entry overrides it **without calling `super`** (it sends the line itself and clears the field) — so it looks like the funnel, holding both the `canactivate` gate and the `wdgmsg`, while being the one method a subclass replaces on the entry every player types into |
| The silent write | `TextEntry.rsettext(String)` replaces `buf` with a brand-new `ReadLine` (`ReadLine.make`, mirroring the constructor) — `Base`'s plain `line(String)` setter it goes through calls nothing, unlike `settext`/`Base.setline` below |
| The noisy write | `TextEntry.settext(String)` → `buf.setline(text)` → `Base.setline`/`PCLine.setline`, which calls `owner.changed(this)` whenever the line actually differs |
| The face is **four** statics, split three ways | `mext`, the middle stretched across the whole box; `lcap`/`rcap`, the two end caps blitted **over** it at each end; `caret`, which is neither. `toffx = lcap.sz().x` is the text's left inset, `wmarg = lcap + rcap + UI.scale(1)` the room the caps take, `coff` the caret's own nudge |
| ...composed in one pass, in this order | `draw(GOut)`: the middle, then the selection `frect2` in `selcol`, then the rendered line, then the two caps over both, then the caret while `hasfocus`. The line is drawn at `toffx - sx` and vertically centred on `sz.y`; `sx` is the horizontal scroll, re-clamped from the caret's own advance against `sz.x - wmarg` |
| The box is the background's | the ctor is `super(new Coord(w, TextEntry.bgheight()))` (`// addon:`, whose stock answer is `mext.sz().y`) — the caller's width and the art's height, so a bigger font clips rather than growing the field, and `resize(int)` keeps `sz.y` |
| The raster cache | `tcache`, one `Text.Line`, dropped by `redraw()` (which disposes its tex) from every buffer edit and every `resize`; `tcgen` re-renders it when `Fonts.gen()` moves. `charat`/`advance` on it are what map a click to a character and back |
| The chrome seam | `face()`/`toff()`/`bgheight()` (`// addon:`) — the `"textentry"` rule's surface under the line and its frame over it, each replacing the statics above only where the rule names it, plus the padding the text and the click maps both shift by |

> **A control's own `:value(v)` write has to go through `rsettext`, never `settext`.** The stock class uses
> `settext` for everything (construction included calls `rsettext`, but every later native caller uses
> `settext`), and that path notifies `changed` — so writing a value the "obvious" way re-enters an adapter's
> own change handler, exactly the feedback loop every other value-bearing control in this catalogue also has
> to avoid, just reached from a buffer object instead of a field.

## `Scrollport` — composition over `Scrollbar` + `Scrollcont`, and a sealed bar

`Scrollport` is not extended by an adapter: its constructor builds `bar`
 as a **fixed anonymous `Scrollbar`** whose only override is
`changed()` (`cont.sy = bar.val`), so a subclass has no seam to make that same object notify Lua too. An
adapter instead rebuilds the shape from `Scrollport`'s own public pieces.

| What | Where |
|---|---|
| The inner container | `Scrollcont`, `public static` — reusable directly; its clip+scroll draw is `draw(GOut)`, offsetting each child by `-sy` via `xlate` and skipping one whose translated box misses the port entirely |
| The bar's range, auto-derived | `Scrollcont.update()` (the constructor's override) sets `bar.max = max(0, contentsz().y + 10 - sz.y)` — runs from `Scrollcont.add` only, **not** from a later `resize()` on an existing child, so a child's final size must be set before it is added |
| The wire-protocol redirect | `Scrollport.addchild` forwards into `cont.addchild` — **`Widget.add` does NOT call `addchild`**, so any Java caller adding straight into a `Scrollport` (not through this override) drops the child beside the bar instead of inside `cont` |
| Wheel + resize | `mousewheel` is `bar.ch(ev.s * UI.scale(15))`; `resize` re-anchors `bar` to the right edge and resizes `cont` to `sz` minus the bar's width |

> **The `:parent(w)` write is the one Java call site outside `Scrollport` itself that adds a child into one.**
> It goes through `Widget.add(child, Coord)`, never `addchild`, so a parent-shaped like `Scrollport` needs its
> OWN `instanceof` branch there redirecting into `cont` — the addchild override above does not cover it.

## `SListWidget`/`SListBox` — the model-backed contract

`SListWidget<I, W>` demands exactly two overrides —
`items()` and `makeitem(I, int, Coord)` —
and `sel` (`public I`) plus `change(I)`
(`this.sel = item`, nothing else) are the whole selection state. `SListBox<I, W>` adds scrolling over that.

| What | Where |
|---|---|
| Row widgets are built LAZILY | `SListBox.update()`, called from `tick(dt)` every frame — **not** from `items()`/`change()` directly, so a row from `:rows(t)` does not exist as a widget until the next tick |
| The ready-made rows | `TextItem.of(sz, Supplier<String>)` and `IconText.of(sz, Supplier<BufferedImage>, Supplier<String>)` — both plain `Widget`s, neither wired to `change()` on their own |
| The click-to-select wrapper | `ItemWidget<I>` — its `mousedown` takes button 1 alone and routes to `clicked(ev)`, whose body is `list.change(item)`, or `change(null)` when `toggle()` marks the row as deselecting on re-click (the base returns false); `makeitem` must wrap a bare `TextItem`/`IconText` in one (added as its own child) for a click to select anything |
| Deselect on empty click | `SListBox.unselect(button)` calls `change(null)` for button 1 when `mousedown` finds no `slotclick` — a REAL interaction, not one an adapter's own `:value(v)` should suppress |
| The one native list always reachable | `MenuSearch.Results` — the action search (`GameUI.srchwnd`, `kb_srch` = Ctrl+Z), which the client keeps in the tree and merely `hide()`s. Its rows are anonymous `ItemWidget` subclasses that call `super.mousedown(ev)` before their own double-click-to-use logic, so anything riding that method sees them |
| Fork: whose selection a row click is | `// addon:` `SListWidget.slistowner()` returns `this`, overridden in `SDropBox.SDropList` and `SListMenu.InnerList` to name the enclosing control — `ItemWidget.list` is the INNER list in both cases, and a popup is not even a child of its box |

> **A programmatic write must not call `change(I)`.** It is the single hook BOTH a real click
> (`ItemWidget.mousedown`) and the click-away deselect reach, with no lower-level "just set `sel`, don't
> notify" seam — so anything selecting a row without announcing it writes the `sel` field directly, and
> `change(item)` stays what a REAL click means.

## `SDropBox`/`SListMenu` — neither is an `SListWidget` itself, and neither wants an `ItemWidget` back

Both extend/wrap `SListWidget`'s contract one level removed, which is why the same `makeitem` result
(`SListWidget.TextItem`/`IconText`) is wrapped in an `ItemWidget` for `SListBox` but must NOT be for either
of these — the wrap happens inside their OWN inner list class instead.

| What | Where |
|---|---|
| `SDropBox<I, W>` IS an `SListWidget<I, W>` | but its `makeitem` must return the BARE `W`, not an `ItemWidget` — `SDropList.Item` (the popup's own row wrapper) and `SDropBox.change` (the closed-box widget) each wrap it themselves |
| The drop arrow is a real `ICheckBox` | `makedrop()` mints one and the constructor `adda`s it as a CHILD, with `state`/`set` replaced by lambdas (`dl != null` / `drop(boolean)`) — so it opens the popup and never runs `changed` |
| ...and a click on the BOX opens the list without touching it | `SDropBox.mousedown` falls through `ev.propagate(this)` to `drop.click()` **directly**, so only a click that actually lands on the arrow's own picture reaches `ICheckBox.mousedown` — two input sites for one activation, and the box one is the ordinary way a dropdown is opened |
| `SDropList.makeitem` | `new Item(item, SDropBox.this.makeitem(item, idx, sz))` — the outer `makeitem` supplies content, the inner list supplies the click wrapper |
| `change(I)` does DOUBLE duty here | `SDropBox.change` sets `sel` **and** rebuilds the closed-box widget (`curitem`, destroyed and re-`makeitem`'d) — unlike `SListBox`, there is no lower-level "just set `sel`" seam at all; a caller must run the SAME method's logic to keep the closed-box display in sync, so a control's own `:value(v)` calls `change(I)` directly and skips only its own notify wrapper (not the field write) |
| `makeitem(null, …)` is a REAL call | `SDropBox.change` calls `makeitem(item, -1, …)` with whatever it is given, including `null` (no selection) — an adapter's `makeitem` must handle it |
| `SListMenu` is NOT an `SListWidget` at all | it wraps one, `InnerList extends SListBox` as a private field (`box`) — `SListMenu.makeitem`'s result is wrapped in `InnerList.Item` the same one-level-removed way `SDropList` wraps `SDropBox`'s |
| `added()` grabs input UNCONDITIONALLY | `SListMenu.added()` — `ui.grab`/`ui.grabkeys`, gated only by the public `grab` field (default `true`); `nograb()` is the documented opt-out, meant for exactly this: a menu that is not a modal popup |
| Window raise vs. popup add-order | `Window.mousedown` raises itself AFTER `ev.propagate` returns — so a click that opens an `SDropBox`'s popup (added to `ui.root` DURING that propagate) is always followed by the enclosing window re-topping itself over it, same frame |

## `GridList` — DRAWS cells, does not build row widgets

`GridList<T>` is the one model-backed control with no `items()`/`makeitem()` —
its only abstract method is `drawitem(GOut, T)`, called straight from `draw`,
so an adapter never touches `SListWidget` at all. Layout is one or more `Group`,
a **non-static inner class whose constructor self-registers** (`groups.add(this)`,)
— there is no removal, so a different `itemsz` needs a whole new `GridList`, not a mutated `Group`.

| What | Where |
|---|---|
| `itemsz`/`marg` are `final` on `Group` |  — a cell-size change is D-113's "rebuild", the same shape a list's `:rowHeight(n)` already has |
| `marg.x < 0` means EVEN SPREAD | `adjx` spaces items across the full row width instead of a fixed gap when `marg.x` is negative — the engine's own icon-grid shape (`SkillWnd.SkillGrid`/`ExpGrid` both pass `(-1, 5)`) |
| `drawitem` runs for EVERY item, every frame, unconditionally | `draw(GOut)`'s item loop runs `sr*rw` to `items.size()-1` with **no per-item bound check against `sz.y`** — an item below the visible box still gets `drawitem` called, just clipped on screen; only `Group`-level visibility (`grp.ey - yo < 0`) skips a whole group |
| A `Loading` from one cell does not aim the whole draw | `draw` catches `Loading` PER ITEM and blits a placeholder — a wrapping addon callback (`drawitem` override) that raises anything else propagates to whatever calls it |
| Selection exists but is native-only | `change(T)`/`itemclick` set `sel` and draw a highlight (`drawsel`) on a real click — no Lua verb reads it (spec 040 ships no `:value()` on a grid) |
| Only button 1 selects, and `mousedown` does not know it | `mousedown` routes EVERY button through `itemclick(item, ev.b)`, whose whole body is `if(button == 1) change(item)` — so a right-click reaches the same method, changes nothing and goes on to the client's own context menu. The click-away is a separate branch in `mousedown` itself (`item == null && b == 1` → `change(null)`), not a call to `itemclick` |

## `TableBox` — the fifth model-backed control, and a constructor-order trap of its own

`TableBox<I>` demands `items()`/`spec()`/`itemh()` — all called from ITS OWN
constructor, before an adapter subclass's own field initializers run, so
a plain `this.x = x` in the subclass constructor body reads back its default the one time `spec()`/`itemh()`
actually need it.

| What | Where |
|---|---|
| Columns are fixed at construction | `cols`/`main` are `public final`, built once from `spec()` — the same "no live setter" shape [row height](#slistwidgetslistbox--the-model-backed-contract)/[cell box](#gridlist--draws-cells-does-not-build-row-widgets) already have |
| A cell is built PER COLUMN, per row | `Row`'s constructor calls `ColSpec.makecell` once for every column; `MainList`, an inner `SListBox`, is what runs it — lazily, from the same uncaught per-frame tick the model-backed contract's row-widget note above already covers |
| `widths()` skips its own flex math at `flexw=0` | `widths()` redistributes stretch space by `ColSpec.flexw()`; with every column's `flexw` at `0` (this bridge's own choice — no stretch columns), `c.w` reduces to exactly `fixw()`, independent of the widget's own `:size()` |
| `MainList` carries its own `Scrollbar` | `MainList extends SListBox` inherits its auto-scrollbar, so a suite walking its children for "the row widgets" must filter `type()=="Scrollbar"` out, same as any bare `:list()` |

> **The constructor-order trap.** `spec()`/`itemh()` run on `this` while `TableBox`'s OWN constructor is
> still executing — before control ever returns to the adapter's own constructor body. The fix costs no
> `haven` edit: the adapter's factory method returns an ANONYMOUS subclass overriding both, capturing the
> column spec and row height as locals of that factory — the compiler assigns an anonymous class's
> captured-variable fields before it calls its OWN super-constructor, which is exactly early enough for
> `TableBox`'s constructor, one level up, to see them. Confirmed with a throwaway, `haven`-free Java repro
> before trusting it in the real adapter.

