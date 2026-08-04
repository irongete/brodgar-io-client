# Learnings — World reads (gobs, map, items, char, party)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Positioning:** NO global coordinate; `gob.rc`/`hafen.gob.pos` is **login-relative**. The shared
  anchor is **grid id + within-grid offset** (`hafen.map.grid`/`hafen.map.gridPos`); segment ids are
  local. See `hafen-positioning` memory + `coverage-gaps.md` C4.
- **Gob reads (1c-1):** `Gob.rc` (Coord2d, login-relative), `Gob.a` (facing rad), `Gob.getattr(cls)`;
  attrs — `Moving.getv()` (speed; presence ⇒ moving), `GobHealth.hp` (float 0..1), `Speaking.text.text`
  (String via `Text.text`), `Drawable.getres().name` (type identity; **player = `gfx/borka/body`**),
  `GobIcon.icon().name()`. `getres()`/`icon()`/overlay names can throw **`Loading`** (a
  `RuntimeException`) or be null before resolve → **each reader try/catches and returns nil/skips**.
  Overlay names: `Gob.ols` → `Overlay.spr.res.name` (skip unresolved), read under `synchronized(gob)`.
- **`OCache` enumeration:** it `implements Iterable<Gob>`; copy the list under `synchronized(oc)` then
  build snapshots/apply the Lua filter **outside** the lock (filters call back into Lua). `getgob(id)`
  is already `synchronized`. Building a full snapshot per gob per scan is not free (icon() resolves a
  resource, cached after first hit) → keep recommending `GobAdded`/`GobRemoved` over per-frame scans.
- **GobRef resolution is centralized** in `resolve(LuaValue)` (the one place to add `"target"`/
  `"partyN"`/`"mouseover"` later). Unknown string token → `Long.parseLong` throws → caught → nil, so
  `hafen.gob.name("target")` is a graceful nil today, not an error.
- **Map coords (1c-2):** `tilesz = 11` (`Coord2d`), `cmaps = 100` tiles/grid (`Coord`) → a grid is
  **1100 world units** square. world→tile = `Coord2d.floor(tilesz)`; tile→grid = `Coord.div(cmaps)`.
  **Both floor-divide** (`Coord2d.floor` uses `Math.floor`; `Coord.div` uses `Utils.floordiv`) so
  **negative login-relative coords convert correctly** — do NOT hand-roll `(int)(x/11)` (truncates
  toward zero, wrong for negatives). `MCache.gettile(Coord)`→tileset id, `tilesetr(id).name`; `getcz(px,py)`
  = interpolated height. All three throw `Loading` before the grid is here → guard → nil.
- **`Grid.id` is `buf.int64()`** (full 64-bit) and is THE persistent/cross-player anchor → expose it
  as a **decimal String** (`Long.toString`), never a Lua number: LuaJ numbers are doubles and lose
  precision > 2⁵³. `Grid.gc`/`ul` are `Coord` (tile coords; `ul = gc*cmaps`). `gridPos` offset =
  `wc − ul*tilesz` (world units, 0..1100). Gob ids stay doubles (transient, session-local — 1c-1).
  `grid().seg` (segment id) is NOT on `MCache.Grid` — it's a `MapFile` concept (deferred to A1).
- **world→screen exists:** `MapView.screenxf(Coord2d)` projects to **map-view-relative** pixels via
  the live camera and **catches `Loading` → null** internally (uses `getcc()`); it reads the
  last-composited `basic.state()`, and the client already calls it off-draw (GameUI.updhand), so it's
  safe from Lua on the UI thread. Backs `hafen.player.worldToScreen` (still wrap in try/catch for NPEs).
- **`hafen.gob.health("player")` is nil** — the player body has no `GobHealth` attr; GobHealth is
  *object* integrity (structures, damageable gobs), not the player's hp/stam/energy bars (those are
  the private `GameUI` meters, Phase 1d). Expected, not a bug.
- **`Glob.ast` (`Astronomy`) is nil until the first "astro" update**; the object is immutable (all
  final: `dt`/`mp`/`yt`/`night`/`is`…) so a plain ref read is safe. `glob.globtime()` (the clock) is
  available without `ast`. Centralized `glob()`/`mcache()`/`astro()` helpers hang off the live `view`.
- **Items/char/party (1c-3):** read them **without touching package-private state**. Inventory/
  equipment items: **walk the `WItem` children** — `inv.children(WItem.class)` (public recursive DFS)
  — instead of `Inventory.wmap`/`Equipory.wmap` (both package-private). `WItem.item` (public `GItem`),
  `WItem.c` (public `Coord`, inherited from `Widget`). Inventory grid cell = `witem.c.sub(1,1).div(
  Inventory.sqsz)` (reverses `c.mul(sqsz).add(1,1)`). Equipment slot index = `Equipory.epat(witem.c)`
  (public); slot name = `Equipory.etts[ep].text` (public `Text[]`, entries may be null); a **two-slot
  item shows up as two `WItem` children** (two entries, distinct `slot`). Item fields: `GItem.res`
  (`Indir<Resource>`, `.get().name` — Loading), `GItem.num` (`-1`→nil), `GItem.meter` (**0..100** %, the
  client treats `>0` as "has meter"), name via `ItemInfo.find(ItemInfo.Name.class, item.info()).str.text`
  (`item.info()` throws Loading). Cursor item = `GameUI.vhand.item` (public `WItem`).
- **Char attrs / lp / weight are all public reads** — no widget-tree needed for these. `Glob.getcattr(nm)`
  (public, synchronized) **never returns null** (auto-creates `{base=0,comp=0}` for unknown names) → treat
  `base==0 && comp==0` as nil. The 9 base attrs are content-defined & hard-coded: `str,agi,int,con,prc,
  csm,dex,wil,psy` (from `BAttrWnd`). `hafen.char.lp/weight` = `GameUI.chrwdg.exp`/`enc` (**public int**):
  `chrwdg` (`CharWnd`) is created **hidden at login** (`GameUI.addchild` place `"chr"` → `.hide()`) and its
  `exp`/`enc` update via `uimsg` regardless of visibility — so they read live **without opening the sheet**.
  (FEP/food/curiosity/skills still need the widget-tree mechanism — 1d.)
- **Party (1c-3):** `Glob.party` (public), `party.memb` (`Map<Long,Member>`, public), `party.leader`
  (public). `party.memb` is **replaced wholesale off-thread** (`Partyview.uimsg` builds a new HashMap and
  assigns `party.memb = nmemb`), so copying `memb.values()` on the UI thread is snapshot-safe (no in-place
  mutation → no CME; keep a defensive catch anyway). `Member`: `gobid`/`seq`/`col` public, `getc()` (live
  gob pos else last-known, may be null)/`geta()` public; **no name field** (client/protocol limitation).
  Order the roster by `Member.seq` for the `"partyN"` ordinal. Colors → `{r,g,b,a}` 0..255.
- **`"partyN"` token added to the central `resolve()`** (the designed extension point): `s.startsWith(
  "party")` → `Integer.parseInt(s.substring(5))` → Nth member by seq → `getgob(member.gobid)`. Empty/
  non-numeric suffix → `NumberFormatException` → caught → nil (so `hafen.gob.name("party")` is a graceful
  nil). Out-of-view member → gob not in OCache → nil (use `hafen.party.member(id)` for last-known pos).
- **Item reads return snapshots, deliberately NO `hafen.items.name/count/wear` accessor fns.** Items have
  **no stable addon-visible id** to re-resolve by (unlike gobs), so the snapshot IS the canonical read
  (carries name/num/wear). Per-item live accessors wait for **item handles** (bridge-owned proxies over the
  live `GItem`, UI/replacement phase) — keeps D-013 "one canonical way".
- **(017) An OOP gob handle must wrap the ID ONLY — that is what preserves D-012 freshness for free.** Every
  method re-resolves through the one `getgob(long)` funnel, so a handle stashed in an upvalue tracks a moving gob
  and answers `nil` the moment it despawns; and because it holds no `haven.Gob`, a stashed handle can never pin a
  dead gob (or its `.res`/overlays) in memory — strictly better than `LuaWidgetNode`, which has to null its
  `Widget` by hand. The old `resolve(LuaValue)` with its `"player"`/`"me"`/`"partyN"` token branches collapses
  into that one funnel; the tokens' successors are `hafen.player():gob()` / `hafen.gob(m.id)`.
- **(017) Retargeting the filter from snapshot to object makes the hot path CHEAPER, not dearer.** The old
  `matches(filter, snap)` forced a full `gobSnapshot` per gob *just to test it*; `gobMatches(filter, owner, Gob)`
  reads `gobName(g)` directly for a string filter and mints an (interned) handle only for a function filter. Same
  for the per-frame gob-overlay pass. The discipline that must NOT be lost in the rewrite: copy the gob list under
  `synchronized(oc)` (`allGobs()`), then filter **outside** the lock — a function filter re-enters Lua.
- **(017) A per-addon payload cannot ride the shared `fire(...)` path.** With interning, `GobAdded`/`GobRemoved`
  need *this addon's* handle for the id, so the fan-out builds one per owner. Gate it on "does this owner actually
  subscribe?" first, or a busy spawn stream mints a handle for every addon that isn't listening. Accepted loss on
  `GobRemoved`: the gob is already out of the OCache, so only `:id()` answers — index the name on `GobAdded`.
- **(037.2) The recorded map and the live world are two coordinate systems, and `sessloc` is the ONE bridge
  between them.** `MiniMap.sessloc` is a `Location(seg, tc)` where `tc` is the **segment tile coord of
  session tile (0,0)**, so segment tile = session tile + `sessloc.tc` and back. Everything in `hafen.map`
  that answers in world units (`grid:pos()`, `marker:pos()`, `marker:anchor()`'s fast path) is that one
  addition; everything that answers in segment space is raw database. The two must never be mixed silently:
  `grid:tile(c)` therefore takes a **within-grid** coord `0..99` and *errors* on a segment tile coord rather
  than reading it modulo, because the wrong space reads as "not loaded yet" (nil) forever otherwise.
- **(037.2) The recorded grid under the player is FRESH, which is why the live/recorded cross-check is a
  real assertion.** `GameUI.mapfilesave` re-records the 3×3 grids around the player whenever the grid or its
  `seq` changes, so `grid:tile(c)` and `hafen.world.tile(x,y)` naming the same tileset is a genuine
  agreement between `MapFile` and `MCache` rather than a tautology — and it stays true right after terrain
  changes. Ground explored long ago is as old as `grid:mtime()` says; only the neighbourhood is current.
- **(037.2) Compare the two halves by tileset NAME, never by tile id.** `hafen.world.tile(x,y).id` is an
  index into the session's tileset table, while a recorded grid's `gettile(c)` is an index into **that
  grid's own** `tilesets[]`. Both are called "the tile", both are small integers, and they mean nothing to
  each other. The map file stores `Resource.Saved` (name + version), so the name is available with no
  resource load at all — which is also why `grid:tile(c)` deliberately publishes no `id` field.
- **(039.2) `MCache.getgrid` REQUESTS the grid it cannot find — a locating read must not use it.** On a miss
  it calls `request(gc)`, which queues a `mapreq` the session re-sends up to five times, and then throws
  `LoadingMap`. That is right for "draw this ground" and wrong for "where is this place": a Position's
  durability lookup runs in loops (a panel over saved places, a sweep probing outward) and would have put
  requests on the wire for ground nobody is near. `AddonWidgets.loadedGrid(mc, gc)` is the plain lookup —
  `synchronized(mc.grids)`, skip `removed`, answer null (D-110).
- **(039.2) The two grid-UL derivations agree, which is what lets one lookup have two halves.** `MCache.Grid.ul`
  is `gc * cmaps` in session tiles; the recorded side is `(sc * cmaps) - sessloc.tc`. They are the same point,
  so an anchor computed while a grid is streamed and the same anchor computed from the database after it
  unloads carry identical `x, y` — 037.2 proved it to the millimetre, and 039.2 depends on it: without it
  `p:info()` would flip its offsets as ground streams in and out.
- **(039.2) `MapFile.Segment`'s whole coord→id map is loaded WITH the segment.** The `seg-%x` file is a flat
  list of `(coord, grid id)` pairs read in one go, so `Segment.gridid(sc)` (the `// addon:` accessor) answers
  from memory for every grid of that segment. `Segment.grid(sc)` is the expensive door — it returns an
  `Indir` backed by `Defer` and needs the grid's tiles off the disk — and is the wrong one when all you want
  is the id (D-111).
