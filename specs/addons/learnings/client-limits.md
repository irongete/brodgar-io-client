# Learnings — Client/protocol limits (what CANNOT be exposed)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole.
>
> These are **not gaps to fill later** — they are properties of the client/protocol. Verified
> absent or unstable in this fork; the API must never promise them. Addons that need them must
> derive or approximate.

- **Other players' display names / nicks.** `OD_BUDDY` is removed ([OCache.java:51](src/haven/OCache.java:51));
  no `Gob` name field, no `KinInfo`. Names arrive via resource-published `GAttrib.Parser` over
  `OD_RESATTR` ([OCache.java:347](src/haven/OCache.java:347)) — not referenceable by a stable type.
  A name is available only when the gob is on your **kin list** (`GameUI.buddies` → `Buddy.name`,
  keyed by buddy id) or, for the **local** player, `GameUI.chrid`. `Party.Member` has a gobid but
  no name.

- **Absolute HP / stamina / energy numbers, and hunger/satiation.** Vitals are `IMeter` bars
  (0..1 fractions) in private `GameUI.meters` ([:49](src/haven/GameUI.java:49)) / protected
  `LayerMeter.meters` ([:35](src/haven/LayerMeter.java:35)), identifiable only by matching
  `IMeter.bg` resource names. No absolute values exist. **Exception:** FEP/hunger DO exist as
  numbers, but only inside the `CharWnd` widgets (`BAttrWnd` `feps`/`glut`) — read the widget
  tree, not the vitals bars.

- **Item quality as a typed value.** No `Quality` class; quality lives only inside
  resource-published `ItemInfo` (built generically by `ItemInfo.buildinfo`
  [ItemInfo.java:362](src/haven/ItemInfo.java:362) — that line is the generic builder, not a
  quality field). Best-effort raw-`tt` parsing only.

- **Seconds-based BUFF timers, and per-maneuver openings/weight.** Buff cooldowns are 0..1
  fractions only (no time). Per-maneuver "openings/weight" beyond the IP integers do not exist as
  fields. **NOTE — combat cooldowns are the exception:** attack/maneuver cooldowns use `rtime`
  targets, so seconds-remaining IS derivable (`ct - Utils.rtime()`) and the fight API exposes it.

- **Names of maneuvers/cards/buffs/icons** need `Indir<Resource>.get()` → throws `Loading`;
  return nil while loading rather than blocking.
