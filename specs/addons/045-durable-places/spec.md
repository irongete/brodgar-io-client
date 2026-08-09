# 045-durable-places — Spec

## What & why

A thing you stand at a **point** should be at that point tomorrow, and after a cave, the way a
window pinned to a wall in a VR headset is still on that wall when you put the headset back on.
Today it is not: a free `hafen.vr()` entity keeps the **session coordinate** it was given, and
that coordinate space is **re-based whenever the map is invalidated** — walk into a cave or a
house and the same numbers name different ground. The entity goes on holding a number that has
quietly stopped meaning anywhere. (Verified in-game after 044.9: walk into a cave, come back,
and the thing is not where it was put.)

The fix is one change with three consequences: **a free entity's place becomes its durable
anchor** — the server's grid id plus the offset inside that grid — and the session coordinate
becomes a derived cache the layer recomputes instead of trusting. The two forms are already one
type (`Position`, 039), so nothing new is invented; what changes is which of the two the entity
holds on to.

## Acceptance criteria

- [ ] **A place survives a map change.** Stand a ghost at a point, walk into a cave or a house,
      come back: it is standing where it was put. `e:position():info()` reads the same
      `gridId` and offset before and after, while `e:position():x()` is free to differ — that
      asymmetry IS the contract, and it is what the pages say.
- [ ] **A place you have not reached is legal.** `hafen.vr():ghost():add(res, p)` with a
      Position rebuilt from a grid id this session has never loaded no longer raises. The
      entity exists, answers `:position():info()` with the grid it was given, and reports
      `:drawn()` false; when that ground is drawn it stands there with nothing else done to it.
      An unknown grid id simply waits, silently and forever, rather than erroring.
- [ ] **A place with no durable form at all is refused**, at `:add` and at `:position(p)`,
      naming why — a raw coordinate on ground no client has recorded cannot be held, and today
      it silently becomes an entity pinned to a number that will lie. Hard cut (no deprecation);
      it changes one line of 044.9's own archived suite, which is stated rather than discovered.
- [ ] **Nothing else moves.** A gob-anchored entity is untouched (its place is the gob's).
      044.9's rule is unchanged: no coordinate, or ground not drawn, means not in the scene and
      `:drawn()` false. Culling, the four kinds, and every other verb read the same.
- [ ] **The pages say it.** `api/vr/README.md` carries the contract above; `api/world.md`'s
      "raw world coordinates reset each login" is **corrected** — they are re-based mid-session
      too, which is the sentence a reader would build the wrong model on.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## Out of scope

- **Cross-session persistence.** Saving *which* entity, *what* it draws and *whether* you still
  want it is the addon's own business through `hafen.store`, which already round-trips a
  Position with no conversion at either end (`planner` does exactly this). A convenience verb on
  `hafen.vr()` that saves and re-places for you is a separate feature, and it would be wrong to
  build it before places stop lying.
- **Gob-anchored entities**, which have no place of their own.
- **Making a durable form out of unexplored ground.** A grid id comes from the server or from
  the recorded map; the client cannot invent one, so ground nobody has recorded stays
  un-anchorable — that is the refusal above, not a gap to close later.
- Resolving a durable anchor through its **segment** rather than through its own grid (one
  loaded grid in a segment fixes every other one's coordinate). A real improvement, a separate
  subject, and unnecessary for any criterion here.
- World-space text and world-space shapes, still on the ROADMAP.

## Context files

- `specs/addons/044-spatial-ui/` — the immediately prior feature; **044.9 is what this extends**
  (the ground rule, its drain, and `<entity>:drawn()`)
- `specs/addons/decisions/virtual-entities.md` — **D-206** (the ground rule) and **D-127** (a
  thing that cannot exist without a place takes it on the constructor — the refusal this re-aims)
- `specs/addons/design/25-uniform-api.md` §Position — the two forms, and why one type carries both
- `specs/addons/design/16-virtual-entities.md` — the client-only entity core this lands on
- `src/io/brodgar/addon/LuaPosition.java` — `Anchor`, `world()`, `anchor()`, `ulOf`, `worldArg`
- `src/io/brodgar/addon/VrApi.java` — the entity core, `moveEntity`, and 044.9's ground drain
- `src/io/brodgar/addon/LuaWorldEntity.java` — where the anchor lands beside `rc`
- `src/haven/MCache.java` — `trimall`/`invalblob`: the moment the coordinate space is re-based
- `specs/codebase/world-3d.md`, `specs/codebase/mapfile.md` — the terrain cut map (044.9's
  signal) and the grid-id → coordinate path
- `docs/addons/api/vr/README.md`, `docs/addons/api/world.md` — the two pages that change
