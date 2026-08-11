# 027-meters-oop — Spec

## What & why

`hafen.player():vitals()` is the last of the widget-tree reads still shaped as a flat snapshot, and it
is also **wrong about what the client has**: the HUD's `place == "meter"` slot takes an arbitrary
number of `IMeter`s (`GameUI.meters`, laid out in a 3-wide grid), while the bridge hard-codes the
first three as `hp`/`stamina`/`energy` **by tree position** and drops the rest. This feature replaces
it with the OOP section the ROADMAP's migration asks for: **`hafen.meter`** — every HUD meter, keyed by
what the engine actually publishes about it (its `IMeter.bg` **resource**, D-061), with colour and
multi-segment bars that the snapshot never exposed.

Hard cut, no debt: `hafen.player():vitals()`, the `Vitals` type and the `VitalsChanged` event are
**deleted** (nothing is released — see `no-backward-compatibility`), and the positional
`VITAL_KEYS` mapping goes with them.

## Acceptance criteria

- [ ] `:lua hafen.meter()` lists **every** HUD meter (≥3 in a normal session), 1-based, in HUD order;
      `#` matches, and each entry is the same object as the matching `hafen.meter(key)` lookup.
- [ ] `:lua hafen.meter("hp"):value()` returns the health fraction and tracks the bar live (take a
      hit / run to drain stamina and re-read). `"hp"` is **not a key the code knows** — it is a
      substring of the resource name that meter publishes; the same call reaches any other HUD meter
      by its own substring, and `:res()` is where an addon author reads the names off a live client.
- [ ] `hafen.meter(needle)` = the first meter whose **res name contains** `needle`; a miss → `nil`, a
      number key → an error naming the collection form, `""` → an error. Both forms reach one
      interned object.
- [ ] `:color()` and `:segments()` answer for a live meter (a vital bar = one segment); `:index()` is
      its 1-based HUD position, `:exists()` false after a meter is destroyed, `:info()` is the snapshot.
- [ ] `MeterChanged` fires **only on a real change** (value or colour) with the Meter as payload;
      `MeterAdded`/`MeterRemoved` fire as meters stream in after `OnEnterWorld` and when one is destroyed.
- [ ] `hafen.player():vitals()`, `VitalsChanged` and the `Vitals` type are gone — `hello` proves the
      old surface errors/never fires, and no `docs/addons/` page still mentions them.
- [ ] `hello` extends its login self-check to the whole `hafen.meter` contract (interning, lookup
      hit/miss, key errors, `:exists()`, the three events) and its HUD overlay reads meters through the
      new section; the full regression still passes on one login.

## Out of scope

- Absolute hp/stamina/energy numbers and hunger — the client has neither (only FEP, `hafen.char.food`).
- Meter **tooltips** (`LayerMeter.rawinfo`/`info()` — an `ItemInfo` compose, its own feature if wanted).
- `BAttrWnd`'s FoodMeter/GlutMeter and any other `LayerMeter` outside the HUD `place == "meter"` slot.
- Writes: meters are server-pushed, there is no verb to add.
- The rest of the OOP migration (party/fight stay flat).

## Context files

- `specs/design/14-widget-tree-reads.md` — the adapter mechanism this rewires (the `VitalsAdapter` row)
- `specs/025-buffs-oop/` — the template: widget-identity interning, `fireBuff`, the flat hard cut
- `src/io/brodgar/addon/CharApi.java` — `VitalsAdapter`, `readVitals`, `vitalsEqual`, `player():vitals()`
- `src/io/brodgar/addon/LuaBuff.java` — the entity + per-addon weak intern cache to copy
- `src/io/brodgar/addon/AddonManager.java` — `fireBuff`/`hasSub` (the `fireMeter` sibling), `resetSession`
- `src/haven/AddonWidgets.java` — the `haven`-package accessor (`meters(LayerMeter)`; a `GameUI` locator lands here)
- `src/haven/IMeter.java`, `src/haven/LayerMeter.java` — `bg` (identity), `meters[i].a/.c`, the `set`/`col` uimsgs
- `src/haven/GameUI.java` — `private List<Widget> meters` + the `place == "meter"` seam (:1014)
- `specs/codebase/services.md` — the vitals row (gets the coverage toll for the meter list)
- `docs/addons/api/player.md`, `types.md`, `events.md`, `README.md`, `conventions.md`, `hooks.md`,
  `docs/addons/README.md`, `docs/addons/getting-started.md` — every page that names vitals today
- `addons/hello/main.lua` — the harness (`readVitals`, the `VitalsChanged` handler, the HUD overlay)
