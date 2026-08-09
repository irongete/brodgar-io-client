# 046-gob-scale — Spec

## What & why

A **native** gob cannot be resized. Every `hafen.vr()` entity answers `:scale`
([design/16](../design/16-virtual-entities.md), D-194) — a ghost, a sprite, an object, a standing widget —
but the things the *game* puts in the world are the ones an addon most wants to make bigger: the herb you
keep walking past, the boar you want to see coming, the cupboard you are lining up. This feature gives the
Gob handle the same verb, with the same meaning, as its virtual siblings: `gob:scale(1.5)`.

It is **client-local and purely visual** — the same footing as `gob:overlay()`, which the Gob page already
states is ungated because what you paint at a gob changes nothing the server, the client or another addon
owns. Nothing goes on the wire. It is the **first write** on a handle that has been read-only, so the page
has to say that in its own voice.

**The scale ends with the loaded object, by directive.** It lives on the engine's `Gob`, so walking far
enough to unload the object and coming back gives you the original size. That is the contract, not a defect:
an addon that wants it back re-applies it on `GobAdded`, which is a subscription it already has.

## Acceptance criteria

- [ ] `gob:scale()` answers `1` for a gob nobody scaled, and reads back what the last write set.
- [ ] `gob:scale(k)` hands the **Gob** back, so `gob:scale(2):name()` is one chain.
- [ ] The size changes **in the world, live** — no relog, no re-adding the gob — and the change is **in
      place**: the object's feet stay where they were, and it still turns and moves normally.
- [ ] `gob:scale(1)` puts it back exactly as it was.
- [ ] Refusals, each naming the rule: `0`, a negative, a non-finite, and a non-number.
- [ ] On a gob that is **gone**, `:scale()` answers `nil` and `:scale(k)` does nothing and does not throw —
      the Gob page's standing rule (every method answers `nil` once the gob is gone, none of them throws).
- [ ] **The scale is undone when the addon that set it goes away** — `:reload`, or disabling the addon,
      returns every gob it scaled to its original size. Nothing is left distorted by an addon that is no
      longer running.
- [ ] A scaled gob is still **clickable**, and the pick follows the drawn size.
- [ ] It survives an unload only by re-application: a `[manual]` walk out of range and back shows the
      original size, and a `GobAdded` handler that re-applies restores it.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **Non-uniform scale** (`x, y, z`) — the vr siblings take one number and this takes one number.
- **Alpha, tint and visibility on a native gob.** The mechanism this ships would carry them, and that is
  deliberate room, not a promise; each is its own question about writing over what the game drew.
- **Persistence** of any kind: across an unload, a relog, or in `hafen.store()`. Re-applying is the addon's.
- Anything the server can see, and anything that changes what a gob *is* (its hitbox on the server, what it
  collides with, what a click sends).
- Scaling a gob's overlays independently of the gob, and scaling the player's own view.

## Context files

- `src/haven/Gob.java` — `Gob.SetupMod` (:154), `GobState`/`updstate` (:730-756, the per-tick compare that
  makes a change propagate with no new seam), `setattr`/`attrclass` (:611-660), `Placed.Placement` (:857)
- `src/haven/GobHealth.java` — the engine's own `GAttrib implements Gob.SetupMod` with a cached `Pipe.Op`:
  the exact pattern this copies
- `src/io/brodgar/addon/GhostGob.java` — how a scale is applied today (`Location.scale` below the gob's
  translate+rotate) and the two limitations that carry over (`goback("gobx")` resources)
- `src/io/brodgar/addon/LuaGob.java` — the handle the verb lands on (methods table, `gob()`/`handle()`)
- `src/io/brodgar/addon/AddonManager.java` — per-addon teardown, where an addon's scales are undone
- `docs/addons/api/gob.md` — the page that gains its first write section (256 lines of a 300 ceiling)
- `docs/addons/api/vr/README.md` — the shared `:scale` wording this must agree with
- `specs/codebase/world-3d.md` — the placement/transform coverage this extends with the `SetupMod` seam
- `043-vr-namespace/`, `011-virtual-entities/` — where `:scale` came from and what it means there
