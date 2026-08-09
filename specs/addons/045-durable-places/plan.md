# 045 — Plan

> **[044-spatial-ui](../044-spatial-ui/) 044.9 is what this extends** — the ground rule, its drain and
> `<entity>:drawn()`. See [spec.md](spec.md) for what and why. 044 is closed at 9 of 9, so this feature is
> the only thing in flight on the shared entity core.

## The change, in one paragraph

A free entity's place today is `LuaWorldEntity.rc`, a **session** coordinate, and every session coordinate is
re-based whenever the server drops the map (`MCache.invalblob` type 2 → `trimall`, then the same ground
streams back under different grid coords — entering a cave or a house, and coming back). The entity holds a
number that has stopped naming anywhere. So the entity holds the **anchor** instead — the grid id plus the
offset inside it, which is the server's own and never moves (D-096) — and `rc` becomes a **derived cache**
recomputed from it, exactly as `LuaPosition` already derives one form from the other. Both forms already live
in one type (039), so nothing is invented; what changes is which of the two the entity keeps.

**The common case does not change at all.** Ground you can see is ground that is streamed, and a streamed
grid answers its own id (`AddonWidgets.loadedGrid`), so a ghost placed where you stand or where you clicked is
durable already. What changes is the two edges, and they swap: a place **recorded in another segment** is
accepted today by `:add`/`:position` and refused tomorrow only if it is not durable at all, while a raw
coordinate over **never-visited** ground is accepted today and refused tomorrow.

## Approach

**The anchor lands beside `rc`, and `rc` becomes nullable.** `LuaWorldEntity` gains `anchorGrid` + `agx/agy`
(free entities only; an anchored one's place is its gob's and is untouched). `rc` is the derived session
coordinate **or `null`** while this session cannot locate that grid — which is exactly the state a walk into a
cave puts every overworld entity in. `null` is safe because `grounded` is false whenever `rc` is, so `shows()`
is false, so no scene path ever dereferences it: an entity with no coordinate has no gob yet either, and the
create is what runs when the place resolves.

**The argument door swaps one refusal for another.** `LuaPosition.worldArg` (refuses a place with no *world*
coordinate) becomes a new `LuaPosition.anchorArg` (refuses a place with no *durable* form) at exactly two call
sites — `VrApi.anchorArg`'s Position branch and `<entity>:position(p)`. The other eight `worldArg` sites
(`hafen.act():moveTo`/`place`/`select`/`useItemOn`, `marker():add`, `snapPlace`, `worldToScreen`) genuinely
need a coordinate here and now and keep it. That two-against-eight split is criterion 4's "nothing else moves".

**Re-derivation rides events, never a sweep.** Two, and both are mutation points the client already has:
1. **the terrain's cut map** — 044.9's `groundChanged` → `drainGround`, which already walks the `free` list on
   the addon tick. `reground` gains one step in front of its ground test: re-derive `rc` from the anchor.
   `trimall` drops every cut, so the *loss* of a coordinate already fires here, as does its return.
2. **`sessloc` itself** — the anchor→world derivation runs through `MiniMap.sessloc` (`MapApi.gridUL`), which
   `MiniMap.tick` re-resolves and which can land a frame or more **after** the cuts do. Without a second tap,
   an entity whose ground came back while the player stands still would wait for the next cut change that may
   never come. So one `// addon:` line where `sessloc` is assigned, **guarded by an equality test on
   `(seg, tc)`** — `resolve` mints a new `Location` every frame, and an unguarded notify would be the per-frame
   poll 042 deleted. Flag only, drained on the addon tick, into the same drain (D-106).

**An unreachable anchor waits, and nothing bounds the wait.** No `Resolve` chain: 042.12's lesson is that a
retry chain wants one stable blocker, and "the player has not walked there" is not one. The entity sits in
`free` with `rc == null` and is re-asked on the two events above — silently, and forever.

**`<entity>:position()` answers the anchor form for a free entity** (the world form for an anchored one, whose
place is its gob's). That is what makes criterion 1's asymmetry literal: `:info()` reads the same grid and
offset across the walk while `:x()` is free to differ, and while in the cave `:x()` is simply `nil`.

## Files to create / modify

- `src/io/brodgar/addon/LuaWorldEntity.java` — the anchor fields beside `rc`; `rc` documented as derived and
  nullable, with `grounded`'s invariant (`rc == null ⇒ !grounded`) stated where `grounded` is.
- `src/io/brodgar/addon/LuaPosition.java` — `anchorArg` beside `worldArg` (required-durable, refusing by
  naming why a raw coordinate on unrecorded ground cannot be held); `Anchor` reachable from `VrApi`.
- `src/io/brodgar/addon/VrApi.java` — the create path (build the gob when the place resolves, not at `:add`),
  `moveEntity`, `reground` + the second drain source, `entityPosition`, and `anchorArg`'s Position branch.
- `src/io/brodgar/addon/AddonManager.java` — the `sessionRebased()` hub beside `groundChanged()`.
- `src/haven/MiniMap.java` — **one `// addon:` line**, guarded, at `tick`'s `sessloc = resolve(sesslocator)`.
- `docs/addons/api/vr/README.md` — the place contract; `docs/addons/api/world.md` — the corrected sentence.
- `specs/codebase/minimap.md` — extend: `sessloc`'s assignment is the coordinate space's mutation point (and
  the new `// addon:` seam); `specs/codebase/state.md` — extend: `invalblob` type 2 → `trimall` is what
  re-bases the session coordinate space, which nothing in that file says today.
- `specs/addons/decisions/virtual-entities.md` — this feature's decisions; `addons/045-durable-places.<X>/`.

## Risks & gotchas

- **A scene add is a map read at the gob's CURRENT point** (044.9, `learnings/ghosts.md`): the new
  build-on-resolve path must `gob.move` before `addClientGob`, like every other attach.
- **The two grid-UL derivations agree to the millimetre** (039.2, `learnings/world-reads.md`) — that is what
  makes "the same offset before and after" an assertion rather than a rounding hope. If it ever did not,
  `p:info()` would already flip its offsets as ground streams in and out.
- **`MapApi.gridUL` answers only for the segment the character is standing in.** In a cave every overworld
  anchor is unresolvable — the desired behaviour, and the whole reason the walk *back* needs the `sessloc` tap.
- **`Loading` is everywhere on the `MapFile` path** and is a `RuntimeException` — catch broadly at the drain.
- **Two things are called an anchor in this feature**: `VrApi.Anchor` (what an entity is attached to) and
  `LuaPosition.Anchor` (a durable place). Keep the fields plain (`anchorGrid`/`agx`/`agy`); do not import.
- **`ant hafen-client` is incremental** — `rm -rf build/classes` for a true compile check; Java changes need a
  full client restart.

## Discarded alternatives

- Re-base every entity's session coordinate on the map-invalidate event — there is nothing to re-base *from*
  once the grids are gone; the anchor is the only thing that survives the drop.
- Keep both forms and prefer the world one while it is "still valid" — two sources of truth, and *valid* has
  no test.
- Retry unresolved anchors on every addon tick — a poll, which 042 deleted; the mutation point is available.
- Resolve an anchor through its **segment** rather than its own grid (spec: out of scope).
- Default an unresolvable place to the player's position, or accept it and place it at the origin — D-127:
  an invented place is a wrong place, and the origin raises.
- Warn instead of refusing a non-durable place — the spec's hard cut; a silent entity pinned to a number that
  will lie is the failure this feature exists to delete.
