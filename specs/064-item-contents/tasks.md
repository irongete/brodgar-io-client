# 064 — tasks

- [x] **064.1 — an item says what it holds, and what holds it.** Adds `LuaContents`, interned per
      owning `GItem`, with `:items()` and `:name()` read from the `GItem.contents` widget and
      `GItem.contentsnm`; adds `item:contents()`, answering `nil` when the item has neither that
      widget nor a contents tooltip block; adds `item:container()`, the `ContentsWindow.cont` climb.
      The Item snapshot gains `contents` and carries no `container`.
      *Its suite* declares no permission, finds a **stack** by scanning the inventory for an item
      whose `:contents()` is not nil, and asserts what only this task can answer: two reads of that
      contents are `==`, `#:items()` is more than one, no entry is the stack, each answers `:res()`,
      and the round trip holds both ways — every entry answers the stack to `:container()`, while
      the stack and an ordinary item both answer `nil`. It asserts an item inside reads `:cell()`
      as `nil` and does **not** appear in `inventory:items()`, and that `item:info()` has a
      `contents` key and no `container`. It `pcall`s `item.contents(item)` and asserts the refusal
      names the colon call.
      `[manual]`: with no stack in the inventory the suite says so and scores nothing — put a stack
      of anything in it and re-run.

- [ ] **064.2 — a bucket says what it holds, which is not items.** Adopts `ui/tt/level` with
      `haven.Resource get-code` under `@FromResource`, version-pinned. Adds `contents:text()`,
      `:quality()` and `:level()`, read from `ItemInfo.Contents.sub` and that class; splits
      `LuaItem.quality(GItem)` so its list half serves the content's quality; completes
      `contents:info()` as the three of them, with **no** `items`. The items page gains the liquid
      block: what a liquid looks like from here — a stated line, a fill meter, no items — how
      `c:level()` asks it, and that the substance itself is never stated to the client.
      *Its suite* finds a liquid container the same way 064.1 finds a stack, and duplicates that
      task's identity assertions rather than assuming the other suite is ever run: the contents are
      interned, and `item:contents()` is nil for something holding nothing. Its own claims are that
      `:text()` is a non-empty string, `:level()` gives `cur` and `max` numbers with `cur <= max`,
      `:quality()` and `item:quality()` both read and are recorded side by side, `:items()` is an
      **empty array and not nil**, and `contents:info()` carries the three and no `items` key. It
      asserts a stack answers `nil` to `:text()` and `:level()`, so the two halves stay separable.
      `[manual]`: hover the container and confirm the printed `text` is the tooltip's own line, and
      that the printed `cur`/`max` match the fill the meter draws.
      <!-- extra context: `haven.Resource find-updates src` checks the pin -->

- [ ] **064.3 — an item entering a stack is an event on the container holding it.** Replaces
      `WidgetSubs.offerPlaced`'s `hasparent` test with the `ContentsWindow` → `cont` climb, diffs a
      **deep** item set from a new helper leaving `LuaItem.items` untouched, and filters the diff so
      only the outermost thing that moved is reported.
      *Its suite* subscribes on the inventory, records every `ItemAdded`/`ItemRemoved` with what it
      carried, and asserts automatically that the seeding equals `inventory:items()` exactly — same
      count, same objects — which is the claim the outermost rule exists to make true. It asserts a
      handler's item answers `:container()` so a payload can be placed, and it re-asserts that
      `inventory:items()` never lists a contained item.
      `[manual]`: take one thing out of a stack and drop it back in, then read back the printed
      log — expect one `ItemRemoved` and one `ItemAdded`, each naming the thing and not the stack.
      Then move the whole stack to another container — expect exactly one `ItemRemoved`, for the
      stack, and not one per thing inside it.

- [ ] **064.4 — two reads answer what the client draws.** Retires `item:num()` and `item:wear()`
      through `Retired`, the second naming **both** replacements and which is which. Adds
      `item:quantity()`, reading `GItem.num` when it is not `-1` and `GItem.NumberInfo.itemnum()`
      otherwise, and `item:progress()`, mirroring `WItem.draw` — `item.meter / 100.0` when `meter`
      is set, else `GItem.MeterInfo.meter()` — and answering `0..1`. The snapshot's `num` and `wear`
      become `quantity` and `progress`.
      *Its suite* scans every item it can reach and asserts the contract that makes this checkable:
      every `:progress()` is `nil` or within `0..1`, never above 1, which is exactly what reading
      the old field without converting would give. On a stack it asserts `:quantity()` equals
      `#item:contents():items()`, which is what proves the fold reached the right source, and it
      duplicates 064.1's stack lookup to do so. It `pcall`s `:num()` and `:wear()` and asserts each
      refusal names its replacement — the second naming both.
      `[manual]`: read back the printed table of item, quantity and progress and confirm each
      number is the one on that item's icon, and that nothing showing a number reads `nil`.

- [ ] **064.5 — durability is the two counts, not the arc.** Adds `item:durability()`, answering
      `{cur, max}` read from the wear tooltip's published class by name — the technique
      `LuaItem.quality` uses for `QBuff` — with the per-class field lookup cached the same way, and
      `nil` for an item that prints none or whose info is still resolving.
      *Its suite* scans for an item answering `:durability()`, asserts `cur` and `max` are numbers
      with `cur <= max`, and asserts the separation this task exists for: the same item's
      `:progress()` is read independently and neither is derived from the other. It duplicates
      064.4's `0..1` bound on `:progress()` rather than assuming that suite is run. It records
      whether any item answers both, which is what settles whether the wear class also publishes the
      arc, and prints that finding as a line.
      `[manual]`: the class name is read off the running client and never guessed — hover a worn
      tool, read its info in the `:lua` REPL, and confirm the printed `cur`/`max` are the numbers
      that tooltip prints. If no item in the inventory carries wear, the suite says so and scores
      nothing.
