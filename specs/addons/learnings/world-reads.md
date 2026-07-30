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
