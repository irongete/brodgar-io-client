# State roots: the client's read surfaces

> Where game state actually lives: gobs, map, player, items, character, party, time.

| What | Where |
|---|---|
| Global root | `Glob` via `ui.sess.glob` (`UI.sess`, `Session.glob`) |
| Object cache (gobs) | `OCache`: iterate, `getgob`, `callback`/`uncallback` (/). ⚠️ **The iterator is wider than `getgob`**: it walks `objs.values()` *plus* the registered `local` collections, while `getgob` looks only in `objs` — which is a `MultiMap<Long,Gob>`, so one key may hold several gobs. And `OCache.Virtual` mints **negative** ids (`nextvirt`, from -1 down): `Skeleton` `oc.add`s a `FixedPlace` purely to carry a one-shot effect overlay, and `Gob.ctick`'s `virtual && ols.isEmpty() && no Drawable ⇒ oc.remove` drops it the tick that overlay ends. So an id the iterator just yielded can answer `null` from `getgob` immediately — never read a `nil` there as "the property is unset" |
| **What a `ChangeCallback` is holding when it fires** | the **gob's own monitor**, in every one of the four paths: `OCache.add` takes `synchronized(ob)` around its whole callback loop (`remove`, `ladd` and `lrem` do the same), the `cbs` snapshot always being taken under `synchronized(OCache)`. So a callback may not take any lock the tick holds under that gob, and — the useful half — anything that itself opens with `synchronized(ob)` is ordered strictly **after** every callback has run, which is what `MapView.Gobs.addgob` does on the Loader thread it was deferred to. `cbs` is a `WeakList`, so a caller of `callback(cb)` must hold its own strong reference or the registration simply stops firing |
| Game object | `Gob`: `id`, `rc`, `a`, `getattr`, `getc` |
| Gob attributes | `GAttrib`: `Drawable.getres()`, `Moving`, `GobHealth.hp`, `GobIcon`, `Speaking` |
| Gob attrib map | `Gob.attr` is **package-private** (reflection from outside `haven`); keyed by `attrclass` = the subclass directly under `GAttrib`, so `getattr(C)` only matches that exact key — a same-named class from another loader misses (see `learnings/engine-lifecycle.md`) |
| Kin mark on a gob | `res/ui/obj/buddy/Buddy` (`@FromResource` v4): public `id` = the `BuddyWnd.Buddy` id. Server-stamped on **every** gob of that kin — body **and** hearth fire (`gfx/terobjs/pow`) |
| Map / terrain | `MCache`: `gettile`, `tilesetr`, `getcz`, `getgrid`; `tilesz`/`cmaps` (/) |
| Player id / gob / camera | `MapView.plgob`, `player()`, `getcc()`, `camera` |
| Inventory / items | `GameUI.maininv`, `Inventory.wmap`, `GItem` (`res`, `num`, `meter`, `info()` — see the icon-numbers row below for what `num` and `meter` actually are). `Inventory.addchild` builds **one `WItem` per item** at `args[0].mul(sqsz).add(1,1)`; `cdestroy` does `ui.destroy(wmap.remove(i))` **unguarded**. The reverse of `wmap` is on the icon: `WItem.item` is the `GItem` it draws, `public final` and set in the constructor, so it answers from the moment the widget is built |
| Equipment | `GameUI.equwnd` → `Equipory.wmap` = item → a **collection** of `WItem`s: `addchild` makes one per ep index in `args`, so a two-slot item is drawn **twice** and `children(WItem.class)` returns it twice. Slot from a coord: `epat`; slot name: `ettstr[ep]` (`etts[ep]` is that string rasterised at class init, and `betts[ep]`/`etttip` the re-render), filled only where `gfx/hud/equip/ep<i>` has an image layer — **23 slots, 22 names** (slot 16 has none, and the server does place items there) |
| The item on the cursor | `GameUI.addchild` `place == "hand"` does `add((GItem)child)` — a real **server-bound** `GItem` under the HUD, so it has a widget id like any other; `GameUI.vhand` is only the `WItem` that draws it, rebuilt by `updhand` from the `hand` list |
| What an item holds | `GItem.contents` (the widget the server pushed), `contentsnm` (its caption), `contentsid`, `contentswnd`. All four are written by `GItem.addchild` — which does **not** add the child under the item: it puts it in a new `GItem.ContentsWindow` added to `contparent()` = `getparent(GameUI.class)`. The items inside are that widget's **`GItem` children**, which is the walk `GItem.addcontinfo` makes over `contents.children()`; `updcontinfo` invalidates the holder's own info when a child's `infoseq` moves |
| ...and what holds it | `GItem.ContentsWindow.cont`, public final: the item that pushed the contents. Climbing `Widget.parent` from a contained `GItem` to the first `ContentsWindow` is the only back-link there is, and climbing on from there chains through a container inside a container |
| A tooltip that states its contents | `ItemInfo.Contents extends ItemInfo.Tip`, `public final List<ItemInfo> sub` — the block a bucket carries instead of items. Built by resource code (`ui/tt/cont` on the wire), so it is matched by type; `GItem.info()` throws a bare `Loading` while the resource streams. `sub` is a **whole nested tooltip** — that factory fills it with `ItemInfo.buildinfo` — so what is inside describes itself with the same rows an item uses: an `ItemInfo.Name` (or `AdHoc`) for the stated line, whose text is `Name.source()`/`AdHoc.source()` (`Name.str.text` is that line rasterised), and its own `QBuff` for the content's quality |
| A fill meter | published **resource code** again: `ui/tt/level`'s `Level` (`public final double cur, max`), adopted at `res/ui/tt/level/Level.java` (`@FromResource` v21) so the two counts are reachable by type. It is an `ItemInfo` implementing `GItem.OverlayInfo<Double>`, **not** a `Tip`: the engine asks it for `overlay()` = `cur / max` alone and `WItem.draw` paints that over the icon through `itemols`, which walks the item's **own** `info()`. So nothing in `haven` reads the counts, and nothing renders this as a tooltip row |
| The two numbers on an item's icon | `WItem.draw` is the authority for both, and it reads **two sources each**. The **count**: `GItem.num` (written by the `"num"` uimsg, `-1` when unset) is drawn **nowhere** — the number comes only from `itemols`, the `AttrCache` of `GItem.InfoOverlay`s built from the `GItem.OverlayInfo`s in `info()`, and `GItem.NumberInfo extends OverlayInfo<Tex>` is the one that renders a count (`itemnum()`). **What that number counts is the implementor's, and there is no finer type**: `GItem.Amount.itemnum()` is an amount, while `res/ui/tt/slots_alt/ISlots.itemnum()` is `s.size()`, the gildings applied — drawn, `0` included, unless `ignol` (set when the owner's `fcontext` is a `MenuGrid`). The **arc**: `(item.meter > 0) ? item.meter / 100.0 : itemmeter.get()`, that second half being the first `GItem.MeterInfo` of `info()` (`meter()`) — so the field is a **percentage** and the interface a `0..1` fraction, and `g.prect` paints `meter * 2π`. `GItem.uimsg "meter"` writes the field |
| An item's durability | published **resource code** once more: `ui/tt/wear`'s `Wear extends ItemInfo.Tip` (`public final int d, m`), reachable in `GItem.info()` by class name. It is a **plain `Tip` and nothing else** — it implements neither `GItem.MeterInfo` nor `OverlayInfo`, so a worn item paints **no arc** from this row and the two are genuinely separate data. `tipimg` renders `Wear: d/m` and re-renders it in red once `d >= m`, which is the whole of what the client knows about what they measure |
| Item quality | published **resource code**, not `haven`: `ui/tt/q/quality`'s `Quality extends` `ui/tt/q/qbuff`'s `QBuff` (`public double q`, `public String name`), reachable in `GItem.info()` by class name. An item may carry several `QBuff`s (gilding is one) |
| Item metadata / name | `ItemInfo`: `Name` (`Name.source()`, `null` where the caller supplied a rendered `Text`), `find`, `buildinfo` |
| **When an item becomes describable** ← an addon seam | `GItem.info()` builds the list lazily and caches it in `GItem.info`, so its build block runs **once per arrival and once per revision**, never per frame. Two things gate it and only the first is a message: `uimsg "tt"` nulls the cache and stores `rawinfo`, and `ItemInfo.buildinfo` then throws `Loading` until the resource that renders those rows has streamed — so every read through `info()` answers nothing and then answers, with no message marking the moment. The end of that build block is the seam (`onItemInfo(this)`, `// addon:`); it is reached on whichever thread first asks, which in practice is the draw of the icon. ⚠️ A **font change** also nulls the cache (the tooltip is re-rendered), so the seam fires again for the same words |
| Character attributes | `Glob.getcattr`, `CAttr{base,comp}`; `CharWnd` (exp/enc) |
| Party | `Glob.party`: `memb`, `leader`, `Member.gobid`/`seq`, `getgob()`, `getc()` ( — live gob position if in view, else the **last-known** one the server sent, `null` before either), `col`. Written only by `Partyview.uimsg`: `"list"` rebuilds `memb` into a NEW map but **reuses the existing `Member` for an id that stayed**, so the object is stable across a roster push and only a joiner is minted; `"m"` writes one member's coord/colour; `dispose` empties it. A member has **no name** — the client is never sent one |
| Time / astronomy | `Glob.globtime` (`gtime`); `Glob.ast` → `Astronomy` `dt`/`night`/`mp`/`yt`/`is`; light fields. ⚠️ **`is` is a season index the engine never names**: `Cal` is its only reader, and it uses `is` to pick one of four calendar textures (`Tex[4] dlnd`/`nlnd`, `gfx/hud/calendar/dayscape-<i>` and `nightscape-<i>`), so the domain is `0..3`. `Glob`'s `"astro"` branch defaults the field to `1` when the server omits it — a hint that 1 is the neutral season, not proof. Which index is which season appears nowhere in `src/haven`: read it off the calendar the client draws |
| Gob speech / icon | `Speaking.source()` (reliable; `Speaking.text` is the rendered bubble); `GobIcon.Icon.name()` (Loading-guarded) |
| Gob overlays | `Gob.ols` (public `Collection<Overlay>`); `Overlay` (`id`  = the SERVER's, `-1` when it gave none; `spr.res.name` is the only nameable identity), `addol` ( body / one-arg / / /), `addolsync`, `findol(id)`, removal at. **Three removal paths and only two go through `Overlay.remove`** — the third is `ctick`'s own expiry, which calls `remove0()` + `i.remove()` directly |

## Gotchas

- **No global world position**: `rc` is login-relative; the shareable anchor is grid id +
  within-grid offset. Grid/segment ids are 64-bit → expose as decimal strings.
- **Hiding a `ContentsWindow` is not destroying it**: `reqclose()` is `chstate("hide")` and nothing more —
  only `cdestroy(inv)` nulls `contents`/`contentsnm`/`contentsid`/`contentswnd` and destroys the window. So
  everything a contents widget holds is readable with the window down. `wndshow` has exactly one caller,
  `GItem.uimsg "contopen"`, so **no client can pin that window open** — the server decides.
- **A contained item is under the `GameUI`, not under the container it appears to be in**: the window hangs
  off `contparent()`, so `hasparent(inventory)` is false for it and a `children(WItem.class)` walk taken from
  a node **above** the HUD sweeps contained items in.
- Any read here can throw **`Loading`** — swallow to a partial/absent value or defer.
- **A `GAttrib` dies with its gob for free**: `Gob.dispose()` disposes every
  attrib, and a gob dropped from `OCache.objs` takes them with it — so state attached to a gob needs no
  prune, unlike an addon-side map keyed by gob id. `setattr`  adds a `RenderTree.Node`
  attrib to the gob's slots and can throw **`Loading`**; it also `dispose()`s whatever it displaced.
- **…but "for free" is GC, not `dispose()`**: `OCache.remove` only calls
  `ob.removed()` (sets a flag) — `Gob.dispose()` is NOT on that path. So
  anything a gob merely *points at* elsewhere (its own client gob in the scene, a GL resource) must be
  ended on the gob-removed seam: the screen-vs-world `gob:overlay` asymmetry, and `follow=`'s orphan.
- **`ctick` self-removes a VIRTUAL gob** when `ols.isEmpty()` and it has no `Drawable`
   — a client-only gob must keep something in one of the two, or it
  vanishes on the next tick. `ctick` also drops a finished overlay (`ol.tick(dt)` true,) **without calling
  `Overlay.remove`** — which is how most of the game's overlays actually end (they are transient sprites
  nobody removes by hand), so anything watching removals must hook that line too.
- **`addol(ol)` is `addol(ol, true)` = ASYNC** — it defers through `Gob.defer` onto a loader thread, so
  the one-arg and two-arg forms are the same event: hook the two-arg **body** or every add fires twice.
  Overlays also arrive from server messages off the UI thread; nothing may call into Lua from there.
- **`MCache.getgrid` REQUESTS what it cannot find** : a miss calls
  `request(gc)` (re-sent up to five times) and throws `LoadingMap`. Right for "draw this ground",
  wrong for "where is this place" — a locating read uses a plain `synchronized(grids)` lookup instead, which
  is what `AddonWidgets.loadedGrid` is. `Grid.ul` is `gc * cmaps` in session tiles, the same point
  the map file derives as `(sc * cmaps) - sessloc.tc`, which is why one anchor survives a grid unloading.
- **The session coordinate space is re-based MID-SESSION, not only at login** — `invalblob`
   type 2 calls `trimall()`,
  dropping every grid, and the ground that streams back in arrives under different grid coords. Entering a
  cave or a house does exactly this, so the same `Gob.rc` numbers name different ground before and after.
  Anything that must still mean somewhere afterwards has to be kept as a **grid id + offset**, never as a
  session coordinate (see [minimap.md](minimap.md) for the `sessloc` side, which lags the drop).
- **Several overlays may share one resource** — measured 13 of 33 gobs carrying overlays, so a
  resource name identifies a *set*, not one overlay.
