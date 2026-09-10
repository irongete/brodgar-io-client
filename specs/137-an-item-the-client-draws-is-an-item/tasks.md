# 137 — tasks

- [x] **137.1 — A depiction is an Item, read through the icon that draws it.** `LuaWidget.itemOf(Widget)`
      is the one decision (`WItem`, `Makewindow.SpecWidget`, `BAttrWnd.ItemIcon`, a drawn widget that is
      itself an `ItemInfo.SpriteOwner`); `widget:item()` asks it. `LuaItem` holds `owner` + `icon`, the
      `Cache` is keyed on the owner and its `Ref` remembers the icon; `live` is the icon's reachability;
      `res`/`name`/`quality`/`durability`/`info` read through the interface, `quantity`/`progress` drop
      the `GItem` halves, the where-reads and `contents` answer absence, `target` refuses a depiction
      naming "drawn, not held". `Addon.itemSubs`, `dropItemSubs`, `dropInternedHandles` and
      `drainDisposedWidgets`'s kind test take the owner through `itemOf`. `CharApi.itemResOf`/`itemNameOf`
      widen. Pages: `ui/items.md` (the Item object, the stale paragraph, **A depiction that is not an
      item**), `types/items.md`, `references.md`. Criteria 1, 4, 5, 6.
      *Its suite* runs with a recipe open. It finds the slots by `:type() == "SpecWidget"` over
      `s:ui():matchAll("*")` (the role is 137.2's) and asserts `icon:item()` is non-nil, `==` on a second
      read, `:res()` a string, `:exists()` true, `:cell()`/`:handle()`/`:container()`/`:contents()` nil and
      `:slots()` empty, `:info()` a table with `res` and without `cell`; with `item.*` declared,
      `pcall(it.drop, it)` fails naming *drawn, not held*. A backpack item still answers `:cell()` and is
      `==` its `inventory:items():list()` entry. It keeps one slot's Item and, over a 20 s timer, prints
      pass when `:exists()` turns false while `:res()` still answers.
      `[manual]`: close the crafting window within 20 s of `:t137.1` -- expect: the timed line prints pass.

- [x] **137.2 — Found where it is drawn: the role, the collection, the door.** `LuaWidget.role` answers
      `item` exactly where `itemOf` answers; `witems` becomes `icons` (deep children filtered by `itemOf`)
      and `LuaItem.items` dedupes on the owner, so `widget:items()` on the crafting window lists its slots
      and `s:ui():on("item", "Added"/"Removed")` seeds and fires for them; `deepItems`/`ItemAdded` stay a
      container's. Pages: `ui/selectors.md`'s `item` row, `ui/items.md`'s read rows and opening, `craft.md`'s
      `spec:res()` sentence, and the **derived impact set** of `spec.md` discharged line by line, `widget-
      draw.md:45` included. Criteria 2, 3, 10, and the qmod/tools boundary.
      *Its suite* runs with a recipe open. `s:ui():on("item", "Added", fn)` seeds; it asserts the seeded
      icons of type `SpecWidget` number `#s:craft():inputs():list() + #s:craft():outputs():list()` and
      that the crafting window's `:items():count()` is the same number; that every seeded `WItem` count
      equals `inventory:items():count()` (a real container unchanged); that `icon:role() == "item"` on a
      slot and `nil` on the window; over 20 s, that `Removed` fires once per slot icon and each kept Item
      then answers `:exists()` false, and `matchAll("item")` no longer lists them.
      `[manual]`: close the crafting window within 20 s of `:t137.2` -- expect: the timed lines print pass.

- [x] **137.3 — Changed reaches every depiction, once per build.** The seam at the end of
      `ItemInfo.buildinfo` for a non-`GItem` `SpriteOwner`, behind the outermost-build guard;
      `AddonManager.onItemInfo(ItemInfo.SpriteOwner)` files a `GItem` under its `ui` and any other owner
      under the state whose `ui.sess` is its `Session`, dropping what resolves none;
      `itemInfos`/`drainItemInfos`/`fireItem` on the owner. `GItem.info()` untouched. Pages:
      `ui/items.md`'s **An item arrives before it can be described** (a recipe slot is described at
      `Added`; a `.res` depiction fires as its owner rebuilds), `docs/client/state.md`'s describable row
      rewritten (two seams, no font-change fire) plus the drawing-widgets row. Runs `tools/docverbs.py`,
      `tools/refusalverbs.py`, `tools/widgetstate.py`. Criteria 7, 8, 9.
      *Its suite* runs with a recipe open. On every `item` icon up it subscribes `Changed`, then reads
      `:name()` on each and, over 2 s, asserts an icon already described fired **zero** times for the read;
      it installs a sheet rule (`hafen.ui():sheet():rule("window.title"):font(...)`, `install`, `release`)
      and asserts, over 1 s, zero `Changed` on every icon, `WItem` and slot alike; `:on("Changed")` on a
      kept stale Item returns a `Sub` and never fires. The arrival half is scored over a 10 s window: an
      icon whose `:name()` was nil at subscribe and is a string at the end must have fired exactly once,
      and the summary says how many such icons the run reached — zero is reported, not passed.
      `[manual]`: open a barter stand during the 10 s window -- expect: "arrival: N icons, each fired once"
      with N > 0.

- [ ] **137.4 — A depiction's `Changed` carries the depiction.** `LuaItem.Cache.drain()` takes the whole
      entry when Lua releases a handle, and the `Ref` takes `icon` with it — so the `of(Addon, owner)` that
      `AddonManager.fireItem` calls answers `NIL`, and a handler is handed `nil` where `ui/items.md` promises
      the very object it subscribed on. A `GItem` is immune, being its own icon; a `.res` owner that revises
      is not, and a `Shopbox` revises. `drain()` stops unmapping and clears the collected value alone — the
      icon stays named — because `Cache.retire` at the icon's **disposal** already bounds the map for every
      icon kind, `drainDisposedWidgets` taking the owner through `itemOf`: that is the bound the entry's own
      comment claims, and the reference queue was buying a second one at the cost of the pair. `fireItem`
      refuses to fire a payload that is not an Item rather than passing one on. Pages: `ui/items.md`'s
      `:on("Changed", fn)` row and its payload sentence, which this is what makes true. Criterion 7.
      *Its suite* runs with a barter stand open. It subscribes `Changed` on every `item` icon whose
      `:type()` is not `WItem` **without keeping the Item**, holding nothing but a counter the handler
      writes; it then drops every reference, runs `collectgarbage("collect")` twice, and asserts
      `icon:item()` still answers and is `==` a second read. Over a 10 s window it asserts every payload
      it is handed is a userdata answering `:res()`, counting how many the run reached — zero is reported,
      not passed — and that a `WItem` payload is the same object in the same run.
      `[manual]`: buy or browse at the barter stand inside the 10 s window -- expect: "payload: N depictions,
      each an Item" with N > 0.
