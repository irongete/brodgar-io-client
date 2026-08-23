# 103 — Plan

## Approach

**One record set, two views.** An attachment is a record carrying its owner addon, its key, its kind and
that kind's configuration — the shape `LuaGobOverlay.Attach` already has. Each record is in two lists:
the **widget's own**, which is the draw order, and its **addon's**, which is the census and the
teardown. Neither is derived from the other, and `hafen.ui():overlay()` already lives on exactly this
pair (`Addon.hudOverlays` is both its `:list()` and what `UiApi.paintHudOverlays` walks).

**The widget's list is a field on `Widget`, not a map.** `// addon:` one reference, null on every widget
that carries none, so an unarmed client pays a null check inside a loop it already runs.
`Widget.prof`'s own comment states the alternative's cost — a hash lookup per widget per frame — and
this feature is armed for the whole session by construction, which is precisely the case that note
rules out.

**The paint is one call at the one draw seam.** `Widget.draw(GOut, boolean)`'s child loop already
translates and clips (`g2 = g.reclip(cc, wdg.sz)`), already skips `!wdg.visible`, and already opens the
per-widget style frame. Our call goes **after** `wdg.draw(g2)` and **outside** `Fonts.frame`'s
try-with-resources, so an overlay paints over its widget and is not restyled by the sheet the user put
on it. The root is reached by no loop, so it gets the same second call site in `UI.draw` that
`Fonts.frame(Widget)` already has there.

**Two kinds, and the declarative one never enters Lua.** `:draw(fn)` calls back through
`AddonManager.callLua` with `(g, w, h)` — watchdog-armed, error-isolated, cost-attributed, exactly the
HUD painter's contract. `:text(s)` is drawn in Java through `LuaGOut.label`, the same per-addon rendered
text cache `g:text` goes through, so a label costs one rasterisation for its lifetime; the
`:background(c)` fill is a `frect` behind the measured raster and stays out of the cache key, which
already excludes colour.

**The join is one verb.** `widget:item()` reads `WItem.item` (public, final, set in the constructor) and
hands back the interned `LuaItem`; every other widget answers `nil`. It is the read the icon could not
answer because `Widget.children(Class)` is recursive but **excludes the receiver**, which is what makes
`widget:items()` empty on an icon.

**Docs.** `api/ui/overlay.md` becomes the one page for the UI overlays, both receivers, so the shared
vocabulary — the collection, the key, the draw order, the `g`, the cost — is stated once;
`hafen.ui():overlay()` moves there out of `custom.md`, which keeps its surfaces.

## Files to create and modify

Create: `src/io/brodgar/addon/LuaWidgetOverlay.java` · `docs/addons/api/ui/overlay.md` ·
`docs/client/widget-draw.md` (the traversal half of `widgets.md`, which is at its ceiling) ·
`addons/103-drawn-over-a-widget.{1,2,3,4}/`.

Modify: `src/haven/Widget.java` (the field, the call in the child loop) · `src/haven/UI.java` (the
root's call) · `LuaWidget.java` (`:item()`, `:overlay()`, the events list, the verb roster in its
refusal) · `LuaGOut.java` (a label that measures, fills and blits) · `Addon.java` /
`AddonRegistry.java` (the owner list and its teardown) · `UiApi.java` (the paint entry points) ·
`docs/addons/api/ui/{custom,widget,items,drawing,pixels,README}.md` ·
`docs/addons/api/{README,overlay}.md` · `docs/addons/api/ui/style/README.md` ·
`docs/addons/guides/custom-ui.md` · `docs/client/{widgets,README}.md` · the anchor links the spec lists.

## Risks and gotchas

- **`WItem.draw(GOut)` never calls `super.draw(g)`**, so a `WItem`'s children are never painted. It
  does not touch this seam — the parent's loop paints the icon and then our overlay — but it is the
  fact that kills the adopted-child route, it costs a day to find, and `docs/client/widgets.md` does
  not state it. The task that reads it writes it there.
- **A closing `Window` fades**: `Window.reqdestroy` sets `animst = "dest"` and lingers in the tree, so
  an overlay under it goes on painting for the animation. Correct, and a line on the page.
- **`GItem.info()` throws `Loading`** until an item's info resolves (`LuaItem.info` guards it), so
  `item:quality()` is `nil` for the first frames after an icon appears. Documented on `items.md`
  already; the overlay pages must not imply otherwise.
- **Design pixels**: `wdg.sz` and `g2` are device, everything Lua reads or writes is `Px.out`. The
  anchor is a fraction and needs no conversion; the offset does.
- **The label path must go through the counted cache.** `LuaGOut.blitText` is what `hits`/`misses`
  count; a background drawn by a second, uncached path would make the acceptance check pass while the
  raster is rebuilt.
- **Teardown has two ends**: dropping the addon's list is not enough, each record must leave its
  widget's field, or a disabled addon goes on painting until that widget dies.
- **The paint runs inside `UI.draw` under `synchronized(ui)`**; Lua writes run on the UI thread.
  Copy-on-write, like `Addon.hudOverlays`.

## Discarded alternatives

- **A control adopted into the target** (`hafen.ui():widget():parent(w)`) — a class that overrides
  `draw(GOut)` without calling `super.draw` never draws its children, and the first receiver anyone
  reaches for is exactly such a class; it also puts a widget in the client's own hit-test path.
- **A `Draw` subscription on a native widget** — it makes every widget a surface and every decoration a
  per-frame Lua call, with no declarative half at all: a subscription cannot carry a kind.
- **A stylesheet property that paints a badge** — a rule is a state resolved per property, not a
  painter, and a tree key on anything that is not a window, panel, button or field is inert by design.
- **An `IdentityHashMap<Widget, …>` behind a global armed flag** (the `Fonts.treed` shape) — one hash
  lookup per widget per frame while armed, and this feature is armed for the whole session.
- **Retiring `hafen.ui():overlay()` into the root widget's collection** — the HUD painter is addressed
  at the screen and a root is addressed at one character, so the hard-cut would quietly change what a
  two-session client draws, and it would pull the layer-versus-session draw order into this feature.
- **`item:overlay()` instead** — an item worn in two slots is drawn twice, so one key would name two
  rectangles and every read on it would have to answer twice.
- **Painting inside the widget's `Fonts.frame`** — an addon's own label would change font because the
  user themed the window underneath it.
- **Growing `custom.md` with the second receiver** — that page is the surfaces you *build*, and an
  overlay on a widget you did not build is not one.
- **Splitting `api/ui/widget.md`, which stands at 312 lines against a 300 ceiling** — it was already
  309 before this feature, and `DOCUMENTATION.md` §11.2 charges the split to the writing that pushes a
  page over. Three rows of this feature's sit on it; none of them is what put it there, and a split
  priced by that page's inbound anchors is its own piece of work.
- **`:info()` and `:kind()` on the HUD painter**, which answers only `:key() :draw() :exists()` where
  the widget's overlay beside it on the same page answers both — the grammar's "every live object
  answers `:info()`" is unmet there. No task of this feature adds or changes a verb of
  `hafen.ui():overlay()`, so it is not this feature's surface to complete, and the asymmetry is
  visible now only because 103.2 put the two receivers on one page.
