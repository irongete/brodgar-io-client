# 002-read-api — Tasks

- [x] 002.1 — Gob + world reads: `hafen.gob.*(ref)` (11 accessors), `hafen.world.*`
      (gobs/count/nearest/within, substring/function filter), centralized GobRef `resolve()`,
      full `Gob` snapshot shape, richer `GobAdded`/`GobRemoved` payloads.
- [x] 002.2 — Map + player + time + sound: `hafen.map.*` (tile/height/grid/gridPos +
      conversions; string grid ids), `hafen.player.*` (exists/id/name/worldToScreen),
      `hafen.time.*` (clock + astronomy), `hafen.sound.play`/`hafen.music.play`;
      `OnEnterWorld` retimed to wait for the HUD.
- [x] 002.3 — Items + char + party: `hafen.items.*` (inventory/equipment/hand/find, Item
      snapshots), `hafen.char.*` (attr/attrs/lp/weight), `hafen.party.*`
      (members/leader/member), `"partyN"` GobRef token.
