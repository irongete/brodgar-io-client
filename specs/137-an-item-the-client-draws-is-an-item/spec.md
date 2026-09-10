# 137 — An item the client draws is an item

## What & why

Every way the API reaches an item starts at a **`WItem`**: `widget:item()`, `widget:items()`, the `item`
role and the "can be described" seam in `GItem.info()`. That covers `Inventory`, `Equipory` and the cursor,
and is blind everywhere else.

The client models an item otherwise. The server pushes a **`GItem`**; a `WItem` is what a container mints
to draw one. A second kind of depiction is no `GItem` at all: a resource + `sdt` + an `ItemInfo` list held
by whoever draws it — `ItemSpec` (shipped *for `.res` code*) and `Makewindow.Spec`. The engine's name for
the family is **`ItemInfo.SpriteOwner`**; those three are its only implementors in `haven`, and a `.res`
may bring a fourth: `res/ui/barterbox`'s `Shopbox extends Widget implements ItemInfo.SpriteOwner`, holding
an `ItemSpec price`. Nothing fires, lists or reads.

Two facts, verified in `src/`, fix the design:

- **A depiction is always drawn by a widget, and that widget is its address.** `Makewindow.Spec` is drawn
  by `Makewindow.SpecWidget`, which `Makewindow.uimsg` **destroys** on a recipe change; the constipation
  icon is `BAttrWnd.ItemIcon`; a `.res` depiction is a widget that *is* the owner. A `GItem` is an
  `AWidget`, never drawn; its `WItem`s are its address today. So the tree's universal entry and disposal
  seams already say where a depiction is and when it went, and the selector watch on the `item` role is
  its door — seeded, per session, reaching a `.res` window the day its class loads, because a role is an
  `instanceof` against `haven`'s interface. **No new door.**
- **Every description funnels through `ItemInfo.buildinfo(Owner, Raw)`** — resource code has no other
  door. That is the seam for a non-`GItem` owner. A `GItem`'s stays in `GItem.info()`: its list is complete
  only after that method appends the contents and pagina rows, and the font-rebuild suppression lives there.

An `Item` is interned on the `SpriteOwner`, found through the widget drawing it, ended by that widget's
death — a `GItem`'s shape today, with `WItem` generalised to "a widget drawing one depiction".

## Acceptance criteria

1. **A depiction IS an `Item`.** Same object, verbs and `:info()`, interned on `ItemInfo.SpriteOwner`. No
   second type, nothing renamed or retired. `:res()` reads `resource()`; `:name()`, `:quality()`,
   `:durability()` read the built list.
2. **It is found where it is drawn.** `widget:item()` answers on every widget drawing one depiction:
   `WItem`, `Makewindow.SpecWidget`, `BAttrWnd.ItemIcon`, and any drawn widget that is itself a
   `SpriteOwner`. The `item` role is exactly "`widget:item()` answers"; `widget:items()` is the deep
   collection of those answers, one per depiction. An `AWidget` is never an icon.
3. **The door is the one that exists.** `s:ui():on("item", "Added" / "Removed", fn)`, seeded, per
   session. **No bus event is added.**
4. **A real item is unchanged**: the same interned object, `==` to `icon:item()`, every where-read, the
   four protected verbs and its `:exists()` test as today.
5. **A depiction exists while its icon is in the tree, and dies with it.** It is minted only from the icon
   drawing it, so the handle captures that icon: `:exists()` is the icon's reachability, and every map keyed
   on a depiction — the per-addon intern cache, the `Changed` subscriptions — drops its entry at the icon's
   **disposal**, the seam retiring a `GItem`'s (`dropInternedHandles`, `dropItemSubs`). A stale depiction
   is a stale item: `:res()` and `:name()` answer, `Changed` is inert. **Nothing grows**: open and close a
   recipe and every map is back to its size; a kept handle pins the depiction alone.
6. **One that is not an item says so.** `:cell()` nil, `:slots()` empty, `:handle()` nil, `:container()`
   nil, `:contents()` nil; a protected verb refuses naming why (drawn, not held — nothing to send);
   `:quantity()` and `:progress()` read the tooltip rows alone.
7. **`Changed` reaches every depiction, once per build.** For a non-`GItem` owner the seam is the
   completion of the **outermost** `buildinfo` for that owner (a `Contents` row builds its `sub` through
   the same static, same owner): once per arrival, once per revision, never per frame, never for a font
   change — a `GItem` keeps its seam and B15's suppression, and no other owner rebuilds on a font change.
   `onItemInfo` takes a `SpriteOwner`.
8. **Every depiction names its character.** `fcontext(Session.class, false)` is the only key all four
   owner kinds share (`ItemSpec`'s `uictx` resolves neither `UI` nor `Widget`); the seam files under the
   state whose `ui.sess` is that `Session`, catches `RuntimeException`, and drops what it cannot place.
9. **A depiction inside a tooltip is not an item.** `ISlots` builds two `ItemSpec`s per gilding slot into
   a tooltip *image*; no widget draws them, nothing mints them. One queue entry each, nothing else.
10. **The oracle is the crafting window.** Opening a recipe makes each **input and output** arrive as
    `Added` on the `item` role, describable at once, on a background character too; a recipe change or
    close fires `Removed` and a held `Item` answers `:exists()` false. Quality inputs and tools are **not**
    depictions — `Makewindow.draw` paints bare resource icons; `s:craft()` keeps those reads.

## Out of scope

- **The barter stand's surface** — rows, price, Buy: `Shopbox` adopted with `@FromResource`, the next
  feature. Here `widget:item()` on a `Shopbox` is the product.
- **Writes on a depiction.** No message exists; criterion 6 is all that is owed.
- **A place for a depiction.** No lattice, no slot, no anchor of its own — the icon keeps the one it has.
- **`s:craft()`'s `spec`.** Not reshaped. Two views of one slot: `spec:res()` the **displayed**
  constraint, `icon:item():res()` the concrete item.

## Docs impact

Pages written: `docs/addons/api/ui/items.md`, `ui/selectors.md`, `references.md`, `types/items.md`,
`craft.md` (one sentence), `docs/client/state.md` — its **When an item becomes describable** row still says
a font change fires the seam, which B15 ended: rewritten, plus a row for the widgets drawing a depiction.

Derived impact:

```
grep -rn "item icon\|icon one item\|one icon draws\|wherever it is drawn" docs/
```

→ `font.md:151`, `ui/custom.md:227`, `ui/items.md:85`, `ui/overlay.md:5,94,181,197`,
`ui/selectors.md:164,183,184`, `ui/style/README.md:288`, `ui/style/surfaces.md:87`,
`guides/custom-ui.md:132`, `client/widget-draw.md:45`. Each read, then corrected or confirmed;
`overlay.md` and `custom.md` decorate a **widget** and are expected to stand.

## Context files

**H** = `src/haven/`, **B** = `src/io/brodgar/addon/`, **A** = `docs/addons/api/`.

- H `ItemInfo.java`, `GItem.java`, `ItemSpec.java`, `Makewindow.java`, `BAttrWnd.java` — 1, 3
- H `OwnerContext.java`, `Widget.java` (`add0`, `remove`, `destroy`) — 1
- B `LuaItem.java`, `LuaContents.java`, `CharApi.java`, `LuaHand.java` (its `hand:use(item)` resolves
  an Item and so owes the depiction refusal too) — 1
- B `LuaWidget.java` (`itemOf` — 137.1's one decision — plus `role`, `witems`, `typeName`),
  `Selector.java` — 1, 2
- B `Addon.java` (`items`, `itemSubs`, `dropItemSubs`, `dropInternedHandles`) — 1
- B `AddonManager.java` (`onItemInfo`, `drainItemInfos`, `fireItem`, `drainDisposedWidgets`,
  `queueState`, `allStates`) — 1, 3
- B `WidgetSubs.java` — 2
- A `ui/items.md` (the Item and its verbs), `ui/contents.md` (what one holds), `ui/container.md`
  (`ItemAdded`/`ItemRemoved`) — `items.md` was over the ceiling and 137.1 split it in three
- A `types/items.md`, `references.md` — 1, 3
- A `ui/selectors.md`, `ui/replace.md`, `craft.md`, the derived impact set — 2
- `docs/client/state.md` (its `SpriteOwner` and drawing-widget rows are 137.1's), `DOCUMENTATION.md` — 1, 2, 3
- `tools/widgetstate.py` — its second closure root is `ItemInfo.SpriteOwner` since 137.1 — 3
