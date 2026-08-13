# 064 — plan

## Approach

**A new interned type, `LuaContents`**, minted per-addon exactly as `LuaItem.Cache` mints Items and
keyed on the owning `GItem`, so `item:contents()` twice is `==`. It answers `nil` rather than an
empty object when the item has neither a contents widget nor a contents tooltip block, which is what
makes `nil` mean "holds nothing" and an empty `:items()` mean "empty container".

Its reads come from two unrelated places, and that is the whole point of the type:

- `:items()` and `:name()` from the **widget** the server pushed: `GItem.contents` and
  `GItem.contentsnm`.
- `:text()`, `:quality()` and `:level()` from the item's **tooltip info**: `ItemInfo.Contents.sub`
  for the first two, and the published `ui/tt/level` class for the third.

**`item:container()`** is the inverse, and the engine already carries the link. Climb `Widget.parent`
from the `GItem`; on reaching a `GItem.ContentsWindow`, its `public final GItem cont` is the
containing item, and climbing on from there gives a container inside a container. Reaching an
ordinary container widget instead means `nil`.

**The deep events** change two things inside `WidgetSubs` and nothing outside it. `offerPlaced`'s
membership test — today `w.hasparent(wdg)` — becomes that same `ContentsWindow` → `cont` walk, so an
item placed into a stack held in the subscribed container is recognised. `refreshItems` diffs a
**deep** item set from a new helper, leaving `LuaItem.items` untouched so `widget:items()` keeps its
meaning. The outermost-only rule falls out of one filter over the computed diff: an added or removed
item whose `:container()` is itself in the same batch is suppressed, so a stack arriving or leaving
with three things inside is one event and a thing dropped into a stack that stayed is one event.

**`:quantity()` and `:progress()` fold two sources each, in `WItem.draw`'s own order.** That method
is the authority and settles both: the icon's number is drawn from `itemols` — the
`GItem.OverlayInfo`s of `info()`, which is where `GItem.NumberInfo.itemnum()` lives — and **never**
from `GItem.num`; the arc is `(item.meter > 0) ? item.meter / 100.0 : itemmeter.get()`, that second
half being `GItem.MeterInfo.meter()`. So `:quantity()` reads `GItem.num` when it is not `-1` and the
`NumberInfo` otherwise, and `:progress()` mirrors the draw exactly and divides to `0..1`.

**`:durability()` reads a published class by name**, the technique `LuaItem.quality` already uses for
`QBuff`/`Quality`: walk `info()`, match the wear tooltip's class name, take its two fields
reflectively, and cache the lookup per class as `qfield` does. Naming the class rather than pinning a
copy means a revised resource makes this answer `nil`, never something wrong.

## Files to create and modify

| File | What |
|---|---|
| `src/io/brodgar/addon/LuaContents.java` | new — the type, its cache, its five reads and `:info()` |
| `src/io/brodgar/addon/LuaItem.java` | `:contents()`, `:container()`, `:quantity()`, `:progress()`, `:durability()`; `quality(GItem)` split so its list half serves the content's quality; `num`/`wear` gone from the snapshot |
| `src/haven/res/ui/tt/level/Level.java` | new — adopted with `haven.Resource get-code`, `@FromResource` version-pinned |
| `src/io/brodgar/addon/WidgetSubs.java` | the deep membership walk, the deep diff set, the outermost-only filter |
| `src/io/brodgar/addon/Retired.java` | `item:num` → `:quantity()`; `item:wear` → both `:progress()` and `:durability()`, saying which is which |
| `docs/addons/api/ui/items.md` | the verbs, the `Contents` type, the deep-event rule, the **Where the item reads end** rewrite, the line separating `item:progress()` from the control |
| `docs/addons/api/types.md` | the snapshot's new fields and what it omits |
| `docs/client/state.md` | the items row: the contents widget, its window, the `cont` back-link, the contents tooltip block, the fill-meter class, and that nothing draws `GItem.num` |
| `docs/client/glossary.md`, `docs/client/services.md` | the same `num` correction where each calls it the stack count |

## Risks and gotchas

- **The contents widget may mint no `WItem`s.** `LuaWidget.witems` is `w.children(WItem.class)`, but
  `GItem.addcontinfo` iterates `contents.children()` looking for `GItem` directly. Read the `GItem`
  children of the contents widget, not the `WItem` walk, or `:items()` answers **empty in silence** —
  the worst possible failure here. Confirm in-game which it is and record it in `docs/client/state.md`.
- **`ContentsWindow` hangs off `GameUI`, not the container.** `GItem.addchild` builds it under
  `contparent()`, which is `getparent(GameUI.class)`. That is why `hasparent` fails for the events,
  and also why a `widget:items()` taken from a node above the HUD already sweeps contained items in.
- **Hiding is not destroying.** `ContentsWindow.reqclose()` only does `chstate("hide")`; only
  `cdestroy` nulls `contents`/`contentsnm`/`contentsid`/`contentswnd`. Every read works with the
  window down. `wndshow` has exactly one caller, `GItem.uimsg "contopen"`, so no client can pin it.
- **`GItem.info()` throws a bare `Loading`** while the resource streams — not resolvable. Wrap every
  info read as `LuaItem.quality` already does and answer `nil`, never a half-built object.
- **`ItemInfo.Contents` is built only by resource code**, so match it by type; it is a `haven` class.
  On the wire it is `ui/tt/cont` and its payload is a **nested tooltip**, so `sub` may carry far more
  than a name — and a separate `ui/tt/cn` entry carries a content name, which is where to look if
  `contentsnm` comes back empty.
- **Pin `Level` by version** and check it with `haven.Resource find-updates src`.
- **The durability class name is read off the running client**, never guessed: use the `:lua` REPL or
  the inspector on a worn tool. It is also unknown whether that class implements `GItem.MeterInfo`;
  if it does, a worn tool paints an arc too and `:progress()` and `:durability()` describe the same
  wear by two roads. Say which in the docs once seen.
- **Threading**: item widgets arrive off the UI thread. Take the `ui` monitor for tree reads, as
  every neighbouring read already does.

## Discarded alternatives

- **`item:liquid()`** — the structures carry a rendered line and two numbers and no substance type, so
  the name asserted something the client is never told, and it would answer non-nil for any
  non-liquid container whose tooltip carries a contents block.
- **Two verbs, `item:items()` beside `item:contents()`** — a bag states a line *and* carries objects,
  so the caller would have to know which kind of container they hold before asking.
- **Flattening `widget:items()` so a stack's contents appear in the inventory** — it would break
  `:cell()` and `#items` as the count of slots used, and delete the difference between one stack of
  eight and eight loose things, which is the difference that decides space and transfers.
- **`widget:stacks()` beside it** — needs a stack/creel discriminator the client does not have; the
  server marks the difference only by whether it ever sends the message that pins the window open.
- **`item:parent()`** — `widget:parent()` already means the enclosing **widget**, and one verb naming
  two different relations by receiver is the ambiguity `:container()` avoids.
- **Subscribing on a `Contents`** — an addon cannot subscribe to every item a container holds, and a
  second door onto one arrival fires twice.
- **Reporting every contained item when a container moves** — four events for one movement, and it
  would make a subscription's seeding disagree with `widget:items()` from the first frame.
- **A plain table for `:contents()`** — a read hands back a live interned object with `:info()` as its
  only snapshot, and the table forced the awkward exception of a snapshot carrying live items.
- **`:quantity()` as its own feature** — it would leave `items.md` shipping a row that calls the
  retired verb the stack count, in the same page this feature rewrites.
- **Leaving durability unreadable** — nothing else reaches it: `widget:tooltip()` reads the
  `Widget.tooltip` **field**, which a `WItem` never sets, composing its tip by overriding the method
  instead. "The developer handles it" needs something underneath to handle.
- **Keeping `0..100` for the arc** — the verb is retired either way, and every other fraction in the
  API (`slot:progress()`, the progress control's value, `g:prect`) is spelled `0..1`.
