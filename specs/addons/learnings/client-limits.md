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

- **(SUPERSEDED by 039.14 — see the entry below) Item quality as a typed value.** No `Quality` class; quality lives only inside
  resource-published `ItemInfo` (built generically by `ItemInfo.buildinfo`
  [ItemInfo.java:362](src/haven/ItemInfo.java:362) — that line is the generic builder, not a
  quality field). Best-effort raw-`tt` parsing only.

- **Seconds-based BUFF timers, and per-maneuver openings/weight.** Buff cooldowns are 0..1
  fractions only (no time). Per-maneuver "openings/weight" beyond the IP integers do not exist as
  fields. **NOTE — combat cooldowns are the exception:** attack/maneuver cooldowns use `rtime`
  targets, so seconds-remaining IS derivable (`ct - Utils.rtime()`) and the fight API exposes it.

- **Names of maneuvers/cards/buffs/icons** need `Indir<Resource>.get()` → throws `Loading`;
  return nil while loading rather than blocking.

- **(039.14) Quality IS readable — what the client lacks is a TYPE, not the number.** The entry above is
  right that `haven` declares no quality class, and wrong to stop there: the number is published by code
  that ships inside the resource, `ui/tt/q/quality`'s `Quality extends ui/tt/q/qbuff`'s `QBuff`, which
  carries `public double q` and `public String name`. So an item's tooltip info list holds it, and
  `item:quality()` reads it by **class name** + one reflective field read (no `get-code` copy, no
  `@FromResource` pin — D-140: a pin that stops matching answers null for every item silently, a name
  lookup can only stop finding it). An item may publish several `QBuff`s (a gilding row is one), so the
  plain `Quality` subclass wins and any other is the fallback. **The general lesson is about this file:**
  "the client has no typed X" is a statement about `src/haven`, and the resources ship code too — before
  recording something as absent, check whether a `.res` publishes it (`unzip -p bin/hafen-res.jar` and read
  the preprocessed source inside the `.res`).
