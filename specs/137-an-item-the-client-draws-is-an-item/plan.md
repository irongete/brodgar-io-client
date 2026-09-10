# 137 — plan

## Approach

**One function decides what an icon draws: `LuaWidget.itemOf(Widget) → ItemInfo.SpriteOwner | null`.**
Four rules, in order: `WItem` → `.item`; `Makewindow.SpecWidget` → `.spec`; `BAttrWnd.ItemIcon` →
`.spec`; a widget that is itself an `ItemInfo.SpriteOwner` and not an `AWidget` → itself. Everything that
today tests `instanceof WItem` asks it instead: the `item` verb (`LuaWidget`, `m.set("item")`), the role
table (`LuaWidget.role`, the line before `Window`), and `witems(Widget)`, which becomes `icons(Widget)` —
a deep `children(Widget.class)` filtered by `itemOf`. `LuaItem.items(container)` dedupes on the owner, as
it dedupes on the `GItem` now. `deepItems` and `WidgetSubs`'s `ItemAdded` stay `GItem`-only: a container
lifecycle is a `GItem`'s.

**The handle holds the owner and the icon.** `LuaItem` gets `final ItemInfo.SpriteOwner owner` and
`final Widget icon` in place of `final GItem wdg` (for a `GItem`, `icon == owner`). Minting is
`of(Addon, owner, icon)` from the widget verbs; the seam's `fireItem` calls `of(Addon, owner)`, which
re-mints from the icon the `Cache`'s `Ref` remembers (`Ref.icon`, strong, retired with the entry) and
answers `NIL` when no entry exists — no subscription can exist without a prior mint. `Cache.live` is
`IdentityHashMap<ItemInfo.SpriteOwner, Ref>`. Liveness: `live(h)` is `LuaWidget.live(h.icon)`-style
reachability (`icon.ui.root` and `hasparent`) — for a `GItem` that is the test it has today.

**Reads branch on `owner instanceof GItem`, and only where the `GItem` has something the interface lacks.**
`res` and `toString` go through `resource()`; `CharApi.itemResOf`/`itemNameOf` widen to `SpriteOwner`
(every `GItem` caller compiles unchanged). `quantity` drops the `GItem.num` half and `progress` the
`GItem.meter` half for a non-`GItem`; `cell`, `slots`, `handle`, `container`, `contents` answer their
absence before touching a `GItem` member; `snapshot` follows. `target(self, verb)` refuses a non-`GItem`
before the stale test: *"item:drop: a depiction is drawn, not held — it is a listing, a recipe slot or a
price, and there is no message to send"*. The `Refusal` is written inside the verb, as the grammar says
for a reshape.

**Retirement is the disposal drain, generalised.** `AddonManager.drainDisposedWidgets`'s kind test gains
`itemOf(w) != null`; `Addon.dropInternedHandles(w)` resolves the owner through `itemOf` and calls
`items.retire(owner)` (`contents` and `studySlots` stay `GItem`); `Addon.dropItemSubs` and `itemSubs`
take a `SpriteOwner` (`Interned<ItemInfo.SpriteOwner, Subs>`). The removal drain's 104 line
(`if(w instanceof GItem) dropItemSubs`) is left alone: a removal is a detach (128).

**The seam.** `ItemInfo.buildinfo(Owner, Raw)`, after its `try/finally` (outside the `"tooltip"` font
scope): `if((owner instanceof SpriteOwner) && !(owner instanceof GItem) && outermost)
AddonManager.onItemInfo((SpriteOwner)owner)`. *Outermost* is a `ThreadLocal<Set<Owner>>` identity set
entered before `buildinfo0` and left in the `finally`; a nested call for the same owner (the `cont`
factory's `sub`) builds and stays silent. A throw never reaches the seam.
`AddonManager.onItemInfo(ItemInfo.SpriteOwner)`: a `GItem` files under `queueState(it.ui)` as now; any
other owner resolves `Session s = owner.fcontext(Session.class, false)` inside the existing
`try/catch(RuntimeException)`, then the state in `allStates()` whose `ui.sess == s`; none → dropped.
`SessionState.itemInfos` becomes `Queue<ItemInfo.SpriteOwner>`; `drainItemInfos` and `fireItem` follow.
`GItem.info()` keeps its call and `fontrebuild` guard untouched. `anyItemSubs`/`recountItemSubs` keep
their fast path.

**Pages.** `ui/items.md` states what an item is (a thing the client draws, found through its icon), the
`widget:item()` row names the four icons, the stale paragraph covers a depiction, "An item arrives before
it can be described" says a recipe slot is described at `Added`, and a short section **A depiction that is
not an item** lists the absences and the refusal. `selectors.md`'s `item` row, `references.md`'s Item
section, `types/items.md`'s intro, `craft.md`'s `spec:res()` row (its twin). `docs/client/state.md`: the
describable row rewritten (two seams, no font-change fire), one row for the three drawing widgets and the
`SpriteOwner` family.

## Files to create / modify

- `src/haven/ItemInfo.java` — the seam and the nesting guard, `// addon:`
- `src/io/brodgar/addon/LuaWidget.java` — `itemOf`, `icons` (was `witems`), `item`, `role`
- `src/io/brodgar/addon/LuaItem.java` — owner + icon, `Cache`, `Ref.icon`, `live`, every read, `target`
- `src/io/brodgar/addon/LuaContents.java`, `CharApi.java` — `SpriteOwner` where a `GItem` was only a name
- `src/io/brodgar/addon/Addon.java` — `itemSubs`, `dropItemSubs`, `dropInternedHandles`
- `src/io/brodgar/addon/AddonManager.java` — `onItemInfo`, `itemInfos`, `drainItemInfos`, `fireItem`,
  `drainDisposedWidgets`
- `docs/addons/api/ui/items.md`, `ui/selectors.md`, `references.md`, `types/items.md`, `craft.md`
- `docs/client/state.md`
- `addons/137-an-item-the-client-draws-is-an-item.{1,2,3}/`

## Risks & gotchas

- **`Makewindow.SpecWidget`'s constructor calls `spec.opt()` → `info()` → `buildinfo`**, so a recipe slot
  is described before it enters the tree and `Changed` never fires for it after `Added`; the page's
  example already reads first and subscribes second. `BAttrWnd.ItemIcon.text()` → `spec.name()` builds at
  first draw, likewise before any handle exists. The arrival half of the seam is therefore observable
  natively only on a `.res` owner that revises (`Shopbox`); 137.3's suite scores it over a window.
- **`ItemSpec.context` delegates to whatever `ctx` it was built with** (`uictx.curry(ui)`, a `GItem`, a
  `.res` widget's own resolver): resolve `Session` only, and catch `RuntimeException`.
- **`OwnerContext.ClassResolver.get` matches by `isAssignableFrom`**, so `fcontext(Widget.class)` on a
  `Makewindow.Spec` answers the *window*, not the `SpecWidget` — which is why the icon is captured at the
  mint and never derived from the owner.
- **`Widget.children(Class)` is deep and excludes the receiver**; `itemOf` on the receiver itself is the
  `item` verb, never part of `items()`.
- **`typeName`/`@Class` report the nearest named class**: `SpecWidget`, `ItemIcon`, `WItem`, `ItemDrag`.
- **`ISlots.SItem` builds two `ItemSpec`s per gilding slot inside the parent's build** — the seam fires
  for each (queued, no subscriber, dropped by `fireItem`); they never reach a handle.
- **A `Makewindow.Spec` is an inner class**: a kept handle pins the `Makewindow` — the same pin a `GItem`
  handle puts on its `UI`.
- **`tools/widgetstate.py` reads every widget-keyed `Interned`'s note**; `itemSubs`' key type changes,
  so run it and `tools/docverbs.py` + `tools/refusalverbs.py` (there is no `retiredverbs.py`).
- Java 8: no `var`, no `Set.of`; `ThreadLocal.withInitial` is fine.

## Discarded alternatives

- **A bus `ItemAdded`/`ItemRemoved` beside the selector watch** — a second door to the same widgets, and an
  `Added` with no `Removed` behind it: a non-widget owner has no death of its own to announce.
- **A global registry owner → icon written by the entry seam** — a third map to keep in step with the
  caches for a join the mint already has in hand, since a depiction is minted only from its icon.
- **Weak keys for the intern cache** — a handler closing over its key keeps the key alive (128's finding),
  and "not yet collected" is not "still drawn".
- **One universal seam in `buildinfo` that also fires for a `GItem`** — the `GItem` list is complete only
  after `addcontinfo` and the pagina row, the `fontrebuild` flag is invisible to a static, and the `cont`
  sub-list would fire it twice.
- **Deduplicating by `Raw` identity to tell a font rebuild from a revision** — only a `GItem` rebuilds on
  a font change and it keeps its own seam; what nesting needs is the outermost-build guard.
- **Making a tooltip-internal `ItemSpec` an Item** — two objects per gilding slot, rebuilt per revision,
  drawn by no widget: no address and no end.
- **`:handle()` answering a `.res` widget's own id** — that number addresses the widget's protocol, not an
  item's, and the four verbs would have a number to send to and nothing correct to send.
- **Retiring at the removal drain** — a removal is a detach; a re-homed icon is alive one line later.
- **Quality inputs and tools as depictions** — bare `Indir<Resource>` icons painted by `Makewindow.draw`,
  with no tooltip list to describe and no widget to address.
