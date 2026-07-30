# 017-gob-oop — Spec

## What & why
Migrate **only the Gob surface** from the flat reference accessor (`hafen.gob.health(ref)`) to a
real **OOP class**: `hafen.gob(id)` is the factory, `gob:health()` / `gob:pos()` / `gob:name()` are
methods. A Gob wraps **only the id** — every method re-resolves against `OCache` and returns `nil`
if the gob is gone, so the freshness semantics of D-012 are kept verbatim; what changes is that the
reference stops being an argument and becomes the object. **Identity by interning** (per-addon
weak-value cache keyed by id) so `a == b` and `seen[gob] = true` are reliable. **Hard cut**: the flat
`hafen.gob` table and the GobRef token strings are deleted, no shim, no deprecation, no dual style
(D-013). Every other namespace stays flat — that coexistence is deliberate and transitional.

Consequence accepted from the brief: **`hafen.player` also becomes callable** (`hafen.player()`
→ a Player object) purely as the composition anchor for `hafen.player():gob()` (nil before entering
the world). Player gets **no forwarded gob methods** — `player:pos()` would coexist with
`player:gob():pos()` and that is exactly the dual style D-013 forbids. Player is therefore a
minimal object (`:gob()`, `:name()`, `:vitals()`, `:worldToScreen(x,y)`); `exists()`/`id()` are
dropped — `player:gob()` and `gob:id()` already answer both.

## Decisions taken here (the spec's open items, closed)
- **`hafen.world.gobs()/nearest()/within()` return Gob objects**, not snapshots — same task. A
  **function filter now receives a Gob** (`function(g) return not g:isplayer() end`); a string
  filter keeps substring-on-name (evaluated Java-side, no snapshot built). `count()` still returns
  a number.
- **`gob:info()` survives** as the one snapshot escape hatch (same table shape as today), for
  logging/serialising. `:overlays()` and `:isplayer()` are added as methods so the method set and
  the snapshot fields line up 1:1.
- **`hafen.gob(id)` always returns a Gob** for a number id — never nil, even for an unloaded/never-
  seen gob (`gob:exists()` is the liveness test). A non-number argument is a guiding error.
- **Interning is per-addon** — each sandboxed env owns its weak-value cache, so no Lua object ever
  crosses between addons (D-017).
- **Every other GobRef consumer now takes a Gob object**: `hafen.act.clickGob(gob[,button,mods])`,
  `hafen.render.sprite/object{follow=gob}` and `:follow(gob[,offset])`. Raw ids are not accepted
  (that would be the dual style); `hafen.gob(id)` builds the object without needing the gob loaded,
  so the pre-load `follow` case still works.
- **`GobAdded`/`GobRemoved` payloads become a Gob object.** Accepted loss: on `GobRemoved` the gob
  is already gone, so only `:id()` answers — an addon that needs the name must have indexed it on
  `GobAdded`. (Fallback if this bites in-game: keep the snapshot as a second payload arg.)
- Party gobs stay reachable via `hafen.gob(m.id)` from the roster; **the combat target's gob becomes
  unreachable** until Fight migrates — assumed and accepted.

## Acceptance criteria (verified in-game, one login)
- [ ] `:lua` REPL: `hafen.gob(hafen.player():gob():id()):name()` works; `hafen.gob(1):exists()`
      is `false` without erroring; a nonexistent gob's `:pos()`/`:health()` return `nil`.
- [ ] Identity: `hafen.gob(id) == hafen.gob(id)` is `true`, and `hafen.player():gob()` is the same
      object as `hafen.gob(<player id>)`; a `seen[gob]=true` table de-duplicates across two
      `hafen.world.gobs()` sweeps.
- [ ] Freshness: hold a Gob in an upvalue, walk, and its `:pos()` tracks you; log out of view of a
      tracked gob and its methods go `nil` while `:id()` still answers.
- [ ] `hafen.world.nearest(function(g) return not g:isplayer() end)` returns a Gob whose
      `:name()`/`:distance()` read live; `within`/`gobs` likewise; string filters still work.
- [ ] `hafen.player()` before entering the world → `:gob()` is `nil`; in-world it is the player Gob;
      `player:vitals()`/`:name()`/`:worldToScreen` unchanged. `hafen.player.exists` is gone.
- [ ] Hard cut proven: `hafen.gob.health` is `nil` (calling it errors), `hafen.gob("player")`
      errors, and grep for `hafen\.gob\b` is zero in `src/io/brodgar/addon/`, `docs/addons/`,
      `addons/`, `API-REFERENCE.md`, `STATE.md`.
- [ ] `walker` (gated) still walks to the nearest non-player Gob; `planner` still places ghosts at
      `hafen.player():gob():pos()`; `hello` exercises the whole new surface and the **full prior
      regression still passes in that one login**.

## Out of scope
- Any other namespace migrating to OOP (world/player-beyond-`:gob()`/party/fight/items/ui stay flat).
- Replacing the retired tokens: `hafen.target():gob()` and `hafen.party()[1]:gob()` arrive when
  **Fight** and **Party** migrate — not now. Token mentions are only *removed* from the docs.
- Rewriting the marked zone: `design/06-lua-api.md` gets a one-line obsolescence banner, D-012 /
  D-013 / D-022 get `(SUPERSEDED by D-0NN)` in their headers, closed `NNN-` folders, `learnings/`
  and git history are untouched.

## Context files
- `../design/06-lua-api.md` — the flat-API design being superseded (banner target)
- `../decisions/architecture-api.md` — D-012/D-013/D-022 (supersede + 3 new decisions land here)
- `src/io/brodgar/addon/WorldApi.java` — `installGob`/`installWorld`, the real rewrite
- `src/io/brodgar/addon/AddonManager.java` — `resolve`/`pos`/`gobSnapshot`/`matches`/`allGobs`,
  `installHafen`, the `GobAdded`/`GobRemoved` fire site
- `src/io/brodgar/addon/CharApi.java` — `installPlayer` (→ callable), `partyMemberByOrdinal`
- `src/io/brodgar/addon/ActApi.java` — `actClickGob` (GobRef consumer)
- `src/io/brodgar/addon/RenderApi.java` — `followTargetId` (GobRef consumer)
- `src/io/brodgar/addon/UiApi.java` — `gobOverlay` filter (snapshot consumer)
- `src/io/brodgar/addon/LuaWidgetNode.java` — the handle pattern (table + `__key` userdata) to mirror
- `docs/addons/api/gob.md` — the real doc rewrite; `world.md`, `player.md`, `conventions.md`,
  `types.md`, `party.md`, `actions.md`, `ghost.md`, `map.md`, `render.md`, `README.md`,
  `../getting-started.md` — 1–5 line cross-reference edits
- `addons/hello/main.lua`, `addons/walker/main.lua`, `addons/planner/main.lua` — the consumers
- `../002-read-api/` — the feature that built the flat surface being replaced
