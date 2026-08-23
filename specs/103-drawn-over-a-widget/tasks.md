# 103 — Tasks

- [x] **103.1 — `widget:item()`, the item an icon draws.** Adds one read on the Widget object:
      the interned Item a `WItem` draws, `nil` on every other widget and on a stale one. It reads
      `WItem.item`, which is public and final and set in the constructor, so the read answers from the
      moment the placement seam offers the widget. Rows on `api/ui/widget.md` (Read, and the verb
      roster its refusal lists) and on `api/ui/items.md` (Read) — one line each, so neither page moves
      past its ceiling.
      *Its suite* takes the first icon of `s:ui():inventory():matchAll("@WItem")` and asserts
      `icon:item()` is `==` the entry of `inv:items():list()` carrying the same `:res()` — identity,
      not equality of fields, because interning is the claim. It asserts `nil` on the inventory grid
      itself, on the window around it, and on a bare widget the suite builds; that `icon:items()` is
      still empty, which is why this verb exists; and that `icon:item(1)` raises naming the verb. With
      an empty backpack it prints one `[fail]` naming the precondition rather than skipping.
      `[manual]`: none.

- [ ] **103.2 — One page for the UI overlays.** Docs only, no Lua and no Java. Creates
      `api/ui/overlay.md` and moves `hafen.ui():overlay()` into it out of `api/ui/custom.md`, which
      keeps the two builders. The new page states once what both receivers share and what this feature
      then extends: the collection, the key per addon, `:list()` as the draw order, the `g` the painter
      gets, and that a painter is unprotected. Re-points every link the spec's grep listed, adds the
      leaf to `api/README.md` and `api/ui/README.md`, and re-points `api/overlay.md` and
      `guides/custom-ui.md`, which each name the HUD painter as the only other receiver.
      *Its suite* runs every example the new page prints, in order, and asserts each does what the page
      says: an overlay added under a key is `:get`-able, `:list()` reports it, a second `:add` on the
      key leaves one member, `:remove` empties it. A page whose examples are not run is a page nothing
      checks.
      `[manual]`: read the top-of-screen banner the page's own example draws — expect yellow text,
      centred, over the HUD.

- [ ] **103.3 — What is drawn over one widget.** The record, its two lists, the seam and the first
      kind. A `// addon:` field on `Widget` holds that widget's records; `Addon` holds its own for the
      census and the teardown. The paint is one call after `wdg.draw(g2)` in `Widget.draw`'s child
      loop, outside the style frame, plus the root's own call in `UI.draw`. Ships `widget:overlay()`
      whole — `:add` `:get` `:remove` `:list` `:count` `:find`, members interned, the collection a view
      — and the `:draw(fn)` kind, called with `(g, w, h)`. Writes its half of `api/ui/overlay.md`, the
      `widget.md` row, and — in this task, which had to read them — the `super.draw` trap and the seam
      row on `docs/client/widgets.md`, splitting the traversal half into `docs/client/widget-draw.md`
      because that page is at its ceiling. It discharges the feature's impact set, the edge section of
      `api/ui/style/README.md` included, where "not a rule" now has an answer to point at.
      *Its suite* attaches a painter to a widget it built, and asserts from a timer that the callback
      ran, which is the seam itself. It asserts `:add` on a live key replaces rather than adds,
      `:list()` order follows attachment, a bare overlay raises nothing and paints nothing,
      `:remove` of an unknown key is inert, two `:overlay()` calls are not `==` while two `:get(key)`
      are, and that `:add` on a destroyed widget raises naming the tree it left. It then destroys the
      widget and asserts `ov:exists()` is false.
      `[manual]`: with the suite's mark on your inventory grid, hide the window — expect the mark gone
      with it, and the mark cut off at the grid's edge rather than spilling past it.

- [ ] **103.4 — The label that costs one raster.** The `:text(s)` kind and its dressing:
      `:anchor(ax, ay)`, `:offset(x, y)`, `:color(c)`, `:font(h)`, `:background(c)`, each with a bare
      read. It is drawn in Java through `LuaGOut`'s per-addon text cache, with the background filled
      behind the measured raster and left out of the cache key, which already excludes colour. Adds the
      one-kind rule: an overlay says exactly one, and a second, different kind raises naming the first.
      Completes `api/ui/overlay.md` and the third callback row on `api/ui/drawing.md`.
      *Its suite* reads `hafen.client():profiling():textcache()` before and after a second of a live
      label, and asserts `misses` did not move while `hits` did — the claim is that the draw never
      rasterises again, and that pair is the only thing that can say so. It asserts every setter reads
      back what it wrote, that `:text` on a painter raises naming `draw`, and the reverse, and that
      `:anchor(0.5)` raises naming both arguments.
      `[manual]`: put an item in your backpack and read its icon — expect the quality centred on a
      black strip at the bottom of the slot, in the suite's own font, unchanged as the client redraws.
