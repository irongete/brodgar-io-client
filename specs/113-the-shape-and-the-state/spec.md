# 113 — the shape and the state

## What and why

A Gob answers where it is, what it is called and how hurt it is. It does not answer what it *is*
underneath its picture, and the client holds both halves of that.

**`gob:hitbox()` — the ground the object stands on.** Every resource that blocks movement carries an
`obst` layer, a set of polygons in world units, and the engine rotates it by the object's facing to
place it on the terrain. Nothing in the API reaches it, so an addon cannot draw a footprint, ask
whether a place is inside one, or tell a boulder's size from a pebble's.

**`gob:sdt()` — the state bytes the server sent with the object's resource.** Their meaning belongs
to that resource's own published code — a crop's stage, a gate's leaf, a stockpile's count — and the
client never decodes them, so the honest read is the bytes. The nearest thing today is
`GobInfo.overlays`, which names what is *drawn on* an object rather than what state it is in.

**`GobSdtChanged` — because the state is what changes.** The bytes are the one thing about an object
that moves while the object stands still, so a read without an edge is a read taken every frame. The
client knows the moment: the server's `OD_RES` delta is where the bytes are replaced.

Both reads are of the **server's** object, so both follow the page's rule for which character
computes an answer: the one on screen when it can see the object, otherwise whichever can.

## Acceptance criteria

1. `gob:hitbox()` answers an array of polygons, each an array of Positions in the world, rotated by
   the object's facing and anchored at its place; `nil` for a gob that is gone, whose resource has
   not resolved, or which carries no collision footprint.
2. Every point is a real place: `p:x()`, `p:distance()` and `s:world():worldToScreen(p)` all answer,
   and the polygon sits on its object rather than at the frame's origin.
3. `gob:scale(k)` does not move a hitbox — the drawn size changes, the footprint does not, and the
   page says so where it already claims it.
4. `gob:sdt()` answers a 1-based array of `0..255` numbers for a gob whose drawable carries state
   bytes, the empty array for one that carries none, and `nil` for a gob that is gone or whose body
   is composed rather than resource-drawn.
5. `gob:info()` carries the bytes under `sdt` and carries **no** hitbox — a snapshot holds no
   objects — and both pages say which it is.
6. Each verb refuses an argument, naming arity as the rule it broke.
7. `GobSdtChanged` fires when a gob's bytes change, **without a sweep of any kind**: the client
   pushes it from the delta. Its payload answers `:gob()` and `:sdt()`, the bytes that change made.
8. It fires **once per object** however many characters see it, and it fires for the first state a
   gob is given — so a handler never waits out a loading resource by polling.
9. `docs/client/` gains the page that maps what a `.res` carries and how a gob reads it.

## Out of scope

- **A named obstacle other than the collision one.** A resource may carry several `obst` layers under
  ids of its own. Reading one by name is a second verb with a second vocabulary, and the collision
  footprint stands whole without it.
- **Decoding an sdt into meaning.** The decoder lives in the resource's published code, so a
  `gob:cropStage()` would be this layer guessing.
- **Writing either.** Neither is client-local state; both are what the server said.
- **Drawing a polygon in the world.** A hitbox hands back Positions, and projecting them is
  `s:world():worldToScreen(p)` plus the `g:` verbs, which both already ship.
- **An edge for the footprint.** A hitbox changes whenever the object turns or moves, which is every
  frame for anything that walks. The state has one moment and the shape has none.

## Docs impact

Pages written: `docs/addons/api/gob.md`, `.../types/world.md`, `.../event/bus/world.md`,
`.../event/bus/README.md`, and a new `docs/client/resources.md` plus its index row.

Derived impact set —
`grep -rniE "hitbox|\bsdt\b|state data|collision|footprint|obstacle|crop stage|growth stage" docs/`:

| Hit | Why it is in the set |
|---|---|
| `api/gob.md` "its footprint, what it collides with" | the scale section's claim, now a name the reader can read back |
| `api/overlay.md` "a crop's growth stage" | calls the stage an **overlay** — the last copy of a claim `gob:sdt()` settles |
| `api/vr/ghosts.md` "around its own footprint" | a scaling pivot, not a collision box — discharge |
| `api/font.md`, `api/client/keybindings.md`, `guides/hotkeys-and-commands.md` "collision" | name clashes — discharge |

## Context files

- `src/io/brodgar/addon/LuaGob.java` — 1, 2
- `src/io/brodgar/addon/AddonManager.java` (`gobName`, `gobSnapshot`, `anygob`, `gobUser`; for 3 also
  `BUS_KEYS`, `gobOverlayCame`, `fireGobOverlay`, `drainOverlayEvents`, `nativeEdge`) — 1, 2, 3
- `src/io/brodgar/addon/Args.java`, `Refusal.java` — 1, 2 · `LuaEvent.java`, `Subs.java` — 3
- `src/io/brodgar/addon/LuaPosition.java` — 2
- `src/haven/AddonWidgets.java` — 1
- `src/haven/ResDrawable.java`, `MessageBuf.java`, `Message.java` — 1, 3
- `src/haven/OCache.java` (`ObjDelta.apply`) — 1, 3
- `src/haven/Gob.java` (the `// addon:` overlay seams — 3; `BasePlace` — 2) — 2, 3
- `src/haven/Resource.java` (`Obstacle`, `layer(Class, id)`) — 2
- `docs/client/resources.md` — born by 1 (layer/flayer, the `id == null` gotcha, the `OD_RES` path);
  2 extends it with the `obst` half
- `docs/addons/api/gob.md` — 1, 2, 3
- `docs/addons/api/types/world.md`, `.../overlay.md` — 1
- `docs/addons/api/event/bus/world.md`, `.../bus/README.md`, `.../event/README.md`,
  `docs/addons/guides/events-and-timers.md` — 3
- `docs/addons/api/shapes.md`, `.../position.md`, `.../world.md`, `.../vr/ghosts.md`, `.../font.md`,
  `.../client/keybindings.md`, `docs/addons/guides/hotkeys-and-commands.md` — 2
- `docs/client/README.md`, `.../services.md`, `.../state.md` — 1, 2, 3
- `addons/109-the-frame-a-session-is-in.2/` (a live suite's shape), `DOCUMENTATION.md` — 1, 2, 3
