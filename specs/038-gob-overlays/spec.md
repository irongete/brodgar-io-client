# 038-gob-overlays — Spec

## What & why

**`overlay` is the engine's own word for a thing attached to a gob** — [`Gob.ols`](src/haven/Gob.java:42),
`Gob.Overlay`, `addol`/`findol` — and the addon API currently spends that word on
`hafen.ui.gobOverlay`, a *screen-space painter* that is not one, while the real per-gob attachment
hides behind `follow = gob` in two other sections. Three mechanisms, one concept, none of them on the
gob. This feature collapses them onto the entity that owns them (D-044 — *a verb belongs on the
thing*): **`gob:overlay(...)` is the one way to attach anything to a game object, and the one way to
read what is already attached.** After it there are exactly three places a drawn thing can live — in
the world at a fixed place, on a gob (following by definition, so **`follow` stops existing**), or on
the HUD. It closes the ROADMAP's "per-overlay anchor/offset + a per-gob match cache" by deleting the
sweep those items were polish on. Both superseded surfaces are **hard cut** (no aliases — nothing is
released), while their mechanisms are kept and re-fronted, not rewritten.

## Acceptance criteria

- [ ] **Arity is the verb, keyed.** `gob:overlay(key, spec)` attaches or replaces (idempotent — the
      same key twice leaves one overlay); `gob:overlay(key, nil)` removes; `gob:overlay(key)` answers
      that one or `nil`; `gob:overlay()` answers every overlay on the gob. Keys are per addon: two
      addons using `"tag"` on one gob do not collide, and each reads back only its own plus the
      native ones.
- [ ] **One spec table, both spaces.** `{draw = fn}` and `{text = …}` paint in **screen space** at the
      gob's projected point (what `hafen.ui.gobOverlay`'s callback received); `{image = asset}`,
      `{model = asset}` and `{ghost = res}` stand in the **3D world** anchored to the gob, with
      `offset = {x=,y=,z=}` (what `follow=` did). A spec naming neither is an error naming the field.
- [ ] **The native ones are readable and read-only.** `gob:overlay()` includes the game's own overlays
      with `native = true` (the addon's carry `native = false`), and `gob:overlay(nativeKey, nil)` or
      an attach onto a native key **raises**, naming the key — never a silent no-op.
- [ ] **Two new events**, payload `{ gob, key, native }`: `GobOverlayAdded` fires for an addon attach
      **and** when the server puts a native overlay on a gob; `GobOverlayRemoved` likewise. Provable
      by walking near a gob the server decorates and by attaching one from the suite.
- [ ] **The hard cuts are cut**: `hafen.ui.gobOverlay == nil`; `gob.overlays == nil` (the reader is
      subsumed — `grid:overlays()` on the *map* database is a different verb and is untouched);
      `hafen.render.sprite{follow=…}` / `object{follow=…}` / `hafen.ghost{follow=…}` are **refused
      naming the replacement**, and the handles' `:follow` / `:offset` methods are gone.
- [ ] **An overlay dies with its gob, exactly as the natives do.** When the gob goes the record goes —
      nothing is kept "in case it comes back" (a felled tree never does) — and a returning gob is
      **bare**: re-attaching is the addon's own call, from `GobAdded`. `:reload` or disabling likewise
      removes every overlay the addon attached, leaving the natives untouched.
- [ ] `hello` is edited (it is frozen, but this genuinely breaks it — `hafen.ui.gobOverlay` at
      `main.lua:2883` and `render.sprite{follow=}` at `:2027`), and one shipped example addon
      demonstrates the new verb.
- [ ] Each task ships its self-checking addon per `specs/testing/addon-suite.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **Highlighting a gob by restyling what the client already draws** (tint/outline on the gob's own
  model). This feature attaches things; it does not repaint the engine's.
- A **filter form** — `gob:overlay` addresses one gob. "Every player gets a label" is a
  `GobAdded`/`GobRemoved` handler plus a loop, and that trade is deliberate (the declarative sweep is
  what is being deleted). If it proves painful, a `hafen.world` helper is a later feature, not a
  second way to do this.
- Animated or state-dependent overlays (a spec is read once, like a sheet rule).
- Native overlay **writes**, and any richer native read than key + `native` + `:res()`.
- **An overlay on a ghost.** A ghost is a virtual gob with id `-1` that never enters `OCache`
  ([GhostGob.java:81](src/io/brodgar/addon/GhostGob.java:81)), so it is not addressable as a Gob.
  Nothing is lost (today's sweep and `FollowMoving` resolve through `OCache` too) — but it is written
  down as a decision rather than left as a gap (D-092).

## Context files

- `specs/design/07-ui-and-drawing.md` — specified `hafen.ui.gobOverlay`; this supersedes §"World-space overlays over gobs"
- `specs/design/17-custom-rendering.md` — specified `follow=`/`:follow`; this supersedes it
- `specs/codebase/state.md` — `Gob`, `OCache`, `GAttrib`, the attrib-map gotcha · `world-3d.md` — the 3D scene, client-only gobs, billboards
- `src/haven/Gob.java` — `ols` (:42), `Overlay` (:49, removal at :118), `addol` (:525/:534), `findol` (:553) — the two `// addon:` seams
- `src/io/brodgar/addon/LuaGobOverlay.java` — the `PView.Render2D` attrib, kept and re-fronted
- `src/io/brodgar/addon/FollowMoving.java` — the world-space anchor, kept and re-fronted
- `src/io/brodgar/addon/UiApi.java` — `newGobOverlay`/`sweepGobOverlays`/`paintGobOverlays`, the code being moved out
- `src/io/brodgar/addon/RenderApi.java` — `follow=`, `:follow`, `:offset`, `followTargetId`, `applyEntityFollow`
- `src/io/brodgar/addon/LuaGob.java` — where the verb lands; `overlays()` at :265, the resolver at :75
- `src/io/brodgar/addon/AddonManager.java` — `GobOverlay` (:1889), the filter test (:1648), the sweep (:419), and `fireGob`'s `hasSub` pattern for the new events
- `src/io/brodgar/addon/Addon.java` — `gobOverlays` (:51), the owned list that is deleted outright
- `src/io/brodgar/addon/GhostGob.java` — why a ghost is not addressable as a Gob (:81, id `-1`, never in `OCache`)
- `docs/addons/api/gob.md` — gains the verb · `ui/custom.md` — "Overlays", losing half of itself
- `docs/addons/api/render/sprites.md`, `render/models.md`, `ghost.md` — lose `follow=`/`:follow`; `events.md` gains two rows
- `specs/017-gob-oop/` — the Gob entity, its interning and the hard-cut precedent
- `specs/011-virtual-entities/` — `hafen.ghost`, the handle whose `follow` is absorbed
