# 002-read-api — Spec

## What & why
The full **Glob-backed read API** — the safe modding tier's data layer. Addons can read the world
(gobs), the terrain and the persistent grid anchor, the local player's identity, game time and
astronomy, sounds/music, the inventory/equipment, character attributes and the party roster —
all as reference-based accessors (`hafen.gob.*(ref)`, D-012) or point-in-time **snapshots**, and
all `nil`-before-ready (never an error while the world streams in). Contract:
[../API-REFERENCE.md](../API-REFERENCE.md); design: [design/06-lua-api.md](../design/06-lua-api.md).

## Acceptance criteria (all verified in-game)
- [x] `hafen.gob.*` (exists/info/pos/facing/name/health/moving/speed/speech/icon/distance) reads
      one gob fresh per call; `hafen.world.*` (gobs/count/nearest/within) enumerates with a
      substring-or-function filter; `nearest`/`within` measure from the player and skip self;
      `GobAdded`/`GobRemoved` carry the full snapshot.
- [x] `hafen.map.*`: `tile` resolves a real tileset name (saw `gfx/tiles/grass`), `gridPos()`
      returns the shareable anchor with the **grid id as an exact decimal string** (64-bit safe;
      saw `-666514804926139788`), pure conversions (`worldToTile`/`tileToWorld`/`tileToGrid`)
      always work.
- [x] `hafen.player.*`: `name()` resolves **inside `OnEnterWorld`** (event retimed to wait for
      the HUD); `worldToScreen(player)` ≈ map-view centre.
- [x] `hafen.time.*` (clock/dayFraction/isNight/season/moon/yearFraction) and
      `hafen.sound.play`/`hafen.music.play` (audible ping; no UI-thread block).
- [x] `hafen.items.*` (inventory/equipment/hand/find) returns Item snapshots (12 named inventory
      items with grid pos; 19 equipment slots, two-slot items = two entries); `hafen.char.*`
      (attr/attrs/lp/weight — saw lp=403475) and `hafen.party.*` (members/leader/member) read
      correctly; the `"partyN"` GobRef token works (`hafen.gob.pos("party1")`).
- [x] `hello` re-reads map/items/char at `[now]` and `[+3s]`, proving the post-enter-world
      data streaming resolves; full prior regression intact.

## Out of scope (later features)
- Vitals, buffs, FEP/food, study, skills, action bar — widget-tree surfaces →
  [003-widget-tree-reads](../003-widget-tree-reads/spec.md).
- Item `quality`/`contents` and per-item handles (→ ROADMAP); markers/segment ids (→ 009);
  `"target"`/`"mouseover"` GobRef tokens (need combat/hover tracking).

## Context files
- `../API-REFERENCE.md` — the `hafen.*` contract this implements
- `design/06-lua-api.md` — API design; `design/01-architecture.md` — threading (P5)
- `src/io/brodgar/addon/AddonManager.java` — all accessors, `resolve()`, `gobSnapshot`, helpers
- `src/haven/OCache.java`, `Gob.java`, `MCache.java`, `Glob.java`, `CharWnd.java` — backings
- `docs/addons/api/gob.md`, `world.md`, `map.md`, `player.md`, `time.md`, `audio.md`,
  `items.md`, `char.md`, `party.md`, `types.md` — shipped surface
- `../001-bootstrap-engine/` — the engine + events this rides on
